状态：已完成

# 应用导航交互方案

## 备份恢复导航组接入（2026-10-02）

本轮状态：已完成；导航父图和轻量共享会话已接入，app 单测/编译/R8 与独立复审通过，设备验收另列待办。完整会话、状态、返回门禁与测试设计以 [备份恢复主方案的导航组改造章节](webdav_backup_plan.md#备份恢复与媒体关联导航组改造2026-10-02) 为准，本页只维护宿主接入差异。

- 新增 `BackupGraphRoute`，组内起点为 `BackupRoute`，另包含 `BackupRestoreRoute` 与 `BackupMediaRelocationRoute(restoreTaskId)`；应用根起点仍为 `MainRoute()`。继续同一 NavController，不新增可视容器页面或第二个 NavHost。
- 三个目的地注册移到 `ui/navigation/BackupNavigation.kt`，AppNavigation 仅接入；我的页实际入口 `MineEntries.kt` 的 BackupEntry 改为导航父图，组外不直接打开恢复/媒体子页。
- 只有轻量 `BackupFlowVm` 使用父图 backStackEntry 作为 owner，业务 VM 继续属于各自目的地；跨页通信实例由图 VM 持有并显式传入，不依赖 Singleton 或另一个 VM 的构造注入。
- 普通返回逐层退回；恢复结果完成、媒体成功/无需处理完成 inclusive 弹出 `BackupGraphRoute`，回到进入备份前页面。关闭会话后不保存/恢复该组状态。回调核验当前具体 entry、关闭许可仅一次，避免转场中的旧点击弹出组外页面；父图不存在的 fallback 也仅能弹仍属于回调来源的当前页。
- 组内恢复打开及媒体打开保留身份检查、防抖/单顶去重；组外快速点击不能叠出重复组。嵌套图不是访问控制，直接子路由和系统恢复都必须验证当前请求/结果；无有效内存会话回本组备份起点。
- 全局转场、首页 Tab、下载页特殊返回和 TorrentSearch 独立 Activity 不变。新增类型安全父图沿用 @Serializable/@Keep，session/UI state 不作为外部序列化协议。

### 本轮日志与验证

沿用导航低敏日志，增加 sessionId、routeType、targetType、closedGraph、reasonCode 的创建/关闭/降级摘要；不得打印请求对象、密码、账号、URI、地址、路径或用户内容。轻量会话的请求与回显日志细节只在备份主方案维护。

已新增会话/归约纯 JVM 测试，先写失败测试再实现；独立审查的同步 Running 与关闭重入两项回归也经历红绿验证。运行命令及结果集中记录在备份主方案，不复用历史验证作为本次证据。导航薄接线通过编译与 R8，真实普通回退、整组完成、重新进入、重复点击、图 VM 释放和进程死亡降级仍需设备或导航集成验收。

## 当前状态

应用通过 `AppNavigation` 注册首页、剪贴搜索、详情、下载记录、折叠数据、回收站、视频/图片提取等内建路由，子页面统一通过 `onBack` 调用 `NavHostController.popBackStack()` 返回上一层，通过 `onNavigate` 跳转到目标路由。TorrentSearch 作为编译期可选能力由 `TorrentSearchFeatureEntry` 发现并启动独立 `TorrentSearchActivity`；宿主 `NavHost` 不注册 TorrentSearch 或已废弃的磁力 route，关闭构建时入口和 Activity 均不可达。

全局保持 Compose Navigation 官方页面级左进右出动画：前进时新页面从右侧进入、当前页向左退出；返回时上一层从左侧进入、当前页向右退出。备份三页由 `BackupNavigation` 注册为父图，沿用同一宿主 NavController 和转场。导航动作增加来源身份和会话门禁，防止退出页迟到回调操作新的返回栈，不改变动画配置。

当前 release/R8 导航协议约束：`Routes.kt` 中所有类型安全路由、`SearchScope` 和 `MainInitialTab` 都通过 `@Keep` 保留默认完整类名，避免 `composable<T>`、`toRoute<T>` 或返回栈恢复在混淆包中按默认 serialName 找不到路由参数类型。TorrentSearch 不向 `Routes.kt` 暴露 route；`TorrentSearchFeatureEntry.launch(context, initialQuery, launchSource)` 只传递轻量候选查询和稳定启动来源，Activity 自行维护其内部状态，宿主不传递结果、数据库实体或剪贴板内容。`MainRoute(initialTab)` 只保存首页初始底部 Tab，用于需要显式指定首页首帧 Tab 的入口；媒体关联成功闭环不再创建新的首页路由，而是只关闭备份、恢复和媒体关联链路。

## 目标

- 使用 Compose Navigation 官方转场能力实现完整左进右出页面切换。
- 保持现有类型安全路由、普通返回行为和下载页特殊返回逻辑不变；仅在媒体关联成功或无须处理终态点击底部“完成”时，收起备份、恢复和媒体关联链路，不改变用户进入备份前所在的页面状态。
- 不引入元素联动、页面自身退出动画、退出页无动画或点击穿透规避策略。
- 先只观察纯页面切换动画的实际观感。

## 范围

- 调整 `AppNavigation` 的 `NavHost` 过渡配置和动画时长常量。
- 增加 `BackupGraphRoute` 嵌套图，组内起点为备份页；仅新增轻量图作用域 VM，三个业务 VM 仍属于各自目的地。
- 调整备份入口与整组退出、迟到回调门禁，备份及恢复算法和其他页面业务点击逻辑保持。
- 不改变首页底部 Tab 内部的 `HorizontalPager` 切换动画。

## 用户体验

- 从“我的”页进入回收站、折叠数据、下载记录等二级页时，新页面从右侧滑入，当前页面向左滑出。
- 启用 TorrentSearch 后，从“我的”页或详情页启动独立 Activity；其内部转场由功能模块维护，宿主不注册目的地。默认关闭构建不展示入口，且 Activity 不可达。
- 从二级页返回时，上一层从左侧滑入，当前二级页向右滑出。
- 我的页入口只保留普通卡片点击反馈，不做导航前缩放或延迟。

## 数据流

- 根图 `onNavigate` 普通路由调用 `navController.navigate(route)`；`BackupGraphRoute` 使用单顶并拒绝当前已处于本组时重复进入。组内恢复/媒体通过 `navigateWithinBackup` 检查来源 entry 与请求/任务，并使用 `launchSingleTop`。
- `onBack` 仍直接调用 `navController.popBackStack()`。
- 恢复结果和媒体关联页正向终态“完成”调用 `closeBackupGraph`，核验来源 entry 并领取一次会话关闭许可，再 `popBackStack<BackupGraphRoute>(inclusive = true)`；异常栈 fallback 只弹仍属于来源的当前页。媒体成功判断继续复用既有 VM 终态标记与 `BackupRestoreState.kt` 派生属性；普通返回只回上一层。媒体返回/完成先同步检查会话 Running，恢复完成也不以尚未更新的 UI projection 作为最终许可。退出不硬编码首页 Tab。
- `NavHost` 只改变视觉转场，不改变 back stack 数据流。
- 动画时长：进入 260ms，退出 260ms。

## 涉及文件

- `app/src/main/java/com/cla/clip/master/ui/navigation/AppNavigation.kt`
- `app/src/main/java/com/cla/clip/master/ui/navigation/BackupNavigation.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/backup/BackupFlowVm.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/mine/MineEntries.kt`
- `app/src/main/java/com/cla/clip/master/ui/navigation/Routes.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/main/MainPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/backup/BackupMediaRelocationPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/backup/BackupRestoreState.kt`
- `feature/torrent-search-api/src/main/**/TorrentSearchFeatureEntry.kt`
- `feature/torrent-search/src/main/**/TorrentSearchActivity.kt`
- `docs/app_navigation_plan.md`

## 实现步骤

1. 在 `NavHost` 上通过 Compose Navigation 官方 API 配置四个页面级转场。
2. 前进导航进入与退出都使用 `SlideDirection.Left`。
3. 返回导航进入与退出都使用 `SlideDirection.Right`。
4. 保持普通页面业务点击逻辑不变。
5. 为 `MainRoute` 增加 `initialTab` 参数，首页按该参数设置初始 Pager 页。
6. 备份三页收敛到父图，成功完成 inclusive 关闭整个父图；顶部返回和系统返回逐层退回；闭环出口同步关闭会话并核验来源。
7. 更新方案文档并运行 `./gradlew :app:compileDebugKotlin` 验证编译。

## TorrentSearch 接入影响（已实施）

TorrentSearch 已替换旧磁力模块，宿主不再注册 `MagnetSearchRoute` 或由 `NavHost` 承载磁力页面；通过 `TorrentSearchFeatureEntry.launch(context, initialQuery, launchSource)` 打开独立 `TorrentSearchActivity`。我的页传空查询、详情页传候选关键词，关闭 `enableMagnetFeature` 时发现集合为空且入口/Activity 不可达。外部 `ACTION_SEARCH`、`SEND` 与 `PROCESS_TEXT` alias 由 Activity 内部统一处理，不新增 launcher。完整 API、输入规范化、安全和生命周期要求以 [TorrentSearch 主方案](magnet_search_plan.md) 为准。

## 测试验证

- 从“我的 > 回收站”进入和返回时，应表现为完整左进右出动画。
- 从“我的 > 折叠数据”进入和返回时，应表现为完整左进右出动画。
- 点击“快捷操作”单行设置卡片应立即打开右侧锚定下拉菜单，不触发导航转场；页面内菜单关闭和保存规则以 [我的页方案](mine_page_plan.md) 为准。
- 运行 `./gradlew :app:compileDebugKotlin`。
- 媒体关联完成或无须处理后点击底部“完成”，应退出媒体关联页、恢复页和备份页，回到进入备份与恢复前的页面状态；点击顶部返回或系统返回应只回恢复页；失败、权限不足、预估、权限请求和待确认阶段返回仍回到恢复页。
- release 混淆包进入普通搜索和折叠搜索时，`SearchRoute.scope` 应能正常解析，不再因 `SearchScope` 类名被混淆而崩溃。
- release 混淆包通过媒体关联正向终态关闭备份恢复链路时，不应依赖新建带“我的”初始 Tab 的首页路由；进入备份前的页面状态应保持。
- 启用 TorrentSearch 的 release 混淆包应能通过 `TorrentSearchFeatureEntry.launch(...)` 打开独立 Activity，并正确接收候选查询和稳定启动来源；关闭构建不得暴露入口、Activity 或旧 route。

## 已知取舍

- 完整页面级退出动画会在动画期间保留退出目的地，这是 Compose Navigation 容器转场的正常机制；本次按最新要求只保留动画效果，不在本方案内处理点击穿透。
- 备份组针对业务导航增加来源 entry 与会话关闭门禁，防止旧回调操作新栈；其他页面若需治理触摸命中仍另行设计，不扩大本轮范围。

## 开放问题

- 当前动画时长 260ms 是否符合实际观感，后续可只调时长和 easing，不改变页面点击逻辑。

## 变更记录

- 2026-10-02：按已确认方案接入备份导航父图，入口改跳 BackupGraphRoute，三页注册移到 BackupNavigation；图 VM 只持有通信会话，业务 VM 作用域保持。整组出口核验来源 entry 并领取一次关闭许可，避免退出转场重复弹栈。app 全量单测、Kotlin 编译、R8 与独立复审通过，设备验证边界以备份主方案为准。原因是导航组、跨页通信与关闭边界需要一致。

- 2026-10-02：补充备份恢复导航组接入草案及主方案引用，明确组起点、实际我的页入口、图 VM owner、整组收栈、重复入口和进程重建边界；同时将现行关闭方法名修正为代码中的 closeBackupRestoreStack(reasonCode)，避免旧说明误导。本次仅修改文档，尚未实施导航和通信改造。

- 2026-10-01：同步“快捷操作”标题与下拉菜单验收描述，仍只在我的页内选择、不触发导航转场；原因是旧单选弹窗已改成单行即时选择菜单。本轮不改变路由协议，无新增导航日志或 R8 需求。

- 2026-08-31：清理当前状态、R8 约束、文件清单和验收中的旧 `:feature:magnet` / `MagnetSearchRoute` 现行表述；原因是这些契约已删除，宿主当前只通过 `TorrentSearchFeatureEntry` 启动独立 Activity。
- 2026-08-30：同步 TorrentSearch 独立 Activity 的导航接入边界，并废弃旧磁力 route 注册说明；原因是新功能不再作为宿主 NavHost 的可选页面实现。
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
