# IDEA PowerShell 脚本执行插件 - 产品文档

## 一、核心目标
在 IntelliJ IDEA 的项目视图（Project View）中，右键目录即可运行用户预先配置的 PowerShell 脚本，输出显示在 IDEA 的 Run 工具窗口中，提高日常开发效率。

---

## 二、技术选型与环境
- **开发语言**：Java（插件完全用 Java 编写）
- **JDK版本**：openjdk 21（2026.1 SDK 为 Java 21 字节码，编译 JDK 须 ≥21；发布后插件运行于 IDE 自带 JBR，用户无需安装 JDK）
- **平台**：IntelliJ Platform（建议使用最新稳定版 SDK）
- **构建工具**：Gradle 9.0.0 + `org.jetbrains.intellij.platform` 2.x 插件（IntelliJ Platform Gradle Plugin，最新稳定版 2.18.1）
- **目标 IDE**：IntelliJ IDEA Ultimate / Community，以及其他基于 IntelliJ Platform 的 IDE
- **操作系统**：仅 Windows（使用内置 PowerShell 5.1，命令 `powershell.exe`）
- **脚本配置范围**：全局有效（所有项目共用）
- **执行输出**：集成到 IDEA 的 Run 工具窗口
- **参数支持**：初期运行固定脚本，支持占位符动态替换

---

## 三、第一部分：最小可用功能（第一期）
**目标**：实现右键菜单执行脚本的基本闭环，确保安全、稳定、易用。

### 1. 菜单注册
- 在项目视图（Project View）中右键**目录**时，弹出上下文菜单。
- 菜单顶部出现一级菜单项：**“PowerShell 脚本”**（英文 `PowerShell Scripts`），带图标（可先用内置图标）。
- 该一级菜单为**动态 ActionGroup**，子菜单项根据用户配置的脚本列表动态生成。
- **仅在选中的是目录时显示**该菜单项，文件或空区域不显示。

#### 技术要求
- 在 `plugin.xml` 中注册 ActionGroup，挂载到 `ProjectViewPopupMenu` 分组。
- ActionGroup 的 Java 类重写 `getChildren(AnActionEvent e)` 方法，从持久化状态中读取脚本列表，为每个启用的脚本创建 `AnAction`，最后追加分隔线和“配置脚本…”菜单项。

### 2. 脚本配置持久化
- 使用 IntelliJ 的 `PersistentStateComponent` 实现全局配置存储，序列化为 XML 文件（例如 `powerShellScriptConfig.xml`）。
- 状态类中维护一个脚本配置列表 `List<ScriptConfig>`。
- `ScriptConfig` 实体包含以下字段（第一期用到的）：

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `name` | String | 显示在菜单上的脚本名称 |
| `command` | String | 要执行的 PowerShell 命令或代码块（支持多行） |
| `enabled` | boolean | 是否在菜单中显示（默认 true） |

#### 序列化要求
- 使用 JAXB 注解或 `@Attribute` 标注字段，确保 XML 可读性好。
- 提供静态方法 `getInstance()` 获取服务实例。

### 3. 配置界面
- 点击“配置脚本…”菜单项打开一个模态对话框（使用 `DialogWrapper`）。
- 对话框主要内容：
  - **脚本列表**（使用 `JBTable` 或 `TableView`），显示所有脚本名称。
  - **右侧操作按钮**：添加、删除、编辑。
- 点击“添加/编辑”弹出二级对话框，编辑：
  - 脚本名称（`JTextField`）
  - 脚本命令（`JTextArea`，支持多行）
  - 是否启用（`JCheckBox`）
- 点击“确定”后，对话框直接修改 `PowerShellScriptSettings` 的状态，并持久化。
- 关闭配置对话框后，下一次右键菜单刷新就会体现变更。

