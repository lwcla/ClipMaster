状态：已完成

# MMKV 替换为 DataStore 方案

## 当前状态

本方案依据 2026-09-29 的仓库代码梳理，已按新安装基线完成直接切换。以下原有键和调用数量为实施前盘点，最终行为以本方案后续章节和当前代码为准。

- `base/general/.../config/AppSetting.kt` 集中保存 30 个持久化键，并手动维护部分 `MutableStateFlow`；共有 28 个生产 Kotlin 文件直接调用 `AppSetting`，另有初始化、依赖和系统备份规则需要清理。
- 当前介质是默认 MMKV 与保存 WebDAV 密码的独立加密 MMKV；后者的文件加密密钥写在代码中。
- `MmkvInitializer` 同时服务 `BaseApplication` 和 `ClipboardBridgeProvider`，用于处理 Provider 早于 `Application.onCreate()` 的冷启动。
- `feature/torrent-search` 已使用 Preferences DataStore、Hilt 单例、强类型设置快照、挂起写入和临时文件测试。版本目录已有 `androidx-datastore-preferences`，版本为 `1.2.1`。
- Torznab 已有 Android Keystore AES-GCM 实现，但属于 feature 内部，不能让 `base:general` 反向依赖该模块。
- Room 仍承载剪贴、下载、历史等集合数据。本次只替换 MMKV，不把 Room 数据搬进 DataStore。

## 目标与范围

用户约束：项目处于开发阶段，不需要兼容旧版本；替换完成后直接删除 MMKV，不保留任何 MMKV 相关代码。本轮采用直接切换：不读取旧 MMKV、不迁移旧键、不双写、不保留同步兼容门面，也不增加旧文件清理或过渡支持代码。

验收目标：项目维护的源码、测试、构建脚本与资源配置中 MMKV 相关实现和引用归零，包括依赖、初始化、封装、调用、加密常量、专用测试和备份排除项；现有设置、后台任务和备份能力在新安装数据下工作；关键写入可等待、失败可观察；主线程不因读取设置而阻塞。

开发验证以清除应用数据或卸载后重装的新安装为基线，不设计覆盖安装旧状态的支持路径。开发设备的旧数据通过上述操作处理，应用内不保留 MMKV 文件检测、迁移、清理或残留目录排除逻辑。替换代码本身不增加删除 Room、现有 TorrentSearch DataStore、外部备份或媒体文件的行为。

“不兼容旧版本”用于免除本次新增迁移工作，不扩展为清理无关的 ZIP 兼容实现。本轮保持当前自定义备份字段协议，不因介质替换增加 schemaVersion，也不增加历史版本兼容测试。

## 方案选择

| 方案 | 收益 | 代价 | 判断 |
| --- | --- | --- | --- |
| Preferences DataStore + 强类型领域 Repository | 与现有 TorrentSearch 技术一致，适合当前标量、集合和少量摘要，可按键渐进替换调用 | 需要自行集中管理键、默认值和映射规则 | 推荐 |
| Proto DataStore | 持久化 schema 明确，适合大量嵌套结构和严格协议演进 | 新增 protobuf 构建链、生成类型与映射，本轮大部分字段用不上 | 暂不引入 |
| 保留 `AppSetting` 同步 getter/setter，内部阻塞或异步代写 | 调用方改动看似较少 | 阻塞读取、写入失败不可见、先返回后落盘、双份状态难同步 | 不采用 |

