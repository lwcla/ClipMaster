状态：已完成

# 图片提取功能方案

## 当前状态

图片提取入口位于剪贴板详情页，用户点击“提取图片”后进入图片提取页。页面使用隐藏的 `WebView` 加载目标网页，通过 DOM 扫描脚本、懒加载滚动探测和网络请求拦截合并图片候选，再将候选保存到 Room 的图片提取批次表和图片项表。用户确认后，`DownloadImagesWorker` 从数据库读取本批次图片，按网页顺序并发下载到临时目录，校验过滤无效图片后再发布到相册目录，并把本批次输出目录记录到批次 `outputDir`。

当前体验在下载前展示图片网格，所有图片默认选中。用户可以在网格中取消不需要的图片，也可以点击缩略图打开底部弹窗查看大图、动图和元信息，再确认下载已选图片。

## 目标

- 提取完成后以网格展示全部图片候选，默认全选。
- 用户可以手动取消部分图片，再确认下载。
- 点击缩略图打开底部弹窗预览，长图可以上下滑动完整查看。
- 弹窗支持 GIF、Animated WebP 等动图播放。
- 弹窗展示分辨率、文件类型、文件体积，帮助用户在重复图片中选择质量更合适的一张。
- 下载完成或部分完成后，完成态点击入口直接打开系统相册，保持 App 轻量并避免文件夹直达在不同系统上的不稳定体验。
- 不新增数据库字段，不引入 Room 迁移；选择状态保存在当前 UI 内存中。

## 范围

- 涉及 `ImageExtractPage`、`ImageExtractVm`、`ImageExtractRepository`、`ImageExtractDao`、图片下载 Worker 的既有数据契约、字符串资源和 Gradle 图片加载依赖。
- 不改变图片候选提取规则、图片内容过滤规则和最终保存路径。
- 调整下载完成后的打开入口：页面完成态和图片结果通知都直接打开系统相册；`outputDir` 仅用于尝试携带相册 bucket 定位信息，不再尝试文件管理器或 DocumentsUI。
- 不实现完整相册能力，例如左右切换、双指缩放、暂停动图或逐帧控制。

## 用户体验

1. 用户进入图片提取页后看到提取加载状态。
2. 提取成功后展示图片网格，所有图片默认选中。
3. 顶部显示已选数量和总数，并提供全选、取消全选和确认下载。
4. 缩略图点击打开底部弹窗。弹窗中的图片按宽度等比显示，不裁剪；如果图片很长，用户可以在弹窗内上下滑动查看完整内容。
5. 弹窗显示分辨率、文件类型和文件体积。文件体积依赖服务端响应头，无法获取时显示“未知”。
6. 用户可以在网格或弹窗内切换当前图片是否下载。
7. 确认下载后，未选中的图片从本批次下载列表中移除，剩余图片沿用现有 Worker 下载。
8. 下载完成后，用户点击完成态文案会直接打开系统相册；如果相册支持 bucket 参数，会尽量定位到 `clipMaster/<本次网页标题目录>` 对应相册。
9. 点击图片下载结果通知时，同样直接打开相册；如果没有任何图片成功保存，则不携带具体目录，避免误导用户进入空目录。

## 最终实现

- `ImageExtractPage` 在提取完成状态下展示 `LazyVerticalGrid`，顶部固定显示已选数量、全选、取消全选和“下载已选图片”。
- `ImageCandidateTile` 使用 Coil 加载缩略图，复选图标独立处理选择动作，缩略图主体点击打开底部预览。
- `ModalBottomSheet` 负责单图预览，图片按宽度等比展示，内容区可纵向滚动，因此长图可以完整查看。
- 预览 ImageLoader 注册 `coil-gif` 解码器：Android 9 及以上使用 `AnimatedImageDecoder`，低版本使用 `GifDecoder`，支持 GIF 和系统可解码的 Animated WebP。
- 预览和缩略图请求都携带 Referer、User-Agent、Cookie，尽量保持预览加载与 Worker 下载一致。
- `ImageExtractVm` 在当前页面内缓存分辨率、文件类型和文件体积，文件体积只通过 HEAD 或 Range GET 响应头尽力获取。
- `ImageExtractRepository.keepSelectedItems` 通过事务删除未选图片并更新批次总数，Worker 继续读取剩余图片项下载。
- `ImageFolderOpenHelper` 统一处理图片结果查看入口，页面完成态和图片下载结果通知都复用它；工具不再尝试 DocumentsUI、文件管理器或目录 URI，只尝试相册 bucket 和普通相册入口，并通过 `ImageFolderOpenResult` 告知调用方是否成功打开相册。
- 图片下载结果通知使用 `TARGET_IMAGE_FOLDER` 和 `ImageFolderOpenData`，不再复用视频下载结果页跳转；没有成功保存图片时不会携带具体目录，避免打开空目录。
- 页面和通知只在没有相册可用时展示失败提示；成功打开相册时不额外 Toast，避免打扰用户。

