# PSRunner

IntelliJ IDEA PowerShell 脚本执行插件——在项目视图中右键目录，即可运行你预先配置的 PowerShell 脚本，输出显示在 IDEA 的 Run 工具窗口。

目标 IDE：IntelliJ IDEA 2026.1+（Windows）。仅支持 Windows 的 `powershell.exe`（PowerShell 5.1）。

> **安装与开发说明**：见 [DEVELOPMENT.md](DEVELOPMENT.md)（环境要求、安装方式、技术栈、验证命令、项目结构、2026.1 适配要点）。

---

## 功能概览（v1 第一期）

- **右键目录执行脚本**：项目视图中右键任意目录，菜单顶部出现「PowerShell Scripts」，展开后列出所有启用的脚本。
- **全局脚本配置**：所有项目共用同一份脚本配置（app 级持久化），配置一次到处可用。
- **配置界面**：菜单底部「配置脚本…」打开管理对话框，支持添加 / 编辑 / 删除脚本。
- **占位符替换**：脚本命令中可用 `{{SelectedDir}}`、`{{ProjectDir}}`、`{{ModuleDir}}` 占位符，执行时替换为实际路径。
- **输出进 Run 窗口**：脚本输出实时显示在 Run 工具窗口，支持停止按钮、`Read-Host` 交互输入。
- **结束标记**：进程结束后显示「成功 / 失败 (exit code: X) / 被用户终止」+ 耗时 + 完成时间。
- **进程树清理**：点停止会级联终止 PowerShell 派生的子进程，不残留孤儿进程。

---

## 快速上手

1. 在 IDEA 的项目视图中**右键任意目录**。
2. 菜单顶部出现 **PowerShell Scripts**，展开它。
3. 首次使用点 **「配置脚本…」**。
4. 在配置对话框点 **「添加」**，填写：
   - **名称**：显示在菜单上的脚本名（如 `npm run dev`、`git pull all`）。
   - **命令**：要执行的 PowerShell 命令或代码块（支持多行）。
   - **启用**：勾选后脚本出现在菜单中。
5. 点「确定」保存，下次右键目录即可看到并执行新脚本。

---

## 配置说明

### 占位符

| 占位符 | 替换为 |
|---|---|
| `{{SelectedDir}}` | 右键选中的目录的绝对路径 |
| `{{ProjectDir}}` | 当前项目的根路径 |
| `{{ModuleDir}}` | 包含选中目录的模块的内容根路径 |

**执行规则**：任何占位符解析失败（如 `{{ModuleDir}}` 对应的目录不属于任何模块）会弹错误对话框并终止执行，绝不把未解析的字面量传给 PowerShell。

### 脚本编写注意事项

1. **相对路径基准 = 选中目录**。脚本中的相对路径（如 `npm run dev`）以你右键的目录为工作目录执行。
2. **`$PSScriptRoot` 指向临时目录**（插件执行时把脚本写入 `%TEMP%` 再执行），**不要依赖它**。要引用选中目录请用 `{{SelectedDir}}`。
3. **中文路径/中文输出**：插件已处理编码（UTF-8 BOM + 控制台 UTF-8），中文正常显示。
4. **交互输入**：脚本可用 `Read-Host`，在 Run 控制台直接输入。

### 常见坑：遍历目录时 `$_` 只给文件名

在 `Get-ChildItem` 管道里，`$_` 是 `DirectoryInfo` 对象，字符串插值 `"$_"` **只得到文件名**，不是完整路径。要用 `$_.FullName`：

```powershell
# ❌ 错误：找不到 .git（$_ 只有文件名）
Get-ChildItem -Directory -Recurse | Where-Object { Test-Path "$_\.git" }

# ✅ 正确：用 $_.FullName
Get-ChildItem -Directory -Recurse | Where-Object { Test-Path "$($_.FullName)\.git" }
```

### 实用脚本示例

**批量 git pull 所有子仓库**（在 D:\VUE 这类多仓库目录上使用）：

```powershell
Get-ChildItem -Directory -Recurse -Depth 2 | Where-Object { Test-Path "$($_.FullName)\.git" } | ForEach-Object {
    Write-Host "Pulling: $($_.FullName)" -ForegroundColor Green
    Push-Location $_.FullName
    git pull
    Pop-Location
}
```

**在选中目录启动前端 dev server**：

```powershell
Write-Host "Working in {{SelectedDir}}"
npm run dev
```

---

## 运行行为说明

| 场景 | 行为 |
|---|---|
| 右键**文件**或空白处 | 「PowerShell Scripts」菜单不显示（仅目录可见） |
| 选中**多个目录** | 仅对第一个选中目录执行，结束标记会注明 |
| 脚本运行中 | Run 窗口标签页可点「停止」，终止脚本及其子进程 |
| 脚本正常退出 | 显示「成功」+ 耗时 |
| 脚本失败 | 显示「失败 (exit code: X)」+ 耗时 |
| 用户手动停止 | 显示「被用户终止」+ 耗时 |
| 临时文件 | 脚本写入 `%TEMP%`，进程结束后自动删除；超过 24 小时的遗留文件在首次右键菜单时清理 |

---

## 已知边界（v1 不含）

- 仅 Windows（Mac/Linux 下菜单隐藏，功能未实现）。
- 不支持 `.ps1` 文件执行、脚本分组、导入导出、快捷键绑定、多目录批量执行、工具窗口面板（属第二部分增强，规划在 `openspec/changes/psrunner-mvp`）。
- 脚本命令内嵌参数请自行用 PowerShell 语法转义；占位符替换值以单引号字面量落地，内含单引号会翻倍转义。
