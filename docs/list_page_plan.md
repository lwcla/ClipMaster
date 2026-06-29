状态：已完成

# 列表页设计文档

## 当前状态

列表页是首页底部 Tab 的默认一级页面，负责展示本地未折叠且未进入回收站的剪贴记录、进入搜索页、进入详情页，以及承接复制、删除、置顶/取消置顶、折叠和普通列表多选批量操作。当前列表页使用带多选底部栏的页面骨架、固定一级标题、标题栏右侧搜索入口、Shizuku 状态提示，并通过共享 `ClipResultList` 渲染剪贴结果。第一版多选仅提供“删除”和“折叠”：长按 item 进入多选并选中当前记录，多选态点击 item 只切换选中，返回键或取消按钮退出多选。

本文件用于指导列表页页面结构、分页生命周期、普通列表数据范围、搜索入口、删除弹窗和首页 Tab 协作。`ClipResultList` 的卡片结构、按钮布局、侧滑方向、动画阈值、测量、关键词高亮和通用测试验证以 `docs/clip_result_list_plan.md` 为主文档；本文只记录列表页如何使用共享组件以及普通列表的页面级差异。

## 目标

- 首页列表 Tab 展示剪贴记录的主浏览入口。
- 普通列表只展示未折叠剪贴记录，折叠数据从“我的 > 折叠数据”入口管理。
- 普通列表不展示回收站数据，删除弹窗提供移入回收站和彻底删除两个动作，完整方案见 `docs/recycle_bin_plan.md`。
- 使用生命周期感知分页收集，页面可见时工作，离开页面时停止不必要收集。
- 通过共享 `ClipResultList` 展示剪贴结果，组件细节由 `docs/clip_result_list_plan.md` 维护。
- 保留稳定的列表滚动体验：切回列表保持位置，重复点击列表 Tab 回到顶部，取消置顶后不跟随被移动 item 跳转。
- 保持列表页页面级交互清晰：点击内容进详情，标题栏右侧进入搜索，重复点击列表 Tab 回到顶部；单条记录操作由共享组件回调给页面处理。
- 支持普通列表第一版多选：底部操作栏只放批量删除和批量折叠，不提供全选或复制合并；0 选中可以停留但按钮禁用。

## 范围

- `ClipListPage` 页面结构、生命周期收集、搜索入口、删除弹窗和列表页专属状态。
- `ClipListModel` 首页剪贴记录分页流和剪贴操作委托。
- `ClipResultList` 的页面接入：传入普通列表分页数据、空态文案、关键词空值和折叠动作回调。
- 与首页 `MainPage` 的 `LazyListState` 协作：列表 Tab 重复点击回顶、切换 Tab 后保留位置。

不包含：

- 搜索页筛选、搜索查询和关键词高亮的完整方案，详见 `docs/search_page_plan.md`。
- 剪贴结果 item 的共享 UI、侧滑、动画、测量和滚动修正，详见 `docs/clip_result_list_plan.md`。
- 首页底部 Tab 和一级标题整体方案，详见 `docs/main_page_plan.md`。
- 剪贴数据采集、数据库结构、Shizuku 服务细节和通知/后台监听策略。

## 用户体验

- 顶部显示一级标题“列表”，标题栏不提供返回按钮。
- 标题下方显示 Shizuku 服务不可用提示，列表内容只占用提示下方剩余空间。
- 标题栏右侧显示搜索图标按钮，点击进入搜索页；首页列表底部保留一层轻量留白，让最后一条记录和底部导航之间有明确但不过分的呼吸感，不再出现明显空白带。
- 列表内容复用 `ClipResultList` 单列竖向 item，具体卡片展示、斜向快捷动作区、右滑菜单和第二段滑动动画见 `docs/clip_result_list_plan.md`。
- 普通列表传入 `ClipVisibilityScope.VisibleOnly` 数据，只展示未折叠记录。
- 普通列表的第二段继续右滑语义为折叠数据，提示“继续右滑折叠数据”；触发后调用 `viewModel.updateFoldStatus(clip, true)`。
- 普通列表读取“我的”页保存的快捷动作设置；当设置不是“无（整卡进入详情）”时启用左下斜向快捷动作区并显示同源极浅三角背景，快捷动作区执行复制、置顶/取消置顶、删除或折叠，其余区域进入详情。
- 设置为“无（整卡进入详情）”时，普通列表不显示斜向背景、不启用快捷动作区，整卡点击进入详情；右滑菜单仍保留复制、置顶/取消置顶和删除。
- item 长按进入多选并选中当前记录；多选态标题栏显示已选数量和取消入口，底部批量操作栏显示“折叠”和“删除”。
- 多选态点击 item 只切换选中，不进入详情；0 选中允许停留，但“折叠”和“删除”按钮禁用。
- 多选态临时关闭斜向快捷动作、右滑菜单和第二段右滑；进入多选时已展开的右滑菜单会收起。
- 多选态底部操作栏出现时，普通列表额外增加底部 padding，避免最后几条记录被批量操作栏遮挡。
- 批量删除复用统一删除选择弹窗，但批量弹窗只展示已选数量，不展示剪贴内容；用户可选择移入回收站或彻底删除。
- 批量折叠直接执行，不二次确认；成功后退出多选并 Toast 提示“已折叠 N 条”，实际处理数量为 0 时 Toast 提示“没有可处理的剪贴”。

