状态：已完成

# 图片提取功能方案

## 当前状态

图片提取入口位于剪贴板详情页，用户点击“提取图片”后进入图片提取页。页面使用隐藏的 `WebView` 加载目标网页，通过 DOM 扫描脚本、懒加载滚动探测和网络请求拦截合并图片候选，再将候选保存到 Room 的图片提取批次表和图片项表。用户确认后，`DownloadImagesWorker` 从数据库读取本批次图片，按网页顺序并发下载到临时目录，校验过滤无效图片后再发布到相册目录，并把本批次输出目录记录到批次 `outputDir`。

当前体验在下载前展示图片网格，所有图片默认选中。用户可以在网格中取消不需要的图片，也可以点击缩略图打开底部弹窗查看大图、动图和元信息，再确认下载已选图片。

2026-05-18 开始调整图片提取探测阶段的前台体验：隐藏 `WebView` 仍负责真实网页加载、自动滚动、DOM 扫描和网络拦截，但用户前台不再只看到加载态。只要探测到第一张有效图片，页面就切换到实时候选网格，实时展示已获取图片、默认选中新增图片，并允许用户取消选择、预览候选、手动结束提取后下载。自动完成只表示探测停止，页面停留在选择确认状态，不自动创建批次或进入下载；只有用户确认下载时才把已选候选写入 Room 并启动 Worker。

本次调整图片提取探测阶段：不对知乎或其他站点做单独处理，自动滚动最多执行两轮完整触底。第一轮触底后检查当前 DOM 快照中的有效图片是否已进入候选集，未完整时回到顶部再滚动一次；第二轮触底后直接结束探测并进入选择页。若探测开始后连续 10 秒没有任何有效候选，则进入失败流程；若超过 60 秒仍未首次触底且已有候选，则提示图片较多、展示已获取数量，并允许用户查看阶段性候选或手动结束提取。

本次新增链接预览补全策略：剪贴板入库时仍保留 `LinkMetaParser` 的 OkHttp/Jsoup 快速解析，不针对知乎 403 等风控站点做额外绕过；当用户后续进入图片提取页时，隐藏 `WebView` 以真实浏览器上下文加载网页，并从 DOM meta、JSON-LD、图片候选中提取标题、描述、站点名和预览图，补写到 `link_previews` 表。这样用户返回列表页时，即使首轮 HTTP 解析失败，也能看到 WebView 阶段补齐的预览图。

本次修复图片批量下载中的动图保存问题：下载阶段继续原样复制原始图片字节，不做 Bitmap 重编码；但发布到相册前不再只信任响应头 MIME，而是在临时文件落盘后通过文件头和 Android 解码结果识别真实格式，再用真实 MIME 和扩展名写入 MediaStore。这样服务端错误返回 `image/jpeg`、URL 无后缀或后缀与内容不一致时，GIF/Animated WebP 等动图仍能以正确媒体类型进入相册，避免被相册按静态图处理。后续加固要求预览和 Worker 使用一致的图片 `Accept`，并记录 GIF/WebP/APNG/AVIF 是否含动画标记；如果日志显示 `animated=true` 但外部相册静止，优先判断为相册播放能力或索引展示限制。

本次补充 B 站动态页动图提取策略：`b23.tv` 短链会跳转到 `bilibili.com/opus`，页面 SSR/脚本状态里的正文图片保存在 `window.__INITIAL_STATE__.opus.detail.modules[].module_content.paragraphs[].pic.pics[]`；用户反馈候选图阶段已经可播放，说明提取预览链路能拿到可播放内容，但最终保存仍可能因为 B 站图片组件提供 `xxx.gif@...webp` 等转码变体而落到相册不支持的静态/转码资源。因此完整 DOM 扫描会额外读取 B 站初始状态里的正文图片 URL，并在候选合并时对 B 站 BFS 图片样式后缀做同源去重，优先保留原始 `.gif`，避免用户默认下载到静态预览图。

为避免修复只对单个链接生效，本次进一步把动图候选优先级抽象成通用策略：完整扫描会递归读取常见页面初始状态对象和内联脚本文本中的图片 URL；候选合并时会识别 `xxx.gif@...webp`、`xxx.webp@...jpg` 等“原始图片 + 样式/转码后缀”形式，将其与原始 URL 归为同一图片并优先保留 GIF/WebP 等更适合动图保存的原始地址。站点专有字段只作为补充入口，不再作为唯一规则。

## 目标

- 提取完成后以网格展示全部图片候选，默认全选。
- 用户可以手动取消部分图片，再确认下载。
- 点击缩略图打开底部弹窗预览，长图可以上下滑动完整查看。
- 弹窗支持 GIF、Animated WebP 等动图播放。
- 弹窗展示分辨率、文件类型、文件体积，帮助用户在重复图片中选择质量更合适的一张。
- 下载完成或部分完成后，完成态点击入口直接打开系统相册，保持 App 轻量并避免文件夹直达在不同系统上的不稳定体验。
- 动图下载后保留原始文件内容、文件扩展名和 MediaStore MIME 的一致性，避免因媒体类型写错导致相册只显示首帧。
- B 站动态页中的 GIF 原图优先进入候选列表和下载批次，不被同一图片的 WebP/JPEG 静态预览替代。
- 对其他站点的脚本状态图片和常见样式转码图片，也尽量优先保存原始动图 URL，而不是只对单个 B 站链接生效。
- 不新增数据库字段，不引入 Room 迁移；选择状态保存在当前 UI 内存中。
- 图片提取探测不再无限上下循环；最多一次回顶重试，并在长耗时时允许用户查看当前进度。
- 首轮链接预览解析遇到 403、登录态或反爬限制时不阻塞保存；图片提取 WebView 成功加载后补全链接预览缓存。
- 提取阶段前台实时展示候选网格，用户可以一边看提取进度一边筛选图片。
- 透明 `WebView` 不响应用户触摸，避免用户滑动、点击或聚焦网页打断自动滚动探测。
- 自动完成后停留在选择确认页，用户确认后才创建下载批次；提交失败时保留当前候选和选择状态。
- 重新提取视为全新探测会话，确认后切断旧 `WebView`、清空候选和选择状态，再重新加载页面。

## 范围

