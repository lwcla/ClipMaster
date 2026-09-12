# TorrentSearch 完整接入实施计划

> 状态：实现中
>
> 上游基线：`prajwalch/TorrentSearch@ba82e889637027f7bed82715ec4d2106cbce5a28`
>
> 宿主基线：`ClipMaster@68f838c`

## 目标与硬约束

用独立 `TorrentSearchActivity` 和 `:feature:torrent-search-api`、`:feature:torrent-search` 完整替换旧磁力搜索模块。保持宿主 minSdk 24、compile/target 36、JVM 11、Kotlin/AGP 和 Hilt 体系；默认关闭 `enableMagnetFeature`，开启构建包含实现，关闭构建不包含实现代码。

实现必须保留上游 MIT License、署名、固定提交和本地修改记录；43 个内置 Provider、Torznab、Cloudflare、DoH、搜索/浏览/详情/书签/历史/已查看/设置/导入导出/分享/下载均纳入范围。所有持久协议使用稳定 code/ID，所有新增或修改的方法、构造、回调、字段和局部变量补充简体中文注释。

## 任务 1：重写主方案并同步宿主文档

**文件：**

- 修改 `docs/magnet_search_plan.md`
- 修改 `docs/app_navigation_plan.md`
- 修改 `docs/mine_page_plan.md`
- 修改 `docs/detail_page_plan.md`
- 修改 `docs/download_history_plan.md`
- 修改 `docs/webdav_backup_plan.md`
- 修改 `docs/unit_test_plan.md`

**验收：**

- 主文档状态为“实现中”，旧 Academic Torrents 方案明确标记废弃。
- 文档包含范围、架构、数据流、输入限制、网络安全、备份恢复、生命周期、日志与诊断、测试矩阵、回退、开放问题和 2026-08-30 变更记录。
- 关联文档只记录本页面/能力的接入影响并引用主文档，不复制主方案。
- 若 Mermaid 发生变化，重新生成并校验 `docs/flows` 中文 HTML。

## 任务 2：删除旧模块并建立新 API/实现空壳

**文件：**

- 删除 `feature/magnet-api/**`、`feature/magnet/**`
- 新增 `feature/torrent-search-api/**`
- 新增 `feature/torrent-search/**`
- 修改 `settings.gradle.kts`、`app/build.gradle.kts`、`gradle/libs.versions.toml`
- 修改宿主导航、我的页、详情页、下载历史和 Hilt 集合注入点

**步骤：**

1. 先写 API/宿主契约测试，固定 `TorrentSearchLaunchSource` 稳定 code 与入口发现行为。
2. 定义 `TorrentSearchFeatureEntry.launch(context, initialQuery, launchSource)`、我的页/详情页入口和备份映射接口。
3. 建立 Hilt `TorrentSearchActivity` 空壳；保留 ACTION_SEARCH、SEND、PROCESS_TEXT alias，不声明第二个 launcher。
4. 直接删除旧路由注入、下载历史入口、Academic Torrents 数据源和旧使用记录。
5. 验证功能关闭和开启两条编译路径。

## 任务 3：固定快照、供应链与资源机械移植

**文件：**

- 新增 `feature/torrent-search/LICENSE`
- 新增 `feature/torrent-search/NOTICE.md`
- 新增 `feature/torrent-search/upstream-manifest.json`
- 移植上游 Kotlin、资源与翻译文件
- 修改 version catalog、dependency verification、许可证/依赖清单任务

**验收：**

- manifest 记录提交、43 个 Provider ID、能力、安全等级、域名、关键文件 SHA-256 和本地改造。
- 所有资源具有 `torrent_search_` 前缀，不覆盖宿主资源。
- 移植代码不启动 Koin、不替换宿主 Application/全局异常处理/全局 Coil。
- 新增依赖只来自 version catalog，验证元数据覆盖新增 artifact。

## 任务 4：测试先行接入搜索内核与 43 个 Provider

**状态：** 已完成（2026-09-12）。43 个 Provider、共享协调器/网络边界、固定注册表及 Hilt 接线均已落地并通过模块全量单测、编译与中文注释门禁。

**文件：**

