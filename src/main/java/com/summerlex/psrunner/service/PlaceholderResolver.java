package com.summerlex.psrunner.service;

import com.summerlex.psrunner.data.ResolvedPaths;
import com.summerlex.psrunner.data.ScriptConfig;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * 占位符强制解析（决策 4 / 4.1）。
 * 只走 module 结构 + ProjectRootManager，不触索引。
 */
public final class PlaceholderResolver {

    public static final String SELECTED_DIR = "{{SelectedDir}}";
    public static final String PROJECT_DIR = "{{ProjectDir}}";
    public static final String MODULE_DIR = "{{ModuleDir}}";

    public @NotNull ResolvedPaths resolve(@NotNull Project project,
                                          @NotNull VirtualFile targetDir,
                                          @NotNull ScriptConfig script)
            throws PlaceholderResolutionException {
        String selectedDir = targetDir.getPath();
        String projectDir = project.getBasePath();
        if (projectDir == null) {
            throw new PlaceholderResolutionException("无法解析 {{ProjectDir}}：项目根路径为空。");
        }
        String moduleDir = findModuleContentRoot(project, targetDir);

        String command = script.getCommand();
        command = command.replace(SELECTED_DIR, quote(selectedDir));
        command = command.replace(PROJECT_DIR, quote(projectDir));
        if (moduleDir != null) {
            command = command.replace(MODULE_DIR, quote(moduleDir));
        }

        if (command.contains("{{")) {
            throw new PlaceholderResolutionException(
                    "脚本命令中仍残留未解析的占位符 {{...}}，已拦截执行。请检查拼写是否正确。");
        }
        return new ResolvedPaths(command, selectedDir, projectDir, moduleDir);
    }

    /** 决策 4.1：替换值以单引号字面量落地，值内单引号翻倍转义。 */
    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * 经 ModuleUtilCore 查「包含 targetDir 的内容根」（决策 4）。
     * 目录不属于任何模块则抛异常拦截。
     */
    private static String findModuleContentRoot(@NotNull Project project,
                                                @NotNull VirtualFile targetDir)
            throws PlaceholderResolutionException {
        Module module = ModuleUtilCore.findModuleForFile(targetDir, project);
        if (module == null) {
            throw new PlaceholderResolutionException(
                    "无法解析 {{ModuleDir}}：选中目录不属于任何模块。\n" + targetDir.getPath());
        }
        return Arrays.stream(ModuleRootManager.getInstance(module).getContentRoots())
                .map(VirtualFile::getPath)
                .max(java.util.Comparator.comparingInt(String::length))
                .orElseThrow(() -> new PlaceholderResolutionException(
                        "无法解析 {{ModuleDir}}：模块没有内容根。\n" + module.getName()));
    }
}