- 涉及 `ImageExtractPage`、`ImageExtractVm`、`ImageExtractRepository`、`ImageExtractDao`、图片下载 Worker 的既有数据契约、字符串资源和 Gradle 图片加载依赖。
- 涉及链接预览缓存补写时，需要复用 `link_previews` 表和 `ClipRepository`，不新增数据库表或迁移。
- 不改变图片候选提取规则、图片内容过滤规则和最终保存路径。
- 调整图片下载 Worker 的格式识别和 MediaStore 入库 MIME；不改变数据库结构和用户确认下载交互。
- 调整图片提取页脚本状态扫描和候选去重优先级；不新增页面、数据库字段或用户操作。
- 通用化脚本状态扫描和样式图去重：优先处理标准 URL/扩展名模式，站点特殊结构只作为通用递归扫描可以覆盖的输入来源。
- 调整下载完成后的打开入口：页面完成态和图片结果通知都直接打开系统相册；`outputDir` 仅用于尝试携带相册 bucket 定位信息，不再尝试文件管理器或 DocumentsUI。
- 不实现完整相册能力，例如左右切换、双指缩放、暂停动图或逐帧控制。

## 用户体验

1. 用户进入图片提取页后，如果暂未发现有效图片，看到居中的提取加载状态。
2. 发现第一张有效图片后，页面立即展示实时候选网格；顶部第一行左侧显示“正在提取中/提取已完成”和已选数量，右侧固定放置主按钮“结束提取并下载/下载已选图片”，第二行提供重新提取、全选和取消全选，避免所有按钮挤在同一行。
3. 新发现图片默认选中并追加到网格中；用户取消过的图片不会因为 DOM 重扫、网络候选合并或 URL 从转码预览升级成原图而重新选中。
4. 透明 `WebView` 保持后台完整视口来触发懒加载，但不接收用户触摸事件；用户只操作前台网格、按钮和预览弹窗。
5. 缩略图点击打开底部弹窗。弹窗中的图片按宽度等比显示，不裁剪；如果图片很长，用户可以在弹窗内上下滑动查看完整内容。
6. 弹窗显示分辨率、文件类型和文件体积。文件体积依赖服务端响应头，无法获取时显示“未知”。
7. 用户可以在网格或弹窗内切换当前图片是否下载；全部取消时下载按钮禁用，并提示需要至少选择一张图片。
8. 用户点击“结束提取并下载”后，页面进入停止/提交中状态，停止后台 WebView 和滚动协程，只把当前已选候选写入批次并启动下载；提交失败时回到当前网格并保留选择状态。
9. 如果自动完成探测，页面顶部切换为“提取已完成”，关闭后台 WebView，仍停留在网格中等待用户点击“下载已选图片”，不会自动下载。
10. 正在提取或自动完成后已有候选但未下载时，点击重新提取需要二次确认；确认后清空当前候选和选择状态并启动全新会话。提交下载中禁用重新提取；下载开始后的重新提取不影响已创建的 Worker 批次。
11. 下载完成后，用户点击完成态文案会直接打开系统相册；如果相册支持 bucket 参数，会尽量定位到 `clipMaster/<本次网页标题目录>` 对应相册。
12. 点击图片下载结果通知时，同样直接打开相册；如果没有任何图片成功保存，则不携带具体目录，避免误导用户进入空目录。

## 最终实现

- `ImageExtractPage` 在实时提取和提取完成待确认状态下展示 `LazyVerticalGrid`，顶部第一行左侧显示提取状态和已选数量，右侧固定显示主下载按钮；第二行放置重新提取、全选和取消全选。
- `ImageCandidateTile` 使用 Coil 加载缩略图，复选图标独立处理选择动作，缩略图主体点击打开底部预览。
- `ModalBottomSheet` 负责单图预览，图片按宽度等比展示，内容区可纵向滚动，因此长图可以完整查看。
- 预览 ImageLoader 注册 `coil-gif` 解码器：Android 9 及以上使用 `AnimatedImageDecoder`，低版本使用 `GifDecoder`，支持 GIF 和系统可解码的 Animated WebP。
- 预览和缩略图请求都携带 Referer、User-Agent、Cookie，尽量保持预览加载与 Worker 下载一致。
- `ImageExtractVm` 在当前页面内缓存分辨率、文件类型和文件体积，文件体积只通过 HEAD 或 Range GET 响应头尽力获取。
- `ImageExtractRepository.keepSelectedItems` 通过事务删除未选图片并更新批次总数，Worker 继续读取剩余图片项下载。
- `ImageFolderOpenHelper` 统一处理图片结果查看入口，页面完成态和图片下载结果通知都复用它；工具不再尝试 DocumentsUI、文件管理器或目录 URI，只尝试相册 bucket 和普通相册入口，并通过 `ImageFolderOpenResult` 告知调用方是否成功打开相册。
- 图片下载结果通知使用 `TARGET_IMAGE_FOLDER` 和 `ImageFolderOpenData`，不再复用视频下载结果页跳转；没有成功保存图片时不会携带具体目录，避免打开空目录。
- 页面和通知只在没有相册可用时展示失败提示；成功打开相册时不额外 Toast，避免打扰用户。
- WebView 探测采用双轮触底策略：第一轮触底后做当前 DOM 快照完整性检查，必要时回顶再试一次；第二轮触底后保存已有候选并结束，不再持续上下循环。
- 自动完成必须至少在当前 DOM 快照中看到有效图片候选；如果 DOM 仍为空，只允许继续等待或失败，不会仅凭网络拦截中的单张疑似图片创建正式批次。
- 网络拦截候选只接收非主文档请求，并要求 `Sec-Fetch-Dest=image`、图片扩展名或明确图片 URL 语义，避免把网页主文档或初始化接口误当成图片。
- 探测阶段维护内存中的阶段性候选列表，供长耗时提示、进度查看和手动结束使用；阶段性候选不写入 Room，只有自动完成或用户点击“结束提取”时才创建正式批次。
- 探测开始后连续 10 秒没有任何有效候选会进入失败流程；超过 60 秒仍未首次触底且已有候选时只提示和允许查看进度，不自动失败、不自动停止。
- 页面加载完成和图片候选收集完成后，会尽力把 WebView 中获得的预览标题、描述、站点名和首张有效图片写回链接预览缓存；写入时保留旧的非空字段，只用新获得的非空字段补齐，避免一次不完整 DOM 扫描覆盖已有预览。
- 图片提取页标题栏由 `Scaffold.topBar` 承载，正文只应用 `Scaffold` 提供的内容区避让；`TitleBar` 自身负责状态栏高度，避免无 `topBar` 的 `Scaffold` 默认安全区与标题栏状态栏间距叠加，造成标题顶部留白过大。
- 探测期的 `ProbeWebView` 与前景状态内容使用 `Box` 叠放，WebView 保持完整视口但透明显示；这样懒加载和滚动扫描仍按真实屏幕尺寸执行，同时不会作为 `Column` 子项占满一屏并把加载进度挤到屏幕底部。
- `DownloadImagesWorker` 下载临时文件后会优先根据文件头识别 JPEG、PNG、GIF、WebP、AVIF、BMP 等真实格式，再用响应头和 URL 后缀兜底；最终文件名扩展名与写入 MediaStore 的 MIME 保持一致，动图字节不经过重编码。
- Worker、预览元信息请求和缩略图加载统一携带浏览器图片请求常见的 `Accept` 头，降低 CDN 因客户端协商差异返回静态转码图的概率；下载日志会记录该 Accept，方便对比提取页预览和 Worker 下载是否处在同类请求条件下。
- Worker 识别到动图后会尽力计算动画时长并写入 MediaStore `DURATION`，帮助部分系统相册把保存结果识别为动态媒体；Android 10+ 通过 `IS_PENDING=0` 发布后不再对 `content://` URI 调用媒体扫描，避免无效扫描失败日志。
- 不再对知乎示例这类 Animated WebP 做 GIF 转码试验；Worker 继续按真实文件格式原样发布，避免实验性转码产生空白图或破坏原始内容。
- `DownloadImagesWorker` 只保留批次读取、唯一输出目录创建、并发阶段编排、进度回写、最终批次状态决策和通知发送；图片请求头构建、真实格式识别、GIF/WebP/APNG/AVIF 动画元数据解析、下载内容质量校验、文件名清理、单图临时下载和 MediaStore 发布拆到 `image/download`、`image/format` 领域包，避免 Worker 再堆积格式解析、网络、校验和发布细节。
- `ImageTempFileDownloader` 负责单张图片的 OkHttp 请求、临时文件写入、真实格式识别、内容质量校验和单项状态回写；它通过 `ImageTempDownloadResult` 明确区分成功、过滤和失败，Worker 只消费结果并统计阶段进度。
- `ImageMediaStorePublisher` 负责把已经校验通过的临时图片按网页顺序写入 MediaStore/旧系统公共目录，保持原始字节不重编码，并在成功或失败时回写单项状态；Worker 通过进度回调更新前台通知。
- B 站 opus 页面额外从 `window.__INITIAL_STATE__` 读取正文 `pic.pics` 原始 URL；候选合并时把 `hdslb.com/bfs/...gif@...webp` 与 `hdslb.com/bfs/...gif` 视为同一张图片，并优先保留无样式后缀的 GIF 原图。
- 知乎图片 CDN 会出现 URL 后缀是 `.jpg`、响应内容是 WebP、预览仍可动的情况；候选合并需按 `zhimg.com` 的 `v2-*` 图片标识归并同一资源变体，优先保存 GIF/原图候选，并记录最终下载文件是否真的包含动画标记。
- 完整 DOM 扫描会额外递归读取 `__INITIAL_STATE__`、`__NEXT_DATA__`、`__NUXT__`、`__APOLLO_STATE__` 等常见页面状态对象，并扫描内联脚本中的图片 URL 字面量。
- 候选合并会把路径中带 `@` 样式/转码后缀且前缀已经是图片文件的 URL 归并到原始图片键上，优先保留 GIF/WebP 等动图友好的地址。

