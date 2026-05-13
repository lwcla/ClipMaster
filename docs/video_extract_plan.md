状态：已完成

# 视频提取功能方案

## 当前状态

视频提取入口位于剪贴板详情页。用户点击“提取视频”后进入视频提取页，页面使用共用的 `ProbeWebView` 在后台加载目标网页，并在页面加载完成后尝试通过 JavaScript 静音播放页面中的 `video` 标签。探测过程中，页面会通过 `shouldInterceptRequest` 拦截 WebView 发出的网络请求，并用扩展名、MIME、关键词以及抖音播放接口特征判断候选视频地址。

当前实现以自动识别为主：如果在隐藏探测超时时间内捕获到候选视频地址，页面会展示识别成功状态；用户点击“去下载”后，先申请必要的存储权限，再创建视频下载任务并跳转到视频下载页。下载页通过 WorkManager 启动 `DownloadVideoWorker`，支持普通视频直链和 M3U8/HLS 下载，下载完成后将视频保存到系统媒体库目录并允许用户点击播放。

## 目标

- 从网页链接中自动识别可下载的视频资源地址。
- 尽量复用网页请求上下文，例如 Referer、User-Agent 和 Cookie，降低反盗链导致的下载失败概率。
- 视频地址识别成功后创建可观察的下载任务，并跳转到下载页展示进度。
- 支持普通视频文件和 M3U8/HLS 分片视频下载。
- 下载失败时清理半成品，避免用户在相册或文件管理器中看到损坏文件。

## 范围

- 涉及剪贴板详情页的视频入口、视频提取页、视频下载页、下载任务 Repository、Room 下载任务表、WorkManager 下载 Worker、文件保存工具和字符串资源。
- 当前只提取第一个被识别到的视频候选地址，不提供多候选列表、清晰度选择、字幕选择或音轨选择。
- 当前手动播放辅助识别流程尚未正式启用；自动识别超时后直接展示失败重试。
- 当前不实现断点续传；重试会重新拉取并覆盖本任务状态。

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
- `VideoExtractVm.startDownloadAndGo` 调用 `DownloadRepository.createTask` 写入或更新下载任务，成功后通过 SharedFlow 通知页面跳转到 `VideoDownloadRoute`。
- `DownloadRepository` 以 `video_url` 唯一索引复用历史任务；重复下载同一地址时更新请求上下文、文件名和 pending 输出信息，并返回已有任务 id。
- `VideoDownloadPage` 观察下载任务状态并触发 `DownloadVideoWorker.enqueue`；失败状态点击后通过递增 `sessionId` 重新下载。
- `DownloadVideoWorker` 使用前台通知下载视频，保存前先登记 MediaStore 输出 URI，失败时清理半成品，成功时标记媒体文件可见并发送下载结果通知。
- 下载直链时会校验响应类型和响应头内容，避免把 JSON、HTML 或错误页误保存成视频。
- 下载 M3U8 时会解析 master playlist 并选择最高带宽子流，随后解析分片列表和 AES-128 KEY，并发下载分片后按顺序合并成单个视频文件。
- 抖音 `/playwm/` 地址会先尝试替换为 `/play/` 无水印地址，失败后再回退到原地址。

## 状态流转

- `Idle`：页面初始状态，等待探测会话启动。
- `HiddenProbing(sessionId)`：隐藏 WebView 自动探测中，用户看到加载状态。
- `NeedUserPlay(sessionId)`：预留给用户手动播放辅助识别的状态，当前正式流程未启用。
- `Success(candidate)`：已捕获视频候选地址，等待用户确认下载。
- `Failed`：自动探测超时或用户手动阶段超时，用户可点击重试并开启新 session。

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

## 涉及文件

- `app/src/main/java/com/cla/clip/master/ui/page/detail/DetailPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/video/VideoExtractPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/video/VideoExtractVm.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/video/VIdeoDownloadPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/video/VideoDownloadVm.kt`
- `app/src/main/java/com/cla/clip/master/ui/widget/ProbeWebView.kt`
- `app/src/main/java/com/cla/clip/master/entity/VideoCandidate.kt`
- `app/src/main/java/com/cla/clip/master/work/DownloadVideoWorker.kt`
- `app/src/main/java/com/cla/clip/master/work/Download.kt`
- `app/src/main/java/com/cla/clip/master/ui/navigation/AppNavigation.kt`
- `app/src/main/java/com/cla/clip/master/ui/navigation/Routes.kt`
- `base/general/src/main/java/com/cla/clip/base/general/repository/DownloadRepository.kt`
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

## 已知取舍

- 当前只使用第一个命中的候选视频地址，命中 `.ts` 分片或低清晰度资源时可能提前结束探测。
- 自动播放受网页策略、登录态、脚本加载时机和 WebView 媒体策略影响，不能保证所有站点都能自动触发视频请求。
- 手动播放辅助识别状态已经在状态机中预留，但当前超时后直接失败，因为 WebView 播放黑屏问题尚未解决。
- M3U8 下载会将分片临时写入 `cacheDir/m3u8/{taskId}`，完成或失败后清理；下载过程中仍可能占用较多缓存空间。
- 任务以 `video_url` 建唯一索引，同一视频地址会复用旧任务，避免重复记录，但也意味着同一地址不同标题会更新同一任务的文件名。
- 当前不做断点续传，重新下载会从 0% 开始。

## 开放问题

- 后续可以启用 `NeedUserPlay` 流程，在自动识别失败后显示可操作 WebView，引导用户手动点击播放来触发视频请求。
- 后续可以增加候选列表和质量筛选，避免第一个命中地址不是用户真正想下载的视频。
- 后续可以优化视频请求判断规则，降低 `.ts` 分片、广告视频、预加载片段或非主视频资源的误判。
- 后续可以为 M3U8 下载补充更完整的进度统计，例如区分分片下载、解密和合并阶段的总进度。
- 后续如果要支持大文件下载恢复，需要在任务表中记录断点信息，并调整 `VideoDownloadVm` 当前重置进度的逻辑。

## 变更记录

- 2026-05-13：新增视频提取功能方案文档，记录当前视频入口、WebView 探测、候选判断、下载任务、直链/M3U8 下载、已知取舍和后续待办；原因是视频提取功能已有实现但缺少本地方案文档。
