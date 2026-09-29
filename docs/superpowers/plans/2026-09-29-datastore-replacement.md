# DataStore 替换 MMKV 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 Preferences DataStore 承接宿主的 30 个设置键，切换所有调用方，彻底删除项目维护的 MMKV 代码、依赖与配置。

**Architecture:** `base:general` 装配唯一宿主 DataStore；配置、身份、广告、备份等领域通过强类型仓库与挂起写入访问。WebDAV 密码使用独立 Keystore 密钥加密；备份仍使用现有 ZIP 白名单。Shizuku 通过宿主 Provider 获取身份，不直接打开设置文件。

**Tech Stack:** Kotlin 2.2、Hilt、Coroutines/Flow、Preferences DataStore 1.2.1、Android Keystore、JUnit、Gradle。

**Spec:** `docs/datastore_replacement_plan.md`

## 全局约束

- 开发阶段直接替换；不迁移旧 MMKV、不双写、不保留 MMKV 代码、专用测试、依赖或 XML 排除项。
- 业务方法、变量和关键分支补简体中文注释；用户可见文案进 strings.xml；诊断日志仅含低敏字段。
- `base:general` 不依赖 `feature:torrent-search`；仅将可复用 Keystore 原语下沉。
- 每个物理 DataStore 文件每进程仅一个实例。现有 TorrentSearch 文件保持独立。
- 现有备份 ZIP schemaVersion 与白名单保持原契约；数据恢复和 Worker 的失败必须可观察。
- 修改先跑相关单元测试，再运行宿主编译和必要的 Release/R8；测试使用合成数据。

## 评审重点

1. Provider 冷启动先于 Application：身份生成只得到一个持久化值，Binder 失败可重试，Main 不阻塞。
2. 保存凭据时 Keystore 失败：配置不被部分提交，旧密码不因目标变更被静默发送。
3. 来源过滤名单连续增删：两个并发操作不覆盖，读取失败时不放行来源。
4. 备份导出期间发生变更：成功确认旧版本后仍 dirty；部分目标失败仍保留待备份。
5. 设置文件损坏：广告、备份、清理和剪贴保存均按安全失败处理，不能把 IO 故障当作新安装默认值。

---

### Task 1：宿主存储与纯设置规则

**文件：** `base/general/build.gradle.kts`、`base/general/src/main/java/com/cla/clip/base/general/config/datastore/HostSettingsDataStoreModule.kt`、同目录 `HostSettingsStore.kt`、`HostSettingsKeys.kt`；相关 `src/test`。

**接口：** `internal class HostSettingsStore(dataStore: DataStore<Preferences>)` 提供 `val data: Flow<Preferences>`、`suspend fun snapshot(): Preferences` 与 `suspend fun edit(block: (MutablePreferences) -> Unit): Preferences`；只允许领域仓库使用原始 Preferences。Module 以 qualifier + `@Singleton` 提供一个 `app_settings` 实例。

- [ ] 写临时文件/假 DataStore 测试：缺失文件默认值、提交后重建可读、两个实例不得共用文件；先确认涉及新增行为的测试为红。
- [ ] 引入现有版本目录 DataStore alias，实现唯一实例、领域 key、读 IO 错误传播与挂起写入。
- [ ] 运行 `:base:general:testDebugUnitTest`、`:base:general:compileDebugKotlin` 并核对绿灯。

### Task 2：剪贴、广告、身份及轻量运行设置

**文件：** `base/general/.../config/AppSetting.kt` 中相关规则拆到 `config/clip/ClipSettingsRepository.kt`、`config/ad/AdSettingsRepository.kt`、`config/installation/InstallationIdentityRepository.kt`、`config/download/VideoDownloadRuntimeStore.kt`、`config/update/AppUpdateCheckStore.kt`；更新 `base/general/src/test`。

**接口：** 仓库暴露领域 `Flow<Snapshot>`、`suspend fun snapshot()` 和按动作命名的挂起写入；身份仓库提供 `suspend fun getOrCreateInstallId(): String`、`suspend fun setExpectedProcessName(String)`。剪贴仓库提供 `suspend fun addBlockedPackage(String): Boolean`、`removeBlockedPackage(String): Boolean`、`replaceBlockedPackages(Iterable<String?>): Boolean`，均在 `edit` 内判断。

- [ ] 先写并运行默认值、代码映射、并发名单更新、只生成单份安装 ID、广告未同意默认状态的失败测试。
- [ ] 实现领域仓库并复用 `ClipSourceBlockRules`、`NumericInstallIdGenerator`、`ClipItemQuickAction` 的稳定值规则。
- [ ] 运行 `:base:general:testDebugUnitTest`、`:base:general:compileDebugKotlin`。

### Task 3：剪贴/UI/下载/广告消费方

**文件：** `MineVm`、`ClipSourceBlockSettingVm`、`DetailViewModel`、`RecycleBinVm`、`ClipHelper`、`ShizukuConnector`、`MainActivity`、列表/搜索页、广告模块与下载/回收站 Worker 等 `AppSetting` 消费文件，以及必要字符串资源。

