# 第三步交付物：核心逻辑伪代码与异常地图

> **决策确认**：技术方向采用 B-lite（标准平台 API + JDK 21 现代写法 + 谨慎使用较新的平台 API，带回退）。JDK 由 17 升 21 的原因：2026.1 SDK 为 Java 21 字节码（65.0），编译 JDK 须 ≥21；本项目代码均为 JDK 17 特性（record/sealed/text blocks），21 完全兼容、零代码改动。
>
> **开发环境约束**：IntelliJ IDEA 2026.1.3 / JDK 21 / 语言级别 21。
>
> **核心落定**：后台执行采用 `executeOnPooledThread`（未弃用）而非新 `BackgroundTask`。原因见下方「2026.1.3 兼容性自查清单」：新进度 API 为 Kotlin-first，从 Java 调用不便。这是 B-lite 在第三步的落定。

---

## 交付物 1 — 核心时序图与异常地图

```mermaid
sequenceDiagram
    autonumber
    actor U as 用户
    participant G as "PowerShellScriptsActionGroup<br/>(getChildren / update)"
    participant A as "ScriptExecutionAction<br/>(EDT)"
    participant S as "ScriptExecutionService<br/>(后台线程)"
    participant R as "PlaceholderResolver<br/>(ReadAction)"
    participant F as "%TEMP% 临时 .ps1"
    participant P as "KillableProcessHandler"
    participant C as "ConsoleView + RunContentManager<br/>(EDT)"
    participant L as "ProcessListener<br/>(后台回调)"

    U->>G: 右键目录 → 弹出上下文菜单
    G->>G: update(): 校验选中项为目录<br/>+ 决策7：惰性清扫临时文件<br/>+ 决策8：多选时取第一个选中目录
    Note over G: 非目录 → setEnabledAndVisible(false)<br/>整个菜单隐藏，链路终止
    G->>A: getChildren(): 读启用的脚本配置，生成子动作
    U->>A: 点击脚本项
    A->>S: execute(project, script, targetDir)<br/>【EDT 切后台线程】
    S->>R: ReadAction.compute(resolve(...))
    Note over R: ⚠️ PCE 抛出点 ①<br/>读操作被放弃（项目关闭 / 原子写）
    R-->>S: ResolvedPaths(替换后命令)
    Note over S: 解析失败（如 {{ModuleDir}} 无归属模块）<br/>→ 错误对话框，终止执行
    S->>F: 写入 idea_powershell_&lt;ts&gt;.ps1<br/>头部注入（决策 1.1/3.1/6）：<br/>① UTF-8 with BOM（首字符 U+FEFF，防 PS5.1 按 GBK 解析）<br/>② [Console]::OutputEncoding = UTF8<br/>③ $OutputEncoding = UTF8<br/>④ [Console]::InputEncoding = UTF8<br/>⑤ $PSScriptRoot 语义注释
    S->>S: 生成唯一标签名<br/>baseName / +" (2)" / +" (3)"…
    S->>C: 【EDT】createBuilder().build()<br/>RunContentManager.showRunContent(...)
    C->>P: new KillableProcessHandler(cmd)<br/>charset=UTF-8, 工作目录=选中目录
    P->>P: startNotify() 输出转发 ConsoleView
    loop 运行中
        P-->>C: stdout/stderr 实时显示
        Note over P: 用户点停止 → destroyProcess()<br/>KillableProcessHandler 级联杀子进程
    end
    P-->>L: processTerminated(exitCode)
    L->>C: 【EDT】追加结束标记<br/>成功 / 失败(exit code) / 被用户终止<br/>+ 耗时 + 完成时间<br/>+ 决策8：仅对第一个选中目录执行
    L->>F: 无论成败删除临时文件

    Note over S: ⚠️ IndexNotReadyException 风险点 ②<br/>v1 核心链路不触碰索引，理论不触发；<br/>未来若使用 PSI / 智能模式解析，<br/>必须 DumbService / computeInSmartMode 包裹
```

### 异常地图

