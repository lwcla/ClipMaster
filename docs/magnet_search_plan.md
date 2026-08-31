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

上游代码、资源和翻译采用机械移植并保留本地修改说明。所有导入资源使用 `torrent_search_` 前缀，禁止覆盖宿主资源。移植实现不得启动 Koin，不得替换宿主 Application、全局异常处理或全局 Coil。新增依赖必须来自 version catalog，并补齐 dependency verification 与许可证/依赖清单校验。

## 实施拆分

### 任务 2 阶段实现（2026-08-31）

已删除 `:feature:magnet-api`、`:feature:magnet` 及其 Academic Torrents 单源实现，建立轻量 `:feature:torrent-search-api` 与可选 `:feature:torrent-search` 空壳。宿主继续保留 `enableMagnetFeature` 属性名：关闭时只依赖 API 和空 Hilt 集合，开启时才编入实现模块。我的页、剪贴详情和备份报告仅消费稳定入口契约；宿主导航不再注册功能内 NavHost 路由，下载历史也只保留视频与图片两类。

本阶段的 `TorrentSearchActivity` 只验证显式启动、Hilt、资源隔离和 Manifest 合并，不读取外部查询，也不移植 Provider、完整页面或数据层。`ACTION_SEARCH`、`ACTION_SEND`、`ACTION_PROCESS_TEXT` 由三个 exported alias 指向同一非 exported Activity，且均不声明 `MAIN`/`LAUNCHER`。启动来源只通过 `TorrentSearchLaunchSource.code` 传递；每个来源的精确稳定 code 和未知回退均由单测固定。我的页由功能 API 渲染 slot 提供本地化内容，宿主薄适配层复用现有 `ListEntryCard` 和主题 token；备份预检计数、恢复分类顺序及 label 由纯展示 mapper 承载并覆盖稳定映射测试。阶段空壳没有网络、文件、数据库或后台副作用，因此不新增运行时日志，避免把候选查询文本写入日志；后续输入消费、搜索、存储和备份实现继续遵循本文件的日志与脱敏计划。

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

- 2026-08-31：任务 2 审查修正改为宿主渲染 slot 复用 `ListEntryCard`，并补齐启动来源精确 code、未知回退以及备份计数/分类纯映射测试；原因是空壳也必须保持宿主视觉、模块依赖和稳定协议可回归验证。
- 2026-08-30：重写为 TorrentSearch 固定快照完整接入主方案，并明确废弃旧 Academic Torrents 单源磁力方案；原因是项目已确认以独立 Activity、43 Provider、安全边界和 schemaVersion 4 完整替换旧模块。
- 2026-08-31：记录任务 2 的实际空壳边界、双构建路径、系统 Intent alias、宿主入口与下载历史迁出结果；原因是旧模块删除和新稳定契约已经落地，需要让方案与当前代码保持一致。