- 修改 `feature/torrent-search/src/main/**/data/**`
- 修改 `feature/torrent-search/src/main/**/provider/**`
- 新增 `feature/torrent-search/src/test/**/provider/**`
- 新增 43 组最小合成 fixture

**步骤：**

1. 先写注册快照失败测试：恰好 43 个内置 Provider、ID 唯一、元数据完整。
2. 先写聚合器失败测试：同时启动、渐进结果、失败隔离、取消、超时、最大结果数。
3. 使用 `SupervisorJob`，每个启用 Provider 立即启动独立协程；Provider 任务 45 秒超时。
4. OkHttp Dispatcher 使用全局 64、同主机 5；连接 10 秒、响应 30 秒。
5. Provider 健康仅由用户操作更新，失败映射到稳定 `ProviderFailureReason` code。
6. 合成 fixture 覆盖正常、空结果、缺字段、超大响应和结构异常。

## 任务 5：测试先行接入数据、协议与输入安全

**状态：** 已完成（2026-09-12）。输入规范化、系统 Intent、Room v1、Preferences DataStore、Torrent/Bencode 校验、书签导入、Torznab 配置与 Android Keystore API Key 存储均已落地；任务 6 可在这些稳定协议和安全边界上接入完整页面。

**文件：**

- 新增 Room `torrent_search_feature.db` v1 及 DAO/Repository
- 新增 `torrent_search_settings` DataStore
- 新增输入、URL、响应、Torrent、Bencode、导入校验器及测试
- 新增 Torznab API Key Keystore 封装及测试替身

**验收：**

- ACTION_SEARCH/SEND/PROCESS_TEXT 去控制字符并限制 512；超限只预填。
- 外部 scheme 仅允许 HTTP/HTTPS/magnet。
- 文本响应 5 MiB、Torrent 20 MiB、书签导入 10 MiB 限制可测试。
- Torrent 验证顶层字典和 `info` 字典；导入逐项容错、稳定键去重。
- Provider/category/sort/launch source/failure reason 均使用稳定 code，不依赖 ordinal/name/类名。
- API Key 用 Android Keystore 加密；URL、Key、Cookie、查询原文和响应正文不写日志。

**05B 已完成边界：** `TorrentSearchActivity` 的 `onCreate` / `onNewIntent` 已把显式启动、`ACTION_SEARCH`、`ACTION_SEND`、`ACTION_PROCESS_TEXT` 解析成同一安全启动快照；当前占位页只显示预填查询，不自动联网也不写搜索历史。Room v1 使用 `info_hash` / `normalized_query` 稳定主键保存书签、搜索历史与已查看记录，Repository 隐藏 DAO/实体并统一清洗、去重、100 条历史上限及低敏写入日志。DataStore 保存 Provider 稳定 ID、安全模式和排序稳定 code，损坏值回退为空来源、安全模式开启、做种数降序。系统 Auto Backup 与设备迁移已精确排除独立数据库和 DataStore 整文件；任务 7 再实现书签、历史、已查看及非敏感排序设置的字段白名单导出、dirty 标记、幂等恢复和报告，Provider 选择与安全模式状态不恢复。

**05C 已完成边界：** Torrent 以 20 MiB 有界流读取并严格验证规范 Bencode 顶层字典、唯一 `info` 字典和原始字节 SHA-1 infoHash。书签导入以 10 MiB 有界读取、64 层 JSON 深度、10,000 条目、256 KiB 单项和 64 字符 envelope key 上限约束输入，兼容 v1 envelope 与固定上游数组，逐项构造临时对象、容错并按 infoHash 去重。Torznab 非敏感配置 ID 复用 `ProviderId` 且使用 `torznab_` 命名空间；API Key 通过 Android Keystore AES-256-GCM 与配置 ID AAD 加密，密文独立存放并排除系统备份，测试使用可替换 cipher/blob store 验证隔离、删除和损坏恢复。页面接入、真实网络/Android 设备 Keystore 冒烟和 release/R8 仍分别归任务 6、8。

## 任务 6：移植完整 Activity 与页面

**状态：** 实现中（2026-09-12）。任务 5 的输入、持久化与密钥边界已经关闭；任务 6 按 06A–06E 纵向切片推进，每个切片都必须保持开启功能编译通过，不能先堆页面再补生命周期或安全确认。

**文件：**