**接口：** 注入 Task 2 的领域仓库；页面通过 ViewModel 和生命周期收集只读状态；后台入口 `suspend` 等待真实设置快照；同步 Binder 边界只在 Task 4 处理。

- [ ] 为设置保存失败、过滤读取失败和后台清理安全失败补回归测试并运行红灯；纯接线复用既有测试。
- [ ] 切换消费方，保存成功后再提示或触发副作用；只更新真正需要的 UI 状态和资源文案。
- [ ] 运行受影响模块单测及 `:app:compileDebugKotlin`，同时验证可选广告模块组合。

### Task 4：Provider 与 Shizuku 冷启动

**文件：** `ClipboardBridgeProvider.kt`、`ClipboardBridgeShizukuProcessCoordinator.kt`、`ShizukuConnector.kt`、`BaseApplication.kt`、相关协议测试。

**接口：** 协调器先挂起取得已提交 ID、写入进程名，再执行连接请求；Provider 的同步 Binder 边界使用局部限时桥接并返回明确错误码，不在 `onCreate()` 读取磁盘。

- [ ] 写 Provider 先于 Application、并发身份读取、超时和 Main 保护测试，运行红灯。
- [ ] 移除 MMKV 冷启动初始化；复用 Hilt 唯一 DataStore，保持请求结果协议与权限边界。
- [ ] 运行 `:app:testDebugUnitTest` 和 `:app:compileDebugKotlin`；无设备时记录 Binder 实机验证待办。

### Task 5：WebDAV 加密、备份设置与运行状态

**文件：** 新增 `base/general/.../security/AndroidKeystoreSecretCipher.kt`、`backup/settings/BackupSettingsRepository.kt`、`BackupRuntimeRepository.kt`；调整 Torznab 安全适配、`BackupVm`、`BackupAutoWorker` 和相关测试。

**接口：** 配置仓库提供一次性强类型快照、`suspend` 成组保存和凭据状态；运行仓库提供 `suspend fun markChanged()`、`suspend fun captureRevision(): Long`、`suspend fun acknowledgeRevision(Long)`。Keystore 底层由 alias/AAD 参数化，WebDAV 与 Torznab 分别使用独立 alias。

- [ ] 先写凭据失败不部分提交、密文篡改/缺钥、连续 dirty、旧版本确认不清新变化的失败测试。
- [ ] 实现密文存储、强类型仓库、Worker 与 UI 适配；配置关联字段一次提交。
- [ ] 运行 base、app、torrent-search 单测及相关编译。

### Task 6：备份/恢复与系统备份闭环

**文件：** `BackupSnapshotExporter.kt`、`BackupSnapshotRestorer.kt`、`BackupEntityMappers.kt`、`BackupVm.kt`、`BackupRestoreVm.kt`、`BackupAutoWorker.kt`、`BackupTaskGate.kt`、各 Repository dirty 调用、`backup_rules.xml`、`data_extraction_rules.xml`。

**接口：** 现有 `BackupSettings`/ZIP schema 保持；Exporter 一次性读设置快照；Restorer 挂起且同事务恢复白名单；备份完成只确认捕获的 revision。每日周期备份全量兜底，短延迟任务仍按 dirty 合并。

- [ ] 写设置快照一致、名单恢复并集、设置阶段部分失败、导出中新增变化、系统排除规则测试并运行红灯。
- [ ] 切换导出、恢复、dirty marker 和 Worker；XML 替换为 DataStore 排除，删除 MMKV 排除。
- [ ] 运行受影响单测、`:app:compileDebugKotlin` 和备份集成验证。

### Task 7：删除 MMKV、文档同步与最终验证

**文件：** 删除 `MmkvInitializer.kt` 与旧 `AppSetting.kt` 持久化门面；修改 Gradle 版本目录、`base/general/build.gradle.kts`、相关注释与 docs 主方案。

- [ ] 搜索所有源码、测试、构建和 XML 中的 MMKV 相关实现；迁移仍有价值的业务测试，删除只服务 MMKV 的测试/代码/依赖。
- [ ] 更新 `docs/datastore_replacement_plan.md` 状态、`docs/webdav_backup_plan.md` 和各受影响页面主文档，记录实际边界、日志与取舍。
- [ ] 运行 `:base:general:testDebugUnitTest`、`:app:testDebugUnitTest`、`:feature:torrent-search:testDebugUnitTest`、`:app:compileDebugKotlin`、`:app:minifyReleaseWithR8` 与 `git diff --check`；检查可选模块组合和 APK 解析依赖。
- [ ] 自检代码注释、字符串资源、生命周期、隐私日志、备份覆盖和工作区差异；无设备项记录实际限制。

## 变更记录

- 2026-09-29：依据已确认的替换方案和“彻底删除 MMKV”约束创建实施计划；按先基础接口、再消费方、最后删除依赖与验证的顺序拆解。
