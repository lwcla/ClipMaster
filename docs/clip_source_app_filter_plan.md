状态：已完成

# 按 App 过滤剪贴保存方案

## 当前状态

2026-09-30 已移除安装应用名称规范化：直接保留 PackageManager 标签，缺失标签才转为空串，删除 80 字符上限和名称 trim。名称显示复用现有行宽约束、单行与省略号；本轮 app Kotlin 编译、完整 app 155 项单测及差异检查通过，名称读取测试 8 项通过；独立构建输出绕开原 general/classes.jar 占用，未修改工程配置。真实设备显示与 release/R8 未验证，最终名称契约以“原样保留安装应用名称”章节为准。

2026-09-30 已完成“先按系统开关过滤，再随列表读取图标”：读取器先排除自身和范围外系统应用，再读取名称与 Application 图标，页面直接展示 Drawable，专用 Coil 图标加载器已删除。开关变化重新扫描；完整草稿与备份契约保持。app Kotlin 编译、完整 app 154 项单测及差异检查通过；因原 general/classes.jar 被占用，本轮通过临时 init 将该模块输出改到独立验证目录，未修改工程配置。最终契约及本轮验证以“扫描时读取应用图标”章节为准；真实设备图标、开关与耗时/内存尚未验收，未运行 release/R8。

2026-09-30 当前扫描候选简化已完成：设置页仅使用本机扫描到的应用，不再订阅历史来源或创建未安装占位候选；已保存屏蔽应用优先，默认严格隐藏系统应用，名称缺失显示空名，图标不可用显示默认图标。app Kotlin 编译和完整 app 147 项单测通过，相关测试 15 项，差异检查通过；真实设备与 release/R8 未验证。当前实现及验证以“仅使用当前扫描应用”章节为准，下文旧的候选合并、历史名称/图标兜底与系统应用例外均为被替代的历史设计。

2026-09-30 此前提交的历史复核：app Kotlin 编译、app 150 项单测及 base/general 64 项单测全部通过，失败、错误和跳过均为 0；`git diff --check` 通过。这是本次候选简化之前的验证记录，本次结果以“仅使用当前扫描应用”章节为准；设备列表展示与连续刷新取消交互仍未验收。

2026-09-30 已按用户确认调整默认列表：显示所有已安装的非系统应用，不再要求桌面启动入口。复用现有候选构建与最终筛选，移除安装列表专用的 Launcher 查询和可启动字段；当前纯规则直接 JVM 执行 15 个测试通过，标准 Gradle 测试及编译仍被缓存访问拒绝阻断，详见“默认展示所有非系统应用”。

2026-09-30 底部操作栏导航栏避让已改由公共 `SecondaryPageScaffold` 统一负责，移除本页局部 padding；完整契约以 UI 主方案为准，接入及验证边界见“底部操作栏导航栏避让”。

2026-09-30 已按确认方案移除安装列表的 10 分钟节流：同一 ViewModel 会话内正在读取或已有成功结果时跳过自动加载，首次失败允许下次自动入口重试；手动刷新始终重读。返回弹出导航条目后重新打开创建新 ViewModel，仍自动读取，不引入应用级缓存。独立 Kotlin 编译、6 个新增纯状态测试及差异检查通过；完整 Gradle 单测仍受缓存 JAR 访问拒绝阻断，详见测试验证。

2026-09-30 已完成安装列表刷新方法的协程取消简化。`git diff --check` 通过；定向测试与 app 编译均在依赖模块 `:base:hidden-api:compileDebugJavaWithJavac` 被 Gradle 缓存 `appcompat-1.7.1-api.jar` 的 `AccessDeniedException` 阻断，提升权限重试仍失败，因此本次编译和测试未完成。

2026-09-28 的“保留未安装应用规则、隐藏对应列表行”已完成，上一轮 15 个定向测试、app 编译和差异检查均通过，独立只读审查未发现需要修复的问题。随后把长说明改为数量简报，但与标题下原有摘要重复；按最新截图反馈已移除新增简报，只保留原有头部摘要。本次删除后的差异与资源引用检查通过，编译受 Gradle 缓存 JAR 访问拒绝阻断，不能将上一轮通过视为本次编译通过。真实设备交互尚未实测。

应用通过 Shizuku 进程直读剪贴板，并把来源包名、来源名称、来源图标 hash 和剪贴 payload 提交给主进程 Provider。主进程由 `ClipHelper.processClipText(...)` 统一执行去重、链接解析、入库、通知和自动备份 dirty 标记。历史来源 App 继续缓存在 `source_apps` 表，搜索页来源筛选只读取 `source_apps`。

