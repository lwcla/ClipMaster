状态：已完成

# 来源应用图标与分页解耦方案

## 当前状态、目标与范围

2026-10-11 用户确认按包名由 UI 加载图标。替换本轮先装配 Bitmap/主色再交付列表的实现；继续复用纯内存 Manager，不恢复旧 Store、PNG 发布或后台补图。正文、历史来源名称和安装候选先交付，冷缓存显示默认图标与主题色，图标和主色加载后同时更新。

| 入口 | 轻量数据来源 | 图标加载边界 |
| --- | --- | --- |
| 首页、折叠、搜索、回收站 | 名称装配后的 `ClipShowEntity` | 共享卡片的局部 UI 会话 |
| 搜索来源菜单 | 原有 `SourceAppCandidate` 包名/名称投影 | 菜单可见行 `AppIcon` |
| 安装应用过滤 | `InstalledAppInfo` 包名/名称/系统标记 | 共享选择行的图标 slot |
| 单条详情 | 相同名称装配契约 | 当前无图标区域，不取图 |

范围不包含清理用户文件、Room schema 或备份格式迁移、跨工作资料读取、实时安装更新、图标包或快捷方式支持。用户已明确要求提交代码并推送主分支，本轮只提交来源图标相关代码、测试及文档；保留工作区原有 WebDAV 恢复流程文档及流程图改动。

## 用户体验与数据流

1. 分页只装配历史来源名称；搜索候选和安装扫描只返回轻量文本与身份。
2. 页面提供可替换的 `AppIconLoader`；共享 UI 不直接获得 Manager、Repository 或 ViewModel。
3. `rememberAppIconEntry` 按包名和 loader 创建一份局部 `AppIconUiSession`。页面 STARTED 且实际可见时加载，离开前台取消未完成读取；首页保留组合的非当前 Pager 页用 `visibleToUser` 暂停。
4. Manager 返回同一 `AppIconEntry` 的图标与主色，卡片使用该结果绘图和边框取色，避免两份请求产生不同状态。菜单和安装行复用 `AppIcon`，纯绘制复用 `AppIconImage` 和统一默认资源。
5. 重组不重复取图；暂时后台保留已完成局部状态；条目离开组合立即关闭会话、释放引用，晚到结果不得回填。懒布局保留的少量预取/复用条目仍可能持有图片，不承诺仅可见像素数量。

未知/空包名不查询系统。失败直接使用默认，不阻断正文。失败冷却结束不自动刷新现有会话，重新组合可重试；暂停时取消的未完成会话在重新可见后重试。成功图标不实时更新，进程重启或缓存淘汰后的新会话按正常规则读取。

## Manager、缓存与失败契约

- 复用 Hilt 单例 `AppIconManager`、74 像素缩图、取色、Launcher Activity 优先与 Application 图标降级。图标为空使用项目默认资源，颜色为空使用当前主题；不复制默认 Bitmap、不主动 recycle UI 尚引用的图片。
- 复用纯 Kotlin `AppIconCache`：LRU 最多 256 个包名，失败冷却 30 秒，单调时钟；固定 32 分片锁同包去重，最多两次重型读取。容量是数量限制而非严格字节预算。
- 取消向上传播，不写负缓存；普通失败降级默认。系统内存回调清理与代次防回填保留；没有包变化广播、每包永续 StateFlow、文件或数据库图标写入。
- Manager 淘汰只移除缓存引用，不通知已完成 UI 会话，也不清除当前显示的小图；数据列表不再持有图片，离屏组合释放后引用可以被回收。
- 局部会话只持有一个可空结果：未完成为 null，失败为全空结果。序列化并发加载，关闭不可逆；取消/关闭后不可中断读取的晚到结果不发布。

## 模型、分页与生命周期边界

- `ClipShowEntity` 仅保留本次历史来源名称，删除 Bitmap/主色字段；`InstalledAppInfo`、安装原始条目和过滤候选同样删除图片/主色。Room、JSON、导航和备份协议不变，不新增 keep。
- `ClipSourceNameAssembler` 只协调名称；每代次会话保留含 null 的有界名称缓存 256 项，查询失败仍交正文。名称查询继续用既有 `SourceAppLoadGate` 和 `SourceAppNameLoadingEffect` 门控，防止隐藏页面的 cachedIn 子任务继续查库。
- 搜索来源候选直接使用既有轻量投影，不扫描全部来源图标；安装扫描只查询当前范围的标签与系统标记，图标读取由行的生命周期约束。
- 统一 `clipPagingConfig()` 保持 pageSize=20、prefetchDistance=5、关闭占位，恢复默认不丢弃已加载页；移除本轮新增的 maxSize=200。真实 Paging 已复现有限窗口丢弃前页后 `scrollToItem(0)` 只到中段；恢复默认使现有回顶语义成立。正文仍按需分页，但已加载文本可增长，后续容量优化须连同全局回顶设计及设备测量评估。
- `AppIconUiSession` 为可测试纯逻辑，Compose adapter 负责 DI 与生命周期，`AppIconImage` 纯绘制，三者职责分开；共享选择行通过 icon slot 接入，不把平台图片塞回 UI state。

