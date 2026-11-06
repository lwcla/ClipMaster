当前状态：已完成

# 全应用多语言适配方案

## 目标与范围

- 2026-11-06 用户已确认实施第一期：完整支持简体中文、英文，默认英文，跟随系统语言；应用内语言选择后续再做。
- 本期保持 `ComponentActivity` 和现有主题，不引入 AppCompat 切换、语言偏好持久化或备份协议变化。
- 覆盖宿主、公共模块、种子搜索接入文案、通知、Toast、弹窗、无障碍描述、错误提示和广告宿主界面。第三方 SDK 自绘内容遵循 SDK 能力，单独核实。
- 用户剪贴内容、搜索词、网页内容、协议字段、数据库 code、资源 key、日志和第三方品牌不属于界面翻译范围。

## 当前状态与复用检查

- 实施前 `app/src/main/res/values/strings.xml`、`ad_strings.xml` 和 `base/general/src/main/res/values/strings.xml` 默认文案主要为中文；本期沿用原 key，默认改英文，中文放入 `values-b+zh+Hans`。
- `feature/torrent-search/src/main/res` 保留多种上游翻译，默认资源为英文，`resources.properties` 标记 `unqualifiedResLocale=en`；本期将完整中文从 `values-zh-rCN` 归一到 `values-b+zh+Hans` 并校对术语，其他语言源文件保留但不打包。
- 由设置页面和组件、宿主设置与语言调用、公共模块、全仓资源及方案文档依次检查，未找到宿主语言管理实现或专门方案文档。
- 可复用现有 `stringResource` / `getString` 调用和资源前缀；语言选择入口优先评估 `SettingDropdownEntryCard`，不复制一套设置卡片。
- `MainActivity` 继承 `ComponentActivity`，XML 基础主题继承系统 `Theme.Material.NoActionBar`；版本目录已有 AppCompat 1.7.1，当前由 `base/general` 使用。
- 宿主 DataStore 已有领域仓库与键管理，但其整个文件被系统备份排除；不能直接新增语言 key 后假定语言可跨安装恢复。

## 路线与取舍

| 路线 | 效果 | 影响 |
| --- | --- | --- |
| 先做完整翻译并随系统语言显示（建议第一阶段） | 中英文系统都能完整使用；未设置应用语言时跟随系统 | 保留 Activity 结构，重点处理翻译、资源回退、格式和布局 |
| 同时加入应用内选择（可作为第二阶段） | 用户可让应用语言与系统不同，Android 13+ 与系统应用语言设置同步 | 增加 Activity/主题适配、状态恢复、旧系统存储和后台文案验证 |

本期采用第一条路线，默认回退英文；第二条路线为后续范围。新增更多语言时，每种语言按全应用覆盖验收，不以某个依赖拥有翻译作为发布依据。

## 资源与回退设计

1. 清点各模块 `string`、`plurals`、字符串数组、硬编码文案及展示给用户的异常信息，保持原有 key 和模块职责；公共模块不反向引用宿主资源。
2. 默认 `values/` 使用完整英文，简体中文统一放入 `values-b+zh+Hans/`，宿主和种子搜索采用相同回退策略。不采用保留中文默认再添加英文目录的备选路线。
3. 默认语言调整应与完整中文资源一起落地，核对简体地区、繁体地区、未支持语言与多语言偏好列表，不把“存在一个中文目录”等同于所有中文地区行为已验证。
4. 翻译包含变量时使用带序号占位符，例如 `%1$d`、`%1$s`；复数使用 `plurals` / `pluralStringResource`，不通过拼接中文量词构建句子。
5. 固定 URL、协议常量和不翻译的品牌可标记 `translatable="false"`；不能借此隐藏缺失翻译。
6. 采用术语表统一“剪贴记录、置顶、回收站、恢复”等说法。机器翻译只能作为初稿，按钮语义、权限提示和错误提示需结合场景复核。

## 语言入口、数据流与生命周期

