# TorrentSearch 固定快照接入主方案

> 状态：实现中
>
> 上游基线：`prajwalch/TorrentSearch@ba82e889637027f7bed82715ec4d2106cbce5a28`
>
> 宿主基线：`ClipMaster@68f838c`

## 方案处置与目标

本文件是 TorrentSearch 的唯一主方案文档。旧“磁力搜索第一版方案”及其 `Academic Torrents` 单源、本地 XML 索引、`:feature:magnet-api`、`:feature:magnet`、宿主下载记录磁力 Tab 和旧磁力备份协议均已废弃；它们只作为历史记录，不得继续扩展。后续实现按本方案完整替换，旧数据不迁移。

目标是在不改变宿主 minSdk 24、compile/target 36、JVM 11、Kotlin/AGP 与 Hilt 体系的前提下，接入固定上游快照的完整 TorrentSearch 能力：搜索、浏览、详情、书签、历史、已查看、Provider、设置、导入导出、分享、下载、Torznab、Cloudflare 与 DoH。默认构建关闭 `enableMagnetFeature`；关闭时不包含实现代码，开启时才包含新功能。

## 范围与非目标

- 固定接入 43 个内置 Provider；Provider 注册表、ID、能力、安全等级、域名和关键文件校验值均冻结在本地 manifest 中。
- 保留上游 MIT License、署名、固定提交、关键文件 SHA-256 和本地改造记录；不跟随上游分支滚动更新。
- 使用独立 `TorrentSearchActivity`，不把页面并入宿主剪贴搜索或下载历史页面。
- 新旧磁力数据、旧数据库、sidecar 与 Academic Torrents 缓存仅支持检测、确认和精确清理；不转写、不迁移、不计入恢复成功数。
- 不建设通用热插拔平台；API 模块只提供 TorrentSearch 所需的小型稳定宿主契约。

## 架构与宿主 API

### 模块边界

- `:feature:torrent-search-api`：仅放置稳定契约、Hilt 空集合声明、启动来源和备份映射接口；不得泄漏 Compose 页面状态、Room 实体、Repository、Provider 或上游实现类型。
- `:feature:torrent-search`：承载固定快照、`TorrentSearchActivity`、Hilt DI、页面、数据存储、网络、安全包装、Provider 调度和备份参与者。
- `app`：默认只依赖轻量 API；`-PenableMagnetFeature=true` 时才依赖实现模块。关闭构建的发现集合为空，入口、备份分类、实现类和 Activity 均不可达。

### 启动与入口契约

宿主通过 `TorrentSearchFeatureEntry.launch(context, initialQuery, launchSource)` 启动独立 Activity。`TorrentSearchLaunchSource` 使用稳定 `code`，禁止依赖枚举 `name`、`ordinal` 或类名。我的页以空查询启动；详情页只传候选关键词，不写系统剪贴板；外部 `ACTION_SEARCH`、`ACTION_SEND`、`ACTION_PROCESS_TEXT` 复用同一输入规范化链路。Manifest 保留这些 alias，但不声明第二个 launcher。

关闭功能时，宿主只隐藏入口并保持其余页面正常可用；不得留下旧 `MagnetSearchRoute`、旧下载记录 Tab、旧 Repository 或旧 Hilt 集合引用。

## 43 Provider 与搜索调度

### 固定注册表

内置 Provider 恰好为 43 个，Provider ID 必须唯一、稳定且写入 `upstream-manifest.json`。每项记录展示元数据、能力、安全等级、允许域名和启用策略。Torznab 属于用户配置 Provider；其 API Key 仅经 Android Keystore 加密保存，不进入备份或日志。

不安全 Provider 默认关闭。用户首次关闭安全模式前展示全局确认；首次执行多源搜索前披露查询会发送给每个已启用第三方服务，确认结果只保存在本机。

### 并发、失败与生命周期

每个启用 Provider 使用 `SupervisorJob` 立即启动独立协程，结果渐进汇总；单个 Provider 失败不得取消其他任务。每项搜索上限 45 秒，离开页面、修改搜索或用户取消时必须取消全部关联请求。全局最大结果数由聚合器统一限制。

OkHttp Dispatcher 固定全局并发 64、同主机 5；连接超时 10 秒、响应超时 30 秒。Provider 健康只因用户操作更新，失败统一映射为稳定 `ProviderFailureReason.code`。浏览、详情和图片等重型工作只在页面可见/前台时启动，离开后及时取消，不因不可见 Tab 持续消耗网络或 CPU。

## 输入、网络与内容安全

- `ACTION_SEARCH`、`SEND`、`PROCESS_TEXT` 输入先去除控制字符并限制 512 字符；超限文本只预填，不能自动搜索或写入历史。
- 外部链接仅允许 `http`、`https` 与 `magnet` scheme；分享、下载、详情链接和富文本点击均走同一校验器。
- 文本响应上限 5 MiB、Torrent 文件上限 20 MiB、书签导入上限 10 MiB；超限、结构异常或缺字段必须返回可测试的失败分类。
- Torrent/Bencode 仅接受顶层字典且必须含 `info` 字典；导入按条容错、按稳定键去重。
- DoH、Cloudflare 与 WebView 都限定到当前 Provider 允许来源。Cloudflare WebView 禁止文件访问、任意跳转、混合内容和 SSL 绕过，仅挑战页面可执行受限 JavaScript。
- 详情 Markdown/HTML 不执行脚本；图片按展示尺寸解码。请求、响应、Cookie、完整 URL、搜索原文、API Key 和磁力原文均不得写日志。

## 数据、备份与旧数据

### 本地持久化

新功能独立使用 Room `torrent_search_feature.db` v1 和 `torrent_search_settings` DataStore。Provider、分类、排序、启动来源和失败原因的持久字段全部使用显式稳定 `code`/ID。书签、搜索历史、已查看和非敏感设置属于可恢复用户数据；缓存、临时文件、Provider 健康、Cookie、NSFW 选择、SAF URI、Torznab/API Key 与其他设备绑定密文不备份。

### 备份恢复

自定义备份升级至 schemaVersion 4。启用实现时，`BackupFeatureContributor` 以字段白名单导出版本 1 的书签、搜索历史、已查看和非敏感设置，并在预检、计数与恢复报告中单列 TorrentSearch 分类。恢复按 infoHash、规范化查询等稳定键幂等合并，重复恢复不得生成重复项。

首次手动备份或开启自动备份前，需要披露 ZIP/WebDAV 未加密风险；manifest 只记录敏感标记和数量。系统 Auto Backup 排除新数据库、DataStore、WebView 和缓存。旧磁力备份文件在预检与报告中标记为“不兼容并跳过”，不得迁移或计作成功。

### 旧磁力清理

首次进入新功能前检测旧数据库、sidecar 和 Academic Torrents 缓存。用户确认后仅删除已校验的旧目标；取消/退出则保留旧数据并阻止进入新功能，避免新旧数据并存产生误解。清理前后记录低敏数量、阶段和 reasonCode，不记录内容或路径。

## 页面与交互边界

`TorrentSearchActivity` 内部承载搜索、Latest/Top、详情、书签、历史、已查看、Provider 和设置页面。共享 Compose 组件接收稳定 UI state、slot 与 callback，不直接持有具体 ViewModel、NavController 或 Repository。Activity 只负责 Intent 接入和导航级生命周期，页面负责可见性驱动的数据收集与任务取消。

我的页、详情页、下载历史和应用导航只保留各自入口/退出影响，完整搜索行为、安全策略与数据契约均以本文件为准。宿主下载历史不再接入旧磁力 Tab；TorrentSearch 内部自行管理其下载能力和历史。

## 供应链与资源

上游代码、资源和翻译采用机械移植并保留本地修改说明。所有导入资源使用 `torrent_search_` 前缀，禁止覆盖宿主资源；仅 AGP 固定识别的 `src/main/res/resources.properties` 保持原文件名，其 `unqualifiedResLocale=en` 元数据不生成 `R` ID。移植实现不得启动 Koin，不得替换宿主 Application、全局异常处理或全局 Coil。新增依赖必须来自 version catalog，并补齐 dependency verification 与许可证/依赖清单校验。

### 任务 3 固定基线（2026-08-31）

已将 `prajwalch/TorrentSearch@ba82e889637027f7bed82715ec4d2106cbce5a28`
固定为本地供应链基线：`feature/torrent-search/LICENSE` 保留 MIT 原文，
`NOTICE.md` 说明署名与宿主不接管边界，`upstream-manifest.json` 固定 43 个
内置 Provider 的稳定 code、源路径、SHA-256、能力、安全/NSFW 标记、源域名与
HTTPS-only 本地策略。Provider 域名仅从固定源码的可执行 URL 常量、接口字段或详情下载选择器提取，不采纳致谢注释 URL；例如 `1337x` 仅记录 `1337x.to` 与 `itorrents.org`。清单禁止远程代码、选择器和脚本下发；LimeTorrents 的
`itorrents.net` 明文地址仅作为上游源事实记录，本地策略仍禁止明文访问。

上游 `app/src/main/res` 已完整机械导入并保留已有语言。所有本地资源定义、XML
引用和文件型名称都增加 `torrent_search_` 前缀（AGP 固定 `resources.properties` 元数据文件除外）；默认 `values` 保持英文，任务 2
的核心入口和空壳字符串同时保留在 `values-zh-rCN`。本阶段不导入上游 Kotlin、
Koin、Application、Activity 或 launcher 声明，资源可由当前空壳模块独立编译。
为避免资源阶段引入未使用的 SplashScreen 运行时，API 31 的上游
`Theme.SplashScreen`/`postSplashScreenTheme` 关系仅回退为平台无 ActionBar 父主题；
带前缀的主题名称仍保留，后续由宿主 Activity 接入时再决定是否恢复该运行时能力。

`THIRD_PARTY_DEPENDENCIES.md` 记录当前模块已经声明的直接依赖和许可证来源，并明确 JUnit 4.13.2、`kotlinx-serialization-json` 1.11.0 为本阶段快照契约的 test-only 依赖。独立测试 fixture 以 manifest 文件 SHA-256、43 个精确 code/sourcePath 对、关键文件 SHA 和逐项资源路径/内容摘要锁定完整基线；错误域名、Provider 字段和资源缺失的内存受控变异必须失败。
完整 Gradle dependency-verification metadata 与完整许可证汇总仍延后到任务 8：
原因是 Provider 运行时依赖尚未确定，当前生成会错误锁定不完整集合。

## 实施拆分

### 任务 2 阶段实现（2026-08-31）

已删除 `:feature:magnet-api`、`:feature:magnet` 及其 Academic Torrents 单源实现，建立轻量 `:feature:torrent-search-api` 与可选 `:feature:torrent-search` 空壳。宿主继续保留 `enableMagnetFeature` 属性名：关闭时只依赖 API 和空 Hilt 集合，开启时才编入实现模块。我的页、剪贴详情和备份报告仅消费稳定入口契约；宿主导航不再注册功能内 NavHost 路由，下载历史也只保留视频与图片两类。

本阶段的 `TorrentSearchActivity` 只验证显式启动、Hilt、资源隔离和 Manifest 合并，不读取外部查询，也不移植 Provider、完整页面或数据层。`ACTION_SEARCH`、`ACTION_SEND`、`ACTION_PROCESS_TEXT` 由三个 exported alias 指向同一非 exported Activity，且均不声明 `MAIN`/`LAUNCHER`。启动来源只通过 `TorrentSearchLaunchSource.code` 传递；每个来源的精确稳定 code 和未知回退均由单测固定。我的页由功能 API 渲染 slot 提供本地化内容，宿主薄适配层复用现有 `ListEntryCard` 和主题 token；备份预检计数、恢复分类顺序及 label 由纯展示 mapper 承载并覆盖稳定映射测试。阶段空壳没有网络、文件、数据库或后台副作用，因此不新增运行时日志，避免把候选查询文本写入日志；后续输入消费、搜索、存储和备份实现继续遵循本文件的日志与脱敏计划。

### 任务 04A 共用搜索内核（2026-08-31，2026-09-01 审查收口）

本子批次建立后续 Provider 共用的 `model/contract`、`provider`、`network`、`search`、`logging` 与 `di` 分层，不移植真实 Provider、注册表、UI、Room、Torznab、WebView、下载或备份。协议以固定上游 `ba82e889` 为事实：`Category` 精确包含 `all/anime/apps/books/games/movies/music/porn/series/other` 并提供 `isNsfw`，`SortCriteria` 精确包含 `name/seeders/peers/file_size/date` 且默认 `seeders`，`SortOrder` 为 `ascending/descending` 且默认 `descending`；全部协议枚举只通过显式 `code` 与大小写敏感的 `fromCode` 读写，`null`、空值、大小写变化和未知值统一回退安全默认。`ProviderId` 是校验长度与字符集的强类型，贯穿 Descriptor、Torrent、搜索事件和日志事件；`ProviderType`、Provider base URL/Cloudflare solver URL、支持分类、安全等级、默认启用、Cloudflare 标记及详情/Latest/Top 能力也由显式契约承载。

`Torrent` 与 `TorrentDetails` 覆盖上游所需的 infoHash、名称、大小、做种/下载人数、Provider ID/名称、上传时间、分类、详情链接、magnet、种子下载链接，以及详情页 uploader、lastChecked、描述、海报、截图、文件和 tracker。时间字段统一使用带单位后缀的 UTC `epochMillis`，避免为 minSdk 24 引入全局 desugaring。`SearchProvider.search(query, category)` 显式接收分类，详情、Latest、Top 能力扩展基础搜索能力，并带分类或备用域名契约。

Provider 只能依赖 `TorrentSearchNetworkClient`，直接 OkHttp 实现由 Hilt 以功能内限定符延迟提供共享客户端，不复用也不覆盖宿主全局 `OkHttpClient`：Dispatcher 固定全局 64、同主机 5，连接 10 秒、读取 30 秒，OkHttp 不另设总 `callTimeout`，由协调器统一执行 45 秒 Provider 总任务上限；正式构造没有 HTTP 测试旁路并严格 HTTPS-only。状态校验、Cloudflare 分类和透明解压后的 5 MiB 限量读取都在同一个 OkHttp `Call` 的 callback/IO 线程内完成，响应在恢复调用协程前关闭；响应头到达后的慢 body 仍受 Provider 总超时、用户取消和 collector 取消约束，取消会触发 `Call.cancel()` 并处理 continuation 恢复竞争。

共享客户端及构造注入客户端都强制关闭 OkHttp 自动 HTTP/SSL 重定向，普通 `getText`、`getJson`、`postJson`、`submitForm` 在原调用协程显式处理 301/302/303/307/308；每一跳先通过既有 callback 边界关闭响应且不消费跳转正文，再检查协程取消并创建下一 `Call`，因此不会重置协调器外层 45 秒总任务预算。来源固定为首次请求 `HttpUrl` 的 scheme、host 和有效 port，不扩大到同主域、子域、其他 Provider 白名单或不同端口；HTTPS 降级、跨 host/port、缺失或非法 `Location`、带用户信息 URL、循环及超过 20 次跳转都在下一 `Call` 前以无正文 `HttpDeniedException` 拒绝。相对 `Location` 只用当前请求 `HttpUrl.resolve`，GET/HEAD 保持方法，POST 的 301/302/303 转 GET 并移除正文及 `Content-Length`、`Content-Type`、`Transfer-Encoding`，307/308 保留原方法和已编码 body。`redirectLocation` 继续只返回一次未信任的 `Location` 且绝不跟随，AniRena 调用方仍须执行精确 host/magnet 校验。最终成功正文继续受解压后 5 MiB 上限，最终拒绝与挑战继续复用状态分类；只有 403/429/503 等挑战状态同时带 Cloudflare 特征头才映射 `cloudflare_locked`，Cloudflare 404 仍为 `http_denied`，`SocketTimeoutException` 映射 `timeout`。测试以 `okhttp-tls` 的 `HeldCertificate` 和 MockWebServer 构造仅测试信任的回环 HTTPS，不为生产暴露明文开关。

