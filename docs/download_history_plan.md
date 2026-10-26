状态：已完成

# 下载记录页面方案

## 下载媒体记录功能移除方案（2026-10-26）

本节为已完成的移除实现；设备验收仍待进行，具体边界见实施记录。用户明确开发阶段无需旧数据兼容，2026-10-26 授权实施。下方原页面设计、审查和验证均为已废弃功能的历史背景，不再代表当前实现或待办；本轮不清理设备数据。

**目标**：移除宿主视频/图片下载历史管理及其备份、恢复、媒体重新定位负担，保留正常提取、下载和当前任务结果体验。

**架构**：沿用 Room + Repository + WorkManager 的下载执行链路，把三张相关表限定为本机任务数据；删除历史页面及专属查询，备份恢复只处理保留的数据类别。不引入替代历史页、功能开关、新任务框架或跨模块反向依赖。

**技术栈**：继续使用 Kotlin、Compose、Room、WorkManager、kotlinx.serialization 和现有 ZIP/JSONL 备份器，无新增生产依赖。

**设计依据与执行方式**：本节承载删除范围与任务计划；备份协议细节只在 [备份主方案](webdav_backup_plan.md#下载媒体任务退出备份恢复方案2026-10-26) 维护。本次按 `superpowers:executing-plans` 在专用分支内执行，最终做一次独立审查。

### 已确认前提与方案取舍

| 做法 | 影响 | 本次选择 |
| --- | --- | --- |
| 只隐藏入口 | 历史查询、媒体关联和备份协议继续存在，维护负担没有消失 | 不采用 |
| 删除历史功能及备份耦合，保留下载所需本机任务表 | 下载体验可延续，主要历史管理复杂度被移除，改动范围可控 | 已确认，按此实现 |
| 同时删除任务表、把下载完全改成内存状态 | 需重做 Worker 输入、进度、重试、进程恢复及 pending 输出清理，扩大为下载架构重构 | 不纳入本次 |

用户已确认无需旧数据兼容，并进一步明确不得影响 Android 系统自动备份；允许系统恢复时带回多余任务数据，不为此增加排除、迁移或清理机制。具体删除边界与自定义备份格式 5 已按下列任务落地，验证状态见实施记录。无需为旧历史记录、旧路由或旧备份制作迁移、降级读取、占位页面和兼容字段；也不借本次任务清理全仓其他领域的历史迁移。

### 额外保留与自主取舍复核（2026-10-26）

| 项目 | 原方案的处理 | 性质与原因 |
| --- | --- | --- |
| 已完成任务行 | 不新增立即删除或定期清理 | 属于暂缓清理的保守取舍；避免当前结果、重试及 pending 输出清理失去输入。旧行允许存在，不增加后台保留策略 |
| 任务表字段与索引 | 保留现有实体结构，未单独做字段/索引瘦身 | 属于范围收敛的保守取舍；例如视频表仍有 `total_size`、`downloaded_size` 预留字段。本轮未承诺它们全部必要，也未逐项证明可删；这是不同于保留任务表本身的决定 |
| 三张任务表、当前重试、共享组件、恢复请求会话 | 继续保留仍有消费者的部分 | 当前功能的实际依赖，并非为了兼容旧数据；删除它们会改变下载或恢复的现有执行链路 |
| 自定义备份格式 5 与旧包拒绝 | 协议升级并复用现有不支持版本错误 | 是额外确定的协议边界，不是旧数据兼容方案；不新增升级转换、历史读取或回填 |
| 已发布媒体、既有备份文件、其他功能迁移 | 不主动删除或顺带重构 | 不属于宿主历史页面代码退役的清理目标；旧备份不承诺可读，也不为保存文件增加兼容实现 |

本次修订只撤回改变 Android 系统备份的建议，并把上述自主取舍显式列出；未把任务容量清理、表结构瘦身或新取消按钮悄悄加入实施范围。测试、checksum 与恢复阶段失败处理属于剩余功能的正确性验证，不因开发阶段而替换为无校验流程。

### 用户体验与数据边界

1. “我的”页不再显示“下载记录”，应用中不再提供视频/图片历史 Tab、历史缩略图浏览、历史批量删除和历史重新下载。
2. 视频/图片提取、确认下载、前台进度、失败重试及当前页面结果展示保持；下载成功的文件仍由系统相册或文件管理器管理。当前主动取消下载依赖历史页“删除进行中记录”，详情页和进度通知没有独立取消按钮；该用户入口随历史页一起移除，详情页返回仍按现有行为让下载继续。本次建议接受这一功能减少，不额外增加取消 UI 或全局任务中心；底层取消传播、冷启动现有策略和失败清理保留。
3. 保留 `download_tasks`、`image_extract_batches`、`image_extract_items` 及任务执行必需字段。它们同时承载 Worker 输入、状态、图片候选、输出引用和当前页面结果，不是可以整体删除的“历史专用表”。本次不改 Room 实体结构，因此不需要新增 Room 迁移或重置数据库版本。
4. 删除功能不删除已发布媒体，不调用 MediaStore 删除授权，也不扫描相册。当前任务失败后处理 pending 输出、临时文件和网络取消的既有行为仍保留。
5. 本机任务行暂时沿用现有保留方式，不在 Worker 完成或页面离开时立即删除，避免丢失当前结果、图片重试输入和异常输出清理依据。本次不新增自动清理 Worker；任务行仍可能累积，这是明确的范围取舍，不能宣称“所有下载数据都已删除”。后续若容量成为问题，再设计基于活动任务与页面持有状态的独立清理策略。
6. 应用内新生成的本地/WebDAV 备份不含上述三类数据，自定义恢复结果不再出现媒体重新定位入口、媒体读取授权、匹配进度及关联结果。Android 系统自动备份和设备迁移配置保持现状，允许其整库恢复带回任务行；不因此增加额外恢复处理。具体边界以备份主方案为准。
7. TorrentSearch 的独立下载/历史、可选功能备份参与者不属于宿主媒体历史，继续保留；不把“下载记录”文本匹配当作全仓删除依据。

### 复用检查与保留清单

按页面 package → app 模块 → base/feature 共享模块 → 全仓引用核查，得到以下边界：

| 能力 | 决策及代码证据 |
| --- | --- |
| 视频下载 Repository、DAO、Worker、状态观察 | 保留。`DownloadVideoWorker.doWork()` 读取任务和最近任务的 pending 输出；`VideoDownloadVm.startDownload()` 仍观察和重试当前任务；`VideoDownloadWorkObserver` 与 `VideoDownloadStartPolicy` 继续提供中断/重试规则 |
| 图片批次与条目、`cloneBatchForRetry` | 保留。`ImageExtractVm.retryDownload()` 仍调用 `ImageExtractRepository.cloneBatchForRetry()`，不能随历史页一起删除 |
| `createRetryTask`、历史 Paging/COUNT/id 批量接口 | 删除前再次核对调用方；当前视频历史克隆、历史查询和多选删除接口随历史功能退役，单任务接口按剩余调用保留 |
| 按 taskId/batchId 取消的用户入口 | `DownloadHistoryVm` 在删除记录前调用两类 Worker 的 cancel；详情页和通知未提供相同动作。本次随历史管理退役，不宣称主动取消体验保持；保留仍被系统/冷启动/执行链路使用的取消能力 |
| `ImageHistoryFileRef`、`VideoMediaReferenceUpdate`、`ImageMediaReferenceUpdate`、`ImageRelocationBatchSummary` | 在历史页和重新定位消费者删除后，删除无其他调用的模型与查询；不迁入新的万能工具类 |
| 共享图片预览、媒体网格、分页状态、选择操作条与卡片外壳 | 保留其他页面仍使用的实现。共享组件主文档只减少调用方，不重新描述其完整契约 |
| `base_general_download_history_retry_failed`、`base_general_download_history_filtered` | 图片提取页仍使用；迁到图片下载语义的资源 key 并修改引用，再删除旧 key，保留现有显示文案 |
| `BackupPackageWriter`、文件型 Reader、任务互斥、分阶段恢复与报告 | 复用现有能力，只收缩数据类别；保持 Room、可选模块、DataStore 的阶段失败边界 |
| 每日备份与 dirty | 当前 `BackupAutoWorker` 已不读取业务 dirty，普通业务保存不触发备份。本次无需新增或删除 dirty 机制，WebDAV 表单的 draft dirty 状态与此无关 |

### 文件与职责清单

以下路径均相对仓库根目录；前缀 A 为 `app/src/main/java/com/cla/clip/master/`，G 为 `base/general/src/main/java/com/cla/clip/base/general/`。

| 范围 | 文件与处理 |
| --- | --- |
| 历史页面 | 删除 A 下 `ui/page/download/DownloadHistoryPage.kt`、`DownloadHistoryVm.kt`、`DownloadHistoryContent.kt`、`DownloadHistoryChrome.kt`、`DownloadHistoryCards.kt`、`DownloadHistoryDialogs.kt`、`DownloadHistoryPagerPolicy.kt` |
| 页面入口与路由 | 修改 A 下 `ui/page/mine/MinePage.kt`、`MineEntries.kt`、`ui/navigation/Routes.kt`、`AppNavigation.kt`，移除 `DownloadHistoryEntry`、`DownloadHistoryRoute` 和目的地注册 |
| 媒体重新定位 | 删除 A 下 `media/DownloadedMediaRelocator.kt`、`ui/page/backup/BackupMediaRelocationPage.kt`、`BackupMediaRelocationContent.kt`、`BackupMediaRelocationVm.kt`、`BackupMediaRelocationEvents.kt`、`BackupMediaRelocationStateReducer.kt`、`BackupMediaRelocationAdPolicy.kt` |
| 恢复页面与会话 | 修改 A 下 `ui/navigation/BackupNavigation.kt`、`Routes.kt`、`ui/page/backup/BackupRestoreState.kt`、`BackupRestoreVm.kt`、`BackupRestoreFlowPage.kt`、`BackupRestoreFlowContent.kt`、`BackupFlowSession.kt`；按引用调整 `BackupFlowVm.kt`，保留请求提交、接管、释放、关闭与迟到请求隔离 |
| 任务数据接口 | 修改 G 下 `dao/DownloadDao.kt`、`dao/ImageExtractDao.kt`、`repository/DownloadRepository.kt`、`repository/ImageExtractRepository.kt`，删除历史/备份/重新定位专属方法；实体和下载执行接口保留 |
| 备份模型与数据链路 | 修改 G 下 `backup/BackupModels.kt`、`BackupJson.kt`、`BackupExportModels.kt`、`BackupEntityMappers.kt`、`BackupSnapshotExporter.kt`、`BackupSnapshotRestorer.kt`、`BackupPackageIo.kt`，按调用删除 `BackupRepository.kt` 中仅为旧格式保留的入口；协议细节见备份主方案 |
| 文案与图片页面 | 修改 `base/general/src/main/res/values/strings.xml`、A 下 `ui/page/image/ImageExtractPage.kt`、`ImageBatchStatusContent.kt`，删除专属文案、修正预检/进度/报告文本，迁移两条复用资源 |
| 广告位 | 删除 `feature/ad-api/src/main/java/com/cla/clip/feature/ad/api/AdContracts.kt` 中 `VideoHistoryNative`、`ImageHistoryNative`、`BackupMediaRelocationNative`；同步 `feature/ad-admob/src/sdk/java/com/cla/clip/feature/ad/admob/AdmobAdSourceEntry.kt` 和 `gradle/admob-ad-config.gradle.kts`，保留下载进度和结果广告位 |
| 权限与系统备份 | `app/src/main/AndroidManifest.xml` 只删除媒体重新定位专属权限及说明；保留 `allowBackup`、备份规则资源引用、`backup_rules.xml` 与 `data_extraction_rules.xml` 的现有内容 |
| 失效测试 | 删除 `app/src/test/java/com/cla/clip/master/ui/page/download/DownloadHistoryPagerPolicyTest.kt`、`app/src/androidTest/java/com/cla/clip/master/ui/page/download/DownloadHistoryPagerStateTest.kt`、`app/src/test/java/com/cla/clip/master/ui/page/backup/BackupMediaRelocationStateReducerTest.kt`；其他测试只移除已退役场景，保留有效回归 |

### 生命周期、权限与规则约束

- 不改变下载页面的可见性收集、Worker 后台执行、重试互斥和临时文件清理边界；不在 ViewModel 初始化时新增扫描或全库任务观察。
- Android 13+ 的 `READ_MEDIA_IMAGES`、`READ_MEDIA_VIDEO`、`READ_MEDIA_VISUAL_USER_SELECTED` 在当前宿主中用于重新定位，随该功能及 Manifest 说明一起移除；实施时检查 merged manifest 和所有模块消费者。Android 12 及以下读写权限按现有媒体/文件能力保留，本次不泛化清理旧存储授权。
- 普通下载文件写入与读取当前自有结果继续沿用已有实现。恢复只需要备份文件的 SAF/网络访问，不再为了历史媒体读取请求相册权限。
- 删除导航类型与序列化模型后检查 `@SerialName`、`@Keep` 和 consumer/R8 规则，仅删已无消费者的专属项，不扩大 keep 范围；最终做 Release/R8 验证。
- 后续修改的方法、变量、字段、回调和关键分支按仓库要求补齐简体中文注释。大类本次以删减职责为主，不为删除功能新建框架；若为状态规则测试确需抽取，只抽单一职责协作者。

### 实施任务与验证门禁

这些任务依次落地；备份模型与 UI 分类消费者在相邻步骤同步删除，阶段验证由基础模块逐步扩展到 app。默认不提交 Git；若用户另行要求提交，再按任务边界使用简体中文标题和正文。

#### 任务 1：收缩备份协议和媒体数据读写

**接口**：沿用 `BackupSnapshotExporter.export(source, backupKind, now, taskId): BackupExportResult`、`BackupPackageReader.preview(ref): BackupManifest` 及文件型恢复入口；改为备份主方案定义的格式 5，不再读取或写入三张任务表。

- [x] 先在 `base/general/src/test/java/com/cla/clip/base/general/backup/BackupCurrentFormatTest.kt` 添加 `acceptsVersionFiveWithoutMediaEntries`：五个宿主必需 entry 即可通过预检，清单/摘要不含视频、图片批次和图片项；可选模块 entry 仍可存在。
- [x] 添加 `rejectsOlderSchema` 与 `rejectsNewerSchema`：合成 schema 4 和 6 的包均抛 `BackupFailure.UnsupportedSchema`；不要求保留旧包可读。保留 checksum、坏路径、缺失必需 entry 和损坏 JSONL 的失败测试。
- [x] 先运行上述用例，确认当前格式 4/必需媒体 entry 使目标行为失败；随后按备份主方案删除模型、编解码、导出/恢复分支、摘要和报告类别，更新所有构造处与测试 fixture，再删除三张表的备份专属 DAO 方法。
- [ ] 保留可选 feature contributor、剪贴/来源/预览/搜索/设置恢复及幂等规则。为真实表隔离新增 `base/general/src/androidTest/java/com/cla/clip/base/general/backup/BackupRuntimeMediaIsolationTest.kt`，复用既有 in-memory Room 测试方式；断言导出含用户数据但无任务数据、重复恢复幂等、恢复不修改预先存在的活动任务。实现与测试源码编译已完成；本机没有隔离 AVD，仪器用例未执行，不能把源码编译当作测试通过。
- [x] 运行受影响模块单测与编译，确认格式 5 回归通过。此时历史页仍可使用本机记录，阶段性移除备份不影响下载。

#### 任务 2：删除恢复后的媒体重新定位

**接口**：`BackupFlowSession` 仅保留 `submitRequest`、`acknowledgeRequest`、`clearPendingRequest`、`finishRestore`、`close` 及恢复请求流；删除媒体状态流、媒体事件和媒体导航门禁。恢复成功/部分成功报告仍由既有状态模型表达。

- [x] 在 `app/src/test/java/com/cla/clip/master/ui/page/backup/BackupFlowSessionTest.kt` 保留并补齐请求重复提交拒绝、接管后占用、完成释放、关闭后迟到请求拒绝场景；移除 `registerRestoreTask`、`publishMediaEvent` 等退役契约的断言。若抽出新的退出或状态规则，先写失败测试。
- [x] 删除文件清单中的重新定位实现、路由、按钮、状态、协程、权限 launcher、summary 回显和运行中阻止退出分支。移除“完整恢复之后才能关联媒体”的额外状态门禁。
- [x] 保留读取/校验/恢复期间的既有忙碌门禁、取消处理、临时包释放、普通返回和完成退出整组逻辑；删除仅含媒体场景的 `BackupRestoreStateTest.kt`，保留 `BackupPartialRestoreOutcomeTest.kt`，不能把 Room 已提交而设置失败改成完全失败。
- [x] 删除专属 reducer 测试，运行 `:app:testDebugUnitTest` 与 `:app:compileDebugKotlin`。
- [ ] 隔离设备验收成功、失败、部分成功均无媒体关联入口且返回正常。

#### 任务 3：移除历史页面，清理数据接口与资源

**接口**：保留 `observeTask(taskId)`、任务写入/查询及 `cloneBatchForRetry(batchId)` 等当前下载消费者所需接口；不新增替代历史接口。

- [x] 删除历史页面七个文件、我的页入口、路由和两个历史页测试；这部分是功能删除和薄接线，不写“源码中不包含某字符串”式单测。
- [x] 由当前调用方反查 DAO/Repository，删除历史 Paging、COUNT、id 列表、多选删除、历史视频克隆和重新定位接口；保留下载详情重试、图片克隆、pending 输出与批次观察能力。
- [x] 将两条图片页面复用资源改为 `base_general_image_download_retry_failed`、`base_general_image_download_filtered`，迁移引用后删除旧 key；只删已无调用的其余历史/媒体关联文案。
- [x] 移除三个专属广告 placement、SDK 分支和构建配置；删除无使用的 import/资源，不改其他广告会话策略。检查共享 UI 的剩余调用，不能连同共享组件一起删除。
- [x] 运行核心单测、编译、AndroidTest 源码编译；保留 `VideoDownloadStartPolicyTest`、`VideoDownloadFailureMessageTest`、`ImageBatchPresentationTest` 和图片提取会话回归。

#### 任务 4：收口媒体权限与文档，保持系统备份

- [x] 删除仅重新定位使用的 Android 13+ 媒体读取权限和注释；Debug/Release merged manifest 已核对。
- [ ] 实际发布/预览在目标系统版本验收。
- [x] 核对本次差异未修改 `allowBackup`、备份资源引用、`backup_rules.xml`、`data_extraction_rules.xml`；不增加主库/sidecar 排除，不拆新任务库，不实现 BackupAgent 或系统恢复后的任务清理。现有敏感数据排除保持原状。
- [x] 更新本主文档、备份、我的页、图片/视频、广告和单测主文档；共享媒体/表单/卡片文档只减少已退役调用方。将本节状态推进为实现中/已完成，并明确旧页面方案已废弃；清理历史页专属测试债。
- [x] 已更新备份导航组、恢复结果和媒体关联 Mermaid 图并重新生成 HTML。

#### 任务 5：完整回归与交付

- [x] `.\gradlew.bat :base:general:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugKotlin :base:general:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin --console=plain`，期望全部成功。
- [x] `.\gradlew.bat :app:assembleRelease --console=plain`，期望打包及 R8 成功并保留 mapping；覆盖删除导航/序列化/广告分支后的正式变体。
- [ ] 仅在无用户数据的专用设备/模拟器运行 `.\gradlew.bat :base:general:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.cla.clip.base.general.backup.BackupRuntimeMediaIsolationTest' --console=plain`；不在现用手机运行可能卸载应用的 connected 测试。
- [ ] 人工验收：无历史入口；视频下载成功/失败重试、返回后仍按原行为下载；图片单批成功、部分失败、重试和结果预览；系统中断/冷启动的既有取消与清理不回归；文件仍在相册；本地/WebDAV 新包往返恢复、旧格式拒绝、部分成功与返回；恢复不请求相册权限。确认历史页主动取消入口已随功能删除，不以不存在的详情取消按钮作验收项。设备不可用的项目逐项列为未验证。
- [x] 全仓引用扫描核对 `DownloadHistory`、`BackupMediaRelocation`、`DownloadedMediaRelocator` 和三个退役 placement 仅剩明确历史文档，TorrentSearch 与保留能力允许相关通用“下载”命名继续存在。
- [x] `python -X utf8 tools/render_doc_flow_html.py --check docs/webdav_backup_plan.md`、`git diff --check` 均通过，审查只包含授权范围。

### 重点复核场景

1. 当前图片失败后重试仍能复制批次，不能因 `cloneBatchForRetry` 名似历史能力被误删；由任务 3 既有回归和专用设备当前任务验收覆盖。
2. 恢复时已有下载活动，恢复不改其 id、状态和输出引用；由任务 1 Room 隔离测试覆盖。
3. 格式 5 包没有媒体 entry 也能恢复，可选模块数据仍走既有 contributor；由任务 1 包测试及模块合成回归覆盖。
4. Room 已提交而设置失败仍呈现部分成功，完成与返回不依赖已删除的媒体状态；由任务 2 状态和会话测试覆盖。
5. 删除历史页后媒体文件仍存在，预览和旧系统存储能力正常；由任务 4 merged manifest 检查、任务 5 专用设备验收覆盖。

### 日志与诊断计划

本次不新增运行时日志；既有恢复、下载和会话诊断已经覆盖保留流程。随代码删除历史分页、解码、文件存在性扫描、批量删除、媒体匹配和权限流程的日志点，不为已移除流程保留空日志。

- 下载保留既有开始、阶段、终态、取消及临时输出清理诊断；本次触及的日志只允许 taskId/batchId、状态、数量、耗时、异常类型和 reasonCode，禁止任务对象、来源标题、完整 URL/路径/URI、Cookie 和请求上下文原文。
- 备份保留 INFO 完成摘要、DEBUG 阶段数量和 WARN/ERROR 分类失败；删除媒体类别及“待关联数量”，版本不支持记录 `schema_unsupported` 与版本号，校验失败发生在写库之前。
- 会话保留 DEBUG 请求提交/接管/释放/关闭和 WARN 迟到拒绝；移除媒体事件、媒体导航状态日志，不打印恢复请求携带的文件 URI。
- 验证日志字段与终态和报告一致；网络重试、本地/WebDAV 并行、取消与部分成功诊断继续由既有测试和人工检查覆盖，进度不逐项刷屏。

### 后续待办与本轮验证边界

- 任务行的容量和过期清理单独评估，本轮不凭时间或页面退出删除仍可能被 Worker/结果页使用的数据。
- 未来只有出现明确的历史查询需求才重新设计此功能；源码由 Git 历史保留，不留隐藏页面、配置开关或备用实现。
- 本轮已经修改生产代码和测试，不对设备媒体、数据库和既有备份文件做删除。设备验收未执行，不能把源码编译视为运行通过。
- 本轮文档验证：相对链接与标题锚点检查通过（8 项），文件/类型清单已与代码核对；`git diff --check` 与 `python -X utf8 tools/render_doc_flow_html.py --check docs/webdav_backup_plan.md` 通过。只读设计检查发现主动取消入口原本属于历史页，已修正范围与验收；方案阶段测试尚未执行；实施结果以下方记录为准。

### 实施记录（2026-10-26）

- 分支 `codex/remove-media-download-history`，基线 `6ad16a5`。沿用当前目录以保留本任务七份未提交方案，未创建物理 worktree；所有差异按授权范围复核，未提交或推送。
- 格式 5 回归先红后绿：新增 7 项协议测试，旧实现 5 项按预期失败；删除媒体备份与旧数组入口后基础模块单测通过。app 删除链路后编译和现有单测通过。
- 删除历史页与媒体重新定位、专属 DAO/Repository/Worker 单任务取消入口、三个广告位及资源；图片当前批次重试、视频当前任务重试、Worker 执行和冷启动全局取消保持原实现。
- `BackupRestoreStateTest` 实际全部为媒体 UI/广告场景，随功能整文件删除；请求所有权测试保留并增加会话隔离与关闭后拒绝，部分成功测试保留。
- 新增 `BackupRuntimeMediaIsolationTest`，使用内存 Room 和独占临时 DataStore；编译验证中修正合成 ClipData 必填字段。无隔离 AVD，未运行仪器测试，因此这一项不宣称 TDD 红绿或运行通过。
- 验证通过：`:base:general:testDebugUnitTest` 128 项、`:app:testDebugUnitTest` 316 项、`:feature:ad-api:testDebugUnitTest` 19 项、`:feature:ad-admob:testDebugUnitTest` 16 项、`:feature:torrent-search:testDebugUnitTest --tests '*BackupModelsTest'` 4 项，共 483 项，失败/错误/跳过均为 0。
- 编译/构建通过：`:app:compileDebugKotlin`、`:base:general:compileDebugAndroidTestKotlin`、`:app:compileDebugAndroidTestKotlin`、`:app:assembleRelease`（含 R8）。保留 release APK 与 `app/build/outputs/mapping/release/mapping.txt`；构建有 Shizuku `OnOpNotedListener` 类型警告，不影响本次构建成功。
- 范围核验通过：三张实体的字段/索引声明与基线相同，`AppDatabase.kt` 未改；两份系统备份 XML 和三个 Manifest 备份属性逐项一致；Debug/Release 合并 Manifest 无三项退役媒体权限。退役类型/资源/placement 在生产代码中无引用。
- 文档验证：HTML 生成及 `--check`、`git diff --check` 通过；当前导航/恢复图没有媒体步骤。
- 独立最终审查未发现 Critical/Important 回归；唯一 Minor 是单测计划仍列旧协议兼容和媒体重新定位，已核实并清理，连同历史页 ViewModel 测试债一起移除。审查中未重新评价的任务行累积、系统恢复多余行、旧路由/旧包兼容、替代取消 UI 和 TorrentSearch 自身行为均符合本次已确认边界，接受该范围划分。
- 规则边界：UI 删除/资源重命名不新增镜像测试；协议规则采用 TDD，Room 集成用例因无隔离设备未运行，也未宣称失败先行；无新增运行时日志，因为保留流程已有诊断并已删除退役日志。新增/调整方法与声明补充简体中文说明，不新增生产框架。真实媒体下载/重试、SAF/WebDAV 往返、权限和页面返回仍待隔离设备验收，本轮未安装/卸载用户手机应用。
- 测试与执行证据保存在 Git 忽略的 `build/download-history-removal/`。未提交、未推送。

## 原功能设计与审查记录（历史背景）

## 加载与显示逻辑审查（2026-10-16）

审查状态：已完成；本节修正建议尚未实施。以当前工作区为准，包含已有未提交的视频重试入队等待等改动；本轮只更新本文档。此前 Pager key 测试只证明空数据页面可进入、切换和保存恢复，不代表真实媒体状态、刷新和后台任务链路均正确。

### 当前数据流

1. 页面以生命周期方式收集 `uiState`，四路轻量 COUNT 提供两类总数和运行中数量；全选/清空另取 id，不依赖已加载列表。这些边界合理。
2. 点击 Tab 先改 ViewModel，再执行 Pager 动画；滑动在 settledPage 变化后同步 ViewModel。仅稳定页创建 `LazyPagingItems`，两类滚动位置独立保存，稳定字符串 key 与跨分类选择隔离已有测试。
3. 视频查询全部任务，图片排除 extracted，两者均按更新时间和 id 倒序。分页大小为 20、预取距离为 5，但未配置首次请求大小和缓存上限；本地 Paging 3.4.2 源码确认首次默认请求 60 条、默认不丢弃旧分页。
4. 视频逐条完成存在性、大小、时长、首帧读取后才把分页交给 UI；图片逐批查询成功图片引用，再按批次终态校验本地媒体。映射结果缓存到 ViewModel。
5. 首次加载/空态/首次失败/向后追加已有共享组件。视频任意 refresh loading 都替换旧列表；图片有旧数据则保留列表。图片进入组合与 ON_RESUME 均触发 refresh，视频没有外部媒体变化复查。

### 确认的问题与修正方向

| 优先级 | 触发条件与实际影响 | 代码证据 | 待实施方向 |
| --- | --- | --- | --- |
| 高 | 图片下载中、全部失败、全部过滤时，空 imageUris 一律显示“本地文件已删除”，甚至同时出现“正在下载”和“已删除”。 | `DownloadHistoryCards.kt:207`；`DownloadHistoryVm.kt:789` | 区分下载/发布、没有成功图片、媒体缺失和校验失败；不以空列表直接判断删除。 |
| 高 | 图片重试按钮仅在 deletedLocal 时出现；完全失败或尚有可读图片的部分失败批次没有重试入口，尽管仓库已有克隆能力。 | `DownloadHistoryCards.kt:185`；`DownloadHistoryVm.kt:213,533` | 补失败态动作；部分失败/部分本地缺失的重新下载应明确是整批还是失败项，当前实现为整批。 |
| 高 | 视频每次进度写库会使 Room 分页失效，refresh loading 移除旧列表，并重新执行媒体映射。下载期间容易反复出现加载画面。 | `DownloadDao.kt:198,237`；`DownloadVideoWorker.kt:268`；`DownloadHistoryChrome.kt:184`；`DownloadHistoryContent.kt:64` | 有内容时保留旧列表，独立表达刷新；进度更新与昂贵媒体读取分离。频率及实际闪动程度需设备测量。 |
| 高 | 已加载的视频在系统相册被删除/回收，回到原页面后 Room 不变，仍使用缓存的可播放状态和首帧；图片有前台复查，视频没有。 | `DownloadHistoryContent.kt:218`；`DownloadHistoryVm.kt:350,767` | 统一可见性驱动的媒体复查，避免复查时无条件解码全部首帧。 |
| 高 | 应用冷启动取消遗留视频 Worker，历史页仍只读数据库 downloading/merging，可能一直显示下载中且无重试。当前下载详情页已有 Worker 中断判定，历史页缺失。 | `App.kt:72`；`DownloadHistoryVm.kt:157`；`VideoDownloadVm.kt`；`DownloadHistoryCards.kt:129` | 复用调度状态/中断契约，按需核对任务，避免对全部历史永久订阅。 |
| 中 | 图片页已 RESUMED 时进入组合，主动 refresh 与新增观察者补发 ON_RESUME 重叠，另有 Pager 首次加载。请求可能合并，也可能取消重建，不能把调用次数等同完整查询次数。 | `DownloadHistoryContent.kt:223,228`；本地 LifecycleRegistry 2.10.0 源码 | 收敛一次可见性刷新决策，区分初次加载、切回和外部变化。 |
| 中 | 视频首批最多按默认请求处理 60 条；映射逐条做媒体 IO/解码，getFrameAtTime 先取原尺寸再缩小，Bitmap 随无上限分页缓存保留。图片首批也会逐批查询全部成功引用。 | `DownloadHistoryVm.kt:350,369,767,1041`；本地 PagingConfig 3.4.2 源码 | 先显示轻量记录，媒体按可见项加载并设预算；明确首次请求和缓存边界。开销存在，卡顿/内存峰值未实测。 |
| 中 | 外层 flowWithLifecycle 停止消费，不保证 cachedIn(viewModelScope) 内已经启动的媒体映射在 STOP/切 Tab 时立即取消。 | `DownloadHistoryPage.kt:84`；`DownloadHistoryVm.kt:358,377`；本地 CachedPagingData/CachedPageEventFlow 源码 | 保留分页复用，将昂贵媒体任务放到可取消的可见性边界；不能宣称后台一定反复全量扫描。 |
| 中 | 图片先检查旧 outputDir，目录无媒体就不再校验精确 URI；移动目录但 URI 仍有效的图片可能被误判缺失。权限/查询异常也被折叠为空或 false。 | `DownloadHistoryVm.kt:827,860,877` | 精确引用优先，区分缺失、无权限和读取错误；恢复后的模糊重新定位仍保留目录约束。 |
| 中 | 图片网络阶段 successCount 表示临时文件就绪数；历史直接展示“正在下载 N/N”，发布阶段又未订阅单项状态，看起来完成却没有缩略图。 | `DownloadImagesWorker.kt:244,259`；`DownloadHistoryCards.kt:195,370` | 复用详情页下载/发布阶段语义，使用轻量聚合，避免常驻读取每批全部图片实体。 |
| 中 | 新图片批次/重试批次默认 extracted，排队尚未运行时被历史过滤；提示已开始重新下载却没有新记录。Worker 改状态前启动失败也会留下不可见记录。 | `ImageExtractRepository.kt:53,111`；`ImageExtractDao.kt:391`；`DownloadImagesWorker.kt:72,119` | 区分未确认候选与已提交待执行，补排队/入队失败；若新增持久化状态，再评估数据库及备份兼容。 |
| 中 | 有旧记录的 refresh error 被静默忽略，prepend 错误也没有提示/重试；统计 Flow 与图片引用读取缺少页面级失败状态。 | `DownloadHistoryContent.kt:69,113,165,208`；`DownloadHistoryVm.kt:380,387` | 分开首次、保留内容刷新、前后追加和统计错误，提供可行动的重试。 |
| 中 | busy 遮罩不拦截输入，卡片/标题栏也未统一禁用；重试/删除没有互斥与统一 catch/finally，异常传播且可能跳过 busy 清理，连点可并发提交。 | `DownloadHistoryPage.kt:233`；`DownloadHistoryChrome.kt:201`；`DownloadHistoryVm.kt:517,533,599` | 单一操作状态、互斥入口、禁用/拦截输入、异常分类及 finally 复位，授权等待单独表达。 |

### 冗余、取舍与后续待办

- 图片下载中仍先查询 `getHistoryFileRefs`，映射随后直接丢弃；可先判断是否需要本地校验。
- `shouldCheckLocalImages` 在实体扩展和 UI 模型重复维护，适合收敛为可测试纯规则。
- `Scaffold.bottomBar` 已让位，两个列表仍保留 overlay 时期的多选 `96.dp` padding，形成重复底部留白。
- 缩略图 `LazyRow.items(columns)` 缺稳定 key，移除图片或单双行变化后的身份/横向位置需回归。
- Pager 仅绘制 settledPage，拖动目标页确定为空白；是否接受属于体验取舍，可评估缓存快照或轻量占位，按需加载不等于必须空白。
- `selectedHasRunning` 只随 tab/ids 变化重查，任务后来完成时删除提示可能过期；全选异步结果也只检查 tab，用户取消选择后慢查询仍可能重新打开多选。
- ViewModel 超过千行，混合 UI 模型、分页、解码、媒体校验、旧目录兼容、删除授权和重试。后续依次拆纯状态规则、媒体 reader、删除协作者，避免一次性重写整个流程。

### 复用与实施边界

保留现有 PagerPolicy、独立滚动状态、COUNT/id DAO、共享 Paging 状态、ImageThumbnailTile、SharedImagePreviewBottomSheet 和广告会话边界。视频详情的 VideoDownloadStartPolicy/VideoDownloadWorkObserver、图片详情的 ImageBatchPresentation 已有相近规则；跨页面复用时迁到领域目录，不能依赖另一页面专属 UI 状态。DownloadedMediaRelocator 只负责恢复后定位，不能直接用作普通历史刷新器。

建议先以失败测试锁定占位/重试、中断、保留内容刷新，再收敛刷新触发与可见性取消，之后优化首屏/缓存和平台协作者，补齐操作互斥/失败恢复。数据库任务状态与本地媒体可用性分开表达。本轮没有确定新 schema、备份或外部协议。

### 日志与诊断计划

本轮仅审查文档，不新增运行时日志。后续加载以 DEBUG 记录 tab、触发原因、generation、数量、耗时和缓存命中；媒体查询异常以 WARN 记录 permission_denied/media_query_failed，缺失正常结果只做数量摘要；用户操作以 INFO 记录开始/结果，以 ERROR 记录不可恢复失败，共用短操作标识。禁止标题、完整路径/URI/URL、Cookie、Token、异常原文和逐项高频进度。现有目录查询吞异常、删除日志直接拼接 URI/路径应随对应协作者修复收敛。

### 本轮验证

- 静态追踪页面、Pager、卡片、ViewModel、DAO/Repository、Worker 和现有测试；核对本地 Paging 3.4.2、Lifecycle 2.10.0 源码。上游参考：[PagingConfig](https://github.com/androidx/androidx/blob/androidx-main/paging/paging-common/src/commonMain/kotlin/androidx/paging/PagingConfig.kt)、[CachedPagingData](https://github.com/androidx/androidx/blob/androidx-main/paging/paging-common/src/commonMain/kotlin/androidx/paging/CachedPagingData.kt)。
- `.\gradlew.bat :app:testDebugUnitTest --tests 'com.cla.clip.master.ui.page.download.DownloadHistoryPagerPolicyTest' --console=plain` 成功，2 项测试通过，执行过程中 `:app:compileDebugKotlin` 成功；测试仅覆盖已有选择隔离/动作门，不覆盖本次全部缺陷。`git diff --check` 通过。
- 本轮无生产或测试代码变更，不新增测试、不进入实现 TDD；后续修复上述纯状态、刷新触发、异常/互斥规则必须先补失败测试。现有空 Paging 设备回归不能代替真实状态测试。
- 未执行安装、卸载或 connected 设备测试；首屏耗时、刷新频率、内存峰值、滑动观感和系统媒体访问仍需隔离设备/合成数据验证。没有 Android 实现或协议变更，不单独运行 release/R8。

## Pager 状态 key 闪退修复（2026-10-16，已完成）

状态：已完成。真机从“我的 → 下载记录”进入时抛出 `Type of the key Video is not supported`。根因是 `DownloadHistoryPager` 把不可写入 Android Bundle 的 `DownloadHistoryTab` sealed 单例直接交给 `HorizontalPager.key`，异常在首次页面组合的状态保存容器中触发，不是视频记录或广告素材引起。现已使用分类对应的稳定字符串 key。

- 复用 `DownloadHistoryPagerPolicy` 集中提供分类对应的稳定字符串 key，视频和图片分别使用独立字面值；不使用页码、资源化标题、`toString()` 或类名作为身份。Pager 只替换 key 接线，分类模型、两个独立列表状态、稳定页收集、广告会话和业务动作门均保持原契约。
- 测试先行新增设备回归，直接组合生产 `DownloadHistoryPager`，以空 Paging 合成流且不提供广告环境运行；先在旧代码复现同一异常，再覆盖默认视频、直接图片、两类切换与 Compose 状态保存恢复。复用现有 Compose 测试依赖，不新增框架、测试专用生产 API 或源码文本断言。
- 修复前设备回归已确认两项均失败：默认入口为 `Video`，直接图片入口为 `Image`，均在 `SaveableStateHolderImpl.SaveableStateProvider` 抛出不支持的 Bundle key 异常；因此两个分类一起映射为稳定字符串，而非只修默认视频分支。
- 日志与诊断计划：不新增运行时日志；此处是纯 UI 身份映射，无网络/文件/数据库副作用，失败由设备回归、AndroidRuntime 和既有广告/下载诊断定位。测试不读取用户下载内容，不请求广告；人工验收只浏览和切页，不删除或重新下载记录。
- 验证结果：`.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.cla.clip.master.ui.page.download.DownloadHistoryPagerStateTest' --console=plain` 成功，宿主 304 项单测及 Samsung SM-G9730 / Android 12 上 2 项设备回归通过。测试空 Paging 流显式携带完成状态，避免初始 Loading 掩盖真实可见页断言；两项回归覆盖默认视频、直接图片、双向切换与保存恢复。独立代码复核无阻塞问题，`git diff --check` 通过。字符串不依赖反射或类名，无需 keep；无持久化/备份协议变化，本次不重复 release/R8。
- 实机浏览：重装修复包后的空数据入口、视频/图片切换可用。用户确认恢复最近本地备份后，冷启动再从“我的 → 下载记录”进入，真实视频记录和图片批次均可展示；缺失文件仍按原有不可用状态显示，没有再出现分类 key 异常。未执行删除或重新下载操作；未找到媒体与备份时间边界见备份主方案。
- 验证事故与后续边界：上述 Gradle 宿主设备测试结束时卸载应用且未保留数据，原数据库与设置丢失，未达到原计划的保留数据验证。已重新安装修复包并向用户说明；用户确认后已在手机内恢复最近本地备份，恢复结果和未找回范围见 [备份主方案](webdav_backup_plan.md#设备测试后的数据恢复记录2026-10-16)。后续宿主设备测试须使用无用户数据的专用模拟器/设备或独立 applicationId；不得在现用数据安装上直接运行会卸载目标应用的 connected 测试。证据位于 Git 忽略的 `build/download-pager-key/`，不提交备份或用户数据。

## 当前状态

以下记录已落地的页面结构与原设计目标；加载、媒体状态和异常分支的实际偏差，以顶部 2026-10-16 审查为准。尤其不能将外层生命周期收集视为完整取消保证，也不能将图片空缩略图视为已确认删除。

应用已经有视频下载任务表 `download_tasks`、图片提取批次表 `image_extract_batches` 和图片项表 `image_extract_items`。视频下载完成后，Android 10 及以上会通过 MediaStore 写入公共媒体库，并把 `ContentResolver.insert()` 返回的 `content://` URI 记录到任务的 `savePath`；Android 10 以下记录真实文件路径。图片批量下载完成后，批次记录保存 `outputDir`，每张成功图片项保存 `output_uri`。

当前已新增独立下载记录页，入口位于“我的”页。页面用“视频 / 图片”顶部纯文字 Tab 统一浏览已下载、失败、进行中、部分完成的媒体记录；TorrentSearch 的下载与历史由独立 Activity 管理，宿主不读取其数据库、不展示旧磁力扩展 Tab。Tab 不配置图标以降低高度并减少对列表空间的占用；Tab 支持点击切换，也支持在内容区域左右滑动切换分类。视频、图片记录使用 Paging 分页加载，页面进入 `STARTED` 生命周期且当前 Tab 可见时才收集对应分页流，避免 ViewModel 初始化或页面不可见时读取大量媒体元信息；图片 Tab 可见或页面从系统相册返回前台时会刷新当前分页，重新校验图片 URI 是否被外部删除或移入回收站。页面支持视频本地首帧/大小/时长展示、图片批次缩略图展示、多选删除、清空当前分类以及媒体记录的本地文件授权流程。页面处于多选状态时，系统返回键优先退出多选状态，不直接退出下载记录页面。恢复备份后，恢复流程页可以由用户手动触发本地媒体重新定位；记录页本身继续复用现有“本地文件已删除/不可读”展示，不新增“未定位”数据库状态。

本轮 UI 刷新仍保留下载记录页现有 `DownloadHistoryTitleBar`、Tab 和 Pager chrome，因为普通浏览态与多选态需要切换标题栏内容并和选择操作条协同；但页面根布局已改为 `Scaffold(topBar/bottomBar/snackbarHost)`，并把“标题栏 + 纯文字 Tab”组合成明确的纵向顶部区域，让系统导航区和底部多选条通过统一的 `paddingValues` 参与内容布局，不再依赖页面额外手动补系统导航高度。底部多选条本身也改为按自身内容高度测量，避免作为 `bottomBar` 时把主内容区错误挤没。

## 目标

- 在“我的”页增加下载记录入口，进入后默认用“视频 / 图片”顶部 Tab 切换，并允许左右滑动切换分类页面；TorrentSearch 不向本页追加 Tab。
- 视频记录展示标题、文件大小、视频时长、下载时间和本地首帧；本地文件已删除时展示已删除 UI，并提供重新下载按钮。
- 图片记录展示标题、图片数量和横向可滑动缩略图；8 张以内单行展示，超过 8 张后改为双行横向网格，点击缩略图只预览当前图片，不支持左右切换。
- 重新下载不删除旧公共文件；同名视频文件或图片文件夹必须生成序号后缀，避免覆盖旧内容。
- 图片输出文件夹名需要限制长度，并预留序号后缀空间，避免记录页三行文件夹名仍无法看清保存位置。
- 支持多选删除和清空当前分类；删除前由弹窗让用户选择“仅删除记录”或“删除记录和本地文件”。
- 删除数据库数据时只删除选中的对应下载记录表行，禁止删除整个 Room 数据库或无关表。

## 范围

- 涉及下载记录入口、导航路由、视频/图片历史查询、下载记录页面、媒体读取、重新下载、批量删除、清空当前分类和删除结果汇总。
- 涉及视频下载任务表、图片批次表和图片项表的历史查询与精确删除接口；如实现视频重新下载创建新记录，需要调整 `video_url` 唯一索引。
- 涉及 Android 10+ MediaStore URI 读取和删除授权；Android 10 以下仅按记录中的真实路径处理单个文件。
- 不删除剪贴板、搜索、来源 App、链接预览或未选中的下载记录。
- 不实现完整内置相册；图片大图预览只展示当前图片，从底部弹出，不提供左右切换、缩放、编辑或动图控制。

## 用户体验

1. 用户在“我的”页点击“下载记录”进入页面。
2. 页面顶部提供“视频 / 图片”纯文字 Tab，默认展示视频记录。用户可以点击 Tab，也可以在列表内容区左右滑动切换分类。
3. 普通状态下，用户可以点击视频记录播放本地视频，点击图片缩略图从底部打开单张大图预览；高图可以在预览区域内纵向滑动查看完整内容。
4. 视频本地文件不存在时，首帧区域显示已删除状态；用户点击“重新下载”会创建新的下载记录并重新下载。
5. 图片记录点击“重新下载”会复用旧批次的图片 URL 创建新批次，不覆盖旧批次和旧公共文件夹。
6. 用户点击“多选”或长按记录进入多选管理状态，可以选择多条记录。
7. 多选状态下，用户点击“取消”或系统返回键会退出多选状态；只有普通浏览态点击返回才离开下载记录页面。
8. 多选删除或清空当前分类时，弹窗显示影响数量，并让用户选择“仅删除记录”或“删除记录和本地文件”。
9. 如果选中记录包含进行中下载，确认文案需要提示会先停止下载任务。
10. 删除完成后展示结果汇总，例如“已删除 3 条记录，2 个本地文件删除失败”；文件已不存在可提示为已跳过，不阻塞记录删除。

## 最终实现

- 新增 `DownloadHistoryRoute` 和下载记录页面，入口放在 `MinePage`。
- 下载记录页面使用顶部纯文字 Tab 和横向 Pager 区分视频、图片，点击 Tab 会滚动到对应页面，左右滑动页面会同步更新当前 Tab，并在分类切换时清空多选状态；Tab 不配置图标，避免顶部区域过高挤占列表浏览空间；媒体记录范围包含成功、部分成功、失败、已过滤和进行中，图片未确认下载的 `STATUS_EXTRACTED` 批次不展示。TorrentSearch 的内部下载/历史不复用本页的 Pager、选择态、删除或备份 dirty 流程。
- 下载记录页面在多选状态拦截系统返回键，优先调用 `exitSelection()` 清空选择并回到普通浏览态；删除确认弹窗或图片预览弹层显示时，返回键仍优先交给对应模态层处理；普通态和多选态顶部栏都使用一致的状态栏 padding 与 48dp 高度，确保标题、右侧按钮和多选操作在同一垂直位置。
- 下载记录的视频列表和图片列表普通态底部留白使用同一套 12dp 轻量间距；进入多选态后，页面底部删除条通过 `Scaffold.bottomBar` 参与布局，列表内容区自动让出对应空间，不再依赖额外叠加系统导航高度。
- 下载记录页参考剪贴列表页的分页模式，视频和图片分页 Flow 在 ViewModel 内保持稳定并通过 `cachedIn(viewModelScope)` 缓存，页面层通过 `flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)` 绑定前台生命周期；`DownloadHistoryPager` 在横向 Pager 外只为 `settledPage` 对应的稳定 Tab 调用 `collectAsLazyPagingItems()`，避免邻页预组合或点击 Tab 后尚未停稳时提前加载另一分类，也不使用空 Flow 覆盖缓存。
- 视频和图片列表分别持有独立的 `LazyListState`，并通过 `rememberSaveable` 保存首个可见项和偏移量；切换视频/图片 Tab、亮暗色主题重组或 Activity 重建后，已浏览过的分类应尽量保留原滚动位置。
- 视频和图片都使用 PagingSource，分页大小 20、预取距离 5；默认首次请求 60 条，分页映射包含媒体 IO，并非仅处理屏幕可见卡片，问题与待优化边界见本次审查。
- 标题栏按钮、清空数量和“是否包含进行中任务”使用数据库 `COUNT` 与 id 查询，不依赖已分页加载的可见列表，避免分页只加载部分数据时全选、清空或删除提示统计不完整。
- 视频列表按下载记录更新时间倒序展示，使用数据库保存的媒体 URI/路径读取首帧、大小、时长和存在性；Android 10+ 严格以保存的 `content://` URI 为准。
- 图片列表按批次更新时间倒序展示，缩略图读取每个成功图片项的可读引用：Android 10+ 使用 `output_uri`，Android 9 及以下用批次目录和 `finalName` 组合出的最终公开文件路径；8 张以内单行横向展示全部当前可读图片，超过 8 张后改为双行横向网格，`output_uri` 为空、文件不存在或 Android 11+ MediaStore 标记为已回收时会在批次标签中提示不可读取数量；图片卡片会独立多行展示批次输出文件夹名，避免长文件夹名被单行标签省略，方便相同标题的多次下载互相区分。
- 备份恢复后的媒体重新定位只尝试恢复本地媒体引用，不修改下载状态、不重新下载、不备份媒体文件本体。若未找到媒体，成功/部分成功记录仍保留原状态，下载记录页继续通过现有可读性校验展示“本地文件已删除/不可读”并保留重新下载入口。
- 媒体重新定位成功写回新的 `savePath` 或 `outputUri` 后，记录页无需新增状态字段即可在下一次分页刷新或页面恢复前台时读取新引用；旧失效 URI/路径在没有高可信新引用时不会被清空，以便后续规则增强继续使用 hint。
- 图片大图预览接入共享 `SharedImagePreviewBottomSheet` 底部弹出层，图片按弹窗宽度完整排版，预览内容不再固定为 360dp 高度；当图片高度超出屏幕时，用户可以在图片区域纵向滑动查看完整图片。
- 下载记录页面入口保留生命周期收集、Pager 同步、删除授权和导航等流程编排；标题/Tab/Pager 外壳、分页列表、历史卡片、删除弹窗和选择底栏拆分到同 package 的独立组件文件中；图片预览、普通缩略图和媒体不可读占位复用共享媒体 UI 组件，完整共享规则见 `docs/shared_media_ui_plan.md`。
- 视频重新下载创建新的 `download_tasks` 记录，新记录指向新下载文件；旧公共文件和旧记录都保留。
- 图片重新下载克隆旧批次候选为新批次并启动下载；旧批次状态、旧图片项和旧公共文件夹都保留。
- 删除本地文件只删除记录精确关联的媒体项：视频删除对应任务的 `savePath` 或 pending 输出；图片在 Android 10+ 删除成功图片项的 `output_uri`，Android 9 及以下用批次目录和 `finalName` 定位最终公开文件，且只有确认目录内全是本批次文件时才安全删除整个批次目录。TorrentSearch 不接入本页删除流程，不会因本页操作请求媒体授权或取消其内部任务。
- Android 11+ 删除多个 `content://` 媒体项时，用 `MediaStore.createDeleteRequest` 合并 URI 后一次请求用户确认；Android 10 遇到 `RecoverableSecurityException` 时走系统授权流程，避免逐张图片连续弹窗。
- 删除进行中记录必须先取消 WorkManager，再按用户选择清理 pending/已发布文件，最后删除对应表行，避免 Worker 迟到回写已删除记录。
- 删除数据库数据必须精确到选中记录：视频只删 `download_tasks WHERE id IN (...)`；图片只删 `image_extract_batches WHERE id IN (...)`，图片项仅通过外键级联删除这些批次下的数据。
- Room 数据库版本提升到 5，`download_tasks.video_url` 从唯一索引迁移为普通索引；新下载和重新下载均插入新任务记录。
- 视频成功历史进入下载页时只观察和播放，不再自动重置进度或重新入队 Worker；重新下载由下载记录页创建新任务后再跳转下载页。

## 媒体身份与同名副本

- Android 10+ 公共媒体文件的唯一读取和删除凭证是 `ContentResolver.insert()` 返回的 `content://` URI。
- 如果公共目录已有同名文件，系统可能创建同名副本；只要数据库保存并使用本次返回的 URI，读取到的就是本次下载写入的副本，不会误读同名旧文件。
- 禁止用 `DISPLAY_NAME + RELATIVE_PATH` 反查媒体项作为唯一身份，因为同名文件可能返回多条结果或命中旧文件。
- `DISPLAY_NAME`、`RELATIVE_PATH` 和展示路径只用于 UI 展示或辅助生成新名称，不能用于替代数据库保存的媒体 URI。
- 文件存在性检查：`content://` 通过 `ContentResolver` 查询或打开验证；文件路径通过 `File.exists()` 验证。验证失败时展示已删除或占位状态。

## 删除与清空规则

- 删除记录弹窗提供两个动作：“仅删除记录”和“删除记录和本地文件”。
- “仅删除记录”只删除选中的下载记录对应表行，不删除公共目录中的视频或图片。
- “删除记录和本地文件”先删除记录精确关联的本地媒体项，再删除对应表行；若系统授权失败或用户取消授权，保留对应记录，避免记录消失但文件仍在。
- 清空只作用于当前 Tab：视频 Tab 清空视频记录，图片 Tab 清空图片记录。
- 清空前必须二次确认并展示数量，例如“将删除 12 条图片下载记录”；如果包含进行中记录，文案需要说明会先停止下载任务。
- 清空当前分类也是批量删除当前分类对应表行，不是清空整个数据库，不影响其他业务表和未选中的下载记录。
- 批量删除完成后展示汇总；本地文件已不存在不算阻塞错误，系统拒绝或授权取消需要明确提示。

## 数据流

- 下载记录页进入后，ViewModel 不在 `init` 中主动 collect 列表；ViewModel 只暴露稳定的 `pagedVideos` 与 `pagedImages` 分页 Flow，并使用 `cachedIn(viewModelScope)` 保留分页缓存。可选扩展 Tab 的分页 Flow 由扩展内容在当前 Tab 可见时自行收集。
- 页面层先用 `flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)` 限制外层分页消费，再由稳定 Tab 收集 LazyPagingItems，切换时复用 cachedIn 缓存。外层停止消费不保证缓存作用域内媒体任务立即取消，当前限制与修正方向见本次审查。
- 两个 DAO 返回 PagingSource，按 `update_time DESC, id DESC` 排序；分页大小 20、默认首次请求 60，并缓存到 ViewModel 生命周期。
- 视频和图片分别持有独立 `LazyListState`，列表滚动位置不依赖当前 Tab 的组合生命周期；主题切换等重组场景不会主动回到顶部。
- 视频分页项在映射当前页数据时读取 `savePath`、`pendingOutputUri`、`updateTime` 和状态，并从本地媒体 URI/路径查询大小、时长与首帧；未进入当前页的数据不读取首帧。
- 图片分页项在映射当前页批次时只读取成功图片项的轻量字段，并按批次输出文件夹做存在性短路判断，再批量校验可读图片，避免一个批次几百张图时逐张 `ContentResolver` 查询拖慢刷新；未进入当前页的批次不读取图片项和本地 URI。由于用户可能从系统相册外部删除图片且 Room 表不会自动变化，图片 Tab 进入组合或页面 `ON_RESUME` 时会调用 Paging `refresh()`，重新触发当前页 URI 可读性检查。
- 图片本地校验只作用于成功数大于 0 的成功/部分成功终态。下载中跳过校验原意是避免误判，但当前卡片仍把空缩略图统一展示为“本地文件已删除”，尚未达到该目标，见本次审查。
- Android 10+ 图片校验先用批次 `outputDir` 对应的 `RELATIVE_PATH` 查询目标目录是否存在未回收站媒体项，Android 11+ 文件夹查询和 `_ID IN (...)` 批量查询都过滤 `IS_TRASHED != 1`；如果目录下没有可读媒体项，直接判定本批次无可读图片，目录存在时再用保存的标准 MediaStore 图片 URI 解析 `_ID` 并分块查询。
- 恢复后媒体重新定位沿用同一文件夹前置规则：图片批次文件夹不存在或目录下没有可见媒体时，整批成功图片项直接判定未定位，不再按 `finalName` 全局搜索；文件夹存在时才在该文件夹内按 `finalName` 精确匹配，缺少 `finalName` 的图片项直接跳过。
- Android 9 及以下不能把 `outputDir` 直接当作真实路径：批次记录形如 `DCIM/clipMaster/<folderName>`，而当前旧系统实际保存目录是 `Pictures/clipMaster/<folderName>`；校验时只从 `outputDir` 提取目录名，优先检查 `Pictures/clipMaster/<folderName>`，必要时兼容检查 `DCIM/clipMaster/<folderName>`，并通过一次 `listFiles()` 与图片项 `finalName` 对比。
- 历史总数、当前分类是否有进行中任务、全选 ids、清空 ids 和选中项是否包含进行中任务通过轻量 DAO 查询获得，不依赖当前分页页内项目。
- 图片缩略图 8 张以内单行展示，超过 8 张后双行展示，并用 `successCount - 可读 URI 数量` 提示不可读取数量。
- 重新下载视频时，Repository 使用原任务的 URL、Referer、User-Agent、Cookie 和标题创建新任务，Worker 生成新的唯一文件名后下载。
- 重新下载图片时，Repository 复制旧批次仍可下载的图片 URL 和请求上下文为新批次，Worker 使用唯一且长度受限的文件夹名保存。
- 删除进行中视频时，先取消 `download_video:<taskId>`，再按用户选择删除 pending/已发布媒体，最后删除 `download_tasks` 对应行。
- 删除进行中图片时，先取消 `download_images:<batchId>`，再按用户选择逐个删除已发布图片或 pending 文件，最后删除 `image_extract_batches` 对应行并级联图片项。
- Android 10+ 删除图片本地文件仍按数据库记录的 MediaStore URI 走系统删除授权，不尝试直接删除公共目录；Android 9 及以下成功图片的 `outputUri` 可能为空，删除时需要用 `outputDir` 提取目录名并结合 `finalName` 定位最终公开图片，`tempPath` 只用于清理下载中或异常遗留临时文件。

## 涉及文件

- `docs/download_history_plan.md`
- `docs/video_extract_plan.md`
- `docs/image_extract_plan.md`
- `app/src/main/java/com/cla/clip/master/ui/navigation/Routes.kt`
- `app/src/main/java/com/cla/clip/master/ui/navigation/AppNavigation.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/mine/MinePage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/download/DownloadHistoryPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/download/DownloadHistoryChrome.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/download/DownloadHistoryContent.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/download/DownloadHistoryCards.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/download/DownloadHistoryDialogs.kt`
- `app/src/main/java/com/cla/clip/master/ui/widget/SharedImagePreview.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/download/DownloadHistoryVm.kt`
- `base/general/src/main/java/com/cla/clip/base/general/dao/DownloadDao.kt`
- `base/general/src/main/java/com/cla/clip/base/general/dao/AppDatabase.kt`
- `base/general/src/main/java/com/cla/clip/base/general/dao/DatabaseModule.kt`
- `base/general/src/main/java/com/cla/clip/base/general/dao/ImageExtractDao.kt`
- `base/general/src/main/java/com/cla/clip/base/general/repository/DownloadRepository.kt`
- `base/general/src/main/java/com/cla/clip/base/general/repository/ImageExtractRepository.kt`
- `base/general/src/main/java/com/cla/clip/base/general/utils/FileUtils.kt`
- `base/general/src/main/res/values/strings.xml`
- `app/src/main/java/com/cla/clip/master/work/DownloadVideoWorker.kt`
- `app/src/main/java/com/cla/clip/master/work/DownloadImagesWorker.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/video/VideoDownloadVm.kt`
- `base/general/schemas/com.cla.clip.base.general.dao.AppDatabase/5.json`

## 实现步骤

1. 新增下载记录方案文档，并同步补充视频和图片方案文档的跨功能契约。
2. 增加下载记录路由和“我的”页入口。
3. 为视频和图片数据层增加历史列表查询、精确删除、批量删除前读取和重新下载创建接口。
4. 调整视频下载任务模型，使重新下载创建新记录；移除或放宽 `video_url` 唯一索引，必要时增加来源 URL 普通索引。
5. 为视频保存增加唯一文件名策略，同名时生成序号后缀；图片继续复用唯一文件夹策略，并限制最终文件夹名长度。
6. 实现下载记录页面的普通浏览态、多选态、清空当前分类确认和删除结果汇总。
7. 实现 Android 10+ 媒体读取、存在性判断和删除授权；Android 10 以下实现单文件路径删除。
8. 补充字符串资源和简体中文注释。
9. 运行 `./gradlew :app:compileDebugKotlin` 验证编译。

## TorrentSearch 接入影响（已实施）

宿主下载历史恢复为内建视频/图片职责，入口说明只展示“视频、图片记录”，不再通过可选扩展 Tab 展示旧磁力复制/打开记录；旧 `MagnetDownloadHistoryEntry`、删除确认、分页入口以及弹窗中的磁力/扩展 Tab 注释均已移除。TorrentSearch 的下载与历史由独立 Activity 内部维护，因此本页不读取其数据库、不参与其删除副作用，也不统计其条目。完整职责、数据与备份边界以 [TorrentSearch 主方案](magnet_search_plan.md) 为准。

## 测试验证

- 视频重新下载创建新记录，旧记录和旧公共文件仍保留，新记录指向新下载文件。
- 图片重新下载创建新批次，旧批次和旧公共文件夹仍保留，新批次使用序号后缀目录。
- Android 10+ 同名视频或图片存在时，记录页读取数据库保存 URI 对应的媒体项，不误读同名旧文件。
- 删除记录选择“仅删除记录”时，只删除对应表行，不删除公共文件。
- 删除记录选择“删除记录和本地文件”时，只删除记录精确关联的媒体项；Android 9 及以下如果批次目录内全部文件都属于当前批次，可安全删除整个目录，存在额外文件时只删除匹配文件。
- Android 11+ 多个媒体项删除只发起一次系统删除确认。
- Android 10 遇到系统授权要求时能进入授权流程；用户取消时记录保留。
- 多选删除后展示结果汇总，文件已不存在不阻塞记录删除。
- 清空当前分类前展示二次确认和数量，只清空当前 Tab 对应记录。
- 在下载记录页左右滑动内容区时，能在视频页和图片页之间切换，顶部 Tab 选中态同步变化，切换后不会保留上一分类的多选状态。
- 多选状态下点击系统返回键时，只退出多选并清空已选记录，不退出下载记录页面；删除弹窗打开时返回键优先关闭弹窗。
- 进入下载记录页且页面处于 `STARTED` 后只收集当前视频 Tab 的分页流，不同时收集图片分页流；切换到图片 Tab 后图片分页流才开始加载，切回视频 Tab 后视频分页流继续可刷新。
- 视频和图片下载记录均按页加载，数据量很大时不会一次性读取全部记录、首帧或图片项；列表滚动到底部时追加加载下一页。
- 从视频切到图片、滚动图片列表后，再切回视频、切换亮暗色主题、再回到图片 Tab 时，图片列表应保持之前的滚动位置，不应回到顶部。
- 全选当前 Tab、清空当前 Tab 和删除选中记录的运行中提示在分页只加载部分数据时仍然准确。
- 在系统相册删除已下载图片后，重新打开下载记录或从相册返回图片 Tab，应刷新当前图片分页并把已删除或已回收的图片从缩略图中移除；如果批次没有任何可读成功图片，应展示“本地文件已删除”。
- 删除进行中记录时先取消 WorkManager，再清理文件，最后删除对应表行。
- 禁止删除整个 Room 数据库，剪贴板、搜索、来源 App、链接预览和未选中下载记录不受影响。

## 已知取舍

- 视频重新下载创建新记录会让同一 URL 多次出现，但更符合“下载历史按次数保存”的语义，也能保留每次下载指向的本地文件。
- Android 10+ 删除公共媒体可能需要系统确认；为保证记录和文件一致，用户取消授权时保留记录。
- 删除本地文件优先按数据库保存的 URI 执行；旧系统图片没有 `output_uri` 时，用批次 `outputDir` 提取目录名并结合 `finalName` 定位最终公开文件，不按同名全局搜索，避免误删其它目录里的同名图片。
- 当前图片记录在 Android 10+ 以成功图片项的 `output_uri` 作为缩略图和删除身份；Android 9 及以下以批次目录和 `finalName` 作为最终文件定位兜底。
- 下载记录页暂未切入 `SecondaryPageScaffold`；因为它仍需要页面专属的可变标题栏和 Tab chrome，但页面根布局已改为标准 `Scaffold`，系统导航区与底部多选条不再靠手动 padding 兜底。后续如扩展二级骨架支持可变标题栏插槽，再进一步迁入统一骨架。
- 图片删除本地文件默认按图片项精确删除；Android 9 及以下只有在确认文件夹内全部文件都属于当前批次时才删除整个文件夹，避免误删用户后来放入的内容。
- Android 9 及以下删除图片本地文件时可以做安全目录删除：如果目标文件夹里的文件全部属于当前批次记录，可直接删除整个文件夹；如果存在额外文件或子目录，只删除记录匹配的文件，最后仅在目录为空时删除空目录，避免误删用户后来手动放入的内容。
- 批量删除的授权、取消和结果汇总会让实现复杂度高于单条删除，但能避免多次系统弹窗和状态不一致。
- 恢复后的媒体重新定位不保证跨设备找回；如果媒体文件本体没有同步到当前设备，`outputDir`、`finalName` 和 `savePath` 只作为线索，不会把全局同名文件误当作本批次媒体。

## 开放问题

- 后续是否需要提供按状态筛选，例如只看已删除、失败或进行中记录。
- 后续是否需要在下载记录页提供“重新打开相册”或“复制文件位置”。
- 旧版本已存在的视频任务如果缺少可靠 URI，是否需要在记录页空闲时做一次媒体信息补齐。
- 视频 `video_url` 调整为普通索引后，是否需要新增来源分组字段来把同一 URL 的多次下载聚合展示。
- 后续是否需要为 Android 10 以下图片项补充最终保存路径字段，减少通过 `outputDir + finalName` 反推路径的兼容逻辑。

## 本次同步

- 视频记录卡片和图片批次卡片已接入 `ClipMasterCard` 公共内容卡片外壳，点击和长按反馈被限制在圆角范围内。
- 视频首帧框仍属于下载记录卡片局部 UI；图片缩略图、删除占位块和图片预览已接入共享媒体 UI，完整共享规则详见 `docs/shared_media_ui_plan.md`，公共卡片外壳规则详见 `docs/shared_card_component_plan.md`。
- 分页加载、空态、首次失败和 append 状态复用 `PagingLoadingContent`、`PagingEmptyContent`、`PagingErrorContent` 与 `pagingAppendStateItem`；多选底部删除条复用 `SelectionActionBar`。本页面继续保留视频/图片记录卡片、删除方式弹窗、运行中任务提示和本地文件授权流程，完整共享表单规则见 `docs/shared_form_components_plan.md`。页面根布局已改为 `Scaffold(topBar/bottomBar/snackbarHost)`，顶部区域由“标题栏 + Tab”纵向组成，让三键导航和底部删除条自动参与内容区 padding。
- 多选底部删除条的删除类图标通过 `SelectionActionBar` 接入 `DeleteActionIcon`，与列表菜单删除和详情页删除共用同款删除图形；“清空当前分类”继续使用 `DeleteSweep` 清空语义图标，仅统一为危险色。

## 变更记录

- 2026-10-26：实现收尾完成，483 项单测、源码编译与 Release/R8 通过；记录独立审查修正文档项和隔离设备未执行边界。

- 2026-10-26：用户确认执行移除方案，状态转为实现中；删除功能及备份耦合，保留系统备份、任务表和当前下载能力，并记录测试边界。

- 2026-10-26：按用户反馈撤回系统主库备份排除建议，明确保留 Android 自动备份/设备迁移并接受恢复额外任务行；同时列明任务行保留、表结构暂不瘦身等自主取舍，区分实际运行依赖和旧数据兼容。本轮仍只更新方案。

- 2026-10-26：新增下载媒体记录移除草案及实施任务，明确开发阶段无需旧数据兼容、保留下载任务表和当前下载体验、删除历史页面与媒体重新定位；备份契约归口备份主文档。原因是功能使用价值有限，却增加页面维护与恢复操作复杂度。本轮仅改方案。

- 2026-10-16：完成两类下载记录加载与显示审查，记录错误、缺失状态和冗余，纠正首批大小与生命周期取消边界的描述；原因是用户反馈页面仍异常。本轮只更新方案，未修改业务实现。

- 2026-10-16：修复下载记录首次进入的 `Video`/`Image` key 闪退，复用 PagerPolicy 映射 Bundle 支持的稳定字符串；先复现两分支异常，再通过 2 项生产组件设备回归、304 项宿主单测、编译和打包。同步记录设备测试卸载导致的数据丢失及恢复跟踪，后续改用隔离测试设备；分页、选择态与广告代码保持原契约。

- 2026-10-12：按已确认的 `ec41106` 广告方案，将视频/图片下载历史改为标题与 Tab 下方、横向 Pager 上方的固定单广告；分页收集与广告会话均按稳定 `settledPage` 编排，固定宿主不随业务 loading/空态分支退出。视频刷新 loading 不把旧快照视为实际内容，图片有旧批次时继续按原分支呈现；原因是广告需与数据库并行请求，并且横向拖动/回弹不能提前请求邻页或移走有效素材。共享状态测试和集成验证由广告主方案统一记录。

- 2026-09-27：下载历史 Pager 复用 `AdPagerPageState` 统一稳定页可见性与滚动布局门，保持已有拖动不重载、切页返回新请求的行为；回归边界以 [广告主方案](ad_module_plan.md) 为准。

- 2026-09-27：视频/图片记录页返回后允许重新请求广告，稳定选中页决定访问边界，横向拖动仅阻止布局揭示；修复释放后不重载，完整契约与验证以 [广告主方案](ad_module_plan.md) 为准。

- 2026-08-31：下载记录入口说明收口为“视频、图片记录”，并清理删除弹窗中的旧磁力/扩展 Tab 注释；原因是宿主页面已只承载两类内建媒体记录，用户文案与代码注释必须和真实职责一致。
- 2026-08-31：清理当前状态、职责、文件清单、验收和取舍中的 `MagnetDownloadHistoryEntry` / 磁力扩展 Tab 现行规则；原因是旧下载历史接入已删除，后续以 TorrentSearch 主方案为准。
- 2026-08-30：同步移除宿主下载历史的旧磁力 Tab 接入；原因是 TorrentSearch 以独立 Activity 管理下载和历史，不再复用宿主媒体下载记录页。
- 2026-06-08：同步下载记录删除/清空入口视觉规则；原因是多选删除属于删除类入口需要统一图形，清空当前分类属于清空类入口应保留独立语义。
- 2026-05-28：修正下载记录页底部多选条在 `Scaffold.bottomBar` 中的测量方式；原因是共享 `SelectionActionBar` 原本按 overlay 设计，内部使用 `fillMaxSize()`，迁入 `bottomBar` 后会把内容区高度错误吃掉，表现成一进入多选态列表就消失。
- 2026-05-28：修正下载记录页 `Scaffold.topBar` 的标题栏与 Tab 重叠问题，并恢复 `busy` 遮罩的条件显示；原因是把 `TitleBar` 和 `TabRow` 直接并列塞进 `topBar` slot 会造成视觉重叠，且上一轮布局收口时遗漏了 `if (state.busy)` 判断，导致页面常驻 loading。
- 2026-05-28：将下载记录页根布局切到标准 `Scaffold`；原因是三键导航下主内容仍可能被系统按键区和底部删除条遮挡，需要让 `topBar`、`bottomBar` 和系统导航区统一参与内容 padding，而不是继续手动补高度。
- 2026-05-28：为下载记录视频/图片列表的多选态补充底部让位空间；原因是底部 `SelectionActionBar` 采用 overlay 方式显示，如果列表仍沿用普通态底部留白，最后几个 item 会被遮挡。
- 2026-05-28：按确认结果恢复下载记录视频/图片列表 12dp 底部留白；原因是 8dp 在下载记录卡片体量下偏紧，12dp 更适合作为下载记录列表的统一结尾节奏。
- 2026-05-28：统一并收细下载记录视频/图片列表底部留白；原因是视频列表和图片列表此前沿用 `PaddingValues(12.dp)`，最后一条记录离页面底边偏远，需要和其他列表页的底部节奏一起收口。
- 2026-05-27：记录下载记录页在 UI 刷新中的骨架例外；原因是页面普通态、多选态、Tab/Pager 和扩展 Tab 共同组成专属 chrome，当前先复用主题与标题栏 token，后续等二级骨架支持可变标题栏插槽后再迁入。
- 2026-05-25：下载记录页改为内建视频/图片 Tab + 可选扩展 Tab；原因是磁力搜索已独立为编译期可选模块，宿主不能再直接依赖磁力 repository、实体或卡片实现。
- 2026-05-22：下载记录页新增磁力 Tab，接入磁力记录分页、数量、选择、删除、清空、复制和外部打开；原因是磁力搜索第一版需要把用户复制/打开过的 magnet 纳入下载记录页统一管理，同时保证磁力删除不触发本地文件和 Worker 副作用。
- 2026-05-17：下载记录页视频记录卡片和图片批次卡片接入 `ClipMasterCard` 公共内容卡片外壳；原因是修复卡片触摸反馈呈方角的问题，并让主要内容卡片沿用我的页面卡片效果。缩略图、视频首帧框和删除占位块保持现状。
- 2026-05-14：新增下载记录页面方案文档；原因是后续需要统一展示已下载视频和图片，并明确重新下载、同名副本、删除记录和本地文件删除的跨模块契约。
- 2026-05-14：补充视频重新下载创建新记录、删除结果汇总、批量系统删除授权、清空当前分类二次确认和进行中记录删除顺序；原因是下载记录页需要表达每一次下载结果，并避免误删公共文件、误删整个数据库或让 Worker 回写已删除记录。
- 2026-05-14：将状态更新为实现中，开始落地下载记录页入口、视频/图片历史查询、重新下载新记录、精确删除和 Room 索引迁移；原因是用户要求按照本文档添加下载记录功能。
- 2026-05-14：完成下载记录页第一版实现并将状态更新为已完成，补充 `video_url` 普通索引迁移、成功历史不自动重新下载、旧系统图片路径取舍和编译验证结果；原因是页面、数据层、删除授权、重新下载和入口已经落地。
- 2026-05-14：移除图片下载记录缩略图最多 12 张的展示限制，改为双行横向网格展示全部可读成功图片，并增加不可读取图片数量提示；原因是下载记录页应完整呈现当前批次可读取结果，避免用户误以为只下载了部分图片。
- 2026-05-14：将图片下载记录缩略图布局调整为 8 张以内单行、超过 8 张双行；原因是小批次单行更轻量，大批次双行能减少横向滑动距离。
- 2026-05-14：将图片大图预览从居中弹窗改为底部弹出层，并移除固定高度限制；原因是长图需要按宽度完整展示，并允许用户纵向滑动查看完整内容。
- 2026-05-15：补充下载记录页 Tab 与横向 Pager 联动方案，明确内容区支持左右滑动切换视频和图片分类；原因是当前页面只能点击顶部 Tab，缺少用户预期的滑动切换体验。
- 2026-05-15：补充多选状态返回键处理方案，明确系统返回键优先退出多选而不是离开下载记录页；原因是多选管理态属于页面内临时状态，返回应先撤销该状态以符合用户预期。
- 2026-05-15：将下载记录历史加载调整为页面生命周期驱动的 Paging 分页方案，ViewModel 不再初始化时 collect 列表，页面在 `STARTED` 且当前 Tab 可见时才收集对应分页流；原因是视频和图片都可能存在大量记录，必须避免一次性读取全部媒体元信息，并保证页面进入后台后暂停列表加载。
- 2026-05-15：参考剪贴列表页的分页与滚动状态处理方式，保持 ViewModel 分页 Flow 稳定并缓存到 ViewModel 生命周期，同时为视频和图片列表分别保存 `LazyListState`；原因是切换 Tab 或亮暗色主题重组后应保留已加载分页缓存和原列表位置。
- 2026-05-15：将下载记录页视频和图片 Tab 调整为纯文字，不再配置图标；原因是带图标 Tab 高度过高，会挤占下方历史列表可见空间。
- 2026-05-15：图片 Tab 可见和页面恢复前台时主动刷新图片分页，并将 Android 11+ MediaStore 已回收图片视为不可读；原因是用户在系统相册删除图片不会修改 Room 记录，必须重新校验 URI 才能展示本地文件已删除状态。
- 2026-05-15：补充图片历史存在性校验优化方案，改为按批次输出文件夹短路判断并批量校验图片项，明确下载中批次跳过校验、Android 11+ 回收站图片按不可读处理、旧系统用 `finalName` 匹配真实文件且可安全删除空批次目录；原因是几百张图片逐张判断是否删除会显著拖慢下载记录页刷新。
- 2026-05-15：图片下载记录卡片增加输出文件夹名展示，并从单行标签调整为独立多行文本；原因是相同标题的多次图片下载会生成不同文件夹，用户需要在记录页直接看清保存位置。
- 2026-05-15：限制图片批次最终输出文件夹名长度，并为序号或时间戳后缀预留空间；原因是过长网页标题生成的目录名即使多行展示也难以看清。
- 2026-05-15：统一下载记录页普通态和多选态顶部栏的状态栏 padding 与高度；原因是手写顶部间距会导致多选按钮和标题在不同设备上垂直位置不一致。
- 2026-05-15：记录后续抽象通用标题组件的方向；原因是下载记录页右侧按钮和多选态已经暴露出页面内手写标题栏容易造成对齐不一致，后续应把状态栏、高度、返回区、标题区和操作区规则集中维护。
- 2026-05-15：将 `TitleBar` 改造为带 `navigation`、`title`、`actions` 插槽的通用标题栏，并让下载记录页普通态和多选态接入同一容器；原因是普通页面、右侧操作页面和多选管理态都应复用同一套状态栏 padding、48dp 内容高度和垂直居中规则，避免标题与按钮位置再次分叉。
- 2026-05-15：将通用标题栏调整为覆盖式居中布局，并同步修正下载记录页普通态和多选态标题；原因是左侧一个返回按钮、右侧两个操作按钮时，标题不应被左右按钮数量拉偏。
- 2026-05-15：抽取 `TitleBarText` 并让下载记录普通态和多选态标题复用统一文案样式；原因是标题字体需要统一改小并加粗，避免不同状态下标题层级不一致。
- 2026-05-18：按当前代码组织规则拆分下载记录页 UI，新增 Chrome、Content、Cards、Dialogs 四个同 package 组件文件；原因是页面入口应聚焦生命周期、导航和业务事件编排，卡片、分页状态、弹窗和预览等可复用/可维护 UI 细节需要放到更可预测的位置。
- 2026-05-18：将图片预览底部弹层、图片缩略图和媒体不可读占位改为复用共享媒体 UI 组件；原因是这些能力已经同时服务图片提取页和下载记录页，继续放在下载记录页面目录会造成跨页面复用边界不清。
- 2026-05-18：下载记录分页状态和底部选择操作条接入共享表单组件；原因是这些 UI 形态已可被其他分页/多选页面复用，下载记录页只保留下载任务、本地文件和删除授权语义。
- 2026-05-21：补充备份恢复后媒体重新定位对下载记录页的影响；原因是恢复后旧媒体 URI/路径可能失效，第一版只通过恢复页手动触发高可信写回，下载记录页继续复用现有不可读展示和重新下载入口，不新增 schema 状态字段。


## 固定广告接入（2026-10-12，已完成）

视频记录与图片批次继续使用独立单元，每次稳定 Tab 访问只有一个固定槽；槽位位于标题和 Tab 下方、整个横向 Pager 上方，不参与列表和横向页面的滚动。复用共享 `FixedListAdLayout`、`ListAdContentState`、`AdPagerPageState` 和 `rememberPageAdSession`，移除业务卡片尾部的 `ListAdContentItem`。稳定页对应的分页收集与广告请求从同一次页面组合分别启动，广告请求不等待列表首批、数量查询或 refresh 完成；加载、空态和纯错误画面只关闭呈现许可，固定宿主仍存在。

视频 `refresh Loading` 原本始终画加载态，因此即使分页快照残留旧记录，传入状态仍为 `Loading`；图片仅在 `refresh Loading && itemCount == 0` 时画加载态，有旧批次时继续显示列表并允许呈现。两者的状态由当前分页数据直接计算并透传给列表的加载分支，不通过额外协程或记忆布尔值复制。仅真实业务快照有记录且当前不是加载画面时进入 `Rendered`，清空后及时变为 `Empty`。点击 Tab 时 ViewModel 先更新标题/业务选择态，再执行 Pager 动画；旧稳定页继续展示已有内容，固定广告保持原布局。`DownloadHistoryPagerPolicy` 将不匹配分类的选择投影为普通浏览态，并暂停点击/长按/重试/预览直到停稳且与业务选择同步，避免相同主键串用新分类选择或触发副作用。图片业务身份与计数仍是外层批次，不在缩略图中嵌套广告。

`AdPagerPageState.visibleToUser` 仍由 `settledPage` 决定，页面 `STARTED/RESUMED` 生命周期门继续生效；两个会话放在横向 Pager 外，邻页预组合不取得新请求许可。按压、横向拖动/惯性、列表滚动、多选、预览、删除确认和任务执行继续使用现有布局/交互安全门及实时按压读取；拖动只延后布局变化，回弹不销毁原访问素材。真正停稳到另一分类才切换分页与会话，返回创建新访问。业务稳定 key、去重后对应的原 Paging 索引、卡片、批次数量、append 状态和独立 `LazyListState` 保留，锚点继续由 `rememberListAdLayoutCoordinator` 只针对业务 key 恢复。

### 日志与诊断计划

共享广告的隐私、生命周期、持续呈现、关闭、空间预算和低敏日志契约以 [广告主方案](ad_module_plan.md) 为准。本页不新增重复日志；复用 DEBUG 请求、加载、曝光、释放和 `content_empty`/`content_ready`/`user_dismissed` 状态事件，异常沿用 WARN 来源初始化/渲染失败。字段只含来源、placement、slotIndex、稳定 reasonCode，不记录记录主键、批次信息、媒体 URI、路径、标题、素材或 SDK 原文，也不逐帧输出。通过同一访问请求次数与拖动/回弹、清空/恢复的事件顺序验证，自动隐藏不能伪报主动关闭或释放。仅 Compose 接线和临时内存状态变化，无 Room、下载元数据、设置、备份格式或外部序列化/反射契约变化。

### 验证与取舍

- 共享 `ListAdContentState` 与广告会话/空间纯规则由广告主方案统一测试先行；本页复用已有 `AdPagerPageStateTest`，并先补 `DownloadHistoryPagerPolicyTest`，覆盖提前选择另一分类、相同主键选择隔离、拖动期间动作暂停和停稳恢复。固定布局和 Paging/Compose 接线使用集成编译与设备验收，不编写源码镜像测试。
- 本轮保留 `DownloadHistoryPager` 内集中编排稳定页、分页实例、固定宿主和业务列表，虽然函数超过约 80 行，但不承载广告纯规则、SDK 或 IO 实现；原因是这些接线必须共用同一分页实例与实时画面状态，避免拆分引入状态副本和生命周期歧义。旧的仅透传 `DownloadHistoryContent` 中间层已移除；后续新增分类时再评估具名分页 UI 契约与专属编排组件，复用规则仍以下层共享广告能力为准。
- 验证进入页时广告请求不等待数据库；加载或空态时零空白；视频 refresh 保留旧快照时广告隐藏，图片 refresh 有旧批次时按原画面继续呈现；清空后隐藏、恢复内容后复用有效素材。
- 验证横向拖动与回弹不移动固定广告、不请求邻页、不丢原素材；停稳切 Tab 才切单元，返回为新访问；多选、按压、预览、确认、busy 和纵向滚动期间安全门与业务锚点不回归。
- 验证业务卡片 key、原 Paging 索引、外层批次数量、卡片点击/长按/重试、append 与滚动位置保持原契约；小屏、大字体和横屏的共享空间预算以主方案为准。
- 本轮下载历史接入不单独运行 Gradle，app 编译和共享广告单测由根代理串行执行并汇总到主方案；真实 SDK View、TalkBack、大字体、横屏和实际锚点仍需设备验证。未新增 SDK/反射契约，不额外扩大 keep 或备份范围。

变更记录：2026-10-12，完成固定单槽与稳定 Tab 收集；复核修正点击 Tab 动画空白，抽取并先测跨分类选择投影和动作暂停规则。app 292 项测试、编译及复核通过，整体广告验证与设备边界以主方案为准；页面原有其他实施事项继续按原状态跟踪。