## 数据流

- `WebView` 加载网页并执行 DOM 图片收集脚本，自动滚动触发懒加载。
- `shouldInterceptRequest` 补充网络层捕获到的图片地址，并保存 Referer、User-Agent、Cookie。
- `ImageExtractVm` 合并 DOM 候选和网络候选，按 DOM 顺序优先去重。
- `ImageExtractRepository.createBatch` 将批次和图片项写入 Room。
- UI 通过 `observeBatch` 观察批次状态，通过图片项 Flow 观察当前批次候选。
- 用户确认下载时，Repository 删除未选中的图片项，并把批次 `total_count` 更新为选中数量。
- `DownloadImagesWorker` 读取剩余图片项，并保持现有临时下载、内容校验、按顺序发布和状态回写逻辑。
- 结果通知点击进入 `MainActivity` 后，由 `MainVm` 一次性消费 `ImageFolderOpenData`，再调用 `ImageFolderOpenHelper` 打开相册入口。
- `DownloadImagesWorker` 发布成功后只把批次 `outputDir` 交给通知入口；打开工具直接进入相册，`outputDir` 仅用于尝试相册 bucket 定位。

## 涉及文件

- `app/src/main/java/com/cla/clip/master/ui/page/image/ImageExtractPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/image/ImageExtractVm.kt`
- `base/general/src/main/java/com/cla/clip/base/general/repository/ImageExtractRepository.kt`
- `base/general/src/main/java/com/cla/clip/base/general/dao/ImageExtractDao.kt`
- `base/general/src/main/java/com/cla/clip/base/general/utils/FileUtils.kt`
- `base/general/src/main/res/values/strings.xml`
- `app/src/main/java/com/cla/clip/master/MainActivity.kt`
- `app/src/main/java/com/cla/clip/master/MainVm.kt`
- `app/src/main/java/com/cla/clip/master/entity/ImageFolderOpenData.kt`
- `app/src/main/java/com/cla/clip/master/utils/ImageFolderOpenHelper.kt`
- `app/src/main/java/com/cla/clip/master/utils/NotificationHelper.kt`
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

## 测试验证

- 提取完成后网格出现，所有图片默认选中。
- 取消部分图片后确认下载，只下载选中的图片，进度总数和结果数量匹配。
- 全选、取消全选、无选中状态下按钮可用性正确。
- 点击缩略图打开底部预览；普通长图可上下滑动看完整。
- GIF 或 Animated WebP 在弹窗中正常播放，而不是只显示首帧。
- 弹窗中能显示已有分辨率、推断类型；体积获取失败时显示“未知”。
- 在弹窗中切换选中状态后，返回网格同步更新。
- 下载完成态点击后直接进入系统相册，不再尝试打开文件夹。
- 全部图片被过滤且没有成功保存图片时，页面只展示过滤结果，不展示“点击打开”，避免进入空目录或默认相册。
- 图片下载结果通知点击后不再进入视频下载页，而是直接打开相册入口。
- 运行 `./gradlew :app:compileDebugKotlin` 验证编译。

## 已知取舍

- 选择状态只保存在 UI 内存中，页面重建后会按当前数据库候选重新默认全选；这样可以避免新增数据库字段和迁移。
- 文件体积只通过响应头获取，不为了显示体积提前完整下载图片，避免预览阶段消耗过多流量。
- 动图预览以正常播放为目标，不提供暂停、逐帧或动图编辑能力。
- 主动取消的图片不计入失败或过滤数量，确认下载后的批次总数以选中数量为准。
- Android 对“打开某个公共媒体文件夹”没有统一标准，且目标设备文件夹直达体验不稳定；因此当前不再尝试文件夹，只打开相册。相册 bucket 是否生效取决于系统相册实现，不保证一定定位到本批次目录。
- 曾评估“打开第一张成功图片”兜底，但外部相册通常会进入单图查看，返回时直接退出，用户无法继续浏览本批次图片；为了保持 App 简单且避免引入内置图片查看器，最终不采用单图兜底。

## 开放问题

- 后续如果用户希望对大量图片做更快筛选，可以考虑增加按尺寸、类型或文件体积排序/过滤。
- 后续如果预览动图流量过大，可以增加“仅 Wi-Fi 自动播放动图”或“点击后播放”设置。
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