`SearchCoordinator` 在 `channelFlow` 内使用结构化 `supervisorScope`：先以独立 Deferred 实际进入全部启用且安全模式允许的 Provider，快源结果暂存到所有来源均获得启动机会后再并发处理，从而渐进发射 started/results/failed/skipped/completed 并隔离单源失败；默认每 Provider 45 秒，安全模式跳过非 SAFE 来源，达到 `maxResults` 时只取消其他未完成 Deferred，collector 或父 scope 取消则向下取消全部协程与网络 Call，且不会伪造 Completed。日志使用封闭的 `ProviderLogStage`、`ProviderId` 与可空 `ProviderFailureReason`；Android 输出只读取稳定 code 及数量、耗时、状态码、是否重试，不接触 URL、查询、header、Cookie、API Key、payload、响应正文或 `Throwable.message`。模块级中文注释扫描同时检查 `src/**/*.kt` 与模块 `build.gradle.kts`，独立于构建脚本的正反 fixture 锁定类型、方法、构造、`val`/`var`、普通多行参数、`for` 变量、`catch`、单/多命名 lambda、解构 lambda 与字符串 receiver lambda，并确认函数调用 named argument、`when` 标签、继承续行及匿名对象表达式不会误报。该门禁是确定性文本检查而非完整 Kotlin 语法解析器，复杂新语法仍需代码评审补充确认，避免夸大自动覆盖范围。

### 任务 04B1 首批 Provider（2026-09-01）

本子批次只移植固定快照的 AniLibria、AnimeTosho 与 AniRena，不接入第四个 Provider、注册总表、UI、Room、Torznab、Cloudflare WebView、下载或备份。三者都只通过构造注入的 `TorrentSearchNetworkClient` 联网；站点编排类与纯 Kotlin 的 URL、JSON/HTML、magnet/infoHash、大小和 UTC `epochMillis` 解析器分层放置。网络读取和 AniRena `redirectLocation` 保持在原 Provider 调用协程，响应返回后才将 JSON/Jsoup DOM、逐条校验和领域映射切换到可注入且默认 `Dispatchers.Default` 的计算调度器；DOM draft 不持有网络能力。大列表统一使用逐元素 `ensureActive` 的协作遍历，用户停止、页面退出或父任务取消后不继续映射剩余条目。内置请求仅允许 manifest 中的精确 host 与 HTTPS，查询参数由 OkHttp `HttpUrl.Builder.addQueryParameter` 编码并先删除控制字符；详情地址和 AniRena 磁力跳转在发起网络前校验 host，跳转结果再校验 `magnet` scheme 与 btih。

AniLibria 保留 search 能力且最多取 15 个 release，每个 release 的 torrent 请求并发发起并继续受 04A 共享 OkHttp Dispatcher 的 64/5 边界约束；URL builder、顶层 JSON 解析和 torrent 领域映射均为独立 `internal` 协作者。ISO-8601 接受无小数或 1..9 位小数秒以及 `Z`/`±HH:mm`，超过毫秒的位数明确截断后再转 UTC `epochMillis`，无需 minSdk 24 全局 desugaring。AnimeTosho 实现 search/details，Today/Yesterday 相对日期以可注入基准时间转换为 UTC 日起点。AniRena 实现 search/details/latest/top；本地安全审计结论固定为 `NSFW` 且默认关闭，不采用上源源码的 Safe 标记。单条缺少必填字段时跳过该条，顶层结构异常统一抛出不携带原文的 `ProviderParseException`。

`MagnetInfoHashParser.create` 恢复固定 `ba82e889` 的 `TorrentUtils` 中 20 个公共 tracker 及原始顺序，输出时逐个 URL 编码；该列表是随应用版本审查的静态快照，不在运行时联网刷新，也不得用 trackerslist 当前内容替换。后续如需增删 tracker，必须另做安全、隐私和可用性审计并随版本发布。btih 解析支持 40 位 hex 和 32 位 RFC 4648 base32；多个 `xt` 中遇到非法 btih 会继续查找后续合法候选。

本批次使用三份独立最小合成 fixture 及内联边界样本，覆盖正常字段映射、空结果、单条缺字段、顶层异常、查询/分类编码、详情 host 拒绝、AniRena 磁力重定向与时间换算，以及 ISO 1/3/6/9 位小数秒、40 位 hex、独立非零 base32 位序、多 `xt` 回退和固定 tracker 完整输出。线程测试从单线程调用上下文发起，断言网络回调仍在 caller、解析与映射只进入注入的 parser dispatcher；协作遍历测试在首条回调内取消子任务，断言第二条不会执行。fixture 不包含真实用户内容，测试不访问互联网。本批次不新增 Provider 内部日志：调度开始/成功/失败仍由 04A 的结构化日志记录，解析器不接收 logger，因此无法输出查询、URL、magnet 或 fixture 正文。

### 任务 04B2 第二批 Provider（2026-09-02）

本子批次只移植固定快照的 AudioBookBay、BangumiMoe 与 BitSearch，不接入注册总表、UI、Room、备份或第四个 Provider。三者继续复用 04B1 的精确 HTTPS host 策略、查询清洗、magnet/infoHash、UTC 日期和协作取消能力。AudioBookBay 保留“结果页 draft 后按 DOM 顺序访问详情页读取 infoHash”的两阶段流程，解析和领域映射进入可注入计算调度器，详情网络读取留在调用协程；BangumiMoe 使用 Kotlin serialization 构造 JSON payload 并严格校验 `torrents` 顶层数组、详情 URL path segment 及 magnet/infohash 一致性；BitSearch 以结构化并发立即请求固定 1..5 页，在调用上下文发起网络并按页码稳定合并结果。

AudioBookBay 固定 SAFE、BOOKS、默认关闭并实现 search/details/latest；BangumiMoe 固定 SAFE、ANIME、默认关闭并实现 search/details/latest；BitSearch 按本地 manifest 审计固定为 NSFW、默认关闭并实现 search/details/latest/top，覆盖九个站点分类且 ALL 不发送分类参数。所有详情、torrent 下载和海报 URL 仅允许各自 manifest 的单一精确 host，不放行子域、明文 HTTP、查询污染的 Bangumi 详情 ID 或任意重定向。正常空容器返回空列表，顶层结构异常抛不带正文的 `ProviderParseException`，单条缺必填字段或非法/冲突 btih 只跳过该条。

本批以最小合成 HTML/JSON fixture 和 recording fake 覆盖正常/空/缺字段/顶层异常、恶意 host、查询与 ID 编码、AudioBookBay 两阶段顺序和取消、Bangumi payload 与 hash 冲突、BitSearch 五页稳定顺序/取消/分类/相对日期及三站 dispatcher 边界。fixture 不含真实第三方正文或用户数据且测试不联网。本批不新增 Provider 内部日志：沿用 04A 聚合器的低敏阶段、数量、耗时、状态码和 reasonCode，解析器不接收 logger，禁止记录 URL、查询、magnet、JSON/HTML 正文或底层异常 message。

### 任务 04B3 第三批 Provider（2026-09-02）

本子批次只移植固定快照的 BlueRoms、BT4G 与 BTDigg，不接入注册总表、UI、Room、备份或第四个 Provider。三者继续复用精确 HTTPS host、查询清洗、magnet/infoHash、UTC/相对日期和协作取消能力；每站只通过构造注入共享网络客户端，DOM、Base64、日期、hash 和领域映射进入可注入且默认 `Dispatchers.Default` 的计算调度器，所有网络调用保留在调用协程。BlueRoms 与 BT4G 都采用两阶段 draft：先解析结果 DOM，再按 DOM 顺序请求同站下载页或详情页，取消后不再访问后续页面。

BlueRoms 固定 SAFE、GAMES、默认关闭并实现 search/details；Base64 `data-link` 只在计算 dispatcher 解码，解码结果必须是 UTF-8、非空且包含合法 btih 的 magnet，任何编码值和解码正文都不进入日志。BT4G 固定 SAFE、默认关闭并实现 search/details/latest/top，支持 APPS/BOOKS/MOVIES/MUSIC/OTHER 且标记 Cloudflare；`downloadtorrentfile.com/hash/<hash>` 只作为精确匹配的离线 hash marker，禁止联网、禁止作为模型 URL 产出，其他外域或路径一律拒绝。BTDigg 固定 SAFE、OTHER、默认关闭并实现 search/details，搜索结果保留通过 btih 校验的 magnet，结果与详情相对日期使用可注入时钟。所有请求、详情、下载、海报和截图地址只允许各自 manifest 的单一精确 HTTPS host。BlueRoms 与 BTDigg 的固定上游结果 parser 没有稳定顶层/空态哨兵，因此严格沿用“结果 selector 未匹配即合法空列表”，避免把真实零结果误报为解析失败；BT4G 仍以稳定 `main` 区分顶层异常。单条或详情缺必填字段返回空值而不拖垮整批。

本批以最小合成 HTML fixture 和 recording fake 覆盖正常/空/缺字段/顶层异常、查询与分类编码、descriptor 能力与安全元数据、精确 host、两阶段顺序与取消、Base64 非法输入、BT4G 外域 marker 离线提 hash和恶意 marker 拒绝、BTDigg magnet 保留与相对日期，以及三站 caller/parser 线程边界。fixture 不含真实第三方正文或用户数据且测试不联网。本批不新增 Provider 内部日志：继续由 04A 聚合器记录低敏阶段、数量、耗时、状态码和 reasonCode，解析器不接收 logger，禁止记录 URL、查询、magnet、Base64、HTML 正文或底层异常 message。

### 任务 04D2 NekoBT 与 Nyaa 族 Provider（2026-09-06）

本子批次只移植固定快照的 NekoBT、Nyaa 与 Sukebei，不接入注册表、数据层、UI、备份或其他 Provider。NekoBT 保留独立 HTML Provider 与具名结果/详情 parser；Nyaa 和 Sukebei 共享无网络的 `NyaaFamilyResultsParser`、`NyaaFamilyDetailsParser`，通过 descriptor、精确 URL policy、类别映射或固定类别及信息卡 selector 表达差异。三个入口分别构造 search/latest/top URL，查询由 `HttpUrl` 编码；网络调用留在调用协程，DOM 与领域映射进入可注入的 `Dispatchers.Default`，逐行解析复用可取消遍历。

NekoBT 固定 SAFE、默认关闭、ANIME 与唯一 `nekobt.to`；Nyaa 固定 SAFE、默认开启、六类映射及备用 host `nyaa.iss.ink`；Sukebei 固定 NSFW、默认关闭、PORN 与唯一 `sukebei.nyaa.si`。名称和合法 magnet/btih 是必填字段，详情必填缺失返回 null；详情输入在网络前拒绝非 HTTPS、非 443、userinfo 和非精确 host。可选详情、下载与海报链接校验失败只清空对应字段。Nyaa 详情类别优先第二级 `6_2`，两站 magnet 与下载按钮按固定上源从全页查找，描述保留为不可信 HTML。

本批用三站独立中性合成 fixture 与 Provider 公开契约测试覆盖正常、空、结构漂移、必填缺失、查询参数、精确 host、可选恶意 URL、Nyaa 评论链接排除与完整类别、Sukebei 固定 PORN、NekoBT 嵌套标题/data-tip、epoch 溢出、非法计数、caller/parser dispatcher 及父取消。复用共享网络层既有 5 MiB HTTPS 测试，不创建巨型 fixture 或真实网络请求。无持久数据、序列化和反射契约，因此不改备份及 keep/R8 规则。

日志与诊断继续复用 04A 聚合器的低敏 Provider ID、阶段、数量、耗时和 reasonCode；Provider/parser 不新增 logger，禁止记录 URL、查询、magnet、HTML、登录态或异常 message。解析失败通过合成 fixture、断言和统一失败分类定位，避免重复及高敏日志。

1. 删除旧磁力模块，建立 API/实现空壳和宿主发现/启动/备份映射契约测试。
2. 固定快照、许可证、manifest、资源前缀和依赖验证。
3. 先以失败测试固定 43 Provider 注册表、调度器、超时、取消、渐进结果和失败隔离，再接入搜索内核。
4. 先以失败测试固定输入、URL、响应、Torrent、Bencode、导入、Keystore 与稳定协议，再接入 Room/DataStore。
5. 迁移完整 Activity、页面、Hilt、系统 Intent、分享、下载、SAF、DoH、Torznab 与 Cloudflare。
6. 接入 schemaVersion 4、备份贡献者、Auto Backup 排除及旧数据检测/精确清理。
7. 完成中文注释扫描、资源/Manifest 冲突扫描、测试资源与日志敏感字段扫描，以及端到端与发布验证。

## 日志与诊断计划

网络、解析、数据库写入、备份恢复、导入导出、Keystore、Cloudflare、下载和旧数据清理均记录开始、关键阶段、成功、可恢复失败、不可恢复失败及取消边界。日志使用简体中文正文和低敏字段：`traceId`/`taskId`、Provider ID、阶段、结果数量、耗时、HTTP 状态码、是否重试与稳定 `reasonCode`。

禁止记录 URL、完整查询、API Key、Cookie、磁力原文、响应正文、书签/历史/已查看内容、WebDAV 地址、密钥、完整文件路径或可恢复登录态。高频进度仅在状态变化、限频或汇总时输出；取消采用信息级或调试级，不堆叠异常噪声。验收时以合成 fixture 与手动脱敏日志检查验证这些边界。

## 测试与验收

- 注册表快照：43 个内置 Provider、ID 唯一、元数据与 manifest 完整。
- 聚合器：并发启动、渐进结果、失败隔离、45 秒超时、取消和最大结果数。
- 输入与协议：512 字符限制、scheme 白名单、5/20/10 MiB 上限、Bencode `info`、逐项容错导入、稳定 code/ID 与 Keystore fake。
- 数据与恢复：Room v1、DataStore、备份白名单、恢复幂等、旧磁力备份跳过、旧数据精确清理和 Auto Backup 排除。
- UI/生命周期：入口发现、Intent alias、可见才收集/请求、离开取消、不安全 Provider 提示与 Cloudflare WebView 限制。
- 发布：受影响模块单测、`:app:testDebugUnitTest`、功能关闭/开启两条 `:app:compileDebugKotlin`、两条 release/R8、可用设备上的 API 24/API 36 `connectedDebugAndroidTest`、依赖/资源/Manifest/日志扫描、`git diff --check` 与启用关闭 APK 差异检查。

真实联网冒烟只生成 Provider 健康报告；第三方站点暂时故障不阻塞发布，但代码异常、契约遗漏、崩溃或敏感信息泄漏不可豁免。

## 回退、取舍与开放问题

线上回退仅发布 `enableMagnetFeature=false` 的新版本，不回滚用户数据或改写历史备份。固定快照牺牲上游即时更新，换取可审计供应链和可重复测试；43 Provider 提升覆盖面，也必须以默认安全策略、逐 Provider 隔离和用户知情同意控制风险。

开放问题：后续上游安全修复的受控更新节奏、Provider 具体安全等级维护机制、真实联网健康报告的长期存储期限，需要在首个发布验收后单独确认；在此之前不得擅自升级上游或扩大备份白名单。

## 关联文档

- `docs/app_navigation_plan.md`：独立 Activity 的入口发现和旧 route 移除影响。
- `docs/mine_page_plan.md`：我的页轻量入口与关闭构建隐藏规则。
- `docs/detail_page_plan.md`：详情页候选关键词启动边界。
- `docs/download_history_plan.md`：宿主旧磁力 Tab 移除与职责边界。
- `docs/webdav_backup_plan.md`：schemaVersion 4、分类、恢复报告与 Auto Backup 排除。
- `docs/unit_test_plan.md`：固定快照、协议、安全与恢复测试矩阵。

## 变更记录

- 2026-09-06：启动任务 04D2，固定 NekoBT 独立 HTML parser 与 Nyaa/Sukebei 同引擎共享 parser 边界，明确三站独立请求、安全元数据、精确 URL、计算调度、取消和测试契约；不新增日志、持久数据、备份或 keep。原因是按固定上游快照继续分批移植，同时避免复制 Nyaa 族 DOM 解析。

