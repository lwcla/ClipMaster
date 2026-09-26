状态：实现中

# 广告模块化方案

## AdMob / Google Play 扩展（2026-09-18，实现中）

本节是本轮已确认需求的唯一权威说明；下文 CSJ、uni-ad v1 的“仅详情页”限制保留为对应国内 adapter 的历史/现行范围，不限制 AdMob。

### 广告位与展示契约

独立 Play 包使用 GMA Next-Gen 1.4.0，保留国内包现有来源。共 16 个位置：home_feed_native、search_feed_native、detail_native、folded_feed_native、folded_search_native、recycle_feed_native、video_extract_native、video_download_progress_native、video_download_result_native、image_extract_native、image_download_progress_native、image_download_result_native、video_history_native、image_history_native、backup_overview_native、backup_restore_native。

单列真实内容第 1、21、41…条之后插入；四列网格第 4、24、44…张之后插入，不足一行放在已有内容后。所有列表含普通/折叠搜索、下载记录都不设累计广告上限。下载记录图片按外层批次计数。空态、多选管理、纯错误、权限和确认弹层隐藏；图片候选勾选不属于管理多选。广告不进入 DAO、业务数量、选择集合或备份。

视频提取、下载/合并、下载结果三个独立单元；图片提取、下载进度、下载结果三个独立单元。备份主页和恢复流程广告位在标题栏下正文上方，正常滚动，不插入记录列表。恢复媒体重新关联和预览弹层不接入。媒体任务必须展示真实内容，纯进度或纯失败页面不请求。

### 实现与复用

沿用 ad-api、Hilt 多绑定、选择器、会话保险丝、AppSetting 开关、生命周期观察；新增 ad-admob adapter 和按 source 的 UMP 隐私接口。宿主提供独立页面广告会话/布局协调器，广告不持有业务 Repository。SDK 类型不泄漏。统一列表位置规则与锚点算法为纯协作者。

单列列表优先把广告作为稳定业务 Lazy item 外层 Column 的尾槽（广告不在业务卡片手势内部），保留原 Paging 索引与取消置顶行为，广告子树使用独立请求身份。这是渲染实现的取舍，不改变独立广告身份/数量/SDK 生命周期；网格首行与首广告共用全宽 Lazy 外壳，以便首批媒体可用就准备广告，后续广告为独立全宽条目。首条广告在首批真实内容出现且页面可见后准备，其余按锚点可见性请求；只处理已加载快照。

每个页面会话/阶段/位置一次请求；阶段变化释放旧资源并作废迟到回调。4 秒广告请求超时，无填充/失败零占位。每页最多同时持有 3 个广告对象，仅限制驻留内存，不限制继续浏览出现新位置。搜索条件稳定 800ms 后请求；取消查询时释放旧会话，不增加搜索专属数量上限或 30 秒冷却。

### 布局与交互

SDK 成功仅变为待展示。按下、拖动、滚动、惯性、侧滑动画和确认操作期间不改变布局。展示/收起前保存首个可见真实内容 key/offset，之后恢复；锚点被删依次找后继、前驱、起点，原有置顶纠偏优先。SDK 回调不得触发下载/删除/恢复按钮或导航。请求保留同步的 `canRevealNow` 回调，SDK 同帧返回也必须读取实时按压状态，不能只用重组前的布尔快照。撤回同意立即释放，可在按下期间短暂保留无交互等高占位，松手即清除。原生素材和 AdChoices 不裁切，不以过渡动画掩盖布局跳动。

### 构建、隐私和备份

仓库 `gradle.properties` 默认设置 enableAdmobDebugAdFeature=true、enableAdmobReleaseAdFeature=false、appDistribution=play，直接运行 debug 即接入 AdMob 测试模块，play 禁止国内 SDK。命令行仍可覆盖开关；配置脚本在属性缺失时保留 false/domestic 的保守兜底。宿主暴露 IS_PLAY_DISTRIBUTION、ADS_ADMOB_ENABLED。debug 固定 Google 测试 App/Native ID；正式参数为 admobReleaseAppId 与 admobRelease{PlacementName}AdUnitId，16 个正式单元独立且拒绝测试 publisher。正式参数齐全后再开启 release，避免配置期校验阻断 debug。Play 更新入口转商店，禁止外部 APK 更新。

2026-09-26 调试默认值调整仅复用既有模块装配、测试 ID、来源选择和 UMP 流程，不绕过隐私资格、敏感内容、页面可见性或交互门控；不新增组件、类、方法、持久化字段及备份契约。未新增单测或采用 TDD，因为仅切换已有构建参数并修正注释，没有新增可独立表达的业务规则；通过默认配置的 APK 构建、宿主与广告模块既有单测，以及生成配置检查验证。日志继续沿用下节既有低敏事件，无需新增日志。正式 release/R8 待真实 ID 配齐后验证，真机广告填充仍需设备验收。

UMP 每启动刷新；canRequestAds 是 Google 请求资格依据，不能把国内隐私弹窗、旧缓存、表单关闭等同于授权。入口按 UMP 要求展示；用户改变选择立即释放旧广告。SDK 主进程按需后台初始化且幂等。广告与同意临时状态不进入本地/WebDAV 白名单，不触发 dirty，核对系统备份排除。广告模块不接收用户正文、搜索词、Cookie、WebDAV 凭据、文件名、路径或完整业务模型。

### 日志与诊断计划

INFO/DEBUG：首次请求、加载、展示、释放，字段仅 source/placement/event/reasonCode/durationMs/sdkVersion。WARN：初始化和渲染异常，触发会话保险丝；普通无填充只记录低级别状态。布局推迟仅低频 reasonCode，禁止内容 key、查询、任务实体、设备 ID、响应体和所有凭据；SDK 异常仅映射错误类别，不直接打印 message。使用合成测试数据检查隐私边界。

