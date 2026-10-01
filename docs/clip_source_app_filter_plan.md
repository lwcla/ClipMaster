状态：已完成

# 按 App 过滤剪贴保存方案

## 当前状态

2026-10-01：入口文案简化已完成。我的页在没有过滤规则时仅显示“未设置”；共用标题资源改为“剪贴过滤”，同步影响入口卡片与过滤设置页标题。有规则时仍显示“已过滤 X 个 App”。复用原字符串资源与 `blockedCount > 0` 分支，无新增组件/方法/状态；纯文案不新增或重跑 JVM 单测、不走 TDD。`./gradlew.bat :app:compileDebugKotlin --console=plain` 通过（BUILD SUCCESSFUL，1 分 19 秒），输出保存在 `build/clip-filter-copy-compile.log`，差异检查通过；设备视觉未验收。无需新增日志、备份或 release/R8 验证，字符串旁补充中文用途注释。

2026-10-01：安装应用读取器空结果契约修复已完成。原读取器将规范化后的空列表返回 `Failed(package_scan_empty)`，导致页面成功空态无法由真实零结果触达；临时 `|| true` 仅强制进入该失败分支。现正常完成读取后统一返回 `Success(apps)`，允许空列表；已移除强制分支，只有系统读取异常返回失败，取消继续传播。相关 37 项测试及 app Kotlin 编译、差异检查通过，只读审查无可行动问题；真实设备空态未验收。

2026-10-01：首次打开加载与居中空态修复已完成。原页面只判断可见候选为空，误把未开始扫描、扫描中和失败当成空结果，且提示位于顶部。现已通过独立内容状态与扫描/候选快照同步，列表区域居中显示加载、成功空结果或失败重试；成功且候选快照就绪后才能判空。新增 16 项回归测试，最终相关 35 项测试与 app Kotlin 编译通过，差异检查通过；实际设备居中、下拉和读屏未验收。