- 无独立选择时，沿用系统资源匹配；增加独立选择后，由用户动作进入具名语言平台封装，再调用系统/AndroidX API，由配置变化更新资源与页面。
- 以下独立语言选择设计仅属于第二期：若需兼容 Android 12 及以下，建议采用 `AppCompatDelegate.setApplicationLocales`，并同步调整 `AppCompatActivity`、兼容主题和依赖；本期不实施这些改动。
- “跟随系统”表达为清除应用语言覆盖，不保存某一时刻的系统语言标签。Android 13+ 的系统设置与应用内选择以同一应用语言状态为准。
- Android 12 及以下可评估 AndroidX `autoStoreLocales`，并验证其启动磁盘读写影响；不要另建与系统配置争夺优先级的第二套设置源。
- 默认允许配置变更重建 Activity。核对导航、筛选、输入、滚动位置和任务指针恢复，避免重复下载、重复授权或重新触发首次引导；页面观察仍遵循已有生命周期约束。
- ViewModel 保存语义状态及参数，避免长期缓存翻译后的展示字符串。日期、数量、体积等展示使用当前有效应用 locale；协议、搜索归一化与解析继续保持既有稳定 locale 规则。
- 本期 Application 与 Activity 都跟随系统配置。第二期独立切换时须重新验证旧系统 Application Context 与 AppCompatActivity 的区别；已发布通知不因 UI 重建自动更新，按任务更新策略刷新。
- 详情页与恢复失败 UI state 使用 `@StringRes` 资源 ID，显示层调用 `stringResource`；搜索读屏字段列表复用公共分隔符。视频失败通知使用通用本地化资源，持久化原因和既有错误分类不变。

## 系统声明与模块边界

- 本期不新增 `locale_config.xml` 或 Manifest 应用语言入口，显式保持 `generateLocaleConfig=false`，符合只跟随系统、不提供独立选择的范围。
- 使用 AGP 9.1 的 `androidResources.localeFilters` 仅保留 `en`、`b+zh+Hans`；保留仓库中其他上游翻译，不让其形成用户可见的局部语言混用。
- 第二期再声明 Android 13+ 应用语言列表。显式配置与自动生成二选一，先验证合并清单和语言范围，不能直接采纳依赖自带的全部语言。
- 语言平台封装按使用范围放置：只有宿主调用时留在宿主；公共通知等底层代码也需要时才进入公共模块。设置 UI 只连接状态与回调，不承担系统配置和存储逻辑。
- 本文为共享能力主文档。实施时只有影响对应页面接入、生命周期或备份契约，才同步相关页面及 `docs/webdav_backup_plan.md`，避免复制整份语言方案。

## 备份与稳定契约

- 第一阶段调整资源和展示接线，不新增持久化字段，不改变备份协议或 Room 数据。
- 增加语言选择时，明确系统自动恢复与自定义 ZIP/WebDAV 恢复的边界。建议自定义内容恢复默认不覆盖当前语言；如确定语言要随备份迁移，再补白名单、兼容字段、dirty 标记和恢复测试。
- 语言标识采用稳定 BCP 47 tag；不得把“简体中文”等翻译显示名或枚举 ordinal 当作持久化协议。
- 单纯资源翻译不需要 keep；若实现新增序列化或反射入口，再根据实际协议评估注解与 release/R8 验证。

## 实施顺序与涉及文件

1. 确认首批语言及默认回退；建立各模块资源清单与术语表，补齐中文和英文。
2. 检查格式化、复数、异常映射、通知和无障碍文案，修复英文长文本布局。
3. 在 `app/build.gradle.kts` 限制打包语言，在 `resources.properties` 声明英文默认，并核对打包产物；本期不修改 Manifest。
4. 如纳入应用内选择，再调整 `MainActivity.kt`、`res/values/themes.xml` / `values-v27`，复用设置组件并新增职责单一的平台封装，验证重建与旧系统存储。
5. 验收中英文及回退表现后再逐个扩展语言。每次新增资源都检查所有承诺支持语言，防止后续功能退回中文。

