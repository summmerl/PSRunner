package com.example.psrunner.service;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 临时 .ps1 文件管理（决策 1 / 1.1 / 7）：
 * 创建（UTF-8 BOM + 头部注入）、进程结束清理、首次弹菜单时惰性清扫超时遗留文件。
 * 所有文件系统操作集中在本类。
 */
public final class TempScriptFileManager {

    private static final String PREFIX = "idea_powershell_";
    private static final String SUFFIX = ".ps1";
    private static final long STALE_THRESHOLD_MS = 24L * 60 * 60 * 1000; // 决策 7：24 小时

    private static final AtomicBoolean SWEEP_DONE = new AtomicBoolean(false);

    private TempScriptFileManager() {
    }

    /**
     * 创建临时 .ps1 文件（决策 1.1）：
     * ① 头部固定注入 BOM + 编码设置 + $PSScriptRoot 语义注释（决策 3.1 / 6）；
     * ② UTF-8 with BOM 写出（首字符 U+FEFF，防 PS5.1 按 GBK 解析）。
     */
    public static @NotNull Path create(@NotNull String scriptContent) throws IOException {
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
        Path file = Files.createTempFile(tempDir, PREFIX, SUFFIX);
        String header = "# 脚本在临时目录执行: " + tempDir.toAbsolutePath() + System.lineSeparator()
                + "# $PSScriptRoot / $PSCommandPath = 临时目录；相对路径基准 = 选中目录；引用选中目录请用 {{SelectedDir}}"
                + System.lineSeparator()
                + "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8" + System.lineSeparator()
                + "$OutputEncoding = [System.Text.Encoding]::UTF8" + System.lineSeparator()
                + "[Console]::InputEncoding = [System.Text.Encoding]::UTF8" + System.lineSeparator()
                + System.lineSeparator();
        // BOM 写入：UTF-8 with BOM = 首字符 U+FEFF（编码后为 EF BB BF）
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = (header + scriptContent + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, content, 0, bom.length);
        System.arraycopy(body, 0, content, bom.length, body.length);
        Files.write(file, content);
        return file;
    }

    /** 进程结束后无条件删除临时文件（决策 1）。 */
    public static void delete(@NotNull Path tempFile) {
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException ignored) {
            // 清理失败不阻断主流程，留给惰性清扫兜底
        }
    }

    /**
     * 决策 7：惰性清扫超过 24 小时的遗留临时文件，整个插件生命周期只执行一次。
     * 不抛异常（清扫失败静默，不干扰菜单）。
     */
    public static void sweepStaleTempFilesOnce() {
        if (!SWEEP_DONE.compareAndSet(false, true)) {
            return;
        }
        try {
            Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
            if (!Files.isDirectory(tempDir)) {
                return;
            }
            long now = System.currentTimeMillis();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(tempDir, PREFIX + "*" + SUFFIX)) {
                for (Path p : stream) {
                    try {
                        Instant modified = Files.getLastModifiedTime(p).toInstant();
                        if (now - modified.toEpochMilli() > STALE_THRESHOLD_MS) {
                            Files.deleteIfExists(p);
                        }
                    } catch (IOException ignored) {
                        // 单个文件失败不影响其他文件
                    }
                }
            }
        } catch (IOException ignored) {
            // 目录不可达等整体失败静默
        }
    }
}
