状态：已完成

# 图片提取功能方案

## 当前状态

图片提取入口位于剪贴板详情页，用户点击“提取图片”后进入图片提取页。页面使用隐藏的 `WebView` 加载目标网页，通过 DOM 扫描脚本、懒加载滚动探测和网络请求拦截合并图片候选，再将候选保存到 Room 的图片提取批次表和图片项表。用户确认后，`DownloadImagesWorker` 从数据库读取本批次图片，按网页顺序并发下载到临时目录，校验过滤无效图片后再发布到相册目录。

当前体验在下载前展示图片网格，所有图片默认选中。用户可以在网格中取消不需要的图片，也可以点击缩略图打开底部弹窗查看大图、动图和元信息，再确认下载已选图片。

## 目标

- 提取完成后以网格展示全部图片候选，默认全选。
- 用户可以手动取消部分图片，再确认下载。
- 点击缩略图打开底部弹窗预览，长图可以上下滑动完整查看。
- 弹窗支持 GIF、Animated WebP 等动图播放。
- 弹窗展示分辨率、文件类型、文件体积，帮助用户在重复图片中选择质量更合适的一张。
- 不新增数据库字段，不引入 Room 迁移；选择状态保存在当前 UI 内存中。

## 范围

- 涉及 `ImageExtractPage`、`ImageExtractVm`、`ImageExtractRepository`、`ImageExtractDao`、图片下载 Worker 的既有数据契约、字符串资源和 Gradle 图片加载依赖。
- 不改变图片候选提取规则、图片内容过滤规则、最终保存路径和通知展示策略。
- 不实现完整相册能力，例如左右切换、双指缩放、暂停动图或逐帧控制。

## 用户体验

1. 用户进入图片提取页后看到提取加载状态。
2. 提取成功后展示图片网格，所有图片默认选中。
3. 顶部显示已选数量和总数，并提供全选、取消全选和确认下载。
4. 缩略图点击打开底部弹窗。弹窗中的图片按宽度等比显示，不裁剪；如果图片很长，用户可以在弹窗内上下滑动查看完整内容。
5. 弹窗显示分辨率、文件类型和文件体积。文件体积依赖服务端响应头，无法获取时显示“未知”。
6. 用户可以在网格或弹窗内切换当前图片是否下载。
7. 确认下载后，未选中的图片从本批次下载列表中移除，剩余图片沿用现有 Worker 下载。

## 最终实现

- `ImageExtractPage` 在提取完成状态下展示 `LazyVerticalGrid`，顶部固定显示已选数量、全选、取消全选和“下载已选图片”。
- `ImageCandidateTile` 使用 Coil 加载缩略图，复选图标独立处理选择动作，缩略图主体点击打开底部预览。
- `ModalBottomSheet` 负责单图预览，图片按宽度等比展示，内容区可纵向滚动，因此长图可以完整查看。
- 预览 ImageLoader 注册 `coil-gif` 解码器：Android 9 及以上使用 `AnimatedImageDecoder`，低版本使用 `GifDecoder`，支持 GIF 和系统可解码的 Animated WebP。
- 预览和缩略图请求都携带 Referer、User-Agent、Cookie，尽量保持预览加载与 Worker 下载一致。
- `ImageExtractVm` 在当前页面内缓存分辨率、文件类型和文件体积，文件体积只通过 HEAD 或 Range GET 响应头尽力获取。
- `ImageExtractRepository.keepSelectedItems` 通过事务删除未选图片并更新批次总数，Worker 继续读取剩余图片项下载。

## 数据流

