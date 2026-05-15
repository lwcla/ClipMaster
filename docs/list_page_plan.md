状态：已完成

# 列表页设计文档

## 当前状态

列表页是首页底部 Tab 的默认一级页面，负责展示本地剪贴记录、进入搜索页、进入详情页，以及承接复制、删除、置顶/取消置顶等剪贴记录操作。当前列表页使用固定一级标题、Shizuku 状态提示、右下角搜索 FloatingActionButton 和共享的 `ClipResultList` 竖向侧滑 item UI。

本文件按当前代码生成，用于指导后续列表页、列表结果 UI、列表分页生命周期和列表交互维护。后续涉及 `ClipListPage`、`ClipListModel`、`ClipResultList`、列表结果复用、列表滚动行为、置顶/删除/复制交互或列表页搜索入口的修改，都必须同步更新本文档。

## 目标

- 首页列表 Tab 展示剪贴记录的主浏览入口。
- 普通列表只展示未折叠剪贴记录，折叠数据从“我的 > 折叠数据”入口管理。
- 使用生命周期感知分页收集，页面可见时工作，离开页面时停止不必要收集。
- 普通列表页和搜索页共用同一套结果 item，避免 UI 与交互分叉。
- 保留稳定的列表滚动体验：切回列表保持位置，重复点击列表 Tab 回到顶部，取消置顶后不跟随被移动 item 跳转。
- 保持剪贴记录操作清晰可点：内容点击进详情，复制常驻，置顶/删除通过左滑露出。

## 范围

- `ClipListPage` 页面结构、生命周期收集、搜索入口、删除弹窗和列表页专属状态。
- `ClipListModel` 首页剪贴记录分页流和剪贴操作委托。
- `ClipResultList` 共享结果列表、空态、加载态、分页 append、卡片展示、侧滑操作、滚动修正。
- 与首页 `MainPage` 的 `LazyListState` 协作：列表 Tab 重复点击回顶、切换 Tab 后保留位置。

不包含：

- 搜索页筛选、搜索查询和关键词高亮的完整方案，详见 `docs/search_page_plan.md`。
- 首页底部 Tab 和一级标题整体方案，详见 `docs/main_page_plan.md`。
- 剪贴数据采集、数据库结构、Shizuku 服务细节和通知/后台监听策略。

## 用户体验

- 顶部显示一级标题“列表”，标题栏不提供返回按钮。
- 标题下方显示 Shizuku 服务不可用提示，列表内容只占用提示下方剩余空间。
- 右下角显示搜索 FloatingActionButton，点击进入搜索页；列表底部预留 96dp，避免最后一条被悬浮按钮遮挡。
- 列表使用单列竖向 item，不再使用瀑布流。
- item 保留卡片、边框和来源色；展示链接、链接标题、预览图、剪贴字符串、来源 App 和时间。
- 剪贴字符串最多展示三行，超出省略。
- item 最右侧常驻复制按钮，点击只触发复制，不进入详情。
- 内容区轻点进入详情，长按回调保留，但当前列表页未启用长按底部弹窗。
- item 向左拖动露出右侧置顶/取消置顶和删除按钮，向右拖动收回；不限制多个 item 同时展开。
- item 在第一段左滑菜单基础上继续左滑时显示“继续滑动折叠数据”，超过阈值松手后折叠该记录并从普通列表移除。
- 复制、置顶/取消置顶、删除按钮宽度统一为 48dp，图标尺寸统一为 24dp，并在各自点击区域内水平和竖向居中。
- 内容区与复制按钮、置顶按钮与删除按钮之间使用 0.5dp 固定高度分割线；分割线高度为按钮图标高度加 10dp，竖向居中，颜色与卡片边框一致。
- 置顶状态角标是卡片级视觉装饰，不响应点击；它位于卡片内部第一级，内容层和复制按钮层绘制在其上方。

## 数据流

- `ClipListModel.pagedClips` 通过 `Pager` 加载 `ClipRepository.loadClips(ClipVisibilityScope.VisibleOnly)`。
- Paging 配置：
  - `pageSize = 20`
  - `prefetchDistance = 5`
  - `enablePlaceholders = false`