## 测试与验证

- 资源层：检查 XML 可解析性、key 完整度、占位符类型和顺序、复数规则、非翻译项及最终合并资源；建立缺失翻译检查，审查已有 lint 基线是否屏蔽问题。
- 逻辑层：纯翻译不写逐字复刻资源的单测；若新增语言选择归一化、跟随系统映射、恢复策略或 formatter，先写边界/回归测试，再实现规则。
- Android 层：本期以独立配置上下文测试 en-US/en-GB、zh-CN/zh-SG/zh-Hans、fr-FR/ja-JP、zh-TW/zh-HK、多语言顺序、格式参数和复数选择；第二期才验证应用内切换、系统独立设置同步和语言偏好恢复。
- 布局层：覆盖中英文、未支持语言、窄屏、大字体、长文本、横竖屏及深色模式；调试构建使用 `en-XA` / `ar-XB` 伪语言发现溢出、硬编码和方向问题。通过伪语言测试不代表已支持阿拉伯语。
- 实施后按受影响模块执行 `gradlew.bat :app:compileDebugKotlin`、`:app:lintDebug`、必要的 `:app:testDebugUnitTest` / `:base:general:testDebugUnitTest`；如改变资源打包或切换机制，增加 release 构建及实际安装验证。
- 本期新增 Python 资源门禁和 Android 资源集成测试；纯翻译及原分支上的资源 ID 接线不增加逐字 JVM 单测，不新增运行时日志、持久化设置或 keep 规则。
- 设备测试使用 `scripts/localization-device.init.gradle` 将测试目标改为 `com.cla.clip.master.localizationcheck`，并使用普通 Application 的 `LocalizationTestRunner`，不覆盖原应用、读取用户数据、修改系统语言或启动业务后台任务。完整模块验证命令：`gradlew.bat --init-script scripts/localization-device.init.gradle :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cla.clip.master.localization.LocalizationResourcesTest -PenableAdmobDebugAdFeature=false -PenableMagnetFeature=true`。默认关闭种子搜索的构建仍测试宿主/公共资源，模块专属复数测试明确跳过；完整验证必须显式开启该功能。

## 日志与诊断计划

- 纯翻译和布局适配不新增运行时日志，避免每次资源读取产生噪声；缺 key、占位符错误由构建与 lint 定位。
- 若实现语言切换，用户动作记录一次 INFO：入口、原/目标支持语言 tag、是否跟随系统、系统 API 级别；不记录完整设备语言列表或用户内容。
- 系统设置或恢复失败记录 WARN，字段为稳定 reasonCode、API 级别、是否可重试；不吞掉异常后假报切换成功，不自动无限重试。
- 禁止记录剪贴内容、搜索词、完整 URI、Token、Cookie、WebDAV 凭据和备份内容。验证时触发成功、失败及恢复分支，检查频次与脱敏。

## 开放问题与后续待办

- 后续评估繁体中文与其他语言；本期只承诺英文和简体中文。
- 应用内语言选择及其自定义备份策略延期，不在本期引入设置存储。
- SDK 自绘内容由 SDK 控制；验证结果需区分宿主资源覆盖和第三方内容。
- 本次全页面英文视觉验收限定 Android 12 / API 31 真机、竖屏、浅色主题与 320dp 内容区（元信息另测 240dp）；API 24、API 32、API 33+、横屏、深色和伪语言仍待验证，不能外推为所有设备均无截断。
- Python 门禁校验占位符契约，不是完整 Java Formatter 语法分析器；额外非法格式片段仍由 Android 资源构建和 lint 辅助发现，后续可强化脚本。

## 第一期实施任务与进度

执行采用仓库选择性 TDD：资源翻译以资源一致性检查和 Android 构建验证；新增校验纯逻辑先用合成 fixture 测试。复用现有资源 key 和调用点，不新增页面或持久化模型。

