package com.example.psrunner.ui.actions;

import com.example.psrunner.data.ScriptConfig;
import com.example.psrunner.service.ScriptExecutionService;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * 单个脚本的执行动作：从事件取 project 与第一个选中目录，交给 Service（决策 8）。
 */
public class ScriptExecutionAction extends AnAction {

    private final ScriptConfig script;

    public ScriptExecutionAction(@NotNull ScriptConfig script) {
        super(script.getName());
        this.script = script;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile targetDir = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || project.isDisposed()) {
            return;
        }
        if (targetDir == null || !targetDir.isDirectory()) {
            // 防御：事件里没有目录时静默返回（菜单 update 已保证，双保险）
            return;
        }
        ScriptExecutionService.getInstance(project).execute(script, targetDir);
    }
}