| # | 异常 | 抛出位置 | 典型触发场景 | 处理策略 |
|---|---|---|---|---|
| ① | `ProcessCanceledException` | `ReadAction.compute` 内部 | 项目关闭、`commitDocuments` 原子写期间读操作被放弃、线程中断 | 捕获后静默终止；若临时文件已生成则一并清理 |
| ② | `IndexNotReadyException` | 智能模式外的 PSI/索引访问（本方案**不使用**） | 项目正在索引、未进入 Smart Mode | v1 规避：占位符解析只走 module 结构 + `ProjectRootManager`，不读索引；未来功能须用 `DumbService.runWhenSmart` 或 `computeInSmartMode` |
| ③ | `IOException`（`ExecutionException`） | `new KillableProcessHandler(cmd)` 启动进程时 | `powershell.exe` 不存在/被占用、`%TEMP%` 不可写、工作目录无权限 | 控制台/错误对话框展示原因，不崩溃；属于 JVM 侧异常，与脚本退出码无关 |
| ④ | `PlaceholderResolutionException`（自定义） | `PlaceholderResolver.resolve` | `{{ModuleDir}}` 无归属模块、`{{ProjectDir}}` 为空 | 弹错误对话框**拦截执行**，绝不把字面量带入脚本（决策 4） |
| ⑤ | 脚本运行期错误（非异常） | PowerShell 进程内部 | 脚本自身抛错 | 不进入 JVM，以**退出码≠0** 呈现为「失败 (exit code: X)」 |

**防崩溃原则**：后台线程永远不碰 Swing 组件（UI 只经 `EdtExecutorService` 切回 EDT 操作）；`executeOnPooledThread` 路径无进度取消，PCE 只可能来自 ReadAction 放弃——这是刻意为之，换取 Java 侧 API 稳定。

---

## 交付物 2 — 模块分层与通信

```
com.summerlex.psrunner
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
    ├── ResolvedPaths / ExecutionResult（record；结果三态用 sealed interface）
```

### 模块间通信方式

| 方向 | 方式 | 载体 |
|---|---|---|
| UI → Service | **直接方法调用**（命令式） | `project.getService(ScriptExecutionService.class)` |
| Service → UI | **MessageBus 事件**（订阅式） | 定义 `ScriptExecutionListener`/`ScriptConfigListener` 两个 TOPIC，`app.getMessageBus().connect(disposable).subscribe(...)` |
| Service → Data | 无回调，Service 主动读写 | `app.getService(PowerShellScriptSettings.class)` |
| UI 生命周期 | 无手动管理 | `Disposer` 控制 `Connection` 随 UI 组件销毁，杜绝内存泄漏 |

**为什么这套划分契合 2026.1.3**：Service Locator（`getService`/`getInstance`）+ MessageBus 是平台十几年稳定的架构基座，新进度 API、New UI、`@Service` 注解注册全部建立在同一套 DI 之上，选它等于零迁移成本；UI 层只管 Swing、Service 层可独立单测、Data 层零平台依赖，与 JetBrains 官方插件示例的「Action 薄壳 → Service 编排 → 状态组件」结构一致。一个务实说明：**v1 只需「UI→Service 调用 + Service→Data 读写」，MessageBus 在后续增强第 7 条（ToolWindow）和状态栏落地时才真正承重**——那时 UI 组件需要跨模块感知执行结果，订阅式才比硬调用干净。

---

## 交付物 3 — 关键 API 占位代码（仅必须实现的入口）

### 3.1 动态 ActionGroup

```java
public class PowerShellScriptsActionGroup extends ActionGroup implements DumbAware {

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile vf = e.getData(CommonDataKeys.VIRTUAL_FILE);
        e.getPresentation().setEnabledAndVisible(vf != null && vf.isDirectory());
        // 决策 7：首次弹菜单时惰性清扫遗留临时文件（TempScriptFileManager 删 >24h 的）
        TempScriptFileManager.sweepStaleTempFilesOnce();   // TODO: 惰性清扫，保证只清一次
    }

    @Override
    public @NotNull AnAction[] getChildren(@Nullable AnActionEvent e) {
        List<AnAction> children = new ArrayList<>();
        PowerShellScriptSettings settings = PowerShellScriptSettings.getInstance();
        for (ScriptConfig script : settings.getState().scripts) {
            if (script.isEnabled()) {
                children.add(new ScriptExecutionAction(script));
            }
        }
        if (!children.isEmpty()) children.add(Separator.getInstance());
        children.add(new ConfigureScriptsAction());
        return children.toArray(AnAction[]::new);
    }
}
```

要点：`getChildren` 每次弹菜单都调用，天然满足「关闭配置后下次右键即生效」；`DumbAware` 保证索引期间菜单不消失；`update` 只读数据上下文，**无需重写 `getActionUpdateThread()`**（默认 EDT 即可）。