- [x] 公共模块：393 条默认资源（392 条可翻译）迁移为英文，保留完整简体中文资源；复用 Android 相对时间格式化。
- [x] 宿主与广告：148 条可翻译资源建立英文/Hans 对照，3 条固定配置仅保留默认。
- [x] 种子搜索：沿用英文默认，中文 342 条字符串及 1 组复数归一到 `values-b+zh+Hans`；本来没有缺键，校对 73 条术语和误译。
- [x] 资源门禁：新增 `scripts/check_localization.py` 与 13 项合成 fixture 单测，先观察缺实现失败，再完成实现并通过；CI 逐次推送运行。
- [x] 构建与运行边界：仅打包英文和简体中文，明确默认语言元数据；不声明独立选择入口。修复两处错误状态缓存、读屏分隔符、下载失败通知，沿用既有 UI 和异常分类。
- [x] 验证与收尾：Python 校验、Kotlin 编译/单测、lint、Debug APK、Release 资源处理与 API 31 独立包资源验证均通过；已完成独立代码复核并记录未覆盖边界。

重点回归：英文占位符漏参；中文单复数缺键；非中文系统落入种子搜索其他语言；系统语言变化后的缓存文案；后台失败提示直接显示中文异常。

## 本期验证结果

### 全页面与弹窗英文验收扩展（2026-11-06，已完成）

用户已授权遍历全部页面和应用自有弹窗，发现显示不全直接修复。复用现有独立测试包、真实 Context 字号与 TextLayoutResult 检查；将设备矩阵扩为英文、320dp 内容区、1.0/1.3/2.0 倍字号。全页面正常入口、列表/空/失败与长内容状态、所有应用自有 Dialog/Sheet/菜单分别建立清单，不能用一张共享组件截图替代整页验收。系统文件选择、权限和外部浏览器属于系统/第三方界面，测试只检查本应用入口文案，不执行真实授权或写出。

- [x] 清点主导航：主页（列表/我的）、普通/折叠搜索、详情、图片提取、视频提取/下载、折叠列表、回收站、备份总览/恢复、隐私、剪贴来源过滤。
- [x] 清点种子模块：搜索、浏览、详情、本地数据、书签、历史、已查看、设置、来源管理、Cloudflare/不可用；覆盖多源/安全模式/清空/来源编辑/旧数据清理弹窗。
- [x] 复用生产呈现函数构造合成状态，补全长说明、错误、进度、超长文件名和大数量；私有纯 UI 必要时提升模块内可见，入口继续管理 VM、生命周期与副作用，不复制生产 UI。
- [x] 保留初始、展开/滚动后截图，检查实际字体比例、文字省略、行边界、按钮可见和可达；用户内容摘要的既有省略明确列为白名单，不能把关键操作隐藏在白名单中。
- [x] 真机逐域运行、修复失败并复跑；统一编译、相关单测、lint、资源门禁和差异检查，补充覆盖结果与剩余边界，卸载测试包。

测试组织：宿主新增 `EnglishScreenTestBase`，复用上一轮的真实 View Context/截图与排版校验，领域用例分别归入设置备份、媒体详情、列表搜索共享 UI；种子模块因 internal 组件与模块依赖边界，在本模块使用独立 androidTest 和合成 UiState。根任务串行使用设备，各领域独立编写用例/修复。测试策略按 repo-unit-test-gate：实际字体/窗口布局用设备回归，不写镜像 JVM 样式测试；若抽出纯规则则另做先失败单测。

完整宿主页面另用 `-PlocalizationUseApp=true` 选择 `LocalizationAppTestRunner`，保留真实 App/Hilt/MainActivity/页面 ViewModel；启动前校验独立包名和未接受首启、未启用预览/自动备份的初始状态，并在 Application 创建前预置更新检查时间，触发现有限频以隔离网络。临时 DataStore 写完即关闭，真实页面继续走生产读取。本进程默认 Locale、应用和框架资源同时设置为英文，让 ICU/DateUtils 与 UI 语言一致，不写手机系统语言设置。合成剪贴只写独立数据库，设置/数据库加载完成才截图；来源筛选先输入不匹配的合成条件并等待空态，避免展示本机应用清单。无效恢复 session 验证真实回退回调，不把空白回退当作恢复正文视觉通过。