来源过滤能力已经落地：过滤名单只保存完整包名，按完整包名精确匹配，只影响未来剪贴保存，不删除、不隐藏历史记录。过滤页按自用/私有分发取舍声明 `android.permission.QUERY_ALL_PACKAGES`，由页面自动入口、用户刷新或系统开关变化触发主进程 `PackageManager` 读取；先按范围过滤，再读取名称与 Application 图标。安装列表不查询或保存可启动标记，不使用 Shizuku 安装应用缓存、Provider 同步、Room `installed_app_cache` 或 no-backup 安装图标目录。未安装包名继续参与完整名单和备份恢复，最终列表仅显示安装列表已识别的候选；本轮无需卸载重装，不新增数据迁移或清理代码。

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
- “我的”页过滤名单入口、独立二级选择页、显示系统应用、手动添加、清空确认和重新读取应用列表。
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
- 默认展示所有非系统 App，不要求桌面入口、历史来源或已过滤记录；系统 App 仅由“显示系统应用”开关放行，已保存、草稿勾选和搜索命中都不提供例外。
- 首次未读取到安装列表时不显示历史/已保存占位行；普通刷新失败保留当前内存安装列表并显示错误。允许手动添加、明确清空完整草稿和重新读取，读取失败本身不删除规则。
- 手动添加拒绝空白、当前 App、超长、明显危险字符和超过 500 个的名单；不过度限制厂商特殊包名。添加未安装包名后仅加入完整草稿、不新增可见行，页面确认后保存。
- 数量统计包含隐藏规则的完整名单/草稿，标题下沿用“已选 X 个，已读取 Y 个 App”摘要，不再添加第二行数量简报；清空全部需要二次确认并包含隐藏规则，页面确认保存后才落盘。
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
- 安装应用读取日志只记录阶段、数量、耗时和 reasonCode，例如 `package_visibility_denied`、`package_scan_failed`、`package_scan_empty`；禁止记录完整 App 列表、图标内容或用户剪贴内容。
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
4. 页面确认仍把完整 `draftPackages` 传给 `replaceBlockedSourcePackages`，复用 `ClipSettingsRepository.replaceBlockedPackages` 的规范化、真实变化判断和原子 dirty 版本；写入成功后调度自动备份。
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
- 最终验证：`git diff --check` 通过，全仓 Kotlin 源码已无安装列表的可启动字段或查询接口引用。使用现有 Kotlin 2.2.10 编译器，把当前 `InstalledAppModels.kt`、`ClipSourceBlockSelectionRules.kt` 和从当前 ViewModel 原样提取的纯候选构建/模型片段与两个测试类共同编译，再由 `JUnitCore` 执行，结果 `OK (15 tests)`；命令为 `& .\build\clip-source-block-verification\non-system-rule-tests.ps1 -OutputName 'non-system-green' -CompileCurrentRules`。片段隔离验证覆盖候选合并、安装门槛、默认非系统显隐、搜索/系统开关与完整草稿，不覆盖 Android 入口或安装读取器。
- 标准验证限制：运行 `:app:compileDebugKotlin`，以及三个相关测试类的 `:app:testDebugUnitTest` 加编译（沙箱外重试），均在 `:base:general:compileDebugJavaWithJavac` 被缓存 `okhttp-release-api.jar` 的 `AccessDeniedException` 阻断，未执行 app Kotlin 编译或安装读取器测试。日志位于 `build/clip-source-block-verification/non-system-compile.log` 和 `non-system-green.log`。额外尝试 `:app:compileDebugKotlin -x compileDebugJavaWithJavac` 被依赖 ASM 任务拒绝查询尚未完成的 Java 编译输出，不能作为编译通过。未清理缓存、修改权限或操作设备；缓存恢复后需补跑标准编译与三个相关测试类。
- 日志与诊断计划：不新增纯显隐日志；安装读取成功日志继续记录数量和耗时，移除失去来源的 `launcherCount` 字段并将修改的成功日志正文改为简体中文。现有失败 reason 和日志隐私边界沿用，禁止输出包名列表、App 名称或搜索词。
- 规则评估：仅改变页面内存模型和筛选条件，无新增持久设置、备份字段、外部协议或反射依赖；不修改备份恢复、不新增 keep 规则、不安排 release/R8。保留工作区已有无关改动，不提交或操作设备。

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

- 2026-09-29：过滤名单改为 DataStore 字符串集合与原子提交，读取失败禁止剪贴入库并在设置页提供重试；原因是删除旧设置介质且防止失败时放行剪贴。

- 2026-09-28：按最新截图反馈移除重复数量简报及未使用资源，保留标题下原有数量/加载摘要；原因是两个区域重复显示相同数量，额外简报没有必要。
- 2026-09-28：根据截图反馈，把搜索框上方长说明收敛为“当前读取 X 个 App，已经屏蔽了 Y 个 App”，直接复用安装快照数量与完整草稿数量；原因是该区域只需呈现当前数据摘要，不需要长篇规则说明。
- 2026-09-28：按用户确认完成未安装应用隐藏，抽取最终筛选与完整草稿勾选规则，更新说明/读取失败/清空文案及过期注释；先观察 15 个测试中 6 个预期失败，再加入安装条件后全部通过，app 编译、差异检查和独立审查完成。原因是保证隐藏规则可继续备份且编辑其他行不会误删；未操作设备或提交。
- 2026-09-28：新增未安装应用隐藏修改草案；保留完整名单及备份恢复，明确最终安装筛选、完整草稿保存、清空与计数边界、读取失败策略、纯规则拆分和测试先行要求。原因是用户要求未安装应用不再占据过滤列表；本次仅更新方案，尚未实施。
- 2026-06-29：来源过滤页改为主进程 `QUERY_ALL_PACKAGES` + `PackageManager` 直读安装应用和可见行懒加载图标，直接删除未发布的 `installed_app_cache`、Provider 安装缓存同步、Shizuku 安装列表 AIDL、旧测试和旧备份排除目录，并将 `ShizukuConnector.VERSION` 从 `27` 升级到 `28`；原因是旧同步方案复杂且真机图标补齐慢，自用/私有分发可以接受包可见性权限。
- 2026-06-18：废弃前方案曾将安装应用图标状态从逐个图标查询改为批量 `query_installed_app_icon_states_batch`，并引入 no-backup 安装图标目录；本轮已删除该可执行链路，仅作为历史取舍记录保留。
- 2026-06-17：新增“剪贴保存过滤”设置入口和独立二级选择页接入说明，并补充 App 图标 + App 名展示和页面草稿保存边界；原因是用户需要从我的页集中管理按来源 App 屏蔽后续剪贴保存，且只看包名难以识别 App。