### 3.2 单个脚本的执行动作

```java
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
        // TODO: 空校验 → 提示「请在目录上使用此功能」
        // TODO: script.isNeedConfirm()（后续增强1）→ 确认对话框
        ScriptExecutionService.getInstance(project).execute(project, script, targetDir);
    }
    // 注：不实现 getFamilyName()——该 API 在 2025.2+ 已从 AnAction 移除（2026.1 确认无此方法）。
    // 原用途是后续增强5（Keymap 冲突分组），届时用 ActionManager 的 id 机制替代。
}
```

### 3.3 执行编排 Service（核心，含终止监听）

```java
public final class ScriptExecutionService {

    private final Project project;

    public static ScriptExecutionService getInstance(@NotNull Project project) {
        return project.getService(ScriptExecutionService.class);
    }

    public void execute(@NotNull Project project,
                        @NotNull ScriptConfig script,
                        @NotNull VirtualFile targetDir) {
        // 未弃用、稳定的后台入口（新 BackgroundTask 为 Kotlin-first，见文末风险清单）
        ApplicationManager.getApplication().executeOnPooledThread(
            () -> runExecution(project, script, targetDir));
    }

    private void runExecution(Project project, ScriptConfig script, VirtualFile targetDir) {
        // ── 1. 占位符解析：ReadAction 内，PCE 抛出点 ① ──
        ResolvedPaths paths;
        try {
            paths = ReadAction.compute(() -> new PlaceholderResolver().resolve(project, targetDir, script));
        } catch (ProcessCanceledException e) {
            return;  // 项目关闭等放弃场景，静默终止
        } catch (PlaceholderResolutionException e) {
            EdtExecutorService.getInstance().execute(() ->
                Messages.showErrorDialog(e.getMessage(), "占位符解析失败"));
            return;
        }

        // ── 2. 临时文件 + 唯一标签名 ──
        Path tempFile = null;
        try {
            tempFile = TempScriptFileManager.create(paths.getCommand());   // TODO: 命名 idea_powershell_<ts>.ps1；
                                                                           // 写入编码按决策 1.1：UTF-8 with BOM（内容头加 U+FEFF，防 PS5.1 按 GBK 解析中文路径/脚本）
                                                                           // 头部注入按决策 1.1/3.1/6：OutputEncoding/InputEncoding 两行 UTF8 + $PSScriptRoot 语义注释
        } catch (IOException e) {
            // TODO: invokeLater 报错（%TEMP% 不可写等）
            return;
        }
        String runName = RunTabNamer.uniqueName(script.getName(), RunContentManager.getInstance(project)); // TODO: baseName + " (2)"…

        // ── 3. 后台构造进程 → EDT 接线 UI ──
        GeneralCommandLine cmd = new GeneralCommandLine("powershell.exe");
        cmd.addParameter("-NoProfile");            // 可选：加速并隔离 profile 干扰
        cmd.addParameter("-ExecutionPolicy");
        cmd.addParameter("Bypass");
        cmd.addParameter("-File");
        cmd.addParameter(tempFile.toAbsolutePath().toString());
        cmd.setCharset(StandardCharsets.UTF_8);    // 决策 3.1：Java 端以 UTF-8 解码进程输出
        cmd.withWorkDirectory(targetDir.getPath()); // 工作目录 = 选中目录

        EdtExecutorService.getInstance().execute(() ->
            showInRunWindow(project, cmd, script.getName(), runName, tempFile));
    }

    private void showInRunWindow(Project project, GeneralCommandLine cmd,
                                 String scriptName, String runName, Path tempFile) {
        KillableProcessHandler handler = new KillableProcessHandler(cmd);
        // 决策 5.1 ②：点停止应级联杀掉 PowerShell 派生的子进程（setShouldDestroyProcessRecursively(true)），
        // 消除「只杀父进程 → 孤儿进程」隐患；具体递归实现（Job Object 还是 taskkill /T）见需验证 ④

        handler.addProcessListener(new ProcessListener() {
            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                // 决策 5.1 ①：判定「被用户终止」用 handler.isProcessTerminating()——
                // 反编译 2026.1 ProcessHandler.notifyProcessTerminated 确认：进程自然/异常退出时 willBeDestroyed 恒为 true，
                // 不能用它区分「用户停止」；只有 destroyProcess() 触发后才进入 TERMINATING 状态。
                boolean userStopped = handler.isProcessTerminating();
                EdtExecutorService.getInstance().execute(() ->
                    appendEndMarker(console, event, userStopped));  // TODO: 结束标记 + 耗时/时间
                try {
                    Files.deleteIfExists(tempFile);   // 决策 1.1：成败均清理
                } catch (IOException ignored) { }
            }
        });

        ConsoleView console = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project).build();
        console.attachToProcess(handler);

        RunContentDescriptor descriptor = RunContentDescriptor.builder(handler, console)
            .setDisplayName(runName)                 // 决策 2 唯一标签名
            .build();
        RunContentManager.getInstance(project).showRunContent(
            DefaultRunExecutor.getRunExecutorInstance(), handler, descriptor, console);

        handler.startNotify();  // 必须在 EDT
    }

    private void appendEndMarker(ConsoleView console, ProcessEvent event, boolean userStopped) {
        // 决策 5.1 ①：先判「被用户终止」再读退出码——
        //   - userStopped（handler.isProcessTerminating()）为 true → 警告色「被用户终止」
        //   - 否则按退出码：0 → 常规色「成功」；非 0 → ERROR_OUTPUT「失败 (exit code: X)」
        // TODO: 决策 8：多选时追加一行「仅对第一个选中目录执行：<路径>」
        // TODO: 耗时精确 0.1s（nanoTime 差值），完成时间 LocalDateTime 本地格式
    }
}
```