- 分页结果在 ViewModel 生命周期内 `cachedIn(CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO))`，避免重组或生命周期恢复时重复创建分页查询。
- `ClipListPage` 使用 `flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)` 包装分页流，再通过 `collectAsLazyPagingItems()` 收集。
- 页面离开 STARTED 状态后，列表页收集会暂停；ViewModel 中缓存的 PagingData 仍保留，供重新进入页面时复用。
- `ClipResultList` 在 `refresh Loading && itemCount == 0` 时展示独立 loading，不组合绑定同一个 `LazyListState` 的空 `LazyColumn`，避免空刷新窗口把滚动状态钳回顶部。

## 页面结构

- `ClipListPage`
  - `TopLevelTitleBar`
  - `ShizukuServiceUnavailableTip`
  - 内容 `Box`
    - `ClipResultList`
    - 搜索 `FloatingActionButton`
    - `DeleteDialog`
    - 预留的长按底部弹窗逻辑
- `ClipResultList`
  - 空状态：`EmptyScreen`
  - 首次 refresh loading：居中 `CircularProgressIndicator`
  - 正常数据：`LazyColumn`
  - append loading：底部小 loading
  - append error：底部可点击重试文本
- `ClipCard`
  - 外层裁剪和侧滑容器
  - 右侧侧滑操作区
  - 可横向拖动的 `ElevatedCard`
  - 卡片级置顶角标装饰层
  - 自定义测量内容区、复制按钮区和分割线

## 交互规则

- 详情：点击 item 内容区调用 `onClick(clip)`，列表页跳转 `DetailRoute(clip.id)`。
- 复制：点击右侧常驻复制按钮调用 `onCopy(clip)`，列表页委托 ViewModel 写入系统剪贴板。
- 删除：左滑后点击删除按钮，列表页先记录 `deleteClip` 并显示 `DeleteDialog`，确认后调用 `viewModel.deleteClip(clip)`。
- 置顶/取消置顶：左滑后点击置顶按钮，调用 `viewModel.updatePinStatus(clip, !clip.isPinned)`。
- 折叠：左滑露出菜单后继续左滑，提示“继续滑动折叠数据”；超过第二段阈值并松手后调用 `viewModel.updateFoldStatus(clip, true)`。
- 侧滑：
  - 只允许向左展开、向右收回。
  - 普通菜单阶段最大偏移等于右侧操作区宽度；存在继续滑动动作时最多额外拖动约 96dp，避免拖出过远。
  - 松手时按操作区一半宽度吸附，短距离误滑自动收回。
  - 第二段继续左滑只在松手且超过阈值时触发，未超过时仍按普通菜单规则吸附。
  - 侧滑状态按 `clip.id` 使用 `rememberSaveable` 保存，避免 LazyColumn 复用导致状态串到其他记录。
- 取消置顶滚动：
  - 取消置顶会触发排序变化，`LazyColumn` 默认会按稳定 key 跟随被移动 item。
  - 当前实现只在取消置顶时记录操作时首个可见 index、offset 和 Paging 快照签名。
  - 等异步数据库/Paging 刷新让快照签名变化后，调用 `requestScrollToItem` 和 `scrollToItem` 恢复原视口，避免列表跟随被取消置顶 item 回到它原来的位置。
  - 该策略锁定的是视口位置，不保证视口中仍是同一条内容。

## 高度和测量

- 卡片内容高度必须由左侧内容区真实测量结果决定。
- 不再使用 `IntrinsicSize.Min + fillMaxHeight` 让按钮反向参与高度测量，避免置顶重排后小 item 继承旧首条大 item 高度。
- `ClipCard` 内部自定义 `Layout` 先按可用宽度测量内容区，再让复制按钮区跟随内容高度。
- 复制分割线按固定高度竖向居中，不拉满卡片高度。
- 右侧侧滑操作区通过外层 `matchParentSize()` 跟随最终 item 高度。

## 生命周期和性能约束

- 列表页数据收集必须保持生命周期感知，优先使用 `flowWithLifecycle`、`collectAsLazyPagingItems` 或等价方式。
- 不应在 ViewModel 初始化时启动与页面可见性无关的重型读取；当前 `Pager` 是冷流，页面收集时才工作。
- 不要为了标题、全选或统计一次性加载全部剪贴记录；面向增长数据集合时继续使用 Paging 或轻量聚合查询。
- 切换到“我的”页时，列表页可被 Pager 释放；不要通过让列表不可见时继续组合/收集来规避滚动问题。
- 如果调整 Paging refresh 空态，不要重新组合绑定同一 `LazyListState` 的空列表，否则可能再次引入切回列表回顶问题。
- 如果调整 item key 或 contentType，必须验证置顶、删除、搜索条件变化、侧滑状态保存和滚动位置。