## 实施任务、测试与诊断

- [x] 任务一：先验证模型不持有图像的回归失败；新增局部会话的等待/取消恢复/关闭晚到/同包共享测试，再实现状态边界。
- [x] 任务二：移除模型图片和扫描/分页取图；保留名称缓存与门控回归；共享分页配置用真实 Paging 浏览超过 200 项验证回顶索引。
- [x] 任务三：接入共享图标 adapter、卡片同源颜色、菜单和安装行；页面提供 loader，首页明确可见边界，Preview 不访问系统。
- [x] 任务四：受影响 app/general/shizuku 完整单测与 Debug 编译、静态全入口扫描、独立只读审查、同步文档。

选择性 TDD 覆盖纯状态、资源释放和回顶规则；Compose 绘制、Hilt 接线和系统外观通过编译及待验真机覆盖，不新增镜像单测。日志沿用 Manager 失败、取色与名称失败及扫描汇总；不新增每行加载/取消/重组日志，避免安装名单和剪贴内容泄漏与噪声。备份继续只保存既有元数据白名单，UI 会话、Bitmap、色值与缓存不入备份、不触发 dirty。

## Shizuku 与旧接口兼容

- Shizuku 来源解析只读采集时名称；不读取 Drawable/Bitmap、不计算图标 hash、不编码 PNG、不执行 `query_icon_state → content write → commit_icon`。正文 Provider 命令保留稳定字段，`iconHash` 传空。
- `ShizukuConnector.VERSION` 从 28 升到 29，使覆盖安装后的绑定使用新的 UserService 行为，替换仍会生成图标的旧服务。进程身份和剪贴 payload 验证、过滤、去重、通知沿用原逻辑。
- app Provider 不初始化图标写盘依赖。旧 `query_icon_state` 和 `commit_icon` 保留调用方/线程/参数验证，合法调用统一返回 `ok`、`shouldSyncIcon=false`、`memory_icons_only`、placeholder，不误报正文已保存。旧 `/icon` 写入明确拒绝；剪贴 `/clip` 写入正常。
- `ClipboardBridgeClipCommitCoordinator` 不再读取旧图标路径或检查磁盘文件，入库签名中的图标元数据固定为空。删除已退役的图标 Store、Publisher、Resolver、逐行 Provider 和专属测试，改用内存缓存/快照/兼容契约回归。

## 复用与备份决策

复用图标缩放/取色扩展、来源显示名兜底、历史名称查询、纯内容 mapper、共享卡片/菜单/安装选择行与默认资源。旧 Store/文件工具的输出契约为独立 StateFlow、文件路径和 Room 条件写入，与纯内存按需 UI 加载目标冲突，因此替换而不再扩展它们。职责拆为缓存、平台 Manager、名称会话、图标 UI 会话、生命周期 adapter、纯绘制与旧接口兼容结果，各自单一职责。

`source_apps.iconPath/iconHash/primaryColor` 以及旧 JSONL 白名单保留历史读写兼容，本轮不迁移 schema 或删除用户文件。新图标、主色与安装展示快照不进入自定义备份，不增加 dirty 或后台备份任务。系统 Auto Backup/云备份/迁移继续排除旧 `app_icons/` 和传输目录。恢复旧包后字段可以保留，但 UI 不读取它们作为图标/颜色真相。详细备份约定引用 `webdav_backup_plan.md`，不复制备份流程。

## 日志与诊断计划

| 场景 | 级别/触发时机 | 字段与约束 |
| --- | --- | --- |
| 系统取图异常/系统默认 | DEBUG，单次未命中读取失败时 | reasonCode、异常类型；不输出包名、路径或像素 |
| 真实图标无法取色 | WARN，本次取色为空时 | `color_unavailable`；不输出图片 |
| 来源名称读取失败 | WARN，单会话首次失败 | reasonCode、异常类型；正文照常交付 |
| 安装扫描 | 沿用既有 INFO/WARN 汇总 | 数量、耗时、范围；不输出安装名单 |
| 旧图标命令 | DEBUG，合法兼容命令 | `memory_icons_only`；无 IO/数据库副作用 |

