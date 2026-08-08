package com.example.psrunner.ui.dialog;

import com.example.psrunner.data.PowerShellScriptSettings;
import com.example.psrunner.data.ScriptConfig;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 配置脚本对话框（决策 9）：JBTable 列表 + 添加/删除/编辑按钮；删除需确认；保存校验名称。
 */
public class ScriptConfigDialog extends DialogWrapper {

    private final JBTable table;
    private final ScriptTableModel model;
    private final List<ScriptConfig> workingCopy;

    public ScriptConfigDialog() {
        super(true);
        setTitle("PowerShell 脚本配置");

        PowerShellScriptSettings settings = PowerShellScriptSettings.getInstance();
        workingCopy = new ArrayList<>();
        for (ScriptConfig s : settings.getState().scripts) {
            workingCopy.add(copy(s));
        }
        model = new ScriptTableModel(workingCopy);
        table = new JBTable(model);
        table.getColumnModel().getColumn(0).setPreferredWidth(200);
        table.getColumnModel().getColumn(1).setPreferredWidth(50);

        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));

        JBScrollPane scroll = new JBScrollPane(table);
        panel.add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(0, 1, 4, 4));
        JButton addBtn = new JButton("添加");
        JButton editBtn = new JButton("编辑");
        JButton delBtn = new JButton("删除");
        addBtn.addActionListener(e -> addScript());
        editBtn.addActionListener(e -> editSelected());
        delBtn.addActionListener(e -> deleteSelected());
        buttons.add(addBtn);
        buttons.add(editBtn);
        buttons.add(delBtn);
        panel.add(buttons, BorderLayout.EAST);
        return panel;
    }

    private void addScript() {
        ScriptEditDialog dialog = new ScriptEditDialog(new ScriptConfig());
        if (dialog.showAndGet()) {
            ScriptConfig cfg = dialog.getResult();
            if (!validateName(cfg.getName(), -1)) {
                return;
            }
            workingCopy.add(cfg);
            model.fireTableDataChanged();
        }
    }

    private void editSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            Messages.showInfoMessage("请先选择一个脚本", "提示");
            return;
        }
        ScriptConfig original = workingCopy.get(row);
        ScriptEditDialog dialog = new ScriptEditDialog(copy(original));
        if (dialog.showAndGet()) {
            ScriptConfig edited = dialog.getResult();
            if (!validateName(edited.getName(), row)) {
                return;
            }
            workingCopy.set(row, edited);
            model.fireTableDataChanged();
        }
    }

    private void deleteSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            Messages.showInfoMessage("请先选择一个脚本", "提示");
            return;
        }
        // 删除需确认（spec「删除脚本需确认」场景）
        int answer = Messages.showYesNoDialog("确定要删除脚本「" + workingCopy.get(row).getName() + "」吗？",
                "删除脚本", Messages.getQuestionIcon());
        if (answer == Messages.YES) {
            workingCopy.remove(row);
            model.fireTableDataChanged();
        }
    }

    /**
     * 决策 9：名称非空 + 忽略大小写不重复。
     * @param name  待校验名称
     * @param selfRow 当前编辑行（-1 表示新增），排除自身
     */
    private boolean validateName(String name, int selfRow) {
        if (name == null || name.trim().isEmpty()) {
            Messages.showErrorDialog("脚本名称不能为空", "校验失败");
            return false;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < workingCopy.size(); i++) {
            if (i == selfRow) {
                continue;
            }
            ScriptConfig other = workingCopy.get(i);
            if (other.getName() != null && other.getName().trim().toLowerCase(Locale.ROOT).equals(normalized)) {
                Messages.showErrorDialog("脚本名称「" + name + "」已存在（忽略大小写）", "校验失败");
                return false;
            }
        }
        return true;
    }

    @Override
    protected void doOKAction() {
        // 提交到真实状态并持久化
        PowerShellScriptSettings settings = PowerShellScriptSettings.getInstance();
        List<ScriptConfig> real = settings.getState().scripts;
        real.clear();
        for (ScriptConfig cfg : workingCopy) {
            real.add(cfg);
        }
        settings.loadState(settings.getState());
        super.doOKAction();
    }

    private static ScriptConfig copy(ScriptConfig src) {
        ScriptConfig dst = new ScriptConfig();
        dst.setName(src.getName());
        dst.setCommand(src.getCommand());
        dst.setEnabled(src.isEnabled());
        dst.setKillProcessTree(src.isKillProcessTree());
        return dst;
    }

    private static class ScriptTableModel extends AbstractTableModel {
        private final List<ScriptConfig> scripts;
        private static final String[] COLUMNS = {"名称", "启用"};

        ScriptTableModel(List<ScriptConfig> scripts) {
            this.scripts = scripts;
        }

        @Override
        public int getRowCount() {
            return scripts.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ScriptConfig s = scripts.get(rowIndex);
            return columnIndex == 0 ? s.getName() : (s.isEnabled() ? "是" : "否");
        }
    }
}