## 涉及文件

- `app/src/main/java/com/cla/clip/master/ui/page/list/ClipListPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/list/ClipListModel.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/list/ClipResultList.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/list/FoldedClipListPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/list/FoldedClipListModel.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/main/MainPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/search/SearchPage.kt`
- `docs/list_page_plan.md`
- `docs/main_page_plan.md`
- `docs/search_page_plan.md`

## 测试验证

- 编译验证：运行 `./gradlew :app:compileDebugKotlin`。
- 列表进入验证：进入首页列表 Tab，标题、Shizuku 提示、列表和搜索 FAB 显示正常。
- 分页验证：首屏加载、append loading、append error 重试和空列表展示正常。
- Tab 切换验证：列表滚动到中段后切到“我的”再切回“列表”，视口保持；停留在“列表”时再次点击列表 Tab 回到顶部。
- item 展示验证：无链接、仅链接、仅标题、仅预览图、长剪贴字符串、置顶 item、不同来源色 item 都能正确展示。
- 点击验证：内容点击进入详情，复制只复制，删除弹窗确认后删除，置顶/取消置顶调用正确。
- 侧滑验证：多个 item 可同时展开，左滑露出操作，右滑收回，最大偏移不超过操作区宽度。
- 折叠验证：继续左滑出现折叠提示，超过阈值松手后该记录从普通列表消失，并可在折叠数据页看到。
- 高度验证：高 item 后面置顶矮 item、取消置顶矮 item，都应按自身内容包裹高度显示。
- 取消置顶滚动验证：取消置顶当前可见 item 后，列表视口停留在操作时的位置，不跟随该 item 回到原排序位置。

## 已知取舍

- 取消置顶后恢复的是操作时的 index/offset 视口，不保证继续显示同一条内容；这是为了满足“视口不动”的体验。
- 如果取消置顶同时发生新增、删除或后台刷新，恢复视口可能有轻微偏差，但会通过 index 边界保护避免崩溃。
- 右下角搜索入口当前保留 FloatingActionButton；是否移入标题栏右侧仍是开放问题。
- 长按底部弹窗逻辑保留但当前未启用；后续如果恢复长按操作，需要重新评估和侧滑、详情点击的冲突。
- 共享 `ClipResultList` 同时服务列表页和搜索页，列表页调整可能影响搜索结果展示；改动前必须同步检查 `docs/search_page_plan.md`。
- 共享 `ClipResultList` 也服务折叠列表页，侧滑第二段动作由页面传入，普通列表折叠，折叠列表取消折叠。

## 开放问题

- 是否将搜索入口从右下角 FAB 调整到标题栏右侧。
- 是否恢复长按底部弹窗，或改为更明确的更多操作入口。
- 是否为取消置顶后的视口恢复增加动画抑制或更细粒度的目标 item 判断，以减少极端情况下的轻微回弹。
- 是否为侧滑展开状态提供批量收起入口或页面切换自动收起策略。

## 收尾检查

- 列表页、共享结果 item、列表分页、滚动行为、置顶/删除/复制交互发生变化时，必须更新本文档。
- 如果改动影响搜索结果复用 UI，也必须同步检查并更新 `docs/search_page_plan.md`。
- 如果改动影响首页 Tab 行为或回顶逻辑，也必须同步检查并更新 `docs/main_page_plan.md`。
- 完成代码改动前后都要确认是否需要补充中文注释和字符串资源。

## 变更记录

- 2026-05-15：新增列表页设计文档；原因是列表页已有较多分页、生命周期、侧滑 item、滚动修正和搜索复用约束，需要独立文档指导后续开发。
- 2026-05-15：补充折叠数据交互和普通列表隐藏折叠数据的实现记录；原因是剪贴记录新增折叠状态，普通列表查询改为仅加载未折叠数据，共享侧滑 item 增加第二段继续左滑折叠动作。
