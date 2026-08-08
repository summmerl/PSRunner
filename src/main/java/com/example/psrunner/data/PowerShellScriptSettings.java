package com.example.psrunner.data;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * app 级全局脚本配置（所有项目共用）。
 * 序列化文件名采用平台默认机制，按 PRD 建议命名为 powerShellScriptConfig.xml。
 */
@State(name = "PowerShellScriptConfig", storages = @Storage("powerShellScriptConfig.xml"))
public final class PowerShellScriptSettings
        implements PersistentStateComponent<PowerShellScriptSettings.State> {

    public static final class State {
        public List<ScriptConfig> scripts = new ArrayList<>();
    }

    private State state = new State();

    public static PowerShellScriptSettings getInstance() {
        return ApplicationManager.getApplication().getService(PowerShellScriptSettings.class);
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        if (state.scripts == null) {
            state.scripts = new ArrayList<>();
        }
        this.state = state;
    }
}
