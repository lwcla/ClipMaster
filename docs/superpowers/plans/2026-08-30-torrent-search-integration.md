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

**状态：** 实现中（2026-09-12）。已完成 512 字符输入规范化、自动搜索阻断状态、Provider 共用控制字符清洗、外部 HTTP/HTTPS/magnet scheme 白名单、三类系统 Intent 消费、独立 Room v1 与 Preferences DataStore；Torrent/Bencode、书签导入和 Torznab Keystore 尚未完成。

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

## 任务 6：移植完整 Activity 与页面

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