- 修改 `feature/torrent-search/src/main/**/TorrentSearchActivity.kt`
- 移植搜索、Latest/Top、详情、书签、历史、已查看、Provider、设置等 Compose 页面
- 修改 ViewModel/DI 为 Hilt
- 接入系统 Intent、分享、下载、SAF、DoH、Torznab、Cloudflare WebView

**验收：**

- 页面只在可见/前台时收集和启动重型任务，退出时取消搜索、图片和详情请求。
- 共享 Compose 组件接收稳定 UI state/slot/callback，不依赖具体 ViewModel/NavController。
- 不安全 Provider 默认关闭；首次关闭安全模式有全局确认。
- 首次多源搜索前披露查询会发送到启用的第三方服务，确认仅本机保存。
- WebView 限定当前 Provider 来源、禁文件访问/任意跳转/混合内容/SSL 绕过；仅挑战页运行受限 JavaScript。
- 图片按展示尺寸解码，详情 Markdown/HTML 不执行脚本且链接统一校验。

**实施切片：**

1. **06A 搜索主链路（已完成，2026-09-12）：** 将占位 Activity 替换为 Hilt ViewModel + Compose 导航壳；接入查询、分类、排序、Provider 渐进事件、搜索取消、生命周期可见性、首次多源隐私披露和安全模式关闭确认。生产入口不在 `init` 或 Intent 中自动联网；重新开启安全模式立即取消旧搜索并收敛成人分类。
2. **06B 浏览与详情（已完成，2026-09-12，独立审查修复）：** 接入 Latest/Top、详情页、已查看写入、受限富文本模型、链接白名单和图片尺寸边界；详情图片使用 feature-local 禁自动重定向客户端，NavBackStackEntry START/STOP 直接创建/释放图片子 Composition，正文图与截图使用独立稳定 key 命名空间；Browse 首设置快照与后续值变化显式区分，避免默认值竞态漏载或重复加载。
3. **06C 本地数据页（已完成，2026-09-12）：** 接入书签、搜索历史、已查看页面及清空确认；书签导入复用任务 05C 解析器并逐项写 Repository，导出在完整 JSON 通过 10,000 条与 10 MiB 预检后才交由 SAF 创建文档写出，不持久化临时 URI；三类目的地只在可见前台收集，历史回放返回搜索页激活后才提交一次。
4. **06D Provider 与设置：** 接入内置/Torznab Provider 管理、安全模式、默认排序、DoH 和本机披露状态；Torznab API Key 只经 `TorznabApiKeyStore`，配置模型不携带明文密钥。
5. **06E 系统副作用：** 接入分享、复制、磁力 Intent、torrent 下载与 20 MiB 校验、SAF 写出、Cloudflare 挑战 WebView；WebView 禁文件访问、混合内容、SSL 绕过和跨来源跳转，仅受限挑战页允许脚本。

## 任务 7：备份、旧数据清理与系统排除

**文件：**

- 新增 TorrentSearch `BackupFeatureContributor`
- 修改自定义备份 schema v4 分类、计数、恢复预览和报告
- 修改 Android Auto Backup 规则
- 新增旧磁力数据检测/确认/精确清理器及测试

**验收：**

- 备份 version 1 的书签、搜索历史、已查看和非敏感设置；按 infoHash/规范化查询幂等恢复。
- 不备份 Torznab/API Key/Cookie/NSFW/SAF URI/健康/缓存/临时文件。
- 首次手动备份或开启自动备份前披露未加密 ZIP/WebDAV 风险；manifest 只记录敏感标记与数量。
- 旧磁力备份报告“不兼容并跳过”，不迁移且不计成功。
- 用户确认后只删除已校验旧数据库、sidecar 和 Academic Torrents 缓存；退出则保留且阻止进入新功能。
- 系统 Auto Backup 排除新数据库、DataStore、WebView 和缓存。

## 任务 8：门禁、端到端测试与发布验证

**文件：**

- 新增中文注释扫描任务
- 新增资源/Manifest 冲突、测试资源隐私、运行时日志敏感字段扫描
- 新增 instrumentation 与发布检查脚本/文档

**验收命令：**

