状态：已完成

# 应用导航交互方案

## 当前状态

应用通过 `AppNavigation` 注册首页、剪贴搜索、详情、下载记录、折叠数据、回收站、视频/图片提取等内建路由，子页面统一通过 `onBack` 调用 `NavHostController.popBackStack()` 返回上一层，通过 `onNavigate` 跳转到目标路由。磁力搜索已改为编译期可选模块：`AppNavigation` 注入 `Set<MagnetFeatureEntry>`，集合非空时由 `:feature:magnet` 自行注册私有磁力 route，集合为空时不注册磁力目的地。

本次只增加 Compose Navigation 官方页面级左进右出动画：前进时新页面从右侧进入、当前页向左退出；返回时上一层从左侧进入、当前页向右退出。页面转场只负责视觉效果，不额外处理点击穿透，不关闭退出页动画，也不在具体页面里增加为了动画服务的点击拦截逻辑。

当前 release/R8 导航协议约束：`Routes.kt` 中所有类型安全路由、`SearchScope` 和 `MainInitialTab` 都通过 `@Keep` 保留默认完整类名，避免 `composable<T>`、`toRoute<T>` 或返回栈恢复在混淆包中按默认 serialName 找不到路由参数类型。磁力模块内部的 `MagnetSearchRoute(initialQuery)` 只保存磁力搜索页首帧需要的轻量字符串参数，由 `:feature:magnet` 自行 `@Keep` / `@Serializable` 维护；app 不在 `Routes.kt` 暴露磁力 route，也不在路由里传递磁力结果、数据库实体或剪贴板内容。`MainRoute(initialTab)` 只保存首页初始底部 Tab，用于需要显式指定首页首帧 Tab 的入口；媒体关联成功闭环不再创建新的首页路由，而是只关闭备份、恢复和媒体关联链路。

## 目标

- 使用 Compose Navigation 官方转场能力实现完整左进右出页面切换。
- 保持现有类型安全路由、普通返回行为和下载页特殊返回逻辑不变；仅在媒体关联成功或无须处理终态点击底部“完成”时，收起备份、恢复和媒体关联链路，不改变用户进入备份前所在的页面状态。
- 不引入元素联动、页面自身退出动画、退出页无动画或点击穿透规避策略。
- 先只观察纯页面切换动画的实际观感。

## 范围

- 调整 `AppNavigation` 的 `NavHost` 过渡配置和动画时长常量。
- 不改变路由结构、导航栈层级、页面参数和 ViewModel 作用域。
- 不改变各页面的业务点击逻辑。
- 不改变首页底部 Tab 内部的 `HorizontalPager` 切换动画。

## 用户体验

- 从“我的”页进入回收站、折叠数据、下载记录等二级页时，新页面从右侧滑入，当前页面向左滑出。
- 启用磁力模块后，从“我的”页进入磁力搜索页，或从详情页带候选关键词进入磁力搜索页时，同样使用页面级左进右出转场；默认禁用构建不展示这些入口，也不注册磁力目的地。
- 从二级页返回时，上一层从左侧滑入，当前二级页向右滑出。
- 我的页入口只保留普通卡片点击反馈，不做导航前缩放或延迟。

## 数据流

- `onNavigate` 仍直接调用 `navController.navigate(route)`，但 `BackupMediaRelocationRoute` 继续使用 `launchSingleTop`。
- `onBack` 仍直接调用 `navController.popBackStack()`。
- 媒体关联页的正向终态“完成”调用 `closeBackupRestoreStackAfterMediaRelocation()`，内部通过 `popBackStack<BackupRoute>(inclusive = true)` 只移除 `BackupRoute`、`BackupRestoreRoute` 和 `BackupMediaRelocationRoute` 组成的备份恢复链路；如果异常栈缺少 `BackupRoute`，兜底只弹出当前媒体关联页，避免硬编码跳到固定首页 Tab。正向终态判断由 `BackupMediaRelocationVm` 在发送 Completed/NoWork summary 时同步记录，页面点击“完成”时优先读取该标记；顶部返回和系统返回只调用普通 `onBack` 回到恢复页，方便用户查看恢复结果和媒体关联 summary。底部“完成”统一进入 `BackupMediaRelocationPage.requestDone()`，避免和返回语义混用；`MediaRelocationUiState` 的完成闭环判断、日志 code 以及 `MediaRelocationSummaryType` 的闭环判断派生属性集中放在 `BackupRestoreState.kt`，与 `isRunning` 保持同一归属。
- `NavHost` 只改变视觉转场，不改变 back stack 数据流。
- 动画时长：进入 260ms，退出 260ms。