## 数据流

- `ClipListModel.pagedClips` 通过 `Pager` 加载 `ClipRepository.loadClips(ClipVisibilityScope.VisibleOnly)`。
- Paging 配置：
  - `pageSize = 20`
  - `prefetchDistance = 5`
  - `enablePlaceholders = false`
- 分页结果在 ViewModel 生命周期内 `cachedIn(CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO))`，避免重组或生命周期恢复时重复创建分页查询。
- `ClipListPage` 使用 `flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)` 包装分页流，再通过 `collectAsLazyPagingItems()` 收集。
- 页面离开 STARTED 状态后，列表页收集会暂停；ViewModel 中缓存的 PagingData 仍保留，供重新进入页面时复用。
- 普通列表 DAO 排序在置顶和剪贴时间之后追加 `id DESC` 作为唯一兜底，保证同毫秒数据跨 Paging 页加载时顺序稳定，不让共享列表收到重复 `clip.id` key。
- `ClipResultList` 的 refresh 空态、append 状态和 item 测量策略见 `docs/clip_result_list_plan.md`。

## 页面结构

- `ClipListPage`
  - `Scaffold`
  - 普通标题栏或多选标题栏
  - `ShizukuServiceUnavailableTip`
  - 内容 `Box`
    - `ClipResultList`
    - 单条删除选择弹窗
    - 批量删除选择弹窗
  - 多选底部操作栏
- `ClipResultList`
  - 共享剪贴结果列表组件，内部结构详见 `docs/clip_result_list_plan.md`

## 交互规则

共享组件回调在列表页中的语义：

- 详情：`onClick` 跳转 `DetailRoute(clip.id)`。
- 复制：`onCopy` 委托 ViewModel 写入系统剪贴板。
- 删除：`onDelete` 先记录 `deleteClip` 并显示 `ClipDeleteChoiceDialog`；移入回收站调用 `viewModel.deleteClip(clip)`，彻底删除调用 `viewModel.deleteClipPermanently(clip)`。
- 置顶/取消置顶：`onPinToggle` 调用 `viewModel.updatePinStatus(clip, !clip.isPinned)`。
- 折叠：`onSwipePastAction` 调用 `viewModel.updateFoldStatus(clip, true)`。
- 长按：调用页面的 `enterSelection(clip)`，进入多选并选中当前记录。
- 多选点击：调用页面的 `toggleSelection(clip)`，只改变 `selectedIds`。
- 批量删除：底部栏删除按钮打开 `ClipBatchDeleteChoiceDialog`；移入回收站调用 `viewModel.moveClipsToRecycleBin(ids)`，彻底删除调用 `viewModel.deleteClipsPermanently(ids)`，完成后退出多选。
- 批量折叠：底部栏折叠按钮调用 `viewModel.foldVisibleClips(ids)`，完成后退出多选。

组件自身的右滑菜单、第二段滑动、取消置顶滚动修正、高度和测量规则见 `docs/clip_result_list_plan.md`。

## 生命周期和性能约束

- 列表页数据收集必须保持生命周期感知，优先使用 `flowWithLifecycle`、`collectAsLazyPagingItems` 或等价方式。
- 不应在 ViewModel 初始化时启动与页面可见性无关的重型读取；当前 `Pager` 是冷流，页面收集时才工作。
- 不要为了标题、全选或统计一次性加载全部剪贴记录；面向增长数据集合时继续使用 Paging 或轻量聚合查询。
- 多选态只保存选中 id 集合，不为了保持选择而加载完整实体；普通 Paging 刷新不强制清空选择，避免加载中导致选择闪没。
- 批量动作执行中禁用底部按钮，避免连点、重复弹窗或重复写库；操作完成、返回退出和取消按钮会统一清空选择。
- 切换到“我的”页时，列表页可被 Pager 释放；不要通过让列表不可见时继续组合/收集来规避滚动问题。
- 如果调整共享组件 refresh 空态、item key、contentType 或侧滑状态保存方式，必须同步更新并验证 `docs/clip_result_list_plan.md` 中列出的组件约束。