## 数据流

- `WebView` 加载网页并执行 DOM 图片收集脚本，自动滚动触发懒加载。
- `shouldInterceptRequest` 补充网络层捕获到的图片地址，并保存 Referer、User-Agent、Cookie；候选更新会回到主线程同步到阶段性候选状态，避免后台回调直接修改 Compose 状态。
- 滚动过程中持续做轻量 DOM 扫描，触底时做完整 DOM 快照扫描；`ImageExtractVm` 合并 DOM 候选和网络候选，按 DOM 顺序优先去重。
- `ImageExtractRepository.createBatch` 将批次和图片项写入 Room。
- UI 通过 `observeBatch` 观察批次状态，通过图片项 Flow 观察当前批次候选。
- 用户确认下载时，Repository 删除未选中的图片项，并把批次 `total_count` 更新为选中数量。
- `DownloadImagesWorker` 读取剩余图片项，并保持现有临时下载、内容校验、按顺序发布和状态回写逻辑；网络落盘和 MediaStore 发布细节分别交给下载/发布协作者。
- 单张图片写入临时文件后，`ImageTempFileDownloader` 先识别临时文件真实格式并校验内容质量，再交给 `ImageMediaStorePublisher` 按真实扩展名发布为 `001.gif`、`002.webp` 等文件，MediaStore 记录同步写入真实 MIME。
- 单张图片下载请求通过 `ImageRequestHeaderBuilder` 统一构建，保证 Accept、Referer、User-Agent、Cookie 的拼装规则可复用且便于查找；Cookie 只在日志中输出是否存在和长度。
- 临时文件真实格式由 `ImageFormatSniffer.detectDownloadedImageFormat` 识别；该职责包内部维护 MIME 规范化、URL 后缀兜底、图片文件头判断和动图时长解析。
- 下载后内容质量由 `ImageDownloadValidator.validateDownloadedImage` 校验；透明占位图、过小图片、纯黑/纯白错误图通过 `FilteredImageException` 与真实失败区分。
- 单图临时下载由 `ImageTempFileDownloader` 承接，下载阶段失败契约集中为 `ImageTempDownloadResult.Success`、`Filtered`、`Failed` 三类，避免 Worker 既要处理网络异常又要理解过滤语义。
- 临时文件发布由 `ImageMediaStorePublisher` 承接，发布阶段只接收 `DownloadedTempImage`，确保未通过校验的图片不会进入 MediaStore；发布进度通过回调交给 Worker 更新前台通知。
- 如果临时文件是 Animated WebP，下载/发布协作者只记录动画标记和时长并原样发布 `.webp`；不做 GIF 转码，避免相册兼容试验改变图片内容。
- `ImageExtractPage` 的 DOM 扫描结果进入 `ImageExtractVm` 后，ViewModel 会用规范化后的 B 站 BFS 原图 URL 去重；同一张图同时出现原图和转码预览时，最终批次只保留原图。
- 通用样式图去重不依赖具体链接：例如 `a.gif@672w_1c.webp` 和 `a.gif` 会合并为同一候选，最终批次优先保存 `a.gif`。
- 知乎图片会额外用 `zhimg.com` 路径中的 `v2-*` 图片标识做稳定去重；如果 DOM、脚本状态或网络拦截同时提供 `.jpg?source=...`、`.webp`、`.gif` 等变体，选择状态绑定同一 key，最终下载 URL 取当前优先级最高的候选。
- 结果通知点击进入 `MainActivity` 后，由 `MainVm` 一次性消费 `ImageFolderOpenData`，再调用 `ImageFolderOpenHelper` 打开相册入口。
- `DownloadImagesWorker` 发布成功后只把批次 `outputDir` 交给通知入口；打开工具直接进入相册，`outputDir` 仅用于尝试相册 bucket 定位。
- 进度查看页只读取阶段性候选，不写数据库、不启动下载；用户点击“结束提取”后才取消探测、销毁 WebView、保存当前候选并进入最终选择页。
- 实时选择页只读取内存候选，不提前写数据库；用户确认下载时，才根据稳定候选 key 读取当前最优 URL，创建正式批次并启动 Worker。
- 候选 key 与最终下载 URL 分离：选择状态绑定稳定去重 key，最终下载使用该 key 对应的最新候选，因此 `xxx.gif@...webp` 升级为 `xxx.gif` 时不会丢失用户选择。
- 下载诊断日志覆盖用户确认下载时的候选 key、最终 URL、Referer/User-Agent/Cookie 是否存在，以及 Worker 下载请求 Accept、响应头、临时文件大小、文件头识别格式、GIF/WebP/APNG/AVIF 动画标记、动画时长、发布到 MediaStore 的文件名/MIME/URI；用于排查动图在候选阶段、保存阶段、内容协商阶段或外部相册展示阶段变成静态图的问题。
- WebView 链接预览补全只写 `link_previews`，剪贴板记录仍通过原始 `link` 关联读取；列表页和详情页无需额外刷新协议，Room 分页重新加载后会显示补齐后的预览。