本轮已修复：共享复选弹层标题/操作被压为零高，批量数量窄列，更新多行下载按钮被圆角裁字，备份披露按钮重叠，图片批次长摘要挤占网格，列表元信息锁定小字号，搜索提示/三个筛选入口无法完整辨认，以及隐私 WebView 正文未跟随字体。按钮除文字排版外，还检查完整可点击 bounds、互不重叠和合成回调；隐私英文正文用本地 DOM 行矩形与逐屏滚动检查，生产仍关闭 JS 和网络。截图前增加本进程窗口焦点检查；三星充电提示遮挡的第二轮截图不计入最终视觉证据，关闭提示后重采。结果标题/用户 URL 保留既有摘要，但固定筛选入口不再豁免；可展开设置说明只在真实 Collapsed 语义下允许省略，并逐卡展开验全文。

日志与诊断计划：测试只记录场景 ID、字号、布局边界与合成文本，截图只使用独立测试数据；不采集用户剪贴、真实 URL/凭据或文件目录。布局修复不新增生产日志。测试 Application、网络/权限等副作用须在场景设计中隔离；完整页面集成与合成状态渲染分开记账。无持久化或外部协议变化时不改备份与 keep/R8。

测试隔离补充：宿主合并回归中，所有新增视觉用例通过，但英文进程配置污染了后续多语言顺序与既有读屏状态测试。共用宿主现于父类 `Before` 捕获应用/框架资源配置、LocaleList 和显示/格式化 Locale，`After` 无论成功或失败均恢复；沿用配置协作者，不修改生产语言机制。以实际失败为回归证据，追加“英文场景后接资源/旧组件测试”的同进程验证，不以单独重启测试掩盖泄漏。

恢复边界：协作者仅使用公共 API 恢复资源配置、原默认 Locale 与 DISPLAY/FORMAT 类别。本次设备的默认 Locale 为语言列表首项；`build/english-all-pages-isolation-device.log` 确认英文元信息 4 项 → 多语言资源 7 项 → 既有组件 13 项在同一进程内 `OK (24 tests)`。若其他设备原默认 Locale 不是列表首项，后续 `LocaleList.getDefault()` 可能按 Android 规则重排优先级，不能承诺任意多语言列表的精确顺序恢复。索引重载属于隐藏 API，本轮不引入反射、hidden API 豁免或新增测试绕行；该边界保留为测试工具后续跨语言设备矩阵，不属于生产语言机制缺陷，也不外推当前 24 项结果。

已确认的宿主覆盖如下，参数化场景按 JUnit 用例计数；每个新视觉场景内再验证 1.0/1.3/2.0 三种真实字号，不将三种字号重复算成三项用例。

| 覆盖组 | 用例 | 验收内容与证据 |
| --- | ---: | --- |
| 真实宿主页面 | 17 | 真实 MainActivity/Hilt/VM 与导航页面；包含无效恢复会话回退，恢复正文由下面状态组覆盖 |
| 列表元信息 | 4 | 240dp/320dp、完整时间、来源与正文点击边界 |
| 列表/搜索/共享选择 | 14 | 历史、筛选与菜单、批量动作、单选/多选弹层及错误 |
| 备份/恢复/我的/设置 | 48 | 生产恢复 Scaffold、长报告、更新、首启、权限、日志和预览设置状态 |
| 媒体/详情/回收站 | 57 | 图片网格与摘要共存、预览、视频进度/错误、详情动作、回收与保留期限 |
| 隐私正文与配置更新 | 2 | 英文十节逐屏到底、无横向溢出、字号更新不重载；2.0 倍正文留存 128 屏 |
| 既有布局/行为及资源 | 29 | 原英文布局 9、组件交互 13、资源回退 7；受语言污染的两项由同进程 24 项复验闭环 |