- 2026-09-06：第四批复审要求恢复 Ext 详情下载按钮的整页查找范围；固定上游按钮选择器不属于详情 card，移植时收窄会把卡片外按钮的合法页面误判为无详情。补该页面形状回归，名称/其他详情字段继续读取原有 card，网络和 token 校验边界不变。

- 2026-09-06：启动第四批 Btsow、Dmhy、Ext；恢复四份测试草稿并确认缺实现 RED。三站复用共享网络、URL、磁力、日期和大小能力，解析使用可注入计算调度器，网络留调用协程；Btsow 严格校验详情路径/hash，Dmhy 保留分类及独立 latest/top 请求，Ext 静态解析 token 后分阶段请求 magnet/description，固定请求头并限制字段长度。不新增持久数据或备份字段，不新增日志，沿用聚合器低敏 reasonCode；详情 HTML 作为不可信内容等待展示层禁脚本处理。原因是逐批推进固定快照内核，注册表与 UI 尚不接入。

- 2026-09-05：封闭任务 04A2 的共享网络自动重定向边界：底层客户端统一禁用自动 HTTP/SSL 跳转，普通文本、JSON 与表单请求只显式跟随同首次 scheme、host、有效 port 的 301/302/303/307/308，固定 20 次上限、循环/非法/缺失/用户信息拒绝和 POST 方法转换；`redirectLocation` 保持只返回一次未信任 Location。原因是旧实现只校验首次 HTTPS URL，可能在 Provider 精确 host 校验之后自动访问 HTTP 或另一来源，且最终 200 会隐藏中间 3xx。回环 HTTPS/HTTP 合成测试覆盖目标请求未发出、相对/绝对同来源跳转、方法/body/header、上限、循环、状态分类和单次 Location。日志计划不新增运行时日志，沿用状态码/reasonCode 等低敏分类；禁止输出 URL、Location、query、Cookie、header/body 或异常 message。
- 2026-09-05：修复任务 04B3 的 API 24 兼容性：BlueRoms Base64 解码由 Android API 26 才提供的 `java.util.Base64` 改为 Kotlin 2.2 标准库纯 Kotlin 实现，并保留标准 Base64 末尾 padding 可选的既有行为；补带/不带 padding 等价解析与非法 UTF-8 回归。原因是宿主 `minSdk=24` 且本模块未启用 core library desugaring，原直接调用可能在 API 24/25 触发 `NoClassDefFoundError`。静态字节码检查覆盖业务 class 与实际 `kotlin.io.encoding.Base64` 实现；设备级 API 24 冒烟仍保留为发布验收项。本修复不新增日志，既有调度器日志已覆盖 Provider 成败，解码器继续只在内存处理 Base64/magnet，禁止将正文写入日志。
- 2026-09-02：修复任务 04B3 独立审查项：BlueRoms 恢复固定上游全局 `div.row > div.col-xs-12 > div.card` 选择语义，覆盖多个同级列而不是只解析首列；BlueRoms 与 BTDigg 移除不存在于真实零结果页的人造顶层哨兵，selector 未匹配时返回空列表，并补真实形状空页回归。原因是原合成 fixture 形成自洽假阳性，可能导致 BlueRoms 只返回第一条以及两站零结果被误报解析失败。
- 2026-09-02：启动任务 04B3，按固定 SHA 移植 BlueRoms、BT4G 与 BTDigg，明确 Base64 两阶段解码、BT4G 外域 hash marker 仅离线解析且绝不联网/产出、BTDigg 结果保留合法 magnet，并锁定精确 host、Cloudflare、取消、线程与低敏日志边界；原因是第三批 Provider 必须在注册前独立验证跨页补全与不可信链接约束。
- 2026-09-02：启动任务 04B2，按固定 SHA 移植 AudioBookBay、BangumiMoe 与 BitSearch，明确两阶段详情 hash、严格 JSON/详情 ID、五页结构化并发、精确 host 与本地 NSFW 审计边界；原因是第二批 Provider 必须在接入注册表前独立锁定网络/解析线程、取消、字段容错和安全元数据契约。
- 2026-09-02：修复任务 04B1 独立审查项：把三个 Provider 的 JSON/Jsoup DOM 与逐条领域映射恢复到可注入的 `Dispatchers.Default`，将 AniRena DOM draft 与重定向网络解耦，并以公共协作遍历锁定取消；拆出 AniLibria URL/JSON parser；补齐 ISO 1..9 位小数秒与偏移、非零 base32、多 `xt` 回退，以及固定快照 20 个 URL 编码 tracker 的精确契约。原因是避免在 UI/调用线程执行大响应解析、避免把网络请求错误包入计算调度器，并恢复固定上游磁力创建能力而不引入运行时远程 tracker 更新。
- 2026-09-01：启动任务 04B1，按固定 SHA 移植 AniLibria、AnimeTosho 与 AniRena，明确共用解析分层、HTTPS/host 白名单、可注入时间、`ProviderParseException` 与合成 fixture 边界；原因是首批 Provider 必须先建立可复用且可回归的站点解析安全契约，并以本地审计结论覆盖 AniRena 上源错误的 Safe 标记。
- 2026-09-01：修复任务 04A 中文注释门禁复审项：以先失败后通过的十二类外置合成 fixture 补齐普通多行参数、`for` 变量、显式构造、双命名/解构 lambda 与字符串 receiver lambda，增加调用 named argument、`when` 标签、继承续行与匿名对象反例，并把模块 `build.gradle.kts` 纳入扫描；同时补齐扫描发现的入口参数、网络 Call、协调器参数和两处 DOM 循环下标注释。原因是旧规则虽声称覆盖多行参数，实际只匹配带 `val`/`var` 的属性参数，且内嵌 fixture 曾通过跳过字符串行形成新的扫描绕过。
- 2026-09-01：收口任务 04A 独立审查：按固定上游补齐精确分类/排序、强类型 Provider ID/Type、完整 Descriptor/Torrent/详情能力与 `epochMillis` 时间契约；删除生产 HTTP 测试旁路，改用回环 HTTPS fixture，并将响应状态、限量 body、Cloudflare/重定向分类与取消保持在同一 OkHttp Call 生命周期；同时封闭日志类型并增强中文注释扫描。原因是修复契约漂移、响应头后慢 body 无法及时取消、Cloudflare 404 误分类及注释检查覆盖不足。
- 2026-08-31：启动任务 04A 共用搜索内核：补充稳定领域/Provider 协议、直接 OkHttp HTTPS/5 MiB/取消边界、低敏结构化日志、并发聚合器和 Hilt 提供；原因是先以可测试的共用契约收敛后续 43 个 Provider 的网络、安全和取消语义，且本子批次明确不接入真实 Provider 与持久化/UI 范围。
- 2026-08-31：任务 04A 并发与网络边界复核改为结构化两阶段 Provider 启动/结果处理，移除会抢先于 45 秒总任务上限的 OkHttp `callTimeout`，区分普通 403 与带 Cloudflare 特征头的挑战，并以功能内 Hilt 限定符隔离专用 `OkHttpClient`；原因是修复快源在慢源实际进入搜索前取消它导致 Flow 测试泄漏的问题、消除宿主启用构建的重复网络绑定，同时用 gzip、底层 Call 取消、超时和低敏状态码回归锁定真实安全边界。
- 2026-08-31：完成任务 3 的固定上游供应链与资源基线：新增 MIT/NOTICE、43 Provider 审计 manifest、直接依赖说明，并对完整上游资源作前缀化机械导入；原因是为后续 Provider/运行时移植冻结可回归的来源、安全和资源命名契约，同时避免过早锁定依赖验证元数据。
- 2026-08-31：任务 3 审查修正：移除 `1337x` 来源于致谢注释的 `github.com` 域名，恢复 AGP 固定 `resources.properties` 文件名，并以独立 manifest/资源 inventory 快照和受控变异锁定完整来源事实；同时补全 JUnit 与 kotlinx.serialization 的 test-only 许可证来源，原因是格式自洽测试不足以阻止供应链事实漂移。
- 2026-08-31：任务 2 审查修正改为宿主渲染 slot 复用 `ListEntryCard`，并补齐启动来源精确 code、未知回退以及备份计数/分类纯映射测试；原因是空壳也必须保持宿主视觉、模块依赖和稳定协议可回归验证。
- 2026-08-30：重写为 TorrentSearch 固定快照完整接入主方案，并明确废弃旧 Academic Torrents 单源磁力方案；原因是项目已确认以独立 Activity、43 Provider、安全边界和 schemaVersion 4 完整替换旧模块。
- 2026-08-31：记录任务 2 的实际空壳边界、双构建路径、系统 Intent alias、宿主入口与下载历史迁出结果；原因是旧模块删除和新稳定契约已经落地，需要让方案与当前代码保持一致。
- 2026-09-06：完成 04A2 重定向修复复审；循环身份改为方法与 URL，允许同地址 POST→303→GET，并保留同来源、20 次上限、取消与响应关闭边界。重新验证 154 项模块单测零失败、模块编译、中文注释扫描及功能开启 App 编译通过；未新增持久数据、日志或反射契约，设备与 R8 仍按发布阶段验收。

### 任务 04C2：Eztv、FileMood、InternetArchive（2026-09-06）

状态：已完成。此状态仅指本批实现与本地验证，待根代理独立复审后纳入提交。固定上游提交与 manifest SHA 逐文件核对通过；只移植三站 search/details，不注册来源、不接 UI 或持久化。请求编排与 HTML/JSON parser 分离；网络留调用协程，解析和领域映射进入可注入且默认 Default 的计算调度器。复用公共 HTTPS 精确 host policy、查询清洗、hash/magnet/tracker、日期/字节格式化与协作取消。

Eztv 保留搜索表格丢弃前两行及固定 `Cookie: layout=def_wlinks`，该 Cookie 只选列表样式，不是登录态。共享 getText 增加默认空 headers，并复用 getJson 的固定头构建模式，不改变状态、重定向、5 MiB 与取消边界。详情 Filesize/Released 读取标签后 TextNode，日期去序数后使用 UTC。FileMood 保留标题限制 `in:title`，修复 seeders/peers 拆分不足两个字段崩溃；详情 hash 公共规范化并用固定 tracker 构造 magnet。

InternetArchive 按本地 manifest 为 NSFW、默认关闭。搜索保留固定字段、分页和分类映射，JSON response.docs 必须为数组，合法空数组返回空；坏条目跳过、坏顶层抛无正文 ProviderParseException。identifier 限制为非空单路径段（最多 512），拒绝点段、斜杠、反斜杠与控制符；详情只接受精确 `/details/{identifier}` 且无 query/fragment，元数据 identifier 必须匹配请求。下载文件名单独编码且拒绝空/点段。files 的非法首条不能挡住后续合法 torrent。大小保持 Long，epoch 秒转毫秒复用溢出保护；有意修复上游 `metadata.metadata` 分类字段笔误，读取 `metadata.mediatype`，未知类型 OTHER、缺失可空。

日志与诊断计划：本批不新增逐站日志，现有协调器开始/完成/失败及低敏 reasonCode 已覆盖；parser 不输出 HTML/JSON、URL、Cookie、magnet、查询或异常 message，避免隐私与噪声。描述保持不可信 HTML/文本，后续统一禁脚本展示。三站产出链接均受同站 HTTPS 约束，可选非法字段降级为空，必填非法条目跳过；HTML 零结果沿用上游 selector 的空集合语义，不发明哨兵。

测试：选择性 TDD 覆盖三站正常、空、缺字段、异常结构、请求与产出 URL、编码、元数据、线程边界，以及固定 Cookie、序数日期、人数非标准字段、IA 分类笔误/标识匹配/坏文件候选/溢出。使用合成离线 fixture；迭代定向运行，最后全模块一次并运行模块与开启 App 编译、中文注释扫描、git diff --check。没有持久数据，备份无需变动；显式 JSON key 与纯编译期对象无需 keep，设备/API24、真实站点和 release/R8 留发布验证。

变更记录：2026-09-06，开始 04C2 移植，记录固定上源适配与 FileMood/IA 已知错误修复，防止机械移植保留协议缺陷。

04C2 路径补充（2026-09-06）：复审确认 FileMood 不应从 URL query/fragment 尾部取 hash；改为 HttpUrl 最后路径文件名必须 `.html` 后缀，从最后连字符后读取 hash，存在 query/fragment 的候选直接跳过。IA 详情额外校验归一化前恰好两段和默认 HTTPS 443 端口，避免点段折叠或非标准端口改变元数据目标。这两项先跑 2 tests / 2 failures 的真实 RED 再修复。

04C2 验证记录（2026-09-06）：最终全模块 225 tests / 28 suites，0 failures/errors/skipped；模块 Kotlin、中文注释门禁与开启功能的 App Kotlin 编译通过（BUILD SUCCESSFUL in 1m 3s）。仅运行合成离线测试，真实站点、设备和 release/R8 未验证。

### 任务 04C3：Knaben、LimeTorrents、LinuxTracker（2026-09-06）

状态：已完成。固定上游提交与 manifest SHA 已逐文件核对；本批只移植三站的 search/latest/top 与站点已有 details 能力，不接注册表、UI、Room 或备份。每站入口只编排网络，具名 URL/category/parser 协作者负责结构化请求、静态响应解析和领域映射；网络留在调用协程，JSON/DOM、日期、hash 和逐条映射进入可注入且默认 `Dispatchers.Default` 的计算调度器。复用共享网络客户端、查询清洗、精确 host URL policy、hash/magnet、UTC 日期、大小、无符号人数、协作取消及测试 fake。

Knaben 按本地安全审计标记为 NSFW 且默认关闭，通过 `https://api.knaben.org/v1` 提交结构化 JSON；search 使用清洗后的 query 和 seeders 排序，latest/top 省略 query 并分别按 date/seeders 排序。ALL 省略 categories，其余九类使用固定百万级 ID。`hits` 必须为数组；合法空数组返回空，坏条目跳过，顶层结构异常抛无正文 `ProviderParseException`。名称与合法 magnet 必填，Long 字节值格式化，ISO UTC、seeders/peers 和最小有效 categoryId 安全降级；可选 details 只允许 `knaben.org`、`api.knaben.org` 的 HTTPS 默认端口且无用户信息，非法链接置空而不丢合法条目。

LimeTorrents 按本地审计为 UNSAFE 且默认关闭。search 通过结构化 path segment 编码查询，BOOKS/PORN/ALL 回退 `all`；latest/top 对不支持分类直接返回空且不联网，其余分类保留固定上源大小写路径。结果页只解析 `.table2 > tbody > tr`，名称与精确 `itorrents.net` marker 中的合法 hash 必填；marker 允许输入 HTTP/HTTPS 默认端口、无用户信息且路径严格为 `/torrent/{hash}.torrent`，但只离线提取并重建无 query 的 `https://itorrents.net/torrent/{hash}.torrent`，绝不访问 HTTP。详情 host 只允许两个站点域名的 HTTPS 默认端口；名称、匹配 hash 和合法 magnet 必填，可选下载链接按同一 marker 规则降级。日期/分类字段缺少分隔符时保持可选空值，分类先 trim 后映射，相对日期使用注入时钟。

LinuxTracker 按本地审计为 SAFE 且默认关闭，固定分类为 APPS。search/latest 使用结构化 query 参数请求同一列表入口；top 每次独立发起一次 latest 形状请求，不共享缓存。列表完整保留上游 `table.lista` 与带 title 的详情 anchor 选择器，避免嵌套子表重复；名称和合法 magnet 必填，日期按 UTC `dd/MM/yyyy`，可选详情与下载只允许 `linuxtracker.org` HTTPS 默认端口。详情读取 itemprop 名称/描述与标签相邻表格值，seed/leech 字段缺分隔符或为空时只降级可选人数；描述继续作为不可信 HTML，等待展示层禁脚本。

