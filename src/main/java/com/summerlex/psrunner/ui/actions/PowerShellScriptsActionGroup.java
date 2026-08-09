package com.summerlex.psrunner.ui.actions;

import com.summerlex.psrunner.data.PowerShellScriptSettings;
import com.summerlex.psrunner.data.ScriptConfig;
import com.summerlex.psrunner.service.TempScriptFileManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.actionSystem.Separator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态 ActionGroup（决策 7 / 8 / 9）：
 * update() 判断选中项为目录时可见 + 惰性清扫；getChildren() 生成启用脚本项 + 分隔线 + "配置脚本…"。
 */
public class PowerShellScriptsActionGroup extends DefaultActionGroup {

    /**
     * 2026.1 硬性要求：update() 读取 CommonDataKeys.VIRTUAL_FILE 必须在后台线程，
     * 否则平台抛 "virtualFile is requested on EDT" SEVERE，导致菜单项被跳过。
     */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile vf = e.getData(CommonDataKeys.VIRTUAL_FILE);
        // 决策 7：首次弹菜单时惰性清扫遗留临时文件
        TempScriptFileManager.sweepStaleTempFilesOnce();
        // 仅在选中的是目录时显示（决策 1 / 契约 §1）
        e.getPresentation().setEnabledAndVisible(vf != null && vf.isDirectory());
    }

    @Override
    public AnAction @NotNull [] getChildren(@Nullable AnActionEvent e) {
        List<AnAction> children = new ArrayList<>();
        PowerShellScriptSettings settings = PowerShellScriptSettings.getInstance();
        for (ScriptConfig script : settings.getState().scripts) {
            if (script.isEnabled()) {
                children.add(new ScriptExecutionAction(script));
            }
        }
        if (!children.isEmpty()) {
            children.add(Separator.getInstance());
        }
        children.add(new ConfigureScriptsAction());
        return children.toArray(AnAction[]::new);
    }
}