## 下载记录页扩展契约

- 下载记录页展示图片批次时，优先使用图片项成功发布后保存的 `output_uri` 读取和删除本地图片；后续如为旧系统补充路径字段，该路径只作为 `output_uri` 为空时的兜底。
- Android 10 及以上图片媒体项的唯一身份是发布成功后保存的 `content://` URI，不能用 `outputDir + finalName` 或同名文件名反查作为唯一身份，避免误读、误删同名图片。
- 图片重新下载不覆盖旧批次、不删除旧公共图片文件夹；应复制旧批次的图片 URL、Referer、User-Agent、Cookie 和顺序为新批次，并继续使用唯一文件夹逻辑生成 `folder_1`、`folder_2` 等序号后缀目录。
- 下载记录页删除图片记录时，只删除选中的 `image_extract_batches` 对应行，图片项仅通过外键级联删除这些批次下的数据；禁止删除整个 Room 数据库或影响剪贴板、搜索、来源 App、链接预览和未选中的下载记录。
- 用户选择“删除记录和本地文件”时，只逐个删除选中批次中成功图片项保存的 `output_uri` 或路径兜底，不删除公共父目录或整个图片文件夹，避免误删同目录中其他内容。
- Android 11 及以上批量删除多个 `content://` 图片媒体项时，应合并 URI 后通过 `MediaStore.createDeleteRequest` 一次请求用户确认；Android 10 遇到 `RecoverableSecurityException` 时走系统授权流程，不允许每张图片连续弹多个系统授权框。
- 删除进行中的图片批次时，必须先取消 `download_images:<batchId>`，再按用户选择逐个清理已发布图片或 pending 文件，最后删除 `image_extract_batches` 对应行并级联图片项，避免 Worker 迟到回写已删除记录。
- 清空图片下载记录只清空图片 Tab 下的批次记录，且清空前必须二次确认并展示数量；这不是清空整个数据库。

## 涉及文件

- `app/src/main/java/com/cla/clip/master/ui/page/image/ImageExtractPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/image/ImageExtractVm.kt`
- `app/src/main/java/com/cla/clip/master/ui/widget/ProbeWebView.kt`
- `app/src/main/java/com/cla/clip/master/image/download/ImageDownloadFileNames.kt`
- `app/src/main/java/com/cla/clip/master/image/download/ImageDownloadLogExtensions.kt`
- `app/src/main/java/com/cla/clip/master/image/download/ImageDownloadValidator.kt`
- `app/src/main/java/com/cla/clip/master/image/download/DownloadedTempImage.kt`
- `app/src/main/java/com/cla/clip/master/image/download/ImageMediaStorePublisher.kt`
- `app/src/main/java/com/cla/clip/master/image/download/ImagePublishResult.kt`
- `app/src/main/java/com/cla/clip/master/image/download/ImageRequestHeaderBuilder.kt`
- `app/src/main/java/com/cla/clip/master/image/download/ImageTempDownloadResult.kt`
- `app/src/main/java/com/cla/clip/master/image/download/ImageTempFileDownloader.kt`
- `app/src/main/java/com/cla/clip/master/image/format/ByteArrayImageHeaderExtensions.kt`
- `app/src/main/java/com/cla/clip/master/image/format/ImageAnimationMetadataReader.kt`
- `app/src/main/java/com/cla/clip/master/image/format/ImageFileFormat.kt`
- `app/src/main/java/com/cla/clip/master/image/format/ImageFormatSniffer.kt`
- `base/general/src/main/java/com/cla/clip/base/general/repository/ImageExtractRepository.kt`
- `base/general/src/main/java/com/cla/clip/base/general/dao/ImageExtractDao.kt`
- `base/general/src/main/java/com/cla/clip/base/general/utils/FileUtils.kt`
- `base/general/src/main/res/values/strings.xml`
- `app/src/main/java/com/cla/clip/master/MainActivity.kt`
- `app/src/main/java/com/cla/clip/master/MainVm.kt`
- `app/src/main/java/com/cla/clip/master/entity/ImageFolderOpenData.kt`
- `app/src/main/java/com/cla/clip/master/utils/ImageFolderOpenHelper.kt`
- `app/src/main/java/com/cla/clip/master/utils/NotificationHelper.kt`
- `app/src/main/java/com/cla/clip/master/utils/WebViewLinkPreviewExtractor.kt`
- `base/general/src/main/java/com/cla/clip/base/general/repository/ClipRepository.kt`
- `base/general/src/main/java/com/cla/clip/base/general/repository/ClipRepositoryImpl.kt`
- `gradle/libs.versions.toml`
- `app/build.gradle.kts`

## 实现步骤

