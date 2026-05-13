# 搜索页方案

## 当前状态

已完成。当前文档记录第一版搜索页设计、最终实现以及来源 App 多选筛选调整，后续搜索相关需求、方案调整或代码实现偏差都必须同步更新本文档。

## 目标

- 在剪贴列表页右下角新增搜索浮动按钮，点击后进入独立搜索页面。
- 搜索页面支持按关键词、时间范围和来源 App 筛选剪贴记录。
- 搜索结果沿用列表页的卡片样式和交互，并在命中内容中高亮显示搜索关键词。

## 范围

- 新增列表页搜索入口。
- 新增搜索页路由、页面和 ViewModel。
- 扩展数据层查询能力，支持关键词、时间和来源 App 的组合筛选。
- 抽取列表页结果卡片和瀑布流结果列表，供列表页和搜索页复用。
- 新增或调整用户可见文案时，按项目规则写入对应 `strings.xml`。

## 用户体验

- 列表页右下角展示搜索 FloatingActionButton，点击进入搜索页。
- 搜索页顶部提供返回入口、搜索输入框和筛选入口。
- 时间筛选第一版提供：全部、今天、近 7 天、近 30 天。
- 来源 App 筛选提供：全部来源和已记录过的具体 App；当前调整支持同时选择多个来源 App。
- 搜索结果显示方式与列表页一致，包括瀑布流布局、剪贴内容、来源 App、时间、置顶、删除、复制和进入详情。
- 来源 App 筛选弹窗使用多选勾选交互，点击具体 App 只切换弹窗内草稿选中状态，不立即关闭弹窗，也不立即刷新结果；点击“全部来源”清空草稿，点击“确定”后统一应用。
- 当搜索关键词非空时，在搜索结果卡片的可见文本中高亮命中的关键词。
- 当没有结果时展示搜索页专用空状态文案。

## 高亮规则

- 高亮只针对关键词搜索，不针对时间筛选或来源 App 筛选。
- 第一版高亮范围包含：
  - 剪贴内容 `content`
  - 链接预览标题 `linkTitle`
  - 来源 App 名称 `appName`
- 不高亮不可见字段，例如链接描述、站点名、包名等；这些字段可以参与搜索命中，但不应让用户看到没有上下文的高亮。
- 高亮实现建议通过 `AnnotatedString` 完成，普通列表页传空关键词，搜索页传当前搜索词。
- 搜索词需要先去除首尾空白，按空格拆分多个词，并去重，避免空关键词导致异常高亮。
- 英文、URL 和包名类内容按大小写不敏感匹配。
- 第一版优先高亮完整词；中文 FTS 模糊命中但可见文本里没有完整词时，暂不做逐字碎片高亮，避免视觉噪声。

## 数据方案

- 现有 `ClipRepository.searchAllClips(userInput)` 只支持关键词，并返回 `Flow<List<ClipShowEntity>>`；搜索页第一版建议改为分页数据流，避免历史数据较多时一次性加载。
- 在 `ClipDao` 增加搜索用 `PagingSource<Int, ClipDetail>` 查询，支持以下参数：
  - 关键词查询
  - 起始时间 `startTime`
  - 结束时间 `endTime`
  - 来源 App 包名集合 `sourceAppPackages`
- 关键词为空时，按时间和来源 App 集合条件查询全部匹配记录。
- 关键词非空时，复用现有 FTS 查询思路，并叠加时间和来源 App 集合条件。
- 来源 App 筛选状态使用空集合表达“全部来源”，非空集合表达多选包名；DAO 查询使用 `IN (:sourceAppPackages)` 匹配任一已选来源。
- 搜索结果排序建议沿用列表页规则：置顶优先、置顶时间倒序、普通记录按时间倒序。
- 在 `SourceAppDao` 增加加载全部来源 App 的查询，供搜索页筛选器使用。
- Repository 层暴露搜索分页数据和来源 App 列表给 `SearchViewModel`。

## UI 复用方案

- 现有 `ClipListPage.kt` 中 `ClipCard`、`ClipList` 等组件为私有实现，搜索页无法直接复用。
- 建议抽取到共享文件，例如 `app/src/main/java/com/cla/clip/master/ui/page/list/ClipResultList.kt`。
- 共享组件建议包含：
  - `ClipResultList(...)`
  - `ClipCard(...)`
  - `SourceAppNameWithTime(...)`
  - `ClipContent(...)`
  - `CardButtonContainer(...)`
  - `EmptyScreen(...)` 或可配置空状态
- 共享组件新增可选参数 `highlightQuery: String?`，列表页传 `null`，搜索页传当前关键词。
- 删除、复制、置顶和点击详情继续通过回调传入，避免共享组件直接依赖某个页面 ViewModel。

## 涉及文件

- `app/src/main/java/com/cla/clip/master/ui/navigation/Routes.kt`
- `app/src/main/java/com/cla/clip/master/ui/navigation/AppNavigation.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/list/ClipListPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/list/ClipListModel.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/list/ClipResultList.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/search/SearchPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/search/SearchViewModel.kt`
- `base/general/src/main/java/com/cla/clip/base/general/dao/ClipDao.kt`
- `base/general/src/main/java/com/cla/clip/base/general/dao/SourceAppDao.kt`
- `base/general/src/main/java/com/cla/clip/base/general/repository/ClipRepository.kt`
- `base/general/src/main/java/com/cla/clip/base/general/repository/ClipRepositoryImpl.kt`
- `app/src/main/res/values/strings.xml`
- `base/general/src/main/res/values/strings.xml`

## 实现步骤