### 3.4 占位符强制解析

```java
public final class PlaceholderResolver {

    public @NotNull ResolvedPaths resolve(@NotNull Project project,
                                          @NotNull VirtualFile targetDir,
                                          @NotNull ScriptConfig script) throws PlaceholderResolutionException {
        String selectedDir = targetDir.getPath();
        String projectDir = project.getBasePath();
        String moduleDir = findModuleContentRoot(project, targetDir);

        // TODO: 逐占位符 replace；任一步解析失败 → throw PlaceholderResolutionException（决策 4）
        // TODO: 替换值以单引号字面量落地（决策 4.1）：值内 ' → ''；中文路径依赖 BOM 写文件（决策 1.1）
        // TODO: 替换完成后扫描残留 "{{"，仍有 → 拦截（防拼写错误静默通过）
        String command = script.getCommand();
        // 示例：command = command.replace("{{SelectedDir}}", selectedDir);
        return new ResolvedPaths(command, selectedDir, projectDir, moduleDir);
    }

    private String findModuleContentRoot(@NotNull Project project,
                                         @NotNull VirtualFile dir) throws PlaceholderResolutionException {
        Module module = ModuleUtilCore.findModuleForFile(dir, project);   // 注意：非已弃用的 ModuleUtil.findModuleForFile(File,...)
        // TODO: 遍历 module.getModuleRootManager().getContentRoots()
        // TODO: 选「包含 dir 且路径最长」的 content root；无则 throw 拦截（决策 4）
        // TODO: 说明：ModuleRootManager 只读项目结构，不触索引，故无 IndexNotReadyException
        return /* contentRoot.getPath() */ "";
    }
}
```

### 3.5 数据层（持久化实体 + 运行期 DTO）

```java
// PowerShellScriptSettings —— app 级全局配置
public final class PowerShellScriptSettings implements PersistentStateComponent<PowerShellScriptSettings.State> {

    public static final class State {
        public List<ScriptConfig> scripts = new ArrayList<>();
    }

    private State state = new State();

    public static PowerShellScriptSettings getInstance() {
        return ApplicationManager.getApplication().getService(PowerShellScriptSettings.class);
    }

    @Override public @Nullable State getState() {
        // 决策 5.1 ②：processTree 的读取在 PowerShellScriptSettings 侧聚合，
        // 避免 ScriptConfig 依赖平台 KillableProcessHandler
        return state;
    }
    @Override public void loadState(@NotNull State state) { this.state = state; }
}

// ScriptConfig —— 持久化实体：建议保留普通类（XmlSerializer 兼容性最稳，record 支持待 2026.1 验证）
public class ScriptConfig {
    @Attribute("name") public String name;        // TODO: 保存时唯一性校验（忽略大小写，决策 2.1）
    @Attribute("command") public String command;
    @Attribute("enabled") public boolean enabled = true;
    // 决策 5.1 ②预留：是否递归杀子进程（默认 true；v1 固定为递归，第二部分才暴露该选项）
    @Attribute("killProcessTree") public boolean killProcessTree = true;
}

// 运行期纯 DTO，用 JDK record + sealed 三态（决策 5，JDK 17 特性，21 下兼容）
public record ResolvedPaths(String command, String selectedDir, String projectDir, String moduleDir) {}

public sealed interface ExecutionResult
        permits ExecutionResult.Success, ExecutionResult.Failure, ExecutionResult.Terminated {
    record Success(int exitCode, long durationMs, LocalDateTime finishedAt) implements ExecutionResult {}
    record Failure(int exitCode, long durationMs, LocalDateTime finishedAt) implements ExecutionResult {}
    record Terminated(long durationMs, LocalDateTime finishedAt) implements ExecutionResult {}
}
```