2026-10-01：已完成用户要求的胶囊两态仅改变边框和填充色：移除图标及占位，文字颜色和尺寸一致；未选中浅描边透明底，选中主题描边和主题浅色填充。完整契约与本次验证以 [共享表单方案](shared_form_components_plan.md#筛选胶囊实现已完成) 为准。复用既有系统范围状态与回调；搜索、统计、扫描与名单行为沿用已完成基线。app Kotlin 编译及差异检查通过，只读审查未发现代码问题；纯样式不新增或重跑 JVM 单测，Preview 渲染及设备验收未执行。

2026-10-01：已完成用户确认的“数量行开关与顶部搜索模式”：开关位于数量右侧，默认仅标题搜索入口，搜索态为无边框顶部输入。app 190 项单测、Kotlin 编译及最后布局补验编译通过，只读审查无可行动问题；Preview 渲染和设备 IME/视觉/读屏未验收。当前顶部交互与证据以本轮章节为准，后续即时保存、扫描与数据契约仍沿用既有实现。

2026-10-01：已完成用户批准的即时生效与下拉刷新方案，保持已过滤优先；移除页面确认、清空和手动添加，系统应用开关位于顶部右侧。提交与可见扫描分别下沉到 feature-local 协调器，共享 UI 组件负责展示。app 168 项与 base/general 69 项单测全部通过、app Kotlin 编译和差异检查通过，独立审查问题已修复并复核；设备手势/视觉及 release/R8 未验证。最终实现与本轮证据以“即时生效与下拉刷新修改方案（2026-09-30）”为准，下列既往记录仅作为历史证据。

2026-09-30 已移除安装应用名称规范化：直接保留 PackageManager 标签，缺失标签才转为空串，删除 80 字符上限和名称 trim。名称显示复用现有行宽约束、单行与省略号；本轮 app Kotlin 编译、完整 app 155 项单测及差异检查通过，名称读取测试 8 项通过；独立构建输出绕开原 general/classes.jar 占用，未修改工程配置。真实设备显示与 release/R8 未验证，最终名称契约以“原样保留安装应用名称”章节为准。

2026-09-30 已完成“先按系统开关过滤，再随列表读取图标”：读取器先排除自身和范围外系统应用，再读取名称与 Application 图标，页面直接展示 Drawable，专用 Coil 图标加载器已删除。开关变化重新扫描；完整草稿与备份契约保持。app Kotlin 编译、完整 app 154 项单测及差异检查通过；因原 general/classes.jar 被占用，本轮通过临时 init 将该模块输出改到独立验证目录，未修改工程配置。最终契约及本轮验证以“扫描时读取应用图标”章节为准；真实设备图标、开关与耗时/内存尚未验收，未运行 release/R8。

2026-09-30 当前扫描候选简化已完成：设置页仅使用本机扫描到的应用，不再订阅历史来源或创建未安装占位候选；已保存屏蔽应用优先，默认严格隐藏系统应用，名称缺失显示空名，图标不可用显示默认图标。app Kotlin 编译和完整 app 147 项单测通过，相关测试 15 项，差异检查通过；真实设备与 release/R8 未验证。当前实现及验证以“仅使用当前扫描应用”章节为准，下文旧的候选合并、历史名称/图标兜底与系统应用例外均为被替代的历史设计。

2026-09-30 此前提交的历史复核：app Kotlin 编译、app 150 项单测及 base/general 64 项单测全部通过，失败、错误和跳过均为 0；`git diff --check` 通过。这是本次候选简化之前的验证记录，本次结果以“仅使用当前扫描应用”章节为准；设备列表展示与连续刷新取消交互仍未验收。

2026-09-30 已按用户确认调整默认列表：显示所有已安装的非系统应用，不再要求桌面启动入口。复用现有候选构建与最终筛选，移除安装列表专用的 Launcher 查询和可启动字段；当前纯规则直接 JVM 执行 15 个测试通过，标准 Gradle 测试及编译仍被缓存访问拒绝阻断，详见“默认展示所有非系统应用”。

2026-09-30 底部操作栏导航栏避让已改由公共 `SecondaryPageScaffold` 统一负责，移除本页局部 padding；完整契约以 UI 主方案为准，接入及验证边界见“底部操作栏导航栏避让”。

2026-09-30 已按确认方案移除安装列表的 10 分钟节流：同一 ViewModel 会话内正在读取或已有成功结果时跳过自动加载，首次失败允许下次自动入口重试；手动刷新始终重读。返回弹出导航条目后重新打开创建新 ViewModel，仍自动读取，不引入应用级缓存。独立 Kotlin 编译、6 个新增纯状态测试及差异检查通过；完整 Gradle 单测仍受缓存 JAR 访问拒绝阻断，详见测试验证。

2026-09-30 已完成安装列表刷新方法的协程取消简化。`git diff --check` 通过；定向测试与 app 编译均在依赖模块 `:base:hidden-api:compileDebugJavaWithJavac` 被 Gradle 缓存 `appcompat-1.7.1-api.jar` 的 `AccessDeniedException` 阻断，提升权限重试仍失败，因此本次编译和测试未完成。

2026-09-30：用户要求过滤页改为下拉刷新、勾选/取消勾选立即生效、移除清空操作、显示系统应用开关放到顶部右上角，并补充去掉手动添加包名。用户更新代码后，已重新按 HEAD `d9952fa` 核对并修订草案。用户随后批准开始执行，当前已完成的最新设计以本文“即时生效与下拉刷新修改方案（2026-09-30）”为准，其中页面草稿、底部确认、清空/手动添加弹窗和顶部刷新按钮均已从本轮代码移除并通过本轮验证。其他章节描述已实现基线或历史取舍，旧测试记录不代表新方案已验证。

2026-09-28 的“保留未安装应用规则、隐藏对应列表行”已完成，上一轮 15 个定向测试、app 编译和差异检查均通过，独立只读审查未发现需要修复的问题。随后把长说明改为数量简报，但与标题下原有摘要重复；按最新截图反馈已移除新增简报，只保留原有头部摘要。本次删除后的差异与资源引用检查通过，编译受 Gradle 缓存 JAR 访问拒绝阻断，不能将上一轮通过视为本次编译通过。真实设备交互尚未实测。

应用通过 Shizuku 进程直读剪贴板，并把来源包名、来源名称、来源图标 hash 和剪贴 payload 提交给主进程 Provider。主进程由 `ClipHelper.processClipText(...)` 统一执行去重、链接解析、入库和通知。保存不再维护备份版本或调度；每日/手动备份读取最新数据。历史来源 App 继续缓存在 `source_apps` 表，搜索页来源筛选只读取 `source_apps`。

来源过滤能力已经落地：过滤名单只保存完整包名，按完整包名精确匹配，只影响未来剪贴保存，不删除、不隐藏历史记录。过滤页按自用/私有分发取舍声明 `android.permission.QUERY_ALL_PACKAGES`，由页面自动入口、用户刷新或系统开关变化触发主进程 `PackageManager` 读取；先按范围过滤，再读取名称与 Application 图标。安装列表不查询或保存可启动标记，不使用 Shizuku 安装应用缓存、Provider 同步、Room `installed_app_cache` 或 no-backup 安装图标目录。未安装包名继续参与完整名单和备份恢复，最终列表仅显示安装列表已识别的候选；本轮无需卸载重装，不新增数据迁移或清理代码。

## 成功空列表读取契约（已完成，2026-10-01）

- 范围与复用：仅调整 `PackageManagerInstalledAppReader.loadInstalledApps` 的结果分类，复用原归一化、扫描范围、成功模型和页面空态；不新增组件或工具方法。读取成功但无条目、仅自身、仅范围外系统应用均返回成功空列表，不猜测权限拒绝；真实 SecurityException/其他异常仍返回原失败码，取消原样传播。
- 数据与接口：`Success.apps` 明确允许空；删除无生产调用的 `package_scan_empty` 失败码并同步结果注释。成功零结果更新原子快照及摘要为 0，替换上一次成功列表；真正失败继续保留旧快照。名单、搜索、备份与生命周期不变。
- 测试门禁：采用 TDD，更新现有仅自身归空回归并新增原始空列表、范围过滤归空用例，先观察旧实现失败；保留安全异常/取消测试并补普通异常回归，协调器验证成功空结果替换旧行且页面归约为空态。运行定向读取器、协调器、内容状态测试和 app Kotlin 编译。
- 日志与诊断计划：空列表按正常成功记录 INFO，字段仍仅 success、appCount=0、elapsedMs、showSystemApps；取消不记错误，真实异常维持 WARN 与低敏 reason/errorType，禁止输出完整列表、应用名、包名、输入或异常 message。不改变持久化/备份、外部协议或 keep，无需 release/R8。
- 当前修复替代历史 `package_scan_empty` 分支；下面旧记录仅说明当时契约，不能用于当前结果分类。设备空态视觉仍由上一轮布局实现，实机验收未执行。
- 验证证据：首次新增协调器 fixture 调用了需要包名的旧辅助方法，已改为直接构造成功空结果；重新红测三个空结果用例全部预期断言失败，日志 `build/installed-app-empty-result-red.log`。修复后运行 `.\gradlew.bat :app:testDebugUnitTest --tests 'com.cla.clip.master.installedapps.PackageManagerInstalledAppReaderTest' --tests 'com.cla.clip.master.ui.page.mine.ClipSourceBlockInstalledAppCoordinatorTest' --tests 'com.cla.clip.master.ui.page.mine.ClipSourceBlockListContentStateTest' :app:compileDebugKotlin --console=plain`，`BUILD SUCCESSFUL`（48s），日志 `build/installed-app-empty-result-validation.log`。
- 真实 XML 汇总：读取器 11 项、协调器 12 项、内容状态 14 项，共 37 项，失败/错误/跳过均为 0。新增 4 项回归并更新旧空结果与失败原因用例；源码已无被删除空失败码引用，差异检查通过，只读审查无可行动问题。未新增布局或重复设备验收，未运行全量 app 单测、release/R8 或备份测试，范围与本次读取器内存返回契约一致。

## 首次加载与居中空态修复（已完成，2026-10-01）

- 状态规则：有可见候选时保持列表，包括刷新和刷新失败；没有可见候选时，扫描中或尚未完成首次成功且没有错误展示加载；读取失败展示失败/重试；只有成功完成且候选映射快照就绪后才展示空结果。默认 `loading=false` 不代表曾经成功，不更改协调器默认值或生命周期扫描入口。
- 扫描状态与候选分别收集，后台候选映射可能晚于成功状态到达；数量相同也不能证明映射已更新。协调器以 `InstalledAppSnapshot` 原子发布应用列表和成功摘要对象，候选快照携带同一摘要引用；可见候选为空时，只有候选摘要与当前成功摘要为同一对象且数量一致，才允许判空。同数量、同时间刷新仍能区分，不引入时间比较、持久化版本或跨安装状态。
- 成功摘要及两种快照外壳采用普通 class 的对象身份，不能使用结构相等：慢收集者可能错过中间 loading，而新旧摘要字段值完全相同；保持摘要身份可使外层状态也真正交付这次成功，避免候选已更新但页面仍持有旧摘要导致一直加载。已补受控慢收集者回归并观察旧摘要实现失败。
- 复用检查：现有 mine 状态、协调器和候选规则无同职责内容状态归约；app 的 `CenteredStateContent` 与 `InlineLoadingState` 可直接复用，Paging 状态组件带分页语义/图标不适合本页；共享模块与源码兜底未发现更合适能力。不新增共享组件；仅调整协调器成功快照与 ViewModel 候选 Flow 的数据外壳，扫描/写入状态机和生命周期保持。
- 抽取 `ClipSourceBlockListContentState.kt` 负责列表/加载/空态/失败的纯判断；页面只连接状态与候选快照。协调器将原仅列表的快照 Flow 改为携带摘要的快照，ViewModel 继续使用原 mapper 与后台调度，候选 Flow 的原子值包含映射列表和扫描摘要；任务启停、取消、失败保留及诊断日志不变。空列表保留 LazyColumn 及全视口状态 item，使用 `fillParentMaxSize` 与共享居中容器，以可用列表区域居中，同时保留下拉刷新。首次中央加载时不重复显示下拉刷新指示；已有内容刷新继续用下拉指示。
- 移除顶部重复的设置加载文字；名单快照未到时依旧禁用行提交，设置失败重读和单项写入等待保持原行为。数量摘要与范围胶囊继续位于顶部。
- 单元测试门禁：本次修复独立状态判断，采用 TDD；旧空规则的 12 项测试中 7 项断言预期失败。数量保护版的 13 项测试中新增同数量刷新 1 项预期失败，再引入原子快照及摘要身份；最终覆盖成功空结果、搜索无匹配、候选映射延迟、同数量同时间重读、失败与刷新保留旧行。运行定向内容状态、扫描协调器及候选规则测试和 app Kotlin 编译；纯布局不写镜像 JVM 测试。
- 日志与诊断计划：既有扫描 start/success/failure/cancelled 日志继续覆盖读取；本次只归约展示状态，不新增重组、关键词或应用名单日志。禁止输出搜索词、包名、图标和剪贴内容；不改数据、备份或外部协议，无需 release/R8。
- 验收边界：编译与 JVM 测试不替代真实居中、下拉手势和 TalkBack 验收；本轮复用已编译共享容器，未新增页面 Preview 或渲染，设备观感仍待实测。

### 最终验证证据

- TDD：旧空规则 12 项中 7 项预期断言失败，日志 `build/clip-source-list-content-red.log`；同数量刷新回归在 13 项中新增 1 项预期失败，日志 `build/clip-source-list-snapshot-red.log`；慢收集者实际只收到 1 次完成态、预期 2 次的单项回归失败，日志 `build/clip-source-list-collector-red.log`。逐步修复后均转绿。
- 最终运行 `.\gradlew.bat :app:testDebugUnitTest --tests 'com.cla.clip.master.ui.page.mine.ClipSourceBlockListContentStateTest' --tests 'com.cla.clip.master.ui.page.mine.ClipSourceBlockInstalledAppCoordinatorTest' --tests 'com.cla.clip.master.ui.page.mine.ClipSourceBlockSettingStateTest' --tests 'com.cla.clip.master.ui.page.mine.InstalledAppAutoLoadStateTest' :app:compileDebugKotlin --console=plain`，`BUILD SUCCESSFUL`（24s），日志 `build/clip-source-list-final-validation.log`。
- 真实 XML 汇总：内容状态 14 项、协调器 11 项、候选规则 4 项、按需加载 6 项，共 35 项；失败、错误、跳过均为 0。新增 14 项内容状态及 2 项快照/慢收集者回归，既有协调器读取断言迁移到原子快照，任务与失败契约保持。
- 只读审查指出数量保护不足及慢收集者结构相等问题，均已通过失败回归复现并修复；没有遗留的已知代码问题。`git diff --check` 通过；未改 Gradle 配置、设备状态或缓存权限，未运行全量 app 单测或 release/R8，验证范围与本次 UI/扫描发布接线一致。

## 数量行开关与顶部搜索模式（已完成，2026-10-01）

### 需求与范围

- 用户要求：系统应用开关放在过滤数量信息右边；默认不显示搜索框，标题右侧放搜索按钮；点击后顶部输入样式参考用户图二的 Google Play 搜索栏。
- 推荐在当前页面切换顶部模式，保持应用列表就地筛选。另开搜索页面会增加导航与名单状态同步；在正文展开原边框框则不能达到图二的顶部输入效果，因此不采用这两种方案。
- 用户已确认本轮仅借鉴图二的返回箭头、单行无外框输入和顶部分隔线；历史记录、搜索建议、麦克风和语音识别不加入本轮。

### 布局与交互

| 模式 | 顶部栏 | 顶部栏下方 | 结果区 |
| --- | --- | --- | --- |
| 普通 | 左侧返回，居中“剪贴过滤”，右侧搜索图标 | 左侧“已过滤 X 个，已读取 Y 个 App”，右侧“系统应用”标签和开关 | 当前安装应用列表 |
| 搜索 | 左侧退出搜索箭头，中间“搜索 App 名或包名”输入，有内容时右侧清空图标 | 保留同一数量行和系统开关 | 按应用名或包名实时筛选的原列表 |

- 删除正文常驻的 `SearchInputField`。普通态搜索图标使用至少 48dp 点击区域和资源化无障碍描述。
- 搜索态整条顶部栏替换标题，输入从返回按钮右侧延伸到右侧清空区域，无大矩形外框、无额外前置放大镜，底部轻分隔线；背景、字号和颜色遵循应用主题，顶部内容高度与普通标题栏一致。
- 点击搜索进入搜索态，输入获得焦点并弹出键盘；空关键词展示当前系统范围的全部候选。输入变化复用实时本地筛选，不触发安装扫描、持久化或网络查询。
- 清空图标仅在输入非空时显示，点击只清空关键词，保持搜索态、焦点和键盘。键盘搜索动作收起键盘，保留关键词及搜索结果。
- 顶部搜索返回箭头直接退出搜索：清空关键词、清除焦点、收起键盘、恢复普通标题栏和当前系统范围列表，留在本页。普通态顶部返回才调用现有等待提交的离页入口。
- 系统返回按层级处理：键盘可见时先由 IME 收起；随后在搜索态退出搜索；普通态才执行现有离页逻辑。搜索模式切换不能绕过已接受写入的返回等待，也不能触发名单回滚。
- 数量统计保持现有含义：X 是完整已提交过滤名单数量，Y 是最近成功扫描的应用数量，不随关键词缩减成命中数。加载及失败摘要继续使用现有分支。
- 数量行左侧使用剩余宽度并允许换行，右侧开关保留点击宽度和完整无障碍语义，窄屏、大字体下不与摘要重叠；不强制压缩摘要到一行。
- 搜索态仍可勾选、取消、下拉刷新及切换系统应用。勾选立即生效、已过滤优先、隐藏规则保留和扫描失败保留旧结果沿用基线；切换系统范围与下拉刷新保留当前关键词。

### 复用与职责

- 已按当前 mine package、app 共享 widget、base 共享模块及应用源码兜底检查。主机 `rg` 启动失败，使用 PowerShell 文件枚举与 `Select-String` 完成等价检查。
- 页面继续复用 `SecondaryPageScaffold`、`ToolbarSwitchAction`、`SelectableAppRow`、`clipSourceBlockHeaderSubtitle`、`filterClipSourceBlockCandidates` 和已有扫描/写入协调器。
- `ToolbarSwitchAction` 直接移入数量行，仍负责紧凑标签、单一开关语义和 callback；业务页面不复制 Switch 实现。
- 新增共享 `SearchTopBar`，放在 `ui/widget/SearchTopBar.kt`，负责无边框输入、返回/清空/提交回调及视觉，不依赖具体 ViewModel、Repository 或导航。其完整契约维护在 [共享表单方案](shared_form_components_plan.md) 本轮草案。
- 现有 `SearchInputField` 是正文带边框输入，保留其他调用方原有样式；现有 `TitleBar` 标题插槽每侧预留 112dp，不能把宽输入直接塞入中间插槽。通过二级骨架顶部 slot 接入 `SearchTopBar`，共享骨架契约以 [UI 主方案](app_ui_refresh_plan.md) 本轮草案为准。
- 本页增加小型普通/搜索模式状态，UI 层保存展示模式，查询文本继续由现有协调器作为唯一来源；不复制候选、已提交名单或查询状态，不把焦点放进 ViewModel。
- 配置变化保留模式与当前 ViewModel 查询；进程重建以空关键词恢复，不新增跨安装状态。页面不可见时不主动申请焦点或弹键盘，前台再次恢复不因重组反复抢焦点。离页释放焦点/键盘；Flow 与扫描仍受现有生命周期边界约束。

### 涉及文件与实施顺序

1. 先补普通/搜索模式转换及返回分流的失败测试；为协调器补搜索更新不扫描、刷新/范围变化保留关键词的契约测试，复用现有 JUnit 和 fake reader。
2. 扩展 `ui/widget/PageScaffold.kt` 的顶部 slot，新增 `ui/widget/SearchTopBar.kt` 及窄屏/大字体/深色/长输入 Preview；不修改居中 `TitleBar` 的全局布局。
3. 调整 `ui/page/mine/ClipSourceBlockSettingPage.kt`：数量行移入开关、移除常驻输入、接入搜索模式、焦点/键盘及返回分流。如需独立纯状态规则，放到 mine 的职责文件，不堆到入口底部。
4. 查已有返回/搜索/清空/提示资源；仅缺失文案时补充宿主字符串资源。新增或修改的组件、方法、变量、状态及副作用补齐简体中文注释。
5. 验证后同步三个主文档的状态与变更记录；本轮草案不涉及剪贴内容搜索页，`docs/search_page_plan.md` 无接入或行为变化，无需重复更新。

### 测试与验收

- 单元测试门禁判断：模式/返回分流属于独立状态规则，先补失败测试再改生产代码；既有协调器补查询和刷新契约测试。布局、焦点、IME 实际分发不写镜像 JVM 测试，用 Preview 与设备验证。
- 重点用例：首次无搜索框；进入搜索自动聚焦；按完整名称/包名匹配；空词显示当前范围；清空保持搜索态；提交只收键盘；返回先收键盘再退出搜索；退出搜索恢复列表；普通态离页继续等待写入；刷新或切范围保留词；快速进出搜索不重复扫描、不重复提交。
- 布局验收：数量开关同排，摘要可换行；小屏、大字体、深色、横竖屏及键盘弹出时列表可操作；旋转不丢失当前搜索，恢复前台不强制弹键盘；TalkBack 区分离页返回与退出搜索。
- 实施验证命令：`.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin --console=plain`、`git diff --check`。检查共享骨架全部调用方，默认顶部必须保持原有行为。
- 本轮无外部协议、反射、序列化或备份数据变化，不新增 keep、不改备份覆盖，不要求 release/R8；实际键盘和设备返回交互须在实施后验收，当前不能宣称通过。

### 最终实现与验证证据

- `ClipSourceBlockSettingPage` 删除正文常驻输入，普通态标题右侧显示搜索图标，搜索态通过顶部 slot 接入共享 `SearchTopBar`；数量行复用 `ToolbarSwitchAction`，统计口径不随关键词变化。查询继续由原协调器唯一管理，不改设置、备份或扫描入口。
- 纯返回分流位于 `ClipSourceFilterSearchState.kt`，按 IME、搜索、离页顺序选择单一动作；模式以 Boolean Saver 恢复，避免枚举名称进入 SavedState。顶部返回退出搜索清词，真正离页继续等待原写入协调器；等待离页时禁用进入搜索。
- 焦点只在切入搜索时等待 RESUMED 和输入挂载的下一帧申请，不随输入或扫描重组重复；页面暂停释放焦点并收键盘，恢复前台仅恢复扫描。内容补 IME 避让，输入与按钮最小点击高度 48dp，搜索组件间距来自主题 token。
- 测试先行：初始定向 13 项中 3 项返回断言预期失败；补齐规则后全 app 190 项单测全部通过，失败/错误/跳过为 0。新增 4 项返回测试及 2 项查询/刷新契约测试；既有筛选、写入和等待返回测试保持通过。
- `.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin --console=plain` 返回 `BUILD SUCCESSFUL`，日志位于 `build/clip-source-filter-search-validation.log`；最后仅收敛输入点击高度和主题间距，单独运行 `.\gradlew.bat :app:compileDebugKotlin --console=plain` 再次成功，日志位于 `build/clip-source-filter-search-final-compile.log`。无行为变化的布局收尾不重复 JVM 单测。
- 首次沙箱重跑在外部 Gradle wrapper 锁文件处访问失败；使用已有缓存在沙箱外重试通过，未清缓存或修改权限/工程配置。既有 Kotlin/Gradle 警告未纳入本轮修复；最终差异检查通过。
- 只读独立审查核对共享骨架七处调用方及最后布局收尾，无可行动问题。Preview 已定义并编译，未实际渲染；未安装此轮产物到连接设备，IME 分发、旋转、读屏、触控和视觉仍需设备验收，不将单测视为系统交互证据。
- 新增/修改的类型、方法、变量、回调、状态及生命周期分支已补简体中文注释；UI state 与 Boolean SavedState 无外部协议依赖，不新增 keep、备份字段或运行时日志，不执行 release/R8。

### 日志与诊断计划

- 搜索开关、逐字输入、清空、焦点和布局不新增日志：纯 UI/内存操作且高频，无新增业务副作用，避免噪声和搜索词泄露。
- 系统开关/下拉刷新继续使用现有 INFO 扫描阶段、数量/耗时及 WARN/ERROR 失败 reasonCode；名单提交沿用现有阶段诊断。禁止记录搜索词、应用名/包名列表和剪贴内容。
- 验证依靠单测断言、编译、Preview 和设备操作；新增组件不得在重组中打印日志。

### 开放问题

- 本轮布局和返回/退出清词已获确认；历史记录与语音搜索如果另有需要，再单独确定范围。
- 实施后再确认真实设备 IME 消费系统返回的顺序，避免同一次返回既收键盘又退出搜索。

## 目标

- 用户可以从“我的/设置”管理剪贴来源 App 过滤名单，入口展示未设置或已过滤数量。
- 用户可以在详情页对当前明确来源 App 快捷屏蔽或取消屏蔽后续剪贴。
- 过滤页以 App 图标和 App 名作为主要识别信息，保存时只写入包名。
- 保存链路在文本 trim 并更新 `lastClipContent` 后读取一次名单快照，命中后直接返回 `FilteredBySourceApp`。
- 过滤命中不查库、不入库、不解析链接、不通知、不 toast、不调度自动备份。
- 名单进入 `data/settings.json`，恢复时与本机名单取并集，不覆盖本机新增规则。

## 范围

- `ClipSettingsRepository` 的挂起原子 add/remove/replace 方法和纯规则 `ClipSourceBlockRules`。
- `ClipHelper.processClipText(...)` 的来源过滤分支和 `ClipProcessResult.FilteredBySourceApp`。
- Provider `commit_clip` 返回 `source_app_blocked` 且 `clipCommitted=false`。
- app 层 `InstalledAppReader`、`PackageManagerInstalledAppReader`、安装范围规则和仅扫描应用的过滤页候选构建。
- “我的”页过滤名单入口、独立二级选择页、数量行系统开关、可切换顶部搜索、单项即时提交和下拉刷新。
- 详情页来源快捷屏蔽/取消屏蔽动作与确认弹窗。
- 备份导出和恢复的 `blocked_clip_source_packages` 字段。

## 非目标

- 不删除、不隐藏、不迁移历史剪贴记录。
- 不把 App 名称、图标、系统安装列表或拦截次数统计保存进过滤名单或 WebDAV/本地备份。
- 不持久化当前安装应用列表，不写 Room，也不写 no-backup 图标目录。
- 不提供“测试当前剪贴来源”按钮；只承诺之后由 Shizuku 识别到这些包名时不保存。
- 不做 App 名匹配、前缀匹配、包含匹配或大小写归一。
- 不区分主空间与工作资料空间；当前按包名过滤，同包名在不同空间会一起命中。

## 用户体验

- “我的/设置”新增“剪贴保存过滤”入口，描述为“未设置”或“已过滤 N 个 App”。
- 点击入口进入独立二级选择页，只显示当前安装列表能识别的应用，候选行以图标和 App 名作为主识别信息、包名作为副标题；未安装或暂不可识别的已保存规则不显示占位行。
- 候选只来自当前安装扫描结果，已保存屏蔽应用稳定排在前面，同组沿用扫描器的名称/包名排序；不读取历史来源、不生成保存名单占位行。名称缺失显示空名，包名仍作为副标题。
- 默认展示所有非系统 App，不要求桌面入口、历史来源或已过滤记录；系统 App 仅由“显示系统应用”开关放行，已保存和搜索命中都不提供例外。
- 首次未读取到安装列表时不显示历史/已保存占位行；任何扫描失败保留最近成功的内存安装列表并显示错误；下拉可重试，读取失败本身不删除规则。
- 勾选/取消直接通过原子仓库修改当前包名，不需要页面确认；取消上限内已有项始终允许，新增超过 500 条明确提示。移除手动添加与清空入口，但既有未安装包名继续保留和备份。
- 数量统计包含隐藏规则的完整已提交名单，正文只保留“已过滤 X 个，已读取 Y 个 App”摘要；提交中仅当前行禁用并显示进度，返回等待已接受写入，失败留页允许重试。
- 详情页只有来源包名明确时展示“屏蔽此应用后续剪贴”或“取消屏蔽此应用”；确认弹窗必须说明只影响以后保存，不删除当前或历史记录。

## 数据和规则

- 名单持久化在宿主 Preferences DataStore 的字符串集合键中；`ClipSettingsRepository.blockedPackages` 对外只暴露规范化后的名单快照，写入在单次 `edit` 内完成。
- 规范化规则：trim、去空、拒绝空白/控制字符/少数危险分隔符、单个包名最多 200 字符、去重、排序、总数最多 500。
- 匹配规则：`null` 或空来源不匹配；只按完整包名精确匹配；大小写保持原样，不做归一。
- 保存链路在入库前读取已提交名单快照；读取失败则阻止本次入库，后续用户修改名单不会回溯影响已经开始的处理。
- 链接预览异步补齐复用首次保存已通过过滤的语义；一条剪贴已完成首次保存后，后续元数据补齐不再因为用户刚屏蔽来源而中断。
- 备份只导出规范化包名；恢复旧备份缺字段时按空名单兼容；恢复新字段时与本机名单取并集并按同一规则裁剪。

## 安装应用读取

- 主 App 声明 `android.permission.QUERY_ALL_PACKAGES`，旁边保留私有分发取舍注释和 `tools:ignore="QueryAllPackagesPermission"`；未来如果要上架 Google Play，必须重新评估。
- `PackageManagerInstalledAppReader` 在 `Dispatchers.IO` 读取安装应用，Android 13+ 使用 `ApplicationInfoFlags.of(0)`，低版本使用旧 flags API，不使用 `MATCH_DISABLED_COMPONENTS` 强行展示禁用/冻结 App。
- 安装列表只批量调用一次 `getInstalledApplications`，再读取标签并规范化；不查询 Launcher 包名集合，也不逐个检查启动 Intent。是否具有桌面入口不参与候选显隐。
- 读取规则：排除当前 App，App 名原样保留 PackageManager 标签，包括超长名称与首尾空白；仅缺失标签转为空串。按完整 App 名和包名稳定排序；当前 App 包名拒绝规则同时用于安装列表和手动添加。页面使用现有 `Modifier.weight(1f)`、`maxLines = 1` 和 `TextOverflow.Ellipsis` 约束显示，不在数据层截断名称，搜索同样使用完整名称。
- ViewModel 只保存当前页面会话内存列表、loading、是否已成功读取、失败 reason 和摘要；不在 ViewModel 初始化时读取，只在页面进入或用户刷新时读取。
- 连续刷新会取消旧读取 Job；读取结果返回后调用 `currentCoroutineContext().ensureActive()`，防止已取消的扫描晚返回后覆盖新状态，不再维护请求序号。刷新入口与结果应用都在主线程，取消检查到状态写入之间没有挂起点；同步 PackageManager 扫描不保证立即停止，但取消后不再应用结果。只重抛取消异常的 `try/catch` 已移除，取消直接沿协程传播。
- 读取器接收当前系统应用开关，先排除自身及范围外应用，再加载名称与 Application 图标；结果携带仅页面会话内存使用的 Drawable，不写文件或备份。
- UI 直接将候选 Drawable 交给现有 `AsyncImage`，不再按包名查询图标；null 或展示失败均显示原有 Android 默认图标。开关变化重新读取对应范围，重复同值不读取。

## 日志与诊断计划

- 过滤命中日志只记录 `reasonCode=source_app_blocked`、来源是否可识别和文本长度摘要，不记录包名、剪贴正文、HTML、完整 URL、链接标题或拦截次数统计。
- 安装应用读取日志只记录阶段、数量、耗时和 reasonCode；当前异常码为 `package_visibility_denied`、`package_scan_failed`，成功零结果记录 success、appCount=0。历史 `package_scan_empty` 已由成功空列表替代；禁止记录完整 App 列表、图标内容或用户剪贴内容。
- 刷新开始沿用 INFO 日志，不再记录已移除的 `force` 参数、请求编号或旧请求丢弃事件；自动加载跳过沿用 INFO，记录 `reasonCode=already_loading` 或 `reasonCode=already_loaded`，不输出安装名单、包名或搜索词。取消是正常任务替换，不新增失败日志；成功/失败数量与耗时日志保持原有覆盖。通过状态规则单测、编译和日志分支检查验证。
- 当前安装应用图标失败每次扫描只汇总一次 WARN，记录 `failureCount`、`showSystemApps` 和稳定 reasonCode；不输出包名、名称、图标字节或逐项日志。
- 手动添加、拒绝当前 App、详情页加入/移除和备份裁剪使用低敏 reasonCode：`manual_package_added`、`self_package_rejected`、`detail_block_source_added`、`detail_block_source_removed`、`backup_blocked_packages_trimmed`。
- 后台过滤命中不 toast；只有详情页主动加入或移除名单后展示轻提示。

## 测试验证

- 会话自动加载规则先编写测试：首次进入允许读取、读取中跳过、成功后跳过且不依赖摘要时间、首次失败允许重试、成功后刷新失败仍跳过自动加载、新会话重新读取。复用现有 JUnit 与 `InstalledAppListState`，不新增测试依赖；首次 Gradle 测试尝试在 `:app:compileDebugJavaWithJavac` 被缓存 `okhttp-release-api.jar` 的 `AccessDeniedException` 阻断，未执行到断言，无法完成严格测试先行的失败断言观察，作为本轮环境例外记录。手动刷新不经过自动判断，旧任务取消及 `ensureActive()` 保持原实现，连续刷新取消和真实导航返回仍属于设备/后续 ViewModel 集成测试边界。
- 本轮 Gradle 验证命令：`.\gradlew.bat :app:testDebugUnitTest --tests 'com.cla.clip.master.ui.page.mine.InstalledAppAutoLoadStateTest' --tests 'com.cla.clip.master.installedapps.PackageManagerInstalledAppReaderTest' --tests 'com.cla.clip.master.ui.page.mine.ClipSourceBlockSettingStateTest' --tests 'com.cla.clip.master.ui.page.mine.ClipSourceBlockSelectionRulesTest' :app:compileDebugKotlin '-Pkotlin.compiler.execution.strategy=in-process' --console=plain`；沙箱外重试仍在上述 app Java 编译任务失败，未执行目标测试，不清理缓存、不修改权限。
- 独立执行 `.\gradlew.bat :app:compileDebugKotlin '-Pkotlin.compiler.execution.strategy=in-process' --console=plain` 返回 `BUILD SUCCESSFUL`，日志位于 `build/clip-source-block-verification/session-auto-load-compile.log`。随后用现有缓存中 Kotlin 2.2.10 编译器的 `org.jetbrains.kotlin.cli.jvm.K2JVMCompiler` 编译 `InstalledAppAutoLoadStateTest.kt`，classpath 与 friend paths 指向本次 `app/build/tmp/kotlin-classes/debug` 的真实生产类，再通过 `org.junit.runner.JUnitCore com.cla.clip.master.ui.page.mine.InstalledAppAutoLoadStateTest` 执行，结果 `OK (6 tests)`。该直接 JVM 验证只覆盖新增纯状态规则，不替代完整 Gradle 单测、既有读取器/候选测试或设备验收。`git diff --check` 通过。
- 2026-09-30 协程简化验证命令：`.\gradlew.bat :app:testDebugUnitTest --tests 'com.cla.clip.master.installedapps.PackageManagerInstalledAppReaderTest' --tests 'com.cla.clip.master.ui.page.mine.ClipSourceBlockSettingStateTest' --tests 'com.cla.clip.master.ui.page.mine.ClipSourceBlockSelectionRulesTest' :app:compileDebugKotlin '-Pkotlin.compiler.execution.strategy=in-process' --console=plain`。普通执行及提升权限重试均被上述缓存 JAR 访问拒绝阻断，未执行到目标测试或 app 编译；真实设备和连续刷新取消交互未验证。PowerShell 中含点的 `-P` 参数须整体加引号，避免被拆成任务名。
- 纯 JVM 单测覆盖包名规范化、空来源不拦截、精确命中、大小写不误合并、重复去重、当前 App 拒绝、长度和总数限制、危险字符拒绝。
- 安装应用 reader 单测覆盖排除当前 App、完整长名称和首尾/纯空白标签保留、缺失标签转为空串、系统标记、稳定排序和失败 reason。
- 设置页测试覆盖只构建当前扫描候选、已保存屏蔽优先且保持组内顺序、空名称、系统开关无例外、名称/包名搜索、完整草稿保留隐藏规则及重新安装显示；已废弃的历史合并、历史文件图标和未安装候选测试由新契约替代。
- Provider/映射测试覆盖 `FilteredBySourceApp` 转为 `source_app_blocked`，且 `clipCommitted=false`。
- 备份测试覆盖 `blocked_clip_source_packages` 导出、旧备份缺字段兼容、恢复时与本机名单取并集、异常超量备份裁剪。
- 本轮验证：两个过滤设置规则测试类、`:app:compileDebugKotlin` 和 `git diff --check`；不重复运行与显隐无关的 Shizuku 或发布构建。
- 真机验收应覆盖恢复未安装 A 后隐藏、修改 B 并保存仍保留 A、安装 A 后成功刷新显示且已勾选，以及清空/放弃草稿的落盘时机；本轮不自动安装、卸载或操作设备。

## 已知取舍

- v1 不统计拦截次数，避免诱导记录高敏来源行为，也避免新增数据库字段和备份协议。
- `QUERY_ALL_PACKAGES` 是自用/私有分发取舍，换取简单可靠的安装应用展示；如果未来要公开上架，需要重新设计包可见性策略。
- 工作资料空间暂不区分 userId；后续若要区分，需要重新设计 UI 文案与匹配规则。
- v1 不做后台安装/卸载监听；进入页面读取一次，用户刷新时重新读取一次。

## 未安装应用隐藏方案（已完成）

> 历史实施记录：本节的三源候选合并、`installed` 字段和系统应用显隐例外已被“仅使用当前扫描应用”替代；完整草稿保存、跨安装规则及备份边界继续适用。

### 目标与影响范围

- 用户要求：未安装但已保存的屏蔽包名继续保留，过滤设置列表不显示对应行；以后安装同包名应用并成功刷新安装列表后，正常显示且保持屏蔽勾选。
- 展示以当前页面最近一次可用的本机安装列表为依据；未安装或暂时不可识别的包名都不生成可见行，历史来源名称、历史图标、已保存状态、搜索命中和“显示系统应用”均不能绕过安装条件。
- 已安装但图标读取失败的应用仍然显示默认图标；安装状态使用 `candidate.installed`，不得通过图标是否成功加载来推断。
- 修改仅覆盖来源过滤设置页的可见行、相关提示文案和纯规则测试。详情页快捷屏蔽、搜索页来源筛选、剪贴保存拦截、安装应用读取流程及历史来源数据保持现有职责。
- 本轮按用户确认实施页面、纯规则和测试改动，在当前工作区保留可审查差异，不自动提交或操作设备。

### 复用与方案选择

- 已检查当前 `ui/page/mine` 的候选构建、列表筛选、草稿勾选和保存入口，app 的 `ui/widget` 与 `installedapps`，base 的 `AppSetting` 与 `ClipSourceBlockRules`，以及全仓调用方；现有能力足以表达本需求，无需新增安装查询、Repository 或共享 Compose 组件。
- 复用 `buildBlockedSourceAppCandidates` 及其 `installed` 字段，在现有 `filterClipSourceBlockCandidates` 的最终结果前增加安装条件：`candidate.installed && visibleByDefault && matchesQuery`。这样已安装应用的排序、系统应用开关和搜索语义继续沿用。
- 不采用在恢复阶段删除未安装包名：这会丢失跨设备规则。不采用只修改 `defaultVisible`：现有草稿勾选、系统应用开关和搜索分支仍可让未安装条目重新出现。
- 将页面末尾现有筛选函数迁到同包的 `ClipSourceBlockSelectionRules.kt`，保持原有参数并改为 `internal`，使规则可直接接受纯 JVM 测试；同文件承载从页面提取的单包名勾选规则 `toggleBlockedSourcePackage(draftPackages: Set<String>, packageName: String): Set<String>`，继续使用既有 500 条上限。该规则属于此 feature 的包名选择语义，不放到跨业务 UI 工具中。

### 数据流与保存边界

1. `ClipSettingsRepository.blockedPackages` 提供完整已保存名单，包括未安装应用；页面草稿从完整名单初始化，读取失败提供显式重试。
2. `buildBlockedSourceAppCandidates` 继续合并已保存名单、本机安装结果和历史来源，内部保留候选元数据；最终可见列表才应用安装条件。
3. 点击可见行只增删该行包名；不能用可见列表或搜索结果重新生成完整草稿。单包名勾选规则不得更改其他隐藏包名。
4. 此阶段的完整 `draftPackages` 保存复用规范化和真实变化判断；最终页面已改为单项即时提交，不维护备份版本或保存后调度，参见后文及备份主方案。
5. 手动添加未安装包名仍允许校验后加入草稿，但不出现占位行；退出前仍需确认保存。500 条限制按完整名单计算。
6. “清空名单”保留既有全部清空含义：二次确认后清空完整草稿，包含隐藏包名，页面确认后才落盘。更新确认文案明确此范围；取消清空或放弃页面草稿不修改已保存数据。

### 数量、空态与刷新

- “我的”入口数量与页面名单数量继续统计完整已保存名单/完整草稿，不随搜索、系统应用开关或隐藏规则变化。不新增隐藏规则列表或显示开关。
- 头部与搜索框之间不再显示长说明或额外数量简报，数量和加载状态统一由原有头部摘要呈现。规则说明留在本文，详情页与清空确认提示仍沿用各自语义。
- 首次安装列表读取完成前沿用现有读取中状态，不用已保存包名或历史来源填充占位行。无可见候选时使用现有空态；加载中或读取失败必须保留对应提示，不能解释为全部规则已删除。
- 初次读取失败且无安装缓存时不显示候选行；普通刷新失败沿用当前内存安装列表，并提示失败。现有 `package_scan_empty` 分支清空安装内存时，可见行随之消失，但完整草稿和持久名单不变。
- 自动读取以当前 ViewModel 会话为边界：正在读取或至少成功读取过一次时跳过；首次失败后下次自动入口允许重试。手动刷新始终重新读取，并沿用取消旧任务和写入前取消检查。普通返回弹出导航条目后再次进入是新会话，自动重新读取；旋转等保留 ViewModel 的场景继续使用当前结果，不按时间自动过期。安装/卸载后的显隐在下一次成功读取后更新，不承诺后台实时变化。只复用现有页面收集和读取机制，不新增后台监听、轮询或逐行系统查询。
- 当前“未读取到应用，可继续管理已保存包名”的错误文案需要同步调整，避免承诺隐藏规则仍能逐条管理。清空确认文案需要明确“包含未安装应用的规则”。

### 涉及文件与实施步骤

| 文件 | 实现职责 |
| --- | --- |
| `app/src/main/java/com/cla/clip/master/ui/page/mine/ClipSourceBlockSelectionRules.kt`（新增） | 接收现有候选和完整草稿，承载最终展示筛选与单包名勾选纯规则；不读系统、不写设置。 |
| `app/src/main/java/com/cla/clip/master/ui/page/mine/ClipSourceBlockSettingPage.kt` | 接入抽出的规则，保留完整草稿和保存/清空链路，补充展示边界的简体中文注释。 |
| `app/src/main/java/com/cla/clip/master/ui/page/mine/ClipSourceBlockSettingVm.kt` | 仅修正候选/派生属性注释，明确内部候选不等同于可见行；不修改安装读取或 Flow 行为。 |
| `app/src/test/java/com/cla/clip/master/ui/page/mine/ClipSourceBlockSelectionRulesTest.kt`（新增） | 通过实际展示与勾选规则验证隐藏、重新安装、搜索/系统开关和隐藏规则保留。 |
| `app/src/test/java/com/cla/clip/master/ui/page/mine/ClipSourceBlockSettingStateTest.kt` | 保留候选合并测试，将“未安装包名仍应展示”注释改为“内部候选仍保留”；新增最终筛选测试证明不进入可见列表。 |
| `base/general/src/main/res/values/strings.xml` | 就地更新已有过滤页说明、读取失败说明和清空确认文案，不修改其他页面通用文案。 |
| `docs/clip_source_app_filter_plan.md` | 同步当前体验、测试证据、日志边界和状态。 |

1. 按 `$repo-unit-test-gate` 先写能失败的展示回归测试，再迁移并调整筛选规则；测试直接断言最终可见结果，不能只测候选是否创建。
2. 提取勾选纯规则并接入原回调，保留上限、单项增删及完整草稿保存；新增/修改的方法、变量及关键分支补齐简体中文注释。
3. 同步三类提示文案，复核加载、失败、空态、清空和手动添加边界；复用现有列表行、图标组件和包名稳定 key。
4. 执行定向测试与 app 编译，再核对差异和文档；本任务不自动提交、不安装到设备。

### 测试与验收

- A 已保存但未安装：内部候选与完整草稿包含 A，可见列表不包含 A；即使带历史名称/可读历史图标，也不可见。
- 搜索 A 包名、打开“显示系统应用”、A 已勾选：均不能让未安装 A 出现在可见列表。
- A 隐藏、B 已安装：取消 B 后完整待保存名单仍含 A；增加 C 后为 A 加 C；搜索、刷新、空列表和加载失败不清空 A。
- 同包名 A 加入安装结果后：最终列表显示 A，勾选由完整草稿恢复；应用图标加载失败不影响可见性。
- 本阶段保留当时的系统/可启动/搜索规则；可启动条件已由 2026-09-30“默认展示所有非系统应用”调整取代。已选系统应用继续可见，未选且无历史来源的系统应用仍通过开关或搜索展示。
- 勾选规则覆盖添加、移除、500 条限制及达到上限仍可移除；手动添加未安装包名后保留草稿但无可见行。
- 人工验收覆盖清空确认包含隐藏项、取消/保存的落盘时机，以及失败提示与空态；纯规则 JVM 测试不能替代真实 PackageManager 和 Compose 交互验证。
- 实施阶段先写目标测试，将现有纯规则等价迁到可测试位置后运行 `.\gradlew.bat :app:testDebugUnitTest --tests "com.cla.clip.master.ui.page.mine.ClipSourceBlockSelectionRulesTest" --tests "com.cla.clip.master.ui.page.mine.ClipSourceBlockSettingStateTest"`，确认旧行为因“未安装 A 仍可见”的断言失败，而非因符号缺失或环境失败；实现后同命令通过，再运行 `.\gradlew.bat :app:compileDebugKotlin` 和 `git diff --check`。若环境阻塞，记录具体错误和未验证项。
- 失败验证已完成：原筛选规则下执行 15 个测试，6 个因未安装条目仍可见而断言失败；没有符号或编译错误。其余 9 个规则/候选基线用例通过。
- 环境处理：第一次构建无法写入 `C:\Users\848043\AppData\Local\kotlin\daemon`，Kotlin 自动回退后完成编译并进入断言。后续同一定向测试和 app 编译增加 `-Pkotlin.compiler.execution.strategy=in-process`，不修改仓库或用户的 Gradle 全局配置。
- 实现后运行 `.\gradlew.bat :app:testDebugUnitTest --tests "com.cla.clip.master.ui.page.mine.ClipSourceBlockSelectionRulesTest" --tests "com.cla.clip.master.ui.page.mine.ClipSourceBlockSettingStateTest" :app:compileDebugKotlin -Pkotlin.compiler.execution.strategy=in-process --console=plain`，结果 `BUILD SUCCESSFUL`。XML 核验规则测试 11 个、候选状态测试 4 个，合计 15 个通过，失败/错误/跳过均为 0；`git diff --check` 通过。
- 编译仍有本轮未修改文件中的两个 WebView `databaseEnabled` 弃用警告，以及 `ShizukuServiceUnavailableTip` 原有类型判断警告；没有新增编译错误。真实设备的安装识别、图标加载、动态字体布局与清空交互未执行。
- 2026-09-28 独立只读审查已完成，无待修复项；复核了最终安装门槛、完整草稿保存、清空延迟落盘、备份链路和测试边界。当时保留原有安装缓存/节流、草稿生命周期及历史候选结构；2026-09-30 自动读取已改为上述 ViewModel 会话去重，不再使用时间节流。后台监听、草稿旋转恢复或候选数据重构仍不在范围内。

### 备份、日志与风险

- 隐藏包名仍属于有跨安装价值的用户规则，继续进入 `data/settings.json`；恢复并集、裁剪、备份计数、dirty 和系统 Auto Backup 范围沿用 [备份主方案](webdav_backup_plan.md)。本轮不改备份协议或覆盖，因此不重复修改备份主文档，不升 schema，不做数据迁移。
- 不新增运行时日志：新增逻辑是无副作用的展示/选择纯规则，安装读取、失败 reason、手动添加与备份变化由现有日志覆盖。禁止记录包名名单、App 名称、搜索词或备份内容；通过合成 fixture 和人工交互核对显隐。
- 没有外部协议、反射、keep 或序列化模型调整，无需新增 R8 规则；实施验证使用相关 JVM 测试与 app 编译，本轮不安排 release/R8 构建。
- 主要风险是误把可见名单写回设置，或搜索/系统开关绕过安装门槛；以上回归必须覆盖。读取失败时安装信息可能暂时陈旧，按现有失败/刷新契约处理，绝不据此删除持久规则。
- 方案取舍：先复用完整候选构建和历史来源观察，减少与本轮显隐无关的重构；没有新增共享组件、外部依赖或平台查询。

## 数量摘要去重（2026-09-28）

- 移除搜索框上方新增的数量简报 `Text` 及未使用的 `base_general_clip_source_block_setting_summary` 资源，保留标题下原有“已选 X 个，已读取 Y 个 App”摘要和加载/失败状态，不恢复长说明。
- 复用 `ClipSourceBlockSettingHeader` 和 `clipSourceBlockHeaderSubtitle`，原有统计来源、完整草稿、搜索和系统应用开关行为不变，不额外查询安装应用。
- 无新增组件、方法、变量、状态模型或格式化工具；仅删除重复展示及其专用注释，保留现有方法和状态边界的简体中文注释，无需拆分。
- 单元测试判断：本轮仅删除重复 UI 和未使用资源，没有修改纯规则或状态流，不新增测试或重复执行上一轮的 15 个规则测试；使用 app 编译核对资源引用和类型，运行 `git diff --check`。真实设备布局仍需观察。
- 日志与备份：无新副作用，不新增日志；不改变完整草稿、备份协议、dirty 或恢复策略。无反射或协议变化，不运行 release/R8。
- 验证：`git diff --check` 通过，生产代码已无简报及旧长说明资源引用。运行 `.\gradlew.bat :app:compileDebugKotlin -Pkotlin.compiler.execution.strategy=in-process --no-daemon --max-workers=1 --console=plain`，在 `:base:general:compileDebugJavaWithJavac` 访问 Gradle transforms 缓存中的 `lifecycle-viewmodel-savedstate-release-api.jar` 时发生 `AccessDeniedException`，因此本次 app 编译尚未验证通过。日志保留在 `build/clip-source-block-verification/remove-summary-compile.log`；本轮不扩大到清理缓存、修改权限或终止其他进程。

## 底部操作栏导航栏避让（2026-09-30）

- 原因：主 Activity 开启 `enableEdgeToEdge()`，`SecondaryPageScaffold` 将自定义底栏直接交给 Material3 `Scaffold.bottomBar`；底栏存在时需自行处理系统 Insets，普通 `Row` 的固定上下间距不能避让三键导航区域。
- 最终实现：按用户后续确认将避让移到公共 `SecondaryPageScaffold` 根层，本页底栏移除局部 `navigationBarsPadding()`，保持水平 20.dp、垂直 8.dp 业务间距；主体继续应用既有 `paddingValues`。先前的逐页最小修复已被共享方案替代，完整 Insets 契约以 [UI 主方案](app_ui_refresh_plan.md#页面骨架导航栏-insets-契约2026-09-30) 为准。
- 页面影响：清空与确定按钮由共享骨架留在导航区域外；本页筛选、完整草稿、保存回调和备份契约不变，无新增组件、方法、变量或状态。
- 单元测试检查：纯 Compose 布局 Insets 调整，依赖真实系统导航模式，不新增 JVM 单测、不采用 TDD；通过 app Kotlin 编译和 `git diff --check` 验证引用与差异，三键导航、手势导航、横屏的按钮可见性和点击仍需设备验收。
- 日志与诊断计划：无业务副作用，不新增运行时日志，避免布局重组噪声；诊断通过截图或布局检查确认按钮位于导航区之外，不输出包名、搜索词或剪贴内容。无数据、反射或外部协议变化，不修改备份逻辑，不安排 release/R8。
- 前一轮局部修复验证：`git diff --check` 通过；app Kotlin 编译在依赖任务 `:base:hidden-api:compileDebugJavaWithJavac` 读取 Gradle transforms 缓存 `appcompat-1.7.1-api.jar` 时发生 `AccessDeniedException`，未执行到 app Kotlin 编译。最新共享方案验证结果见 UI 主方案；真实设备布局未验证。

## 默认展示所有非系统应用（2026-09-30）

> 历史实施记录：本节的历史来源、已保存系统应用和搜索显隐例外已被“仅使用当前扫描应用”的严格系统开关规则替代；非系统应用无需桌面入口的规则继续适用。

- 当前状态：已完成代码与纯规则验证，标准 Gradle 构建和设备展示未验证。用户确认默认显示所有已安装的非系统应用；旧实现额外要求 Launcher 入口，与需求不符。
- 实现与边界：复用 `buildBlockedSourceAppCandidates`、`defaultVisible` 和 `filterClipSourceBlockCandidates`，将默认分类条件改为 `savedBlocked || historySource || !systemApp`，最终仍要求 `installed`。已过滤、历史来源系统应用、搜索、系统开关、完整草稿和未安装规则保留既有契约。
- 读取与职责：删除 `InstalledAppPackageReader.loadLauncherPackageNames`、Android 适配实现和 `InstalledAppInfo.isLaunchableApp` / `BlockedSourceAppCandidate.launchableApp`，规范化只接收安装条目与当前包名。沿用既有读取器和 mapper，不新增组件或类，也不改页面加载/取消边界。
- 图标：可见行既有 Launcher 图标优先、Application 图标兜底策略保持；图标查询不参与安装列表显隐，没有桌面入口也可以展示。
- 测试：先更新无桌面入口非系统应用的默认可见回归，旧生产类直接 JVM 执行 15 个测试，1 个预期断言失败、14 个通过；标准 Gradle RED 命令在 `:base:hidden-api:compileDebugJavaWithJavac` 被缓存 `appcompat-1.7.1-api.jar` 访问拒绝阻断，未执行测试。实施后验证筛选、候选合并和安装读取测试，并运行 app Kotlin 编译和差异检查；设备列表展示仍需人工验收。
- 最终验证：`git diff --check` 通过，全仓 Kotlin 源码已无安装列表的可启动字段或查询接口引用。使用现有 Kotlin 2.2.10 编译器，把当前 `InstalledAppModels.kt`、`ClipSourceBlockSelectionRules.kt` 和从当前 ViewModel 原样提取的纯候选构建/模型片段与两个测试类共同编译，再由 `JUnitCore` 执行，结果 `OK (15 tests)`；命令为 `& .\build\clip-source-block-verification
on-system-rule-tests.ps1 -OutputName 'non-system-green' -CompileCurrentRules`。片段隔离验证覆盖候选合并、安装门槛、默认非系统显隐、搜索/系统开关与完整草稿，不覆盖 Android 入口或安装读取器。
- 标准验证限制：运行 `:app:compileDebugKotlin`，以及三个相关测试类的 `:app:testDebugUnitTest` 加编译（沙箱外重试），均在 `:base:general:compileDebugJavaWithJavac` 被缓存 `okhttp-release-api.jar` 的 `AccessDeniedException` 阻断，未执行 app Kotlin 编译或安装读取器测试。日志位于 `build/clip-source-block-verification/non-system-compile.log` 和 `non-system-green.log`。额外尝试 `:app:compileDebugKotlin -x compileDebugJavaWithJavac` 被依赖 ASM 任务拒绝查询尚未完成的 Java 编译输出，不能作为编译通过。未清理缓存、修改权限或操作设备；缓存恢复后需补跑标准编译与三个相关测试类。
- 日志与诊断计划：不新增纯显隐日志；安装读取成功日志继续记录数量和耗时，移除失去来源的 `launcherCount` 字段并将修改的成功日志正文改为简体中文。现有失败 reason 和日志隐私边界沿用，禁止输出包名列表、App 名称或搜索词。
- 规则评估：仅改变页面内存模型和筛选条件，无新增持久设置、备份字段、外部协议或反射依赖；不修改备份恢复、不新增 keep 规则、不安排 release/R8。保留工作区已有无关改动，不提交或操作设备。

## 即时生效与下拉刷新修改方案（2026-09-30）

### 最新代码基线与目标

- 本轮重新核对 HEAD `d9952fa`，相关过滤代码以 `a418649`（扫描候选/图标简化）和 `8e45d11`（独立字段 Flow）后的实现为基线。先前草案对历史来源合并、图标懒加载、10 分钟节流、请求序号和系统开关语义的描述已失效，本节已经修正。
- 执行前基线页面仍有 `draftPackages`、底部确认/清空、手动添加弹窗和顶部刷新按钮；本轮已删除这些入口并接入即时提交，最终验证见本节末尾。
- 候选只来自当前安装扫描，不观察历史来源、不生成隐藏规则占位行；扫描时读取原样名称和 Application Drawable，页面直接展示，不恢复已删除的图标加载器。
- 系统应用开关双向变化都重新扫描；关闭时严格隐藏系统应用，已保存状态及搜索不提供例外。自动加载仅按当前会话是否成功读取判断，没有时间节流或请求序号；取消通过 Job 与 `ensureActive()` 处理。
- 用户最终要求共五项：下拉刷新；勾选/取消立即生效，无页面二次确认；移除清空；显示系统应用开关固定顶部右上角；去掉手动添加包名。
- 用户已要求开始执行，本轮已完成、不提交；旧验证结果属于基线，最新验证见本节“本轮实施与验证结果”。

### 页面布局与操作

| 区域 | 修改后的行为 |
| --- | --- |
| 顶部 | 左侧返回、页面标题、右上角“系统应用”文字与 Switch；无障碍描述为“显示系统应用”，不藏进菜单。删除添加与刷新图标。 |
| 数量摘要 | 只保留一处摘要“已过滤 X 个，已读取 Y 个 App”；X 是完整已提交名单数量，包含隐藏规则；Y 是当前扫描范围的快照数量，不宣称为全部安装应用数量。正文不再重复标题。 |
| 搜索 | 保留应用名/包名搜索及清除搜索词；不显示添加包名入口。清除搜索词仅清除输入，不清空过滤规则。 |
| 列表 | 原有扫描图标、名称、包名及勾选框；整行与勾选框使用同一目标状态回调，单次点击只提交一次。包名作为稳定 key。 |
| 刷新 | 列表顶部下拉刷新，短列表、空列表、搜索无结果及首次扫描失败也有可下拉容器；已有内容不被全屏加载替换。 |
| 底部 | 删除清空/确认操作栏及其专用留白；沿用公共二级骨架的导航栏 Insets，不恢复页面局部避让。 |

- 系统开关只改变扫描/展示范围，不改变屏蔽名单、dirty 或备份；沿用当前“先筛范围，再读名称/图标”的性能契约。切换开关必须重新扫描，不能用尚未读取的系统应用做纯内存筛选。
- 开关关闭后，旧快照的系统应用立即被最终展示筛选隐藏；打开后扫描成功才显示系统应用，扫描期间保留符合当前开关范围的旧行。搜索始终不能绕过关闭状态。
- 搜索词与开关仍是页面会话状态，扫描刷新不重置它们；不新增持久偏好。
- 按用户要求保留“已过滤优先”：继续使用 `sortedByDescending(savedBlocked)`，同组沿用读取器的名称/包名顺序。即时写入成功后按最新已提交名单重新分组，选中项进入已过滤组，取消项进入未过滤组；不冻结旧排序，不以尚未提交的状态提前移动行。名称、空名和 Drawable 契约保持现有实现，包名稳定 key 用于保持移动后的条目身份。
- 真机或 Preview 核对小屏、大字体、横屏和深色模式，保证右侧开关不覆盖标题且触控区域足够；若确需修正共享标题栏宽度，只调整公共布局并同步其主文档，不复制页面骨架。

### 即时保存与错误边界

1. 删除页面完整草稿，勾选和计数直接订阅 `ClipSettingsRepository.blockedPackages`。候选和搜索结果不能用于重建完整名单。
2. 回调传包名与明确目标状态：选中调用原子 `addBlockedPackage`，取消调用 `removeBlockedPackage`。复用事务内最新名单、规范化、500 条上限及真实变化判断，保留未安装/当前未扫描的规则，避免覆盖详情页或恢复并发写入。
3. 写入期间仅该包名禁用重复点击，其他行可操作。保持原已提交勾选并显示局部提交反馈，事务成功后切换；不等离页、不防抖、不弹确认。所谓立即生效以事务成功为界，后续读取规则的剪贴保存使用新名单，已读取旧快照的任务不回溯。
4. `false` 不等于 IO 失败：若最新已提交状态已经达到目标，按幂等成功处理；未达到目标则区分容量限制/无效包名并显示明确提示，不能静默当成成功。上限按完整名单计算，达到上限仍可取消已有规则。
5. IO 写入失败保留旧勾选、结束提交反馈并提示重试。设置首次读取未完成或读取失败时禁用修改，继续提供设置重读入口，不能将读失败当空名单写回。
6. 真实变化仅在仓库事务中提交名单，不推进备份版本或调度任务；下一次每日/手动备份读取最新规则。完整备份流程以 [备份主方案](webdav_backup_plan.md) 为准，名单提交、扫描和返回等待仍按本文执行。
7. 顶部返回与系统返回统一：无写入直接返回；存在已接受写入时等待完成再自动返回，不显示二次确认。写入失败留页显示错误，再次返回可直接离开，避免 ViewModel 销毁取消用户已接受的保存动作。
8. 删除手动添加入口、`showManualAddDialog`、`ClipSourceBlockManualAddDialog`、错误文案映射与 ViewModel `addManualBlockedPackage`。全仓生产调用核对后删除仅服务该入口的 `validateManualPackage`、`ManualPackageValidationResult` 和专用资源/测试；保留共有包名规范化、精确匹配和容量规则，它们仍服务持久化、详情页和备份。
9. 退出不做整单替换。过滤页 `replaceBlockedSourcePackages` 删除，但 Repository 的 `replaceBlockedPackages` 是否保留按其他调用方判断，不能因删除页面入口误删共享仓库能力。

### 刷新与可见生命周期

- 复用现有 `InstalledAppReader.loadInstalledApps(showSystemApps)` 和已引入的 Material3 下拉刷新能力；当前仓库没有现成下拉刷新包装，实施时核对本地 API，不为此升级依赖或重新实现系统查询。
- 自动入口沿用 `needsInitialLoad`：当前会话首次成功前可以重试，成功后回到前台不自动重复扫描；下拉直接重读。首次打开新导航条目仍创建新 ViewModel 并扫描，不恢复 10 分钟节流。
- 重复下拉在 loading 时忽略；系统开关变化仍取消旧 Job 并启动新范围扫描，不能因 loading 而忽略用户新开关状态。继续使用 Job 与 `ensureActive()` 防旧结果回写，不重新引入请求序号。
- 成功替换当前安装快照；卸载后行隐藏，重新安装并扫描成功后按持久规则恢复勾选。刷新、搜索、系统开关和空候选均不能删除持久规则。
- 建议所有扫描失败均保留上次可用安装快照，包括当前被映射为失败的 `package_scan_empty`；停止刷新指示并提示错误。首次失败无快照时显示可重试空态。需要表达成功零应用时，应单独定义成功语义，不用失败顺带清空旧列表。
- 保留生命周期感知 Flow 收集；将安装扫描启动/停止绑定页面可见生命周期，离开前台时取消扫描并清理该任务 loading，不影响已接受的设置写入。替换旧扫描的清理不能清除新 Job 的 loading，可用 Job 身份检查实现，无需请求序号。
- 扫描 Job 仍由 ViewModel 编排，扫描期间重型 PackageManager/名称/图标读取仍在 IO；沿用系统调用返回及逐项资源加载前的取消检查。无后台轮询、安装监听、历史来源订阅或新的应用级缓存。

### 复用与组件职责

- 重新核对当前 `ui/page/mine`、`installedapps`，app `ui/widget`，共享 `base/general` 的设置仓库/纯规则及备份主方案，并检查全仓相关调用。`rg` 在本机无法启动，使用 PowerShell 文件检索和 `Select-String` 等价检查。
- 复用 `SecondaryPageScaffold.actions`、共享 `SearchInputField`、现有 Drawable 展示契约、`filterClipSourceBlockCandidates`、`InstalledAppSelectionRules`、原子 add/remove 与备份调度。`SettingSwitchRow` 是整行设置布局，不直接套到紧凑标题栏；通过 actions slot 接入共享 `ToolbarSwitchAction`；原行抽为共享 `SelectableAppRow`，契约以 [共享表单组件方案](shared_form_components_plan.md#来源过滤共享展示组件已完成2026-09-30) 为准，不复制页面私有布局。
- ViewModel 只协调观察、扫描、写入和事件。单项提交状态及失败分类已拆为 feature-local `ClipSourceBlockWriteCoordinator.kt` 与 `ClipSourceBlockWriteState.kt`，接受可替换的读/写/提交后回调以便单测；不持有导航、Compose 或 Android 图标。
- 扫描任务/范围快照及取消身份检查拆为 feature-local `ClipSourceBlockInstalledAppCoordinator.kt`；状态模型迁到 `InstalledAppListState.kt`。页面使用 `LifecycleResumeEffect`，同会话成功但范围切换被取消时再次进入会补读目标范围，避免把旧范围成功标记误用为新范围缓存。
- 候选构建已经在 `ClipSourceBlockCandidates.kt`，直接复用现有已过滤优先的稳定分组，不改排序算法、不另造排序工具。`savedBlocked` 继续由完整已提交名单派生并负责排序，页面勾选也读取同一名单，避免草稿成为第二个事实来源。
- `ClipSourceBlockSelectionRules.kt` 保留系统开关与搜索纯规则；移除没有生产调用的草稿 toggle，相关测试改为原子提交保留隐藏规则的契约测试。
- 不重构已简化的读取器、图标和历史来源链路。新增或修改的每个类型、方法、变量、回调和关键分支在实施时补齐贴近声明的简体中文注释。

### 涉及文件与实施步骤

| 文件 | 预计调整 |
| --- | --- |
| `app/src/main/java/com/cla/clip/master/ui/page/mine/ClipSourceBlockSettingPage.kt` | 删草稿、底栏、清空/手动添加弹窗；接入下拉刷新、顶部开关、提交反馈与统一返回。 |
| `app/src/main/java/com/cla/clip/master/ui/page/mine/ClipSourceBlockSettingVm.kt` | 接入独立名单 Flow 的单项写入，刷新失败保留快照、扫描生命周期启停，删除页面整单保存及手动校验入口。 |
| `app/src/main/java/com/cla/clip/master/ui/page/mine/ClipSourceBlockWriteCoordinator.kt` / `ClipSourceBlockWriteState.kt`（新增） | 单包名目标提交、重复点击保护、错误分类和可测试提交状态；内部模型不进入协议、keep 或备份。 |
| `ClipSourceBlockInstalledAppCoordinator.kt` / `InstalledAppListState.kt`（同 feature） | 可见扫描、替换取消、失败保留快照与范围恢复；不影响名单写入。 |
| `app/src/main/java/com/cla/clip/master/ui/widget/ToolbarSwitchAction.kt` / `SelectableAppRow.kt` | 共享标题开关与稳定行 UI state，组件不依赖页面业务；含窄屏/深色/大字体 Preview。 |
| `app/src/main/java/com/cla/clip/master/ui/page/mine/ClipSourceBlockCandidates.kt` / `ClipSourceBlockSelectionRules.kt` | 保留已过滤优先及组内扫描顺序、严格系统筛选，淘汰草稿规则并同步相关注释。 |
| `base/general/src/main/java/com/cla/clip/base/general/config/ClipSourceBlockRules.kt` 与对应测试 | 删除仅用于手动输入的校验与结果；保留共享规范化、匹配、容量规则及其测试。 |
| app 与 base/general 的相应 `src/test` | 保留现有已过滤优先测试，补即时提交成功/失败后的排序回归，新增写入及扫描状态测试，复用已有原子并发与字段 Flow 隔离测试。 |
| app/base/general 的 `strings.xml` | 清理无引用手动添加/清空专用资源；调整摘要、容量与读写失败提示。通用确认、取消、清除搜索资源继续保留。 |
| `docs/clip_source_app_filter_plan.md` | 本轮维护最新实现；完成后更新最终状态和真实验证证据。 |

1. 先补能失败的即时提交、容量/IO 错误、隐藏规则保留、提交后已过滤优先分组及扫描取消回归测试，再改生产代码；环境错误或符号缺失不能当作目标测试失败。
2. 复用原子仓库并抽出提交协调，再调整扫描状态和生命周期；保持已过滤优先、扫描范围、名称、Drawable 和存储协议。
3. 接入页面交互并删掉草稿、手动添加、清空及确认代码/专用文案；核对引用和简体中文注释。
4. 执行定向测试、app 编译、文档差异检查及设备验收；未验证项据实记录，不复用旧测试记录声称新行为通过。

### 日志、备份与验证

- 日志复用现有封装/TAG：扫描开始/替换/取消记录低频阶段，成功 INFO、可恢复扫描失败 WARN；只含触发来源、showSystemApps、数量、耗时、reasonCode 和是否保留旧快照。不新增已删除的 requestId 协议，不记录每行加载或重组。
- 单项写入 DEBUG 开始、INFO 成功、WARN 容量/输入拒绝、ERROR IO 失败；字段为会话短操作编号、add/remove、changed 与低敏阶段；错误另记录 reasonCode/异常类型，不重复采集持久名单数量或写入耗时，避免额外读取和噪声。备份调度失败明确 `committed=true`，正常扫描取消不作为错误。
- 删除 `manual_package_added` 等只服务手动添加的日志；禁止记录包名、应用名称/名单、搜索词、剪贴内容、图标内容、原始异常 message。用合成数据与可控失败检查诊断分支，避免高频噪声。
- 复核 [备份主方案](webdav_backup_plan.md)：名单仍通过 `blocked_clip_source_packages` 白名单导出、恢复并集；单项只原子提交业务名单，不维护备份版本或调度。未安装/系统范围外规则继续保留，不能因删除手动添加而清理旧数据。无新增备份 schema 或名单迁移。
- 新写入状态、安装列表/Drawable、搜索词和系统开关都为会话态，不进入备份；重启后从持久名单恢复。无新外部协议/反射，不安排 release/R8。
- 采用选择性 TDD：纯提交/排序/失败契约与取消状态需要先写回归；样式和下拉手势计划通过 Preview/设备验证，不写镜像布局的 JVM 测试。
- 实施阶段计划运行 `.\gradlew.bat :app:testDebugUnitTest --tests "com.cla.clip.master.ui.page.mine.ClipSourceBlock*Test" --tests "com.cla.clip.master.installedapps.*Test"`、`.\gradlew.bat :base:general:testDebugUnitTest --tests "com.cla.clip.base.general.config.HostSettingsRepositoriesTest" --tests "com.cla.clip.base.general.config.clip.ClipSettingsFlowsTest" --tests "com.cla.clip.base.general.config.ClipSourceBlockRulesTest"`、`.\gradlew.bat :app:compileDebugKotlin` 与 `git diff --check`。本轮采用完整模块测试代替仅定向验证；真实结果见下一节，旧提交的通过记录不作为本轮证据。
- 验收：勾选/取消不用确认即可影响之后的过滤；不同包名并发不丢项、隐藏规则保留；同项提交中不重复写入；500 条上限有提示且可取消；写入失败保持旧态及原分组，备份调度失败不回滚；返回不丢接受的写入；提交成功后已过滤组在前、未过滤组在后，各组保持扫描名称/包名顺序，条目移动仍保持包名稳定身份。
- 验收：短/空/长列表与搜索无结果均可下拉；刷新保留搜索/开关/名单；扫描失败保留旧快照；系统开关严格控制范围且扫描新图标；快速切换和离开前台不被旧结果覆盖、不残留 loading；大字体、小屏、横屏、深色模式和真实图标显示通过人工检查。
- 静态验收：页面不存在添加包名、清空、确认保存入口；不恢复旧图标加载器、历史候选、时间节流和请求序号；清理专用文案后无失效引用。

### 本轮实施与验证结果（2026-10-01）

- 最终数据流：`LifecycleResumeEffect` 驱动扫描可见性，`collectAsStateWithLifecycle` 收集独立名单与提交/扫描状态。页面只做展示过滤与共享行适配，不维护草稿；勾选直接原子增删包名，返回等待业务提交。每日备份与入口说明 slot 已另按备份主方案接入，保存链路不再调用备份。
- 写入选择性 TDD：初始 8 项中 7 项预期断言失败（幂等基线已成立），实施后 8 项通过；随后同包名首尾空白变体回归在 9 项中出现 1 项预期失败，改用规范化身份去重。原排序算法不改，其成功/失败分组测试是特征与接入验证，不要求制造排序失败。
- 扫描选择性 TDD：初始 6 项均预期断言失败，实施后通过；独立审查提出“被取消旧调用迟到抛普通异常”边界，新增回归在 7 项中出现 1 项预期断言失败，修复异常分支的 `ensureActive()` 后通过。旧返回和旧异常均不能污染新任务；离页仅取消扫描。
- 仓库特征验证：真实临时 DataStore 覆盖单项增删保留隐藏规则、重复操作不变更名单、容量拒绝不变更名单、满容量仍允许移除；移除失效的备份版本断言，保留规范化、即时提交与容量边界测试。
- 已运行 `.\gradlew.bat :app:testDebugUnitTest :base:general:testDebugUnitTest :app:compileDebugKotlin --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' --console=plain`，`BUILD SUCCESSFUL`。app 37 个测试类/168 项、base/general 15 个测试类/69 项，失败、错误及跳过均为 0；日志 `build/clip-filter-final-validation.log`。最后中文注释整理另运行 app 编译，日志 `build/clip-filter-final-compile.log`。
- 验证过程中的符号缺失/编译接入错误均不算 RED；首次基线构建与新增测试相撞后改为串行构建，toast 挂起接口已改为提示协程。目标 RED 证据分别记录于 `clip-filter-write-red.log`、`clip-filter-scan-red.log`、`clip-filter-integration-red-retry.log` 和 `clip-filter-late-exception-red.log`。
- 最终静态检查：手动校验结果/入口、草稿 toggle、清空/确认/手动添加专用资源无生产引用；页面无底栏和重复标题，候选排序算法不变；新增/修改的方法、变量、回调及关键分支均补充简体中文注释。`git diff --check` 通过，独立只读审查及修复复核无剩余 Critical/Important。
- 验证边界/规则例外：`adb devices` 无连接设备，下拉手势、小屏/大字体/横屏/深色、读屏及真实图标未实际验收；已提供 Preview 定义并编译，未渲染 Preview。布局不写镜像 JVM 测试，核心状态/纯规则均有测试。无新外部协议/反射/混淆边界，不安排 release/R8；无备份 schema 变化，不修改备份协议。为保留用户未提交文档，在当前共享工作区执行，无新分支或提交。用户其他备份文档与流程产物保持原样。

### 已知取舍与开放问题

- 用户点击即提交，以事务完成为生效边界；提交中使用局部反馈，不显示虚假的已保存态，不用防抖或整单替换。
- 去掉手动添加及清空后，仅通过当前扫描到的应用逐项管理。未安装隐藏规则仍可备份恢复，重新安装后可取消；本轮不新增隐藏规则管理页。容量提示按完整名单统计，不能静默忽略新增。
- 用户明确要求保持已过滤优先，因此接受提交成功后的实时分组移动；不再采用取消该排序的建议。保留 `buildCandidatesPlacesSavedAppsFirstAndKeepsGroupOrder` 等现有测试，并补充即时写入成功/失败的分组回归。
- 既有系统查询无法强制即时中断；依靠返回后和逐项检查取消，避免继续读取大量图标。设备耗时、内存及手势仍需要实施后的实际验收。

## 开放问题

- 未来若要上架 Google Play，需要评估是否回到 Shizuku 查询、用户手动添加、系统 picker 或更窄的 `<queries>` 声明。
- 若当前安装图标在个别 ROM 上仍显示默认图标，再补 ROM 维度的低敏诊断，但不恢复旧 Provider 安装缓存方案。
- 历史来源订阅、不可见历史候选和历史文件图标读取已在“仅使用当前扫描应用”中删除，不再作为后续优化待办。

## 仅使用当前扫描应用（2026-09-30）

- 当前状态：已完成。移除与当前展示无关的历史来源合并，减少数据库观察、文件校验和候选状态。
- 数据流：`InstalledAppReader` 提供规范化、去重和稳定排序后的扫描结果；`buildBlockedSourceAppCandidates` 只为扫描应用标记 `savedBlocked` 并把已保存屏蔽项稳定移到前面。ViewModel 只组合扫描结果和已保存名单，不再注入 `ClipRepository` 或调用 `loadAllSourceApps`。
- 展示：默认只显示非系统应用；已保存、草稿选中、历史来源和搜索命中都不能绕过系统开关，只有开启“显示系统应用”后才可显示系统应用。搜索仅匹配当前名称或包名；名称缺失保留空串，包名仍作为独立副标题，不再作为主名称兜底。
- 图标：本节原专用 ImageLoader 和按可见行懒加载已被“扫描时读取应用图标”替代。当前扫描前先过滤系统范围，图标随结果返回；仍不提供加载占位，读取失败或不可用时使用 `drawable/host_ic_app_icon_default.xml` 原路径与绿色/白色，不额外 tint。
- 职责：候选模型与纯构建函数移到同 feature 的 `ClipSourceBlockCandidates.kt`；筛选/完整草稿勾选继续放在 `ClipSourceBlockSelectionRules.kt`。候选只保留包名、名称、系统标记和已保存屏蔽标记，不新增 Repository 或公共 UI 抽象。
- 保存与备份：完整名单/草稿继续保留未安装和隐藏系统包名，确认保存完整草稿；名单容量、清空确认、手动添加、备份协议及 dirty 契约不变，安装候选仍是内存派生数据，无需修改备份主方案或 schema。
- 生命周期：沿用页面生命周期感知收集、ViewModel 会话自动加载与手动刷新；去掉历史来源数据库订阅，不新增后台观察或轮询。
- 测试验证：先补仅扫描候选、空名称和系统应用无例外的失败回归；旧生产逻辑下标准 Gradle 执行 21 项测试，4 项预期断言失败，其余 17 项通过。实施后同步移除旧历史合并/图标测试，运行 `.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' '-Pksp.incremental=false' --console=plain`；初次执行完成 app Kotlin 编译后在 app Java 编译读取缓存 `okhttp-release-api.jar` 时访问拒绝，沙箱外同命令重试 `BUILD SUCCESSFUL`。完整 app 147 项测试通过（相关三个测试类 15 项），失败、错误和跳过均为 0；`git diff --check` 通过。日志为 `build/clip-source-simplify-red.log`、`build/clip-source-simplify-green.log` 和 `build/clip-source-simplify-verify.log`。真实 PackageManager 图标、空名行布局和系统开关需设备验收；未操作设备。
- 日志与诊断计划：不新增运行时日志，纯构建/筛选无副作用且已删除历史文件 IO，安装读取与图标失败的现有低敏日志继续覆盖。禁止输出包名名单、App 名称、搜索词或剪贴内容；以合成 fixture、编译与人工界面检查验证。
- 取舍/开放问题：没有外部协议、序列化、反射或持久化模型变化，不新增 keep 或 release/R8 验证；扫描排除自身和失败保留旧快照继续适用，图标懒加载已被后续“扫描时读取应用图标”方案替代。

## 扫描时读取应用图标（2026-09-30）

- 目标与范围：关闭系统应用时，在读取名称和图标之前排除系统应用及自身；开启后重新读取完整候选。为简化状态与失败边界，开关双向变化均重新扫描，重复设置同值不刷新，手动刷新使用当前开关范围。
- 数据流与职责：沿用 `InstalledAppReader`、`PackageManagerInstalledAppReader`、规范化与候选构建；读取器在 IO 线程调用 `ApplicationInfo.loadIcon()`，图标随安装信息与候选只保存在页面会话内存。页面 `AsyncImage` 直接接收 Drawable，删除 `InstalledAppIconLoader.kt` 的 Request、Fetcher、Keyer 与专用 ImageLoader，不再查询 Launcher Activity。
- 边界：图标读取失败返回 null，页面使用已有 `host_ic_app_icon_default`；单项图标失败不让扫描失败。空的所选范围仍沿用空列表提示。刷新保留旧快照，系统应用显隐继续受页面筛选约束；连续开关/刷新沿用旧 Job 取消和结果应用前 `ensureActive()`，系统适配器增加逐项取消检查，单次系统调用返回后不再读取旧范围后续资源。读取只由页面自动入口、用户开关或刷新触发，不增加后台观察；ViewModel 清理时取消任务和释放会话图标。
- 性能取舍：默认不加载系统图标；仍提前加载所选范围中尚未滚动到或被搜索隐藏的应用。开关变化重新读取以避免额外缓存状态，接受有限应用列表的一次扫描成本；真实设备耗时与内存需验收。
- 保存与备份：完整草稿和已保存包名独立于扫描范围，隐藏系统/未安装规则不裁剪；无数据库、DataStore、备份字段或 dirty 契约变化。图标不持久化、不进入外部协议或反射模型，无需 keep 或 release/R8。
- 实现步骤：先补默认扫描排除系统应用的失败回归；再调整读取接口、过滤规则和图标传递，接入开关重扫并删除加载器；最后运行完整 app 单测、Kotlin 编译和差异检查。具体文件为 `installedapps/InstalledAppModels.kt`、`PackageManagerInstalledAppReader.kt`、安装筛选规则、`ClipSourceBlockCandidates.kt`、`ClipSourceBlockSettingVm.kt`、`ClipSourceBlockSettingPage.kt` 及相关测试。
- 日志与诊断计划：INFO 扫描开始和读取器成功日志加入 `showSystemApps` 布尔字段，结果日志沿用当前数量、耗时和失败 reason；WARN 图标失败改为每次扫描汇总 `failureCount`，不记录包名 hash 或逐项日志。禁止输出包名、名称、搜索词、图标字节与剪贴内容；通过合成 fixture、日志分支检查和设备验收核对。
- 单元测试检查：筛选、图标传递和刷新范围属于行为变更，采用选择性 TDD。纯规则及可替换读取器使用 JVM 单测；真实 PackageManager 图标、系统开关交互和快速取消依赖设备，暂不新增 Android UI 测试，设备验收列为待办。
- 注释与职责：修改的方法、图标字段、扫描范围变量和取消分支均补齐简体中文注释；新纯范围规则归到 `installedapps/InstalledAppSelectionRules.kt`，系统读取复用现有适配器，页面只传递与展示图标，未新增 UI 组件或依赖。
- 验证：标准 RED 命令及沙箱外同命令重试均在 `:base:general:bundleLibCompileToJarDebug` 被原 `classes.jar` 文件占用阻断。临时 `build/installed-app-icons-validation.init.gradle` 只将 general 的构建输出移到 `build/installed-app-icon-validation/base-general`，不修改工程配置、不清理缓存、不终止其他程序；独立输出下单项 RED 实际执行并出现预期断言失败。随后执行 `.\gradlew.bat -I build/installed-app-icons-validation.init.gradle :app:compileDebugKotlin :app:testDebugUnitTest --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' '-Pksp.incremental=false' --console=plain`，`BUILD SUCCESSFUL`；完整 app 154 项单测通过，失败/错误/跳过均为 0，其中本轮新增 7 项（扫描/读取 4、范围 2、候选图标传递 1），相关五类测试共 28 项。`git diff --check` 通过，生产 Kotlin 源码已无专用图标加载器引用。日志为 `build/installed-app-icons-red.log`、`installed-app-icons-red-retry.log`、`installed-app-icons-red-isolated.log` 和 `installed-app-icons-green.log`。未执行设备交互或 release/R8；前者仍需人工验收，后者因无外部协议/反射变化不安排。

## 原样保留安装应用名称（2026-09-30）

- 目标与范围：移除 `normalizeInstalledAppName` 和 `MAX_INSTALLED_APP_NAME_LENGTH`，安装条目直接使用 `entry.label.orEmpty()`，不 trim、不截断；完整名称用于排序、候选展示和搜索，缺失标签仍为空串。
- 职责与复用：沿用读取器、候选构建与现有行组件；显示长度由页面的可用宽度、单行及省略号控制，不新增工具方法、组件或类。安装标签不持久化、不参与备份或外部协议，包名身份与保存规则沿用。
- 单元测试检查：更新既有读取测试验证 88 字符名称完整保留与 null 标签兜底，新增合成首尾空白/纯空白标签回归；先运行旧实现观察两项预期断言失败（共 8 项测试），再删除名称处理逻辑。修改的函数、字段与测试变量已补齐简体中文注释，无新增生产类或方法。
- 验证结果：普通 RED 命令先被 Gradle 缓存 `okhttp-release-api.jar` 访问拒绝阻断，沙箱外重试又被原 general/classes.jar 占用阻断；临时 `build/installed-app-name-validation.init.gradle` 仅把 general 输出移到 `build/installed-app-name-validation/base-general`。独立输出下 RED 命令执行 8 项测试，长名称与空白保留两项按预期失败。随后执行 `.\gradlew.bat -I build/installed-app-name-validation.init.gradle :app:compileDebugKotlin :app:testDebugUnitTest --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' '-Pksp.incremental=false' --console=plain`，结果 `BUILD SUCCESSFUL`；完整 app 155 项测试通过，失败、错误和跳过均为 0，读取测试 8 项通过。`git diff --check` 通过，生产源码无已删除方法及长度常量的引用；日志为 `build/installed-app-name-red.log`、`installed-app-name-red-isolated.log` 和 `installed-app-name-green.log`。未清理缓存、终止其他程序或操作设备。
- 日志与诊断计划：本次只移除无副作用的字符串处理，沿用扫描数量、耗时和失败原因日志，不新增逐项名称日志，以免泄露应用名单或产生噪声；用回归断言和现有布局约束核对。
- 取舍与待办：仅修改页面会话内存名称契约，无序列化、反射或备份变化，不安排 release/R8；真实设备超长名称的显示观感仍需人工验收。

## 变更记录

- 2026-10-01：按用户截图反馈，将无规则入口摘要缩为“未设置”，共用标题改为“剪贴过滤”；只修改原资源值，不改入口分支、导航或过滤行为。纯文案通过编译和资源调用检查验证，不新增镜像测试。

- 2026-10-01：修正读取器将空结果误判为失败的契约，正常完成扫描统一返回允许空列表的 Success，取消和真实异常语义保持；去掉临时强制失败条件并同步日志、注释与回归测试。原因是用户模拟零应用仍收到加载错误，读取器原空失败语义与页面正常空态需求不一致。

- 2026-10-01：修复首次进入误显示空应用提示，明确初始、加载、成功空结果及失败的状态边界，将无列表时的提示居中，同时保留空态下拉刷新。原因是用户反馈未读取完成不能宣称无应用；纯判断先补回归测试。

- 2026-10-01：按最新截图反馈移除胶囊图标和占位，两态仅通过边框/填充色区分，文字居中且颜色一致；完整样式和验证归口共享表单方案，页面业务接线不变。

- 2026-10-01：按用户选定的筛选胶囊替换数量行范围控件，选中采用主题填充和勾，未选中采用浅描边；继续连接原状态与回调，完整视觉契约归口共享表单方案。纯样式不新增 JVM 测试，不改变日志、备份或外部协议；编译和设备验收边界以本轮记录为准。

- 2026-10-01：补充系统应用开关视觉候选入口，完整比较归口共享表单主文档；原因是用户要求更简洁的控件并先看几个方案。此轮只更新方案，未修改布局接线、筛选、扫描或保存逻辑。

- 2026-10-01：完成数量行开关、共享顶部搜索输入、Boolean 模式恢复与返回分流，新增 6 项单测并观察返回规则预期失败后全 app 190 项通过；两轮 Kotlin 编译及差异检查通过，只读审查无可行动问题。原因是实施用户确认的截图交互；设备和 Preview 渲染仍待验收。

- 2026-10-01：用户确认执行数量行与顶部搜索方案，进入实现；先补返回分流失败测试和扫描查询契约测试，再接入 UI，尚未完成验证。

- 2026-10-01：新增数量行系统开关与顶部搜索模式草案，记录截图需求、就地实时搜索、焦点/返回/清空契约、共享组件与骨架接入、测试及日志边界；原因是用户要求数量信息右侧放开关，并以标题搜索入口替代常驻输入。本次仅文档，尚未实施。

- 2026-10-01：完成即时生效、下拉刷新、顶部系统开关与移除清空/确认/手动添加，保留已过滤优先及隐藏规则；补齐重复点击和迟到异常的测试先行回归并修复审查问题。完整 app 168 项、base/general 69 项单测和 Kotlin 编译通过，独立复核通过；记录设备/Preview/R8 未验证边界，原因是完成用户批准的方案并使最终文档与代码一致。

- 2026-09-30：用户批准执行即时生效方案，复用原子增删并拆出写入/可见扫描协调器，接入共享标题开关与应用行，删除页面草稿、清空/确认/手动添加及专用校验；保持已过滤优先。写入与扫描已完成测试先行回归，页面编译和全量测试通过；保留其他备份草案，不实施其调度变更。

- 2026-09-30：按用户要求保留已过滤优先及组内扫描顺序，撤回取消动态分组的建议；明确仅提交成功后重新分组，失败保留原状态/分组，并保留既有排序测试、补充即时提交回归。原因是用户指定排序保持现状；本次仅修订方案，不改产品代码。

- 2026-09-30：按用户最新代码重新分析并修订即时生效草案，加入移除手动添加包名要求；沿用独立 `blockedPackages` Flow、仅扫描候选、扫描时 Drawable、严格系统范围、会话首次加载及 Job 取消检查，删除草案中过期的历史来源/图标加载器/时间节流/请求序号假设。原因是用户更新了代码，需要以当前实现为依据；本次仅修改方案与页面接入摘要。

- 2026-09-30：按用户要求原样保留安装应用名称，删除读取层的名称 trim 和 80 字符截断；显示长度由既有行宽、单行和省略号控制。先观察长名称和空白保留两项预期失败，再完成生产修改；app Kotlin 编译、完整 app 155 项单测（读取 8 项）与差异检查通过，设备显示未验收。名称仅用于页面内存，不改备份或 keep/R8，无需新增日志。

- 2026-09-30：剪贴设置改接对应字段的独立 Flow，减少其他设置变化造成的无关通知；生命周期、读取失败与保存契约沿用原行为，字段 Flow 设计以 [DataStore 主方案](datastore_replacement_plan.md) 为准。

- 2026-09-30：按用户确认实施扫描范围前置过滤与图标随列表读取，简化设置页的图标加载链；双向开关变化重扫以减少缓存状态，备份与完整草稿保持原契约。

- 2026-09-30：按用户最新要求区分默认图标与加载占位，移除来源过滤页 `AsyncImage.placeholder`；加载期间保留 40dp 空白区域，读取失败或不可用时才显示 Android 默认图标，沿用当前 `host_ic_app_icon_default` 资源命名并更新变量注释。复用既有加载器和 error painter，无新增组件、类或方法；纯展示调整，不新增单测或采用 TDD，不新增日志、备份逻辑或 release/R8。执行 `.\gradlew.bat :app:processDebugResources :app:compileDebugKotlin --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' '-Pksp.incremental=false' --console=plain`，资源处理通过；依赖任务 `:base:general:bundleLibCompileToJarDebug` 因 `classes.jar` 被其他程序占用而失败，独占只读检查仍未获得访问，因此 app Kotlin 编译尚未验证。日志位于 `build/clip-source-icon-no-placeholder-verify.log`；未终止其他程序或修改构建配置，文件占用解除后需补跑。`git diff --check` 通过，设备加载过程需人工验收。

- 2026-09-30：按用户提供的 `Android.svg` 替换来源过滤页默认图标，保留原路径与颜色并转为 VectorDrawable，复用既有 AsyncImage placeholder/error。纯展示资源替换，无状态或业务契约变化，不新增 JVM 单测或采用 TDD；7 条 SVG/Vector 路径及颜色逐项一致，`git diff --check` 通过。执行 `.\gradlew.bat :app:processDebugResources :app:compileDebugKotlin --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' '-Pksp.incremental=false' --console=plain`，资源处理通过，首次编译在 hidden-api Java 任务访问缓存 `appcompat-1.7.1-api.jar` 时被拒绝；沙箱外同命令重试 `BUILD SUCCESSFUL`，日志位于 `build/clip-source-fallback-icon-compile.log` 与 `build/clip-source-fallback-icon-verify.log`。设备观感仍需人工验收。相关变量与资源注释已补齐，沿用现有图标失败日志，无需新增日志、备份逻辑或 release/R8。

- 2026-09-30：按用户最新要求简化来源过滤候选，仅使用当前扫描应用，删除历史来源参与、未安装占位候选及历史图标兜底；系统应用严格由开关控制，空名称不再回退包名。原因是展示仅需当前安装信息，原三源合并增加无用状态与 IO。先观察 4 项预期失败回归，再实施；app Kotlin 编译、完整 app 147 项单测（含相关 15 项）及差异检查通过，设备与 release/R8 未验证，无需修改备份协议或新增日志。

- 2026-09-30：补齐提交前组合验证结果，解除实现阶段缓存访问拒绝造成的编译与标准单测未验证状态。执行 `.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest :base:general:testDebugUnitTest --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' '-Pksp.incremental=false' --console=plain`，结果 `BUILD SUCCESSFUL`；150 项 app 和 64 项 base/general 单测通过。仅本次命令关闭 Kotlin daemon 和 KSP 增量生成，未更改构建配置；日志位于 `build/commit-validation.log`。设备与 release/R8 未验证，无新增外部协议，不新增运行时日志。

- 2026-09-30：按用户确认把默认候选改为所有已安装非系统应用，移除安装列表的 Launcher 批量查询、可启动字段和过期测试断言；原因是桌面入口与用户所需默认展示范围无关。保留系统应用例外、搜索、完整名单、备份和图标兜底契约；直接 JVM 观察回归预期失败后，当前生产纯规则 15 个测试全部通过。标准 Gradle 编译和安装读取器测试受缓存访问拒绝阻断，差异检查通过，设备展示未验证。

- 2026-09-30：按用户后续确认移除过滤底栏局部导航栏 padding，改由公共二级骨架统一避让；原因是收敛职责，避免其他接入页面再次遗漏。完整共享契约与最新验证以 UI 主方案为准。

- 2026-09-30：按用户确认的最小方案为来源过滤页底部按钮栏补充 `navigationBarsPadding()` 并更新中文注释；原因是全面屏绘制时普通 `Row` 未处理底部系统 Insets，清空与确定按钮被三键导航栏遮挡。仅调整本页布局，沿用备份恢复页做法。

- 2026-09-30：按用户确认简化安装列表自动加载，移除 10 分钟窗口、`forceRefresh` 重载和仅服务旧节流的 `lastLoadedAtMillis`，保留读取摘要时间。自动判断复用 `InstalledAppListState` 的派生属性并补充会话边界单测；原因是页面返回后 ViewModel 被清理，原时间窗口不能提供跨页面缓存。现有读取器、成功/失败处理、Job 取消及 `ensureActive()` 不变，日志去掉 `force` 并区分自动跳过原因。不涉及设置持久化、备份、外部协议或反射，无需新增 keep 或 release/R8。Kotlin 编译、直接 JVM 执行的 6 个新增单测和差异检查通过；完整 Gradle 单测及测试先行的失败断言观察受缓存访问拒绝阻断，真实设备交互未验证。

- 2026-09-30：安装列表刷新复用现有 Job 取消及用户新增的 `ensureActive()`，移除请求编号和仅重抛取消异常的 `try/catch`；原因是主线程同步应用结果前的取消检查已足够，无需重复维护请求有效性。读取器和成功/失败规则保持不变。单元测试门禁按等价简化处理，本次不新增测试、不采用 TDD，运行现有安装读取及候选/筛选测试和 app 编译；现有测试未直接覆盖连续刷新取消，后续在 ViewModel 可注入测试调度器与轻量仓库后补充旧扫描晚返回的状态测试。不改变持久化或备份协议，无外部协议/反射变更，不运行 release/R8。

- 2026-09-30：新增即时生效与下拉刷新修改草案，明确移除草稿/清空/底部确认、系统应用开关右上布局、原子单项提交与失败/返回契约、刷新失败保留列表及生命周期取消、稳定排序、日志/备份边界和测试先行验收；原因是用户反馈应用过滤页交互仍有问题并指定四项调整。本次只更新方案，产品代码及运行行为未改变。

- 2026-09-29：过滤名单改为 DataStore 字符串集合与原子提交，读取失败禁止剪贴入库并在设置页提供重试；原因是删除旧设置介质且防止失败时放行剪贴。

- 2026-09-28：按最新截图反馈移除重复数量简报及未使用资源，保留标题下原有数量/加载摘要；原因是两个区域重复显示相同数量，额外简报没有必要。
- 2026-09-28：根据截图反馈，把搜索框上方长说明收敛为“当前读取 X 个 App，已经屏蔽了 Y 个 App”，直接复用安装快照数量与完整草稿数量；原因是该区域只需呈现当前数据摘要，不需要长篇规则说明。
- 2026-09-28：按用户确认完成未安装应用隐藏，抽取最终筛选与完整草稿勾选规则，更新说明/读取失败/清空文案及过期注释；先观察 15 个测试中 6 个预期失败，再加入安装条件后全部通过，app 编译、差异检查和独立审查完成。原因是保证隐藏规则可继续备份且编辑其他行不会误删；未操作设备或提交。
- 2026-09-28：新增未安装应用隐藏修改草案；保留完整名单及备份恢复，明确最终安装筛选、完整草稿保存、清空与计数边界、读取失败策略、纯规则拆分和测试先行要求。原因是用户要求未安装应用不再占据过滤列表；本次仅更新方案，尚未实施。
- 2026-06-29：来源过滤页改为主进程 `QUERY_ALL_PACKAGES` + `PackageManager` 直读安装应用和可见行懒加载图标，直接删除未发布的 `installed_app_cache`、Provider 安装缓存同步、Shizuku 安装列表 AIDL、旧测试和旧备份排除目录，并将 `ShizukuConnector.VERSION` 从 `27` 升级到 `28`；原因是旧同步方案复杂且真机图标补齐慢，自用/私有分发可以接受包可见性权限。
- 2026-06-18：废弃前方案曾将安装应用图标状态从逐个图标查询改为批量 `query_installed_app_icon_states_batch`，并引入 no-backup 安装图标目录；本轮已删除该可执行链路，仅作为历史取舍记录保留。
- 2026-06-17：新增“剪贴保存过滤”设置入口和独立二级选择页接入说明，并补充 App 图标 + App 名展示和页面草稿保存边界；原因是用户需要从我的页集中管理按来源 App 屏蔽后续剪贴保存，且只看包名难以识别 App。

- 2026-10-01：备份主方案实施后来源名单只提交业务设置，移除调度回调及版本断言；即时生效、扫描与返回等待不变。