## 涉及文件

- `app/src/main/java/com/cla/clip/master/ui/navigation/AppNavigation.kt`
- `app/src/main/java/com/cla/clip/master/ui/navigation/Routes.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/main/MainPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/backup/BackupMediaRelocationPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/backup/BackupRestoreState.kt`
- `feature/magnet-api/src/main/java/com/cla/clip/feature/magnet/api/MagnetFeatureEntry.kt`
- `feature/magnet/src/main/java/com/cla/clip/feature/magnet/MagnetFeatureEntryImpl.kt`
- `docs/app_navigation_plan.md`

## 实现步骤

1. 在 `NavHost` 上通过 Compose Navigation 官方 API 配置四个页面级转场。
2. 前进导航进入与退出都使用 `SlideDirection.Left`。
3. 返回导航进入与退出都使用 `SlideDirection.Right`。
4. 保持普通页面业务点击逻辑不变。
5. 为 `MainRoute` 增加 `initialTab` 参数，首页按该参数设置初始 Pager 页。
6. 媒体关联页成功完成或无须处理后，底部“完成”只收起备份、恢复和媒体关联链路；顶部返回和系统返回只退回恢复结果页。
7. 更新方案文档并运行 `./gradlew :app:compileDebugKotlin` 验证编译。

## 测试验证

- 从“我的 > 回收站”进入和返回时，应表现为完整左进右出动画。
- 从“我的 > 折叠数据”进入和返回时，应表现为完整左进右出动画。
- 点击“剪贴快捷操作”设置行应立即打开弹窗，不触发导航转场。
- 运行 `./gradlew :app:compileDebugKotlin`。
- 媒体关联完成或无须处理后点击底部“完成”，应退出媒体关联页、恢复页和备份页，回到进入备份与恢复前的页面状态；点击顶部返回或系统返回应只回恢复页；失败、权限不足、预估、权限请求和待确认阶段返回仍回到恢复页。
- release 混淆包进入普通搜索和折叠搜索时，`SearchRoute.scope` 应能正常解析，不再因 `SearchScope` 类名被混淆而崩溃。
- release 混淆包通过媒体关联正向终态关闭备份恢复链路时，不应依赖新建带“我的”初始 Tab 的首页路由；进入备份前的页面状态应保持。
- 启用磁力模块的 release 混淆包进入磁力搜索页时，模块内部 `MagnetSearchRoute.initialQuery` 应能正常解析；初始关键词不应因为导航恢复被重复覆盖用户正在编辑的输入。

## 已知取舍

- 完整页面级退出动画会在动画期间保留退出目的地，这是 Compose Navigation 容器转场的正常机制；本次按最新要求只保留动画效果，不在本方案内处理点击穿透。
- 如果后续仍要解决快速点击被旧页面拦截的问题，需要另开交互稳定性方案，不与本次“只增加左进右出动画”混在一起。

## 开放问题

- 当前动画时长 260ms 是否符合实际观感，后续可只调时长和 easing，不改变页面点击逻辑。

## 变更记录