前五组 140 项和既有布局 9 项在 `build/english-all-pages-host-final.log` 通过；该合并运行原有两项测试隔离失败，修复后以 `build/english-all-pages-isolation-device.log` 的 24 项全通过覆盖，不能把原 169 项日志写成一次零失败。隐私两项在 `build/english-all-pages-real-third.log` 各自状态为成功，该次其他页面的失败已经后续回归修复。宿主最终截图位于 `build/english-all-pages-qa/host-verified/`；隐私完整正文位于 `before-final/privacy-english-page-*.png`，只使用已确认没有系统弹窗遮挡的场景。

种子模块底部验收补充：普通导航栏返回 0px、强制手势区返回 80px，真实 Activity 未开启 edge-to-edge 时 Compose 探针为 0，开启后收到实际 80px；因此修复不能只改截图宿主。模块启用 edge-to-edge，并由领域共享 Insets 提供器合并系统栏与底部强制手势区，八处生产 Scaffold 继续消费既有 innerPadding。系统栏图标通过 `enableTorrentLightEdgeToEdge` 与功能当前固定浅色主题一致，避免设备夜间模式令浅色背景上的状态字变白；API 24/25 导航栏沿用 AndroidX 暗遮罩，低版本实机仍属未覆盖边界。书签 2.0 倍末项在修复前明确失败、修复后单例通过；最终完整 40 项在 `build/english-all-pages-torrent-final-device.log` 全通过（406.494 秒），包含真实手势区边界、键盘/系统窗口遮挡和进程配置清理。真实生产 Activity 的额外 1 项在 `build/english-all-pages-torrent-production-window.log` 通过，原生/Compose 均收到 80px，状态栏和导航栏的浅色背景图标标志均为 true；不再通过测试内二次设置窗口来掩盖生产行为。

最终有效设备用例共 **212 项**：上表宿主 171 项、种子模块 40 项、真实种子 Activity 1 项；复跑同一用例不重复累计。模块 40 组的初始/末态截图（共 240 张，三种字号）保存在 `build/english-all-pages-qa/torrent-final/`，其中两组是纯 API slot/接口分派测试，不把直接置于窗口顶端的 12 张 fixture 图当作生产页面视觉证据；实际宿主 Mine/Detail 页面由上表真实页面测试覆盖。其余 38 组生产呈现与十类弹窗完成最终大字号人工复核，原生窗口证据保存在 `host-verified/real-torrent-activity-window-production.*`。Cloudflare 不可用场景的 2.0 图曾被不抢焦点的悬浮通知遮挡，独立重跑 `cloudflareUnavailable` 再次通过（9.633 秒），该场景六张图片已替换并确认无遮挡；记录为 `build/english-all-pages-torrent-clean-capture.log`。应用自有页面、Dialog、Sheet、菜单及上述长内容状态均纳入清单；系统文件/权限界面、第三方 SDK/网页正文和真实网络搜索、授权、备份写出、删除等外部业务流程不在本次视觉结论内。

最终生产验证命令：`gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest :feature:torrent-search:testDebugUnitTest :feature:torrent-search-api:testDebugUnitTest :app:lintDebug -PenableAdmobDebugAdFeature=false -PenableMagnetFeature=true --console=plain`，`build/english-all-pages-final-verification.log` 为 BUILD SUCCESSFUL（3 分 6 秒）；app 418、种子实现 656、API 4，共 **1078 项 JVM 测试**，失败/错误均为 0，lint 为 **0 errors、89 warnings、3 hints**。纯排版、WebView 字号与系统 Insets 使用先失败的真实设备回归，不增加镜像 JVM 样式断言；旧资源基线测试同步已确认的 Hans 路径与术语后，保留完整快照和精确断言。Python 21 项与 7 模块资源一致性检查通过。无新持久化/备份/反射协议；本轮未重复完整 release/R8，跨模块 Compose 接口签名由编译、javap 与两条真实接口设备调用验证。

