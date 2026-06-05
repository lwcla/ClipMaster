状态：已完成

# 共享表单与选择组件方案

## 当前状态

搜索页、我的页、下载记录页和回收站页都存在可复用的 Compose 形态：单选列表弹窗、复选列表底部弹层、筛选 Chip 行、搜索输入框、入口卡片、固定设置开关列表、开关设置行、底部选择操作条，以及分页加载/空态/失败提示。此前这些组件大多按页面目录拆分，虽然页面入口变薄，但通用 UI 仍被页面 package 持有，不符合“默认公共化，页面只保留强业务耦合组件”的新规则。

当前已完成共享组件收敛：通用入口卡片、搜索输入、筛选 Chip、单选弹窗、复选底部弹层、选择操作条、固定设置开关列表、设置开关行和分页状态组件已经迁入 `app/src/main/java/com/cla/clip/master/ui/widget/`；共享剪贴结果组件已迁入 `app/src/main/java/com/cla/clip/master/ui/widget/clip/`。页面目录只保留业务状态、草稿选择、导航、权限、删除确认和下载重试等页面差异。

本文作为共享表单与选择组件主文档。后续涉及单选弹窗、复选底部弹层、搜索输入、筛选 Chip、设置卡片、设置开关行、入口卡片、底部选择操作栏和分页状态组件的修改，优先更新本文；页面文档只记录本页面如何接入、传入什么 state/callback，以及有哪些差异化业务动作。

## 目标

- 把视觉结构和交互模式通用的 Compose 组件沉淀到 `ui/widget`。
- 页面目录只保留业务实体到 UI state 的薄适配层，以及 ViewModel、导航、权限、删除授权、下载重试等页面流程。
- 公共组件提供 `modifier`、slot、UI state/config 和 callback，不直接依赖页面 ViewModel、Repository、NavController 或具体 Room 实体。
- 避免公共组件 Boolean 参数堆叠，复杂状态用具名 state/config/sealed model 表达。
- 保持现有页面行为等价，只调整组件归属和复用边界。

## 范围

- 新增共享组件：
  - `ListEntryCard`
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
2. 我的页入口卡片、快捷动作单选弹窗和权限设置项列表接入共享组件。
3. 搜索页搜索输入、时间选择弹窗和来源 App 选择弹层接入共享组件；时间与来源的等宽选择器留在搜索页业务适配层。
4. 下载记录页选择操作条和分页加载/空态/错误状态接入共享组件。
5. 回收站多选底部操作栏、保留天数单选行和确认弹窗尽量复用共享组件。
6. 运行 `./gradlew :app:compileDebugKotlin` 验证编译。

## 测试验证

- 我的页入口点击、快捷动作选择和权限开关行为保持不变，Shizuku 与通知权限项分别独立成卡，权限卡片内部不展示单项小标题，说明文案完整显示且不使用省略号截断。
- 搜索页输入、清空、键盘搜索、时间选择和来源 App 多选确认行为保持不变。
- 下载记录页分页加载、空态、失败重试、多选删除和图片预览行为保持不变。
- 回收站多选彻底删除、还原确认、清空确认和保留天数设置行为保持不变。
- 已运行 `./gradlew :app:compileDebugKotlin`，结果通过；仅保留既有 Gradle 配置和弃用 API 警告。

## 已知取舍

- 当前不把下载记录视频/图片历史卡片整体抽公共，因为卡片绑定下载任务、图片批次、重试和本地文件状态，跨页面复用价值不足。
- 当前不把回收站保留天数完整弹层抽成公共，因为保存边界、最小/最大天数和清理副作用属于回收站业务；只复用更底层的单选行和通用动作区域。
- 当前不把搜索来源 App 草稿选择状态抽公共，因为外部空集合表示“全部来源”的契约是搜索领域语义；公共底层只负责列表选择 UI。

## 最终实现

- `ListEntryCard` 承载图标、标题、说明和点击入口，当前由我的页下载记录、折叠数据、回收站和快捷动作设置入口复用。
- `SettingSwitchListCard`、`SettingSwitchRow` 和 `SettingSwitchRowState` 承载固定设置组与开关行，当前由我的页 Shizuku/通知权限项复用；页面按单个权限项分别传入单项列表，使两个权限各自成卡。页面仍负责权限刷新、系统跳转和 Shizuku 回调，卡片内部不展示单项小标题，权限说明文本完整换行展示。
- `SingleChoiceDialog`、`SingleChoiceOption` 和 `SingleChoiceRow` 承载单选弹窗/单选行，当前由我的页快捷动作设置和回收站保留天数选项复用。
- `SearchInputField`、`HorizontalFilterChips` 和 `FilterChipOption` 承载搜索输入和横向互斥筛选；当前搜索页继续复用搜索输入，时间筛选改为复用 `SingleChoiceDialog`，页面层保留时间/来源等宽选择器和折叠语义。
- `SelectableListBottomSheet`、`SelectableListItemState` 和 `SelectableListItem` 承载复选列表底部弹层，当前由搜索页来源 App 选择复用；搜索页继续保留“空集合表示全部来源”和临时 0 选中禁用确认的草稿契约。
- `SelectionActionBar` 承载底部多选主操作条，当前由下载记录页多选删除复用；下载记录页继续保留删除方式弹窗和本地文件授权流程。
- `PagingLoadingContent`、`PagingEmptyContent`、`PagingErrorContent` 和 `pagingAppendStateItem` 承载分页状态，当前由下载记录页和共享剪贴结果组件部分复用。
- `ClipResultList`、`ClipCardContent` 和 `ClipCardGestures` 已从 `ui/page/list` 迁入 `ui/widget/clip`，列表页、搜索页、折叠页和回收站统一从共享包引用。

## 变更记录

- 2026-06-05：我的页权限适配层改为按单个权限项分别调用 `SettingSwitchListCard`；原因是 Shizuku 与通知需要像其他分类入口一样各自独立成卡。
- 2026-06-05：`SettingSwitchRow` 移除单项标题字段和标题文本渲染；原因是我的页权限卡片外层已经有“权限”分类，内部只需要说明和开关。
- 2026-06-05：`SettingSwitchRow` 的说明文本改为完整换行显示；原因是当前该共享行服务我的页权限项，权限说明包含用户必须看到的关键限制和取舍。
- 2026-06-05：将共享设置组从 `ExpandableSettingCard` 收敛为 `SettingSwitchListCard`，删除展开箭头和展开/收起动画；原因是我的页权限区现在固定展示 Shizuku 与通知两条权限项，不再需要可展开分组。
- 2026-05-31：同步搜索页时间筛选接入方式；原因是搜索页已从横向时间 Chips 改为“时间 / 来源”两个等宽单行选择器，并复用现有单选弹窗承载时间选择。
- 2026-05-18：新增共享表单与选择组件方案并标记为实现中；原因是按照最新 Compose 规则，需要再次整理所有页面组件，把通用 UI 形态从页面目录收敛到共享组件。
- 2026-05-18：完成共享表单、选择、分页状态和共享剪贴结果组件收敛，并通过 `./gradlew :app:compileDebugKotlin` 验证；原因是当前页面组件已按默认公共化规则迁入 `ui/widget` 或 `ui/widget/clip`，页面目录只保留强业务耦合适配。
