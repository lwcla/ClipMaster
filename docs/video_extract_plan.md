状态：已完成

# 视频提取功能方案

## 下载记录移除的接入边界（2026-10-26，已完成）

保留视频探测、下载详情、当前任务重试和冷启动取消；删除仅供历史页删除记录使用的单任务取消入口，不新增替代取消 UI。完整范围与验证见 [下载记录主方案](download_history_plan.md#下载媒体记录功能移除方案2026-10-26)，备份契约以 [备份主方案](webdav_backup_plan.md#下载媒体任务退出备份恢复方案2026-10-26) 为准。无新增运行时日志，保留流程继续复用现有诊断。

## 2026-10-16 修复执行记录（已完成）

用户已确认按审查顺序修复。本节作为本轮执行记录，覆盖下方审查，不另建分叉方案。使用现有工作区的新分支，保留既有版本和应用更新改动，不自动提交；测试仅在本机运行或编译，禁止对现用手机执行 connected 测试。

- [x] 1. 先补会话和加载回归：失败重试、暂停恢复、旧回调拒绝、重定向不重载、重复页面完成回调忽略。新建共享 `ProbeLoadSession` 与视频 `VideoProbeSession`，页面只编排 WebView 和状态。
- [x] 2. WebView 每个会话只加载一次；共享组件拥有释放职责。页面不可见时暂停会话并释放 WebView，返回后新建会话；成功/失败后不继续拦截。预览补全改为有界旁路，不能阻塞候选接受。
- [x] 3. 下载观察与启动分离，以 `VideoDownloadStartPolicy` 保护运行中下载/合并进度；保留显式重试。视频提交防重，并保留失败页媒体摘要。
- [x] 4. 图片会话、进度和页面连续性按图片主方案实施；清除两页不再调用的旧流程。
- [x] 5. 运行 app 单测、Kotlin 编译和仪器测试源码编译，最后独立评审；设备实测单列未验证边界。

测试先行：使用合成 URL 和纯会话/进度输入；先观察回归失败再改生产逻辑。日志仅记录 session/taskId、阶段、数量和异常类型；不新增 URL、Cookie 或用户正文输出。新增状态只存在内存，不改变数据库、备份协议或 keep 契约，因此本轮不要求 R8；图片下载/发布阶段由现有 item 状态推导。

执行裁定：本次已获用户明确实施授权，继续在当前会话实施，不重复请求设计批准；保留当前审查文档和用户未提交改动，使用独立分支而不迁移工作区。编译使用已有隔离输出脚本；Gradle 缓存访问需要沙箱外权限，已按工具流程申请。

独立复核补充：完成回调去重限定为每次主文档导航，新文档开始取消旧扫描/预览；不能把整个 WebView 的首次中转页完成当作最终文档。下载页同时读取当前任务的 WorkManager 状态，冷启动取消后遗留的 downloading/merging 显示中断提示，仅用户明确重试才重新入队；保留 App 当前不自动恢复旧视频下载的约定。新增平台观察协作者，不修改数据库/备份协议。

### 当前日志与验证边界

- 保留低频 DEBUG 任务创建/页面进入日志（taskId、sessionId、状态）与提交异常类型；识别仅记录成功事实。共享预览补写不打印预览对象，视频候选不打印 URL/Cookie。没有为高频回调逐条新增日志，避免隐私和性能噪声。
- JVM 回归验证会话重试/过期回调、重定向入口不重复加载、每文档完成去重、运行任务不归零、冷启动中断后显式重试及安全错误资源映射。Compose 生命周期测试源码已加入，运行仅限隔离设备。
- 不新增 Room 字段、协议、序列化模型或 keep 规则；备份内容无变化，无需迁移。本轮未做 release/R8；风险集中在真实 WebView/Coil 设备行为，编译和纯逻辑测试不能替代设备体验验收。

独立代码复核：首次发现的文档导航去重和冷启动中断两个 P2 已修复；定向复核确认无新增阻塞问题。

### 本机验证结果（2026-10-16）

- 命令：`.\gradlew.bat :app:testDebugUnitTest :base:general:testDebugUnitTest :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --init-script build\review-build-isolation.gradle --console=plain`，结果 BUILD SUCCESSFUL。
- app 324 条、base:general 109 条 JVM 测试通过，共 433 条；本轮新增 20 条 JVM 回归及 1 条 Compose 生命周期仪器测试。仪器测试只编译，未安装或执行设备测试。
- 重试、入口重载、阶段进度、读取错误、HTTP 取消、文档导航和冷启动中断均记录过真实断言失败后通过；没有改测试期待来掩盖原问题。未把 JVM/编译结果当作 WebView、滚动及图片缓存的实机体验验证。

## 2026-10-16 加载与显示流程审查

审查归档：以下保留修复前的源码证据与问题清单；当前行为以本文顶部修复执行记录为准。审查覆盖详情入口、视频识别页、独立下载页、共享探测 WebView、预览补全、Room 观察和 Worker 入队。没有运行设备，不把源码风险等同于已测得的卡顿、闪屏或网络请求次数。下文历史验收条目表示目标，不代表本轮已验证通过。

### 修复前的实际流程

| 阶段 | 加载与状态来源 | 前台显示与退出边界 |
| --- | --- | --- |
| 进入识别页 | 一次读取已有链接预览；`sessionId` effect 将 `Idle` 改为 `HiddenProbing`；`ProbeWebView.update` 和页面 effect 都有 `loadUrl` 入口 | 标题、已有媒体摘要、行内加载提示；无缓存时没有新封面 |
| 网页加载与识别 | `onPageFinished` 异步补写预览并尝试播放；请求拦截发现候选后再次等待预览补写，再回调成功 | 当前只接收首个完成成功回调的候选；成功后销毁 WebView |
| 超时 | 进入 `HiddenProbing` 后计时 10 秒，直接改为 `Failed` | 显示可点击错误行；WebView 仍挂载，没有随失败停止 |
| 确认下载 | 权限通过后创建新的任务记录，SharedFlow 通知导航到 `VideoDownloadRoute(taskId)` | 无独立“正在创建任务”状态；按钮仍可再次点击 |
| 进入下载页 | `startDownload` 对所有非成功任务先写入 0% 下载态，再以 KEEP 入队；随后启动同一任务的 Room 观察 | 准备、下载百分比、合并百分比、成功或失败；Worker 独立于页面运行 |
| 下载结束 | Worker 回写 Room | 成功行点击播放；失败行点击重试；失败原因虽进入 UI state，但组件没有展示 |

`downloadState` 和 `taskData` 来源于同一个任务观察，不是两次 Room 查询；这部分已有复用不应当作冗余删除。视频下载与合并保留不同阶段也有实际业务意义。

### 确定的问题与影响

1. **P1：识别失败后的重试被挡住。** `VideoExtractPage` 的重试仅执行 `sessionId += 1`，而新一轮 effect 遇到 `Failed` 就提前返回，无法回到 `HiddenProbing`。失败状态还保留 WebView，候选回调只排除 `Success`，因此可能在错误提示之后又被迟到候选改成成功。应让重试显式启动新会话，失败封闭旧会话，并用会话身份校验候选回调。
2. **P2：查看运行中任务会改写进度。** `VideoDownloadVm.startDownload` 只排除成功任务，查看正在下载或合并的任务也会执行 `updateProgress(taskId, 0)`；Worker 使用 KEEP，并不会因这次查看重新开始。UI 因而可能从合并退到下载 0%，直到下一次 Worker 回写。观察任务、首次启动和显式重试需要分开。
3. **P2：识别结果被预览补全阻塞，当前页却不观察补写结果。** `SharedVideoProbeWebViewLayer` 在候选成功回调之前等待 `onSaveLinkPreview`，多个命中请求又可能分别启动相同补写。`observeCachedPreview` 实际只是 `flow { emit(loadLinkPreview(...)) }`，本次收集期间不会收到新增封面。辅助缓存工作进入识别关键路径，却不能保证当前摘要刷新。应先固定候选与页面阶段，将有界的预览补全独立编排，并合并重复补写。
4. **P2：创建任务没有提交中状态或互斥。** `Success` 在权限通过时立即清空 `pendingCandidate`，`startDownloadAndGo` 每次调用都会创建新记录。快速重复点击可产生多个任务和导航事件；应在当前提交完成前禁用动作，并对创建入口防重。
5. **P2：页面可见性没有约束探测。** 现有清理依赖 `DisposableEffect.onDispose`，未监听前后台生命周期。Activity 暂停但组合仍存在时，计时、页面作用域协程和 WebView 没有显式暂停边界。Room 的生命周期感知订阅不覆盖这些任务。正式下载 Worker 应继续后台运行；网页探测需要独立的暂停/取消及恢复规则。
6. **P2：错误信息与内容上下文不足。** `VIdeoDownloadPage.Failed` 接收 `errorMsg` 却只展示统一文案；失败时媒体摘要整体消失。用户无法区分网络失败、权限失败、任务缺失，也容易感觉整个内容块突然消失。后续应保留任务摘要并提供经过脱敏和资源化的可行动原因。

### 共享加载风险与待实机验证项

- **共享 WebView 的重载判据不合理。** `ProbeWebView.update` 比较 `webView.url` 与原始 `targetUrl`，不是比较上一次请求加载的目标。短链重定向或站内导航后，两者正常情况下就不相等；后续 AndroidView update 若被触发，将再次加载入口。两个页面还各自保留显式 `loadUrl` 路径。应由一个会话/目标变更入口负责加载，不把合法重定向当作新请求。需要用合成短链重定向页验证加载次数，当前没有测定实际发生频率。
- `onPageFinished` 没有按主文档/会话完成去重，视频可能重复补预览和自动播放；图片会取消再启动滚动扫描。回调频率和对具体站点的影响待实机记录。
- `WebViewLinkPreviewExtractor.evaluateJavascriptAwait` 没有独立超时，返回为空与永远不回调是不同情况。预览补全不应无限等待，也不应在取消被捕获后继续驱动业务完成。
- 加载/成功区域和广告可能随阶段改变高度，但代码已实现滚动/触摸时延迟广告布局变化。没有录屏、帧时序或布局测量前，不把所有跳动归因于广告。

### 冗余与收敛方向

- 私有旧版 `VideoProbeWebViewLayer` 没有调用方，还保留第二套 WebView 配置与 scheme 判断；应在建立共享探测回归后删除对照实现。
- `NeedUserPlay`、25 秒计时、隐藏层第二个标题栏及相关注释属于未启用流程残留。应集中标明未支持状态，避免维护者误以为自动超时后真的可以手动播放。
- 后续优先复用 `ProbeWebView`、`MediaStateContent`、`MediaSourceSummary` 和既有 Repository/Worker；状态转移与平台生命周期分开治理，不把图片候选筛选和视频识别强行合成万能页面。
- 修复顺序建议：失败重试与迟到回调 → 共享加载/生命周期 → 查看任务不改进度 → 提交防重、预览补全 → 页面内容和错误展示。以上是修复前审查建议，执行结果见顶部记录。

### 日志与诊断计划

以下为修复前审查提出的诊断建议；当前日志实施见顶部记录。后续修复应以低频 DEBUG 记录会话开始/暂停/停止、加载原因和次数、预览补全耗时；INFO 记录候选接受、任务创建和用户重试；WARN 记录超时、过期回调丢弃和不可恢复失败。字段限于 sessionId、taskId、阶段、计数、耗时、reasonCode 与布尔值，网络/DOM 高频回调只汇总。禁止输出网页正文、完整 URL、查询参数、Cookie、Token 和候选对象。当前仍有 URL/预览对象日志，实施诊断前应同步收敛，不能直接收集或分享原始敏感日志。

### 验证与后续测试边界

- 本轮已沿调用链核对并搜索死代码引用；未修改 Kotlin，未新增测试，未运行 Gradle、R8 或设备测试，不能声称上述问题已修复。
- 现有 `MediaTaskAdPolicyTest` 覆盖广告资格，不覆盖失败重试、候选过期、任务启动幂等、查看进度不回退或预览补写状态。
- 后续按单元测试门禁，先补状态/事件回归测试：失败后重试进入新 session；旧候选不能覆写失败/新会话；查看下载和合并任务不重置进度；重复提交只创建一次；补预览超时不挡住识别结果。
- Android/WebView 验证覆盖重定向、重复完成回调、切后台再返回、慢网和连续重试；使用合成测试页，不依赖真实用户链接。届时运行 `./gradlew.bat :app:testDebugUnitTest`、`./gradlew.bat :app:compileDebugKotlin` 和受影响的仪器测试。

## 当前状态

视频提取入口位于剪贴板详情页。用户点击“提取视频”后进入视频提取页，页面使用共用的 `ProbeWebView` 加载目标网页，视频专用探测层已与图片提取页一致，显式设置完整视口、透明显示和用户触摸屏蔽；前景背景和触摸遮罩继续保留，用户操作现有提取内容。调整与验证边界见下文“视频探测层隐藏调整”。页面加载完成后尝试通过 JavaScript 静音播放页面中的 `video` 标签。探测过程中，页面会通过 `shouldInterceptRequest` 拦截 WebView 发出的网络请求，并用扩展名、MIME、关键词以及抖音播放接口特征判断候选视频地址。

当前实现以自动识别为主：如果在隐藏探测超时时间内捕获到候选视频地址，页面会展示识别成功状态；用户点击“去下载”后，先申请必要的存储权限，再创建视频下载任务并跳转到视频下载页。创建入口通过 WorkManager 启动 `DownloadVideoWorker`，下载页只做观察或显式重试，支持普通视频直链和 M3U8/HLS 下载，下载完成后将视频保存到系统媒体库目录并允许用户点击播放。

本次新增链接预览补全策略：剪贴板保存时仍使用 `LinkMetaParser` 做快速 OkHttp/Jsoup 解析；遇到知乎 403、登录态或反爬限制时不在首轮请求中强行绕过。用户后续进入视频提取页时，隐藏 `WebView` 会以浏览器上下文加载网页，页面加载完成后从 DOM meta、JSON-LD、`video poster` 和图片候选中提取标题、描述、站点名和预览图，并补写到 `link_previews` 表。用户返回列表页后，原本只有域名兜底的链接卡片可以显示 WebView 阶段补齐的预览图。

当前代码组织已将视频提取页状态 UI 和 WebView 探测辅助拆分：`VideoExtractPage.kt` 保留状态机、WebView 生命周期和页面事件编排，`VideoExtractStatusContent.kt` 承载视频提取页的权限触发与候选下载入口；加载、失败和成功等轻量行内状态复用共享 `MediaStateContent`，完整共享规则见 `docs/shared_media_ui_plan.md`；`VideoProbeWebViewLayer.kt` 只承载共享探测层适配和视频请求判断，旧版对照层已删除。

视频提取页与下载页均复用 `SecondaryPageScaffold` 和 `PageBackground`；隐藏 WebView 只在探测阶段挂载，不再绘制第二套标题栏。

## 视频探测层隐藏调整（2026-09-30 历史范围，生命周期已由 2026-10-16 修复更新）（2026-09-30）

状态：已完成（探测层配置与中文注释已调整，Kotlin 编译通过；设备显隐与识别回归仍待验收）。

### 需求与现状

- 目标：视频地址提取期间隐藏网页内容，用户只看到现有标题栏、媒体摘要、提取进度和操作状态，行为与图片提取页一致。
- 图片页已通过 `ProbeWebView(modifier = Modifier.fillMaxSize().alpha(0f), consumeUserTouch = true)` 保留真实探测视口，同时隐藏网页并屏蔽用户触摸。
- 调整前，视频页实际调用 `SharedVideoProbeWebViewLayer`，其内部 `ProbeWebView` 使用默认可见 modifier，且没有启用触摸屏蔽；页面只依靠前景遮罩覆盖。现已按下述实现补齐两个配置。
- 以上是静态源码确认的差异；网页穿透遮罩的具体设备渲染原因尚未复现，不能据此断言 AndroidView 层级或视频渲染机制是唯一原因。

### 最终实现与影响范围

1. 只调整 `VideoProbeWebViewLayer.kt` 中实际使用的 `SharedVideoProbeWebViewLayer`：调用 `ProbeWebView` 时显式传入 `Modifier.fillMaxSize().alpha(0f)` 和 `consumeUserTouch = true`，增加 `alpha` import。
2. 补齐该 Composable 的简体中文说明和新增配置附近的注释，明确透明仅改变展示，完整视口用于网页加载和播放触发，用户触摸不参与自动探测。
3. 复用共享组件既有的 modifier 与触摸屏蔽接口，不新增隐藏策略类、状态字段或公共 Boolean 参数，也不修改 `ProbeWebView` 的默认行为。
4. 保留 `VideoExtractPage` 现有 Box 叠放、前景背景、触摸遮罩、状态展示和广告接入；本轮不重构标题栏或提取页骨架，也不调整未被调用的旧版 `VideoProbeWebViewLayer`。
5. 保留网页加载、自动播放 JS、请求拦截、Cookie/Referer/User-Agent 获取和链接预览补全；保持 10 秒自动探测超时、失败重试、识别成功清理 WebView 和退出清理的现有行为。

生产代码实际只修改 `app/src/main/java/com/cla/clip/master/ui/page/video/VideoProbeWebViewLayer.kt`。方案更新本主文档；图片页及共享媒体/广告契约未变化，不重复修改其他方案文档。

### 显示与资源边界

| 场景 | 目标表现 | 探测行为 |
| --- | --- | --- |
| 首次进入、Idle 到 HiddenProbing | 网页始终透明，只展示前景页面 | 保持现有加载、JS 和网络拦截 |
| 自动探测超时、Failed | 只显示失败重试内容，不露出网页 | 本轮不调整既有停止/清理时机 |
| 点击重试 | 新一轮探测保持透明，不闪现网页 | 使用现有 session 和重新加载逻辑 |
| Success | 显示候选和下载入口 | 保持现有成功清理 WebView |
| 返回离开页面 | 离开提取页 | 保持 DisposableEffect 退出清理 |

透明不等于停止加载，也不等于暂停媒体；本轮只处理显示和触摸。保留完整尺寸，避免删除 AndroidView、设置零尺寸或 GONE 后改变网页视口及资源请求时机。`NeedUserPlay` 当前正式流程未启用，本次不启用；以后若需要可见网页辅助播放，必须另行设计显隐和触摸切换，不能直接复用始终透明的探测层。

### 实施与测试验证

- 实施前再次确认实际调用链及工作区改动；按上述单文件范围调整配置和中文注释，完成后同步本节状态与实际结果。
- 单元测试门禁判断：本方案只复用既有 Compose modifier 与 Android WebView 触摸配置，没有新增纯规则或状态机；JVM 单测不能可靠证明真实 WebView 隐藏、播放和资源拦截，暂不新增单测或为此抽出纯策略。风险通过设备回归覆盖；以后若抽出显隐 predicate 或启用手动播放状态机，必须先补失败测试。
- 实施后运行 `./gradlew.bat :app:compileDebugKotlin` 和 `git diff --check`。
- 设备验证首次进入、网络慢加载、超时失败、连续重试与返回退出，确认网页和播放器内容无闪现，前台按钮正常，点击/滑动空白处不影响后台网页。
- 使用修改前可成功识别的视频网页验证仍能捕获候选，确认下载入口和链接预览补全不回归；同时检查小屏、横竖屏、深色模式及有/无缓存摘要的显示。
- 编译通过只证明源码可编译，不能替代 WebView 设备验收；如透明后识别率变化或仍出现视频画面，先定位实际渲染/播放路径，再决定后续方案。
- 本轮不改变下载数据、数据库、备份覆盖、外部协议或 keep 规则，暂不要求新增 release/R8 构建。

实际验证（2026-09-30）：常规编译及放宽沙箱后的同命令重试均因 Kotlin daemon 临时文件和 Gradle 缓存 `appcompat-1.7.1-api.jar` 的访问权限失败；随后运行 `./gradlew.bat :app:compileDebugKotlin --no-daemon "-Pkotlin.compiler.execution.strategy=in-process" --console=plain`，结果为 `BUILD SUCCESSFUL`。未修改项目 Gradle 配置。构建仍报告 `databaseEnabled` 弃用等警告；`git diff --check` 通过。未新增或运行单元测试，原因见上述门禁判断；未安装或操作设备，网页无闪现、触摸屏蔽与识别成功率尚未运行时验证。

### 日志与诊断计划

本轮不新增运行时日志：只调整展示和触摸配置，不引入新的网络、文件、数据库副作用或状态分支，高频显隐日志会增加噪声。定位时沿用现有探测开始、超时、成功与清理节点，仅关注 session、阶段、候选是否命中和耗时等低敏信息；禁止为验证本方案新增完整 URL 查询串、Cookie、Token、候选对象、HTML 或请求体输出。实际网页显示、触摸和视频渲染效果依靠设备观察验证。

### 后续待办

- 在设备验证后记录网页无闪现、触摸屏蔽和视频识别回归结果。
- 页面骨架统一、手动播放辅助、失败态资源停止与更广泛的生命周期治理继续作为独立需求，不扩大本次隐藏调整。

## 目标

- 从网页链接中自动识别可下载的视频资源地址。
- 尽量复用网页请求上下文，例如 Referer、User-Agent 和 Cookie，降低反盗链导致的下载失败概率。
- 视频地址识别成功后创建可观察的下载任务，并跳转到下载页展示进度。
- 支持普通视频文件和 M3U8/HLS 分片视频下载。
- 下载失败时清理半成品，避免用户在相册或文件管理器中看到损坏文件。
- 首轮链接预览失败不阻塞剪贴保存；视频提取 WebView 成功加载网页后补全链接预览缓存。

## 范围

- 涉及剪贴板详情页的视频入口、视频提取页、视频下载页、下载任务 Repository、Room 下载任务表、WorkManager 下载 Worker、文件保存工具和字符串资源。
- 当前只提取第一个被识别到的视频候选地址，不提供多候选列表、清晰度选择、字幕选择或音轨选择。
- 当前手动播放辅助识别流程尚未正式启用；自动识别超时后直接展示失败重试。
- 当前不实现断点续传；重试会重新拉取并覆盖本任务状态。
- 链接预览补全复用现有 `link_previews` 表和剪贴记录的 `link` 关联，不新增数据库字段或迁移。

## 用户体验

1. 用户在详情页看到网页链接提示，并点击“提取视频”。
2. 视频提取页显示“提取视频地址中...”加载状态，同时后台 WebView 加载网页。
3. WebView 加载完成后自动尝试播放页面中的视频元素，以触发真实媒体请求。
4. 如果拦截到疑似视频请求，页面显示“视频地址提取成功”和“去下载”按钮。
5. 用户点击“去下载”后进入权限检查；权限满足后创建下载任务并跳转视频下载页。
6. 视频下载页展示准备下载、下载中、合并中、下载完成或下载失败状态。
7. 下载完成后用户点击成功状态可调用系统播放器打开视频。
8. 从下载页返回时会跳过视频提取页，回到视频提取入口之前的页面；如果从通知进入下载页，则执行普通返回。

## 最终实现

- `DetailPage` 在剪贴板内容包含链接时展示“提取视频”按钮，并通过 `VideoExtractRoute(url, name)` 进入提取页。
- `VideoExtractPage` 使用 `ProbeState` 管理空闲、隐藏探测、需要用户播放、成功和失败状态。
- `ProbeWebView` 统一承载网页探测配置：启用 JavaScript、DOM Storage、Cookie、第三方 Cookie、混合内容和移动端 User-Agent，并拦截外部 App scheme，避免网页跳出当前流程。
- 页面加载完成且进度达到完成状态后，`AUTO_PLAY_JS` 会尝试静音播放页面内所有 `video` 元素，提升捕获真实视频请求的概率。
- `isLikelyVideoRequest` 按抖音播放接口、常见视频扩展名、视频关键词、请求头 MIME 判断候选视频地址。
- `VideoCandidate` 记录视频地址、Referer、User-Agent、Cookie 和文件名，作为提取页到下载任务之间的核心数据结构。
- `VideoCandidate` 只作为提取页内存态和创建下载任务前的轻量候选对象使用，不承担导航参数、Parcelable 或 JSON 序列化契约。
- `VideoExtractVm.startDownloadAndGo` 防重创建新任务并入队，成功身份保存在 VM；页面消费后导航到 `VideoDownloadRoute`，失败保留候选允许再试。
- `DownloadRepository` 每次下载都会创建新的 `download_tasks` 记录；`video_url` 只保留普通索引，允许同一地址多次下载并分别指向各自的本地媒体文件。
- `VideoDownloadPage` 观察同一任务的 Room 与 WorkManager 状态；查看不重置进度。失败或中断且已无活动 Worker 时，明确重试才从头重新入队；成功任务只观察和播放。
- `DownloadVideoWorker` 使用前台通知下载视频，保存前先登记 MediaStore 输出 URI，失败时清理半成品，成功时标记媒体文件可见并发送下载结果通知。
- 下载直链时会校验响应类型和响应头内容，避免把 JSON、HTML 或错误页误保存成视频。
- 下载 M3U8 时会解析 master playlist 并选择最高带宽子流，随后解析分片列表和 AES-128 KEY，并发下载分片后按顺序合并成单个视频文件。
- 抖音 `/playwm/` 地址会先尝试替换为 `/play/` 无水印地址，失败后再回退到原地址。
- 每次主文档完成后旁路补写 `link_previews`，最多等待 1.5 秒；新文档、成功、失败、暂停均取消旧任务。候选先被接受，不等待预览。补写成功递增缓存版本以刷新当前摘要；提前完成识别时允许保留原摘要兜底。

## 状态流转

- `Idle`：页面初始状态，等待探测会话启动。
- `HiddenProbing(sessionId)`：隐藏 WebView 自动探测中，用户看到加载状态。
- `Success(candidate)`：已捕获视频候选地址，等待用户确认下载。
- `Failed`：十秒探测超时，关闭旧回调并移除 WebView；点击重试显式开启新的会话。后台暂停回 Idle，成功/失败状态恢复后不自动重试。

## 数据流

- 详情页读取剪贴板记录中的链接和标题，并跳转到 `VideoExtractRoute`。
- 视频提取页创建或复用 `ProbeWebView` 加载网页。
- WebView 请求被 `shouldInterceptRequest` 拦截，命中视频判断后构造 `VideoCandidate`。
- 提取成功后销毁 WebView，避免继续加载或占用资源。
- 用户确认下载后，ViewModel 将候选地址和请求上下文写入 `download_tasks`。
- 下载页通过 `observeTask(taskId)` 监听 Room 中的下载任务变化。
- Worker 根据任务中的 URL、Referer、User-Agent 和 Cookie 构造 OkHttp 请求。
- Worker 将下载进度、合并进度、成功或失败状态回写 Room，下载页随 Flow 更新 UI。
- 下载成功后，文件保存到媒体库，并通过系统媒体扫描或 MediaStore pending 状态更新对外可见。
- WebView 链接预览补全只更新 `link_previews`，列表页仍通过剪贴记录中的原始 `link` 读取关联预览；返回列表后分页数据重新加载即可显示补齐结果。

## 下载任务当前边界

历史页及其重试/删除/文件校验已移除，当前下载结果继续使用自身保存的 URI；每次新下载使用独立任务和输出身份。

## 涉及文件

- `app/src/main/java/com/cla/clip/master/ui/page/detail/DetailPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/video/VideoExtractPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/video/VideoExtractStatusContent.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/video/VideoProbeWebViewLayer.kt`
- `app/src/main/java/com/cla/clip/master/ui/widget/MediaStateContent.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/video/VideoExtractVm.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/video/VIdeoDownloadPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/video/VideoDownloadVm.kt`
- `app/src/main/java/com/cla/clip/master/ui/widget/ProbeWebView.kt`
- `app/src/main/java/com/cla/clip/master/entity/VideoCandidate.kt`
- `app/src/main/java/com/cla/clip/master/work/DownloadVideoWorker.kt`
- `app/src/main/java/com/cla/clip/master/work/Download.kt`
- `app/src/main/java/com/cla/clip/master/utils/WebViewLinkPreviewExtractor.kt`
- `app/src/main/java/com/cla/clip/master/ui/navigation/AppNavigation.kt`
- `app/src/main/java/com/cla/clip/master/ui/navigation/Routes.kt`
- `base/general/src/main/java/com/cla/clip/base/general/repository/DownloadRepository.kt`
- `base/general/src/main/java/com/cla/clip/base/general/repository/ClipRepository.kt`
- `base/general/src/main/java/com/cla/clip/base/general/repository/ClipRepositoryImpl.kt`
- `base/general/src/main/java/com/cla/clip/base/general/dao/DownloadDao.kt`
- `base/general/src/main/java/com/cla/clip/base/general/utils/FileUtils.kt`
- `base/general/src/main/res/values/strings.xml`

## 测试验证

- 从详情页点击“提取视频”后能进入视频提取页。
- 普通 MP4/WebM/FLV 直链或网页触发的媒体请求可被识别，并进入成功状态。
- M3U8 地址可进入下载页，分片下载完成后进入合并中状态并最终保存为视频文件。
- 抖音 `/playwm/` 地址下载时优先尝试无水印 `/play/` 地址，失败后能回退原地址。
- Referer、User-Agent、Cookie 能从视频候选传入下载请求，反盗链站点尽量保持可下载。
- 自动识别超时后显示失败重试，点击重试会开启新的探测 session。
- 下载失败时任务状态变为失败，MediaStore pending 文件或旧文件路径会被清理。
- 下载成功后点击成功状态可唤起系统视频播放器；没有可用播放器时显示 Toast。
- 运行 `./gradlew :app:compileDebugKotlin` 验证编译。
- 如果后续重新启用候选对象跨路由或跨进程传递，需要重新评估 Parcelable、JSON 字段协议和 release R8 保留策略。
- 在知乎等首轮 Jsoup/OkHttp 返回 403 的页面中，复制链接后列表可先展示域名兜底；进入视频提取页并成功加载网页后返回列表，链接卡片可显示 WebView 阶段补齐后的标题或预览图。

## 已知取舍

- 当前只使用第一个命中的候选视频地址，命中 `.ts` 分片或低清晰度资源时可能提前结束探测。
- 自动播放受网页策略、登录态、脚本加载时机和 WebView 媒体策略影响，不能保证所有站点都能自动触发视频请求。
- 手动播放辅助未实现，未启用状态和二十五秒分支已删除；当前只有自动识别及明确失败重试。
- M3U8 下载会将分片临时写入 `cacheDir/m3u8/{taskId}`，完成或失败后清理；下载过程中仍可能占用较多缓存空间。
- 任务以 `video_url` 建普通索引，同一视频地址允许产生多条记录，确保每次新下载拥有独立任务和媒体输出身份。
- 当前不做断点续传，重新下载会从 0% 开始。
- WebView 补全预览是后置缓存更新，不改变视频候选识别和下载任务创建逻辑；如果网页没有暴露可用 meta、JSON-LD、poster 或图片候选，列表仍保持原兜底展示。

## 开放问题

- 后续可独立设计手动播放辅助流程，在自动识别失败后显示可操作 WebView，引导用户手动点击播放来触发视频请求。
- 后续可以增加候选列表和质量筛选，避免第一个命中地址不是用户真正想下载的视频。
- 后续可以优化视频请求判断规则，降低 `.ts` 分片、广告视频、预加载片段或非主视频资源的误判。
- 后续可以为 M3U8 下载补充更完整的进度统计，例如区分分片下载、解密和合并阶段的总进度。
- 后续如果要支持大文件下载恢复，需要在任务表中记录断点信息，并调整 `VideoDownloadVm` 当前重置进度的逻辑。
- 当前任务成功后进入下载详情不会自动重新入队；当前失败/中断重试仍沿用已有状态策略。
- 尚未实机验证透明 WebView、脚本导航和媒体播放的厂商差异；需要空数据设备使用合成网页验收，不在现用手机执行 connected 测试。

## 变更记录

- 2026-10-26：本轮移除已完成；483 项单测、编译与 Release/R8 通过，设备验收未执行，详细证据见下载记录主方案。

- 2026-10-26：保留视频探测、下载详情、当前任务重试和冷启动取消；删除仅供历史页删除记录使用的单任务取消入口，不新增替代取消 UI。原因是用户确认执行宿主媒体历史退役。

- 2026-10-26：引用下载记录移除草案并明确视频下载执行与当前任务重试保留；原因是历史管理退役不等于移除任务执行存储。本轮仅改方案。

- 2026-10-16：按用户确认实施会话重试、页面生命周期、加载去重、进度/切换与旧链路清理；新增失败回归、预算、取消及状态测试，并补充独立复核发现的文档导航和中断任务边界。当前实现以顶部执行记录为准，旧审查保留为修复前证据。

- 2026-10-16：完成视频识别与下载页的源码流程审查，记录重试失效、迟到回调、查看任务重置进度、预览补全阻塞、重复提交及共享 WebView 加载/生命周期风险；原因是用户反馈两个下载页面体验不佳。仅同步现状、诊断计划和回归边界，未修改业务代码或宣称完成修复。

- 2026-09-30：完成视频探测层透明显示与触摸屏蔽，补齐中文说明并同步当前实现；使用不复用 daemon 的进程内 Kotlin 编译通过，设备验收待完成。原因是用户确认按图片页方式隐藏网页，本轮保持单文件生产代码调整。

- 2026-09-30：用户确认隐藏调整方案，开始在视频专用探测层复用透明 modifier 和触摸屏蔽配置；生产代码范围保持单文件，不改变视频识别规则和状态机。

- 2026-09-30：新增视频探测层隐藏调整草案，并修正当前状态对“隐藏”的描述；原因是用户反馈提取时仍显示 WebView，计划复用图片页的透明与触摸屏蔽配置，保留既有视频探测流程。本次仅更新方案，未修改业务代码。

- 2026-09-27：视频提取、下载进度和结果广告补齐与媒体摘要相同的左右 16dp 留白；下载页继续继承 PageBackground 的共同外层边距。纯布局调整，不改变任务状态和请求门控；完整广告布局与验证以 [广告主方案](ad_module_plan.md) 为准。

- 2026-09-27：视频提取/下载阶段广告在页面返回时建立新访问会话，阶段资格及媒体任务不变；修复广告释放后不重载，15 秒预算、日志及验证以 [广告主方案](ad_module_plan.md) 为准。

- 2026-05-27：视频下载页接入统一二级页面骨架，并记录视频提取页保留专属探测 chrome 的例外；原因是下载页只是状态展示和 Worker 观察，低风险适合统一骨架，提取页则绑定隐藏 WebView 与探测状态机，需要下一轮单独抽象。
- 2026-05-13：新增视频提取功能方案文档，记录当前视频入口、WebView 探测、候选判断、下载任务、直链/M3U8 下载、已知取舍和后续待办；原因是视频提取功能已有实现但缺少本地方案文档。
- 2026-05-14：补充下载记录页扩展契约，明确 Android 10+ 以保存的媒体 URI 作为读取和删除身份、视频重新下载创建新任务记录、同名文件使用序号后缀、删除进行中记录先取消 Worker 再删对应任务行；原因是下载记录页需要保留每次下载结果并避免误读同名副本、误删公共文件或误删整个数据库。
- 2026-05-14：将状态更新为实现中，并补充 WebView 链接预览补全方案；原因是用户明确希望首轮 HTTP 解析失败时先不调整 `LinkMetaParser`，等视频提取 WebView 成功加载网页后再补写预览图等信息，返回列表即可看到补齐后的链接预览。
- 2026-05-14：完成 WebView 链接预览补全实现并将状态更新为已完成，通过 `./gradlew :app:compileDebugKotlin` 验证；原因是视频提取页已能在页面加载完成或视频候选命中前补写 `link_previews`，且不改变首轮 `LinkMetaParser` 行为。
- 2026-05-14：同步下载记录页实现后的下载任务契约，记录 `video_url` 从唯一索引调整为普通索引、每次下载创建新任务、成功历史任务进入下载页不自动重新入队；原因是下载记录功能已经落地，需要避免文档继续描述旧的任务复用行为。
- 2026-05-18：按当前代码组织规则拆分视频提取页，新增状态 UI 组件和 WebView 探测辅助文件；原因是页面入口同时承担状态机、权限触发、WebView 配置、请求判断和 UI 展示，拆分后更符合入口负责流程编排、工具/组件按职责放置的约定。
- 2026-05-20：移除未接入导航链路的 `VideoCandidate` 自定义参数契约说明，代码同步退回普通内存态 data class；原因是当前下载导航已经只传递 `taskId`，候选对象无需 Parcelable、JSON 字段协议或 release R8 类名稳定契约。
- 2026-05-18：视频提取页加载、失败和成功行内提示接入共享媒体状态组件；原因是图片提取页和视频提取页存在相同的轻量状态展示模式，应由 `MediaStateContent` 统一维护图标、颜色、字号和点击区域。


## 2026-09-18 AdMob 接入变更（实现中）

提取、下载/合并、下载结果使用三个独立单元。复用缓存网页摘要、候选和既有任务元数据展示实际内容；广告接入不额外请求媒体探测，也不额外触发媒体自动播放，视频探测本身既有的自动播放 JS 仍保留。权限申请拒绝/结束回调解除页面等待。

共享广告的隐私、生命周期、资源、布局及低敏日志契约以 [广告主方案](ad_module_plan.md) 为准；本页验证重点为业务交互不回归及广告异步加载时的内容锚点保护。

变更记录：2026-09-19，按实际接入更新页面差异与边界；集成编译/测试结果见主方案。