收尾：截图与日志取回后，三个独立包 `com.cla.clip.master.localizationcheck`、`.test`、`.torrent.test` 均卸载成功；`pm path com.cla.clip.master` 确认原应用仍安装。本轮未替换原应用、读写原数据或修改系统语言/字号。`git diff --check`、最终 Python 21 项和 7 模块资源检查通过；本轮未提交或推送。新增/修改声明补中文注释，组件按领域复用与拆分，无新增生产日志、备份字段或 keep 规则；测试工具的非首项 Locale 恢复和跨设备视觉边界见上文，不以当前机型结果外推。

### 追加英文真机布局验收（2026-11-06，已完成）

以下为扩展到全页面之前的历史验收记录，当前覆盖与剩余边界以上方完整矩阵为准。

用户要求进一步确认英文是否截断。本轮复用生产组件和独立测试包，在 Samsung SM-G9730 / Android 12 / API 31 真机以英文配置、320dp 内容宽度及 1.0/1.3 倍字号测量 `TextLayoutResult` 并保存截图；搜索菜单使用实际 220dp 宽度，详情动作另加 240dp 窄宽度。覆盖标题栏（含双动作/RTL）、权限设置、下拉设置、搜索字段选项、详情链接动作、图片操作区、批量操作栏和首次说明弹窗。剪贴正文预览、收起的说明与搜索筛选摘要允许按既有设计省略，关键动作和页面标题完整可读。

- 真机截图确认并修复：标题栏固定侧边留白导致省略；通知权限标题截断；搜索字段长选项截断；图片下载按钮挤压状态。下拉设置改为标题/当前值纵向排列；详情两处不可换行动作统一为 `DetailLinkActions`。分别同步标题栏、共享表单、图片、详情主方案，保留既有回调/生命周期/保存契约。
- 新增 `EnglishLayoutDeviceTest` 9 项；另复跑 `ExpandableSettingSwitchEntryCardTest` 9 项、`ImageCandidateToolbarTest` 4 项，共 22 项真机测试通过（最终复跑 32.787 秒）。检查实际文字行边界、是否省略、目标字号确实生效及可见性；审查后补充双动作 RTL 不重叠、详情点击回调与不可提取状态断言并全部通过。弹窗使用独立 View Context 继承字号，避免只改 LocalDensity 导致假大字测试；允许字体不足一像素的舍入差异，不以 paragraph 的排版预留宽度误报字形溢出。
- 测试构建：`gradlew.bat --init-script scripts/localization-device.init.gradle :app:assembleDebug :app:assembleDebugAndroidTest -PenableAdmobDebugAdFeature=false -PenableMagnetFeature=true --console=plain`。安装独立 APK 后，以 `adb shell am instrument -w -r -e class` 指定以上三个测试类和 `com.cla.clip.master.localizationcheck.test/com.cla.clip.master.localization.LocalizationTestRunner`；日志 `build/english-layout-final-device.log`。最终截图取回后已成功卸载两个测试包，并只读确认原应用仍安装；原应用数据和系统语言/字号没有改动。
- 最终生产验证：`gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug -PenableMagnetFeature=true --console=plain` 成功，418 项宿主单测通过，lint 0 errors、89 warnings、3 hints；日志 `build/english-layout-final-build.log`。纯布局依赖实际字体和 Android 窗口，不新增镜像 JVM 测试；已先保留真实失败截图和设备回归。
- 截图保存在 `build/english-layout-qa/before/`、`second/` 和 `after/`，最终 18 张截图覆盖 9 组场景和两个字号；截图均为合成数据的独立组件宿主。没有逐页走完原应用的完整业务流程，不据此承诺所有页面、横屏、2.0 倍字号、第三方页面或其他 API 均无截断。
- 日志与诊断计划：布局本身不新增运行时日志或 IO；用断言、截图和测试报告定位。不改变持久化、备份、序列化或反射契约，不重复完整 Release/R8；独立复核未发现阻断项。

