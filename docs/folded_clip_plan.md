状态：已完成

# 剪贴数据折叠与复用搜索页方案

## 当前状态

剪贴记录已新增“折叠”状态：折叠数据不在普通列表和普通搜索中出现，只能从“我的”页的折叠数据入口进入折叠列表管理；折叠数据搜索继续复用现有搜索页和筛选 UI，通过路由范围限制为只搜索折叠且未进入回收站的数据。折叠列表和折叠搜索复用 `ClipResultList`，共享 item 的完整卡片结构、侧滑方向、动画阈值和通用测试验证以 `docs/clip_result_list_plan.md` 为主文档。

## 目标

- 普通列表和普通搜索默认隐藏折叠数据。
- “我的”页新增折叠数据入口，并展示折叠记录数量，数量来自轻量 `COUNT` 查询。
- 折叠列表页展示所有折叠数据，保留复制、置顶/取消置顶、删除和详情跳转。
- 折叠列表不展示回收站数据，删除时复用统一删除选择弹窗，可移入回收站或彻底删除。
- 折叠数据搜索复用现有搜索页，支持关键词、时间范围和来源 App 筛选。
- 普通列表/普通搜索继续右滑折叠数据；折叠列表/折叠搜索继续右滑取消折叠。

## 范围

- Room 剪贴记录表新增折叠字段和 5 -> 6 数据库迁移。
- DAO、Repository、Processor 增加剪贴可见范围、折叠状态更新和折叠数量读取。
- 共享结果列表提供继续右滑动作和提示文案，具体交互细节由 `docs/clip_result_list_plan.md` 维护。
- 搜索页通过路由参数复用为普通搜索和折叠搜索。
- 我的页新增折叠入口，折叠页新增独立列表页面。

不包含：

- 普通搜索页增加“包含折叠数据”的筛选开关。
- 在折叠列表页顶部内嵌完整搜索筛选 UI。
- 折叠状态同步到系统剪贴板或外部存储。

## 用户体验

- 普通列表和普通搜索复用共享 item 的继续右滑动作，页面语义为折叠数据，提示“继续右滑折叠数据”。
- 折叠列表和折叠搜索同样复用共享 item，页面语义改为取消折叠，提示“继续右滑取消折叠”。
- 右滑菜单、触发阈值、离场动画、菜单吸附和左滑收回规则以 `docs/clip_result_list_plan.md` 为准；本文只记录折叠/取消折叠的业务差异。
- 折叠列表和折叠搜索不启用普通范围的左右等分点击区，不显示左半区背景，也不把“左半区折叠”设置解释为取消折叠；整卡点击继续进入详情，取消折叠仍通过右滑第二段触发。
- “我的”页入口展示折叠数量，让用户不用进入页面也能知道是否存在折叠数据。
- 折叠列表空态显示“还没有折叠数据”；折叠搜索空态显示“没有找到相关折叠数据”。

## 数据流

- `ClipData.isFolded` 持久化折叠状态，默认 `false`，迁移时旧数据全部保持未折叠。
- `ClipVisibilityScope.VisibleOnly` 表示普通列表/普通搜索，只查询 `is_folded = 0`。
- `ClipVisibilityScope.FoldedOnly` 表示折叠列表/折叠搜索，只查询 `is_folded = 1`。
- 折叠范围查询同时过滤 `deleted_at = 0`；进入回收站的数据保留折叠状态，后续还原后仍回到折叠列表。
- `ClipRepository.searchClips(...)` 增加范围参数，搜索页仍负责关键词、时间和来源 App 状态，Repository 只负责把范围转换为 DAO 查询条件。
- `MineVm` 订阅折叠数量 Flow，并通过“我的”页入口展示，不加载折叠列表来统计数量。
- 折叠页使用独立 `Pager` 收集折叠范围数据，页面可见时才收集分页结果。

## 涉及文件

- `base/general/src/main/java/com/cla/clip/base/general/dao/ClipDao.kt`
- `base/general/src/main/java/com/cla/clip/base/general/dao/AppDatabase.kt`
- `base/general/src/main/java/com/cla/clip/base/general/repository/ClipRepository.kt`
- `base/general/src/main/java/com/cla/clip/base/general/repository/ClipRepositoryImpl.kt`
- `base/general/src/main/java/com/cla/clip/base/general/entity/ClipShowEntity.kt`
- `app/src/main/java/com/cla/clip/master/processor/ClipboardDataProcessor.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/list/ClipResultList.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/search/SearchPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/search/SearchViewModel.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/mine/MinePage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/mine/MineVm.kt`
- `app/src/main/java/com/cla/clip/master/ui/navigation/Routes.kt`
- `app/src/main/java/com/cla/clip/master/ui/navigation/AppNavigation.kt`
- `docs/clip_result_list_plan.md`