### 验证与外部发布条件

规则、阶段、释放、布局、缓存先写失败测试再实现；Compose 绑定/原生 View 用编译及真机验收，不对样式堆单测。验证 ad-api/ad-admob/app/base:general 单测、debug 编译、Play bundleRelease/R8、无广告/国内变体、合并 manifest 与依赖树、git diff --check。关注单列 0/1/20/21/40/41、网格 0/1/4/24/44、长列表无上限、按住操作时广告完成、旧阶段迟到回调、备份 dirty 不变。

账号持有人需要完成 Play/AdMob 注册、网站/隐私政策/app-ads.txt、16 单元、Data safety、广告 ID 与目标受众声明及审核。代码测试广告成功不等于已完成后台审核；本轮不自动注册账户、不发布商店、不建设远程后台。

### 实现取舍与开放问题

- 列表重排使某个位置的原内容锚点消失时释放原广告，不在同一会话重试或补位；分页追加保持原身份。页面重新进入或搜索条件改变才创建新会话。
- 普通恢复执行阶段当前只有进度内容，因此隐藏广告；预检与实际恢复结果可展示同一个恢复广告单元。未为广告更改恢复状态协议或事务。
- 图片批次复用既有条目观察契约，未为广告增加媒体扫描；缩略图仍按视口读取。超大批次的元数据分页是既有待优化边界。
- 原生素材尺寸由真实素材测量，加载失败零高度。首屏顶部未滚动时保持顶部，不主动滚动跳过广告；已有正文滚动位置按稳定内容锚点/高度差保护。
- 系统备份已精确排除经官方 SDK 字节码核实的 `GoogleMobileAds.xml`、`pcvmspf2.xml`、`paid_storage_sp.xml`、`__GOOGLE_FUNDING_CHOICE_SDK_INTERNAL__.xml`，同时覆盖 Auto Backup 与设备迁移。用户于 2026-09-19 确认仅排除上述 Google 专用文件，不排除整类 sharedpref 或默认偏好文件 `com.cla.clip.master_preferences.xml`，以免影响其他设置。默认偏好文件保留既有系统备份行为，其中 IABTCF/IABGPP 同意键可能随系统备份恢复，不承诺其已被隔离；启动仍按既有 UMP 流程更新同意信息并检查 canRequestAds。此项决策已关闭，不再等待授权。自定义备份继续使用原有字段白名单，广告对象与 UMP 状态没有加入备份模型。
- Play 测试配置 merged manifest 含 AD_ID、READ_BASIC_PHONE_STATE、ACCESS_NETWORK_STATE、WAKE_LOCK；宿主原有 QUERY_ALL_PACKAGES、媒体权限等继续保留。正式发布前需按实际功能核对用途及商店声明，不能把测试编译当作上架验收。