### 第一期资源验证（2026-11-06）

- `python -m unittest scripts.test_check_localization scripts.test_github_release_version`：21 项通过，其中本期资源门禁 13 项；`python scripts/check_localization.py`：7 个模块，0 个问题。
- `gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest :base:general:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:processReleaseResources -PenableMagnetFeature=true --console=plain`：构建成功，宿主/公共模块共 565 项 JVM 测试通过；lint 0 errors、89 warnings、3 hints。lint 首次暴露的 `MinePage` 资源读取问题已改为组合阶段 `stringResource`，最终复跑通过。
- 上述独立包设备命令：Android 12 / API 31 上 7 项通过、0 失败、0 跳过。首轮因默认关闭种子搜索缺资源而失败，随后修正可选模块门控并显式开启功能完成验证；默认关闭模块时宿主测试仍执行，模块专属用例明确跳过。
- `aapt2 dump configurations` 检查最终 Release 资源产物：语言配置仅 `en` 与 `b+zh+Hans`（另有完整默认英文），未带入其他上游翻译。
- 测试使用 `com.cla.clip.master.localizationcheck` 独立身份和普通 Application，测试工具已自动移除两个测试包；只读复核原 `com.cla.clip.master` 安装仍在，未改变系统语言或原应用数据。
- `git diff --check` 通过。新增/修改声明补充中文注释；未引入运行时日志、语言持久化、Room/备份/反射协议变化，因此不新增 keep 规则。本期仅做 Release 资源处理，未重跑完整 Release/R8；跨 API 和完整视觉验收见后续待办。

## 官方参考

- [Android 本地化与默认资源](https://developer.android.com/guide/topics/resources/localization)
- [应用语言设置、Compose/AppCompat 约束及系统声明](https://developer.android.com/guide/topics/resources/app-languages)
- [伪语言测试](https://developer.android.com/guide/topics/resources/pseudolocales)

## 变更记录

- 2026-11-06：完成全页面/应用自有弹窗英文真机验收，累计 212 个有效设备用例和 1078 项生产单测；修复长文案、大字体、WebView 缩放、Compose 接口分派及真实手势区/系统字对比度问题，保存最终截图、剔除并重拍被通知遮挡的证据，清理三个独立测试包。主方案状态改为已完成，保留其他设备/API、横屏、深色、第三方界面和完整 release/R8 为明确未覆盖项。

- 2026-11-06：补充测试进程配置恢复的公共 API 边界及同进程 24 项通过证据；非首项默认语言可能触发列表重排，作为跨语言设备测试范围保留，不引入隐藏 API 或扩大本轮功能。

- 2026-11-06：用户要求全部页面/弹窗和更大字号真机验收，进入扩展检查；建立逐域覆盖清单、2.0 倍字号及长内容滚动验证，直接修复发现的布局问题。

- 2026-11-06：新增多语言适配草案，记录现有资源、两阶段路线、Activity/主题约束、种子搜索依赖语言范围、备份边界和验收策略；原因是用户询问如何从当前中文宿主推进到全应用多语言支持，本轮未实施业务改动。
- 2026-11-06：用户确认第一期，状态改为实现中，明确英文默认、简体中文随系统、应用内切换延期；补充任务拆分和验证边界。
- 2026-11-06：完成 7 模块中英文资源、发布语言过滤、错误状态资源化、通知与读屏文案修正、CI 门禁和独立包设备验证，状态改为已完成；保留应用内选择和跨 API/完整视觉验收为后续范围。
- 2026-11-06：追加真实组件英文布局验收并修复标题、设置、搜索选项、图片和详情动作布局；明确资源测试与视觉测试的覆盖边界，记录 22 项设备回归、418 项宿主单测及截图证据。
