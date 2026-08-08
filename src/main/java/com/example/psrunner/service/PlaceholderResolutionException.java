package com.example.psrunner.service;

/**
 * 占位符解析失败异常（决策 4）：弹错误对话框拦截执行，绝不把字面量带入脚本。
 */
public class PlaceholderResolutionException extends Exception {

    public PlaceholderResolutionException(String message) {
        super(message);
    }
}
