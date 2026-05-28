状态：已完成

# 公共内容卡片组件方案

## 当前状态

当前应用内主要内容卡片由 `ClipMasterCard` / `ClipMasterGestureCard` 统一承载外壳绘制、圆角、边框、阴影和触摸裁剪。本轮 UI 刷新后，卡片圆角、阴影和默认边框继续由主题 token 与 `ClipMasterCardDefaults` 统一维护，避免页面继续沿用默认模板或硬编码视觉。

剪贴列表、回收站、折叠页和搜索结果都通过 `ClipResultList` 复用同一套 `ClipCard`，但 `ClipCard` 还承载来源 App 色边框、快捷动作区底色、按压分区、侧滑菜单和继续右滑动作，公共组件不能覆盖这些业务状态色和交互规则。

## 目标

- 抽象一个薄的公共内容卡片外壳组件，而不是只抽圆角、阴影等零散属性。
- 统一主要内容卡片的圆角、阴影、边框绘制位置和触摸裁剪契约，避免水波纹或按压态再次越过圆角。
- 允许调用方继续传入业务颜色、手势和内容布局，避免公共组件变成理解剪贴、下载、回收站等业务语义的万能卡片。
- 使用 `ui/theme` token 管理卡片圆角、默认阴影、描边和内容节奏，后续调整全局质感时不需要逐页改样式。

## 范围

- 迁移我的页面入口卡片和可展开设置卡片。
- 迁移下载记录页视频记录卡片和图片批次卡片。
- 迁移 `ClipResultList` 内 `ClipCard`，从而覆盖剪贴列表、回收站、折叠页面、普通搜索结果和折叠搜索结果。
- 迁移详情页加载、错误和正文内容卡片。
- 下载记录页缩略图、视频首帧框、删除占位块、Chip、按钮、弹窗和图片缩略图暂不迁移。

## 用户体验

- 我的页面卡片跟随固定品牌主题和共享 token，入口卡片保持舒展但不过重的工具型质感。
- 下载记录页内容卡片在点击、长按和多选切换时，触摸反馈限制在圆角范围内。
- 剪贴列表相关页面统一采用我的页面卡片外壳效果，但来源色边框、快捷动作区域颜色、按压反馈色和侧滑行为保持现状；侧滑菜单和继续滑动提示自身保持圆角裁剪，item 外层不再裁剪公共卡片阴影。

## 数据流

公共卡片组件只位于 Compose UI 层，不新增业务状态、数据库字段或字符串资源。页面仍然负责传入点击、长按、侧滑和导航回调；公共组件只负责外壳绘制、触摸裁剪和内容槽位。

## 涉及文件

- `app/src/main/java/com/cla/clip/master/ui/widget/ClipMasterCard.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/mine/MinePage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/download/DownloadHistoryPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/widget/clip/ClipResultList.kt`
- `docs/shared_card_component_plan.md`
- `docs/mine_page_plan.md`
- `docs/download_history_plan.md`
- `docs/clip_result_list_plan.md`
- `docs/list_page_plan.md`
- `docs/search_page_plan.md`
- `docs/folded_clip_plan.md`
- `docs/recycle_bin_plan.md`

## 实现步骤

1. 新增 `ClipMasterCardDefaults`，集中维护默认 shape、elevation、边框宽度、默认边框色和默认内容内边距；继续以现有 `cardCornerShape` 作为默认 shape 来源。
2. 新增普通点击入口 `ClipMasterCard`，用于只需要点击/长按的内容卡片；点击/长按反馈放在圆角裁剪后的内部层。
3. 新增自定义手势入口 `ClipMasterGestureCard`，用于 `ClipCard` 这类需要侧滑和自定义按压分区的复杂卡片；该入口不额外挂 `clickable` 或 `combinedClickable`。
4. 组件内容槽向调用方暴露同一个 `shape`，让内部 Canvas、边框和裁剪可以与外层阴影保持一致。
5. 组件不负责外边距，页面继续通过 `modifier.padding(...)` 控制列表或页面间距；组件仅提供可配置的内容内边距，并支持 0dp 内容内边距。
6. 迁移我的页面、下载记录页和 `ClipResultList` 内容卡片，保留剪贴列表原有状态颜色。

## 测试验证

- 运行 `.\gradlew.bat :app:compileDebugKotlin`。
- 手动验证下载记录页视频和图片记录卡片的点击、长按反馈均被圆角裁剪。
- 手动验证我的页面入口卡片和可展开设置卡片视觉不变。
- 手动验证剪贴列表、回收站、折叠页和搜索结果的卡片外壳统一，公共阴影可见，来源色边框、快捷动作区颜色、按压色、侧滑和长按行为不变。
- 手动验证详情页加载、错误和正文内容卡片视觉统一，正文滚动和底部按钮行为不变。
- 搜索主要内容卡片实现，确认新增内容卡片优先复用公共组件，不再重复手写卡片外壳。

## 已知取舍

- 不只抽属性，因为方角触摸问题来自点击/长按挂载位置，单纯共享 shape、elevation 无法阻止后续再次写出方角反馈。
- 不抽万能业务卡片，公共组件不理解剪贴、下载、回收站、选中态或快捷动作语义，只提供外壳契约。
- `ClipResultList` 不能在整个 item 外层做圆角裁剪，否则会裁掉 `ClipMasterGestureCard` 的阴影；需要只裁剪侧滑菜单、继续滑动提示和卡片内部触摸反馈。
- 详情页只迁移普通内容卡片外壳，正文滚动、链接提示和底部操作按钮仍由详情页维护，避免公共卡片组件理解详情页业务。

## 开放问题

- 后续新增新的主要内容卡片时，是否需要为不同页面密度增加命名变体，暂时先通过 `contentPadding` 和调用方布局控制。

## 变更记录

- 2026-05-27：公共卡片外壳接入固定品牌主题 token，并将默认阴影降低为更克制的层次；原因是全量 UI 刷新需要主要内容卡片统一、安静且适合工具型列表长时间阅读。
- 2026-05-17：补充剪贴结果列表侧滑层裁剪约束；原因是整个 item 外层裁剪会遮掉公共卡片阴影，最终采用“外层不裁剪、菜单和提示层单独裁剪”的实现。
- 2026-05-17：完成 `ClipMasterCard` / `ClipMasterGestureCard` 公共外壳落地，并接入我的页面、下载记录页和 `ClipResultList`；原因是代码已统一主要内容卡片外壳并通过编译验证，剪贴 item 业务状态色保持由 `ClipResultList` 维护。
- 2026-05-18：详情页加载、错误和正文内容卡片接入 `ClipMasterCard`；原因是详情页普通内容卡片也属于主要内容外壳，适合复用公共卡片组件，页面业务按钮和正文滚动保持不变。
- 2026-05-17：新增公共内容卡片组件方案并标记为实现中；原因是需要统一应用内主要内容卡片效果，同时修复下载记录页卡片触摸反馈方角问题。