**代码层面刻意避开的弃用 API**（对比自查）：`Task.Backgroundable` / `ProgressManager.run(Task)`、`ServiceManager.getService`（改用 `getService`）、`ModuleUtil.findModuleForFile(File,…)`（改用 `ModuleUtilCore`）、`new NotificationGroup(...)`（后续阶段改用 `NotificationGroupManager`）。

---

## 交付物 4 — 构建与插件配置

### build.gradle（Groovy 关键段）

> **构建工具链（2026 已更新，替代原 1.x 方案）**：采用 **Gradle 9.0.0** + 官方新一代 **IntelliJ Platform Gradle Plugin 2.x**（插件 ID `org.jetbrains.intellij.platform`，最新稳定版 **2.18.1**）。原 `org.jetbrains.intellij` 1.x 插件已停止开发（最终版 1.17.4），且不兼容 Gradle 9，故整体迁移。功能代码（Java）零改动，仅构建脚本语法变化。

```groovy
plugins {
    id 'java'
    id 'org.jetbrains.intellij.platform' version '2.18.1'   // 2026-07 最新稳定版
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }   // 2026.1 SDK 为 Java 21 字节码
}
compileJava {
    options.release = 21          // 产出 65 字节码；sinceBuild=261 的 IDE 自带 JBR≥21，可正常加载
}

repositories {
    mavenCentral()                                        // 解析标准 JVM 库
    intellijPlatform { defaultRepositories() }            // JetBrains 托管仓库（下载 SDK）
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity '2026.1.3'                  // 与本地 IDE 版本一致，避免平台 API 偏差
                                                          // （Community 沙盒；要测 Ultimate 改 intellijIdea）
        jetbrainsRuntime()                                // 多平台压缩包不含 JBR，需显式声明运行时供 runIde 用
    }
}

intellijPlatform {
    projectName = 'PowerShell Script Runner'

    pluginConfiguration {
        ideaVersion {
            sinceBuild = '261'                            // 2026.1 起始 build 号（251/252/253→261）
            untilBuild = null                             // 不设上限（null），或按兼容声明收紧
        }
    }
}

buildSearchableOptions.enabled = false   // 本插件无 Configurable 设置页，跳过耗时的搜索索引构建
                                         // （备选：gradle.properties 设 org.jetbrains.intellij.buildFeature.buildSearchableOptions=false）

runIde {
    jvmArgs '-Xmx2g'
}

// verifyPlugin 为 2.x 标准任务：跑 Plugin Verifier 校验二进制兼容性
// 要校验的 IDE 版本在 intellijPlatform { pluginVerification { ides { } } } 中声明（见兼容性自查清单）
```

关键点：**IDE 版本在 `dependencies.intellijPlatform.intellijIdeaCommunity('2026.1.3')` 锁定**（沙盒即你的开发 IDE，SDK 不会漂移）；IC（Community）即可覆盖 Ultimate（Ultimate 功能子集 ≥ Community）；本插件不需要任何第三方依赖。

### plugin.xml 关键声明

