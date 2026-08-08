package com.example.psrunner.data;

import java.time.LocalDateTime;

/**
 * 执行结果三态（决策 5/5.1）：成功 / 失败（带退出码）/ 被用户终止。
 * JDK 17 特性 sealed interface，编译于 JDK 21（2026.1 SDK 要求）。
 */
public sealed interface ExecutionResult
        permits ExecutionResult.Success, ExecutionResult.Failure, ExecutionResult.Terminated {

    record Success(int exitCode, long durationMs, LocalDateTime finishedAt) implements ExecutionResult {
    }

    record Failure(int exitCode, long durationMs, LocalDateTime finishedAt) implements ExecutionResult {
    }

    record Terminated(long durationMs, LocalDateTime finishedAt) implements ExecutionResult {
    }
}