- `WebView` 加载网页并执行 DOM 图片收集脚本，自动滚动触发懒加载。
- `shouldInterceptRequest` 补充网络层捕获到的图片地址，并保存 Referer、User-Agent、Cookie。
- `ImageExtractVm` 合并 DOM 候选和网络候选，按 DOM 顺序优先去重。
- `ImageExtractRepository.createBatch` 将批次和图片项写入 Room。
- UI 通过 `observeBatch` 观察批次状态，通过图片项 Flow 观察当前批次候选。
- 用户确认下载时，Repository 删除未选中的图片项，并把批次 `total_count` 更新为选中数量。
- `DownloadImagesWorker` 读取剩余图片项，并保持现有临时下载、内容校验、按顺序发布和状态回写逻辑。

## 涉及文件

- `app/src/main/java/com/cla/clip/master/ui/page/image/ImageExtractPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/image/ImageExtractVm.kt`
- `base/general/src/main/java/com/cla/clip/base/general/repository/ImageExtractRepository.kt`
- `base/general/src/main/java/com/cla/clip/base/general/dao/ImageExtractDao.kt`
- `base/general/src/main/res/values/strings.xml`
- `gradle/libs.versions.toml`
- `app/build.gradle.kts`

## 实现步骤

1. 增加图片项观察和确认选择下载的数据层方法。
2. 在 ViewModel 中维护图片元信息缓存，提供确认下载入口。
3. 将提取完成状态从“数量 + 下载全部”改为网格选择界面。
4. 使用支持动图的 Coil `ImageLoader` 加载缩略图和底部预览图，并携带 Referer、User-Agent、Cookie。
5. 通过响应头尽力获取文件类型和文件体积；服务端不返回时显示“未知”。
6. 补充字符串资源和简体中文注释。
7. 编译验证后更新本文档状态和变更记录。

## 测试验证

- 提取完成后网格出现，所有图片默认选中。
- 取消部分图片后确认下载，只下载选中的图片，进度总数和结果数量匹配。
- 全选、取消全选、无选中状态下按钮可用性正确。
- 点击缩略图打开底部预览；普通长图可上下滑动看完整。
- GIF 或 Animated WebP 在弹窗中正常播放，而不是只显示首帧。
- 弹窗中能显示已有分辨率、推断类型；体积获取失败时显示“未知”。
- 在弹窗中切换选中状态后，返回网格同步更新。
- 运行 `./gradlew :app:compileDebugKotlin` 验证编译。

## 已知取舍

- 选择状态只保存在 UI 内存中，页面重建后会按当前数据库候选重新默认全选；这样可以避免新增数据库字段和迁移。
- 文件体积只通过响应头获取，不为了显示体积提前完整下载图片，避免预览阶段消耗过多流量。
- 动图预览以正常播放为目标，不提供暂停、逐帧或动图编辑能力。
- 主动取消的图片不计入失败或过滤数量，确认下载后的批次总数以选中数量为准。

## 开放问题

- 后续如果用户希望对大量图片做更快筛选，可以考虑增加按尺寸、类型或文件体积排序/过滤。
- 后续如果预览动图流量过大，可以增加“仅 Wi-Fi 自动播放动图”或“点击后播放”设置。

## 变更记录

- 2026-05-13：新增图片提取完整方案文档，记录现有提取链路和网格选择、底部预览、动图播放、元信息展示的实现计划；原因是图片提取交互从直接下载全部调整为下载前可筛选确认。
- 2026-05-13：完成网格选择、底部可滚动预览、动图播放、图片元信息展示和确认已选下载；原因是用户需要在下载前筛除重复或低质量图片，并能通过尺寸、类型、体积判断保留哪张。
- 2026-05-13：补齐 `ImageExtractVm` 预览元信息探测相关方法、状态字段和缓存字段的简体中文注释；原因是代码注释规范要求私有辅助方法和实体字段也说明职责、边界和取舍，本次无行为变化。
- 2026-05-13：补齐 `ImageExtractPage` 私有 Composable、布局容器、格式化展示函数和 WebView 辅助函数的简体中文注释；原因是 Compose UI 辅助函数同样需要说明 UI 职责、状态输入、用户交互和重组边界，本次无行为变化。
