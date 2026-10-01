状态：已完成

# 共享表单与选择组件方案

## 单行下拉设置卡片（已完成，2026-10-01）

本轮共享组件与我的页接入已完成，编译、既有设置测试与只读审查通过；Preview 渲染/设备验收未执行。用于图标、单行标题、右侧当前选项与下拉菜单构成的即时选择设置；我的页接入和业务保存语义见 [我的页方案](mine_page_plan.md)。

- 复用检查：我的页原 `ClipItemActionSettingEntry` 使用纵向 `ListEntryCard`，共享层已有 `ClipMasterCard` 与 `SingleChoiceOption<T>`；base 与全仓没有下拉设置卡片。新增 `ui/widget/SettingDropdownEntryCard.kt`，复用卡片外壳、主题 token、单选项模型和 Material3 `DropdownMenu`/`DropdownMenuItem`；不改变现有入口卡片的布局及 API，不把弹窗组件改成多模式组件。
- API 接收 `title`、`selectedLabel`、`options: List<SingleChoiceOption<T>>`、`selectedValue`、`expanded`、`onExpandedChange`、`onSelect`、`accessibilityDescription`、`modifier: Modifier = Modifier` 和图标 slot。菜单展开状态由调用方传入，以便页面不可见时明确关闭。调用方提供完整资源化读屏描述，公共组件不拼接业务文案、不依赖具体枚举、ViewModel、Repository 或导航。
- 单行 Row：图标在左，标题占剩余宽度，当前短文案与箭头靠右；标题及右侧文案单行，保留标题/选项间最小间距，右侧最多 132dp 且短值按内容收缩，避免长值占尽标题空间。窄屏按需省略，读屏保留完整内容。沿用主题 typography、颜色及间距，内容高度随字体适配，点击热区至少 48dp，不用固定高度裁切文字。
- 整行是单一点击/读屏入口，右侧文字不另设重复点击监听；当前选项区域作为菜单位置锚点。菜单当前项显示勾选，按选项稳定业务值识别身份；选中关闭并只回传一次选择事件。相同值只关闭，不触发保存回调；菜单外点击或返回只关闭。组件只承载展示和事件，真实保存及失败反馈由调用方处理，已提交值仍由上层传入。
- 不自建草稿选择、持久化、订阅或后台任务；调用方以页面真实可见性约束展开状态，离页关闭，菜单展开不跨离页/重建恢复。若实施中出现新的选择规则、状态归约或可独立表达的 mapper，先补失败测试再实现，不把业务规则放进共享视觉组件。
- 已新增普通、深色、320dp 字体 2 倍与展开菜单 Preview；渲染/设备验收需检查长标题、长值、屏幕边缘、当前项、触摸和 TalkBack。纯布局/薄接线未抽出独立规则，不新增镜像 JVM 单测、不走 TDD；app Kotlin 编译、base 70 项单测与差异检查通过，只读审查无可行动问题，命令、日志及独立构建目录说明引用我的页主方案。
- 日志与诊断计划：公共组件无 IO 或业务提交，不新增展开、收起、点击、重组日志；失败诊断由业务主文档维护。展开状态为瞬时 UI 状态，不进入备份，无外部协议或反射依赖，不新增 keep/R8。
- 开放问题：Preview 渲染、超大字体下的标题省略、菜单可用高度与读屏需设备验收；本轮新改函数、参数状态与变量声明已补齐简体中文注释，后续修改需维护事件和可见性边界。

## 系统应用范围控件视觉候选（已确认，2026-10-01）

用户反馈标准 Material3 Switch 的粗轨道与描边在数量行中显得突兀，已选择筛选胶囊，并明确要求区分选中与未选中。当前采用下述胶囊实现；其他候选只保留为历史比较，既有过滤、扫描和保存行为继续保持。

### 筛选胶囊实现（已完成）