- 模块单测与 `:app:testDebugUnitTest`
- 功能关闭 `:app:compileDebugKotlin`
- 功能开启 `:app:compileDebugKotlin -PenableMagnetFeature=true`
- Release/R8 两条路径
- `connectedDebugAndroidTest`（API 24、API 36 环境可用时）
- 中文注释扫描、依赖验证、资源/Manifest 扫描、`git diff --check`
- 对比启用/关闭 APK 大小、权限、Manifest 和冷启动初始化边界

真实联网冒烟生成 Provider 健康报告，但第三方站点临时故障不阻塞发布；代码异常、契约遗漏、崩溃或敏感信息泄漏不可豁免。回退仅通过发布 `enableMagnetFeature=false` 的新版本。

## 日志与诊断总则

允许记录 Provider ID、阶段、结果数量、耗时、HTTP 状态码、是否重试和稳定 reasonCode。禁止记录 URL、API Key、Cookie、查询原文、magnet 原文、响应正文、书签/历史/已查看内容和可恢复登录态。网络、解析、数据库写入、备份恢复、导入导出、Keystore、Cloudflare、下载和旧数据清理必须有阶段日志与脱敏失败分类；取消使用信息级或调试级，不作为异常堆栈噪声。

## 变更记录

- 2026-08-30：根据已确认的最终产品与工程决策建立可执行任务清单；原因是进入固定快照的正式替换实施阶段。
- 2026-09-12：同步任务 4 完成与任务 5 启动状态；原因是 43 Provider 已具备固定运行时注册入口，而数据、协议和 Android 消费层仍需按后续检查点继续实现。
- 2026-09-12：完成任务 05B 的 Intent 消费、Room v1、Repository、DataStore 与系统备份排除；原因是先建立完整页面可以直接消费的稳定输入和持久化边界，并避免新敏感数据被系统整文件备份。
- 2026-09-12：完成任务 05C 的 Torrent/Bencode 校验、书签逐项导入、Torznab 配置和 Android Keystore API Key 存储，并将任务 5 标记为已完成；原因是完整页面接入前需要先关闭文件输入、稳定身份、内存上限、凭据隔离与损坏恢复边界，并吸收独立安全复审提出的五项问题。
- 2026-09-12：启动任务 6 并拆分为 06A–06E 五个可编译纵向切片；原因是完整页面同时涉及网络生命周期、本地数据、凭据、SAF 与 WebView，高风险副作用必须随各自页面一起验证，不能最后集中补安全边界。
- 2026-09-12：完成任务 06A 搜索主链路；原因是后续浏览、详情和本地数据页面必须建立在已验证的 Hilt 状态编排、生命周期取消、首次多源隐私披露及安全模式状态机上。06B–06E 仍按原切片继续。
- 2026-09-12：任务 06A 独立复审后关闭设置首帧竞态、安全模式异步窗口、目的地生命周期、前置任务复活、新 Intent 旧请求、设置异常逃逸、搜索阶段与结果卡敏感模型问题；最终 542 项模块测试、注释门禁、资源合并及开启功能 App 编译通过，复审无遗留发现。原因是页面切片必须先把网络授权、取消和 fail-closed 状态机做成后续路由可复用的稳定基线。
- 2026-09-12：完成任务 06B 的 Latest/Top 浏览、原生详情、已查看写入、安全富文本与同源 HTTPS 图片边界；原因是结果入口必须在接入 06E 外部副作用前先具备原生、可取消且不把敏感正文或 URL 放入路由的详情闭环。
- 2026-09-12：完成任务 06B 独立审查修复；图片网络改用 feature-local 禁自动重定向客户端，详情生命周期直接控制图片子 Composition，Browse 首设置快照竞态、跨区段图片 key 和本地化格式均由回归测试固定。原因是 URL 首跳白名单、停止收集的状态流和相同 URL 本身不能分别替代重定向边界、直接 Composition 取消和列表全局唯一身份。
- 2026-09-12：完成任务 06C 的本地数据主页、三个独立数据目的地、详情书签动作、历史写入/前台回放、共享清空确认及有界书签 SAF 导入导出；原因是三类用户数据必须在 Provider 设置和系统外部动作之前具备精确身份、生命周期取消、固定错误与不持久化 URI 的安全闭环。任务 6 仍保持实现中，06D–06E 继续按原切片推进。