DataStore 面向小数据，提供 Flow 和挂起更新；同一进程同一文件只能创建一个实例。选型与实例规则参照 [Android 官方 DataStore 指南](https://developer.android.com/topic/libraries/architecture/datastore)。复用仓库已锁定的 1.2.1；该版本可在 [官方发布记录](https://developer.android.com/jetpack/androidx/releases/datastore) 核对，本轮不附带升级依赖。

## 架构与职责

### 物理介质

新增宿主 `files/datastore/app_settings.preferences_pb`，由 `base:general` 的 Hilt Module 以 `@Singleton` 和专用 qualifier 提供。沿用现有应用级协程生命周期，为该文件创建可独立关闭的子作用域，文件 IO 使用 IO 调度器；重置时只关闭该文件子作用域，不能取消整个应用作用域。禁止 Activity、Worker、Provider 各自创建同一路径实例。

本轮采用一个宿主文件、多个领域访问入口。30 个键的数据量小，单文件方便设置与变更标记、WebDAV 配置与密文、状态与摘要一起原子写入。整个文件排除系统备份，跨安装设置由现有 ZIP 字段白名单导出，因此无需为系统备份拆出第二套偏好文件。若未来写频率或体积明显增长，再依据实际测量拆文件，届时重新设计跨文件一致性。

TorrentSearch 保留独立文件及独立 Repository，不合并领域模型。页面、Worker、广告模块不接触 `Preferences.Key`，不直接调用 DataStore。

### 逻辑边界

以下名称为建议职责，不要求一次性建立空接口或通用 CRUD 框架：

| 位置与建议类型 | 职责 |
| --- | --- |
| `base/general/.../config/datastore/HostSettingsDataStoreModule` | 唯一实例、qualifier、文件与作用域装配 |
| `base/general/.../config/clip/ClipSettingsRepository` | 快捷动作、过滤名单、回收站保留策略的独立 Flow；一次性快照仅供备份和任务读取 |
| `base/general/.../config/ad/AdSettingsRepository` | 广告开关、来源与成组隐私状态 |
| `base/general/.../config/installation/InstallationIdentityRepository` | 安装 ID 原子生成、Shizuku 期望进程身份 |
| `base/general/.../backup/settings/BackupSettingsRepository` | 备份目标、授权目录、开关、保留份数与白名单恢复入口 |
| `base/general/.../backup/settings/BackupRuntimeRepository` | 最近任务状态、健康、摘要和备份变更版本 |
| `base/general/.../config/download/VideoDownloadRuntimeStore`、`config/update/AppUpdateCheckStore` | 各自保存下载任务指针、自升级检查时间；不发展为万能设备状态类 |
| `base/general/.../security/AndroidKeystoreSecretCipher` | 从 Torznab 下沉通用密码学原语；alias、AAD 由领域适配层提供 |

键、默认值、映射和校验放在所属领域的可预测文件中，介质绑定集中在 Module。模块内部实现优先 `internal`；宿主、广告和 feature 确需使用的领域契约才公开。具体拆分以测试边界为准，不把原 `AppSetting` 原样搬成一个大 Repository。

数据流为：页面事件 → ViewModel → 挂起领域写入 → DataStore 提交 → 领域 Flow → 生命周期感知 UI；Worker 和 Provider 协作者直接注入所需领域契约。

## 字段处理清单

以下覆盖当前 30 个持久化键；新增的加载态仅在内存，变更版本见备份章节。

| 原字段组 | 新表示、默认值与约束 | 自定义备份 |
| --- | --- | --- |
| `clipItemQuickAction` | 稳定 `storageValue`，默认 `copy` | 保留 |
| `blockedClipSourcePackages` | `stringSetPreferencesKey`；默认空集，复用 `ClipSourceBlockRules` 的规范化、上限与排序规则 | 保留；导出为确定顺序，恢复取并集 |
| `recycleBinRetentionDays` | Int，默认 30，范围 1–3650 | 保留 |
| `pid`、`shizukuSuffix` | 安装 ID 缺失或为空字符串时通过事务生成 20 位纯数字；非空值原样复用；期望进程名默认空，绑定前计算并持久化 | 排除，安装/运行身份 |
| `videoDownloadTaskId`、`appUpdateLastCheckAt` | Long，默认分别为 -1、0；更新限频继续复用 24 小时规则 | 排除，运行状态 |
| `activeAdSourceId`、`adsGlobalEnabled` | 默认 `auto`、true；加载失败不能据此启动广告 SDK | 排除，本机配置 |
| `adConsentState`、`adPrivacyPolicyVersion` | 默认 `unknown`、空字符串；一次事务更新，保留现有同意校验规则 | 排除，本机隐私决定 |
| `webDavEndpoint`、`webDavUsername`、`webDavRemoteDir`、`webDavAllowInsecureHttp` | 默认空、空、`/ClipMaster/backups/`、false；复用 WebDAV 校验与目录规范化 | 排除，连接配置 |
| `webDavPassword` | 替换为版本化 Keystore 密文；缺失表示未设置 | 排除，凭据 |
| `localBackupDirUri`、`localBackupDirLabel` | 默认空；保存、清空时成组提交，使用前校验 SAF 授权 | 排除，设备绑定授权 |
| `autoBackupEnabled`、`backupPlaintextDisclosureAccepted`、`autoBackupOnlyWifi`、`backupRetentionCount` | 默认 false、false、false、5；保留份数 1–20，开关不绕过披露和目标检查 | 排除，本机执行策略 |
| 本地/WebDAV 结果及待上传身份 | 每个目标保存最近完成时间、失败标志和低敏原因；上传身份按文件管理，不关联业务变化版本 | 排除，本机运行状态 |
| `lastAutoBackupStatus`、`lastAutoBackupSuccessAt`、`lastAutoBackupFailureReason`、`lastAutoBackupSkipReason` | 默认 idle、0、空、空；状态写稳定 code，原因优先存 code 并在 UI 资源化 | 排除，可重建状态 |
| `webDavHealth`、`webDavHealthCheckedAt` | 默认 unknown、0；配置变化时同事务使旧健康状态失效 | 排除，目标探测缓存 |
| `lastBackupSuccessSummary` | 继续复用 `BackupJson` 的小型摘要编码；默认无摘要 | 排除，本机成功记录 |

## 异步接口、生命周期与用户体验

1. 持续读取按消费边界暴露只读 Flow。剪贴设置提供 `quickAction`、`blockedPackages`、`recycleBinRetentionDays` 三个独立 Flow，直接从原始 Preferences 提取对应字段并按业务值去重，取消整体 `data` 快照流；过滤名单先按原始集合去重，再规范化并按业务集合去重，避免其他字段写入时重复规范化。其他有成组消费需求的领域仍可提供 `Flow<Snapshot>`。必要时由 ViewModel `stateIn` 转为 UI 状态，页面使用 `collectAsStateWithLifecycle`，不在 Repository 维护可写镜像。剪贴 `snapshot()` 仅在备份或一次性任务读取时构造完整对象，不参与持续订阅。
2. 首次读取区分 `Loading`、`Ready`、`Error`。不存在文件或键才使用业务默认值，磁盘错误不能伪装成新安装。安全决定所需的设置就绪前，禁用相应操作；普通页面仍可展示不依赖设置的区域。
3. 写入为有业务含义的 `suspend` 方法。正常返回表示 DataStore 已完成持久化；存储失败向调用者传播并映射为稳定错误，取消继续抛出。`Changed/Unchanged` 这类业务结果不能兼作 IO 失败。该提交契约依据 [DataStore API](https://developer.android.com/reference/androidx/datastore/core/DataStore)。
4. UI 可以立即显示草稿，但成功提示、关闭保存弹窗、启用后台任务必须等待写入完成；失败保留草稿并显示重试。写入采用提交后的 Flow 更新正式状态，不用属性赋值伪装成功。
5. 来源过滤集合的读、校验、增删和写入在同一 `edit` 内完成，不采用“先 `first()` 再 `edit`”的读改写。`@Synchronized` 不再承担持久化并发控制。
6. 关联字段一次提交：隐私状态和版本、目录 URI 和名称、备份状态和摘要、WebDAV 配置和凭据。摘要解析失败只呈现摘要不可用；修复清理是明确写操作，不在 `map`/getter 内产生隐式写入。
7. 设置页仅在可见时收集；广告只保留 SDK 运行确需的少量状态，未加载或读取失败时按未同意处理；Worker 在任务中取一次一致快照，不依赖先打开页面。应用级作用域只覆盖独立于页面的任务，不借此常驻扫描 Room 或触发网络。
8. 备份输入框先保留内存草稿，明确提交时成组保存；“测试连接”和“立即备份”先等待本次合法草稿提交。避免每键输入一次落盘。按 2026-09-30 用户要求，已保存密码解密后回填备份页 ViewModel 内存状态，默认遮蔽，右侧按钮可切换明文；不保存到 SavedStateHandle/rememberSaveable。表单直接按当前密码提交，清空输入框表示提交空密码；不展示保存密码说明或专用移除按钮，也不要求更换账号后再次输入密码。异步读取不得覆盖编辑中的草稿。最终交互与测试以 [备份主方案](webdav_backup_plan.md) 为准。

## 启动与 Shizuku / Provider

当前 Manifest 未发现业务组件通过 `android:process` 直接共享该配置文件；Shizuku 通过 Provider 访问主进程，不直接读应用 DataStore。因此默认单进程 DataStore，不为“存在 Shizuku 进程”引入 MultiProcessDataStore。实施时同时检查启用功能后的 merged manifest；如果确有其他进程读写同一文件，必须单独复核，不能混用两种实例。

- `Provider.onCreate()` 只保留轻量入口准备；不在这里读磁盘、不 `runBlocking`，不等待 `Application.onCreate()` 中的预加载。
- Provider 首次请求经现有 Hilt EntryPoint 懒取同一个实例。安装 ID 的空值判断和首次生成放在 `edit` 内，所有并发读取获得同一个已提交 ID；不先发射临时 ID。首次生成由现有 `NumericInstallIdGenerator` 保证 20 位纯数字格式；已保存的非空 ID 原样复用，不因长度、字符或首尾空白重新生成。`snapshot()` 与 `data` 同样原样返回非空值，仅将缺失值映射为空字符串，读取不产生隐式写入。
- `ShizukuConnector` 在现有应用协程内先等待身份就绪，再计算并提交期望进程名，最后执行延迟、早退判断和 bind。Provider 查询同样先得到已提交身份，不等待 bind 完成，防止循环等待。
- Android `ContentProvider.call()` 是同步边界。远端 Binder 工作线程可采用局部、有超时的协程桥接（建议初始上限 3 秒，经冷启动测试校准），实际 DataStore IO 不运行在 Main；超时/IO 错误返回明确失败码。若检测到本进程 Main 同步调用，立即返回可重试失败，内部改走挂起接口，不阻塞 UI。
- 普通 Repository、getter、ViewModel 和 Application 不允许 `runBlocking`。上述 Binder 边界是明确的局部例外，需要主线程、超时和取消测试。
- `ClipHelper.processClipText()` 已是挂起入口，应在保存前读取真实过滤名单；读失败则本次不保存并返回可诊断失败，避免空默认名单绕过过滤。
- 下载清理、回收站清理和自动备份的首个步骤必须读取所需设置；失败不按默认值执行删除、清理或上传。无需让整个 UI 等待所有后台设置就绪。

## 密码存储与失败契约

DataStore 不是凭据加密方案。推荐从现有 `AndroidKeystoreTorznabApiKeyCipher` 抽出 AES-GCM 原语到共享安全包，Torznab 保留领域适配层、原 alias/AAD 和既有密文介质。WebDAV 使用独立 alias 和固定用途 AAD；不共享两种业务的密钥，也不让 base 依赖 feature。Keystore 密钥不可导出、操作需避开主线程，参照 [Android Keystore 官方文档](https://developer.android.com/privacy-and-security/keystore)。

WebDAV 存储版本、IV 和带认证标签的密文，采用小型显式格式与专用编解码器。先在 IO 上完成加密，再在一次 DataStore 事务中提交配置和密文；加密失败则不提交配置。表单提交将当前地址、用户名与输入框密码（包括空字符串）作为同一组配置保存，不额外阻止地址或账号变更。

按用户最新要求，页面始终使用 `Replace(输入框当前密码)` 提交，不再调用密码动作决策或 `Retain/Clear`，也不要求账号变更时重新编辑密码。仓库既有 `Retain/Clear` 内部契约保留但不参与表单流程。网络动作从一份 DataStore 快照同时取得目标配置和密文并解密，禁止先读旧地址再读新密码；错误凭据由实际连接结果反馈。WebDAV 健康状态只是诊断缓存；已配置目标即使状态为 unknown，也必须计入自动备份目标并受网络约束，不能让本地单目标成功错误确认全部已备份。

读取区分：未设置、可用、密钥缺失/永久失效/认证失败、暂时不可访问。永久失败使 WebDAV 目标不可用并提示重新填写；暂时失败允许重试，不删除凭据或其他设置。清理无效密文时比较当前密文是否仍为失败的那一份，避免误删并发保存的新密码。后台自动备份不要求每次生物认证。

没有旧 MMKV 密码解密或迁移流程。密码只在备份页默认遮蔽的输入框中按用户点击显示明文；日志、测试报告、自定义备份和系统备份都不能输出真实明文密码、密文完整内容或密钥。解密失败提示重新输入，保留密文并允许修复，不把密码错误当作全部设置读取失败。

## IO 错误与损坏策略

- 读失败通过领域加载错误暴露，提供显式重试；保留最后成功的 UI 展示值时标明不可用于新的敏感动作。
- 不把 `catch { emit(emptyPreferences()) }` 当作通用恢复策略：它会把失败与默认设置混淆，且不能代表持续恢复订阅。这里只复用 TorrentSearch 的结构，不照搬其所有降级行为。
- 单文件包含来源过滤、隐私和安装身份，本轮默认不自动清空损坏文件。`CorruptionException` 进入“设置损坏”状态；明确重置操作完成前阻止依赖这些设置的动作。
- 若实施重置，必须关闭并等待旧 DataStore scope 结束，再处理精确目标文件并重建唯一实例，或通过明确授权后的专用恢复流程完成；不能在活跃实例旁删除文件或创建第二实例。重置清空设置而不清 Room、媒体和外部备份，身份重新生成、隐私重新确认。
- `ReplaceFileCorruptionHandler` 是可选恢复机制，不等同于普通 IOException 处理；其回调不得访问 DataStore，否则可能死锁，参照 [官方损坏处理 API](https://developer.android.com/reference/androidx/datastore/core/handlers/ReplaceFileCorruptionHandler)。实际实现若采用该机制，必须保留显式重置与隐私恢复边界。

## 备份、恢复与并发

备份设计仍以 [WebDAV 与本地备份主方案](webdav_backup_plan.md) 为准，本节只描述存储替换的影响。

### 白名单与恢复

- 将 `AppSetting.toBackupSettings()` 改为接收一次读取的领域快照的纯 mapper；只导出快捷动作、回收站天数和来源过滤名单。单次导出的设置内容、摘要与校验使用同一份快照。
- 复用 `BackupSnapshotExporter`、`BackupSnapshotRestorer`、`BackupJson` 和 `BackupFeatureContributor`，不重新实现 ZIP、checksum、分页和幂等合并。
- 恢复设置时，在同一次 `edit` 内读取最新本地过滤名单、计算并集、更新其他白名单字段，不附加备份版本写入。不要拿恢复开始前的旧名单覆盖用户期间新增的规则。
- Room 与 DataStore 没有共同事务。恢复先完成既有数据库阶段，再等待设置提交；设置失败必须报告数据库已恢复、设置未恢复的部分结果，重试设置阶段仍应幂等。不能把 `edit` 放进 Room 事务就宣称跨库原子成功。
- 可选功能库使用独立 Room 文件：宿主 Room 提交后再恢复功能库，再提交宿主设置；后续阶段失败携带已完成的宿主报告，页面显示部分恢复并提供同一备份重试。功能库分批提交保持，取消跨存储 dirty 标记；下一次每日/手动快照读取最新数据。
- 不把整份 Preferences、WebDAV 配置、密码、授权 URI、隐私同意、安装 ID 或任务状态加入 ZIP。

### 每日备份接入（2026-10-01）

业务保存仅提交业务数据，不维护备份变更/确认版本，也不调用备份调度。备份运行仓库只保存两个目标的完成结果、待上传文件身份与稳定阶段；每日生成无网络约束，WebDAV 同包上传独立等待网络。完整设计、兼容初始化、清理与日志以 [备份主方案](webdav_backup_plan.md) 为准。

### 系统备份

`backup_rules.xml` 及 `data_extraction_rules.xml` 的 cloud-backup、device-transfer 都显式排除 `datastore/app_settings.preferences_pb`，保留现有 TorrentSearch 与 Torznab 排除项。删除现有 `mmkv/` 排除项和配套注释，不新增或保留任何 MMKV 专用规则；新安装验收不承担旧目录残留的兼容支持。

排除后，系统迁移不会带走本机凭据、授权和身份；可移植的三个设置由用户显式 ZIP 恢复。Keystore 不可用时依照凭据失败契约处理，不将不可解密误判为匿名连接配置。

## 涉及文件与实施顺序

本节是实际实施分组；代码已按领域仓库、调用方、备份闭环和旧入口删除的顺序切换。

1. **基础与契约**：在 `base/general/build.gradle.kts` 引入已有 DataStore alias；新增宿主单例、领域快照、键和默认值映射。先写默认值、稳定 code、规范化、原子更新和失败契约测试，再写生产实现。
2. **普通设置和 UI**：替换 `MineVm`、`ClipSourceBlockSettingVm`、`DetailViewModel`、`RecycleBinVm`、列表/搜索页，以及 `ClipHelper` 的设置入口。复用现有组件，只增加必要的加载、保存失败和重试状态及字符串资源。
3. **安装身份和后台入口**：替换 `ShizukuConnector`、`ClipboardBridgeShizukuProcessCoordinator`、Provider 冷启动、下载/回收站 Worker、自升级限频；验证写入与副作用的先后顺序。
4. **广告与敏感配置**：替换 `MainActivity`、`AdHostEnvironment`、`UniAdConsentProvider`、`CsjAdConsentProvider` 的同步来源；下沉通用 Keystore 原语并接入 WebDAV。广告 SDK 保持现有合规门禁，不用默认值提前初始化。
5. **备份闭环**：每日无网络约束生成最新数据，本地先发布，网络满足后消费同包；移除各 Repository、恢复和媒体关联后的版本/调度调用。设置白名单、部分恢复报告和系统备份排除保持，以备份主方案为准。
6. **完全切换**：删除 `AppSetting` 的持久化门面、`MmkvInitializer`、Application/Provider MMKV 初始化、Gradle MMKV alias 与依赖、旧加密常量、MMKV 专用测试与配置、备份 XML 中的 `mmkv/` 排除项和相关代码注释。仍有价值的领域枚举/常量移至对应领域，保留业务测试并改测 DataStore 契约。MMKV 名称只可出现在替换方案和必要的历史文档记录中，不留可执行代码或配置例外。
7. **收尾**：同步设置、来源过滤、Shizuku、广告、回收站、下载及备份主文档中的实际行为；关联页面只写接入差异。受影响代码中的每个方法、参数/变量声明与关键分支按仓库规则补简体中文注释。

阶段可分别开发和验证，但发布结果一次切换，不把双写或兼容门面作为交付物。每个阶段先切调用再移除旧入口，避免业务入口和备份长期使用不同数据源。

## 测试验证

2026-10-12 安装身份复用：按 `repo-unit-test-gate` 对持久化规则变更采用测试先行，复用真实临时文件 DataStore；新增回归覆盖非空 ID 的重复读取与持久化值不变、`snapshot()`/`data` 原样展示，以及已保存空字符串首次生成 20 位纯数字后持续复用。合成输入包含长度不符、非数字字符、UUID、首尾空白和纯空白字符串，明确空白字符串仍属于非空身份。沿用已有缺失键默认值与并发首次生成测试。旧实现运行身份所在测试类共 10 项，两个非空身份回归按预期失败；调整后执行 `.\gradlew.bat :base:general:testDebugUnitTest :base:general:compileDebugKotlin --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' --console=plain`，结果 `BUILD SUCCESSFUL`，general 共 104 项单测，失败、错误、跳过均为 0，`git diff --check` 通过。首次编译遇到 Kotlin daemon 临时文件权限限制并自动回退；修改后沙箱读取 Gradle transforms 缓存 JAR 被拒绝，使用上述命令在沙箱外完成验证。未修改工程或 JDK 配置，未做设备交互与 release/R8 验证；本次没有外部协议、反射或混淆规则变更。RED 与 GREEN 日志分别为 `build/installation-identity-red.log` 和 `build/installation-identity-green-retry.log`。

2026-09-30 剪贴字段 Flow：先验证名单修改不会重复通知快捷动作与保留天数，再验证三个字段各自变化、同文件其他领域变化、默认值/规范化与 IO 失败传播。复用 JVM 内存 fake 和已有测试依赖；页面改动只替换订阅接线，编译及既有 app 单测覆盖接入，生命周期与重试方式保持原契约。

`repo-unit-test-gate` 判断：本次实施涉及纯规则、并发、Flow、数据协议和失败契约，已对 DataStore 原子写入、恢复合并、凭据失败、Provider 超时与密码决策选择性补充单元测试；设备绑定 Keystore 另以 instrumentation 编译和后续实机验证覆盖。

| 测试层 | 必须覆盖 |
| --- | --- |
| 纯规则 | 全字段默认值、稳定 code、未知值、范围裁剪、包名规范化与确定顺序、备份白名单、密文格式坏输入 |
| 真实临时文件 DataStore | 写后重新创建实例可读；先取消并 join 旧 scope；并发名单更新不丢数据；安装 ID 只生成一份；关联字段只出现完整组合 |
| 失败与状态 | 读写 IO 失败、取消传播、损坏文件不静默重置、保存失败不显示成功、不启动任务、广告未就绪不初始化 |
| 备份协作者 | 设置导出快照一致、恢复名单并集、重复恢复幂等、数据库成功而设置失败的报告、两目标结果隔离、周期不依赖保存变化、终态包不复活 |
| Provider / Shizuku | Provider 先于 Application 冷启动、并发查询、IO 超时、Main 调用保护、提交进程名后再绑定、无回调循环等待 |
| 密码学边界 | JVM fake 测失败分支与配置原子提交；Android 设备测 Keystore 加解密、密钥失效、篡改和重新输入；只用合成凭据 |
| 手工/设备 | 设置跨页面刷新、进程重启、关闭页面取消收集、SAF 失效、冷启动 Worker、系统云备份/设备迁移、功能开关开关两种装配 |

Windows 验证命令：

```powershell
.\gradlew.bat :base:general:testDebugUnitTest :base:general:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin
.\gradlew.bat :feature:torrent-search:testDebugUnitTest
.\gradlew.bat :app:minifyReleaseWithR8
git diff --check
```

2026-09-29 收尾结果：`base:general`、`feature:torrent-search`、`app` 的 `testDebugUnitTest` 与 `app:compileDebugKotlin` 组合执行通过；默认配置及 `-PenableMagnetFeature=true` 的 `app:minifyReleaseWithR8` 均通过；`app:dependencyInsight --dependency mmkv --configuration releaseRuntimeClasspath` 显示无匹配依赖；`git diff --check` 通过。维护中的源码、测试、构建脚本和资源配置已做不区分大小写的 MMKV/旧设置入口扫描，未发现残留。未连接 Android 设备，因此 Keystore instrumentation、Provider 真机冷启动、SAF 与系统云备份/设备迁移仍需设备验证；Release 构建不能代替这些运行时检查。

Release/R8 验证关注从枚举 name 改为稳定 code 后的状态恢复、摘要序列化及下沉后的安全契约；不为所有 snapshot/data class 添加 keep，只按现有显式字段协议与最小必要规则处理。

最终扫描标准：项目维护的源码、测试、构建脚本和资源配置经不区分大小写的 `mmkv` 搜索无命中；同时检查依赖解析与 APK，确认不再由项目直接打包 MMKV。旧 `AppSetting` 持久化调用归零；每个新设置都有归属、默认值、失败策略和备份决策；没有主线程同步磁盘读取和静默异步 setter。扫描不以删除仍有价值的业务测试代替迁移测试契约。


2026-09-30 字段 Flow 验证结果：旧实现回归实际失败，名单变化使快捷动作历史从预期 `[Copy]` 变成 `[Copy, Copy]`；改为独立字段接口后，新增 3 项测试全部通过。执行 `.\gradlew.bat -I build/clip-settings-flows-validation.init.gradle :base:general:testDebugUnitTest :app:compileDebugKotlin :app:testDebugUnitTest --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' '-Pksp.incremental=false' --console=plain`，结果 `BUILD SUCCESSFUL`，general 共 67 项、app 共 154 项单测，失败、错误、跳过均为 0。原构建目录 `classes.jar` 被其他程序占用，临时 init script 仅将 general 产物改到 `build/clip-settings-flow-validation/base-general`；沙箱内缓存 jar 访问被拒绝后，同命令沙箱外重试完成验证，未改动工程配置、JDK 配置或设备。RED 和 GREEN 日志分别为 `build/clip-settings-flows-red-retry.log`、`build/clip-settings-flows-green.log`，`git diff --check` 通过。页面生命周期、重新进入与错误重试的设备交互未验证；本次仓库 Flow 测试覆盖字段通知及异常契约，未新增 MineVm 专项设备/UI 测试。无外部协议、反射或混淆规则变更，不安排 release/R8；备份写入与导出白名单沿用原契约。

## 日志与诊断计划

2026-10-12 安装身份复用不新增日志：本次仅收紧首次生成条件，保留已有 DataStore 事务和调用方持久化失败诊断；重复读取与纯快照映射不记录高频日志，禁止输出安装 ID 或完整进程名。安装 ID 继续作为本机身份排除自定义备份与系统备份，备份协议、字段白名单及 keep/R8 契约均不变化。

2026-09-30 字段订阅调整不新增副作用或高频日志，沿用各页面既有读取失败、保存失败日志及稳定 reasonCode。我的页两个字段分别记录读取失败，页面错误入口取两者任一失败，防止一个字段读取成功掩盖另一字段故障。不改变设置键、写入事务、dirty 版本、备份白名单或外部协议；无新增 keep/R8 契约，本次不安排 release/R8 或设备验证。

复用项目 `logD/logI/logW/logE` 与既有 taskId/eventId。日志正文简体中文；设置值、密码、密文、安装 ID、完整进程名、包名名单、地址、URI 和备份正文不进入新增日志。

| 日志点 | 级别与时机 | 允许字段 | 验证 |
| --- | --- | --- | --- |
| 设置首次加载/保存 | D 开始，I 用户提交成功，W 可重试 IO，E 损坏或不可恢复写入 | operation、domain、durationMs、reasonCode、retryable | fake logger 检查开始/结束及失败；不记录每次 Flow 发射 |
| 安装身份准备/Provider | D 准备与查询，W 超时，E 持久化失败 | eventId、created、ready、durationMs、reasonCode | 冷启动、超时和 Main 保护测试，不打印身份内容 |
| 凭据存取 | D 操作，W 暂时不可访问，E 认证失败/永久失效 | operation、hasCredential、reasonCode、异常类型 | 合成哨兵凭据扫描日志，禁止 throwable.message |
| 备份结果及私有包 | I 发布/交接/完成，W 清理失败，E 任务失败 | taskId、source、phase、targetCount、reasonCode；不输出内容或授权 | 独立结果、身份替换和终态清理失败注入 |
| 恢复分阶段提交 | I 阶段成功，W 部分成功，E 设置提交失败 | taskId、stage、changedCount、reasonCode | 报告与真实提交结果一致 |

普通状态映射、键声明和纯规则不新增日志，避免高频噪声；敏感异常只记录类型与稳定原因码。Worker、Provider 和调度失败分支记录低敏 reasonCode，不输出密码、身份或剪贴内容。

## 已知取舍与开放问题

- 这是跨模块、涉及异步契约的中等规模重构，不是替换两个 Gradle 依赖即可完成。主要成本在 28 个调用文件及其间接调用链。
- 单宿主文件减少跨文件提交问题，但损坏时会影响全部宿主设置；采用显式错误和重置流程，不静默恢复默认。
- 本轮不追求比 MMKV 更快，也不预先承诺包体减少多少；完成后可用冷启动、写入延迟及 APK native 库差异测量，DataStore 的主要收益是异步、可观察和可测试的契约。
- 同步 Binder 协程桥接是局部规则例外，限非 Main、有超时的协议边界；不得向普通读取接口扩散。
- 每日完整备份兜底会增加磁盘和网络使用；若之后要同时减少无变化备份并保证崩溃一致性，应设计持久化变更日志，不能仅恢复 clean 跳过。
- 独立 Room 与 DataStore 不提供全局事务，恢复报告必须真实表达阶段结果；本轮不做全库 schema 重构。
- 本地和 WebDAV 保存独立结果；本地成功后远端失败只更新远端，私有包先记录终态再释放，不主动退避重试、不再次写本地归档。次日或手动生成新包，保留当前每目标普通备份滚动份数。
- 本轮只下沉可复用密码学原语，不顺便将 Torznab 的 SharedPreferences 密文介质迁移到 DataStore。
- 仍需用设备校准 Provider 超时、验证 merged manifest 与功能开关组合；这些是设备验证项，不影响当前选型。

## 变更记录

- 2026-10-12：按用户要求将安装 ID 改为首次生成时保证格式、后续非空即原样复用；同步移除快照读取的格式过滤并补充回归测试。原因是格式不符不能等同于身份尚未生成，避免已保存身份被自动覆盖或读取为空。

- 2026-09-30：按用户要求取消剪贴设置整体快照 Flow，改为三个独立字段 Flow 并更新页面订阅；原因是独立设置没有成组消费需求，整体对象增加无关映射与通知。备份和任务的一次性一致快照保留；验证结果见本次字段 Flow 验证记录。

- 2026-09-30：按用户补充要求取消表单保存密码说明、移除按钮和账号变更的额外密码动作校验；提交始终保存当前输入值，空输入提交空密码。仓库加密与事务边界保持不变，页面接入以备份主方案为准。
- 2026-09-30：按用户要求撤销密码不回填的展示约束，改为页面内回填、默认遮蔽和按钮切换；保留存储加密、备份排除、编辑保护与目标变更校验，交互细节归口备份主方案。
- 2026-09-29：完成组合单测、默认与可选功能 Release/R8、依赖树及源码残留检查，状态改为已完成；记录设备验证边界和部分自动备份重试取舍。
- 2026-09-29：实现单宿主 DataStore、领域仓库、Keystore WebDAV 凭据、Provider 冷启动、异步 UI/Worker、分阶段备份恢复与版本 dirty；删除旧设置入口、初始化器、依赖和系统备份规则。恢复与远端目标安全边界按审查结果收紧，验证记录待最终汇总。

- 2026-09-29：用户授权按方案实施，状态更新为实现中；实施任务分解见 `docs/superpowers/plans/2026-09-29-datastore-replacement.md`。
- 2026-09-29：按用户补充要求收紧交付标准，彻底删除 MMKV 相关代码、依赖、专用测试与配置；撤销保留旧目录系统备份排除项的设计，采用新安装验证基线，不实现旧文件检测、迁移或清理逻辑。
- 2026-09-29：根据“开发阶段、无需旧版本兼容”的要求创建草案；完成 30 个键及 28 个直接调用文件的盘点，选择 Preferences DataStore，明确异步提交、Provider 冷启动、Keystore、备份白名单、并发 dirty 与测试边界。仅形成方案，尚未实施。