1. 增加图片项观察和确认选择下载的数据层方法。
2. 在 ViewModel 中维护图片元信息缓存，提供确认下载入口。
3. 将提取完成状态从“数量 + 下载全部”改为网格选择界面。
4. 使用支持动图的 Coil `ImageLoader` 加载缩略图和底部预览图，并携带 Referer、User-Agent、Cookie。
5. 通过响应头尽力获取文件类型和文件体积；服务端不返回时显示“未知”。
6. 下载完成态点击时直接打开相册，并尽量用批次 `outputDir` 定位到对应相册 bucket。
7. 图片下载结果通知改为图片目录打开协议，避免继续复用视频下载结果页跳转。
8. 补充字符串资源和简体中文注释。
9. 编译验证后更新本文档状态和变更记录。
10. 将滚动探测改为双轮触底和当前 DOM 快照完整性检查。
11. 增加阶段性候选、10 秒无候选失败、60 秒长耗时提示、进度查看和手动结束流程。
12. 增加 WebView 链接预览补全能力，页面加载和候选收集成功后补写 `link_previews`。
13. 将探测中前台体验改为实时选择网格，新增图片默认选中，用户取消选择状态保存在当前页面内存中。
14. 为透明 `WebView` 增加触摸拦截，保证用户手势不会影响后台自动滚动探测。
15. 将自动完成改为“停止探测并等待确认下载”，确认下载时再创建批次并启动 Worker。
16. 增加重新提取确认、提交中防重复、提交失败保留选择状态和缩略图失败占位。

## 测试验证

- 提取完成后网格出现，所有图片默认选中。
- 取消部分图片后确认下载，只下载选中的图片，进度总数和结果数量匹配。
- 全选、取消全选、无选中状态下按钮可用性正确。
- 点击缩略图打开底部预览；普通长图可上下滑动看完整。
- GIF 或 Animated WebP 在弹窗中正常播放，而不是只显示首帧。
- GIF 或 Animated WebP 下载到相册后，文件扩展名和 MediaStore MIME 与真实文件头一致，相册支持该格式时应按动图播放。
- 服务端响应头错误返回 `image/jpeg` 但真实内容是 GIF/WebP 时，下载结果仍保存为正确格式。
- `https://b23.tv/ZXCm1f6` 对应的 B 站动态页应提取到 `https://i0.hdslb.com/bfs/album/ebe41132f60c8c2028ffccd473158095c02db02c.gif`，下载后文件头应为多帧 GIF。
- 换成同类页面或其他站点的 `原图 + 转码样式后缀` 动图 URL 时，应走同一套通用候选合并和真实文件格式保存逻辑。
- 同一网页存在多个不同动图时，只要原始路径不同，就应分别保留为多个候选；只有同一原图的 `xxx.gif` 与 `xxx.gif@...webp` 等转码变体会被合并。
- 弹窗中能显示已有分辨率、推断类型；体积获取失败时显示“未知”。
- 在弹窗中切换选中状态后，返回网格同步更新。
- 下载完成态点击后直接进入系统相册，不再尝试打开文件夹。
- 全部图片被过滤且没有成功保存图片时，页面只展示过滤结果，不展示“点击打开”，避免进入空目录或默认相册。
- 图片下载结果通知点击后不再进入视频下载页，而是直接打开相册入口。
- 普通长页面第一轮触底且当前 DOM 快照完整时，立即进入最终选择页。
- 第一轮不完整页面只回顶重试一次，第二轮触底后进入最终选择页。
- 无图片或解析失败页面连续 10 秒无有效候选后进入失败重试页。
- 图片很多且长时间未触底时，超过 60 秒显示长耗时提示、已获取数量和进度入口，后台探测继续运行。
- 点击“查看已获取图片”后能看到当前阶段性缩略图，普通返回后探测继续；点击“结束提取”后用当前候选进入最终选择页。
- 网络拦截候选和 DOM 扫描候选同时更新时，不出现崩溃、重复项或 UI 状态错乱。
- 下载记录页删除图片记录时，只删除选中批次行及其级联图片项，不影响其他业务表和未选中下载记录。
- Android 11+ 批量删除多张本地图片时只发起一次系统删除确认，删除完成后展示结果汇总。
- 删除进行中图片批次时先取消 Worker，再清理本地文件，最后删除对应批次行。
- 运行 `./gradlew :app:compileDebugKotlin` 验证编译。
- 在知乎等 Jsoup/OkHttp 返回 403 的页面中，复制链接后列表可先展示域名兜底；进入图片提取页并成功加载/收集候选后返回列表，链接卡片可显示补齐后的标题或预览图。
- 进入图片提取页时，标题顶部只保留 `TitleBar` 自身的状态栏间距，视觉上应与其他二级页面标题栏一致，不再出现额外顶部空白。
- 图片提取加载态进度条应在标题栏下方剩余内容区居中显示；后台 `ProbeWebView` 不应影响前景加载、失败或下载状态的垂直位置。
- 发现第一张有效图片后应立即显示实时网格，顶部数量随候选增加更新，不需要等待 60 秒长耗时提示。
- 触摸、滑动或点击前台网格和空白区域时，后台透明 `WebView` 不应响应用户手势，自动滚动探测不中断。
- 新候选默认选中；用户取消过的候选在 DOM 重扫、网络合并或 URL 升级后仍保持取消。
- 候选 URL 从转码预览升级为原图时，选择状态保持，最终下载使用升级后的最优 URL。
- 候选持续增加时不自动滚到底、不弹 Toast，已有图片位置尽量稳定，用户筛选过程不被打断。
- 探测中点击缩略图可打开预览弹窗；缩略图加载失败时仍保留候选项，并显示占位和 URL 尾部。
- 点击“结束提取并下载”后按钮禁用，只创建一次批次，只下载已选图片；提交失败后回到选择网格，候选和选择状态不丢失。
- 自动完成后不自动下载，顶部显示“提取已完成”，用户点击确认后才进入下载。
- 正在提取或自动完成后已有候选时，重新提取先二次确认；确认后旧候选和选择状态清空，新会话重新加载页面。
- 提交下载中无法重新提取；下载开始后重新提取不影响旧 Worker。
- 返回页面后不继续探测、不创建批次、不下载。

## 已知取舍