日志与诊断计划：本批不新增逐站运行时日志，现有协调器的 started/results/failed/completed 与低敏 Provider ID、reasonCode、数量和耗时已覆盖诊断。Provider 和 parser 禁止输出 URL、query、JSON body、HTML、magnet、Cookie、响应正文或异常 message；所有响应只在内存处理。描述保持不可信内容，测试 fixture 使用合成数据。没有新增持久数据、备份字段、反射或序列化模型，因此备份与 keep/R8 规则不变。

测试：采用选择性 TDD，先运行可编译失败契约，再实现生产代码。每站覆盖正常、真实形状空、缺字段、结构异常、查询编码、请求参数、恶意输入和产出 URL；额外锁定 Knaben 精确 JSON 与分类边界、Lime 全类别路径/不联网/marker 升级与 hash 一致性、Linux 嵌套表/日期/人数及 latest/top 独立请求。复用单线程 dispatcher 与响应后取消模式。最终运行 `:feature:torrent-search:testDebugUnitTest`、`:feature:torrent-search:compileDebugKotlin`、`:feature:torrent-search:checkTorrentSearchChineseComments`、开启功能的 `:app:compileDebugKotlin` 及 `git diff --check`；设备、真实联网与 release/R8 留发布阶段。

变更记录：2026-09-06，启动 04C3 固定快照移植；明确三站精确 URL、解析线程、单条容错、Lime marker 离线 HTTPS 重建和 Linux latest/top 独立请求边界，避免机械移植保留明文链接、越界拆分与可选字段扩大失败面。

04C3 验证记录（2026-09-06）：首轮可编译空骨架上的 15 个行为测试有 13 个按请求、解析、URL 和元数据契约真实失败；实现后补充 Knaben 严格数字类型回归，先得到 1 test / 1 failure 再拒绝字符串数字与 UInt 溢出。最终全模块 245 tests / 30 suites，0 failures/errors/skipped；模块 Kotlin、中文注释门禁及开启功能的 App Kotlin 编译通过（BUILD SUCCESSFUL in 41s），`git diff --check` 无错误。仅运行合成离线测试；真实站点、设备和 release/R8 未验证，按发布阶段计划处理。

### 任务 04D1：MegaPeer、Mikan、MyPornClub（2026-09-06）

状态：已完成。固定上游提交及三份 Provider SHA 已核对；本批只移植 MegaPeer、Mikan、MyPornClub 的既有搜索、详情、最新和热门能力，不注册来源，不接 UI、Room 或备份。入口类只编排网络；URL builder/policy、无网络 HTML parser、站点字段转换器各自保持单一职责。网络留在调用协程，DOM 与领域映射进入可注入且默认 `Dispatchers.Default` 的计算调度器。

MegaPeer 固定 `megapeer.vip` HTTPS 默认端口，按 `browse.php` 的 search/age/cat/stype/sort/ascdesc 参数与七类 ID 构造请求。列表先在计算调度器生成名称、同站详情地址、大小、人数、日期和文件链接 draft，缺名称或合法详情地址的条目在联网前跳过；入口再用结构化协程并发补详情，任一真实网络失败使 Provider 整体失败，父取消传播至全部子任务，最终在计算调度器按列表原序合并。列表只保留详情解析出的 infoHash，不复制 magnet，详情接口返回合法 magnet/tracker。俄文短月由 feature-local 纯转换器处理，`Мая` 归一为 `Май`；两位年份显式解释为 2000..2099，再复用公共 `d MMM yyyy` 严格 UTC 解析，避免 `SimpleDateFormat("yy")` 的运行年份滑动窗口。测试锁定 00、69、99 与 2000/2096 闰日和非法闰日。详情先提取同站 HTTPS 海报/截图，再移除首图和截图折叠块，避免描述重复；可选媒体或文件 URL 非法只丢字段。

Mikan 固定 `mikanani.me` HTTPS 默认端口和 ANIME 分类，通过 `HttpUrl` 编码 `/Home/Search?searchstr=`。列表名称与合法 magnet 必填，大小复用公共展示规范化器，日期按 `yyyy/MM/dd HH:mm` 严格 UTC；详情与 `/Download/` 文件链接只允许同站。详情读取标题、中文 `文件大小：`/`发布日期：` 标签和描述 HTML，标签内容去前缀后完整 trim；缺失可选字段不影响详情，描述继续作为不可信 HTML，不执行脚本。

MyPornClub 按本地审计标记 NSFW 且默认关闭，固定 `myporn.club` HTTPS 默认端口和 PORN 分类。搜索仅对清洗后的 query 保留上游字面 `%20` 到 `-` 的转换，再作为一个 path segment 编码；普通空格仍由 `HttpUrl` 编为 `%20`，该规则不推广到其他路径。latest `/ts/latest/alltime` 与 top `/ts/hits/alltime` 每次独立请求。列表和 MegaPeer 一样先形成无网络 draft 再结构化并发详情，列表字段优先，hash/magnet/file 由详情补全并保持原序。详情名称取 `#` 前文本，hash 公共规范化；缺 magnet 时复用公共固定 20 tracker 生成，存在 magnet 时必须合法且 hash 一致。相对时间使用注入 clock，大小使用 `Locale.ROOT` 大写；可选文件链接非法只丢字段。测试和 fixture 仅用中性合成标题，不访问真实成人正文或图片。

日志与诊断计划：本批不新增逐站或逐详情日志，复用 04A 协调器的低敏 Provider ID、阶段、数量、耗时和 reasonCode。Provider/parser 禁止输出 URL、query、magnet、日期原文、响应 HTML、正文、Cookie 或异常 message；响应仅在内存处理。详情 fan-out 的取消和网络失败由既有聚合器分类，避免高频逐项日志和敏感内容泄漏。没有新增持久数据、外部序列化/反射模型或数据库字段，因此备份和 keep/R8 无需调整。

测试：采用选择性 TDD。三站覆盖正常、真实形状空、缺字段、非法字段、查询编码、分类、metadata 和恶意输入/输出 URL；MegaPeer 额外覆盖俄文十二月、`Мая`、短年边界、闰日、描述媒体移除；Mikan 覆盖中文标签空白、大小和严格日期；MyPornClub 覆盖字面 `%20` 与普通空格、缺 magnet 生成、magnet/hash 冲突、注入时钟及 latest/top 独立请求。MegaPeer/MyPornClub 还覆盖详情并发机会、原序合并、缺必填不联网、网络失败整体传播和父取消。复用既有 5 MiB 上限测试，不复制大型 fixture。最终运行模块全量单测、模块编译、中文注释门禁、开启功能 App 编译及 `git diff --check`；设备、API 24、真实站点与 release/R8 留发布阶段。

变更记录：2026-09-06，启动 04D1 固定快照移植；批准 MegaPeer 两位年份固定为 2000..2099 并保留公共日期 parser 既有行为，明确两站结构化详情并发、严格站点 URL、取消/失败契约和中性离线 fixture，避免 parser 持有网络或测试随系统年份漂移。

04D1 验证记录（2026-09-06）：在可编译空外壳上运行 12 项 Provider 行为测试，得到 10 项按请求、解析、URL、日期和磁力契约真实失败；实现后新增 4 项 dispatcher/并发/失败/取消测试。最终模块 261 tests / 32 suites，0 failures/errors/skipped；模块 Kotlin、中文注释门禁与开启功能 App Kotlin 编译通过（BUILD SUCCESSFUL in 38s），`git diff --check` 无错误。仅使用中性合成离线 fixture；真实站点、设备/API 24 和 release/R8 未验证，按发布阶段计划处理。后续 Rutor/XXXTracker 出现真实相同俄文短月协议时，由 04E2 把 feature-local 日期规则迁到 common 并保留 MegaPeer 回归，本批不提前扩大共享契约。

04D1 独立复审补测（2026-09-06）：新增 8 项边界测试，覆盖 MegaPeer 十二个俄文短月与 `Мая`、全部请求分类、跨站或异常端口 poster/截图/文件降级，以及 Mikan/MyPornClub 的空页面、selector 漂移、必填缺失、详情请求阻断和恶意可选产出链接；首次定向执行即 20 tests / 0 failures，因此属于既有行为覆盖补全，不记作新的 RED。原第七批测试的压缩分号同时改为标准多行结构，便于逐条审查。

04D1 独立复审最终验证（2026-09-06）：完整命令首次由中文注释门禁发现两处测试 lambda 参数缺贴近注释，补齐后重跑通过；最终全模块 269 tests / 33 suites，0 failures/errors/skipped，模块 Kotlin、中文注释门禁与开启功能 App Kotlin 编译通过（BUILD SUCCESSFUL in 38s），`git diff --check` 无错误，仅有换行符提示。未运行真实联网、设备/API 24 或 release/R8。

### 任务 04E1：TorrentsCSV、ThePirateBay、Yts（2026-09-06）

状态：已完成。固定上游提交与 manifest SHA 已逐文件核对；本批只移植三个 JSON Provider 的既有能力，不接注册表、UI、Room、备份或其他来源。Provider 入口只编排网络，站点 URL builder、严格详情 token、category mapper 和显式 JSON key parser 分别放在具名文件；网络留在调用协程，JSON tree、逐条校验与领域映射进入可注入且默认 `Dispatchers.Default` 的计算调度器。

TorrentsCSV 保留 SAFE、默认启用、OTHER 分类与仅 search 能力。请求固定为 `https://torrents-csv.com/service/search?q=...`，查询先删除控制字符再交给 `HttpUrl` 编码。顶层必须是对象且 `torrents` 必须是数组；合法空数组返回空，非对象条目、空名称或非法 hash 跳过。大小全程使用 Long 格式化；seeders、leechers 与 epoch 秒按可选字段安全降级。列表仅输出上源提供的 infoHash 与统计字段，不合成 magnet、详情页或文件链接。

ThePirateBay 按本地审计固定为 UNSAFE 且默认关闭；展示页为 `thepiratebay.org`，备用页面 host 保留 `knaben.xyz`，所有搜索、latest、top 与 details API 请求只访问 `apibay.org`。search/category、recent 与 48h 预编译路径完整保留固定上源映射。详情 token 只接受 `https://thepiratebay.org/description.php`、唯一有界正整数 `id`、默认 443、无 userinfo、无额外 query/fragment；响应存在 id 时必须与请求一致。结果 JSON 顶层必须为数组，上源空结果 sentinel 为空，坏条目逐项跳过；详情名称与合法 hash 必填，公共 magnet 由 hash 构造，描述保留为不可信文本。

Yts 保留 SAFE、默认启用、MOVIES 分类，API 仅访问 `movies-api.accel.li/api/v2`，展示与可选媒体只允许 `yts.bz`。search 固定 `limit=50`，latest 与 top 每次独立请求 list API。结果按电影和 torrent 的原始顺序展开；正整数 movie ID、非空电影名、合法 hash 与合法页面 token 为必填，坏电影或坏 torrent 不阻断后续合法项。`movie_count=0` 且省略 movies 是合法空，其余缺失或错误顶层结构抛无正文 `ProviderParseException`。详情 token 只接受 `/movies/{slug}` 及唯一 `movieid`、`infohash` 参数；详情响应 movie ID 与目标 hash 必须匹配。海报、截图和 torrent 文件 URL 只保留精确白名单 HTTPS 默认端口且无 userinfo 的值，非法可选媒体置空而不丢详情，入口不预取媒体或 torrent 文件。

日志与诊断计划：本批不新增逐站或逐条日志，沿用协调器的低敏 Provider ID、阶段、结果数、耗时和 reasonCode。Provider/parser 禁止输出 query、URL、movie/id、hash、magnet、响应 JSON、描述、媒体地址、header 或异常 message；所有响应只在内存处理。没有新增持久数据、外部序列化/反射模型或数据库字段，因此备份与 keep/R8 不变。

测试：采用选择性 TDD，先在可编译 stub 上运行行为测试获得断言层面的真实 RED，再实现生产逻辑。三站覆盖 descriptor、请求编码、能力、正常/空/坏顶层/坏单条、Long/UInt/epoch 边界、精确 host 与恶意 URL、详情身份、计算 dispatcher 和取消；Yts 额外覆盖多电影多 torrent 顺序、严格 token 往返、合法零电影、独立 top/latest 与恶意媒体降级。全部 fixture 为中性合成 JSON，不访问真实网络。最终运行模块全量单测、模块编译、中文注释门禁、开启功能 App 编译、JUnit XML 汇总和 `git diff --check`；设备、真实站点与 release/R8 留任务 8。

变更记录：2026-09-06，启动 04E1 固定快照移植；明确三个 JSON 协议的严格顶层/逐项容错、TPB/Yts 页面 token 与 API host 隔离、Yts 逐电影展开和媒体 URL 降级边界，避免机械移植上源的字符串拼接、强制数字转换与宽松 URL 解析。

04E1 验证记录（2026-09-06）：可编译 stub 上定向运行 21 项行为测试，得到 18 项按请求、解析、严格 URL/token、数值边界、dispatcher 和取消契约的真实失败；实现后相同 21 项全部 GREEN。最终完整验证为 318 tests / 41 suites，0 failures/errors/skipped；模块 Kotlin、中文注释门禁与开启功能的 App Kotlin 编译通过（BUILD SUCCESSFUL in 31s），`git diff --check` 无错误，仅有工作树换行符提示。全部测试使用中性合成 JSON 和 fake 网络；真实站点、设备/API 24 与 release/R8 未验证，按任务 8 发布阶段处理。

04E1 独立复审 round1（2026-09-06）：TPB 详情响应的 `id` 校验改为先判断 key 是否存在；完全缺键继续兼容固定源，一旦存在则必须是与请求一致的规范正整数字符串，数字、null、Boolean、对象、数组、空白、负数、零、前导零、溢出或冲突值均返回空详情。该修复不放宽其他字符串数字字段。公开 Provider 入口回归先得到 1 test / 1 failure，再完成定向 GREEN；同时补齐 TPB/Yts 详情网络 caller 与注入解析 dispatcher，以及 TPB search/details 的 Long 大小、UInt 最大值/越界/负数/非法文本和 epoch 溢出覆盖。

04E1 round1 验证记录（2026-09-06）：最终新代码完整组合验证通过，JUnit XML 汇总为 322 tests / 41 suites，0 failures/errors/skipped；模块测试、模块 Kotlin 编译、中文注释门禁与开启功能的 App Kotlin 编译均通过（BUILD SUCCESSFUL in 22s）。限定未暂存差异只包含上述 parser、两份测试、主方案和 NOTICE；`git diff --check` 无错误，仅有 LF/CRLF 工作树提示。

### 任务 04E2：Rutor、XXXTracker、TorrentKitty（2026-09-06）

状态：已完成。固定上游提交 `ba82e889637027f7bed82715ec4d2106cbce5a28` 与 manifest 中三份 SHA 已逐文件核对；本批只移植 Rutor、XXXTracker、TorrentKitty 的既有搜索、详情、最新和热门能力，不接注册表、UI、Room 或备份。每站入口只编排调用协程中的网络请求；URL builder/policy、结果 parser、详情 parser 和 Rutor category mapper分别承担结构化请求、安全边界、静态 DOM 解析与领域映射，解析使用可注入且默认 `Dispatchers.Default` 的计算调度器。

Rutor 保留 SAFE、默认关闭和 search/details/latest/top。搜索路径固定为 `/search/0/{category}/010/2/{query}`，latest 为 `/browse/0/{category}/0/0`，top 固定 `/top`；类别映射完整保留 0/10/9/11/8/1/2/3/4，未知分类回退 0。列表严格读取 file/magnet/name 三个 anchor 的固定顺序，ALL 结果 category 为空，其他分类保留调用范围。详情按俄文 header 读取，完整移除日期括号后缀后严格解析 `dd-MM-yyyy H:m:s`；类别从已解析 `HttpUrl` 的 path segment 映射，截图 textarea 使用真实 page URL 作为 base 二次静态解析，再按同站媒体策略过滤。海报只选择首个直属 `img`，清理时也只移除该节点，再移除全部 `div.hidewrap`，其余嵌套正文图片保留为不可信 HTML；这是对固定上源“直属选择、任意后代删除”不一致的本地修复。