- 以共享 `ToggleFilterChip` 替换仅来源过滤页使用的 `ToolbarSwitchAction`；复用现有 `HorizontalFilterChips` 使用的 Material3 `FilterChip` 基础能力，但其多选项单选 API 不适合独立布尔筛选，因此不改动该列表组件。
- 用户最新修订：两态仅改变边框和填充色，不显示图标，也不预留图标空间。未选中透明底、`outlineVariant` 浅描边；选中 `primaryContainer` 填充、`primary` 描边。两态文字均使用 `onSurfaceVariant`，边框均为 1dp，使用主题圆角及 `bodySmall` 字体。
- 两态均为对称内边距的纯文字标签，宽度不随选择变化；可见胶囊默认约 32dp 高，依靠 Material3 Surface 最小交互尺寸保留至少 48dp 热区，不缩放控件。标签单行省略，完整读屏说明“显示系统应用”并复用 FilterChip 的选中语义。
- 接收 label、accessibilityDescription、selected、onSelectedChange 和 modifier，只回传目标状态；页面连接既有 `showSystemApps` 与 `updateShowSystemApps`，不新增状态机或副作用。
- Preview 覆盖两态、深色、320dp 数量行和放大字体。单元测试门禁：仅样式和等价接线，不走 TDD、不新增或重复镜像 JVM 测试；运行 app Kotlin 编译和差异检查。实际视觉、触控与 TalkBack 留待设备验收。
- 日志、备份和 R8：纯展示无新副作用，不新增点击或重组日志，不改变数据、备份及外部协议，无需 release/R8 验证。
- 本次无图标修订验证：`.\gradlew.bat :app:compileDebugKotlin --console=plain` 返回 `BUILD SUCCESSFUL`（1m 30s），日志为 `build/clip-source-filter-chip-text-compile.log`；差异检查通过，只读审查未发现代码问题，实施中的文档状态已同步完成。Preview 已编译但未渲染，设备视觉、触控和 TalkBack 尚未验收。纯样式不新增或重跑 JVM 单测，既有业务规则没有修改。

| 候选 | 关闭 / 开启形态 | 优点 | 取舍与 Compose 落地方向 |
| --- | --- | --- | --- |
| 筛选胶囊（已选定） | 浅描边透明底 / 主题描边和淡主题色底，无图标 | 控件和短标签合为一体，适合数量行的显示范围筛选 | 复用已有 FilterChip 形态与主题；视觉约 28–32dp 高，整块点击热区至少 48dp，两态文字与尺寸一致 |
| 纤细滑动开关 | 无描边细轨道，小圆点在左 / 主题色轨道，圆点在右 | 保留熟悉的开关动作，视觉明显轻于现状 | 参考轨道 34×20dp、圆点 14dp；如现有 Switch 无法满足几何，单独共享 CompactSwitch，保留 48dp 外层热区及单一 Switch 语义，不直接 scale 整个标准控件 |
| 紧凑勾选 | 小方框与标签 / 主题色勾选方框与标签 | 最节省横向空间，与页面已有勾选形态一致 | 复用 Checkbox/主题，热区至少 48dp；形态与应用行“阻止保存”的勾选相似，可能混淆显示筛选和保存规则 |
| 纯文字切换 | 灰色“系统应用” / 主题色文字与勾 | 去掉轨道、描边和底色，视觉最轻 | 共享可切换文字操作，读屏明确状态；可点击性及关闭态辨识弱于其他候选，不仅以颜色表达开启 |

- 每种都保留“系统应用”短标签，完整读屏说明仍为“显示系统应用”；状态由调用方传入，点击只回传目标状态，不在组件内扫描或保存。
- 数量行左侧继续使用剩余宽度并可换行；深色、字体放大和 320dp 窄屏纳入后续 Preview/设备验证，视觉变小不能缩小实际触控热区。
- 当前对话提供四个可交互示意，支持逐一比较关闭/开启，使用合成应用行，不代表真实 Compose 截图或实际扫描结果。
- 复用检查确认原 ToolbarSwitchAction 只有本页调用；采用 FilterChip，不创建其余三套生产组件。
- 单元测试判断与验证边界以本节筛选胶囊实现为准；此前四种对话示意不代表 Compose 实机截图。
- 日志与诊断计划：控件外观无新业务副作用，不新增点击/重组日志；搜索词、应用名单及剪贴内容仍不得输出。不改变设置、备份、keep 或 R8 契约。
- 开放问题：真实 Compose 字体、动态主题、触控反馈及 TalkBack 在设备验收；候选已选定。

## 顶部搜索输入组件（已完成，2026-10-01）

本轮共享 `SearchTopBar` 已实现并接入来源过滤页，单测、编译与审查通过；Preview 渲染和设备验收未执行。页面模式与证据见 [来源过滤方案](clip_source_app_filter_plan.md) 本轮章节。

