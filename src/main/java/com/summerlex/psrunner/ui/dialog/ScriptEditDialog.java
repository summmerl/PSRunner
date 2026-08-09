package com.summerlex.psrunner.ui.dialog;

import com.summerlex.psrunner.data.ScriptConfig;
import com.intellij.openapi.ui.DialogWrapper;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * 二级编辑对话框（决策 6 / 9）：名称 / 命令（多行）/ 启用。
 * 命令编辑框含 $PSScriptRoot 语义灰字提示（决策 6）。
 */
public class ScriptEditDialog extends DialogWrapper {

    private final JTextField nameField = new JTextField();
    private final JTextArea commandArea = new JTextArea(12, 50);
    private final JCheckBox enabledBox = new JCheckBox("启用", true);
    private final ScriptConfig result;

    public ScriptEditDialog(ScriptConfig initial) {
        super(true);
        setTitle(initial.getName() == null || initial.getName().isEmpty() ? "添加脚本" : "编辑脚本");

        nameField.setText(initial.getName());
        commandArea.setText(initial.getCommand());
        enabledBox.setSelected(initial.isEnabled());
        result = initial;

        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("名称:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(nameField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("命令:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        commandArea.setLineWrap(true);
        commandArea.setWrapStyleWord(true);
        panel.add(new JScrollPane(commandArea), gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weighty = 0;
        panel.add(enabledBox, gbc);

        // 决策 6：$PSScriptRoot 语义灰字提示
        gbc.gridx = 1;
        JLabel hint = new JLabel("提示：脚本在临时目录执行；$PSScriptRoot 指向临时目录，相对路径基准 = 选中目录。选中目录请用 {{SelectedDir}}。");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        panel.add(hint, gbc);
        return panel;
    }

    public ScriptConfig getResult() {
        result.setName(nameField.getText().trim());
        result.setCommand(commandArea.getText());
        result.setEnabled(enabledBox.isSelected());
        return result;
    }
}