XXXTracker 按本地审计固定为 NSFW、默认关闭、仅 PORN，保留 search/details/latest/top；search 使用结构化 `/b.php?search=...`，latest `/b.php`，top `/top`。列表独立解析 magnet/file/name 的相反 anchor 顺序，不能复用 Rutor DOM parser。详情完整保留 `nth-last-child` 定位和严格 `dd-MM-yyyy`，坏日期、人数或选填 URL 独立降级；描述只删除前三个直属元素，嵌套正文继续保留。相对 poster 和文件地址使用真实详情页作为 base，并经过 `xxxtor.com` 精确 HTTPS 默认端口、无 userinfo 策略。

TorrentKitty 保留 SAFE、默认关闭、仅 OTHER 输入分类和 search/details。搜索固定 `/search/{query}`；列表按 `table#archiveResult` 丢弃首行，`td.action` 第一/第二/第三 anchor 分别是详情、magnet/hash 和文件。为保持固定上源契约，列表只输出合法 infoHash，不输出 magnet 或 category；详情按 `h2`、`detailSummary` 第四/五行和 `p.action` 第二/第一 anchor 解析，使用真实 page URL 修复相对文件地址。两处大小均以 `Locale.ROOT` 大写，日期严格 `yyyy-MM-dd`，名称或 hash 缺失跳过，其他坏字段独立降级。

三站复用 `BuiltinProviderUrlPolicy`、`sanitizeProviderQuery`、`MagnetInfoHashParser`、`ProviderDateParser`、`mapNotNullCancellable`、共享网络客户端、5 MiB 正文边界和测试 fake。MegaPeer 既有俄文十二月、`Мая`、三字段与两位年份 2000..2099 协议与 Rutor/XXXTracker 完全相同，因此把最小规则行为等价移动到 `builtin/common/RussianProviderDateParser`，三个调用方共同依赖；站点详情的完整四位年仍由各自格式调用公共 UTC parser，不扩大共享能力。三站各自的 results/details parser 因 selector 顺序、描述结构和类别语义不同而保持独立，并按职责落在六个具名文件中，不创建跨站万能 parser。

日志与诊断计划：本批不新增逐站、逐条或媒体日志，沿用协调器的低敏 Provider ID、阶段、数量、耗时和 reasonCode。Provider/parser 禁止输出 URL、query、magnet/hash、日期原文、响应 HTML、描述、媒体地址、header、Cookie/Token 或异常 message；所有响应只在内存处理，HTML 不执行脚本。三站所有请求和产出 URL 只接受审计 host 的 HTTPS、有效端口 443 且无 userinfo；非法选填 URL 置空而不丢弃合法结果，非法详情输入在网络前拒绝。

测试：采用选择性 TDD，先建立可编译空实现并运行行为测试取得断言层面的 RED，再完成最小实现。共享日期测试覆盖十二个月、`Мая`、00/69/99、闰日、未知月、缺字段和年宽；三站覆盖全部能力与类别、正常/合法空/selector 漂移/缺必填/坏单条、查询编码、固定 anchor 或行顺序、日期与 UInt 人数边界、描述媒体提取清理、相对 URL、精确 host/userinfo/端口输入输出、计算 dispatcher 与取消。测试使用中性合成 fixture 和 fake 网络，不访问真实站点；设备/API 24、真实联网和 release/R8 留发布阶段。没有新增持久数据、外部序列化/反射模型或数据库字段，因此备份与 keep/R8 不变。

变更记录：2026-09-06，启动 04E2 固定快照移植；确认只共享俄文短日期协议，锁定三站独立 DOM 顺序、TorrentKitty 列表仅 hash 契约、Rutor 截图二次解析 base、XXXTracker 直属描述清理，以及精确 HTTPS authority、线程与取消边界。

变更记录：2026-09-07，完成 04E2 实现与离线验证。行为 RED 为 36 tests 中 29 failures，修复实现与测试夹具后扩展到 47 项新测试全绿；最终唯一一轮组合验证 `BUILD SUCCESSFUL in 24s`，JUnit XML 汇总为 369 tests / 46 suites、0 failures/errors/skipped，确认既有 322 tests / 41 suites 基线全部保留，并新增 47 tests / 5 suites。模块 Kotlin、中文注释门禁和开启磁力功能的 App Kotlin 编译通过；真实网络、设备/API 24 与 release/R8 按发布阶段计划保留。

变更记录：2026-09-07，独立复审 round1 修复海报直属元素边界：Rutor 保留嵌套正文图且仅删除实际选中的直属海报，XXXTracker 可识别其他直属元素之后的首个直属海报并继续只删除前三个直属元素。三站原合并 HTML parser 各自拆为具名 `ResultsParser` 与 `DetailsParser` 文件，不新增跨站抽象或改变 selector 契约。三项公开 Provider 回归先取得 3 failures，再全部转绿；本批定向扩展到 50 tests / 5 suites 全绿。round1 唯一一轮完整组合验证 `BUILD SUCCESSFUL in 22s`，JUnit XML 为 372 tests / 46 suites、0 failures/errors/skipped，保留 round0 的 369 项并增加 3 项回归。

### 任务 04E3：TokyoToshokan、UIndex、TorrentDatabase（2026-09-07）

状态：已完成。固定上游提交 `ba82e889637027f7bed82715ec4d2106cbce5a28` 与 manifest 中三份 SHA 已逐文件核对；本批只移植 TokyoToshokan、UIndex、TorrentDatabase 的 search/details/latest/top 能力，不接注册表、UI、Room、备份或其他来源。三个 Provider 按本地 manifest 统一标记 NSFW 且默认关闭；UIndex 与 TorrentDatabase 保留 Cloudflare 静态元数据，但本批不启动 WebView 或新增解锁请求。入口只编排调用协程中的网络，URL/category、结果 parser 与详情 parser 分别放在具名文件，DOM、日期和领域映射进入可注入且默认 `Dispatchers.Default` 的计算调度器。

TokyoToshokan 只允许 `tokyotosho.info`。search 使用 `/search.php` 的 `terms`、`type`、`searchName=true` 结构化参数，latest 使用 `/index.php` 的可选 `cat`，top 每次重新发起独立 latest 形状请求。列表以 `td.desc-top` 识别记录起始，只把紧邻且包含 `td.desc-bot` 的下一行作为元数据，避免 `zipWithNext` 重叠、广告或相邻标题串行；缺元数据或奇数尾标题仍保留合法名称与 magnet，大小、日期、人数降级为空。类别从已解析 URL 的 `cat` 查询参数映射；详情固定序号 selector 保留，hash 规范化必填，现有 magnet 必须与 hash 一致，缺 magnet 时使用公共固定 tracker 生成。torrent 文件仅接受同站 HTTPS 且 MIME 为 `application/x-bittorrent`。日期列表与详情都按 `yyyy-MM-dd HH:mm z` 严格解析 24 小时值并尊重显式时区。

UIndex 只允许 `uindex.org`，Cloudflare solver 元数据固定为 `/search.php?search=ubuntu`。search/latest/top 分别使用 `/search.php?search=&c=`、`/search.php?c=`、`/top.php?t=24h&c=`，十类输入完整映射且 BOOKS/未知回退 `0`，禁止拼出 `null`。列表只解析首个 `table.sr-table, table.top-table` 容器及固定 `sr-*` selector，名称和合法 magnet 必填，人数去除逗号后安全转 UInt，相对日期使用注入时钟。详情括号日期先裁剪空白并仅移除完整包围括号，再按 UTC `yyyy-MM-dd HH:mm:ss` 严格解析；坏可选日期独立降级。类别 `XXX` 映射 PORN、`TV` 映射 SERIES；可选海报只保留同站合法 HTTPS，描述保持不可信 HTML 且不执行脚本。

TorrentDatabase 只允许 `developify.ca`，保留 Cloudflare 元数据。search 使用 `/newest?q=&category=` 且仅在有映射时发送 category；latest/top 使用固定后缀路径，BOOKS 分别锁定 `e-books` 查询与 `newest_ebooks`/`top_seeded_ebooks`，ALL top 每次独立请求 `/newest`，未知 top 直接空返回且不联网。列表名称固定取第一格第二 anchor，其 href 必须解析为同站 HTTPS 默认端口、无 userinfo、精确 `/track/magnet/{hash}` 路径；query 不参与 hash 提取。合法 hash 先交公共 builder 生成含 20 个公共 tracker 的 magnet，再追加一次编码的 `https://developify.ca/announce`，避免已编码常量二次编码。详情名称与合法 magnet 必填；上传时间先把空格日期与 `Z`/`±HH:mm` 归一化为 ISO 后复用公共 ISO parser，失败再按无偏移 UTC 严格解析，lastChecked 保持无偏移 UTC；`E-Books` 与 `AudioBooks` 均映射 BOOKS，不虚构文件下载字段。

日志与诊断计划：本批不新增逐站或逐条日志，沿用协调器的低敏 Provider ID、阶段、结果数、耗时与 reasonCode。Provider/parser 禁止输出搜索词、URL/query、magnet/hash、日期原文、HTML、描述、海报、header、Cookie/Token 或异常 message；响应只在内存中静态解析，不执行脚本。没有新增持久数据、外部序列化/反射模型或数据库字段，因此备份与 keep/R8 不变。

测试：采用选择性 TDD，在可编译空实现上先取得行为断言 RED，再完成生产逻辑。三站覆盖 descriptor、十分类请求、正常/空/缺失/异常结构、必填坏条目、查询编码、安全输入输出 URL、计算 dispatcher 与取消；Tokyo 额外覆盖非重叠双行、奇数尾、广告、缺单个元数据、相邻标题、24 小时/显式时区和 hash 冲突；UIndex 覆盖 BOOKS 回退、sr/top 首容器、逗号人数、固定时钟与坏括号日期；TorrentDatabase 覆盖精确 hash marker、query 不冒充 hash、tracker 解码值/无双编码、BOOKS 两类路径、ALL top 独立请求、未知 top 无请求与偏移时间。fixture 使用中性合成 HTML，不访问真实站点；复用共享 5 MiB 网络上限测试。设备/API 24、真实联网与 release/R8 留发布阶段。

变更记录：2026-09-07，启动 04E3 固定快照移植；锁定 Tokyo 非重叠双行和 24 小时日期、UIndex 精确 selector 与注入时钟、TorrentDatabase 严格 marker 路径与单次 tracker 编码，并保持结果/详情 parser 独立，避免入口或万能 HTML parser 膨胀。

变更记录：2026-09-07，完成 04E3 实现与离线验证。最小可编译骨架上的行为 RED 为 36 tests / 36 failures；完成三站 URL、类别、结果 parser、详情 parser 与薄入口后，同一组 36 tests 全绿。最终唯一一轮组合验证 `BUILD SUCCESSFUL in 32s`，JUnit XML 汇总为 408 tests / 50 suites、0 failures/errors/skipped，完整保留 04E2 round1 的 372 tests / 46 suites 并新增 36 tests / 4 suites；模块 Kotlin、中文注释门禁和开启磁力功能的 App Kotlin 编译均通过。fixture 全部为中性合成 HTML；设备/API 24、真实联网与 release/R8 仍按发布阶段计划保留。

变更记录：2026-09-07，04E3 独立复审发现详情午夜测试缺口，补充通过 TokyoToshokan `getDetails` 公开入口解析 `2024-02-29 00:15 UTC` 的回归，期望 epochMillis 为 `1709165700000`。复用现有详情 fixture 和网络 fake，不改生产日期规则；本次仅补既有正确行为的测试，不另造生产故障作为 RED。TokyoToshokan 定向 11 tests 零失败、中文注释门禁通过；此前完整 408 项测试与模块/App 编译通过，本次只增一项测试，未重复无关编译或整套测试。

### 任务 04E4：OxTorrent、Torrent9、0Magnet（2026-09-08）

状态：已完成。续接已有三站草稿，仅实现本批来源与必要协作者，不接注册表、UI、数据层或备份。OxTorrent（`oxtorrent.co`）与 Torrent9（`www6.torrent9.to`）均 SAFE/default-off，支持 search/details/latest/top；0Magnet（`9mag.net`，ID `0magnet`）为 NSFW/default-off、PORN，仅 search/details。三个固定源码 SHA 与 manifest 核对一致。

复用公共网络、HTTPS 精确主机/443/no-userinfo 策略、magnet 身份、日期、大小排版、协作遍历与测试 fake。两法语站仅共享固定分类映射，结果与详情 DOM 各自独立；列表先校验详情 URL，再在调用协程中结构化并发补详情，顺序与列表一致。无效详情只跳单条，真实网络错误与父取消传播。相同的详情调度收敛为具名协作者，所有 DOM 与领域映射进入注入且默认 Default 的计算调度器，避免合并阶段回落到调用线程。

OxTorrent 保留 `/recherche/{category?}/{query}`、`/torrents/{slug}`、`/top`；Torrent9 保留 `/search_torrent/{category?}/{query}.html`、`/torrents_{slug}.html`、`/top_torrent.html`。查询按单段编码，latest 不支持类别时无请求返回空，top 忽略类别但每次独立请求。各自按固定法语标签与 sibling 结构解析，类别只来自同站精确路径。Torrent9 仅将合法法语 o 单位转换为 B，已为 B 的容量保持量级，坏值空；0Magnet 大小坏值空、日期严格 UTC，列表必须保留合法 magnet，修复固定上游遗漏。可选海报只保留同站 HTTPS，不虚构下载字段。

日志与诊断计划：沿用协调器低敏 Provider ID、阶段、数量、耗时、reasonCode；不新增逐详情或逐条日志。禁止输出搜索词、URL、magnet、正文、图片链接、异常 message。响应仅在内存静态解析。无新持久数据或反射协议，备份与 keep 无需调整；真实站点、设备/API24、release/R8 留发布阶段，不能视为已验证。

测试计划：先补公开 Provider 回归，检查正常/空/缺失/坏字段、十类别路径、合法磁力保留、恶意 authority 拒绝、海报降级、N+1 并发机会、逆序完成、单条跳过、网络失败与取消；另验证独立 mapper 的计算调度契约。现有草稿无本批 RED 证据，恢复时据实记录已有测试通过与真实缺陷失败，不把编译失败或准备材料当作 RED。完成后运行模块全量单测、模块编译、中文注释、开启功能 App 编译及 diff 检查。

测试结果：三站公开入口、fixture 解析与批处理调度共 34 项定向测试零失败；覆盖正常/空/坏字段、分类与查询编码、latest/top 能力、同站 URL、magnet、海报降级、详情并发与稳定顺序、单条跳过、网络失败、父取消及网络/解析调度边界。恢复的 24 项草稿测试首次运行有 9 项失败，根因是第二个测试 hash 误写为 42 位，不代表生产行为 RED；修正为合法 40 位后 24 项通过。其余 10 项回归落地时继承实现已满足预期，未伪造生产 RED，作为本批测试先行例外记录。

完整验证首次执行 443 项测试时发现 3 项既有供应链基线失败：Windows `core.autocrlf=true` 将同一 Git 文本检出为 CRLF，而测试直接摘要工作树字节，manifest 还固定了历史混合换行。已将 manifest、XML 与 `resources.properties` 的文本摘要统一到 LF，二进制资源仍逐字节校验；基线 8 项定向回归通过，完整 JUnit XML 汇总为 53 suites / 443 tests / 0 failures / 0 errors / 0 skipped。模块 Kotlin 编译、中文注释门禁及开启功能的 App Kotlin 编译均纳入最终组合验证；设备/API 24、真实站点联网和 release/R8 未验证，继续留发布阶段。

变更记录：2026-09-08，完成 04E4：接入 OxTorrent、Torrent9 与 0Magnet，新增中性离线 fixture 和 34 项定向回归，修正 0Magnet 列表磁力保留与 Torrent9 容量单位边界，抽取详情结构化并发协作者，并修复供应链快照的 Windows 换行可移植性；不接注册表、UI、数据层、备份或其他来源。

### 任务 04E5：SubsPlease、TheRarBg、1337x（2026-09-08）