- 新增 `ui/widget/SearchTopBar.kt`，用于占满顶部栏的返回 + 单行无边框输入 + 条件清空按钮，底部轻分隔线。复用主题、图标及键盘输入基础 API；现有正文 `SearchInputField` 保持原样，避免一个组件用 Boolean 切换完全不同的布局。
- 接收 query、placeholder、返回/清空无障碍描述、onQueryChange、onBack、onClear、onSubmit、modifier 和 inputModifier；调用方通过 inputModifier 接入 FocusRequester，不在每次重组抢焦点。组件无业务状态、导航、扫描、历史或语音依赖。
- 输入区域使用左右按钮之间的剩余宽度，不套用 `TitleBar` 每侧 112dp 的居中标题保护区。顶部负责一次状态栏避让，输入及按钮至少 48dp 高；分隔线覆盖底边不占额外高度，字号/颜色/间距来自主题及 token，大字体可随内容增加高度。
- 文案由调用方资源化传入；有非空输入才显示清空动作。组件只抛事件，是否退出搜索、清词、收键盘和何时离页由调用方决定，避免绑定应用过滤业务。
- 数量行最初复用 `ToolbarSwitchAction`；现已按用户选择改为共享 `ToggleFilterChip`，最终样式与筛选语义以本文筛选胶囊实现为准。
- Preview 覆盖空输入、长输入、窄屏、大字体和深色；设备验证焦点、IME、TalkBack。纯视觉不补镜像 JVM 测试，模式/返回规则在调用方先补失败测试；共享调用方编译验证使用 `.\gradlew.bat :app:compileDebugKotlin --console=plain`。
- 日志与诊断计划：不在输入、清空、聚焦和重组时新增日志；无持久化、备份或外部协议变化，无需 keep/R8。
- 验证证据：app 190 项单测及 Kotlin 编译通过，返回规则初始 3 项断言预期失败后补齐；最后输入高度与 token 调整补验编译成功。三个 Preview 已定义并编译，未渲染或做设备输入/读屏验收；完整记录以来源过滤主文档为准。

## 入口卡片说明 slot 扩展（已完成，2026-10-01）

本轮状态：已完成，说明 slot 与两条结果已接入，单测和编译/R8通过；Preview 渲染和设备读屏仍待验收。为支持 [备份主方案](webdav_backup_plan.md) 中本地/WebDAV 两组独立结果，扩展 `ListEntryCard` 的说明内容 slot，继续共用入口骨架。

- 新增接收 `descriptionContent` 的重载，共用原有图标、标题、卡片、modifier 和点击骨架；旧 `description: String` 重载委托到同一实现，默认说明继续最多两行，既有调用方行为保持稳定。
- slot 的排版、各条颜色与换行由调用方控制；共享外壳不统一施加两行上限、不读取业务状态、不依赖 ViewModel/Repository/NavController。备份业务文案、时间、失败原因及 loading/error state 由调用方映射，不能进入通用组件。
- 两条结果各自允许换行，使用 MaterialTheme 和既有间距 token；提供长文本、大字体、窄屏、深色 Preview，并验证读屏可分别识别两种目标和结果。
- 仅属于展示骨架扩展，不新增业务日志、持久化或备份字段；结果 mapper 的单测与备份主方案一致，slot 样式本身不写镜像测试。实施时编译并检查全部 `ListEntryCard` 调用方，防止重载歧义。

## 当前状态

