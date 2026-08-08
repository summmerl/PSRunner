package com.example.psrunner.service;

import com.example.psrunner.data.ResolvedPaths;
import com.example.psrunner.data.ScriptConfig;
import com.example.psrunner.ui.run.RunTabNamer;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.KillableProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * 执行编排 Service（决策 1~9 落地）。project 级 Service。
 * 后台线程走 executeOnPooledThread，UI 操作统一切回 EDT（防崩溃原则，契约 §4）。
 */
public final class ScriptExecutionService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Project project;

    public ScriptExecutionService(@NotNull Project project) {
        this.project = project;
    }

    public static ScriptExecutionService getInstance(@NotNull Project project) {
        return project.getService(ScriptExecutionService.class);
    }

    public void execute(@NotNull ScriptConfig script, @NotNull VirtualFile targetDir) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> runExecution(script, targetDir));
    }

    private void runExecution(@NotNull ScriptConfig script, @NotNull VirtualFile targetDir) {
        long startNanos = System.nanoTime();

        // ── 1. 占位符强制解析（决策 4 / 4.1）：ReadAction 内，PCE 抛出点 ① ──
        ResolvedPaths paths;
        try {
            paths = ReadAction.compute(() -> {
                try {
                    return new PlaceholderResolver().resolve(project, targetDir, script);
                } catch (PlaceholderResolutionException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (ProcessCanceledException e) {
            // 决策异常地图 ①：项目关闭等放弃场景，静默终止
            return;
        } catch (RuntimeException e) {
            if (e.getCause() instanceof PlaceholderResolutionException pre) {
                // 决策 4：弹错误对话框，拦截执行
                ApplicationManager.getApplication().invokeLater(() ->
                        Messages.showErrorDialog(pre.getMessage(), "占位符解析失败"));
                return;
            }
            throw e;
        }

        // ── 2. 写临时文件（决策 1 / 1.1） ──
        final Path tempFile;
        try {
            tempFile = TempScriptFileManager.create(paths.command());
        } catch (IOException e) {
            // 决策异常地图 ③：JVM 侧异常，不崩溃
            ApplicationManager.getApplication().invokeLater(() ->
                    Messages.showErrorDialog("无法创建临时脚本文件: " + e.getMessage(), "脚本执行失败"));
            return;
        }

        // ── 3. 构造命令（决策 1 增补 / 3.1） ──
        GeneralCommandLine cmd = new GeneralCommandLine("powershell.exe");
        cmd.addParameter("-NoProfile");
        cmd.addParameter("-ExecutionPolicy");
        cmd.addParameter("Bypass");
        cmd.addParameter("-File");
        cmd.addParameter(tempFile.toAbsolutePath().toString());
        cmd.setCharset(StandardCharsets.UTF_8);            // 决策 3.1：Java 端以 UTF-8 解码
        cmd.withWorkDirectory(targetDir.getPath());        // 工作目录 = 选中目录

        // ── 4. EDT 接线 Run 窗口 ──
        ApplicationManager.getApplication().invokeLater(
                () -> showInRunWindow(script, cmd, targetDir.getPath(), startNanos, tempFile),
                ModalityState.NON_MODAL);
    }

    private void showInRunWindow(@NotNull ScriptConfig script,
                                 @NotNull GeneralCommandLine cmd,
                                 @NotNull String targetDirPath,
                                 long startNanos,
                                 @NotNull Path tempFile) {
        KillableProcessHandler handler;
        try {
            handler = new KillableProcessHandler(cmd);
        } catch (ExecutionException e) {
            // 决策异常地图 ③
            Messages.showErrorDialog("无法启动 powershell.exe: " + e.getMessage(), "脚本执行失败");
            return;
        }
        handler.setShouldDestroyProcessRecursively(true);  // 决策 5.1 ②：递归杀子进程

        // 2026.1：ConsoleView 用 TextConsoleBuilderFactory 创建（ConsoleViewUtil.createConsoleView 已移除）
        ConsoleView console = TextConsoleBuilderFactory.getInstance()
                .createBuilder(project).getConsole();

        // 决策 2：唯一标签名
        String baseName = script.getName();
        String runName = RunTabNamer.uniqueName(baseName, project);

        console.attachToProcess(handler);

        // 2026.1：RunContentDescriptor 用构造器构建（builder 已移除）
        RunContentDescriptor descriptor = new RunContentDescriptor(console, handler,
                console.getComponent(), runName);
        // 需验证 ②：RunContentManager 若已迁移 service-locator，改用 project.getService
        RunContentManager.getInstance(project).showRunContent(
                DefaultRunExecutor.getRunExecutorInstance(), descriptor);

        // 决策 5.1 ①：判断「被用户终止」用 handler.isProcessTerminating()
        // （processWillTerminate 的 willBeDestroyed 参数在进程自然退出时也为 true，不可用于区分用户停止）
        handler.addProcessListener(new ProcessListener() {
            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                boolean userStopped = handler.isProcessTerminating();
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                LocalDateTime finishedAt = LocalDateTime.now();
                appendEndMarker(console, event, userStopped, durationMs, finishedAt, targetDirPath);
                // 决策 1.1：无论成败删除临时文件
                TempScriptFileManager.delete(tempFile);
            }
        });

        handler.startNotify();
    }

    private void appendEndMarker(@NotNull ConsoleView console,
                                 @NotNull ProcessEvent event,
                                 boolean userStopped,
                                 long durationMs,
                                 @NotNull LocalDateTime finishedAt,
                                 @NotNull String targetDirPath) {
        // 决策 8：多选时仅对第一个选中目录执行（v1 行为）
        console.print("仅对第一个选中目录执行: " + targetDirPath + "\n", ConsoleViewContentType.NORMAL_OUTPUT);
        // 决策 5 / 5.1 ①：先判「被用户终止」再读退出码
        if (userStopped) {
            console.print("被用户终止", ConsoleViewContentType.SYSTEM_OUTPUT);
        } else {
            int exitCode = event.getExitCode();
            if (exitCode == 0) {
                console.print("成功", ConsoleViewContentType.NORMAL_OUTPUT);
            } else {
                console.print("失败 (exit code: " + exitCode + ")", ConsoleViewContentType.ERROR_OUTPUT);
            }
        }
        console.print("（耗时 " + formatDuration(durationMs) + "，完成于 "
                + finishedAt.format(TIME_FMT) + "）\n", ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    /** 耗时精确到 0.1 秒（决策 5）。 */
    private static String formatDuration(long durationMs) {
        long tenths = durationMs / 100;
        return (tenths / 10) + "." + (tenths % 10) + "s";
    }
}