状态：已完成。固定上游提交 `ba82e889637027f7bed82715ec4d2106cbce5a28` 与 manifest 中三份来源元数据已核对；本批只移植 SubsPlease、TheRarBg 与 1337x 的既有 search/details/latest/top 能力，不接注册表、UI、Room、备份或其他来源。SubsPlease 按本地审计为 SAFE/default-off，只支持 ANIME；TheRarBg 为 UNSAFE/default-off；1337x 为 NSFW/default-off 并保留 Cloudflare 静态元数据。入口只编排调用协程中的网络，URL/category、列表 parser 与详情 parser 按站点拆分，JSON/DOM、日期和领域映射进入可注入且默认 `Dispatchers.Default` 的计算调度器。

三站复用共享网络客户端、5 MiB 响应边界、`BuiltinProviderUrlPolicy`、`sanitizeProviderQuery`、`MagnetInfoHashParser`、`ProviderDateParser`、`TorrentFileSizeFormatter`、协作遍历、测试 fake 与 `loadDetailDraftsInSourceOrder`。TheRarBg 和 1337x 先在计算调度器解析、校验列表草稿，再在调用协程中结构化并发补详情，保持来源顺序；坏单条或空详情只跳过当前草稿，真实网络失败和父取消继续传播。两站 DOM selector、类别和日期协议不同，分别保留具名 parser，不抽取跨站万能 HTML 解析器。SubsPlease 的搜索/最新 JSON、详情页参数、show API 二次读取与 magnet `xl` 大小是站点专属契约，只在该包内拆分，不扩大公共 API。

SubsPlease 以结构化参数构造 `/api?f=search&tz=$&s=` 与 `/api/?f=latest&tz=$`；列表要求对象顶层、非空 show 名、episode、resolution 与合法 magnet，详情 URL 只保留同站 HTTPS，并显式附加唯一 `ep`/`res`。详情入口在网络前校验同站 authority 和参数，再从静态 HTML 读取 show ID、标题、描述和可选海报，调用 `/api/?f=show&tz=$&sid=` 精确匹配 episode/resolution；名称与合法 magnet 必填，torrent 文件和海报仅接受同站 HTTPS，坏日期、大小或可选媒体独立降级。

TheRarBg 保留 `/get-posts/keywords:{query}`、`/time:2D`、`/time:2D:order:-se` 和九类路径后缀；查询作为单个路径语义编码，ALL 不附分类。列表只解析 `tr.list-entry`，详情地址先校验同站 HTTPS，再并发补充 magnet；列表大小使用非负字节数格式化，epoch seconds 安全转毫秒，人数 UInt 溢出降级。详情按固定标签表读取，上传日期只接受上游 `a.m.`/`p.m.` 形状并严格 UTC 解析，缺名称或合法 magnet 返回空详情。

1337x 保留 `/search/{query}/1/`、`/category-search/{query}/{category}/1/` 与 `/top-100`；八类映射完整，BOOKS 等不支持搜索分类回退全部搜索而不崩溃。列表草稿先校验 `1337x.to` 详情 URL，再并发补详情；日期支持注入时钟下的当日时间、当年无年份和显式两位年份三种严格形状。详情名称与 magnet 必填，相对日期使用注入时钟；可选 torrent 文件只接受 `itorrents.org` HTTPS 默认端口且无 userinfo，海报只接受本站 HTTPS，描述按不可信 HTML 保留且不执行脚本。

日志与诊断计划：本批不新增逐站、逐详情或逐条日志，沿用协调器的低敏 Provider ID、阶段、结果数、耗时与 reasonCode。Provider/parser 禁止输出搜索词、URL/query、magnet/hash、剧集号、show ID、日期原文、JSON/HTML、描述、海报、下载地址、header、Cookie/Token 或异常 message；响应只在内存静态解析。三站所有请求和产出 URL 在网络或领域输出前校验审计 host、HTTPS、有效端口 443 和空 userinfo。没有新增持久数据、外部序列化/反射模型或数据库字段，因此备份与 keep/R8 不变。

测试：采用选择性 TDD，先创建公开 Provider 行为测试和最小可编译空实现，取得断言层面的 RED，再完成最小生产逻辑。本批新增 18 个测试，覆盖描述符、十分类/不支持分类、查询编码、正常解析、缺失/坏单条、恶意 authority、magnet 身份、详情二次请求、三形态日期、N+1 并发机会、逆序完成稳定顺序、父取消和计算 dispatcher；网络失败与空详情跳过继续由复用的共享详情加载器回归覆盖。fixture 使用中性合成 JSON/HTML，不访问真实站点；设备/API 24、真实联网和 release/R8 留发布阶段。最终组合验证通过，JUnit XML 汇总为 461 tests / 55 suites、0 failures/errors/skipped；模块 Kotlin、中文注释门禁及开启功能 App Kotlin 编译通过（BUILD SUCCESSFUL in 2m 3s），`git diff --check` 无错误。

变更记录：2026-09-08，启动 04E5 固定快照移植；确认复用结构化详情并发与公共安全/解析能力，同时锁定 SubsPlease 二次 show API、TheRarBg 标签日期和 1337x 三形态列表日期为站点专属职责。2026-09-08，完成三站 Provider、URL/category、列表/详情 parser、离线 fixture、行为/调度器回归与 NOTICE，同步记录最终验证结果；原因是该子批已达到独立可审查、但尚未注册到用户入口的完成边界。

### 任务 04E6：TorrentDownload、TorrentDownloads、Torrentz（2026-09-08）

状态：已完成。固定上游提交 `ba82e889637027f7bed82715ec4d2106cbce5a28` 与 manifest 中三份 CRLF 字节口径 SHA-256 已核对；本批只移植 TorrentDownload、TorrentDownloads 与 Torrentz 的既有 search/details/latest/top 能力，不接注册表、UI、Room、备份或其他来源。三站按本地审计统一为 NSFW/default-off；TorrentDownloads 保留 Cloudflare 元数据，但不在本批接 WebView 解锁流程。

三站继续复用共享网络客户端、5 MiB 响应边界、`BuiltinProviderUrlPolicy`、`sanitizeProviderQuery`、`MagnetInfoHashParser`、`ProviderDateParser`、协作遍历、测试 fake 与 `loadDetailDraftsInSourceOrder`。TorrentDownload 的列表已携带可验证 infoHash，只做单页静态解析；TorrentDownloads 与 Torrentz 先在计算调度器解析并校验列表详情 URL，再在调用协程中结构化并发补详情，保持来源顺序。坏单条或空详情只跳过当前草稿，真实网络失败和父取消继续传播。三站 DOM、分类和详情字段协议不同，分别保留具名 parser，不抽取跨站万能 HTML 解析器。

TorrentDownload 使用 `/search?q=`、`/latest`、`/top`，搜索分类按固定上游忽略；列表详情路径倒数第二段必须是合法 infoHash，详情页名称与 magnet 必填，`itorrent.net` torrent 链接只作为通过精确 HTTPS authority 校验的可选媒体。TorrentDownloads 使用 `/search/?s_cat=&search=`、`/view/today/{category}.html`、`/view/popular/{category}.html` 及无分类回退页，列表先跳过固定两行头部，再并发读取同站详情；上传与最后检查时间只接受严格 UTC `yyyy-MM-dd HH:mm:ss`。Torrentz 使用 `/search?q=&category=`、`/latest?category=`、`/trending?category=`，列表只承载同站详情草稿，详情名称与合法 magnet 必填；可选 torrent 链接只接受同站 HTTPS，日期只接受严格 UTC `M/d/yyyy`。

日志与诊断计划：本批不新增逐站、逐详情或逐条日志，沿用协调器的低敏 Provider ID、阶段、结果数、耗时与 reasonCode。Provider/parser 禁止输出搜索词、URL/query、magnet/hash、日期原文、HTML、描述、下载地址、header、Cookie/Token 或异常 message；响应只在内存静态解析。三站所有请求和产出 URL 在网络或领域输出前校验 manifest 审计 host、HTTPS、有效端口 443 和空 userinfo。没有新增持久数据、外部序列化/反射模型或数据库字段，因此备份与 keep/R8 不变。

测试：采用选择性 TDD，先写公开 Provider 行为测试并观察缺实现失败，再用最小可编译空实现取得断言层面的 RED，最后补生产逻辑。新增 10 项行为测试与 4 项调度/取消测试，覆盖描述符、本地安全审计、分类/查询编码、search/latest/top 路径、正常解析、必填字段、恶意 authority、详情文件 URL、日期、逐条详情并发机会、逆序完成稳定顺序、父取消和计算 dispatcher；fixture 使用中性合成 HTML，不访问真实站点。收尾同时把既有 BitSearch 跨站详情拒绝测试从依赖协程调度时机的固定 5 次搜索计数，收敛为取消前 1..5 次合法搜索请求且恶意详情 URL 永不联网的安全契约，生产行为未改。最终模块全量 57 个 suite、475 项测试零失败，并通过模块编译、中文注释门禁及启用功能后的 app 编译；设备/API 24、真实联网和 release/R8 留发布阶段。

变更记录：2026-09-08，启动 04E6 固定快照移植；确认三份源码按 manifest 的 CRLF 字节口径校验通过，锁定 TorrentDownload 列表 hash、TorrentDownloads 固定头部与详情日期、Torrentz 纯详情结果为站点专属职责，并沿用结构化并发、严格 URL、低敏日志和无持久化边界。2026-09-08，完成三站 Provider、URL/category、列表/详情 parser、离线 fixture、14 项行为/调度器回归与 NOTICE；同时修正一个既有并发安全测试的时序假设，原因是批次已达到独立可审查、但尚未注册到用户入口的完成边界。

### 任务 04E7：XXXClub（2026-09-12）

状态：已完成。固定上游提交 `ba82e889637027f7bed82715ec4d2106cbce5a28` 的 `XXXClub.kt` 已按 manifest 记录的 CRLF 字节口径复核 SHA-256 `71bba7a809f1d6cf2c50dcf55d7ad92774fb28df1ef19721209c7c33f51b6e7e`；本批只补齐第 43 个内置 Provider 的 search/details/latest/top 能力，不接注册表、UI、Room、备份、Torznab 或 Cloudflare WebView。安全元数据以本地 manifest 审计为准，固定为 NSFW/default-off、PORN，覆盖上游源码中的 Safe 标记。

XXXClub 复用共享网络客户端、5 MiB 响应边界、`BuiltinProviderUrlPolicy`、`sanitizeProviderQuery`、`ProviderDateParser`、`MagnetInfoHashParser`、协作遍历、测试 fake 与 `loadDetailDraftsInSourceOrder`。列表只生成名称、大小、人数、严格 UTC 日期和已校验详情 URL 草稿；详情请求在调用协程结构化并发，逆序完成后仍保持列表顺序，空详情只跳过当前条目，真实网络失败和父取消传播。DOM、固定 `dd MMM yyyy HH:mm:ss` 日期与详情字段协议属于站点专属职责，分别放入独立 URL、结果 parser、详情 parser，不与 MyPornClub 或 1337x 合并为跨站解析器。

所有搜索、浏览、详情、文件与海报地址只允许 `xxxclub.to` 的 HTTPS、有效端口 443 和空 userinfo；查询作为单个路径段编码。详情名称和合法 magnet/infoHash 必填，`Pending` 的 last-checked、坏日期、坏人数及不安全可选媒体独立降级。描述保留为不可信 HTML，仅供后续展示层执行禁脚本渲染。

日志与诊断计划：本批不新增逐站、逐详情或逐条日志，继续由协调器记录低敏 Provider ID、阶段、结果数、耗时和 reasonCode。Provider/parser 禁止输出搜索词、URL、magnet/hash、日期原文、HTML/描述、海报、下载地址、header、Cookie/Token 或异常 message；响应只在内存静态解析。没有新增持久数据、外部序列化/反射模型或数据库字段，因此备份与 keep/R8 不变。

测试：采用选择性 TDD，先以公开 Provider 行为测试固定描述符、查询编码、search/latest/top 路径、正常/坏字段、详情必填、同站媒体、恶意 authority、严格日期、结构化并发、稳定顺序、网络失败、计算 dispatcher 和父取消，再实现最小生产逻辑。fixture 使用中性合成 HTML，不访问真实站点；最终运行模块全量单测、模块编译、中文注释门禁、开启功能 App 编译与 `git diff --check`。设备/API 24、真实联网和 release/R8 仍留发布阶段。

验证结果：测试文件先因生产类型缺失产生编译 RED，补最小可编译骨架后 11 项中 10 项按请求、解析、安全、并发、dispatcher 和取消契约失败；修正一个会被空实现满足的坏输入测试后取得 11/11 行为 RED，完整实现后相同 11 项全部 GREEN。最终组合验证 `BUILD SUCCESSFUL in 26s`，JUnit XML 汇总为 486 tests / 59 suites、0 failures/errors/skipped；模块 Kotlin、中文注释门禁及启用功能后的 App Kotlin 编译均通过。首轮全量测试曾因误删既有 1337x 的 `provider/thirteen` fixture 出现 4 项失败，根因经引用与删除 diff 确认后仅恢复原 fixture，同一完整门禁复跑通过。当前主代码中 43 个 `*Provider.kt` 与 manifest 43 项数量一致；注册表仍是下一子批，不在本批伪装为已完成。

变更记录：2026-09-12，启动并完成 04E7 固定快照移植；确认 XXXClub 是 43 个 manifest 来源中唯一尚未落地的 Provider，新增独立 URL、列表/详情 parser、入口、11 项行为/调度器回归和中性离线 fixture，锁定本地 NSFW 审计、严格同站 URL、两阶段详情补全、线程/取消与低敏日志边界，避免把站点 DOM 协议错误并入 MyPornClub 或 1337x。

### 任务 04F：43 Provider 固定注册表（2026-09-12）

状态：已完成。`BuiltinProviderRegistry` 按 `upstream-manifest.json` 的固定顺序构造恰好 43 个内置 Provider，所有实例复用功能内 `TorrentSearchNetworkClient`，需要相对日期的来源共享可注入毫秒时间源；注册过程不联网、不读取设置、不创建页面状态。`TorrentSearchCoreModule` 只把只读 `List<SearchProvider>` 接入 Hilt，协调器仍由调用方显式传入启用来源快照，避免注册表承担筛选或搜索流程职责。

测试：采用 TDD，注册表测试先因生产符号缺失产生 RED；最小实现首次编译进一步暴露 1337x 与 TorrentDownload 的必需时钟契约，补为注册表统一可注入时间源后转为 GREEN。固定 ID 列表使用人工核对的 43 项字面量，能同时防止遗漏、重复与顺序漂移；最终模块全量单测、Kotlin 编译和中文注释门禁通过。注册表没有持久化、外部序列化、反射、网络调用或新增日志，因此备份、keep/R8 与日志计划不变。

变更记录：2026-09-12，完成任务 4 的最后注册缺口；原因是 43 份独立实现只有进入固定、可测试、无副作用的构造入口后，后续页面和设置层才能消费完整来源集合。

### 任务 05A：入口输入与外部链接基础安全（2026-09-12）

状态：实现中。已新增纯 Kotlin `TorrentSearchInputNormalizer`：统一删除 Unicode 控制字符、裁剪边界空白并限制为 512 个 Kotlin 字符；清洗后空白输入不得自动搜索，超限输入只保留前 512 字符用于预填并通过 `wasTruncated` 阻止自动搜索或历史写入。既有 43 Provider 查询构造复用同一底层文本清洗能力，URL 编码仍交给 OkHttp `HttpUrl`，避免复制不同的控制字符规则。

`ExternalLinkPolicy` 只允许语法完整的绝对 `http`、`https` 与 `magnet` URI 交给系统组件；脚本、文件、相对路径、空白和语法损坏输入返回 `false`，不抛出包含原文的异常。测试先因类型缺失 RED，最小实现首次运行暴露 `URI(String)` 的 `URISyntaxException` 失败契约，收窄捕获后转为 GREEN。两项能力均为无副作用纯规则，不新增运行时日志；禁止记录输入和链接原文。强制重跑组合门禁为 492 tests / 62 suites、0 failures/errors/skipped，模块 Kotlin、中文注释门禁及启用功能后的 App Kotlin 编译通过。Activity 消费与 Room/DataStore 已在任务 05B 完成；Torrent/Bencode、导入、Torznab Keystore 仍属于任务 5 后续子批，当前不得标记任务 5 完成。