### 4. 执行脚本动作
- 每个启用的脚本对应一个 `AnAction`，点击后触发执行。
- **获取工作目录**：从 `AnActionEvent` 中获取 `CommonDataKeys.VIRTUAL_FILE`，确保为目录。
- **占位符替换**：在执行前，将脚本命令中的以下占位符替换为实际值：
  - `{{SelectedDir}}` → 选中目录的绝对路径
  - `{{ProjectDir}}` → 当前项目的根路径
  - `{{ModuleDir}}` → 当前模块的根路径（决策 4：无法解析即弹错拦截执行，绝不保留字面量）
- **执行方式**：使用 `GeneralCommandLine` + `KillableProcessHandler` 启动 `powershell.exe`。
  - 参数：`-NoProfile -ExecutionPolicy Bypass -File "<临时 .ps1 文件>"`（脚本先写入 `%TEMP%` 临时文件再执行；旧文 `-Command <内联脚本>` 方式已废弃，见决策 1）
  - 工作目录设置为选中的目录。
- **输出显示**：
  - 创建一个 `ConsoleView`（通过 `TextConsoleBuilderFactory` 构建）。
  - 将 `ConsoleView` 附着到 `OSProcessHandler`，启动进程。
  - 通过 `RunContentManager` 在 Run 工具窗口显示一个标签页，标签名称为脚本名称。
  - 标签页支持停止按钮（`OSProcessHandler` 自带功能）。
- **异步执行**：使用 `ApplicationManager.getApplication().executeOnPooledThread()` 启动，避免阻塞 UI 线程。

### 5. 安全与稳定性
- 占位符替换值由决策 4.1 统一以单引号字面量转义落地；脚本正文由用户自行编写，双引号/单引号兼容性按 PowerShell 规则自行处理（可教规则见决策 4.1）。
- 如果选中目录不存在或无权限，控制台显示错误信息，不崩溃。

### 6. 菜单隐藏逻辑
- 在 ActionGroup 的 `update` 方法中，判断当前选择项不为目录时，整个菜单不可见。避免在文件上出现无意义的菜单。

---

## 四、第二部分：后续增强功能（可选，按需迭代）
以下功能按优先级排序，可在最小可用功能稳定后，逐步添加。

### 1. 执行确认对话框
- 每个脚本增加一个配置项 `needConfirm`（布尔值），默认为 false。
- 若为 true，执行前弹出确认对话框：“确定要执行脚本 [脚本名称] 吗？”，附带脚本内容预览。

### 2. 执行结果通知
- 脚本正常结束时，在右下角弹出系统通知 `Notification`，标题“脚本执行成功”，内容显示退出码和耗时。
- 异常退出时，弹出错误通知，并附带打开对应 Run 标签页的链接。

### 3. 脚本分组与排序
- `ScriptConfig` 增加字段 `group`（字符串），空表示未分组。
- 菜单动态生成时，按分组组织为二级菜单，同一分组内的脚本平铺。
- 配置界面支持拖拽调整脚本顺序（使用 `ListTableModel` + 上下移动按钮）。

### 4. 导入/导出脚本配置
- 配置对话框增加“导入”和“导出”按钮。
- 导出为 JSON 文件，包含所有脚本配置。
- 导入时允许选择文件，替换或合并到现有列表。

### 5. 快捷键绑定
- 为每个脚本动态注册快捷键（Keymap 扩展点），用户在 Keymap 设置中可自定义。
- 通过 `plugin.xml` 中声明一个可配置的 Keymap 分组，每个脚本一个 action ID。

### 6. 多目录批量执行
- 若用户在项目视图中选中多个目录，插件遍历每个目录依次执行脚本。
- Run 标签页输出合并日志，每个目录执行前后打印标记（如“=== 正在处理：D:\project\src ===”）。

### 7. 工具窗口面板
- 添加一个 IDE 工具窗口（`ToolWindow`），列出所有脚本和最近执行历史。
- 双击脚本可直接执行（作用于项目根目录或配置的默认目录）。
- 显示历史记录（时间、脚本、工作目录、结果状态）。