## 涉及文件

- `app/src/main/java/com/cla/clip/master/ui/page/list/ClipListPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/list/ClipListModel.kt`
- `app/src/main/java/com/cla/clip/master/ui/widget/clip/ClipResultList.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/main/MainPage.kt`
- `docs/list_page_plan.md`
- `docs/clip_result_list_plan.md`
- `docs/main_page_plan.md`
- `docs/search_page_plan.md`

## 测试验证

- 编译验证：运行 `./gradlew :app:compileDebugKotlin`。
- 批量折叠数据层验证：运行批量折叠 DAO/Repository 相关测试，覆盖空 id、无效 id、重复 id、同批次 `foldedAt` 一致、已折叠/已删除记录不被更新、返回实际更新数量。
- 列表进入验证：进入首页列表 Tab，标题、Shizuku 提示、列表和标题栏搜索按钮显示正常。
- 分页验证：首屏加载、append loading、append error 重试和空列表展示正常。
- Tab 切换验证：列表滚动到中段后切到“我的”再切回“列表”，视口保持；停留在“列表”时再次点击列表 Tab 回到顶部。
- 共享 item 展示、点击、侧滑、折叠离场、高度和取消置顶滚动验证见 `docs/clip_result_list_plan.md`。
- 列表页差异验证：普通列表只展示未折叠数据；继续右滑折叠后记录从普通列表移除，并可在折叠数据页看到。
- 列表页多选验证：长按进入多选、点击切换选择、0 选中按钮禁用、执行中按钮禁用、返回退出、底部栏不遮挡最后 item、多选态禁用详情/斜向快捷/右滑、进入多选收起展开菜单、批量删除两种路径、批量删除弹窗不展示内容、批量折叠 Toast 和 0 返回兜底 Toast。
- Pager 手势验证：item 未展开时列表页左滑可以切到“我的”，item 已展开时左滑优先收回菜单。

## 已知取舍

- 搜索入口已移入标题栏右侧，避免悬浮按钮遮挡列表底部内容。
- 长按已改为进入多选，不再保留旧的长按内容底部弹窗；后续如需恢复内容预览或更多操作，需要重新设计与多选入口的优先级。
- 共享 `ClipResultList` 同时服务列表页、搜索页和折叠列表页，组件级改动必须优先更新 `docs/clip_result_list_plan.md`。
- 第一版不做全选和复制合并，避免一次性加载全量数据、超长剪贴板和隐私边界在同一轮混入。

## 开放问题

- 是否在后续加入全选、复制合并或更多批量动作；需要单独确认全量统计、剪贴板长度、分隔符和隐私边界。

## 收尾检查

- 列表页页面结构、分页生命周期、普通列表数据范围、搜索入口、删除弹窗或首页 Tab 协作发生变化时，必须更新本文档。
- 如果改动影响 `ClipResultList` 共享结果 item，必须优先更新 `docs/clip_result_list_plan.md`，页面文档只保留影响摘要和差异说明。
- 如果改动影响首页 Tab 行为或回顶逻辑，也必须同步检查并更新 `docs/main_page_plan.md`。
- 完成代码改动前后都要确认是否需要补充中文注释和字符串资源。

## 本次同步

- 普通列表内容卡片通过 `ClipResultList` 接入 `ClipMasterGestureCard` 公共内容卡片外壳；本页面只记录接入影响，卡片外壳规则以 `docs/shared_card_component_plan.md` 为准，剪贴 item 状态色和交互规则以 `docs/clip_result_list_plan.md` 为准。
- 普通列表接入共享多选态：长按进入多选，底部栏提供批量删除和批量折叠；批量折叠通过 Repository/DAO 批量 SQL 更新，不循环调用单条折叠。
- 本轮不新增运行日志；禁止记录选中 id 列表、剪贴内容或批量弹窗内容，诊断以 Toast、测试、编译和人工交互验证为主。

## 变更记录