- 2026-06-05：将媒体关联成功或无须处理后的底部“完成”从硬编码进入首页固定 Tab 改为只关闭 `BackupRoute` / `BackupRestoreRoute` / `BackupMediaRelocationRoute` 链路，顶部返回和系统返回保持一级返回；原因是“完成”表示闭环退出，“返回”表示回上一层查看恢复结果，二者不应混用。
- 2026-05-25：磁力搜索导航改为通过 `Set<MagnetFeatureEntry>` 注册可选目的地；原因是磁力搜索已独立为编译期可选模块，默认构建不能在 app 路由表中保留磁力 route 或页面实现引用。
- 2026-05-24：媒体关联正向终态返回判断改为优先读取 ViewModel 终态标记；原因是页面返回时可能没有进入 `Result/NoWork` UI state 分支，导航层需要消费更稳定的业务终态语义。
- 2026-05-24：收敛媒体关联正向终态返回代码组织；原因是页面 state 和 summary type 的扩展属性应与 `isRunning` 归属一致，导航层低层 `popUpTo` 细节也应封装为命名函数，避免 `AppNavigation` 主流程过于拥挤。
- 2026-05-24：将媒体关联页底部“完成”改为复用 `requestBack()`，并把返回日志字段先在普通代码路径计算；原因是复测时定位到页面日志 lambda 行未执行，需要避免成功态按钮绕过同一返回入口，也避免 `logD` 惰性求值干扰断点判断。
- 2026-05-24：将媒体关联成功终态返回固定首页 Tab 的收栈目标从类型化 `popUpTo<MainRoute>` 调整为导航图起始目的地 id；原因是带参数 `MainRoute(initialTab)` 在实际验证中仍可能回到恢复页，需要用更稳定的起始目的地清理恢复链路。
- 2026-05-24：新增 `MainRoute(initialTab)` 和媒体关联成功终态固定首页 Tab 的导航策略；原因是恢复后媒体关联成功/无须处理时流程已经闭环，再返回到恢复页会让用户误以为还要继续处理。
- 2026-05-22：新增 `MagnetSearchRoute(initialQuery)` 并注册磁力搜索页导航；原因是磁力搜索第一版需要支持“我的”页空关键词进入和详情页带候选关键词进入，同时保持类型安全导航的 R8 稳定性。
- 2026-05-17：新增应用导航交互方案并关闭 `NavHost` 默认淡入淡出过渡；原因是回收站退出后立即点击折叠数据时，旧页面可能仍在退出动画中拦截触摸，导致入口无响应或旧列表动作串到新页面。
- 2026-05-17：将无动画方案调整为 220ms 横向位移动画；原因是完全无动画虽然规避了触摸命中问题，但页面流转过于生硬，横向滑动能保留层级感并减少旧页面原地覆盖。
- 2026-05-17：将完整双页横向滑动调整为 180ms 短距离进入动画，退出页不再整页移动；原因是完整横向位移会让回收站、折叠列表等重页面在过渡期间双页重绘，导致页面切换有卡顿感。
- 2026-05-17：将导航层轻量横向动画改为元素联动动画；原因是只要动画挂在 `NavHost` 上，旧目的地仍可能在过渡期参与触摸命中，导致从回收站返回后点击折叠数据偶发无响应或误触旧页面。
- 2026-05-17：按用户要求改为 Compose Navigation 官方页面级左进右出转场试验；原因是元素联动观感较差，需验证官方 `slideIntoContainer`/`slideOutOfContainer` 是否能在保留回收站生命周期防护的情况下避免旧问题。
- 2026-05-17：将官方左进右出转场时长从进入 180ms/退出 150ms 调整为进入 260ms/退出 220ms；原因是上一版动画时间偏短，页面切换层级感不够明显，需要先加长观察实际观感和回收站返回稳定性。
- 2026-05-17：全局关闭 `exitTransition` 和 `popExitTransition`，只保留前进/返回进入页滑动；原因是只处理回收站业务点击属于局部兜底，其他页面退出动画仍可能拦截新页面点击，需要从导航层减少所有旧目的地的触摸命中窗口。
- 2026-05-17：按用户要求回退点击穿透和退出页无动画相关修改，只保留完整页面级左进右出动画；原因是本次目标收敛为单纯增加页面切换视觉效果，不混入交互治理。
- 2026-05-20：为类型安全导航 route 和 `SearchScope` 补充 `@Keep` 混淆边界；原因是 release 包中 Navigation 会按默认完整类名解析 route serialName，枚举参数类被 R8 重命名后会导致搜索页路由恢复崩溃。