- 选择状态只保存在 UI 内存中，页面重建后会按当前数据库候选重新默认全选；这样可以避免新增数据库字段和迁移。
- 文件体积只通过响应头获取，不为了显示体积提前完整下载图片，避免预览阶段消耗过多流量。
- 动图预览以正常播放为目标，不提供暂停、逐帧或动图编辑能力。
- 主动取消的图片不计入失败或过滤数量，确认下载后的批次总数以选中数量为准。
- Android 对“打开某个公共媒体文件夹”没有统一标准，且目标设备文件夹直达体验不稳定；因此当前不再尝试文件夹，只打开相册。相册 bucket 是否生效取决于系统相册实现，不保证一定定位到本批次目录。
- 曾评估“打开第一张成功图片”兜底，但外部相册通常会进入单图查看，返回时直接退出，用户无法继续浏览本批次图片；为了保持 App 简单且避免引入内置图片查看器，最终不采用单图兜底。
- “全部图片已获取”只表示当前 DOM 快照中的相对完整性，不承诺动态网页未来不会继续追加新图片。
- 第二轮触底后仍不完整时会结束探测，因为继续从顶部重试大概率收益有限，且用户可以在最终选择页处理已经获取到的候选。
- 超过 60 秒未首次触底只提示和允许查看进度，不自动失败、不自动停止；用户可继续等待，也可在进度页手动结束提取。
- 网络拦截只作为 DOM 图片的补充来源，不能单独触发自动完成；这样会放弃极少数“图片只经网络请求出现但完全不进 DOM”的自动保存场景，但能避免空白占位图在网页正文未渲染前直接进入选择页。
- 下载记录页为了保护公共目录内容，删除本地图片时只按图片项记录的 URI 或路径逐个删除，不递归删除文件夹；空文件夹可能保留给系统或用户自行管理。
- WebView 补全预览是“尽力而为”的后置缓存更新，不绕过站点登录态和风控；如果页面本身没有暴露 meta、JSON-LD、poster 或可用图片候选，列表仍保持原兜底展示。
- 动图能否在外部相册自动播放仍取决于系统相册对 GIF/Animated WebP/APNG 的支持；本功能保证保存的原始字节、扩展名和媒体类型正确，不内置相册播放器。
- 通用样式后缀去重只处理路径中 `@` 之前已经是图片文件扩展名的 URL；如果站点用查询参数表达签名或权限，查询参数不会被跨站点粗暴删除。
- 多动图页面的候选合并边界：不同原始路径的动图不会互相覆盖；如果极少数站点把 `same.gif@part1`、`same.gif@part2` 设计成两个不同内容而不是样式变体，当前规则会把它们视为同一原图并保留更适合下载的一项。后续遇到真实误合并站点时，应收窄该站点的 `@` 样式识别模式。

## 开放问题

- 后续如果用户希望对大量图片做更快筛选，可以考虑增加按尺寸、类型或文件体积排序/过滤。
- 后续如果预览动图流量过大，可以增加“仅 Wi-Fi 自动播放动图”或“点击后播放”设置。
- 后续可增加“只看已选 / 全部”筛选，帮助大量图片场景快速复核，但本次先保持工具栏轻量。
- 后续可增加疑似重复图提示，例如同去重源、同尺寸或同文件特征的候选标记，当前先依赖用户预览和手动取消。
- 后续可增加按类型或尺寸排序，但需要平衡网页顺序优先的直觉，避免实时提取时图片位置频繁变化。
- 2026-05-14 体验复盘：外部相册打开第一张图片虽然能看到本次下载内容，但多数相册会把它当成单张图片查看，返回时直接退出相册，不能稳定进入“本批次图片列表”上下文。内置批次结果页和图片查看器能解决该体验，但会让 App 变重；当前采用“直接打开相册”的轻量方案。

## 变更记录

