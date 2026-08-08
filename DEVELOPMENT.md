# 安装与开发说明

PSRunner 的安装与开发相关说明。功能使用见 [README.md](README.md)。

---

## 环境要求

- **JDK 21**（2026.1 SDK 为 Java 21 字节码，编译必需）。本机路径示例：`D:\JDK\jdk21`
- **Gradle**：由 wrapper 自动下载（`gradle/wrapper/`），无需手动安装
- **IntelliJ IDEA** 2026.1+（目标运行环境）

---

## 安装

### 方式一：源码构建（开发调试）

在项目根目录执行：

```bash
./gradlew runIde        # 启动沙盒 IDEA，插件已自动安装
```

`runIde` 会构建插件并启动一个独立的沙盒 IDEA，插件自动加载，无需手动安装。沙盒配置在 `.intellijPlatform/sandbox/`。

### 方式二：安装构建好的插件包

```bash
./gradlew buildPlugin   # 在 build/distributions/ 生成插件 zip
```

然后在 IDEA 中：

1. `Settings → Plugins`
2. 点右上角 ⚙️ 齿轮 → **Install Plugin from Disk...**
3. 选择 `build/distributions/` 下生成的 zip
4. 重启 IDEA

### 插件结构

安装后的插件包结构：

```
PowerShell Script Runner/
└── lib/
    └── psrunner-0.1.0.jar   # 插件 jar（含 META-INF/plugin.xml 描述符）
```

> **注意**：插件描述符必须位于 jar 内的 `META-INF/plugin.xml`（源码路径 `src/main/resources/META-INF/plugin.xml`）。若放在 jar 根目录，IDEA 无法识别插件，右键菜单不会出现。

---

## 开发

### 技术栈

- IntelliJ Platform Gradle Plugin **2.18.1** + Gradle **9.0.0**
- **JDK 21**（`options.release = 21`，产出 65 字节码）
- 目标 IDE：IntelliJ IDEA **2026.1+**（`sinceBuild = '261'`，`untilBuild` 不限）
- 零第三方依赖，仅 IntelliJ Platform 标准 API

### 验证命令

```bash
./gradlew build            # 编译
./gradlew verifyPlugin     # Plugin Verifier 兼容性检查
./gradlew runIde           # 沙盒运行
./gradlew buildPlugin      # 打包插件 zip
```

### 项目结构

```
src/main/java/com/example/psrunner/
├── data/     持久化实体与 DTO
│   ├── ScriptConfig                   脚本配置实体（name/command/enabled/killProcessTree）
│   ├── PowerShellScriptSettings       app 级 PersistentStateComponent
│   ├── ResolvedPaths                  运行期解析结果 record
│   └── ExecutionResult                执行结果三态 sealed interface（Success/Failure/Terminated）
├── service/  执行编排（不依赖 Swing）
│   ├── ScriptExecutionService         核心编排：占位符解析 → 写临时文件 → 启动进程 → Run 窗口
│   ├── PlaceholderResolver            占位符强制解析（{{SelectedDir}} 等）
│   ├── TempScriptFileManager          临时文件创建（UTF-8 BOM）/清理/惰性清扫
│   └── PlaceholderResolutionException 占位符解析失败异常
└── ui/       菜单与对话框
    ├── actions/PowerShellScriptsActionGroup   动态右键菜单（BGT 线程）
    ├── actions/ScriptExecutionAction          单脚本执行
    ├── actions/ConfigureScriptsAction         打开配置对话框
    ├── dialog/ScriptConfigDialog              配置列表对话框
    ├── dialog/ScriptEditDialog                脚本编辑二级对话框
    └── run/RunTabNamer                        唯一标签名生成

src/main/resources/META-INF/plugin.xml   插件描述符
```

### 2026.1 适配要点（实现期实测）

以下是与旧版 API 的关键差异，改代码时注意：

| 旧 API | 2026.1 实际情况 | 处理 |
|---|---|---|
| `AnAction.getFamilyName()` | 已移除 | 不要重写 |
| `<actionGroup>` 元素 | 已改为 `<group>` | plugin.xml 用 `<group>` |
| `DefaultRunExecutor` | 移到 `com.intellij.execution.executors` 包 | 更新 import |
| `ConsoleViewUtil.createConsoleView` | 已移除 | 用 `TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole()` |
| `RunContentDescriptor.builder(...)` | builder 移除 | 用构造函数 `(console, handler, component, name)` |
| `Module.getModuleRootManager()` | 已移除 | 用 `ModuleRootManager.getInstance(module)` |
| `update()` 读 `VIRTUAL_FILE` | 必须在后台线程 | override `getActionUpdateThread()` 返回 `ActionUpdateThread.BGT` |
| `willBeDestroyed` 参数 | 自然退出时恒为 true | 「被用户终止」用 `handler.isProcessTerminating()` 判定 |

### 契约文档

- **产品需求 / 实现契约**：[PSRunner.md](PSRunner.md)（决策 1~9、进程边界契约）
- **占位代码 / 时序图 / 异常地图**：[deliverables_03.md](deliverables_03.md)
- **变更管理**：`openspec/changes/psrunner-mvp/`（proposal / design / specs / tasks）

---

## 常见问题

### Q: 右键目录后没有「PowerShell Scripts」菜单

1. 确认插件已加载：`Settings → Plugins` 应显示 "PowerShell Script Runner"；
2. 确认插件描述符位置正确（见上文「插件结构」注意事项）；
3. 确认 `update()` 已声明 `ActionUpdateThread.BGT`（见适配要点）；
4. 重新 `runIde`，并查看沙盒日志 `.intellijPlatform/sandbox/psrunner/IU-2026.1/log/idea.log` 是否有 SEVERE。

### Q: 脚本执行后显示「被用户终止」但没点停止

这是已修复的误判：`willBeDestroyed` 参数在自然退出时恒为 true，不能用于判断用户停止。请确认使用的插件版本包含 `isProcessTerminating()` 修复（重新构建）。

### Q: 构建报 "类文件具有错误的版本 65.0"

表示编译 JDK 版本过低。2026.1 SDK 为 Java 21 字节码（65.0），需用 **JDK 21** 编译（`JavaLanguageVersion.of(21)`）。用 JDK 17 编译会报此错。
