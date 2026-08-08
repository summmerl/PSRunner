package com.example.psrunner.data;

import com.intellij.util.xmlb.annotations.Attribute;
import org.jetbrains.annotations.NotNull;

/**
 * 单个脚本配置实体（决策 1/5.1 ②）。
 * 通过 getter 上的 {@link Attribute} 注解做 XML 序列化。
 */
public class ScriptConfig {

    private String name = "";
    private String command = "";
    private boolean enabled = true;
    /** 决策 5.1 ② 预留：是否递归杀子进程（本期仅存储，不暴露 UI）。 */
    private boolean killProcessTree = true;

    @Attribute("name")
    public @NotNull String getName() {
        return name;
    }

    public void setName(@NotNull String name) {
        this.name = name;
    }

    @Attribute("command")
    public @NotNull String getCommand() {
        return command;
    }

    public void setCommand(@NotNull String command) {
        this.command = command;
    }

    @Attribute("enabled")
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Attribute("killProcessTree")
    public boolean isKillProcessTree() {
        return killProcessTree;
    }

    public void setKillProcessTree(boolean killProcessTree) {
        this.killProcessTree = killProcessTree;
    }
}