### 8. 环境变量隔离选项
- 脚本配置增加 `useCleanEnv` 布尔选项。
- 若为 true，执行时清理 PowerShell 进程的环境变量，只保留基本 PATH 等，避免 IDEA 的 Java 环境变量干扰。

### 9. 脚本模板库
- 内置几个常用 PowerShell 脚本模板（如清理构建缓存、列出文件树、统计代码行数等），方便新用户直接使用或修改。

### 10. 支持 .ps1 文件
- 配置时允许选择本地 `.ps1` 文件路径，执行时参数改为 `-File "路径"` 而不是 `-Command "..."`，占位符替换仍生效（因为 .ps1 文件内部可按需使用环境变量或参数）。

---

## 五、开发与测试注意事项
- 使用 Gradle 任务 `runIde` 启动沙盒 IDEA 进行调试。
- 确保插件在 Windows 环境下测试，Mac/Linux 环境运行时菜单可隐藏或提示不支持。
- 注意 `plugin.xml` 中声明插件兼容的 IDE 产品和版本范围。
- 将第三方依赖降至最低，仅依赖 IntelliJ Platform 标准 API。

---

## 六、技术方案（实现契约）

> 本节为实现期契约，与配套交付物 [deliverables_03.md](deliverables_03.md)（核心时序图与异常地图、模块分层、关键 API 占位代码、构建与插件配置）共同构成实现依据。产品行为条款见第三、四章；两处对同一规则有表述时，以本章为最终契约。

### 1. 技术方向（B-lite）

- **全部使用 IntelliJ Platform 标准 API，零第三方依赖**。
- **JDK 21 现代写法**：text blocks 生成临时 .ps1 内容、records 定义运行期 DTO、sealed interface 建模执行结果三态（均为 JDK 17 起可用，21 兼容）。
- **后台执行入口**：`executeOnPooledThread`（未弃用、无进度取消语义）。**不引入协程**（新进度 API 为 Kotlin-first，Java 侧调用不便）；**不引入自定义类加载器**（脚本是外部 powershell.exe 进程，与本 JVM 类加载无任何关系，强行引入违背平台最佳实践）。
- **明确边界**：虚拟线程与 StructuredTaskScope 在 JDK 21 下已可用，但 v1 仍采用 `executeOnPooledThread`（无进度取消语义与异常地图 PCE 定位匹配）；虚拟线程留待「多目录批量执行」时评估。

### 2. 模块分层与通信

```
com.example.psrunner
├── ui          // 只做两件事：拿数据上下文 + 交给 Service；绝不碰持久化
│   ├── actions     PowerShellScriptsActionGroup / ScriptExecutionAction / ConfigureScriptsAction
│   └── run         RunTabNamer（唯一标签名生成）
├── service     // 纯逻辑编排，不依赖 Swing
│   ├── ScriptExecutionService     （project 级 Service）
│   ├── PlaceholderResolver
│   └── TempScriptFileManager      （创建/清理 + 启动兜底清扫）
└── data        // 纯 POJO / record，无任何平台依赖
    ├── PowerShellScriptSettings   （PersistentStateComponent，app 级）
    ├── ScriptConfig
    └── ResolvedPaths / ExecutionResult（record；结果三态用 sealed interface）
```

| 方向 | 方式 | 载体 |
|---|---|---|
| UI → Service | **直接方法调用**（命令式） | `project.getService(ScriptExecutionService.class)` |
| Service → UI | **MessageBus 事件**（订阅式） | 自定义 `ScriptExecutionListener` / `ScriptConfigListener` TOPIC，`app.getMessageBus().connect(disposable).subscribe(...)` |
| Service → Data | 无回调，Service 主动读写 | `app.getService(PowerShellScriptSettings.class)` |
| UI 生命周期 | 无手动管理 | `Disposer` 控制 `Connection` 随 UI 组件销毁 |