```xml
<idea-plugin>
    <id>com.summerlex.psrunner</id>
    <name>PowerShell Script Runner</name>
    <description>在项目视图中右键目录即可运行用户预先配置的 PowerShell 脚本，支持占位符替换，输出显示在 Run 工具窗口。</description>

    <depends>com.intellij.modules.platform</depends>   <!-- 模块模型/执行API均在平台内 -->

    <extensions defaultExtensionNs="com.intellij">
        <applicationService serviceImplementation="com.summerlex.psrunner.data.PowerShellScriptSettings"/>
        <projectService       serviceImplementation="com.summerlex.psrunner.service.ScriptExecutionService"/>

        <!-- 决策 7：临时文件清扫采用「首次弹菜单时惰性清扫」，不注册 applicationStartupActivity
             （该扩展点名称在 2026.1 待验证；惰性清扫零未验证 API，启动清扫作为 post-polish 后置） -->

        <!-- 后续增强2的通知组，可本期先占位 -->
        <notificationGroup id="psrunner.notifications" displayType="BALLOON"/>
    </extensions>

    <actions>
        <!-- 注：2026.1 schema 用 <group> 而非 <actionGroup>（见实现期核实：ActionElement$ActionElementName 常量池） -->
        <group id="PowerShellScripts.ActionGroup"
               class="com.summerlex.psrunner.ui.actions.PowerShellScriptsActionGroup"
               text="PowerShell Scripts"
               popup="true">
            <add-to-group group-id="ProjectViewPopupMenu" anchor="first"/>
        </group>
        <!-- 动态 ActionGroup 无需在 XML 中声明子 action，children 由 getChildren() 生成 -->
    </actions>
</idea-plugin>
```

说明：Run 工具窗口、`RunContentManager`、`ConsoleView` 均属 `com.intellij.modules.platform`，Community/Ultimate 一致可用，无需 `<depends>` 额外平台插件；Service 的 `getInstance` 走 `getService`，与上面的 XML 注册一一对应。

---

## 2026.1.3 兼容性自查清单（需你本地一次性验证）

我的知识截止 2026 年 1 月，2026.1.3 在其后发布，以下为我**确信稳定**与**需验证**的分界：

- **确信稳定**：ActionSystem、`PersistentStateComponent`、`GeneralCommandLine`/`KillableProcessHandler`/`ProcessListener`、`ConsoleView`、`RunContentManager`、`DialogWrapper`、`Messages`、`ReadAction`——均为多年平台基座，占位代码全部落在这一层。
- **需验证 ①**：`applicationStartupActivity` 扩展点的**确切名称**（老版为 `startupActivity`/`postStartupActivity`，2021 年重构后拆分，请对照 2026.1 的 platform-impl 扩展点列表；不放心可退回「首次弹菜单时惰性清扫」方案，决策 1.3 语义等价）。
- **需验证 ②**：`RunContentManager.getInstance(project)` 在近版本是否已迁移为 service-locator 形式（若是，改用 `project.getService(RunContentManager.class)`，仅一行差异）。
- **需验证 ③**：若你日后想用新 `com.intellij.platform.ide.progress` 的 `BackgroundTask` 替代 `executeOnPooledThread`——它主要为 Kotlin 协程设计，Java 侧调用的可用性与签名请以 2026.1 实际 API 为准；在验证通过前，本方案稳定落在 `executeOnPooledThread`（未弃用、无取消语义，也正因如此时序图中 PCE 只出现在 ReadAction 一处）。
- **需验证 ④**：`KillableProcessHandler.setShouldDestroyProcessRecursively(true)` 在 2026.1 Windows 的实现（Job Object 还是 `taskkill /T`），直接决定「停止」按钮的进程树语义（决策 5.1 ②）。「被用户终止」判定已确认：**`ProcessEvent.isWillBeDestroyed()` 在 2026.1 已移除**，且 `willBeDestroyed` 参数在自然退出时恒为 true（不可用），改用 `handler.isProcessTerminating()`（决策 5.1 ① 修正）。
- **需验证 ⑤**：决策 3.1 ① 读方向编码配方（`[Console]::OutputEncoding`/`$OutputEncoding` = UTF8 + `setCharset(UTF_8)`）在 stdout 被管道重定向（OSProcessHandler 接管）时 PS 5.1 的实际行为；以及决策 1.1 的 UTF-8 with BOM 写文件（预期稳定，PS 5.1 对 BOM 确定性识别，仍验证一次）。

跑通 `runIde` 后，用 IDE 日志中的 DEPRECATION 警告 + Gradle `verifyPlugin` 任务可一次性核销上述清单。

> **构建迁移备注（2026-08 更新）**：构建工具链已从原 1.x 方案迁移到 2.x（`org.jetbrains.intellij.platform` 2.18.1 + Gradle 9.0.0）。**需验证 ④**：2.x 中 `pluginConfiguration`/`ideaVersion`（sinceBuild/untilBuild）、`verifyPlugin`、`pluginVerification.ides` 的确切 DSL 与属性名以实际 Gradle 文档/`gradlew tasks` 输出为准——我按 2.10.x 示例核实过，但请以 2.18.1 实际为准。功能代码（Java）零改动，仅构建脚本语法变化。