- 2026-06-29：新增普通列表多选批量删除和批量折叠接入记录；原因是第一版多选只服务普通列表和普通搜索，列表页需要记录长按入口、底部栏、批量删除弹窗、批量折叠直接执行和日志取舍。
- 2026-05-28：按确认结果恢复首页列表 12dp 底部留白；原因是 8dp 在当前视觉密度下仍显得过紧，12dp 更符合本轮统一后的列表结尾节奏。
- 2026-05-28：将首页列表底部留白继续收细；原因是 12dp 在部分页面观感上仍偏大，需要和其他结果列表一起收口到更克制的统一节奏。
- 2026-05-28：将首页列表底部留白从完全贴底回调为轻量间隔；原因是最后一条记录与底部导航顶边完全贴合后观感过硬，需要保留更克制的视觉呼吸感。
- 2026-05-28：移除首页列表传给共享结果列表的额外底部留白；原因是首页底部导航贴底后，列表仍保留 24dp 页面私有 `contentPadding.bottom`，会让最后一条记录从导航栏上方一段距离才开始滚出。
- 2026-05-27：列表页接入 `TopLevelPageScaffold`，搜索入口从右下角 FAB 移入标题栏右侧；原因是本轮 UI 刷新要求减少悬浮控件噪音，并统一一级页标题栏动作入口。
- 2026-05-26：补充普通列表分页查询的 `id DESC` 稳定排序契约；原因是同时间戳剪贴记录需要唯一顺序，避免 Paging 边界重复导致共享 LazyColumn key 冲突。
- 2026-05-17：记录普通列表内容卡片经由 `ClipResultList` 接入公共内容卡片外壳；原因是列表页复用共享剪贴 item，主要内容卡片外壳需与我的页面卡片效果保持一致。
- 2026-05-18：将 `ClipResultList` 引用更新为 `ui/widget/clip` 共享组件路径；原因是剪贴结果列表已跨列表、搜索、折叠和回收站复用，列表页文档只记录接入差异。
- 2026-05-15：新增列表页设计文档；原因是列表页已有较多分页、生命周期、侧滑 item、滚动修正和搜索复用约束，需要独立文档指导后续开发。
- 2026-05-15：补充折叠数据交互和普通列表隐藏折叠数据的实现记录；原因是剪贴记录新增折叠状态，普通列表查询改为仅加载未折叠数据，共享侧滑 item 增加第二段继续滑动折叠动作。
- 2026-05-15：补充第二段滑动折叠的离场动画约束；原因是折叠状态立即更新会让 Paging 在卡片滑动中途移除数据，最终改为动画结束后再更新数据库。
- 2026-05-15：将第二段滑动最大距离和触发阈值改为跟随 item 宽度；原因是固定 96dp 第二段在最后阶段不够跟手，改为 85% 宽度触发后只补完少量离场动画。
- 2026-05-15：延长未达到折叠阈值时回到菜单状态的吸附动画；原因是原先松手后偏移变化过快，尤其从接近折叠阈值的位置回到菜单展开状态时显得突兀。
- 2026-05-15：将剪贴 item 菜单、折叠和取消折叠整体改为右滑，左滑恢复为列表页切到“我的”的 Pager 手势；原因是 item 左滑和页面左滑冲突，右滑更适合承载单条数据操作。
- 2026-05-15：曾将本文临时作为 `ClipResultList` 共享结果 item 的主方案入口；原因是搜索页和折叠页都会复用同一套 item，当时需要先把共享交互细节从页面文档中收拢。
- 2026-05-15：将 `ClipResultList` 共享 item 细节拆分到 `docs/clip_result_list_plan.md`；原因是列表页文档应只描述列表页面职责，组件级卡片、侧滑、动画和测量规则由独立组件文档统一维护。
- 2026-05-16：曾将列表页文档中的复制入口和详情点击描述收敛为引用共享组件主文档；原因是当时 `ClipResultList` 临时采用左侧复制视觉方案，页面文档不重复维护组件级点击分区；当前行为以后续左半区动作设置记录为准。
- 2026-05-16：记录普通列表接入左半区动作设置和右滑菜单复制按钮；原因是普通列表需要根据用户偏好在左半区执行复制、置顶、删除、折叠或关闭分区。
- 2026-05-17：记录左半区背景改为更浅的同源色；原因是半屏背景使用边框同透明度时观感偏重，普通列表需要降低背景存在感。
- 2026-05-17：记录左半区背景透明度继续降低；原因是上一版浅色背景在部分来源色下仍偏明显，需要让内容阅读优先。
- 2026-05-17：记录普通列表接入斜向快捷动作区；原因是共享 item 已从左右等分热区调整为左下三角快捷区，列表页只保留普通范围的接入语义和影响摘要。
- 2026-05-15：补充回收站删除语义；原因是列表页删除不再只有直接删除，需要通过统一删除选择弹窗在移入回收站和彻底删除之间选择。