**v1 约定**：第一版仅需「UI→Service 调用 + Service→Data 读写」；MessageBus 在后续增强第 7 条（ToolWindow）与状态栏落地时开始承重，届时 UI 组件需跨模块感知执行结果。

### 3. 核心执行链路契约

| 决策 | 实现契约 |
|---|---|
| **临时 .ps1 文件**（决策 1） | 文件置于系统 `%TEMP%`，命名固定 `idea_powershell_` + 时间戳 + `.ps1`；进程终止时（`ProcessListener`，无论正常/异常退出）无条件删除；插件启动时兜底清理超时遗留文件（建议阈值 24 小时）。文件系统操作不得留在后台线程之外（统一经 `TempScriptFileManager`） |
| **标签页唯一命名**（决策 2） | `baseName` = 脚本 name；Run 窗口无 `baseName` 开头的标签则直接用；否则依次尝试 `baseName + " (2)"`、`" (3)"`…序号仅基于当前存在的标签判断，关闭后释放可复用，只保证同一时刻不重名 |
| **编码**（决策 3） | ① 生成临时 .ps1 时**文件头部固定注入**：`[Console]::OutputEncoding = [System.Text.Encoding]::UTF8` 与 `$OutputEncoding = [System.Text.Encoding]::UTF8`；② `GeneralCommandLine` 创建后调用 `setCharset(StandardCharsets.UTF_8)`，Java 端以 UTF-8 解码进程输出 |
| **占位符强制解析**（决策 4） | 支持 `{{SelectedDir}}` / `{{ProjectDir}}` / `{{ModuleDir}}`；执行前全部解析，任何失败弹错误对话框并**终止执行**，绝不保留字面量。`{{ModuleDir}}` 经 `ModuleRootManager` 查「包含选中目录的内容根」，目录不属于任何模块即报错拦截；替换后若仍残留 `{{`，同样拦截（防拼写错误静默通过）。**只走 module 结构 + `ProjectRootManager`，不触索引** |
| **执行结束标记**（决策 5） | 进程终止时在控制台末尾追加：退出码 0 → 常规色「成功」；非 0 → 错误色「失败 (exit code: X)」；用户手动停止 → 警告色「被用户终止」。耗时精确到 0.1 秒，完成时间用本地时间格式。与临时文件清理联动（同一 `ProcessListener`） |

### 3.1 进程边界契约增补（决策 1.1 / 3.1 / 4.1 / 5.1 / 6）

> 本节是对 §3「核心执行链路契约」的增补，覆盖进程/文件交界处（编码输入方向、转义、目录语义、进程树）的边界细节。**本节增补不推翻 §3 原决策**；对同一规则的表述以「本节 + §3」合并为准。