变更记录：2026-09-12，启动任务 5 并完成输入与外部 scheme 基础边界；原因是先建立可独立测试且可被 Provider/Activity 共同复用的纯规则，再接入 Android Intent、持久化和页面流程。

### 任务 04A3：内置来源共同 URL authority 边界（2026-09-06）

状态：已完成。内置来源共同 `BuiltinProviderUrlPolicy` 在既有 HTTPS 与精确 host 白名单之外，统一要求解析后的有效端口为 443 且用户名、密码均为空。默认 443 与显式 `:443` 等价允许；非 443 端口、username-only、password-only、组合及编码 userinfo 均在网络前以无正文 `HttpDeniedException` 拒绝。`resolveOptionalHttpsUrl` 继续复用同一 policy 校验 base 与解析后的 candidate，覆盖网络路径、相对/绝对地址和污染 base；`null`/空白 candidate 保持原有 `null` 短路，非法非空 candidate 保持抛异常契约。

本修复复用 `HttpUrl` 解析、既有低敏异常、Eztv 真实 `getDetails` 入口和 recording fake，不新增类，不改通用 `TorrentSearchNetworkClient` 的端口规则；后者的回环 HTTPS 测试及未来显式配置来源仍可使用合法自定义端口。已审计全部 21 个共同 policy 调用方；Knaben、LimeTorrents、LinuxTracker 等 feature-local 重复 guard 暂时保留，以缩小修复范围并避免无关重构。

日志与诊断计划：不新增运行时日志，共用协调器的低敏 Provider ID 与 reasonCode 已覆盖拒绝结果；异常禁止携带 URL、host、userinfo、query、body 或底层异常 message。测试仅使用 `fixture.example`、Eztv 合成地址和现有网络 fake，不访问真实网络。没有持久字段、数据库、备份、序列化、反射或外部 ABI 变化，备份与 keep/R8 无需调整；设备/API 24、真实联网和 release/R8 仍留发布阶段。

测试与变更记录：2026-09-06，先增加共同 policy 与 Eztv 入口回归，定向执行 6 tests 得到 3 failures，分别证明直接校验、optional resolve 与真实 Provider 会放行 userinfo/8443；随后仅补共同 guard，相同 6 tests 定向 GREEN。最终唯一一轮组合验证通过，JUnit XML 汇总为 273 tests / 33 suites、0 failures/errors/skipped；模块 Kotlin、中文注释门禁及开启功能 App Kotlin 编译通过（BUILD SUCCESSFUL in 40s），`git diff --check` 无错误。仅使用合成离线测试；设备/API 24、真实联网与 release/R8 未验证，仍按发布阶段计划处理。

### 任务 05B：Intent 消费、Room v1 与 DataStore（2026-09-12）

状态：已完成。`TorrentSearchIntentResolver` 把显式 feature extra、`ACTION_SEARCH`、`ACTION_SEND` 与 `ACTION_PROCESS_TEXT` 映射为无 Android 依赖的启动请求；系统 action 只读取自己的系统 extra，SEND/PROCESS_TEXT 仅接受 `text/plain`。所有输入继续复用 512 字符规范化器，超限文本只用于预填且 `canAutoSearch = false`。`TorrentSearchActivity` 在 `onCreate` 和 `onNewIntent` 消费最新请求，当前仍是占位页，只展示安全预填结果，不自动联网、不执行搜索、不写历史；完整生命周期感知 ViewModel 与页面留任务 6。

独立 Room `torrent_search_feature.db` v1 包含 `torrent_search_bookmarks`、`torrent_search_histories`、`torrent_search_viewed` 三张表。书签和已查看使用规范化 40 位小写十六进制 `info_hash` 主键，历史使用去控制字符、trim、`Locale.ROOT` 小写后的 `normalized_query` 主键并固定最多 100 条；时间字段统一为带单位后缀的 UTC epochMillis。领域 Repository 隐藏 Room 实体/DAO，书签逐行映射时跳过损坏 Provider ID，数值快照越界降级为空，写入失败原样传播，协程取消独立记录后继续抛出。Room schema JSON 已纳入版本审查；批量恢复、high-water mark 分页和 backup dirty 触发器随任务 7 的真实备份管线一并补充，避免提前制造未使用协议。

Preferences DataStore 文件为 `datastore/torrent_search_settings.preferences_pb`，只保存 Provider 稳定 ID 集合、安全模式布尔值、排序标准 code 与排序方向 code。缺失或损坏值分别回退为空 Provider 集合、安全模式开启、做种数、降序；非法 Provider ID 逐项丢弃，不影响其余设置。Provider 本机选择和安全模式状态不进入自定义备份；任务 7 只对白名单中的非敏感设置（当前为排序）及三类用户数据实现导出、幂等恢复、计数与报告。

备份与隐私：`backup_rules.xml` 和 `data_extraction_rules.xml` 已按精确路径排除数据库与 DataStore，云备份和设备迁移都不能整文件复制查询、magnet 快照或本机安全选择。系统 Intent 解析不记录输入；Room/DataStore 写入日志只包含固定 operationCode、started/completed/cancelled/failed/fallback 状态与可选影响行数，禁止输出查询、名称、hash、magnet、URL、Provider 集合或设置值。未新增序列化/反射模型，Room 由生成代码访问，故本批不新增 keep 规则；release/R8、API 24/36 设备与真实联网仍按任务 8 执行。

测试与变更记录：2026-09-12，采用选择性 TDD 分别为 Intent resolver、三类 Repository 和 DataStore 设置仓库取得缺类型/缺实现 RED，再补最小生产实现转为定向 GREEN；覆盖 action 优先级、MIME 拒绝、超限预填、稳定 hash/查询去重、历史上限、损坏设置回退与稳定 code 写入。新增 AndroidX Room 2.8.4 与 Preferences DataStore 1.2.1，并同步直接依赖许可证清单。最终组合验证 `BUILD SUCCESSFUL in 2m 9s`，JUnit XML 汇总为 503 tests / 65 suites、0 failures/errors/skipped；模块 Kotlin、中文注释门禁、Room schema 生成、系统备份资源合并及开启功能后的 App Kotlin 编译均通过，`git diff --check` 无错误。关闭功能编译、release/R8、API 24/36 设备与真实联网按任务 8 保留。

### 任务 05C：Torrent、书签导入与 Torznab 密钥边界（2026-09-12）

状态：已完成。本子批关闭任务 5 剩余输入安全能力，不接入页面、真实 Torznab 网络请求、分享下载或备份恢复。Torrent 与书签文件均由 feature-local 的有界流读取器消费，调用方继续拥有并关闭输入流；读取器分别在 20 MiB 和 10 MiB 后立即拒绝，不能依赖不可信 `Content-Length`，也不能在异常或日志中回显文件内容、URI 或路径。

Torrent 校验器只接受完整且无尾随数据的 Bencode 顶层字典，字典键必须使用规范字节串并严格递增，整数拒绝前导零、负零和空值，递归深度固定限制为 128。顶层必须恰好包含一个 `info` 键且其值必须是字典；infoHash 对原文件中 `info` 字典的精确原始字节区间计算 BitTorrent v1 规定的 SHA-1，并输出 40 位小写十六进制，不做 decode/re-encode，避免非文本字段或编码差异改变身份。失败通过稳定 reasonCode 区分空文件、超限、结构损坏、顶层非字典、缺少/重复 `info` 和 `info` 非字典；SHA-1 仅用于协议兼容身份，不用于新的安全签名或凭据保护。

书签导入接受本仓库固定版本 1 envelope（`version`、`bookmarks`）及固定上游兼容的无版本顶层数组。新 envelope 每项使用稳定 `providerId`、category code 与 `uploadEpochMillis`；旧数组兼容上游 `id`、`providerName`、`uploadDate` 及 `Movies` 等枚举展示名，因为上游文件没有稳定 Provider ID，导入时统一映射为符合 Provider ID 协议的固定 `legacy_import`，不得从可变展示名派生身份。导入器先以单次扫描验证 JSON 语法并定位条目，递归深度限制为 64、条目数限制为 10,000、envelope key 限制为 64 字符，再逐项构造临时 JSON 对象；单条原始 JSON 超过 256 KiB 时在创建 substring/DOM 前直接计为跳过，避免把接近 10 MiB 的整棵 DOM 或单个恶意大对象与结果列表同时保留在内存。每个元素独立读取 `infoHash`、名称、Provider、大小、人数、时间、分类和可选链接；身份字段类型错误不能按缺失处理，缺失 infoHash 时才可从合法 magnet 的 btih 恢复，显式 magnet 与 infoHash 不一致则跳过该项。非法对象、坏字段、超长名称、非法 Provider ID、非法 URL 或非法数值只计入跳过数量，不阻断后续条目；同一文件按规范化 infoHash 保留首个合法条目并单列重复数量。解析结果不直接写 Room，任务 6 的导入页面再逐项交给既有 `BookmarkRepository`，因此数据库失败、用户取消和最终新增/覆盖计数仍由页面编排处理。

Torznab 将非敏感配置与 API Key 分离。`TorznabConfig` 只承载稳定配置 ID、展示名、无 userinfo/query/fragment 的 HTTP/HTTPS feed URL、分类、安全等级和是否存在密钥；配置 ID 复用 `ProviderId` 校验并强制使用 `torznab_` 前缀，避免与 43 个内置 Provider 的稳定 ID 冲突。允许局域网 HTTP 以兼容 Jackett/Prowlarr，但任务 6 发请求前仍需做网络安全确认和明文风险提示。API Key 只通过 `TorznabApiKeyStore` 访问：生产实现使用 Android Keystore 内不可导出的 AES-256 GCM 密钥，配置 ID 作为 AAD，随机 IV 与密文以版本化字符串写入独立 SharedPreferences；测试只替换 cipher 和密文介质，不向生产类加入测试清理入口。空 Key 等价删除并返回真实删除结果，过长 Key 拒绝；读取介质异常、密文损坏、Key 失效或系统恢复后不可解均被仓库边界收敛为缺失，解密失败时尝试删除对应密文，清理自身失败也不会逃逸到页面，页面后续提示用户重新输入。

备份与反混淆：书签本身仍按任务 7 的字段白名单进入自定义 schema v4；导入临时字节、解析计数和失败原因不持久化。Torznab 配置、API Key、Keystore alias、IV/密文和本机安全选择均不进入自定义备份，独立密文 SharedPreferences 同步从 Auto Backup 和设备迁移排除。新增类型仅由编译期调用，不依赖反射、类名或自动序列化，JSON 导入使用显式字段读取，因此不新增 keep/R8 规则。

日志与诊断计划：Torrent、导入和 Keystore 仅记录固定 operationCode、started/completed/cancelled/failed、输入字节数、合法/跳过/重复数量及稳定 reasonCode；禁止记录文件名、SAF URI、书签名称、infoHash、magnet、URL、API Key、密文、IV、alias、JSON/Bencode 正文或底层异常 message。纯解析器默认不主动写 Android 日志，由任务 6 的 IO 编排层在操作边界记录一次，避免逐项高频噪声；密钥仓库仅在写入、读取恢复和删除边界记录低敏状态。

测试与验证计划：选择性 TDD 先固定 20/10 MiB 边界、Bencode 正常/损坏/深度/重复键/原始 infoHash、导入 envelope/上游数组/逐项容错/去重/magnet 一致性、Torznab 配置 URL 和稳定 code、密钥密文不含明文/AAD 隔离/损坏清理/空值删除，再实现最小生产逻辑。最终运行定向 JVM 单测、模块全量 `testDebugUnitTest`、模块 Kotlin 编译、中文注释门禁、开启功能 App 编译、备份资源合并和 `git diff --check`；真实 Android Keystore、API 24/36 设备与 release/R8 留任务 8。

变更记录：2026-09-12，明确任务 5 最后子批的原始字节 infoHash、逐项导入、Torznab 非敏感配置与设备绑定密钥分层；原因是完整页面接入前必须先固定可测试的文件大小、失败契约、去重身份和凭据恢复边界。独立复审后补充 JSON 深度/条目数、256 KiB 单项、64 字符 envelope key 及逐项 DOM 边界、Torznab ID 命名空间、身份字段严格类型和密钥介质异常收敛；原因是避免大输入内存放大、内置 Provider 冲突及损坏本机状态导致页面崩溃。

验证记录：2026-09-12，最终组合门禁 `:feature:torrent-search:testDebugUnitTest`、`:feature:torrent-search:compileDebugKotlin`、`:feature:torrent-search:checkTorrentSearchChineseComments`、`:app:processDebugResources` 与开启功能后的 `:app:compileDebugKotlin` 通过；JUnit XML 汇总为 527 tests / 69 suites、0 failures/errors/skipped，`git diff --check` 无错误。真实 Android Keystore、功能关闭编译、release/R8、API 24/36 设备与真实联网按任务 8 保留。

### 任务 06A：搜索主链路与页面生命周期（2026-09-12）

状态：已完成。本切片把独立 Activity 的占位内容替换为 feature-local Compose 导航壳和 Hilt `TorrentSearchViewModel`，只完成搜索主链路及进入后续页面的稳定导航入口；Latest/Top、详情、本地数据页、Provider 管理、设置、下载/SAF、DoH、Torznab 网络和 Cloudflare WebView 分别在 06B–06E 接入，不用占位实现伪装为已完成能力。

用户体验：主页提供查询输入、分类、排序、搜索/停止操作和渐进结果列表；系统 Intent 只更新预填查询，不自动联网。首次准备向多个已启用第三方 Provider 发送查询时，先展示全局隐私披露，明确查询会发送到当前启用数量的第三方来源、确认状态只保存在本机；用户取消时不启动任何 Provider。安全模式默认开启，关闭时必须单独二次确认，并明确可能出现不安全或成人内容；取消确认保持安全模式开启。重新开启安全模式会立即取消仍可能包含不安全来源的旧搜索，把成人分类回退到全部分类，且安全模式开启期间不展示也不接受成人分类。无启用来源、空查询、运行中、部分来源失败、无结果和完成结果使用明确 UI state，不用异常 message 或响应正文作为文案。

生命周期与职责：Activity 只负责不可信 Intent 到启动快照的映射、主题容器和根页面装配；ViewModel 负责搜索状态编排，但构造和 `init` 不执行网络。Compose 通过 `collectAsStateWithLifecycle` 收集状态，并在页面进入 `STARTED` 时允许用户发起任务、离开时调用可见性入口取消搜索；分类、排序和当前 Tab 只能决定用户查看内容，不能替代页面生命周期。共享结果卡接收稳定 `TorrentSearchResultUiState` 和 callback，不直接依赖 ViewModel、NavController、Repository 或 Android Intent。

数据流：页面提交先经既有 `TorrentSearchInputNormalizer`，再根据设置快照选择启用 Provider；首次多源披露未确认时进入待确认状态，确认后才调用 `SearchCoordinator.search`。`Started/Results/Failed/Skipped/Completed` 事件统一由 ViewModel reducer 合并为不可变 UI state；新搜索和停止操作取消上一个 Job，页面不可见也取消 Job，底层结构化取消继续传到 OkHttp Call。结果按 infoHash 稳定去重，再按 `SortCriteria`/`SortOrder` 生成展示列表；查询正文、结果名称、URL 与 magnet 不进入日志。

测试与验证：选择性 TDD 先覆盖 reducer 的渐进合并、去重、失败隔离、排序、停止和完成状态，再覆盖首次多源披露与安全模式确认状态机；ViewModel 协程测试使用 fake Provider/设置仓库和虚拟调度器，证明构造、预填 Intent、取消披露及页面离开均不联网。实现后运行模块全量 JVM 单测、Kotlin 编译、中文注释门禁、资源合并及开启功能 App 编译；Compose 视觉、旋转、动态字体和 API 24/36 生命周期设备验证保留任务 8 汇总执行。