来源过滤即时生效最初使用 `ToolbarSwitchAction` 和 `SelectableAppRow`；当前范围控件替换为 `ToggleFilterChip`，候选行继续复用。下述 2026-09-30 记录是历史实现，最新控件契约以本文筛选胶囊实现为准。完整业务流程以 [来源过滤方案](clip_source_app_filter_plan.md#即时生效与下拉刷新修改方案2026-09-30) 为准；入口卡片说明 slot 验证记录以备份主方案为准。

### 来源过滤共享展示组件（已完成，2026-09-30）

- `ToolbarSwitchAction` 接收短标签、完整无障碍描述、checked、callback 和 modifier，宽度控制在标题栏已预留的 112dp 保护区以内；标签可省略，开关保持可见且全区域有 Switch 语义。组件不扫描应用、不持久化设置。
- `SelectableAppRow` 接收具名 UI state：标题、副标题、内存图标、已提交勾选及 `Available/Disabled/Submitting` 交互状态。整行和 Checkbox 只触发一个明确目标状态 callback；提交中展示局部进度并禁用，Drawable 失败使用现有默认 App 图标。
- 页面负责将候选与提交状态转换为共享 UI state，并执行保存、错误提示和导航。组件不持有页面 ViewModel/Repository，不读取安装列表；包名稳定 key 由列表调用方设置。
- 文案由调用方字符串资源传入，颜色/字体来自 MaterialTheme；无新增业务日志、数据库或备份数据。提供窄屏长标题、提交中和深色 Preview，单测保护提交/筛选规则，布局已编译，Preview 已定义但未渲染；设备未连接，真实触控、读屏和视觉留待设备验收，不写镜像样式测试。

搜索页、我的页、下载记录页和回收站页都存在可复用的 Compose 形态：单选列表弹窗、复选列表底部弹层、筛选 Chip 行、搜索输入框、入口卡片、入口式开关卡片、固定设置开关列表、开关设置行、底部选择操作条，以及分页加载/空态/失败提示。此前这些组件大多按页面目录拆分，虽然页面入口变薄，但通用 UI 仍被页面 package 持有，不符合“默认公共化，页面只保留强业务耦合组件”的新规则。

当前已完成共享组件收敛：通用入口卡片、入口式开关卡片、搜索输入、筛选 Chip、单选弹窗、复选底部弹层、选择操作条、固定设置开关列表、设置开关行和分页状态组件已经迁入 `app/src/main/java/com/cla/clip/master/ui/widget/`；共享剪贴结果组件已迁入 `app/src/main/java/com/cla/clip/master/ui/widget/clip/`。页面目录只保留业务状态、草稿选择、导航、权限、删除确认和下载重试等页面差异。

本文作为共享表单与选择组件主文档。后续涉及单选弹窗、复选底部弹层、搜索输入、筛选 Chip、设置卡片、入口式开关卡片、设置开关行、入口卡片、底部选择操作栏和分页状态组件的修改，优先更新本文；页面文档只记录本页面如何接入、传入什么 state/callback，以及有哪些差异化业务动作。

## 目标

- 把视觉结构和交互模式通用的 Compose 组件沉淀到 `ui/widget`。
- 页面目录只保留业务实体到 UI state 的薄适配层，以及 ViewModel、导航、权限、删除授权、下载重试等页面流程。
- 公共组件提供 `modifier`、slot、UI state/config 和 callback，不直接依赖页面 ViewModel、Repository、NavController 或具体 Room 实体。
- 避免公共组件 Boolean 参数堆叠，复杂状态用具名 state/config/sealed model 表达。
- 保持现有页面行为等价，只调整组件归属和复用边界。

## 范围

- 新增共享组件：
  - `ListEntryCard`
  - `SettingDropdownEntryCard`
  - `SettingSwitchEntryCard`
  - `SettingSwitchListCard`
  - `SettingSwitchRow`
  - `SingleChoiceDialog`
  - `SelectableListBottomSheet`
  - `SelectableListItem`
  - `SelectionActionBar`
  - `SearchInputField`
  - `HorizontalFilterChips`
  - `FilterChipOption`
  - `PagingLoadingContent`
  - `PagingEmptyContent`
  - `PagingErrorContent`
  - `pagingAppendStateItem`
- 我的页保留入口业务适配函数，例如回收站、折叠数据、下载记录和快捷动作设置入口。
- 搜索页保留搜索范围、时间筛选语义和来源 App 草稿选择状态。
- 下载记录页保留视频/图片分页、重试、删除授权和记录卡片业务内容。
- 回收站页保留恢复、彻底删除、保留天数保存和清理流程。

## 实现步骤

1. 新增共享表单、选择、分页状态组件。
2. 我的页入口卡片、快捷动作下拉设置和权限入口式开关卡片接入共享组件。
3. 搜索页搜索输入、时间选择弹窗和来源 App 选择弹层接入共享组件；时间与来源的等宽选择器留在搜索页业务适配层。
4. 下载记录页选择操作条和分页加载/空态/错误状态接入共享组件。
5. 回收站多选底部操作栏、保留天数单选行和确认弹窗尽量复用共享组件。
6. 运行 `./gradlew :app:compileDebugKotlin` 验证编译。

## 测试验证

- 我的页入口点击、快捷动作选择和权限开关行为保持不变，Shizuku 与通知权限项分别独立成卡，权限卡片内部展示图标、短标题、完整说明和右侧开关，说明文案完整显示且不使用省略号截断。
- 搜索页输入、清空、键盘搜索、时间选择和来源 App 多选确认行为保持不变。
- 下载记录页分页加载、空态、失败重试、多选删除和图片预览行为保持不变。
- 回收站多选彻底删除、还原确认、清空确认和保留天数设置行为保持不变。
- 已运行 `./gradlew :app:compileDebugKotlin`，结果通过；仅保留既有 Gradle 配置和弃用 API 警告。

## 已知取舍

- 当前不把下载记录视频/图片历史卡片整体抽公共，因为卡片绑定下载任务、图片批次、重试和本地文件状态，跨页面复用价值不足。
- 当前不把回收站保留天数完整弹层抽成公共，因为保存边界、最小/最大天数和清理副作用属于回收站业务；只复用更底层的单选行和通用动作区域。
- 当前不把搜索来源 App 草稿选择状态抽公共，因为外部空集合表示“全部来源”的契约是搜索领域语义；公共底层只负责列表选择 UI。

## 最终实现

- `ListEntryCard` 承载图标、标题、说明和点击入口，当前由我的页下载记录、折叠数据、回收站等普通入口复用。
- `SettingDropdownEntryCard` 承载单行即时选择设置，当前由我的页快捷操作入口接入；复用卡片外壳和单选项模型，当前值/菜单状态/保存均由调用方管理。
- `SettingSwitchEntryCard` 承载入口式开关卡片，当前由我的页 Shizuku/通知权限项复用；页面传入权限标题、说明、状态、图标和回调，使权限卡片与其他入口保持图标、标题、说明和统一卡片外壳。页面仍负责权限刷新、系统跳转和 Shizuku 回调，权限说明文本完整换行展示。
- `SettingSwitchListCard`、`SettingSwitchRow` 和 `SettingSwitchRowState` 承载固定设置组与开关行，保留给后续多项表单式设置列表使用，不再承载我的页权限入口卡片。
- `SingleChoiceDialog` 和 `SingleChoiceRow` 承载单选弹窗/单选行，继续用于搜索时间与回收站保留天数等选择；`SingleChoiceOption` 同时复用于我的页快捷操作下拉菜单，旧快捷动作弹窗已移除。
- `SearchInputField`、`HorizontalFilterChips` 和 `FilterChipOption` 承载搜索输入和横向互斥筛选；当前搜索页继续复用搜索输入，时间筛选改为复用 `SingleChoiceDialog`，页面层保留时间/来源等宽选择器和折叠语义。
- `SelectableListBottomSheet`、`SelectableListItemState` 和 `SelectableListItem` 承载复选列表底部弹层，当前由搜索页来源 App 选择复用；搜索页继续保留“空集合表示全部来源”和临时 0 选中禁用确认的草稿契约。
- `SelectionActionBar` 承载底部多选主操作条，当前由下载记录页多选删除复用；左侧删除类图标接入 `DeleteActionIcon`，与列表菜单删除和其他执行删除入口共享同款图形，下载记录页继续保留删除方式弹窗和本地文件授权流程。
- `PagingLoadingContent`、`PagingEmptyContent`、`PagingErrorContent` 和 `pagingAppendStateItem` 承载分页状态，当前由下载记录页和共享剪贴结果组件部分复用。
- `ClipResultList`、`ClipCardContent` 和 `ClipCardGestures` 已从 `ui/page/list` 迁入 `ui/widget/clip`，列表页、搜索页、折叠页和回收站统一从共享包引用。

## 变更记录

- 2026-10-01：共享单行下拉设置卡片完成编译与只读审查，调用方 base 70 项单测通过，同步完成状态与现行接入说明；Preview 渲染和设备验收未执行。原因是用户确认的单行即时选择方案已实施。

- 2026-10-01：用户确认执行单行下拉卡片，新增 `SettingDropdownEntryCard` 与四种 Preview，接入我的页快捷操作并明确单一读屏入口、稳定选项身份、上层展开状态与相同值不提交。开始编译、设置测试与只读审查。

- 2026-10-01：新增共享单行下拉设置卡片草案，明确复用外壳/选项模型、右侧菜单锚点、单一点击语义、事件与业务保存分离及生命周期/验收边界；原因是我的页“快捷操作”要求单行即时选择。本轮未新增产品组件。

- 2026-10-01：用户明确要求仅边框和填充色区分两态；移除勾选图标及其占位，统一文字颜色，使用等宽描边避免状态切换尺寸变化。纯样式继续编译验证，不新增日志、持久化或 JVM 测试。

- 2026-10-01：用户选择筛选胶囊并补充两态区分要求；将原独立开关替换为共享 ToggleFilterChip，复用 Material3 的选中语义和热区，以浅描边/主题填充加勾表达状态。纯 UI 样式不新增 JVM 测试，编译、差异与设备验收边界见本轮实现章节。

- 2026-10-01：新增系统应用范围控件四种视觉候选，推荐筛选胶囊，并保留纤细开关、紧凑勾选与文字切换供比较；原因是用户反馈标准 Switch 与数量行不协调。此轮仅文档和交互示意，未替换 Android 实现。

- 2026-10-01：完成 SearchTopBar 与数量行开关接入，使用主题 token、48dp 输入/按钮高度、返回/清空资源化语义及独立 Preview；190 项 app 单测、Kotlin 编译和最后布局编译通过。原因是实施确认的顶部搜索方案；设备及 Preview 渲染待验收。

- 2026-10-01：用户确认执行顶部搜索方案，SearchTopBar 与数量行接入进入实现，完成后记录编译及设备验证边界。

- 2026-10-01：新增 SearchTopBar 共享组件草案，并记录数量行复用 ToolbarSwitchAction；原因是来源过滤页要求隐藏常驻输入并使用 Google Play 式顶部搜索。只写方案，既有 SearchInputField 与其他调用方未变更。

- 2026-10-01：入口卡片说明 slot 完成调用方编译验证，更新本轮已完成状态；视觉和 TalkBack 仍保留设备验收边界。原因：备份主方案实施及审查修复已完成。

- 2026-10-01：备份入口接入本地/WebDAV 独立结果及说明 slot；普通设置保存不再调度备份。原因：实施备份主方案；其他来源过滤交互保持。

- 2026-09-30：完成 ToolbarSwitchAction 与 SelectableAppRow，使用资源化文案、单一点击语义和具名行状态；app 编译与 168 项单测通过，独立审查复核通过，Preview 渲染与设备验收未执行。原因是来源过滤即时提交与顶部开关接入已完成；其他入口说明 slot 仍保持草案。

- 2026-09-30：新增来源过滤共享紧凑标题开关与应用候选行的实现说明，明确 UI state、回调、触控/无障碍、Preview、日志和备份边界；原因是即时保存需要局部提交反馈且系统开关移到右上角，复用公共骨架而不让新 UI 堆入页面。本轮仍保留其他任务的入口卡片 slot 草案。

- 2026-09-30：新增入口卡片说明 slot 扩展草案，明确复用骨架、旧 String API 兼容、调用方文案与可访问性边界；原因是我的页需要本地/WebDAV 两条独立备份结果，现有最多两行 Text 不足以保证完整展示。本次仅文档。

- 2026-06-08：`SelectionActionBar` 的删除图标接入共享 `DeleteActionIcon`；原因是底部多选删除条属于删除类入口，需要与列表菜单删除图形统一，同时保持文字和业务确认流程不变。
- 2026-06-08：新增 `SettingSwitchEntryCard` 并让我的页 Shizuku/通知权限项接入入口式开关卡片；原因是权限区需要和其他入口卡片统一图标、标题、说明和右侧状态控件的扫描节奏。
- 2026-06-05：我的页权限适配层改为按单个权限项分别调用 `SettingSwitchListCard`；原因是 Shizuku 与通知需要像其他分类入口一样各自独立成卡。
- 2026-06-05：`SettingSwitchRow` 移除单项标题字段和标题文本渲染；原因是我的页权限卡片外层已经有“权限”分类，内部只需要说明和开关。
- 2026-06-05：`SettingSwitchRow` 的说明文本改为完整换行显示；原因是当前该共享行服务我的页权限项，权限说明包含用户必须看到的关键限制和取舍。
- 2026-06-05：将共享设置组从 `ExpandableSettingCard` 收敛为 `SettingSwitchListCard`，删除展开箭头和展开/收起动画；原因是我的页权限区现在固定展示 Shizuku 与通知两条权限项，不再需要可展开分组。
- 2026-05-31：同步搜索页时间筛选接入方式；原因是搜索页已从横向时间 Chips 改为“时间 / 来源”两个等宽单行选择器，并复用现有单选弹窗承载时间选择。
- 2026-05-18：新增共享表单与选择组件方案并标记为实现中；原因是按照最新 Compose 规则，需要再次整理所有页面组件，把通用 UI 形态从页面目录收敛到共享组件。
- 2026-05-18：完成共享表单、选择、分页状态和共享剪贴结果组件收敛，并通过 `./gradlew :app:compileDebugKotlin` 验证；原因是当前页面组件已按默认公共化规则迁入 `ui/widget` 或 `ui/widget/clip`，页面目录只保留强业务耦合适配。