| 决策 | 实现契约 |
|---|---|
| **决策 1.1** 临时文件写入编码（增补决策 1） | 临时 `.ps1` 文件以 **UTF-8 with BOM** 写出。Java 无内置 BOM 编码，写入内容头部显式加 `﻿` 字符（UTF-8 编码后为 `EF BB BF`）。理由：Windows PowerShell 5.1 对**无 BOM** 的 UTF-8 文件按系统 ANSI 代码页解析（中文 Windows 为 GBK/936），脚本内中文或替换进占位符的中文路径会乱码甚至解析报错；BOM 使 PS 5.1 确定性识别为 UTF-8。与决策 4.1 互补：BOM 保证中文路径进得了文件，单引号字面量保证其在文件内不被 PS 语法误伤 |
| **决策 3.1** 编码增补（增补决策 3） | ① 读方向（进程→Java）配方不变：`[Console]::OutputEncoding` / `$OutputEncoding` = UTF8 + `setCharset(UTF_8)`，列入兼容性清单「需验证 ⑤」而非「确信稳定」——PS 5.1 在 stdout 被重定向（OSProcessHandler 管道接管）时 `Console.OutputEncoding` 存在历史行为怪癖，需 runIde 实测；② 输入方向（Java→PS 5.1）由决策 1.1 的 BOM 保证；③ **stdin 交互**：Run 控制台键盘输入转发至进程 stdin，脚本可用 `Read-Host`；头部额外注入 `[Console]::InputEncoding = [System.Text.Encoding]::UTF8` 保证交互输入中文不乱码 |
| **决策 4.1** 占位符转义契约（增补决策 4） | 所有占位符替换值以 **PowerShell 单引号字符串字面量**落地：`{{SelectedDir}}` → `'<值>'`；值内 `'` 翻倍转义为 `''`（Windows 路径不允许 `'`，v1 实际零转义）。选择单引号的原因：单引号上下文不做插值，值内 `$`、反引号等字符无特殊含义。**可教规则**（写入配置界面提示）：不要将占位符直接写进双引号字符串（如 `"{{SelectedDir}}"`）——路径含 `$` 时会被当变量插值；需嵌入双引号串时用 `"$($SelectedDir)"`。与决策 4 的「残留 `{{` 拦截」检查兼容 |
| **决策 5.1** 终止判定与进程树（增补决策 5） | ① **判定顺序**：`ProcessListener.processWillTerminate` 中先判 `willBeDestroyed` 再读退出码——`willBeDestroyed = true` → 警告色「被用户终止」；否则按退出码判成功/失败。防止被强杀进程的乱退出码误显示为「失败 (exit code: X)」；② **进程树**：`KillableProcessHandler` 默认只杀 `powershell.exe` 本体，用户脚本启动的子进程（`Start-Process`、`node`、`robocopy` 等）会残留。v1 调用 `setShouldDestroyProcessRecursively(true)` 递归杀（Windows 实现 2021 年后为 Job Object，待 2026.1 验证）；`ScriptConfig` 预留 `killProcessTree` 布尔字段（默认 true）进第二部分，供需保留子进程的用户关闭 |
| **决策 6** `$PSScriptRoot` 语义（新决策） | 临时文件执行方式下，`$PSScriptRoot` / `$PSCommandPath` 指向**临时目录**（`%TEMP%`），为只读自动变量、不可覆盖——结构性事实，不可修，只能教。**相对路径基准 = 选中目录**（`setWorkDirectory` 已设，直觉正确）；脚本内引用选中目录必须用 `{{SelectedDir}}` 占位符。动作：① 注入头部写两行注释（真实临时路径 + 语义说明，见下方样例）；② 配置界面脚本命令编辑框加灰字提示 |

**注入头部完整样例**（决策 1.1 / 3.1 / 6 的落地形态；`<JVM tmpdir>` 为解析出的实际临时目录）：

```powershell
# 脚本在临时目录执行: <JVM tmpdir>
# $PSScriptRoot / $PSCommandPath = 临时目录；相对路径基准 = 选中目录；引用选中目录请用 {{SelectedDir}}
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding = [System.Text.Encoding]::UTF8

<替换后的脚本内容>
```

**执行参数**（增补决策 1）：`powershell.exe -NoProfile -ExecutionPolicy Bypass -File "<临时文件路径>"`。`-NoProfile` 避免用户 PowerShell profile 拖慢启动、污染环境；**不加 `-NonInteractive`**（会禁用 `Read-Host` 交互输入）。

### 3.2 第一期实现补充决策（决策 7 / 8 / 9）

> 本节为第一期（最小可用功能）实现前的补充定案，覆盖临时文件清扫时机、多选目录行为、脚本名称校验三个实现细节。