命中、UI 绘制、取消、内存清理及每行默认状态不增加日志；包广播监听已删除，不新增诊断链路。禁止输出剪贴正文、完整搜索词、安装名单、Token/Cookie、完整用户路径、图标编码内容。验证方式是 JVM 合成数据回归、静态检查日志字段，设备通过 Logcat 验收；不创建本地诊断文件。

## 本轮 UI 加载验证（2026-10-11）

- 模型契约测试先失败，确认 `ClipShowEntity` 仍持图；迁移后模型契约、6 项局部图标会话与 4 项名称会话回归通过。旧安装 mapper 主色/Drawable 传递及候选传图共 3 项测试随契约退役，改由无图像模型契约及 UI 会话共享/释放回归覆盖，名称/排序/名单/取消测试保留。
- 真实生产分页配置回归先复现 `expected 0 but was 200`，恢复默认容量后浏览超过 200 项回顶测试通过。不是只断言配置常量，也不改变既有 `scrollToItem(0)` 动作。
- `.\gradlew.bat -I build\source-app-isolation.init.gradle :app:testDebugUnitTest :shizuku:testDebugUnitTest :base:general:testDebugUnitTest :app:compileDebugKotlin --no-daemon --no-watch-fs --console=plain`：BUILD SUCCESSFUL（45s）；app 266、shizuku 47、general 82，共 395 项，失败/错误均为 0，日志 `build/review-paging/ui-full-validation.log`。
- 原 general `classes.jar` 被其他进程占用，使用既有临时输出隔离脚本；不终止进程或修改构建配置。进度记录在 `build/review-paging/ui-progress.md`；实施验证阶段未自动提交，后续按用户明确提交与推送指令执行。
- `python -X utf8 tools/render_doc_flow_html.py --check docs/webdav_backup_plan.md`、`git diff --check` 及暂存区差异检查通过；本轮无 Mermaid 变化，既有 HTML 改动保留。静态扫描确认系统取图仅在 Manager，分页/候选不持图像，四类 Pager 统一使用新配置。独立只读审查无 Critical/Important/Minor；此次审查聚焦 UI 持图迁移，旧 Provider/Shizuku 兼容安全性沿用既有审查与回归，WebDAV 既有改动保留，图标主动刷新仍不在用户要求内。

## 上一轮快照实现验证（历史记录）

- [x] 任务一：缓存未定义红测后实现缓存/平台 Manager；覆盖同包共享、并发去重、失败冷却、空包名、取消、淘汰与失效中不回填。
- [x] 任务二：装配会话未定义红测后实现一次交付；迁移所有分页和单条入口，验证名称缺失与取消不阻断正文。
- [x] 任务三：迁移全部 UI、候选与安装读取；删除旧订阅/文件输出链路，旧桥接返回禁用响应。
- [x] 任务四：停止 Shizuku 取图/传图并升级服务行为版本；保留历史字段兼容。
- [x] 任务五：受影响 app/shizuku/general 完整单测、`:app:compileDebugKotlin`、差异与全入口扫描、独立只读审查及文档同步。

单元测试门禁：缓存、装配、兼容结果及安装 mapper 属于纯规则/失败契约，使用选择性 TDD；直接 Bitmap 绘制和 Android 系统接线不写镜像测试，由编译与设备验收覆盖。旧文件/补图测试随退役功能移除，基础数据/备份协议测试保留。新增 14 项回归，包括真实 cachedIn 隐藏/恢复/完成快照回放与不可中断取消。删除广播接线及无调用方的按包失效方法属于收缩入口，不新增纯规则；调整现有缓存测试，覆盖系统图标变化后缓存命中仍保持原结果、新缓存读取最新结果及内存清理中的回填保护，不另造广播注册镜像测试。