日志与诊断计划：继续复用 `SearchCoordinator` 与设置 Repository 已有的低敏 operationCode/stateCode、Provider 阶段、数量和稳定 reasonCode 日志；06A 页面层不新增运行时日志，因为提交、披露、停止与生命周期事件会高频携带用户上下文，额外记录容易形成隐私和重组噪声，改由明确 UI phase、固定错误分类和合成协程测试定位。禁止记录查询、书签名、infoHash、magnet、URL、API Key、Cookie、响应正文、Intent extra 或异常 message；纯 reducer 与 Compose 组件不主动打印日志。

变更记录：2026-09-12，完成 06A 并固定 Activity、ViewModel、共享结果组件、首次多源披露及生命周期取消边界；补充安全模式重新开启时取消旧搜索、回退成人分类，以及安全模式期间 UI/状态双层拒绝成人分类。原因是搜索网络副作用必须先具备可验证的用户授权和可见性控制，后续页面才能安全复用同一导航与状态结构。

独立复审收口：首个 DataStore 快照到达前通过 `settingsReady` 禁止搜索；披露保存提交时冻结的来源数量，启动前按最新设置、安全模式和披露快照重新收紧；前置持久化使用可取消 Job 与 generation，搜索目的地使用 `NavBackStackEntry` 生命周期，离页或新 Intent 永久废弃旧请求。安全模式开启同步 fail-closed，设置写失败收敛为固定低敏错误；`IDLE/SEARCHING/STOPPED/COMPLETED` 区分初始、运行、部分结果停止与完成空态。共享结果卡改用不携带 URL/magnet 的展示模型，06A 暂不提供无语义的默认点击。

验证记录：2026-09-12，最终组合门禁 `:feature:torrent-search:testDebugUnitTest`、`:feature:torrent-search:compileDebugKotlin`、`:feature:torrent-search:checkTorrentSearchChineseComments`、`:app:processDebugResources` 与开启功能后的 `:app:compileDebugKotlin` 通过；JUnit XML 汇总为 542 tests / 71 suites、0 failures/errors/skipped，`git diff --check` 无错误。WebDAV 流程 HTML `--check` 通过；最终独立复审无 Critical、Important 或 Minor。Compose 视觉、功能关闭编译、release/R8、API 24/36 设备和真实联网仍按任务 8 执行。

### 任务 06B：Browse、原生详情与安全富文本（2026-09-12）

状态：已完成。本切片在 06A 的 feature-local 导航根追加 Latest/Top Browse 与原生详情目的地，结果卡点击先通过既有 `ViewedTorrentRepository.markViewed(infoHash)` 异步刷新已查看时间，再把完整候选保存在 Activity 作用域内存 ViewModel；路由只携带通过 `ProviderId` 校验的稳定来源 ID，不携带 URL、磁力链接、正文或 infoHash。06E 的分享、磁力打开、Torrent 下载、SAF 与 Cloudflare WebView 仍未接入。

用户体验与复用：Browse 提供 Latest/Top 和安全模式约束下的分类选择，复用搜索设置、`ProviderSelectionPolicy`、`TorrentResultSorter` 与共享 `TorrentResultCard`；Latest 默认按日期倒序，Top 默认按做种数倒序，结果按 infoHash 保留首个来源并限制 200 条。详情展示名称、来源、分类、大小、做种/下载数、时间、上传者、infoHash、描述、海报、截图、文件与 Tracker；加载、固定低敏错误和重试均由具名页面状态表达，不展示异常 message。详情暂用 feature-local 图片组件，因为宿主 `app` 中的图片预览组件位于上层模块，反向依赖会破坏模块边界；组件只接受已验证 URL，使用仓库既有 Coil 3.4.0 依赖、固定 1200×800 请求尺寸和 120–360dp 布局边界。字段行与文件字节数统一使用中英文命名字符串资源占位符，不在 Kotlin 中拼接 UI 标点或单位。

生命周期与数据流：共享 `DestinationLifecycleEffect` 观察每个 `NavBackStackEntry`，只有目的地至少 `STARTED` 才允许 Browse 或 Details 启动网络；离页、模式切换、分类切换和候选替换都会取消旧 Job，取消继续传播到 Provider/OkHttp，重新进入时只恢复未完成加载。Browse 显式区分“首个设置快照 ready”和“后续值变化”：即使目的地先 START、首快照恰好等于内存默认值，快照到达后也立即且只加载一次；首快照先到、STOP 后到和重新进入的时序均不重复或后台加载。Browse 再以最新 DataStore 快照筛选已启用且满足安全等级的 Provider，并发调用实现 `LatestTorrentsProvider` 或 `TopTorrentsProvider` 的来源；单来源 45 秒超时并隔离失败。Details 按内存候选的精确 Provider ID 选择 `TorrentDetailsProvider`，联网前要求候选详情 URL 为允许 host 的 HTTPS 默认端口且无 user-info，返回后再次校验 Provider ID 与 infoHash 一致；失配内容不得进入 UI。详情生命周期桥接除同步 ViewModel 网络许可外，还直接返回 Compose 活跃状态；当前详情 NavBackStackEntry STOP 时正文图、海报和截图 item 立即退出子 Composition 并取消 Coil 请求，START 后才按现有状态重新创建，取消不依赖停止收集的 ViewModel StateFlow。

安全富文本与资源上限：使用既有 Jsoup 静态解析 HTML，不执行 WebView、JavaScript 或第三方 Markdown 运行时；主动丢弃 `script/style/iframe/object/embed/form/svg/math/noscript/template` 及其内容，并只额外识别最小 Markdown 链接/图片语法。点击链接仅保留规范化成功的 HTTP/HTTPS；图片仅保留详情 host、Provider `baseUrl` 或 `alternateUrlDomains` 精确命中的 HTTPS 默认端口地址，拒绝 user-info、无 host、非网络协议和超长 URL。图片使用 feature-local Coil `ImageLoader` 与专用 OkHttp 客户端，强制关闭 `followRedirects` 和 `followSslRedirects`；3xx 停在首个响应，不手工跟随，因此初始同源 HTTPS 地址不能经第二跳逃出白名单，也不修改应用全局 `ImageLoader`。原始正文最多 65,536 字符，输出纯文本最多 32,768 字符，链接最多 64 个，正文图片最多 32 个，单 URL 最多 2,048 字符；截图规范化、去重后最多 32 张，超限安全截断或丢弃。同一 `LazyColumn` 的正文图与截图 key 使用独立区段命名空间，即使规范化 URL 相同也不会发生身份碰撞。

职责、测试与备份：`ProviderSelectionPolicy` 是搜索与 Browse 共用的纯选择策略；`BrowseCoordinator` 负责能力筛选、并发、超时、去重与排序；`TorrentDetailsLoader` 负责详情 URL/能力/身份边界；`SafeRichTextParser` 负责不可执行展示模型；两个页面 ViewModel 只编排生命周期和不可变状态，Compose 组件不依赖 Repository 或具体 ViewModel。选择性 TDD 先观察 Provider 策略、Browse、详情、安全富文本 4 组缺实现/行为 RED，再观察两个生命周期 ViewModel 缺实现 RED和日志 operation 契约 RED；实现后定向测试转为 15、6 和日志契约全部 GREEN。06B 不修改 Room schema/DAO，只复用 viewed 表；已查看写入的 dirty、自定义备份 contributor 与幂等恢复仍由任务 7 实现，因此本切片不改变 WebDAV schema、备份字段白名单或流程图。

日志与诊断计划：`ProviderLogEvent` 新增封闭 `operationCode`，Search、Latest、Top、Details 仅记录 Provider ID、阶段、结果数量、耗时、固定 reasonCode、取消或重试状态；不记录查询、URL、正文、磁力链接、infoHash、文件名、Tracker、Cookie/Token 或异常 message。页面层和富文本解析器不新增日志，避免重组、用户点击和不可信正文形成隐私或噪声；生命周期取消、身份失配、超限丢弃和脚本移除以合成单测和稳定 UI phase 定位。

取舍与后续验证：06B 只为成功解析的内存候选提供原生详情，不恢复进程死亡后的敏感候选，也不从路由重新拼接 URL；进程重建会进入固定不可用态。Compose 视觉、动态字体、旋转、API 24/36 设备、真实站点和 release/R8 仍留任务 8；本切片新增模型均为编译期内部状态，不依赖反射、序列化类名或外部协议，因此不新增 keep 规则。

变更记录：2026-09-12，完成 06B 浏览与原生详情闭环，固定目的地生命周期取消、稳定 ID 路由、响应身份校验、安全富文本与同源图片预算；原因是后续外部动作必须建立在已验证的原生详情身份和不可执行展示模型上，避免敏感导航参数、跨来源图片与离页后台网络。

审查修复：2026-09-12，I1 为详情图片增加 feature-local 无自动重定向客户端，并以合成 HTTPS MockWebServer 锁定跨 host/HTTPS 降级 302 不产生第二跳；I2 为“先 START 后默认首设置快照”补确定性竞态测试，并显式拆分首次 ready 与后续值变化；I3 让 NavBackStackEntry START/STOP 直接控制图片子 Composition 创建与释放；I4 为正文图/截图 key 增加稳定区段命名空间；M1 把字段和文件大小格式迁入中英文资源占位符。JVM 环境没有 instrumentation，生命周期可验证边界由事件 reducer 和实际展示策略纯逻辑测试锁定，模块及 App Compose 编译证明接线路径；API 24/36 实机切后台/返回栈视觉与网络取消仍按任务 8 验收。

验证记录：2026-09-12，最终组合门禁 `:feature:torrent-search:testDebugUnitTest`、`:feature:torrent-search:compileDebugKotlin`、`:feature:torrent-search:checkTorrentSearchChineseComments` 与开启功能后的 `:app:compileDebugKotlin` 通过；审查修复后 JUnit XML 汇总为 574 tests / 80 suites、0 failures/errors/skipped，`git diff --check` 与 WebDAV 流程 HTML `--check` 通过。Compose 视觉、功能关闭编译、release/R8、API 24/36 设备和真实站点仍按任务 8 执行。

### 任务 06C：本地数据页、清空确认与书签 SAF 导入导出（2026-09-12）

状态：已完成。本切片在 06B 导航骨架中增加轻量本地数据主页，以及书签、搜索历史和已查看三个独立目的地；搜索页只新增本地数据入口。三个数据目的地分别使用导航作用域 ViewModel，并复用 feature-local `DestinationLifecycleEffect` 与 `collectAsStateWithLifecycle`：只有当前目的地至少 `STARTED` 才收集 Room Flow，离开后取消收集、清空确认和页面 IO。当前整表 Flow 是可编译的阶段性接入，分页 DAO 与轻量聚合计数仍由任务 7 落地，06C 不修改 Room schema。

用户体验与导航：书签页复用 `TorrentResultCard` 展示完整快照，点击仍通过 Activity 内存选择态进入 06B 原生详情；支持精确移除、带确认的清空、导入与导出。详情页通过 DAO/Repository 的精确 infoHash Flow 观察存在状态并提供保存/移除，不加载整张书签表。搜索历史支持单项删除、清空确认和点击回放；回放只在内存中设置待处理查询并返回搜索目的地，搜索页重新进入可见前台后才复用既有规范化、Provider 选择、首次多源披露与安全模式门禁提交一次，历史页不会后台联网。真正开始搜索时异步写入历史，数据库失败与网络搜索隔离。已查看页只展示 infoHash 首尾四位缩略身份与本地化时间，不提供复制、分享或其他系统动作。三类 `LazyColumn` 分别以 infoHash、规范化查询和 infoHash 为稳定 key；清空确认是只接收字符串与 callback 的 feature 共享组件。

导入导出与职责：`AndroidBookmarkDocumentGateway` 是 feature-local ContentResolver IO 边界，所有阻塞流操作固定切到 `Dispatchers.IO`；URI 只作为 `read`/`write` 一次性参数，不进入 UI state、SavedState、日志或持久层，也不调用 `takePersistableUriPermission`。Compose 分别使用 `OpenDocument` 与 `CreateDocument("application/json")`，只有选择器返回非空 URI 后才启动 ViewModel IO；首个书签快照到达前或页面 IO 运行中禁用不安全动作。导入继续复用 05C `BookmarkImportParser` 的 10 MiB、64 层、10,000 条、256 KiB 单项和旧数组/v1 包络兼容边界，再由 `BookmarkImportCoordinator` 逐项精确查重和写 Repository；单项异常继续下一项并报告导入、跳过、重复、写失败四类计数，协程取消原样传播。纯 Kotlin `BookmarkExportSerializer` 以固定字段顺序生成 `version = 1` 紧凑 JSON，完整序列化后先检查 10,000 条与最终 UTF-8 10 MiB 上限，只有成功结果的完整 bytes 才交给文档网关打开输出流；超限整体失败，不打开输出流或写出部分字节，SAF Provider 是否在用户确认文件名时预建空目标由系统实现决定。`BookmarkedTorrentMapper` 独立承担页面 `Torrent` 与书签快照转换，不把协议逻辑塞进页面或 ViewModel。

错误、日志与诊断：页面只展示 `READ_FAILED`、`WRITE_FAILED`、`IMPORT_FAILED`、`EXPORT_FAILED` 四类固定错误和低敏导入计数，不展示异常 message、URI、文件名、查询词、infoHash、标题、magnet 或 Provider 响应。书签、历史与已查看数据库写入继续复用 Repository 的固定 operation/state/count 日志；导入导出只记录 `bookmark_import`/`bookmark_export` 的 started/completed/partial/failed/cancelled 与总数。页面收集、Compose 重组、缩略格式和 mapper 不新增日志，避免高频噪声或把业务数据带入诊断；生命周期、取消、上限与失败契约由合成单元测试定位。

测试、性能与备份边界：选择性 TDD 先为 v1 serializer、精确书签观察、详情书签动作、导入协调、页面生命周期和历史回放取得缺实现或行为 RED，再转为定向 GREEN；测试同时覆盖特殊字符往返、10,000 条/10 MiB 不可截断、重复/跳过/单项写失败、取消传播、URI 不进入状态、纯 mapper、固定错误与安全 hash 缩略。06C 已经会新增或修改书签、搜索历史和已查看三类可恢复用户数据，但本切片不实现 `BackupFeatureContributor`、dirty、分页 DAO、幂等恢复或恢复报告，也不改变 WebDAV schema；这些继续由任务 7 一并完成，SAF URI 与原始导入文件永不进入备份。Compose 视觉、动态字体、旋转、API 24/36 设备、功能关闭编译与 release/R8 仍由任务 8 汇总验证；新增 serializer 使用显式 JSON key 且内部状态只靠编译期调用，不依赖反射类名，因此本切片不新增 keep 规则。

变更记录：2026-09-12，完成任务 06C 本地数据闭环；原因是 Provider/设置和系统外部副作用接入前，需要先让三类用户数据具备独立生命周期、精确身份、显式确认、有界 SAF 传输和可恢复错误契约，同时保持任务 7 备份边界不被提前实现。

验证记录：2026-09-12，最终组合门禁 `:feature:torrent-search:testDebugUnitTest`、`:feature:torrent-search:compileDebugKotlin`、`:feature:torrent-search:checkTorrentSearchChineseComments` 与开启功能后的 `:app:compileDebugKotlin` 通过（`BUILD SUCCESSFUL in 29s`）；JUnit XML 汇总为 591 tests / 85 suites、0 failures/errors/skipped，`git diff --check` 与 WebDAV 流程 HTML `--check` 通过。首次模块全量回归唯一失败为 06C 新增中英文字符串后固定资源清单 SHA 未同步，核对实际变更后只更新两份 values XML 摘要，定向与全量复跑均通过。收尾自审再为导航过渡期搜索目的地仍短暂 active 的历史回放时序取得 1 项 RED，移除请求入口的即时消费后定向与最终组合门禁转为 GREEN，确保只在搜索目的地下一次激活时提交。Compose 视觉、功能关闭编译、release/R8、API 24/36 设备和真实 SAF Provider 仍按任务 8 执行。