| 决策 | 实现契约 |
|---|---|
| **决策 7** 临时文件清扫时机 | **首次弹菜单时惰性清扫**：在 `PowerShellScriptsActionGroup.update()` 中惰性触发一次清扫（`TempScriptFileManager` 删除超 24 小时的遗留临时文件）。**不采用** `applicationStartupActivity` 启动清扫——该扩展点名称在 2026.1 待验证（兼容性清单需验证①），惰性清扫语义等价且零未验证 API；启动清扫作为验证通过后的 polish 后置 |
| **决策 8** 多选目录行为 | v1 从 `CommonDataKeys.VIRTUAL_FILE` 取**第一个**选中目录执行（多选时该 key 只返回第一个）；结束标记额外追加一行注明「仅对第一个选中目录执行：&lt;路径&gt;」，诚实告知用户 v1 只跑一个。多目录批量执行属第二部分，不在 v1 范围 |
| **决策 9** 脚本名称唯一性 | 配置对话框保存时校验：脚本名称**非空**，且忽略大小写**不重复**（与其他脚本比较）；不满足则阻止保存并提示。避免菜单出现同名脚本、用户混淆（决策 2 的标签序号只能兜底标签层，兜不住配置层） |

### 4. 异常地图

| # | 异常 | 抛出位置 | 处理策略 |
|---|---|---|---|
| ① | `ProcessCanceledException` | `ReadAction.compute` 内占位符解析 | 捕获后静默终止；已生成的临时文件一并清理 |
| ② | `IndexNotReadyException` | 智能模式外的 PSI/索引访问（v1 **不使用**） | v1 规避：占位符解析不读索引；未来功能须用 `DumbService.runWhenSmart` / `computeInSmartMode` 包裹 |
| ③ | `IOException`（`ExecutionException`） | `new KillableProcessHandler(cmd)` 启动进程 | 控制台/错误对话框展示原因，不崩溃；属 JVM 侧异常，与脚本退出码无关 |
| ④ | `PlaceholderResolutionException`（自定义） | `PlaceholderResolver.resolve` | 弹错误对话框**拦截执行**，绝不把字面量带入脚本 |
| ⑤ | 脚本运行期错误（非异常） | PowerShell 进程内部 | 不进入 JVM，以**退出码≠0** 呈现为「失败 (exit code: X)」 |

**防崩溃原则**：后台线程永远不碰 Swing 组件（UI 只经 `EdtExecutorService` 切回 EDT 操作）；`executeOnPooledThread` 路径无进度取消，PCE 只可能来自 ReadAction 放弃。

### 5. 兼容性自查清单（IntelliJ IDEA 2026.1.3，需本地一次性验证）

- **确信稳定（占位代码全部落于此层）**：ActionSystem、`PersistentStateComponent`、`GeneralCommandLine` / `KillableProcessHandler` / `ProcessListener`、`ConsoleView`、`RunContentManager`、`DialogWrapper`、`Messages`、`ReadAction`。
- **需验证 ①**：`applicationStartupActivity` 扩展点确切名称（老版为 `startupActivity` / `postStartupActivity`，2021 年重构后拆分）。**v1 不依赖它**——决策 7 已采用首次弹菜单时惰性清扫，零未验证 API；启动清扫（需验证①通过后）作为 polish 后置。
- **需验证 ②**：`RunContentManager.getInstance(project)` 是否已迁移为 service-locator 形式（若是，改用 `project.getService(RunContentManager.class)`）。
- **需验证 ③**：新 `com.intellij.platform.ide.progress` 的 `BackgroundTask` 为 Kotlin 协程设计，Java 侧调用可用性待 2026.1 实际 API 确认；验证通过前稳定落在 `executeOnPooledThread`。
- **需验证 ④**：`setShouldDestroyProcessRecursively(true)` 在 2026.1 Windows 的实现（Job Object 还是 `taskkill /T`），直接决定「停止」按钮的进程树语义，见决策 5.1 ②。
- **需验证 ⑤**：决策 3.1 ① 的读方向编码配方在 stdout 被管道重定向时 PS 5.1 的实际行为；以及决策 1.1 的 BOM 写入（预期稳定，PS 5.1 对 UTF-8 BOM 确定性识别，仍验证一次）。
- **验证手段**：`runIde` 后查 IDE 日志 DEPRECATION 警告 + Gradle `verifyPlugin` 任务。