官方依据：[Next-Gen 接入](https://developers.google.com/admob/android/next-gen/quick-start)、[UMP 隐私](https://developers.google.com/admob/android/next-gen/privacy)、[无内容页面政策](https://support.google.com/publisherpolicies/answer/11112688?hl=en)。

### 2026-09-19 本地验证结果

2026-09-19 验证时，实现保存在隔离工作树分支 `codex/admob-integration`，当时未提交、未发布；后续集成进展见下方变更记录。总状态保留“实现中”，直至正式发布验收完成；系统备份排除范围已获用户确认。

| 验证 | 结果 |
| --- | --- |
| Play 测试配置 `:app:compileDebugKotlin` | 最终源码通过；GMA Next-Gen 1.4.0 / UMP 4.0.0 |
| 默认配置 `:app:compileDebugKotlin` | 通过 |
| `:app:testDebugUnitTest` | 94 项通过 |
| `:base:general:testDebugUnitTest` | 50 项通过 |
| `:feature:ad-api:testDebugUnitTest` | 15 项通过 |
| `:feature:ad-admob:testDebugUnitTest` | 3 项通过 |
| `:feature:ad-csj:testDebugUnitTest` | 23 项通过 |
| `:feature:ad-uniad:testDebugUnitTest` | 26 项通过 |
| `:app:dependencies --configuration debugRuntimeClasspath` | 默认无 GMA/UMP；Play 有 GMA/UMP、无 CSJ/uni-ad 运行时依赖 |
| merged manifest / 备份 XML | 核对广告 App ID 元数据与权限，XML 可解析 |
| release 缺失 ID、示例 publisher、Play 混入国内 SDK | 均在配置期按预期失败 |
| `git diff --check` | 通过，仅现有行尾转换提示 |

运行环境为 Windows PowerShell，`JAVA_HOME` 指向 JDK 17，统一串行 Gradle 避免 Windows jar 锁。常用后缀：`--no-parallel --max-workers=1 --no-daemon '-Pkotlin.compiler.execution.strategy=in-process'`；Play 测试使用 `'-PappDistribution=play' '-PenableAdmobDebugAdFeature=true'`。共 211 项模块单测通过，不把直接 compiler 的重复红绿次数计入总数。

位置/敏感跳过/锚点/缓存淘汰/网格行映射/实时展示门采用回归或测试先行；权限系统回调和原生视图绑定采用编译加待完成的设备验收，没有为 Compose 接线堆造 JVM 样式测试。交叉 review 修复了敏感锚点遗漏、后续位置提前请求、首行广告准备、后台 Tab、全页按压、首图删除定位和同帧展示许可问题。

正式 `:app:bundleRelease` 已验证因缺少真实 App ID 被正确阻断，未生成正式 AAB，也未完成 AdMob release/R8 验证。当前无连接的授权 Android 设备，真机弱网、旋转、TalkBack、SDK 填充及真实手势不能标为已验收。国内模块做了测试/编译兼容回归，未用不存在的真实后台 ID 冒充完整国内广告包验收。

### 实施跟踪

- [x] adapter、配置、UMP、API（API 15 项及 adapter 3 项单测通过，真实 SDK 编译通过）
- [x] 宿主会话、布局及详情/备份/设置（本地编译与单测通过，真机验收待办）
- [x] 所有列表和下载记录（交叉审查问题已修复）
- [x] 媒体内容与独立阶段（编译与规则测试通过）
- [x] 本地自动验证、交叉 review 与发布清单
- [x] 系统备份范围确认：仅排除四个 Google 专用文件，默认偏好文件保持现状
- [ ] 正式 ID、账号审核、Play AAB/R8、真机及小范围发布验收

### 变更记录

- 2026-09-26：按用户先测试广告、后发布 Google Play 的安排，默认开启 AdMob debug 并切换 Play 渠道；release 暂不启用，保留正式 ID 完整性校验。同步本机配置示例和构建注释，避免普通调试仍漏装广告模块。默认属性下执行 `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :feature:ad-api:testDebugUnitTest :feature:ad-admob:testDebugUnitTest --no-parallel --max-workers=1 --no-daemon '-Pkotlin.compiler.execution.strategy=in-process'` 成功；宿主 94 项、ad-api 15 项、ad-admob 3 项单测共 112 项通过（ad-admob 任务复用有效缓存），零失败、错误和跳过。产物为 `app/build/outputs/apk/debug/ClipMaster-v0.6.0-admob-debug.apk`。生成 BuildConfig 确认 AdMob/Play 开启、CSJ/uni-ad 关闭，模块 BuildConfig 与合并 Manifest 使用官方测试 ID；`git diff --check` 通过。未安装设备或验收广告填充，未运行正式 release/R8，继续按真实 ID 与真机待办跟踪。

- 2026-09-22：按用户要求单独撤销构建提交 `b0e0e83`，删除 `gradle/gradle-daemon-jvm.properties`，保留广告功能提交 `5391e0d`。原因是没有已确认需求要求项目统一使用 JDK 25，也没有证据表明 AdMob 接入需要该版本；Gradle 恢复按现有本机/IDE/CI 配置选择运行时。未修改组件、类、方法、变量、源码 JVM target、广告配置、日志或备份契约，无新增注释及单测需求；验证：`.\gradlew.bat --version` 确认守护进程使用本机 Microsoft JDK 17.0.15；`:app:compileDebugKotlin :feature:ad-admob:testDebugUnitTest -PappDistribution=play -PenableAdmobDebugAdFeature=true` 在串行、进程内 Kotlin 编译设置下通过；`git diff --check` 和广告代码零差异检查通过。正式 AdMob Release/R8 与真机验收仍按既有待办跟踪。


- 2026-09-22：按用户要求整理 AdMob 提交并合入 main；广告功能与 Gradle 生成的 JDK 25 运行时配置分别提交，便于独立追踪。清理三个 Kotlin 文件的末尾多余空行，不改变方法、变量或业务行为。默认配置 `testDebugUnitTest :app:compileDebugKotlin` 通过，全仓 XML 报告汇总 924 项、零失败/错误/跳过；Play 测试配置 `:app:compileDebugKotlin :feature:ad-admob:testDebugUnitTest -PappDistribution=play -PenableAdmobDebugAdFeature=true` 通过；三个系统备份入口的 Google 专用排除检查和 `git diff --check` 通过。当时新增的 `gradle/gradle-daemon-jvm.properties` 指定 JDK 25；该配置已按同日后续决定单独撤销，源码 JVM target 始终保持既有配置。未新增日志或单测，原因是本次只做已有功能集成与空行清理，原有回归测试随代码提交；生成的 JVM 配置保留 Gradle 原生格式。正式 ID、AdMob release/R8 和真机验收仍为后续待办，总状态保留“实现中”。


- 2026-09-19：用户确认仅排除四个 Google 专用偏好文件，关闭默认偏好文件排除的授权待办；记录默认偏好中的同意键仍可能被系统恢复。现有 XML 已符合决定，本次只同步文档，不新增日志、测试或运行时代码。

- 2026-09-19：接入阶段独立广告、全页交互门控、首行预加载、可见锚点加载与恢复顶部区域；补充发布边界和备份审批阻挡，进行集成验证。
- 2026-09-18：根据确认方案扩展为 16 个 AdMob 位置；统一无限累计列表广告、阶段拆分和顶部备份广告；加入触摸/滚动期间布局保护。状态设为实现中。


## 当前状态

早期基础接入新增 `:feature:ad-api` 作为稳定广告抽象，新增 `:feature:ad-debug` 作为调试占位实现，并在详情页接入一个信息流/原生广告位。国内真实广告 adapter 已包含穿山甲 CSJ/Pangle `:feature:ad-csj` 和 uni-ad `:feature:ad-uniad`。所有可选实现模块统一由 `gradle.properties` 中的显式布尔开关控制，`gradle/feature-flags.gradle.kts` 负责读取并向各模块暴露；广告 ID 仍由本机 `local.properties` 或 CI Gradle 属性提供。开启某个广告 buildType 开关后，缺少对应 ID 会在 Gradle 配置期失败。广告 SDK 不在 `Application.onCreate()` 初始化，不向普通用户暴露广告源选择 UI。CSJ 与 uni-ad 同一 buildType 保持互斥，避免同包重复塞入两套真实国内广告 SDK。

## 目标

- 广告能力独立成可替换模块，宿主只依赖 `:feature:ad-api`，不直接依赖 AdMob、穿山甲、优量汇等 SDK。
- 详情页只展示一个原生广告位，位于正文卡片和链接/磁力操作区之间。
- 无广告源、广告关闭、隐私未同意、无填充、加载失败或保险丝禁用时，广告位直接隐藏且不占空白。
- 同一 APK 内已编译进来的广告源可运行时切换；新增全新 SDK 仍通过 adapter 模块和重新打包发布完成，不动态下载代码。
- 广告请求和日志禁止携带剪贴正文、完整 URL、搜索词、本地文件路径、设备标识、广告素材或 SDK 原始响应。

## 架构与模块

- `:feature:ad-api`：定义 `AdPlacement`、`AdSourceEntry`、`AdSlotRequest`、`AdSlotEvent`、`AdSourceSelector`、`AdRuntimePolicy` 和 `AdSessionFailureFuse`，并通过 Hilt `@Multibinds` 提供空广告源集合。
- `:feature:ad-debug`：通过 Hilt `@IntoSet` 注入 `debug` 广告源，只在 debug 请求下显示固定占位 UI，不接入真实 SDK。
- `:feature:ad-csj`：通过 Hilt `@IntoSet` 注入 `csj` 广告源，`priority = 100`，只支持 `AdPlacement.DetailNative`；SDK 初始化、配置、loader、模板广告 View、事件去重、释放和异常兜底全部封装在 adapter 内。
- `:feature:ad-uniad`：通过 Hilt `@IntoSet` 注入 `uniad` 广告源，`priority = 100`，只支持 `AdPlacement.DetailNative`；uni-ad SDK 初始化、配置、`DCFeedAdLoader`、`DCFeedAd`、广告 View 生命周期、事件去重、释放和异常兜底全部封装在 adapter 内。
- `:app`：注入广告源集合、选择器和会话保险丝，将 `AppSetting.activeAdSourceIdFlow`、`AppSetting.adsGlobalEnabledFlow` 与保险丝禁用集合传给详情页。
- 未来其它真实广告源继续使用独立 adapter 模块，例如 `:feature:ad-admob`、`:feature:ad-gdt`；SDK 初始化、Manifest metadata、权限、R8/consumer rules、广告单元 ID 和隐私同意都留在 adapter 内。
- 渠道裁剪优先：Google Play 包优先只带 AdMob，国内渠道包优先只带国内 SDK 或必要 SDK；运行时多源切换只用于同包内已内置来源。

## 穿山甲 CSJ v1 接入

- SDK 依赖使用官方 Maven `com.pangle.cn:ads-sdk-pro:7.6.1.2`，版本来自 2026-06-01 实施时官方 Maven metadata 的 `release/latest`；后续升级按高风险变更处理。
- `settings.gradle.kts` 增加穿山甲官方 Maven 仓库，`settings.gradle.kts` include `:feature:ad-csj`；`app/build.gradle.kts` 仅在当前 buildType 显式开关开启且参数校验通过时依赖该模块，debug 使用 `debugImplementation`，release 使用 `releaseImplementation`，防止默认/海外包误带国内 SDK。
- app manifest 明确 `tools:replace="android:label"`，避免穿山甲 AAR 自带 `application@label` 覆盖宿主应用名；该合并规则只允许宿主品牌名优先，不作为接受 SDK 其它 manifest 声明的兜底。
- `:feature:ad-csj` consumer rules 透出 R8 自动生成的穿山甲可选依赖 `dontwarn` 规则；当前 SDK POM 没有声明这些包，后续每次升级必须重新检查 `missing_rules.txt`、R8 warning 和官方 consumer rules。
- merged manifest 显示穿山甲 AAR 会引入 `ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`READ/WRITE_EXTERNAL_STORAGE`、`CHANGE_NETWORK_STATE`、`CHANGE_WIFI_STATE`、`WAKE_LOCK`、`VIBRATE`、华硕 MSA 权限、下载服务、`:downloader`、`:bytelive`、`:push` 等声明；v1 虽然 adapter 禁止下载类广告和关闭可选设备能力，但上线前必须按渠道最小化裁剪或完成 SDK 清单/权限说明。
- `gradle/feature-flags.gradle.kts` 与 TorrentSearch 共用同一种读取方式，从 Gradle 属性读取 `enableCsjDebugAdFeature`、`enableCsjReleaseAdFeature`，默认 `false`；`gradle/csj-ad-config.gradle.kts` 只负责读取和校验广告参数，读取优先级为命令行/CI Gradle 属性高于项目 `local.properties`。debug/internal 只读取 `csjDebugAppId`、`csjDebugDetailNativeAdSlotId`、`csjDebugUseTestAdSlot`，release 只读取 `csjReleaseAppId`、`csjReleaseDetailNativeAdSlotId`、`csjReleaseUseTestAdSlot`。旧的 `csjAppId`、`csjDetailNativeAdSlotId`、`csjUseTestAdSlot` 不再作为兜底，避免 debug/release 误共用同一套后台配置。
- 本机或 CI 先为目标 buildType 准备两项 ID，再显式开启对应广告 feature 开关；只配置 ID 不再自动打包 SDK，开关开启但 ID 缺失或格式非法时 Gradle 配置期直接失败。临时构建无广告包时只需把对应开关设为 `false`，无需删除或注释本机密钥。
- debug/internal 包不再使用系统自动生成的 `$HOME/.android/debug.keystore`，而是使用项目固定的 `debug-internal.keystore`，alias 为 `clipmaster_debug`，SHA1 指纹为 `11:17:8B:F7:6D:FA:7F:2F:19:2C:10:0A:16:61:C5:42:BA:05:8E:C2`；穿山甲 debug/internal 应用后台绑定该指纹。release 包继续使用 `keystore.properties` 指向的正式 release keystore，穿山甲 release 应用后台绑定正式签名指纹。
- `CsjAdConfig`、`CsjAdAvailabilityPolicy`、`CsjAdInitializer`、`CsjSdkClient`、`CsjNativeAdSlot` 分别负责配置、可用性判断、懒初始化、SDK facade 和 Compose/AndroidView 承载，第三方 SDK 类型不泄露到 app。
- SDK 只在主进程、广告总开关开启、用户广告隐私同意为 `Granted`、隐私政策版本满足配置、详情页非敏感内容且首个广告位需要展示时懒初始化；`AdConsentState.NotRequired` 仅允许调试源，穿山甲不接受该状态。
- 详情页本地敏感内容保护只传递布尔值，验证码、密码、Token、密钥、银行卡等明显敏感内容命中时返回 `csj_sensitive_detail_hidden`，不记录原文或命中片段。
- 详情页不预加载、不跨 `clipId` 缓存广告；宿主侧 4 秒超时后隐藏并释放，返回 `csj_request_timeout`。
- SDK 回调统一切回主线程后再更新 Compose state；释放后迟到回调返回 `csj_late_callback_ignored`，展示、点击和释放事件按 request nonce 去重。
- v1 不支持下载类广告；若 SDK 返回下载类交互，adapter 立即释放并返回 `csj_download_ad_unsupported`，后续如启用下载广告必须补下载要素展示、权限和隐私清单。
- 视觉上由 adapter 在 SDK View 上方展示“广告”标识，并给 TalkBack 提供广告语义；外层不添加透明点击层，点击区域按 SDK 模板要求注册。
- 穿山甲 `TTCustomController` 默认关闭定位、电话状态、应用列表、Wi-Fi 状态、外部存储、录音、消息、OAID、IMEI、MAC 和 Android ID 读取；确需启用 OAID 或其它设备标识时必须单独补审批、隐私政策、SDK 清单和验证项。
- 当前不做远程配置后台、统计后台、普通用户广告源 UI 或内部诊断面板完整实现，只保留全局关闭、`off`、`auto`、会话保险丝、低敏 BuildConfig 字段和 reasonCode 预留。

## uni-ad v1 接入

- `:feature:ad-uniad` 使用 DCloud uni-ad 原生 SDK 包 `UNI_AD_android_5.5.2.0606`，AAR 固定在 `third_party/uniad/UNI_AD_android_5.5.2.0606/`，并通过 `checksums.sha256` 与 `gradle/uniad-ad-config.gradle.kts` 校验 SHA-256；AAR 只允许保存在当前私有仓库或私有 CI 制品环境中。
- v1 只打包支持信息流的章鱼 + 泛连：`uniad-native-release.aar`、`android-gif-drawable-1.2.29.aar`、`uniad-zy-release.aar`、`octopus_ad_sdk_2.5.10.5.aar`、`Funlink_2.8.8_76006310_release.aar`、`Funlink_adapter_uniad_2.8.4_74659082_release.aar`；不引入 Sigmob、华夏乐游、优量汇、穿山甲、百度、快手、GroMore。
- `gradle/feature-flags.gradle.kts` 从 Gradle 属性读取 `enableUniadDebugAdFeature`、`enableUniadReleaseAdFeature`，默认 `false`；`gradle/uniad-ad-config.gradle.kts` 统一读取 `uniadDebugAppId`、`uniadDebugUnionId`、`uniadDebugDetailNativeAdpid`、`uniadReleaseAppId`、`uniadReleaseUnionId`、`uniadReleaseDetailNativeAdpid` 和 `uniadRequiredPrivacyPolicyVersion`，读取优先级为 CI/Gradle 属性高于本机 `local.properties`。
- 当前 buildType 的显式开关开启且 AppId、联盟 ID、详情页信息流 adpid 三项校验通过时，宿主才使用 `debugImplementation` 或 `releaseImplementation` 依赖 `:feature:ad-uniad`；只配置参数或只开启开关都不会产生半配置广告包。
- uni-ad 与 CSJ 同一 buildType 的显式开关同时开启时，Gradle 配置期直接失败并提示只保留一套真实国内广告源；默认/海外包开关关闭时不会触发 AAR 校验。
- debug/internal 构建固定视为测试 adpid，release 构建固定视为正式 adpid；显式开关开启但参数不完整时构建期失败，开关关闭或运行时 SDK 能力不可用时隐藏广告并返回 `uniad_config_missing`。
- `UniAdConfig`、`UniAdAvailabilityPolicy`、`UniAdInitializer`、`UniAdSdkClient`、`UniAdFeedAdSlot`、`UniAdReleaseGuard` 和 `UniAdEventDeduplicator` 分别负责配置、可用性判断、懒初始化、SDK facade、Compose/AndroidView 承载、释放幂等和事件去重，第三方 SDK 类型只出现在 `UniAdSdkDirectClient`。
- SDK 只在主进程、广告总开关开启、用户广告隐私同意为 `Granted`、隐私政策版本满足配置、详情页非敏感内容且首个广告位需要展示时懒初始化；`AdConsentState.NotRequired` 仅允许调试源，uni-ad 不接受该状态。
- 初始化时立即调用 `DCloudAdManager.setPrivacyConfig(...)`，默认关闭 PhoneState、Storage、Location、WifiState、安装列表、运行中应用、MAC、Android ID、OAID、IP、传感器、运营商、录音等可选采集；随后调用 `DCloudAdManager.setPersonalAd(context, false)`，v1 默认关闭个性化广告。
- 详情页广告请求使用 `DCFeedAdLoader`、`DCloudAdSlot.Builder().adpid(...).count(1).build()`、`DCFeedAd.render()` 和 `getFeedAdView(Activity)`；找不到 Activity 时返回 `uniad_activity_missing` 并隐藏广告。
- SDK 回调统一切回主线程后再更新 Compose state；释放后迟到回调返回 `uniad_late_callback_ignored`，展示、点击和释放事件按 request nonce 去重。
- 广告 View 加入容器前先从旧 parent 移除，避免 `View already has a parent` 崩溃；加载成功且渲染成功后才插入容器，容器最大高度为 320dp。
- v1 不预加载、不跨 `clipId` 缓存、不支持下载类广告、插屏、开屏、激励视频、Draw 信息流、弹窗、列表页广告或搜索页广告；弱网、无网、无填充、加载失败或 4 秒超时都隐藏且不占位。
- `:feature:ad-uniad` 自身 manifest 只声明 `INTERNET` 和 `ACCESS_NETWORK_STATE`；不为广告全局放宽 cleartext 策略，不默认加入定位、存储、读手机状态、应用列表、安装应用、传感器或录音权限。
- 当前 uni-ad release merged manifest 显示 AAR 仍会合并 `READ_PHONE_STATE`、`WAKE_LOCK`、`ACCESS_WIFI_STATE`、`REQUEST_INSTALL_PACKAGES`、`VIBRATE`、`io.dcloud.openapi.activity.WebViewActivity`、DCloud 下载服务/FileProvider、章鱼广告 Activity/DownloadService/Provider 等声明；v1 adapter 已默认关闭可选采集且不支持下载类广告，但正式上线前必须按渠道最小化裁剪或完成权限说明、SDK 清单和审核材料。
- uni-ad v1 R8 consumer rules 已为章鱼/泛连可选路径补充 `dontwarn`，包括未打包渠道、微信 openSDK、ReLinker、旧 commons-codec 等；后续 SDK 升级必须重新核对 `missing_rules.txt` 和 R8 输出，避免把真实必需依赖误当可选依赖。
- uni-ad AAR 更新、渠道变化或开启 OAID/下载广告时必须补隐私政策、第三方 SDK 清单、权限用途说明、merged manifest、R8、包体/ABI、重复类和渠道后台截图验证。

### uni-ad 渠道能力表

| 渠道 | v1 是否打包 | 详情页信息流 | 取舍 |
| --- | --- | --- | --- |
| 章鱼 | 是 | 支持 | v1 主验证渠道之一，AAR 已固定校验。 |
| 泛连 | 是 | 支持 | v1 主验证渠道之一，AAR 已固定校验。 |
| Sigmob | 否 | 不支持 | 官方渠道能力表不支持信息流，不用于详情页广告位。 |
| 华夏乐游 | 否 | 待后续评估 | 需要额外旧版 OkHttp/Gson/Glide/commons-codec 风险依赖，v1 排除。 |

## 详情页广告控制

- 详情页不做用户可感知频控：每次用户主动进入详情页时允许展示一个原生广告位。
- 同一个 `clipId` 在一次详情页生命周期内最多创建一次 `requestNonce`；Compose 重组、前后台切换或同页状态刷新不重复创建请求。
- `NoFill`、`LoadFailed`、广告源不可用或隐私未同意时不在当前详情页内重试。
- 详情页离开可见状态时，adapter 必须释放广告 View、loader、监听器和 SDK 回调；返回详情页可重新请求。
- 用户撤回广告隐私同意或关闭广告能力后，当前广告 View 和 loader 立即释放；已初始化 SDK 若无法反初始化，v1 只承诺停止新请求并释放当前资源。
- 详情页禁止插屏、弹窗、自动跳转、覆盖正文和抢焦点广告。
- 列表页、搜索页、下载页等连续消费场景后续必须单独规划密度、间隔和分页级频控，不复用详情页的一页一广告规则。
- 当前会话内某个广告源初始化失败或渲染失败后，`AdSessionFailureFuse` 会临时禁用该 source，并回退到其它 source 或隐藏广告位。

## 合规与 Adapter 验收

- 真实 SDK adapter 必须等待隐私同意完成后再懒初始化；用户不同意非必要个人信息处理时，核心功能仍可用，广告隐藏或降级。
- 广告 SDK 不得放在 `Application.onCreate()` 初始化；只在“隐私同意完成 + 广告总开关开启 + 首个广告位需要展示”时启动，并限制初始化耗时。
- 每接一个真实广告 SDK，必须补齐 adapter 验收清单：SDK 名称/版本、广告平台、收集信息、权限、是否读取广告 ID、初始化时机、隐私政策链接、SDK 清单条目、测试广告位 ID、正式广告位 ID、R8/consumer rules、崩溃回退策略和 release 验证命令。
- 广告单元 ID 按环境隔离：debug/internal 永远使用测试广告位 ID；release 才允许正式广告位 ID。
- Google/海外 adapter 必须检查 merged manifest 是否引入 `com.google.android.gms.permission.AD_ID`，并同步 Play Data safety、广告 ID 声明和测试广告位配置。
- AdMob adapter 必须单独标注 EEA、UK、瑞士场景的 EU consent policy、UMP/CMP/TCF 要求；不能把普通隐私弹窗当作 AdMob 合规完成条件。
- 国内广告 adapter 必须补齐隐私政策、SDK 清单、权限说明、个人信息处理目的、初始化前同意策略和不同意后的降级行为。
- SDK 版本升级按高风险变更处理：每次升级都重新跑 adapter 清单、merged manifest 检查、R8 验证、Data safety/SDK 清单核对和初始化行为验证。

## 设置与备份边界

- `AppSetting.activeAdSourceId` 保存 `auto`、`off` 或稳定 sourceId，只影响本机广告选择，不进入 WebDAV/本地备份。
- `AppSetting.adsGlobalEnabled` 是广告总开关，只影响本机广告展示，不进入 WebDAV/本地备份。
- `AppSetting.adConsentState` 和 `AppSetting.adPrivacyPolicyVersion` 是本机隐私/运营态入口，用于阻止真实广告 SDK 初始化，不进入 WebDAV/本地备份，也不触发 backup dirty。
- `AdSessionFailureFuse` 只保存当前进程内的临时禁用集合，不持久化、不进入备份、不触发 dirty。
- `local.properties` 中的 `uniad...`、`csj...` 和 release 签名配置属于本机/CI 构建态，不进入 WebDAV/本地备份，也不能写入运行时日志。
- 卸载重装后广告选择恢复默认 `auto`、广告总开关恢复默认开启，实际是否显示仍由广告源集合、隐私同意和渠道包决定。

## 日志与诊断计划

- 详情页广告隐藏记录 `placement` 和 `reasonCode`，例如 `ads_global_disabled`、`ads_source_off`、`consent_unavailable`、`no_available_source`。
- 广告事件只记录 `providerId`、`placementId`、`eventType`、`reasonCode`、`durationMs`、`isDebug` 和低敏 `sdkVersion`。
- 初始化失败和渲染失败使用 WARN 级别，并触发会话级保险丝；普通请求、加载、展示、点击、释放使用 DEBUG 级别。
- 穿山甲 adapter reasonCode 使用稳定短码：`csj_config_missing`、`csj_consent_denied`、`csj_consent_revoked`、`csj_privacy_version_outdated`、`csj_sensitive_detail_hidden`、`csj_not_main_process`、`csj_init_failed`、`csj_no_network`、`csj_request_timeout`、`csj_no_fill`、`csj_load_failed`、`csj_download_ad_unsupported`、`csj_render_failed`、`csj_released`、`csj_late_callback_ignored`、`csj_event_deduplicated`、`csj_adapter_exception`。
- uni-ad adapter reasonCode 使用稳定短码：`uniad_config_missing`、`uniad_sdk_missing`、`uniad_sdk_checksum_mismatch`、`uniad_channel_unsupported`、`uniad_activity_missing`、`uniad_consent_denied`、`uniad_consent_revoked`、`uniad_privacy_version_outdated`、`uniad_sensitive_detail_hidden`、`uniad_not_main_process`、`uniad_remote_kill_switch`、`uniad_init_failed`、`uniad_no_network`、`uniad_request_timeout`、`uniad_no_fill`、`uniad_load_failed`、`uniad_render_failed`、`uniad_download_ad_unsupported`、`uniad_closed_by_user`、`uniad_released`、`uniad_late_callback_ignored`、`uniad_event_deduplicated`、`uniad_adapter_exception`。
- 禁止输出剪贴正文、完整 URL、query、广告素材内容、SDK 原始响应、设备标识、Cookie、Token、登录态、本地路径和广告位正式 ID。
- 第一阶段收益评估只使用低敏聚合指标：详情页打开次数、广告请求次数、填充率、展示率、点击率、加载耗时、失败 reasonCode、广告相关崩溃率和详情页停留时长变化。

## 测试验证

- `AdSourceSelectorTest` 覆盖空集合隐藏、`off` 隐藏、全局关闭隐藏、隐私拒绝隐藏、保险丝跳过、`auto` 优先级、显式 source 命中、显式 source 缺失回退和广告位不支持过滤。
- 详情页人工验证：无广告源不改变布局；debug 源显示在正文和操作区之间；同一 `clipId` 生命周期内只创建一次请求；切换剪贴记录生成新请求；长正文、多链接和底部弹层不重叠。
- 生命周期验证：进入详情页请求，离开详情页释放；返回详情页可重新请求；无填充/失败不在当前详情页内重试。
- 日志检查：广告事件不得出现剪贴正文、完整 URL、query、SDK 响应体、广告素材内容或设备标识。
- 穿山甲单元测试覆盖 `CsjAdConfig`、`CsjAdAvailabilityPolicy`、`CsjAdInitializer`、`CsjAdEventDeduplicator`、`CsjAdReleaseGuard` 和详情页敏感内容保护。
- uni-ad 单元测试覆盖 `UniAdConfig`、`UniAdAvailabilityPolicy`、`UniAdInitializer`、`UniAdEventDeduplicator`、`UniAdReleaseGuard`、`UniAdFeedRequestSpec` 和 AAR 制品校验。
- 验证命令：`./gradlew :feature:ad-api:testDebugUnitTest`、`./gradlew :feature:ad-api:compileDebugKotlin`、`./gradlew :feature:ad-debug:compileDebugKotlin`、`./gradlew :feature:ad-csj:testDebugUnitTest`、`./gradlew :feature:ad-csj:compileDebugKotlin`、`./gradlew :app:testDebugUnitTest`、`./gradlew :app:compileDebugKotlin`、`./gradlew :app:compileDebugKotlin -PenableCsjDebugAdFeature=true -PcsjDebugAppId=<test> -PcsjDebugDetailNativeAdSlotId=<test>`、`./gradlew :app:minifyReleaseWithR8 -PenableCsjReleaseAdFeature=true -PcsjReleaseAppId=<official> -PcsjReleaseDetailNativeAdSlotId=<official>`、`git diff --check`。另以只开启 feature、不传 ID 的 `help` 命令验证配置期失败。
- uni-ad 追加验证命令：`./gradlew :feature:ad-uniad:testDebugUnitTest`、`./gradlew :feature:ad-uniad:compileDebugKotlin`、`./gradlew :app:compileDebugKotlin -PenableUniadDebugAdFeature=true -PuniadDebugAppId=<test> -PuniadDebugUnionId=<test> -PuniadDebugDetailNativeAdpid=<test>`、`./gradlew :app:minifyReleaseWithR8 -PenableUniadReleaseAdFeature=true -PuniadReleaseAppId=<official> -PuniadReleaseUnionId=<official> -PuniadReleaseDetailNativeAdpid=<official>`，并验证同一 buildType 的 CSJ 与 uni-ad 开关同时开启时构建失败。
- 上架前仍需人工检查 merged manifest、权限/provider/activity、是否引入 AD_ID、SDK 清单、广告单元 ID、隐私同意路径、关闭降级路径、包体/方法数/冷启动/详情页耗时、Android 7/10/13/14、深色模式、大字体、弱网、无网和国产 ROM 后台切换。

## 已知取舍

- 默认构建的所有可选实现开关均为 `false`，不接真实广告 SDK；debug/internal 和 release 分别通过显式 feature 开关决定是否编译对应 adapter，广告参数只负责配置与完整性校验。该方式多一个明确开关，但与 `enableMagnetFeature` 的位置和语义一致，也避免本机残留密钥意外改变 APK 组成。
- 第一版不做远程配置后台；只保留本地 `auto`、`off`、总开关、会话保险丝和远程 kill switch 预留字段。
- 第一版不在“我的”页暴露广告源选择 UI；后续若要给内部构建开放切换入口，需要先补设置页方案和隐私文案。
- `AdConsentState.NotRequired` 只用于调试广告源和无真实 SDK 的第一版；真实广告源必须接入明确同意状态。
- 穿山甲 v1 不接开屏、插屏、激励视频、弹窗、下载类广告、列表页广告、搜索页广告、远程配置后台、统计后台或完整内部诊断面板。
- uni-ad v1 不接运营后台、收益统计后台、普通用户广告开关 UI、列表页/搜索页广告、下载类广告、插屏、开屏、激励视频、Draw 信息流或更多渠道；先用章鱼 + 泛连验证详情页一个广告位。

## 开放问题

- 优量汇、快手、AdMob 等第二广告源的具体接入顺序、渠道裁剪规则和广告单元 ID 管理策略待后续单独确认。
- 穿山甲正式上线前需要补齐第三方 SDK 目录、隐私政策更新、权限用途说明、个人信息收集清单、广告 ID/设备信息声明、审核截图/录屏留档和渠道构建报告。
- uni-ad 正式上线前需要完成 DCloud uni-ad 后台实名认证、财务审核、应用创建、包名/签名绑定、DCloud AppId、联盟 ID、详情页信息流 adpid、隐私政策 URL、第三方 SDK 清单、章鱼/泛连渠道审核材料、审核截图/录屏和渠道后台广告位截图留档。
- 当前 `ads-sdk-pro` AAR 含 `arm64-v8a` 和 `armeabi-v7a` 多个 `.so`，并在 R8 中产生大量 SDK jar 的 stack map table warning；合入前可接受为第三方 SDK 风险记录，正式上线前仍需在目标渠道 release 包上二次验证启动、加载、崩溃率和包体。
- 内部诊断面板、远程 kill switch 下发、聚合收益统计、本地事件缓存和敏感内容识别增强放到 v1.1 或后续单独方案。
- 列表页、搜索页、下载页广告位需要独立方案，重点补密度/间隔/分页级频控和连续消费场景体验。
- 是否需要灰度平台、SDK 版本快速回滚面板和广告包构建报告自动化，后续结合发布渠道与运营能力再评估。

## 变更记录

- 2026-06-01：新增广告模块化方案，规划 `:feature:ad-api`、`:feature:ad-debug` 和详情页广告位；原因是详情页需要接入可热插拔广告能力，同时先用调试实现验证模块边界、隐私边界和页面布局。
- 2026-06-01：补充国内广告第一家穿山甲 CSJ adapter 的实现计划和真实接入边界，新增 `:feature:ad-csj`、官方 SDK 版本、Gradle 属性、隐私同意、敏感详情隐藏、生命周期释放、下载类广告拒绝、R8/manifest/渠道验证和 v1/v1.1 范围；原因是详情页需要优先接入国内信息流广告并保持默认/海外包可裁剪。
- 2026-06-01：补充穿山甲 AAR manifest 合并、权限/多进程/下载服务声明、consumer rules `dontwarn` 和 R8 stack map table warning 风险；原因是真实 SDK 构建验证暴露了需要上线前复核的第三方 SDK 边界。
- 2026-06-01：调整穿山甲构建开关，新增 `gradle/csj-ad-config.gradle.kts` 统一读取 CI Gradle 属性和本机 `local.properties`，当旧通用 AppId 与广告位 ID 同时存在时默认启用广告模块；原因是日常打包不应要求额外命令行开关。该旧通用 key 规则已在 2026-06-02 被 buildType 专属 key 取代。
- 2026-06-02：将穿山甲广告 ID 调整为 debug/release 两套 buildType 专属配置，并让 debug 包使用项目固定的 `debug-internal.keystore`；原因是系统自动 debug keystore 换机器会变化，穿山甲后台需要稳定签名指纹，同时正式包必须使用 release 签名和正式代码位。
- 2026-06-02：删除用户侧广告总启用配置语义，广告模块只由对应 buildType 的 AppId 与广告位 ID 是否齐全决定；原因是临时关闭广告时注释掉 AppId 更直观，也能避免额外总开关和 ID 配置互相矛盾。
- 2026-06-03：新增 uni-ad 详情页信息流 adapter `:feature:ad-uniad`，固定 `UNI_AD_android_5.5.2.0606` AAR、章鱼 + 泛连渠道、buildType 自动配置、CSJ/uni-ad 互斥、AAR SHA-256 校验、隐私采集关闭、个性化广告关闭、`DCFeedAdLoader` 生命周期、`count(1)` 和单元测试；原因是穿山甲平台不适合当前个人开发者接入，详情页改为优先验证 uni-ad 原生信息流广告。
- 2026-09-13：新增 `gradle/feature-flags.gradle.kts`，将 TorrentSearch、CSJ debug/release、uni-ad debug/release 五个可选实现开关统一由 `gradle.properties`/`-P` 显式控制；广告参数继续保存在 `local.properties` 或 CI secret，开关开启但参数不完整时配置期失败。原因是模块是否打包不应再由密钥是否存在隐式决定，并需要与 `enableMagnetFeature` 保持一致的配置位置和启用语义。本次只改变构建期依赖选择与错误提示，不新增运行时日志，也不涉及用户数据、备份或隐私日志字段。