## 实现步骤

1. 新增折叠字段、迁移、DAO 查询和 Repository 范围枚举。
2. 扩展 Processor 和 UI 实体，提供折叠/取消折叠动作。
3. 扩展共享侧滑 item，支持第二段继续滑动提示和松手触发动作。
4. 新增折叠列表页、ViewModel、导航路由和“我的”入口。
5. 扩展现有搜索路由、搜索页和搜索 ViewModel，按范围复用为折叠搜索。
6. 补充字符串资源、更新相关方案文档并运行编译验证。

## 测试验证

- 运行 `./gradlew :app:compileDebugKotlin`。
- 数据库从版本 5 升级后，旧数据默认未折叠。
- 普通列表和普通搜索均看不到折叠数据。
- “我的”页折叠入口显示折叠数量，数量来自轻量 COUNT 查询。
- “我的 > 折叠数据”能看到折叠记录，并可进入同一个搜索页搜索折叠数据。
- 普通搜索与折叠搜索的关键词、时间范围、来源 App 筛选都可用，且结果范围正确隔离。
- 普通列表/普通搜索继续右滑会折叠；折叠列表/折叠搜索继续右滑会取消折叠。
- 共享 item 的右滑菜单、阈值、动画和基础按钮验证见 `docs/clip_result_list_plan.md`，本文只补充折叠范围隔离和取消折叠语义验证。
- 折叠列表页复制、置顶/取消置顶、删除、详情跳转和取消折叠均可用。
- 空态文案正确区分无折叠数据和折叠搜索无结果。

## 已知取舍

- 折叠不是删除，不改变内容、时间、来源、链接预览和置顶状态。
- 折叠记录仍参与剪贴采集去重；重复复制同样内容不会新增记录，也不会自动取消折叠。
- 普通搜索不提供包含折叠数据的开关，避免折叠入口边界变得含混。
- 折叠搜索通过现有搜索页路由范围复用，不新增独立折叠搜索页面文件。
- 共享 item 的侧滑阈值、Pager 手势边界、菜单吸附和离场动画以 `docs/clip_result_list_plan.md` 为准；折叠功能只通过页面传入不同的 `onSwipePastAction` 决定折叠或取消折叠。

## 开放问题

- 是否需要在折叠列表页支持批量取消折叠。
- 是否需要在详情页展示当前记录是否已折叠，以及从详情页折叠/取消折叠的入口。

## 变更记录

- 2026-05-15：新增折叠数据方案文档并标记为实现中；原因是本次将新增数据库字段、折叠列表、复用搜索和共享侧滑交互，需要在实现前记录目标、范围和取舍。
- 2026-05-15：完成折叠数据功能并将状态更新为已完成；原因是数据库迁移、普通/折叠范围查询、我的页折叠入口、折叠列表页、复用搜索页和第二段侧滑折叠/取消折叠交互已落地，并通过 `./gradlew :app:compileDebugKotlin` 验证。
- 2026-05-15：明确重复复制同内容时折叠记录仍参与去重且不自动取消折叠；原因是用户希望“重复复制同样内容不再次保存”这一规则不因折叠状态改变。
- 2026-05-15：折叠/取消折叠跟随 `ClipResultList` 共享 item 的侧滑方向、阈值、吸附和离场动画调整；原因是折叠功能只提供不同业务动作，完整共享交互细节统一记录在共享结果列表文档中。
- 2026-05-15：收敛折叠方案文档中的共享 item 细节；原因是 `ClipResultList` 的完整交互此前曾由列表页文档临时作为主文档维护，本文只保留折叠状态、入口、范围隔离和取消折叠差异。
- 2026-05-15：将折叠方案对共享结果 item 的引用改为 `docs/clip_result_list_plan.md`；原因是 `ClipResultList` 已拆分为跨页面组件主文档，折叠方案只保留折叠状态、入口、范围隔离和取消折叠差异。
- 2026-05-16：明确折叠列表和折叠搜索不接入左半区动作设置；原因是“折叠”设置只在普通范围表达折叠数据，折叠范围不应反向解释为取消折叠。
- 2026-05-15：补充折叠数据与回收站的关系；原因是折叠列表和折叠搜索需要过滤回收站数据，折叠数据还原后仍应保持折叠状态。