- `.\gradlew.bat :app:testDebugUnitTest :shizuku:testDebugUnitTest :base:general:testDebugUnitTest :app:compileDebugKotlin --no-daemon --no-watch-fs --console=plain`：BUILD SUCCESSFUL（1m27s）；app 260、shizuku 47、general 82，共 389 项，失败/错误均为 0，日志 `build/source-app-snapshot/full-validation.log`。
- 安装 mapper 漏主色和取消后负缓存分别由断言红测复现，修复后完整回归通过；缓存/装配/兼容/门控不存在时的红测编译错误也已确认。独立审查两项 P2 已补回归并修复，复核未发现新必修。
- 全入口扫描仅剩 Manager 访问系统图标，旧 Store、文件 Publisher、图标 Provider 同步与 UI 文件模型无残留运行引用；`git diff --check` 通过。补充完成快照回放断言后，`.\gradlew.bat :app:testDebugUnitTest --tests 'com.cla.clip.master.sourceapp.*' --no-daemon --no-watch-fs --console=plain` 再次 BUILD SUCCESSFUL（39s），12 项来源快照测试通过，日志 `build/source-app-snapshot/snapshot-final.log`。
- 移除包变更监听后，`.\gradlew.bat :app:testDebugUnitTest --tests 'com.cla.clip.master.sourceapp.*' :app:compileDebugKotlin --no-daemon --no-watch-fs --console=plain`：BUILD SUCCESSFUL（3m4s），12 项来源快照测试通过，失败/错误均为 0，日志 `build/source-app-snapshot/receiver-validation.log`；全仓引用扫描确认按包失效方法无残留。
- `python tools/render_doc_flow_html.py --check docs/webdav_backup_plan.md`：通过；本轮仅补来源图标兼容边界，不修改 Mermaid，既有 HTML 已同步，无需重新生成，原有 WebDAV 文档及 HTML 改动保留。

在 `codex/source-app-memory-snapshots` 分支内联执行；Windows 使用 PowerShell 与 `gradlew.bat`，Gradle 外部缓存目录按批准权限运行。进度写在忽略的 `build/source-app-snapshot/progress.md`；无需重复方案文档或 POSIX 脚本。

## 开放问题、待验与规则例外

- 真机冷首屏、分页 append、搜索多来源、菜单候选、更新图标后进程重启/权限拒绝和长列表内存尚需验收；此实现不承诺性能已优于旧方案。
- Android 系统资源、进程重启及 Compose 视觉没有真实设备测试；JVM 测试覆盖缓存/规则，不替代系统行为测量。
- 旧 schema/备份字段和旧文件清理作为独立迁移待办，本轮不自动删除。`SourceAppPresentationRepository` 的历史持久元数据接口仍保留兼容基础数据测试，UI 仅使用名称查询。
- 没有新外部字段、反射/序列化模型或混淆边界；本轮未运行 release/R8，已有外部协议字段/keep 规则未改，正式包仍需按发布流程验证，不能由 Debug 结果推断。
- 图标缓存容量、冷却、可见 UI 按需加载是当前保守取舍；若设备数据需要预取，再单独设计有界任务，避免扫描全部包。文本分页默认保留已加载页，未来若需限制窗口，必须连同全局回顶与反向浏览一起设计。

## 变更记录

- 2026-10-11：用户要求提交并推送主分支，核对提交范围并保留无关 WebDAV 恢复文档/HTML 改动；同一完整验证命令再次 BUILD SUCCESSFUL（2m49s），395 项单测失败/错误均为 0，日志 `build/review-paging/precommit-validation.log`。原因是提交前需确认当前源码与验证结果一致；仅补验证和提交边界记录，不改变生产代码、备份协议或运行日志。

- 2026-10-11：完成所有入口的 UI 按包名加载迁移，列表对象不持图片；395 项 JVM 单测、Debug/Hilt 编译、真实 Paging 回顶回归和差异/流程图检查通过，独立只读审查无必修项。原因是用户要求按已讨论方案实施；真机视觉/实际生命周期/内存效果与 release/R8 保留待验，备份及运行日志沿用既有边界。

- 2026-10-11：用户确认按包名 UI 加载方案，开始全入口迁移；移除列表模型图像/主色与 maxSize=200，保留名称门控，补局部会话释放和真实 Paging 回顶回归。原因是长期数据对象持图导致缓存淘汰无法释放离屏图片，有限分页窗口还改变已有回顶语义。

- 2026-10-11：根据用户关于按包名显示图标的提议，补充 UI 加载适配与纯绘制分层的草案评估，明确图标/主色同源、局部引用释放、冷缓存占位和分页容量独立决策；记录真实 Paging 复现的首页回顶回归。原因是分页对象持有 Bitmap 使缓存淘汰不能单独控制长期图像引用。本次只维护备选说明，未确认替换现行实现或修改产品代码，不新增日志/单测/备份字段。