- 2026-05-13：新增图片提取完整方案文档，记录现有提取链路和网格选择、底部预览、动图播放、元信息展示的实现计划；原因是图片提取交互从直接下载全部调整为下载前可筛选确认。
- 2026-05-13：完成网格选择、底部可滚动预览、动图播放、图片元信息展示和确认已选下载；原因是用户需要在下载前筛除重复或低质量图片，并能通过尺寸、类型、体积判断保留哪张。
- 2026-05-13：补齐 `ImageExtractVm` 预览元信息探测相关方法、状态字段和缓存字段的简体中文注释；原因是代码注释规范要求私有辅助方法和实体字段也说明职责、边界和取舍，本次无行为变化。
- 2026-05-13：补齐 `ImageExtractPage` 私有 Composable、布局容器、格式化展示函数和 WebView 辅助函数的简体中文注释；原因是 Compose UI 辅助函数同样需要说明 UI 职责、状态输入、用户交互和重组边界，本次无行为变化。
- 2026-05-13：将图片下载完成态和图片结果通知调整为优先打开本批次保存文件夹；原因是当前“打开文件夹”只打开泛化图片入口，不能直接定位到本次下载目录。
- 2026-05-13：完成目录打开工具、图片结果通知协议、MainActivity 一次性目录打开消费和无可用应用 Toast 兜底，并通过 `./gradlew :app:compileDebugKotlin` 验证；原因是需要保证页面和通知的下载完成入口都能复用批次 `outputDir`，且不再误跳视频下载结果页。
- 2026-05-14：修正 DocumentsUI 初始目录 URI 方案，从 tree URI 调整为 document URI，并优先使用主存储卷创建打开目录 Intent；原因是部分系统文件管理器会忽略 tree URI 初始位置，导致仍打开默认文件夹。
- 2026-05-14：移除自动 `ACTION_OPEN_DOCUMENT_TREE` 兜底链路，改为只尝试携带目标目录 URI 的直达入口，再退到相册 bucket/普通相册；原因是目录选择器启动成功但可能仍停在默认位置，会阻断后续更合适的兜底。
- 2026-05-14：修正相册 bucket 兜底 Intent，改为 `setDataAndType` 同时写入 URI 和 MIME；原因是单独设置 type 会清空 data，导致相册仍打开默认图片入口。
- 2026-05-14：完成本次修正并通过 `./gradlew :app:compileDebugKotlin` 验证；原因是需要确认目录直达入口和相册 bucket 兜底调整没有引入编译问题。
- 2026-05-14：计划移除 `image/*` 图片应用选择器兜底，并区分目录直达与相册兜底结果；原因是没有文件管理器可处理目录 URI 时，系统选择器只会显示相册和第三方图片应用，容易让用户误以为仍在选择文件夹应用。
- 2026-05-14：完成 `ImageFolderOpenResult` 结果区分、移除图片应用选择器兜底，并为相册兜底增加明确 Toast；原因是设备没有可用文件管理器入口时，需要诚实提示系统限制，而不是继续弹出相册/第三方图片应用选择器。
- 2026-05-14：通过 `./gradlew :app:compileDebugKotlin` 验证本次兜底调整；原因是新增结果枚举、字符串资源和调用方分支后需要确认 Compose 与资源引用编译正常。
- 2026-05-14：计划增加第一张成功图片 URI 兜底，目录直达失败后打开具体图片文件；原因是用户希望即使系统不支持打开文件夹，也能直接进入本次下载内容，而不是只打开默认相册。
- 2026-05-14：完成第一张成功图片 URI 兜底，并通过 `./gradlew :app:compileDebugKotlin` 验证；原因是部分 Android 设备没有可用文件夹打开入口，打开具体图片比进入默认相册更接近用户查看本次下载内容的目标。
- 2026-05-14：补充 FileProvider 授权路径，旧系统图片保存路径也统一转换为可打开的 `content://` URI；原因是 Android 7+ 会拦截对外暴露 `file://`，需要确保“打开第一张图片”兜底在旧系统上同样可用。
- 2026-05-14：全部图片被过滤时移除“点击打开”入口；原因是该状态没有成功保存图片，继续打开会把用户带到空目录或默认相册，和本次下载结果不匹配。
- 2026-05-14：记录外部相册单图兜底的体验问题，并提出 App 内批次结果页作为后续优化方向；原因是用户反馈返回相册会直接退出，无法稳定浏览本次下载好的图片。
- 2026-05-14：按简单优先原则移除第一张图片兜底和 FileProvider 授权路径，目录直达失败后直接打开相册；原因是用户希望 App 保持轻量，不新增内置图片查看器，同时避免外部相册单图查看返回即退出的割裂体验。
- 2026-05-14：移除文件夹直达尝试，下载完成入口改为直接打开相册；原因是目标设备上文件夹直达仍不稳定，用户明确希望不要再尝试打开文件夹。
- 2026-05-14：开始实现双轮触底滚动、当前 DOM 快照完整性检查、10 秒无候选失败、60 秒长耗时提示、阶段性进度查看和手动结束提取；原因是原固定往返滚动会在知乎等动态页面上下循环到超时，且无法让用户查看长耗时提取进度。
- 2026-05-14：完成双轮滚动、会话隔离候选池、轻量 DOM 进度扫描、首次触底前长耗时提示、进度页和手动结束流程，并通过 `./gradlew :app:compileDebugKotlin` 验证；原因是需要确保探测不会无限上下循环，且长耗时页面可让用户查看和提前使用已获取图片。
- 2026-05-14：修正 `ProbeWebView` 在 Compose 重组后仍调用旧回调的问题；原因是重试或新会话开始后，旧 WebViewClient 闭包可能继续使用上一轮 session，导致网络候选被会话保护误丢弃。
- 2026-05-14：修正网页正文尚未显示时提前进入选择页的问题，要求自动完成必须有 DOM 有效图片证据，并收紧网络拦截图片判断；原因是主文档 Accept 能力声明和页面初始化占位图会被误判为图片候选，导致单张空白图提前落库。
- 2026-05-14：补充下载记录页扩展契约，明确图片记录优先使用 `output_uri` 读取和删除、重新下载克隆旧批次为新批次、批量删除合并系统授权、清空只作用于图片分类、进行中批次先取消 Worker 再删对应批次行；原因是下载记录页需要安全管理图片历史记录，避免误删公共文件夹或整个数据库。
- 2026-05-14：将状态更新为实现中，并补充 WebView 链接预览补全方案；原因是用户明确希望首轮 HTTP 解析失败时先不调整 `LinkMetaParser`，等图片提取 WebView 成功加载网页后再补写预览图等信息，返回列表即可看到补齐后的链接预览。
- 2026-05-14：完成 WebView 链接预览补全实现并将状态更新为已完成，通过 `./gradlew :app:compileDebugKotlin` 验证；原因是图片提取页已能在 WebView 加载和候选收集后补写 `link_previews`，且不改变首轮 `LinkMetaParser` 行为。
- 2026-05-14：开始修复动图下载后在相册中显示为静态图的问题，方案改为下载临时文件后识别真实图片格式，再用真实 MIME 和扩展名发布到 MediaStore；原因是部分站点响应头或 URL 后缀不可靠，错误媒体类型会让外部相册按静态图片处理动图。
- 2026-05-14：完成动图保存格式修复并通过 `./gradlew :app:compileDebugKotlin` 验证；原因是 `DownloadImagesWorker` 已改为基于文件头识别真实图片类型，发布到相册时扩展名和 MediaStore MIME 会与真实文件内容一致。
- 2026-05-14：开始补充 B 站动态页脚本状态图片提取和 BFS 样式图去重优先级；原因是 `b23.tv/ZXCm1f6` 的正文 GIF 原图在 `window.__INITIAL_STATE__` 中，页面渲染层可能提供静态 WebP 预览，导致下载结果仍不能动。
- 2026-05-14：完成 B 站动态页原始 GIF 候选增强并通过 `./gradlew :app:compileDebugKotlin` 验证；原因是用户确认候选图阶段可动，最终修复应聚焦保存阶段优先使用同源原始 GIF，而不是 B 站 BFS 样式转码变体。
- 2026-05-14：开始将动图候选优先级从 B 站特例扩展为通用脚本状态扫描和样式转码 URL 合并；原因是用户明确希望换一个链接也能生效，不能只针对 `b23.tv/ZXCm1f6` 做特殊处理。
- 2026-05-14：完成通用脚本状态图片扫描、内联脚本图片 URL 提取和 `原图@样式后缀` 候选合并，并通过 `./gradlew :app:compileDebugKotlin` 验证；原因是动图保存应覆盖同类页面和其他站点的常见转码预览模式，而不是只依赖 B 站 opus 字段。
- 2026-05-14：补充多动图页面的合并边界说明，并同步更新 `ImageExtractVm` 去重注释；原因是需要明确多个不同动图会分别保留，只有同一原图的样式/转码变体会合并，避免后续维护时误解为只支持单动图。
- 2026-05-17：修正图片提取页标题栏顶部留白过大的布局，并记录标题栏与 `Scaffold` 安全区的边界；原因是页面此前在内容区叠加了 `Scaffold` 默认顶部安全区和 `TitleBar` 自身状态栏间距，视觉上不止一份状态栏高度。
- 2026-05-17：修正图片提取加载态进度条被挤到屏幕底部的问题；原因是后台 `ProbeWebView` 作为 `Column` 子项使用完整屏幕高度，导致前景 `ImageExtractContent` 被排在 WebView 后方，改为透明叠放后前景状态可正常居中。
- 2026-05-18：开始实现图片提取实时选择网格、透明 WebView 触摸屏蔽、自动完成后等待确认下载、重新提取确认和提交失败保留状态；原因是隐藏 WebView 的纯加载态无法让用户理解提取进度，也不能在提取过程中及时筛选图片。
- 2026-05-18：完成图片提取实时选择网格改造并通过 `./gradlew :app:compileDebugKotlin` 验证；原因是实现已改为内存候选实时展示、用户确认后才创建批次下载，透明 WebView 不响应用户触摸，重新提取和提交失败也能保留清晰状态。
- 2026-05-18：调整实时选择页顶部工具栏，将主下载按钮移动到提取提示和数量右侧，重新提取、全选和取消全选保留在下一行；原因是所有按钮同排时空间不足，主操作需要更稳定清晰的位置。
- 2026-05-18：计划补充图片下载诊断日志，覆盖确认下载候选、响应头、临时文件格式识别和 MediaStore 发布结果；原因是需要定位动图下载后在相册中显示为静态图时，到底是候选 URL 已经变成静态预览，还是保存格式/MIME 出现偏差。
- 2026-05-18：完成图片下载诊断日志并通过 `./gradlew :app:compileDebugKotlin` 验证；原因是现在可以从日志中串起选中候选 URL、响应头、文件头识别、临时文件大小、最终文件名/MIME 和 MediaStore URI，便于定位动图静态化发生在哪个阶段。
- 2026-05-18：开始补充知乎动图候选归并和 GIF/WebP 动画标记日志；原因是用户确认提取页预览可动，但相册里显示静态，需要区分下载字节是否仍是动画、是否选中了知乎转码预览地址，以及是否只是外部相册不播放 Animated WebP。
- 2026-05-18：完成知乎 `zhimg.com` 同一 `v2-*` 图片变体归并、候选升级日志和 GIF/WebP 动画标记日志，并通过 `./gradlew :app:compileDebugKotlin` 验证；原因是提取页预览可动但相册静态时，需要让最终 URL 优先选择 GIF/原图候选，并用 `animated=true/false` 判断保存字节和外部相册展示能力。
- 2026-05-18：复查动图从候选、预览、落库、下载到 MediaStore 发布的完整链路，并计划统一图片请求 Accept 与补充 APNG/AVIF 动画诊断；原因是除 URL 选错和相册不播放外，CDN 内容协商差异和非 GIF/WebP 动图也可能表现为下载后静态。
- 2026-05-18：完成图片请求 Accept 统一、APNG/AVIF 动画标记日志和知乎变体优先级收紧，并通过 `./gradlew :app:compileDebugKotlin` 验证；原因是 Worker 需要尽量复用浏览器图片请求语义，同时避免普通 WebP 变体覆盖已经可动的知乎 `.jpg?source` 候选。
- 2026-05-18：确认知乎示例 URL 下载字节为 Animated WebP，计划补充动图时长写入 MediaStore 和 Android 10+ 扫描逻辑修正；原因是本地相册静止不是下载转静态，而是相册播放 Animated WebP 能力有限，保存链路仍应提供更多媒体元数据并消除 `content://` 扫描失败噪声。
- 2026-05-18：完成 GIF/WebP/APNG 动画时长解析、动图 `DURATION` 写入 MediaStore、Android 10+ 发布后跳过无效媒体扫描，并通过 `./gradlew :app:compileDebugKotlin` 验证；原因是保存字节已确认保留动图，需要尽量提升系统相册识别动图的机会，同时避免误把 `content://` 扫描失败当成下载异常。
- 2026-05-18：开始试验 Animated WebP 转 GIF 发布流程；原因是知乎示例保存为 Animated WebP 后目标相册仍不播放，用户希望下载后转成更通用的 GIF 以提升本地相册兼容性。
- 2026-05-18：完成 Animated WebP 转 GIF 的 best-effort 接入并通过 `./gradlew :app:compileDebugKotlin` 验证；原因是系统 API 不能直接导出 Animated WebP 帧，本次通过解析 `ANMF` 帧、逐帧解码并内置 GIF 编码发布 `.gif`，若转码失败则回退原始 Animated WebP。
- 2026-05-18：开始修正 Animated WebP 转 GIF 中 `ALPH + VP8` 帧解码失败的问题；原因是知乎示例从第二帧开始的 `ANMF` payload 带透明通道块，单帧 WebP 封装必须包含 `VP8X` 扩展头并声明 alpha，否则 Android 解码器会拒绝该帧。
- 2026-05-18：完成 `ALPH + VP8` 单帧 WebP 封装修正并通过 `./gradlew :app:compileDebugKotlin` 验证；原因是转码器现在会在帧 payload 含透明块时补写 `VP8X` 扩展头和 alpha 标记，避免知乎 Animated WebP 从第二帧开始解码失败。
- 2026-05-18：放弃知乎示例 Animated WebP 转 GIF 试验并撤回转码发布路径；原因是用户确认浏览器下载该图也表现为静态/转码结果为空白图，继续尝试会增加空白图风险，最终以原始字节保存和日志诊断为准。
- 2026-05-18：修正下载批次状态分支中的可疑缩进；原因是 `curBatch` 早返回判断应与变量声明同级，避免 IDE 误判为上一行表达式延续，也提升后续维护可读性。
- 2026-05-18：开始整理 `DownloadImagesWorker` 职责，将请求头构建、真实格式识别、动图元数据解析、下载内容校验、Cookie 日志和文件名清理拆到 `image/download`、`image/format` 领域包；原因是 Worker 已承载过多工具方法，后续应按“领域 + 职责”放置可复用能力，减少重复实现和全仓搜索成本。
- 2026-05-18：完成 `DownloadImagesWorker` 职责拆分并通过 `./gradlew :app:compileDebugKotlin` 验证；原因是 Worker 现在只负责批次流程、下载编排、发布和通知，图片请求、格式识别、动画元数据和内容校验已迁移到可预测领域包，保持行为等价并提升后续复用性。
- 2026-05-18：继续按代码组织规则优化 `DownloadImagesWorker` 相关类，计划抽出 `ImageTempFileDownloader`、`ImageTempDownloadResult`、`DownloadedTempImage`、`ImageMediaStorePublisher` 和 `ImagePublishResult`；原因是 Worker 上一轮仍直接承担单图网络落盘、异常分类和 MediaStore 发布副作用，本次需要让入口类更专注于批次流程编排。
- 2026-05-18：完成单图临时下载、下载结果、临时图片边界对象、MediaStore 发布和发布结果拆分，并通过 `./gradlew :app:compileDebugKotlin` 验证；原因是现在 Worker 只负责批次读取、并发调度、计数进度、最终状态和通知，网络/格式/校验/发布副作用都已收敛到可预测领域类。
