package com.summerlex.psrunner.ui.actions;

import com.summerlex.psrunner.ui.dialog.ScriptConfigDialog;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 打开配置对话框的动作。
 */
public class ConfigureScriptsAction extends AnAction {

    public ConfigureScriptsAction() {
        super("配置脚本…");
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        ScriptConfigDialog dialog = new ScriptConfigDialog();
        dialog.show();
    }
}
