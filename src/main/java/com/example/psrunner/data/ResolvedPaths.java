package com.example.psrunner.data;

/**
 * 运行期解析结果 DTO：command 为占位符替换后的最终脚本内容，
 * 其余字段为解析出的原始路径（供结束标记等使用）。
 */
public record ResolvedPaths(String command, String selectedDir, String projectDir, String moduleDir) {
}