1. 抽取列表结果共享组件，保证列表页行为不变。
2. 新增搜索路由和导航注册。
3. 在列表页增加搜索浮动按钮，并处理与底部导航栏、列表底部内容的间距。
4. 增加 DAO 和 Repository 的分页搜索能力。
5. 新增来源 App 列表查询能力。
6. 新增搜索 ViewModel，管理关键词、时间筛选、来源 App 筛选和分页结果。
7. 新增搜索页面 UI，接入筛选器和结果列表。
8. 在共享卡片组件中加入关键词高亮。
9. 补充字符串资源和中文注释。
10. 编译验证并检查列表页、搜索页、详情跳转、置顶、删除、复制行为。

## 最终实现

- 已新增 `SearchRoute` 并在导航图中注册 `SearchPage`。
- 列表页右下角已新增搜索 FloatingActionButton，点击进入搜索页；列表底部已预留空间，避免悬浮按钮遮挡最后一行卡片。
- 已抽取 `ClipResultList.kt`，普通列表页和搜索页共用同一套瀑布流、卡片、按钮、空状态和分页加载状态展示。
- 搜索页已实现关键词输入、时间筛选、来源 App 筛选、分页结果展示、详情跳转、置顶、删除和复制。
- 高亮已在共享卡片中实现，范围包含剪贴内容、链接预览标题和来源 App 名称；普通列表页传空关键词，不显示高亮。
- 数据层已新增分页组合搜索查询，并给返回 `ClipDetail` 的相关查询加 `@Transaction`，降低主表与关联表读取不一致的风险。
- 关键词搜索已同时使用 FTS 和 `LIKE` 子串并集，修复中文连续词搜索不稳定的问题，例如搜索“来源”可以命中“来源应用的包名”。
- 来源 App 筛选候选项通过 `SourceAppDao.loadAllSourceApps()` 从本地数据库实时加载。
- 来源 App 多选已完成：筛选状态已从单个包名改为包名集合，查询层已从等值匹配改为集合匹配。
- 来源 App 筛选 Chip 在未选择时显示“全部来源”，单选时显示来源 App 名称，多选时显示已选数量，避免多个 App 名称挤压横向筛选区。

## 测试验证

- 编译检查：已运行 `./gradlew :app:compileDebugKotlin`，结果通过。
- 中文模糊搜索修复后已再次运行 `./gradlew :app:compileDebugKotlin`，结果通过。
- 列表页验证：搜索按钮可见，点击后进入搜索页，原列表分页和卡片交互不退化。
- 搜索验证：关键词、时间、来源 App 可单独和组合筛选。
- 来源 App 多选验证：已运行 `./gradlew :app:compileDebugKotlin`，结果通过；Room 集合参数查询和 Compose 多选 UI 编译通过。
- 高亮验证：剪贴内容、链接标题、来源 App 名称中命中的关键词能高亮；空关键词不高亮。
- 交互验证：搜索结果里的详情跳转、置顶、删除、复制与列表页一致。
- 边界验证：无数据、无搜索结果、来源 App 缺失、关键词包含空格、英文大小写混合时表现正常。

## 已知取舍

- 第一版时间筛选先使用固定范围，不做自定义日期区间，以降低 UI 和本地化复杂度。
- 第一版中文高亮优先高亮完整词，不做逐字碎片高亮，避免结果卡片视觉过碎。
- 搜索结果排序沿用列表页置顶优先规则，方便用户在搜索页继续看到被置顶的重要内容。
- 对只包含标点或 FTS 特殊字符的关键词，最终实现会退回普通 LIKE 查询；这是为了让 URL 片段、符号输入等场景不因 FTS 语法失败而中断搜索。
- 对可构造 FTS 的关键词，最终实现也会并入 `LIKE` 子串结果；代价是关键词搜索会比纯 FTS 多一次主表扫描，但能保证中文连续子串的命中率。

## 开放问题

- 自定义日期范围是否进入第一版暂不确定；当前先按固定时间范围设计。
- 高亮是否需要支持中文逐字碎片命中暂不确定；当前先按完整词高亮设计。
- 来源 App 筛选器已采用底部弹窗；多选调整使用勾选列表、草稿选择和确认按钮，避免用户每选一个 App 都需要重新打开弹窗，也避免连续勾选过程中频繁触发分页查询。

## 收尾检查

- 搜索相关代码或方案发生变化时，确认本文档状态、正文、开放问题和变更记录是否需要同步更新。
- 如果最终实现与本文档不一致，在提交或交付前补充最终实现和取舍原因。

## 变更记录

- 2026-05-12：创建搜索页方案文档，记录搜索入口、筛选、结果复用和关键词高亮方案。
- 2026-05-12：按方案文档维护规则补充当前状态、开放问题和收尾检查，原因是需要让后续方案与代码变更有明确同步入口。
- 2026-05-12：将当前状态更新为实现中，原因是开始按已确认搜索方案进入编码阶段。
- 2026-05-12：记录搜索页最终实现、LIKE 兜底取舍和编译验证结果，并将状态更新为已完成，原因是第一版搜索方案已落地。
- 2026-05-12：将当前状态更新为实现中，原因是发现中文搜索词无法对子串内容做稳定模糊搜索，需要补充中文 LIKE 兜底。
- 2026-05-12：关键词搜索改为 FTS 与 LIKE 子串并集，并将状态更新为已完成，原因是修复中文搜索“来源”无法命中“来源应用的包名”的问题。
- 2026-05-13：将当前状态更新为实现中，并记录来源 App 筛选从单选改为多选的方案，原因是用户希望搜索页面可同时筛选多个来源 App。
- 2026-05-13：将来源 App 多选实现记录为已完成，并补充编译验证结果，原因是搜索筛选状态、UI 和数据查询已同步支持多选来源。
- 2026-05-13：补充来源 App 多选弹窗的草稿确认交互，原因是取消按钮应能丢弃未确认选择，并避免勾选过程频繁刷新搜索结果。