- 2026-10-11：按用户要求移除 `AppIconManager.receiver` 和按包失效接口，资源配置变化也不清空缓存；保留有界容量与内存回调清理。图标更新不主动触发读取，进程重启后按需取得最新系统图标；调整缓存回归，12 项来源快照测试与 Debug 编译通过。原因是用户接受更新图标延后显示，包变化监听不再有业务必要。

- 2026-10-11：完成全入口纯内存快照迁移；完整 389 项 JVM 单测和 app/Hilt Debug 编译通过，独立审查发现的生命周期/取消问题均经红测修复，保留真机视觉、广播、性能及 release/R8 待验项。原因是用户已确认全入口范围且不需要发布后的图标更新。

- 2026-10-11：用户接受快照刷新边界并要求覆盖全部入口，实施统一主进程 Manager、一次装配和直接绘制；移除旧订阅/文件/传图链路，服务行为版本升到 29，保留历史字段兼容。缓存/装配/兼容红测及安装 mapper 漏色回归已确认；独立审查指出后台 cachedIn 装配及取消后负缓存问题，补页面加载门控与真实 Paging 回归后继续验证。原因是全入口一致性与页面生命周期要求。

- 2026-10-11：继续评估用户提出的“读取列表时装配图标/主色、发布后直接显示”，将草案的独立图标订阅改为分页加载阶段的不可变 UI 快照；补充冷缓存等待、逐项 map 并发限制、cachedIn 刷新语义、共享 Bitmap 引用与分页内存边界。原因是用户希望进一步删除逐行订阅和二次图标更新；本次仍只维护方案，不修改代码。

- 2026-10-11：按用户最新讨论收敛为统一 AppIconManager 管理图标/主色的纯内存方案，失败直接默认展示，不再把磁盘兜底列为推荐备选；补充单一缓存快照、可观察状态、无来源行取图、主题色兜底、现有 UI/Provider/备份兼容的迁移边界与测试。本次仅评估并更新方案，未实施代码或删除旧缓存。

- 2026-10-10：按用户建议评估取消 Shizuku 图标传输、由主进程系统取图并缓存图标/主色；记录现有可复用能力、Application/Launcher 差异、并发/生命周期/失效与容量规则、纯内存历史图标取舍及后续测试。新增方案标为草案，已有实现基线仍已完成，本次不修改产品代码或备份契约。

- 2026-10-02：按用户最新 android.svg 将所有复用同一默认资源的应用占位图标改为黑色轮廓样式；复用原资源和调用方，不新增组件/类/方法，矢量旁补中文来源与颜色说明。

- 2026-10-02：完成独立审查修复与最终验证：334 项 JVM 单测、15 项真机 Room 测试、最新源码 Kotlin 编译及差异/派生文档检查通过；方案标记已完成，设备 UI/性能与安装更新失效保留为后续验证/增强。

- 2026-10-02：执行已确认的来源订阅与分页解耦方案，新增内容 POJO、独立共享 Store/候选投影、逐项版本文件与条件写库，接入四类结果页/来源菜单，统一 Provider 发布和系统备份精确排除；记录回归证据、验证环境与未测 UI/性能边界。

- 2026-10-02：用户确认拆分来源展示订阅与结果分页，主方案改为分页去除 source_apps 依赖、名称/图标/主色按包名共享订阅、文件保存后逐项条件写库。替换此前批量及未解耦逐项备选，补全 DAO/模型/生命周期/并发边界、实施步骤和 Room 分页隔离验收；总体状态已确认，产品尚未实施。


- 2026-10-02：增加按包名单项文件发布后立即写库的备选评估；对比渐进显示、退出后缓存保留、整列表 Flow 重查和关联分页失效，补充条件更新、任务自触发去重与测试边界。原因是用户继续评估提交时机调整；未确认替换原批量草案，未修改产品代码或备份边界。

- 2026-10-02：按用户建议新增列表先展示、后台补齐文件、版本路径和批量 Room 刷新草案；评估损坏文件、部分失败、取消、并发覆盖、旧文件清理与备份边界。原因是逐行系统读取只改善即时展示，不能避免滚动重建和再次打开时重复获取。仅更新文档，状态改为草案；原三级读取实现和已通过验证作为基线保留。

- 2026-10-02：按用户要求补齐图标三级加载链路；先补回归测试并观察 3 项预期失败，再接入单包名系统图标读取。最终 Kotlin 编译与 app 248 项单测、差异检查通过，标记已完成；不扫描整表、不扩大持久化范围，设备验收待补。
