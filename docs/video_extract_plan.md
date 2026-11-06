状态：实现中

## 英文大字体状态与弹层验收（2026-11-06，已完成）

- 复用 VideoProbeControls 的全部提示与错误分支，薄提取成功入口和下载各阶段展示组件；权限、创建任务和播放器仍留在原适配层。完整页面由本轮导航 smoke 覆盖。
- `EnglishMediaDetailDeviceTest` 使用真实英文 View Context、320dp、1.0/1.3/2.0 倍字号，验证本域关键长状态和全部 modal；测试仅使用合成数据、图片槽和空回调，不触发真实剪贴、网络、下载、权限或播放器。
- 单元测试检查：仅调整 Compose 布局与等价展示边界，没有新增业务规则；新增仪器回归，沿用现有纯规则单测，不增加镜像 JVM 测试。统一编译、宿主单测及 `build/english-all-pages-host-final.log` 中媒体/详情 57 项回归通过；本域人工复核了无系统遮挡的 2.0 倍长进度、超时/错误、下载失败、合并及成功入口截图，未观察到文字裁边、操作叠层或底部遮挡。整体范围及其他领域结果以 [多语言主方案](multilingual_support_plan.md) 为准。
- 日志与诊断计划：不新增运行时日志，以真实 TextLayoutResult、滚动可达性和合成截图诊断；既有副作用日志不变。无持久化、备份、外部协议或反射变化，不新增 keep/R8 规则。
- 变更记录：2026-11-06，补齐视频状态与弹层英文大字体回归并修正静态检查确认的布局风险；原因是原英文测试仅覆盖部分散组件。
- 变更记录：2026-11-06，根据本轮 lint 反馈将 `VideoDownloadFailureContent` 的 `modifier` 移到首个可选参数，必填重试回调前置；全部调用继续按具名参数连接，错误映射和禁用契约不变。此为无行为变化的接口顺序调整，复用既有回归，由统一 Kotlin 编译与 lint 验证，不新增镜像测试。
- 变更记录：2026-11-06，回填真实英文布局与人工截图验收；未触发真实权限、网站、下载或播放器，相关副作用仍遵循原验证边界。

# 视频提取功能方案

## 主分支集成记录（2026-11-05）

用户要求提交全部改动并推送主分支。本次保留远端 `1eadcf3` 的 0.9.20 发布历史和资源前缀修复，将视频增量正常合并到 `main`；新增失败提示统一为 `host_video_probe_failed`，复用既有消息 mapper 并同步回归测试，文案和失败行为不变。图片选择与广告间距草案独立提交，仍未实施。

- 复用与职责：仅协调同一资源的声明、映射和测试引用，不新增类或方法，不修改会话、底栏及备份契约；修改位置保留简体中文说明。
- 单元测试检查：资源重命名由既有 `VideoPageUnavailableTest` 覆盖，不另写重复测试或采用 TDD；集成后运行 app/base:general 单测、app 与 androidTest Kotlin 编译及资源前缀检查。
- 日志与诊断计划：集成与资源命名不新增运行时副作用，不增加日志；沿用前述低敏错误分类和生命周期诊断。
- 验证：合并前后均执行 `.\gradlew.bat :app:testDebugUnitTest :base:general:testDebugUnitTest :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --init-script build\review-build-isolation.gradle --console=plain`；最终 `BUILD SUCCESSFUL`，两模块 546 条 JVM 用例零失败、错误和跳过。资源前缀、冲突标记及 `git diff --check` 检查通过，构建输出保存在忽略的 `build/main-merge-validation.log`。本轮不重跑设备用例或完整 release/R8，新增布尔 DOM 协议不依赖反射/序列化名称，设备与布局矩阵仍以前文验收边界为准。

## 视频下载相关页面广告统一靠底（2026-11-05，已完成）

已按用户追加要求完成视频下载页及其他视频提取相关页面的广告位置调整。沿用上一轮已确认的固定底栏布局，不修改广告资格或下载流程。检查路由与全部视频广告位后，当前视频流程只有 `VideoExtractPage` 和 `VideoDownloadPage` 两个页面；下载中、M3U8 合并中、成功结果是后者的状态分支，不存在额外独立的结果页或历史页需要改造。

### 接入范围与复用

| 页面/阶段 | 最终广告位置与规则 |
| --- | --- |
| 视频提取 | 沿用上一轮固定底栏；交互查看网页、Idle、失败等不满足原资格时无广告 |
| 视频下载中、合并中 | 共用进度广告位，固定底部；摘要、进度和提示在上方独立滚动 |
| 视频下载成功 | 原结果广告位固定底部，成功播放入口仍在内容区域 |
| 视频准备、失败、任务不存在 | 沿用原无广告规则，内容占满可用区域 |

- 当前 video package 复用 `videoDownloadAdPlacement`、`rememberPageAdSession(taskId to placement)`、`contentScroll` 及现有状态展示方法，不改 ViewModel、Worker、任务观察、重试或播放动作。进度百分比不参与广告会话身份，下载切合并不重复请求。
- app 共享层继续复用 `MediaAdFooter`、`MediaAdFooterPolicy`、`SecondaryPageScaffold` 和 `PageBackground`；共享广告完整契约见 [广告主方案](ad_module_plan.md#视频提取页复用固定媒体广告底栏2026-11-05已完成)。不新增视频专用底栏，不把下载状态塞入共享组件。
- 广告 API 模块只提供位置与请求契约；全仓检索三个视频广告位未发现其他页面直接呈现。详情页只是提取入口，其详情广告属于独立页面布局，保持现有行为。
- `VideoDownloadPage` 的 `PageBackground` 内以有界 `BoxWithConstraints` 测得可用高度；外层 `Column` 中，摘要与状态放入 `weight(1f)` 的滚动区，唯一底栏作为后续兄弟节点。短内容自然留白，长内容可滚动到底，广告不覆盖内容。
- 接入共享底栏的 96dp 最小内容视口、16dp 横向留白以及已加载素材放不下时释放的规则；无广告时零占位。页面补尚未消费的底部 `mandatorySystemGestures`，普通导航栏仍由骨架消费，不叠加固定尺寸或第二份导航栏留白。
- 页面方法和新增/修改变量、回调补齐简体中文注释。仅调整当前入口的结构编排，不新增业务逻辑，不借机拆分既有状态 UI 或修改其外观。

### 实施步骤与验证

- 2026-11-05 真机补充验收（已完成）：用户已要求安装并验证下载、合并和成功三阶段。新增仅显式传入 `videoAdDeviceQa=true` 才运行的 `VideoDownloadAdDeviceTest`，复用正式页面、ViewModel、广告宿主及当前隐私/广告设置，用内存 DAO 提供合成阶段；不写下载表、不请求视频、不播放媒体。仅入队一个负数测试任务对应的延迟 Worker 占位，保证生产调度观察契约，结束后只取消该占位；嵌套 finally 保证页面清理异常时仍尝试取消，若测试进程被强杀而残留，负数输入不对应任何正式任务，之后运行只会查无任务并失败。使用真实 SDK 广告测量与合成页面截图，检查底部安全区、阶段切换、内容手势和关闭回收；截图只含此测试页。没有生产逻辑改动，不新增 JVM 规则测试或采用 TDD；该依赖设备与实时广告填充的验收不纳入默认自动回归。

- [x] 调整 `app/src/main/java/com/cla/clip/master/ui/page/video/VIdeoDownloadPage.kt`，将滚动内容与 `MediaAdFooter` 分开，接入安全区与剩余高度。
- [x] 运行 `.\gradlew.bat :app:testDebugUnitTest --tests '*MediaTaskAdPolicyTest' --tests '*MediaAdFooterPolicyTest' --tests '*VideoDownloadStartPolicyTest' --tests '*VideoDownloadFailureMessageTest' :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:assembleDebug --init-script build\review-build-isolation.gradle --console=plain`，核对报告与编译结果。
- [x] 完成本轮独立差异审查，确认三个下载阶段共用固定布局、阶段身份与失败重试不变；`git diff --check` 通过并同步文档状态。代码基线及验证证据保存在忽略的 `build/video-download-ad-footer/`。

单测门禁：生产修改为纯 Compose 布局/Insets 接线，不新增纯规则或测试依赖，不采用 TDD；既有资格/空间/下载策略测试用于防止流程回归。用户追加要求后新增上述可选设备仪器用例，真实执行正式下载页面与 SDK 广告，专用合成内容截图不包含用户下载数据。没有通过数据库创建实际下载任务，也不能将合成阶段布局验收等同于 Worker 下载/合并链路测试。小屏/大字体、其他导航模式和设备矩阵仍为验证边界。

### 实施结果与验证边界

- 本轮生产改动只涉及 `VIdeoDownloadPage.kt`；复用已有共享底栏，无新增组件/类/方法。新增高度变量、底栏回调与页面布局说明均有简体中文注释，原业务状态分支与操作方法保持。提取页已在上一轮接入，不重复修改；当前 video package 无直接 `PageAdSlot` 接入，两个视频页面都通过固定底栏呈现。
- 上述 Gradle 命令 `BUILD SUCCESSFUL`，24 秒完成。`MediaTaskAdPolicyTest` 5 项、`MediaAdFooterPolicyTest` 3 项、`VideoDownloadStartPolicyTest` 2 项、`VideoDownloadFailureMessageTest` 1 项，共 11 项，失败/错误/跳过均为 0；app 与 androidTest Kotlin 编译、Debug 打包通过。输出为 `build/video-download-ad-footer/verification.log`，APK 为 `app/build/review-20261011/outputs/apk/debug/ClipMaster-v0.9.19-admob-debug.apk`。
- 独立只读审查未发现可确认的 Critical / Important / Minor 问题。检查覆盖高度约束、滚动兄弟布局、Insets 消费、底栏移除释放、下载/合并共享位置及成功切换身份、空态；既有下载观察、重试、播放和共享底栏内部策略不作额外重构。
- 补充真机验收：已用 `adb install -r` 安装当前 Debug APK 与测试 APK，保留应用数据。三星 SM-G9730 / Android 12 / 1440×3040 / 当前字体与手势导航模式下，两次运行专项用例均 `OK (1 test)`；首次 18.939 秒，清理与状态同步加强后的最终版本 12.564 秒。测试源码只新增 `VideoDownloadAdDeviceTest` 和专用测量/截图方法，所有新增方法、变量均补中文注释；独立审查提出的异常清理问题已修正。
- 三阶段真实 `Test Ad` 完整边界均为 `(96, 2428)–(1344, 2960)`，没有裁切；安全底边为 2960px，下方 80px 为系统手势区。内容区域到 2428px 截止，广告与内容不重叠，短内容后的余白由内容区吸收。下载切合并保持同一个原生广告 View，成功换成独立结果广告。点击宿主关闭控件后，原生 View 移除且内容区域扩展到 2960px，无残留广告空槽；未点击广告 CTA。
- 长标题与描述更新后向上滑动，广告位置保持；该素材高度下实际最大滚动距离为 0，因此本轮只验证内容变化及手势后定位，**不声称已覆盖真正滚动中的固定效果**。横屏、大字体、三键导航、小屏、多窗口、无填充/超高素材及实际下载/合并执行链路仍未覆盖。未重复全量 app 单测或 release/R8，原因是本轮只新增可选设备测试，无生产外部协议、反射或持久化变动。
- 本轮构建命令：`.\gradlew.bat :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:assembleDebugAndroidTest --init-script build\review-build-isolation.gradle --console=plain`，最终 `BUILD SUCCESSFUL`（7 秒）。设备命令：`adb -s RF8NA2N81BV shell am instrument -w -r -e videoAdDeviceQa true -e class com.cla.clip.master.ui.page.video.VideoDownloadAdDeviceTest com.cla.clip.master.test/androidx.test.runner.AndroidJUnitRunner`。构建/仪器日志与最终截图、坐标记录保存在忽略的 `build/video-download-ad-footer/device-build.log`、`device-test-final.log` 和 `device-final/`。

### 日志与诊断计划、数据边界

纯布局不新增日志，沿用共享宿主的加载、关闭和释放诊断及现有下载任务日志，不打印重组、滚动帧、标题、完整媒体地址、授权 URI、Cookie 或任务实体。没有新状态协议、反射、序列化、持久化或备份字段，不新增 keep/备份适配，不运行完整 release/R8。当前工作区其他视频错误页、图片文档等改动保留，不自动提交或推送。

## 视频提取页广告固定底部（2026-11-05，已完成）

已按用户确认方案完成“广告放在最底部，不直接跟在内容下边”，编译、定向单测和合成真机回归通过，真实测试广告的底部定位及手势区避让已验收。沿用当前功能分支并保留已有错误页修复，不自动提交；完整设备矩阵缺口见本节验证结果，下方历史记录继续有效。

### 目标、现状与方案选择

- 目标：广告展示时固定在页面可用区域底部、系统导航栏上方；短内容与广告之间由剩余空间隔开，长内容在广告上方独立滚动，广告不随结果滚动，也不覆盖内容。
- 修改前 `VideoExtractPage.kt` 把 `Success` 和 `PageAdSlot` 放进同一个 `verticalScroll(resultScroll)` 的 `Column`，两者按 12dp 间距依次排列，因此广告紧跟内容，而不是定位到底部。
- 已在既有有界内容区内采用“上方结果滚动区占满剩余高度 + 下方固定广告底栏”的兄弟布局。复用 `MediaAdFooter` 的自然测高与空间不足释放能力，单一 WebView 宿主保留原组合位置。
- 对比：`SecondaryPageScaffold.bottomBar` 也能固定到底部，但本页需要结合摘要、操作区之后的剩余高度保护网页/结果视口；在现有内容区内编排更直接。滚动容器内增加固定空白不能适配屏幕和内容长度；直接叠放到底部会遮挡内容，均不采用。
- 上一轮范围为视频提取页及复用底栏所需的兼容参数调整；技术沿用 Kotlin / Compose。后续下载页扩展见顶部章节；提取算法、错误页提前失败、下载权限/任务创建和广告来源配置维持现有行为。

### 复用检查与组件职责

1. 当前 video package：继续使用 `VideoExtractPage`、`VideoProbeControls`、`Success`、`videoExtractAdPlacement` 和已有 `resultScroll`；不新增页面状态机、ViewModel 或广告资格规则。
2. 当前 app 共享组件：复用 `SecondaryPageScaffold`、`PageBackground`、`MediaAdFooter`、`MediaAdFooterPolicy`、`PageAdSlot` 和 `rememberPageAdSession`。现有 `MediaAdGrid` 已采用内容与底栏分离布局，可沿用结构，但其图片索引/网格接口不适合视频结果，不整体接入。
3. 共享模块与全仓兜底：广告 API/来源模块负责请求和资源契约，不应接管页面布局；`FixedListAdLayout` 是列表上方广告且依赖列表内容状态，也不适合作为视频底栏。无需新增平行布局组件或修改 SDK 适配器。
4. 底栏的兼容参数、空间与释放规则只在 [广告主方案的固定媒体底栏章节](ad_module_plan.md#视频提取页复用固定媒体广告底栏2026-11-05已完成) 维护；本页仅记录接入差异。

### 页面接入与边界

- 保留“摘要 / 状态与操作区 / 剩余内容区”的外层结构。在原 `Box(Modifier.weight(1f).fillMaxWidth())` 内取得实际有界高度，非交互模式的前景布局改为：结果区域 `weight(1f)` 且使用原 `resultScroll`，之后为唯一 `MediaAdFooter`；不把底栏放回结果滚动容器。
- `SharedVideoProbeWebViewLayer` 继续在同一个组合位置、同一个 session key 下；广告加载、关闭或结果尺寸变化仅重新布局，不重建网页、不重新加载 URL、不重置提取计时。
- 短内容顶部位置维持现状，剩余空间由上方区域吸收；长内容仍能滚动到最后一个操作。底栏按素材真实高度占位，不硬编码广告高度，不增加常驻空白或额外固定底部垫高。
- 视频调用方传入 `PaddingValues(horizontal = 16.dp)`，继续继承 `PageBackground` 的共同外边距；不叠加底栏原默认 12dp。普通导航栏由 `SecondaryPageScaffold` 统一避让，页面内不重复增加 `navigationBarsPadding`；页面只补尚未消费的底部 `mandatorySystemGestures`。真机发现三星手势模式的导航栏 Insets 为 0、必需手势区为 80px，仅依赖骨架会让提示条压住广告下沿；局部兜底跟随系统 Insets，不硬编码 80px，也不修改其他页面骨架。
- 底栏可用高度取摘要、操作区之后的真实剩余区域；结果至少保留 96dp 可滚动操作视口，沿用已有媒体/列表底栏基线。小屏、横屏或大字体下素材放不下时不呈现广告，不能压缩、裁切广告或挤掉业务操作；超高素材的锁存与恢复时机以共享底栏既有规则为准。
- 保留 `videoExtractAdPlacement`：自动提取且有可见描述、或提取成功时才可能展示；Idle、失败、交互查看网页等原无广告状态仍无广告。广告未加载、无填充、失败、关闭或不满足显示资格时零占位。
- 查看提取网页时移除整个底栏组合并释放槽，网页获得剩余空间；收起后按原会话与资格规则接入。权限等待沿用现有请求/呈现抑制，保留 `retainOnDispose = false` 的释放边界；不以布局调整新增重试或刷新请求。
- 保留页面触摸守卫和滚动期间延迟广告布局变更的规则；继续以页面生命周期控制请求与释放。验证摘要/操作区滚动、结果滚动和按压期间不发生会影响当前操作的突然呈现。

### 单任务实施清单

本节按 `writing-plans` 组织，由当前会话按 `executing-plans` 顺序完成；方案与实现记录继续维护在本文，不另建重复计划，不自动提交。本轮独立差异基线与执行记录保存在忽略的 `build/video-ad-footer/`，避免把已有错误页修复混入审查。

- [x] 调整 `app/src/main/java/com/cla/clip/master/ui/widget/media/MediaAdFooter.kt` 的兼容参数与中文注释，按广告主方案保留默认图片行为；`MediaAdGrid.kt` 仅适配最小内容高度参数名，不调整网格逻辑。现有 `MediaAdFooterPolicy.kt` 的判定公式不变，只修正图片专属注释的适用范围。
- [x] 调整 `app/src/main/java/com/cla/clip/master/ui/page/video/VideoExtractPage.kt`：把广告移出结果滚动区并接入上述底栏；按剩余区域传入高度、96dp 最小视口和 16dp 横向留白，沿用既有资格、会话和回调。新增/修改的方法、变量、回调与关键分支补齐简体中文注释。
- [x] 运行定向 JVM 回归、app 与 androidTest Kotlin 编译、Debug 与测试包打包；最终命令及结果见下文。本轮单测门禁判断为纯布局接线、Insets 与兼容参数调整，不新造 JVM 规则测试、不强制 TDD；空间公式和广告资格没有变化。
- [x] 三星真机复跑现有 `VideoExtractPageTest` 的网页显隐、键盘、成功及失败接线用例；真实测试广告验证固定底边与系统手势区避让，失败态无广告。下表为完整验收目标，本轮实际覆盖与缺口分别记录在验证结果中。
- [x] 核对仅有一个广告槽、图片底栏默认边距未变、WebView 身份未变；完成四文件独立审查、两份文档同步与 `git diff --check`，保留当前工作区已有错误页修复。

### 验收标准与风险

| 场景 | 验收结果 |
| --- | --- |
| 成功态短标题、短结果 | 广告底边贴合页面内容安全区底边；内容与广告之间自然留出剩余空间 |
| 长描述、长候选名、动态大字体 | 内容可以完整滚动，下载操作可达；广告位置不跟随结果滚动 |
| 延迟加载、加载失败、关闭广告 | 成功加载后才占实际高度；不覆盖内容；失败/关闭后没有空槽 |
| 查看网页、收起、失败重试 | 查看时无广告，触摸/键盘可用；显隐不因底栏迁移额外重载网页，失败状态不残留广告 |
| 横屏、小屏、系统导航模式切换 | 系统栏只避让一次；素材过高时让位给内容，不能裁切或让操作区归零 |
| 滚动/按压中广告完成加载，后台与返回 | 既有交互保护、请求与释放规则有效，无重复槽或新增循环请求 |
| 图片提取/图片任务回归 | 底栏默认 12dp、96dp 最小网格视口、空间保护和关闭行为保持 |

本次没有新增独立纯规则，主要风险是 Compose 测量、SDK 原生素材高度与窗口 Insets，需以页面/设备验收覆盖。现有页面仪器测试不包含广告底边坐标断言，不能把它通过当作本需求已经验收；需使用测试广告检查短/长内容时实际底边与遮挡情况，不点击广告 CTA。

### 实施与验证结果

- 生产改动为 `VideoExtractPage`、`MediaAdFooter`、`MediaAdGrid` 的布局/接口接线以及 `MediaAdFooterPolicy` 的职责注释；没有新增组件、类、业务规则或测试源码。页面采用剩余结果视口加固定底栏，单一网页宿主和原广告会话保留；默认图片边距与策略公式保持。
- 最终命令：`.\gradlew.bat :app:testDebugUnitTest --tests '*MediaTaskAdPolicyTest' --tests '*MediaAdFooterPolicyTest' :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:assembleDebugAndroidTest --init-script build\review-build-isolation.gradle --console=plain`，`BUILD SUCCESSFUL`。资格回归 5 项、空间策略回归 3 项，失败/错误/跳过均为 0。日志为忽略的 `build/video-ad-footer/verification-final.log`；首次沙箱无法访问既有 Gradle 缓存锁，批准工作区外缓存访问后完成验证。
- 三星 SM-G9730 / Android 12 保留数据覆盖安装最终 Debug 包，以 `adb -s <device> shell am instrument -w -r -e class com.cla.clip.master.ui.page.video.VideoExtractPageTest com.cla.clip.master.test/androidx.test.runner.AndroidJUnitRunner` 运行已有合成用例，最终 `OK (2 tests)`。覆盖三行长标题、真实网页点击、键盘、显隐同实例、超时重试、STOP/START、候选成功以及错误页双模式接线；日志为 `build/video-ad-footer/device-final.log`，不访问真实剪贴/下载仓库。
- 用户另行明确授权只截取视频提取页。真实 `Test Ad` 在自动提取阶段加载后位于底部，与短内容之间自然留白。首轮截图发现设备导航栏 Insets 为 0，必需手势区为 80px；本页补尚未消费的底部手势 Insets 后，最终 `safe.png` 确认广告完整位于手势条上方。原始失败布局 `loading.png` 与最终布局均保存在忽略的 `build/video-ad-footer/`，不硬编码设备像素，也不提交含页面标题的截图。
- 该真实网页仍按原十秒预算超时，本轮不把它记录为视频提取成功；失败状态没有残留广告。尝试关闭验收时，新一轮快照尚未出现广告关闭按钮，因此没有点击未知位置，也不宣称关闭路径实机通过；未点击广告 CTA、未创建下载任务。验收结束退出网页，并恢复临时切换的键盘语言。
- 四文件独立只读审查无可确认的 Critical / Important / Minor 问题；手势区补充由失败截图、系统 Insets 证据、最终编译/回归及截图复核验证。审查明确原摘要/操作区惯性滚动的门禁与此前错误页修复属于既有行为，本轮不扩大为广告门禁或提取规则重构。`git diff --check` 通过。
- 未完成完整横屏、超高素材、三键导航、图片页面视觉、深色模式及多设备矩阵；现有策略单测与默认参数兼容检查不能替代这些实机边界。当前未新增 JVM/UI 测试，不运行全量 app 单测或 release/R8，理由是纯布局/Insets 接线且没有反射、序列化、SDK 适配或持久化变化。以上为已记录验证边界，不把未运行项写为通过。

### 日志与诊断计划、数据边界与后续待办

- 纯布局迁移不新增运行时日志，避免在重组、测量和滚动回调刷屏。复用广告宿主的 DEBUG 关闭/释放与状态诊断、既有加载失败 WARN，以及 `VideoProbe` 生命周期日志；验证显隐与后台切换没有新增请求或重复释放。
- 诊断只使用既有广告位、session、状态、reasonCode 等低敏字段；禁止打印网页正文、标题、完整 URL、Cookie、候选地址或广告素材。位置验收通过受控测试广告截图/布局检查完成，不把真实用户内容写入日志。
- 无数据库、设置、备份或外部协议变化；不需新增备份适配、keep 或 R8 规则。此纯布局范围拟不运行完整 release/R8；如实施实际触及 SDK/反射/协议则重新评估。
- 后续待办：补齐上文未覆盖的关闭、横屏、素材高度、图片页与多设备矩阵；若 96dp 在目标窗口的大字体验收中仍影响操作，先调整方案并评估共享调用方，不能通过裁切广告解决。

## 抖音错误页提前失败（2026-11-05，已完成）

用户已确认仅做错误页提前失败，对新增失败只提示“视频提取失败”，详情接口兜底后续再加。本节为本轮实施方案与进度记录，下方已完成验收保留为历史证据。沿用当前工作区保留既有文档修改，从当前 main 创建 `codex/video-error-early-failure` 分支，不自动提交；按用户已确认范围直接实施，不重复请求批准。

### 范围、复用与接口

- 只在已加载的抖音分享页识别本轮已核实的“抱歉出错了 / 请尝试在抖音内观看”错误组件；自动与交互模式均结束该轮、取消计时和扫描并释放网页，复用现有失败区、重新提取和查看网页重试入口。新失败只映射资源文案“视频提取失败”；其他既有网络/HTTP/SSL/超时文案不作全局调整。
- 继续复用 `ProbeWebView` 原生加载/释放、`VideoProbeSession` 会话/文档身份、`VideoProbeMessageMapper` 和共享状态组件。站点错误属于页面内容，不混入共享原生网络错误枚举；图片页保持现有行为，不引入新的 Composable。
- 新建视频领域 `DouyinErrorPagePolicy.kt`，以 `isDouyinSharePage(url: String?): Boolean` 校验完整域名白名单与数字作品路径；非法地址、伪造子域名、非分享页均不探测。`DouyinErrorPageScript.kt` 只读取 `.special-case-wrap .special-case-container` 内可见的标题、副标题、打开按钮，严格匹配完整错误文案；存在视频源时不提前判错。选择器来自本次公开分享组件脚本核查，站点未来改版时宁可回到原超时，不宽泛搜索页面全文。
- `DouyinErrorPageProbe.kt` 在主线程有界调用 JS，只接收布尔快照；相隔 500 ms 的两次连续肯定结果才确认，单次回调上限 1 秒、每文档总预算 10 秒。空值、脚本异常、结构变化、正常引导及短暂错误都不直接失败；原自动十秒总预算不延长。没有新增 JS bridge 或 JSON 反射模型，布尔返回协议不依赖 R8 保留名称，无需新 keep；完整 release/R8 构建本轮不做。
- 视频适配层持有当前文档扫描 Job，主文档切换、原生失败、候选成功、STOP/离场及时取消。`VideoProbeSession.onPageUnavailable(expectedSession, expectedDocument): Boolean` 仅接受当前已完成文档且仍在提取的回调，成功优先于迟到错误，失败后的旧超时不能改写新失败原因。已明确错误页退出后可显式重试，不自动反复加载。

### 实施步骤与测试门禁

本节作为 `writing-plans` / `executing-plans` 的单任务实施记录，规格即上述已确认范围；优先维护既有主文档，不新建重复方案。按仓库约定不自动提交，现有主机目录保留用户诊断文件，构建沿用 `build/review-build-isolation.gradle`。改动由当前会话实现，完成后做独立审查。

- [x] 核对现有视频、共享网页、图片扫描与预览提取能力；无可直接复用的站点错误判定，复用 JS 回调模式但不依赖其他业务的私有封装。修改前 `:app:testDebugUnitTest --tests '*VideoProbe*Test'` 基线通过。
- [x] 先写 URL 边界、当前文档提前失败、候选优先、旧导航/暂停/重试拒绝、统一提示的 JVM 回归；添加无行为的接口骨架以使测试可执行，观察预期断言失败后实现。
- [x] 用合成 WebView 错误区验证真实 DOM：正确组件命中，隐藏组件、评论中的同文案、只有打开按钮、已有视频源不命中；短暂错误不直接结束。补页面接线测试，确认完成加载后在十秒前显示“视频提取失败”、网页释放且重试入口保留。
- [x] 实现领域判定、JS 快照、有限扫描及页面/会话接线；新增和修改的方法、字段、变量、回调均补中文注释。
- [x] 运行 `:app:testDebugUnitTest :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`，构建并在已授权真机运行定向合成用例；按现有两条抖音样例补手动验证，证据仅保留安全状态与耗时。
- [x] 独立审查本次差异，无阻塞项；核对 `git diff --check`、文档状态与变更范围。

审查重点：普通 App 引导/评论不能误判；隐藏祖先或已有媒体源不能提前失败；两次快照之间恢复正常须清空确认；旧文档或停止后回调不能影响新会话；错误确认与候选成功竞态不能覆盖已成功结果。

### 日志与诊断计划

仅在会话接受明确错误时新增一条 WARN，字段为 sessionId、documentId、固定 reasonCode `site_error_page`，耗时沿用网页释放日志；不逐次打印扫描、网页文本、HTML、URL、资源 ID、Cookie 或异常 message。JS 异常/超时回到既有提取预算，不新增噪声日志。无数据表、设置、下载元数据、备份或外部持久化协议变化。详情接口兜底继续保留为后续工作。

### 实施与验证结果

- 已完成领域 URL 策略、布尔 DOM 脚本、有界扫描器及会话/页面接线；新增 `PageContent` 仅映射“视频提取失败”。复用共享 WebView、既有失败与重试组件；无新增 Composable 或共享原生错误类型。新增/修改方法、变量与关键分支均有简体中文注释。
- 选择性 TDD：新增 6 条 JVM 回归，先观察 3 条预期行为断言失败再实现通过；真实 WebView 的 5 条 DOM 用例先观察 2 条预期失败，再补双模式页面接线用例并观察未接线时等待失败。测试发现 `loadDataWithBaseURL` 的空 historyUrl 使原生 URL 为 `about:blank`、与 JS 来源不一致，已仅修正合成 fixture，不放宽生产来源校验。
- 最终执行 `.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:assembleDebugAndroidTest --init-script build\review-build-isolation.gradle --console=plain`，`BUILD SUCCESSFUL`；app 全量 399 条 JVM 用例零失败、错误和跳过。
- 三星 SM-G9730 / Android 12 真机覆盖安装最终 `0.9.19` Debug APK，保留应用数据。新增 DOM 与页面用例 `OK (6 tests)`；页面与共享生命周期回归 `OK (8 tests)`，剔除重复的双模式用例后共 13 条不同设备用例通过。最后注释/fixture 整理后重跑新增 6 条仍通过。设备命令为 `adb -s <device> shell am instrument -w -r -e class <testClasses> com.cla.clip.master.test/androidx.test.runner.AndroidJUnitRunner`，测试类为 `DouyinErrorPageProbeTest`、`VideoExtractPageTest`、`ProbeWebViewTest`、`ProbeLifecycleEffectTest`，没有运行全仓所有设备测试。
- 原失败抖音记录真实验收：自动模式命中 `site_error_page` 后释放 WebView，总耗时 `2533 ms`；“查看网页并重试”交互模式同样命中，耗时 `2148 ms`。均显示“视频提取失败”且保留重试入口；日志未出现这两轮的超时或候选接受，确认无需等待十秒。
- 对照记录本次环境出现变化：可见重试实际显示普通播放器封面与“打开看看”引导，不再是此前 Chrome 的错误页。自动轮次未捕获媒体、按原十秒预算超时；交互轮次正常保留网页，没有被新规则提前判错。未观察到该轮视频候选成功，不能宣称对照视频已在应用内完成提取或下载；当前结论仅为错误页提前失败与普通页面不误判。
- 构建/设备证据保存在忽略的 `build/video-error-validation.log`、`build/video-error-device-green.log`、`build/video-error-device-regression.log`；真实阶段与耗时为 `build/video-error-real-validation.log`，对照页面截图为 `build/video-error-control-interactive.png`。日志只保留 `VideoProbe` 阶段字段，无正文、URL、资源 ID、Cookie 或请求头；验收结束离开网页，不创建下载任务。
- 独立只读审查无 Critical / Important 问题；已有候选、旧导航、停止与迟到回调保护符合范围。未评判的详情兜底、既有候选启发式与站点未来改版均维持本节范围，不附带扩大实现。`git diff --check` 通过；不提交或推送。规则例外仅完整 release/R8 未运行：没有新增 bridge/反射/序列化名称依赖，布尔 JS 返回不需 keep；无持久化字段，因此不需备份适配。

### 后续待办

- 详情接口兜底按用户要求后续独立设计；站点改版或其他错误类型需先取得对应证据，再增加精确判定，未知状态继续沿用原预算。

## 视频提取页简化与可见网页辅助提取（2026-11-04，已完成）

用户已确认按本方案实施。本节记录 2026-11-04 已完成的增量需求；2026-11-05 错误页提前失败以顶部新章节为准。沿用当前工作区并创建 `codex/video-extract-visible-webview` 分支，保留已确认文档，避免另建工作树后复制本地方案和构建配置；不自动提交。2026-11-04 用户随后明确要求在已连接真机测试，本次授权覆盖下文历史记录的设备限制；此前覆盖安装保留应用数据并使用合成网页与定向用例，本次追加真实公网网页的提取、下载和播放验证。

### 真实公网网页补充验收（2026-11-04）

- 用户随后指定使用手机现有抖音剪贴记录补测。通过应用搜索找到一条直播切片分享记录，从其详情页进入提取：自动模式在主文档完成后仍未命中候选，约十秒触发 `no_candidate_timeout`。失败后“查看网页并重试”及再次“重新提取”均能加载页面，但抖音网页明确显示“抱歉出错了，请尝试在抖音内观看”，交互等待超过三十秒仍未识别到视频；三轮会话均无候选接受日志，未创建下载任务。
- 抖音补测结论是**这条现有分享链接当前提取失败**。初次验收定位到网页播放器不可用阶段，当时尚无证据区分链接失效、登录态、站点风控或 WebView 兼容性；后续公开接口查询得到的作品过滤原因见下文，仍不能由公开 MP4 成功推断抖音全面兼容。没有自动登录、处理验证码、修改 Cookie 或放行 SSL 错误；退出提取页已释放网页。
- 用户要求进一步在浏览器对照：在同一三星真机、相同网络下，用 Chrome `155.0.8059.79` 与三星浏览器 `30.1.5.27` 分别打开同一条抖音短链接，两者均跳转到抖音分享页并显示相同的“抱歉出错了，请尝试在抖音内观看”，均未出现可用播放入口，也未正常播放。截图为忽略的 `build/douyin-chrome-qa.png` 与 `build/douyin-samsung-qa.png`；未更改登录态、浏览器设置或默认打开方式。
- 浏览器对照说明该失败**并非仅在应用 WebView 内发生**，因此不能归因于“抖音视频一律不支持 WebView”；当前只能确认这条视频在所测手机网页环境下不可播放。尚未验证抖音 App 内的内容有效性、其他分享链接或网页错误响应，链接/内容状态与站点网页策略仍需进一步区分；不把推测作为已确认根因。
- 用户追问错误页是否仍能拿到视频 ID：只读检查同一真机 Chrome 页面后，确认跳转路径仍携带作品标识，页面 `_ROUTER_DATA.loaderData['video_(id)/page']` 也保留 `itemId`；但该状态中未找到 `video_id`、`play_addr`、`url_list` 或 `videoInfoRes` 等媒体字段，实际 `video` 元素数量为 0。脱敏结构摘要保存在忽略的 `build/douyin-id-inspection.json`，只含字段名、类型、数量和字符串长度；临时 ADB 调试转发已移除。
- 作品 ID 可读取不等于已取得下载资源。源码实际行为是 `VideoProbeWebViewLayer` 捕获完整媒体请求 URL 并保留请求上下文，`DownloadVideoWorker` 仅对已有 `/playwm/` 地址尝试替换为 `/play/`，并不存在“从作品 ID 自动拼出可下载地址”的实现。当前错误页没有取得播放资源 ID 或有效媒体地址；若考虑作品详情解析/查询兜底，必须另行验证资源字段是否可取得及请求是否可用，不能直接把作品 ID 当成播放接口的 `video_id` 或承诺可下载。本次仅核对事实，不实施兜底或修改代码。
- 针对“下载地址是否只需替换视频 ID”的资料核查：截至 2026-11-04，[short_videos 的原始实现](https://github.com/jiuhunwl/short_videos/blob/main/api/douyin/DouyinParser.php) 中，`extractVidFromVideoInfo` 从 `video.play_addr.uri` 等字段取播放资源 ID，`buildOriginalPlayUrl` 使用 `https://aweme.snssdk.com/aweme/v1/play/?video_id={资源ID}&ratio=default&line=0` 模板，随后尝试跟随重定向。因此固定模板方案确实存在，但输入不能与分享页作品 ID 混用；这是第三方源码证据，并非抖音官方稳定契约，也不代表已用当前失败样例验证下载成功。
- 地址形式并不统一：[douyin_downloader 作者的下载记录](https://github.com/ljinshuan/douyin_downloader/blob/main/docs/douyin-video-download.md) 同时展示含 `file_id`、`sign` 的播放 API，以及含临时鉴权字段的 CDN 媒体地址。不能把这些动态字段一律写死。资源字段获取与模板可用性的后续实测见下文；无需将网页成功播放视为所有解析方式的必要前提，但仅有错误页的作品 ID 尚不足以证明可下载。
- 资料核查阶段仅补充事实边界，复用现有方案和前轮脱敏证据，不新增组件、方法、运行时日志或自动化测试；当时未实施详情查询或下载兜底，不重复编译、单测和 R8。文档检查使用 `git diff --check -- docs/video_extract_plan.md`。
- 用户随后授权实际尝试获取资源 ID。本轮从 Windows 主机直接请求抖音公开接口，不携带用户登录 Cookie，不等同于新增一轮真机 WebView 验收。原失败作品在 `www.douyin.com` 与 `m.douyin.com` 的分享页均返回 HTTP 200，但解析出的状态无 `videoInfoRes`、`play_addr` 或播放资源 ID；公开详情接口也返回 HTTP 200 / `status_code=0`，实际没有 `aweme_detail`，并在 `filter_detail` 返回 `filter_reason=status_audit_self_see`。复核过滤对象的作品 ID 与目标精确匹配，因此不能将接口状态码 0 当成获取成功；可以确认目标详情被服务端过滤，具体审核或可见性含义仍无官方解释，不直接断言用户设置为私密或内容已删除。
- 补充按[开源文档](https://github.com/ucmao/media-parser/blob/main/docs/parsers/douyin.md) 的公开 Feed 入口查询，返回六条内容但目标作品匹配数为 0，未使用其他作品的资源冒充目标成功。之后以此前从手机剪贴中已定位的另一条抖音记录作为对照：同一详情接口成功返回精确匹配的 `aweme_detail`，取得 `video.play_addr.uri` 与三个播放地址。将该资源 ID 代入固定模板后，一次重定向得到 HTTP 206 / `video/mp4`，`Content-Range` 为 `bytes 0-4095/6880899`，实际只读取 4096 字节且文件头包含 MP4 的 `ftyp` 标记。该步仅证明对照作品可取得资源 ID、模板可返回视频数据；后续完整下载验证见下文，没有修复原失败作品或将详情解析接入应用。
- 用户进一步要求完整下载对照视频：复用上述资源 ID 和固定模板，在 Windows 主机去掉 Range 请求头后取得 HTTP 200 / `video/mp4`，一次重定向完成下载，耗时约 53 秒。`Content-Length`、实际文件大小和此前范围响应报告的总量均为 `6880899` 字节。文件位于忽略的 `build/douyin-control-full-download.mp4`；`ffprobe` 确认时长 `53.242993` 秒、`720 × 1280`、H.264 视频与 AAC 双声道音频，`ffmpeg -v error -xerror -i build/douyin-control-full-download.mp4 -map 0:v:0 -map 0:a:0 -f null NUL` 完整解码退出码 0、无错误。由此确认对照视频通过资源 ID 模板完整下载成功，超出前轮仅检查文件头的验证范围；本次未接入应用、未在手机完成这条对照视频的下载或播放。
- 完整下载诊断保存于忽略的 `build/douyin-control-full-download.json`，仅保留 HTTP 状态、媒体类型、字节数、摘要、耗时与本地校验结果，不记录 CDN 签名地址、请求头、Cookie 或响应正文。复用既有对照样例、方案文档与 ffprobe/ffmpeg，无生产或测试代码、组件/类/方法、注释契约、持久化业务字段变化；按单测门禁采用真实外网下载及完整解码验收，无需新增单测/TDD、编译/R8、业务日志或备份适配。文档运行 `git diff --check -- docs/video_extract_plan.md`，真实样例文件与诊断确认保持 Git 忽略。
- 用户进一步询问是否已全面禁止网页播放。截至本次核查，[抖音官方帮助页](https://www.douyin.com/help) 仍提供网页版播放、浏览器声音、音画同步和播放卡顿排查，[开放平台文档](https://partner.open-douyin.com/docs/resource/zh-CN/dop/develop/openapi/video-management/douyin/iframe-player/get-iframe-by-video) 仍列出通过作品 VideoID 获取网页 iFrame 播放代码的能力。未找到全面关闭网页播放的官方说明；这些资料支持网页播放仍是官方提供的功能，但不能保证任意手机分享入口、浏览器会话或作品均可播放。没有找到 `status_audit_self_see` 的官方公开释义，不将其进一步解释为已确认的用户私密设置或删除状态。
- 新增真机网页对照：在同一手机 Chrome 普通界面打开已完整下载成功的对照视频短链，仍出现“抱歉出错了，请尝试在抖音内观看”，没有可用的网页播放入口。通过界面地址栏与短链重定向的作品号精确比较，确认当前确为该对照作品、不是原失败页；直接读取该公开分享页也没有 `videoInfoRes` 或 `play_addr`。截图为忽略的 `build/douyin-control-web-current.png`，脱敏核对为 `build/douyin-web-availability-probe.json`；仅使用正常页面打开和 UI 控件读取，没有启用 Chrome 调试转发、读取 Cookie、切换浏览器设置或登录账号。
- 本轮进一步收敛结论：两条样例在手机分享页显示相同错误，但原作品详情被过滤，对照作品详情与完整媒体下载正常。因此分享页错误不能直接证明视频已失效、资源 ID 无法取得或文件不能下载；手机分享页、官方网页版/嵌入播放器、详情接口和媒体地址属于不同访问路径。当前证据仍不足以区分分享页策略、访问环境与会话/风控条件，不能把“强制导流 App”或“全部 WebView 不受支持”写成已确认根因。现有仅等待页面发起媒体请求的提取路径会受到这种分享页失败影响；对照详情解析能力尚未接入应用。本轮复用既有样例与验收工具，仅更新事实记录，不新增代码、组件/方法、业务日志或自动化测试，验证边界与前述单测门禁结论一致。
- 证据保存于忽略的 `build/douyin-resource-page-probe.json`、`build/douyin-resource-api-probe.json` 与 `build/douyin-resource-control-probe.json`。只记录状态、字段存在性、精确匹配结果、过滤原因及有限媒体头验证；原始 HTML/JSON、推荐内容、Cookie 和签名媒体 URL 不落盘。临时 Chrome 调试转发申请被自动审批拒绝，理由是可能暴露其他标签页和登录态；未建立转发，后续改用上述限定作品的公开请求完成验证，没有读取浏览器会话。此次仅更新文档与忽略的诊断证据，无组件/类/方法或注释契约变化；单测门禁判定为外网人工验证，不新增单测/TDD、不运行编译/R8，未新增运行时日志或备份数据。
- 抖音现场截图与脱敏会话日志保存在忽略的 `build/douyin-qa-auto-timeout.png`、`build/douyin-qa-interactive.png`、`build/douyin-qa-retry.png` 和 `build/douyin-qa-diagnostics.log`。仓库文档不复制用户分享正文、短链标识或视频地址；复用现有页面/日志，仅做人工验收与记录，无生产/测试代码变化，不新增单测或运行编译/R8。另核实上一轮恢复来源名的重复提交被去重，已用唯一临时标记成功恢复 `Shell` 并删除该标记，保留用户原剪贴数据。
- 复用已连接三星 SM-G9730 和已安装的 `0.9.18` Debug 应用，本次未重新安装、清除数据或修改生产/测试代码。通过现有剪贴桥接 Provider 添加一条明确标记 `Video QA 2026-11-04` 的公开页面记录，再从详情页点击“提取视频”。
- 正向样例为 [W3Schools HTML Video](https://www.w3schools.com/html/html5_video.asp)。真机自动模式在约 3.5 秒捕获 `mov_bbb.mp4` 并显示“视频地址提取成功”；未注入合成候选、未替换下载实现。点击“去下载”后约 5 秒完成，页面显示“视频下载完成，点击播放”。
- MediaStore 中本次文件大小为 `788493` 字节，时长 `10027` 毫秒，尺寸 `320 × 176`，MIME 为 `video/mp4`，`is_pending=0`。下载文件保留在手机 `DCIM/clipMaster/`，真实视频任务与公开测试剪贴保留供复查；来源显示名已恢复为原来的 `Shell`，系统剪贴板和默认打开方式未更改。
- 从下载完成入口选择系统“视频播放器”且仅打开一次，已看到样例视频实际画面。拉取本次生成的文件后，`ffprobe` 确认 MP4 / H.264 / AAC，时长 `10.026667` 秒；`ffmpeg -v error -i build/video-real-sample.mp4 -map 0:v:0 -map 0:a:0 -f null NUL` 完整解码退出码为 0、无错误。
- 同时检查列表里现有的 B 站短链接：主文档加载完成后尝试唤起外部应用，自动模式十秒未命中而超时；展开重试可看到内容页及“打开 APP 即可浏览完整内容”，本次未找到可用的视频播放请求。该页面不是已确认能在网页播放的视频样例，因此不能由此推断所有 B 站视频都失败，也不能声称此链接已修复。
- 调试广告验证气泡曾遮住成功区按钮，关闭气泡和测试广告后继续完成下载/播放；此观察仅记录本次 Debug 验收现场，不调整广告或提取逻辑。
- 证据位于忽略的 `build/video-real-extract-success.png`、`build/video-real-download-success.png`、`build/video-real-playback.png`、`build/video-real-device-diagnostics.log` 和 `build/video-real-media-validation.json`。诊断文件只保留本轮会话/阶段/耗时/任务成功事实，媒体 URI 已遮蔽，不保存候选对象、Cookie 或请求头；不新增业务日志。
- 单元测试门禁结论：本次是依赖真机、WebView、外网及 MediaStore 的人工验收，没有实现规则或代码变化，不新增 JVM/仪器测试，不重复此前编译、单测和 R8；文档修改运行 `git diff --check`。结论仅证明本次公开 MP4 网页的提取→下载→系统播放链路成功，M3U8、登录/验证码、其他平台和设备仍需对应样例验证。

### 本轮真机验收（2026-11-04）

- 已连接三星 SM-G9730，Android 12 / API 31，Google WebView `158.0.8127.2`；覆盖安装当前 Debug APK 与测试 APK，未清除或卸载用户应用数据。
- `ProbeWebViewTest` 四条、`ProbeLifecycleEffectTest` 两条已在真机运行通过（`OK (6 tests)`）：实际 DOM 点击、同实例显隐与加载连续性、主文档错误去重、STOP 关闭迟到回调，以及图片/视频会话恢复。
- 新增 `VideoExtractPageTest` 一条完整页面验收已通过（`OK (1 test)`），与前述六条合计七条通过：长标题实际三行省略、无封面、网页真实触摸、系统键盘弹出与收起、同实例显隐、自动超时后可见重试、实际 Activity STOP/START 恢复新轮次，以及合成媒体请求触发成功后移除 WebView。已检查展开、键盘、超时与成功四张截图。
- 该测试复用现有 Compose 框架、生产页面/状态机/WebView；预览使用只读合成值、下载 DAO 拒绝写入，测试替身不读取业务剪贴数据。按单测门禁采用仪器验收，不修改生产代码、不增加重复 JVM 用例；未实施新规则或修复生产 bug，因此本次无需新的失败先行阶段。真实网站登录/验证、横屏/其他设备、真实 SSL/重定向时序及下载成功率仍待对应场景验证。
- 构建命令：`.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --init-script build\review-build-isolation.gradle --console=plain`，结果 `BUILD SUCCESSFUL`；后续测试文件增补再次运行 `:app:assembleDebugAndroidTest` 通过。设备命令为 `adb -s <device> shell am instrument -w -r -e class <testClasses> com.cla.clip.master.test/androidx.test.runner.AndroidJUnitRunner`，按上述两个 widget 类及一个 video 类定向执行，未运行全量设备测试。
- 测试报告保存在忽略的 `build/video-device-tests.log` 和 `build/video-device-page-tests.log`；截图为 `build/video-probe-qa-{expanded,keyboard,timeout,success}.png`。`build/video-device-diagnostics.log` 核对了开始/完成、显隐、超时、暂停恢复、候选接受和释放，所筛选的 `VideoProbe` 行仅含阶段、会话与耗时，无网页内容或凭证。无新增业务日志、持久化字段、外部协议或 R8 规则。

### 实施与验证结果

- 已实现无封面、三行标题、同实例展开/收起、失败后可见重试、可操作网页、自动十秒/交互三十秒提示、加载错误分类与交互广告撤下。
- 状态移到 `VideoProbeState.kt`，`VideoProbeSession` 负责计时版本和文档诊断归约；`VideoProbeMessageMapper` 只生成安全资源消息。共享原生回调拆为 `ProbeWebViewClient`，`ProbeWebViewOwner` 在 STOP 直接释放，AndroidView 离场再次清理由原子门闩保证幂等；避免后台重组暂停导致网页继续工作。
- 新增 15 条 JVM 回归，分组观察到 5、4、4、1、1 条预期断言失败后修复通过；覆盖同实例显隐、旧预算、可见重试、恢复偏好、错误现场、旧文档、失败文案、广告资格与幂等释放。实现阶段新增五条仪器用例，真机验收再补一条完整页面用例；连同已有图片生命周期用例共七条在真机通过，详见上节。
- 最终命令：`.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --init-script build\review-build-isolation.gradle --console=plain`，结果 `BUILD SUCCESSFUL`；app 387 条单测，零失败/错误/跳过。使用已有隔离输出脚本与获准的 Gradle 缓存访问，不修改构建配置。
- 独立审查发现并修复 1 个 P2：导航 B 开始但 `getUrl()` 仍为 A 时，B 的错误不得被当作旧页面事件丢弃。失败按最新导航目标匹配，完成仍要求当前显示文档匹配；网络/HTTP/SSL 的合成回归先失败后通过。没有遗留阻塞审查项。
- 验证边界：最初沙箱内 ADB daemon 无法启动，用户授权真机验收后已通过获准的主机 USB 访问完成安装与定向测试。已验证与待验证范围以上节真机记录为准；合成候选成功不代表所有网站或下载均成功。
- 已同步本主文档与共享媒体主文档，未改变数据库、备份、导航参数、JS bridge、序列化或 keep；因此本轮不做 release/R8。新改方法、变量与回调已补中文注释；日志限于会话、阶段、耗时与数值错误，不输出原始网页和鉴权数据。

### 目标与范围

- 用户要求视频提取页不显示封面，标题最多三行；增加按钮，点击后能看到 WebView 中的提取过程，帮助排查频繁提取失败。
- 用户已确认展开后允许操作网页，包括点击播放、登录及验证，并确认按本方案的页面呈现、计时与错误提示细节实施。
- 仅调整视频提取页及必要的共享 WebView 接口；图片提取页继续隐藏、屏蔽触摸，视频下载页继续使用原有摘要和封面。
- 保留现有候选识别规则、首个候选策略、下载权限/任务创建和链接预览补全。此次提供观察与辅助操作入口，不宣称已找到所有站点提取失败的原因，也不承诺所有网页都能提取。

### 修改前源码依据与复用检查

1. 当前视频 package：`VideoExtractPage` 在 `HiddenProbing` 进入后计时十秒，未命中则转 `Failed` 并卸载 WebView；`VideoProbeSession` 已处理重试轮次、暂停恢复和旧回调拒绝。继续扩展该会话，不另造一套页面状态机。
2. 当前 app 模块：`SharedVideoProbeWebViewLayer` 固定 `alpha(0f)`、`consumeUserTouch = true`；`ProbeWebView` 已统一加载、Cookie、重定向、请求拦截和释放，但触摸监听只在 factory 设置，当前没有加载进度/网络错误事件。`OfflineHtmlDocument` 已有只处理主文档错误的范例，可沿用过滤原则，不能复用其禁止网络和 JavaScript 的配置。
3. 共享能力：复用 `SecondaryPageScaffold`、`PageBackground`、`MediaSourceSummary`、行内状态组件、`ProbeLoadSession`、`ProbeLifecycleEffect` 及已有日志封装。`MediaSourceSummary` 已限制标题三行，只需视频提取调用方传入空封面；不修改共享摘要默认契约。
4. 共享模块及全仓兜底：未找到可直接接入的“显示提取过程”组件；其他 feature 的验证页有独立导航/站点策略，不适合引入本页。通用网页展示和加载事件放共享 widget，视频候选与超时策略留在 video package。

已确认的是十秒截止、不可操作和失败后销毁这些源码行为；缺少用户失败网页的现场和错误码，不能把它们直接认定为所有失败的根因。现有自动播放脚本也受站点和 WebView 策略约束。

### 方案取舍

推荐在当前页展开正在提取的同一个 WebView：会话、滚动位置、站内导航和请求拦截连续，用户可直接观察并操作。另开页面/弹窗并新建 WebView 会重新加载，无法观察原轮次；把原生 View 在两个宿主之间迁移也增加生命周期复杂度，因此本轮不采用。仅切透明度无法处理十秒截止、触摸监听和前景遮挡，也不足以满足需求。

### 页面与交互

| 场景 | 展示与操作 | 会话行为 |
| --- | --- | --- |
| 初次进入 | 不展示封面；标题最多三行、超出省略；保留来源和已有描述；状态下方增加“查看提取过程” | 默认仍隐藏网页，自动提取十秒 |
| 正在提取时点击查看 | 当前页显示网页区域，按钮改为“收起网页”；提示“可在网页中点击播放，辅助识别视频” | 同一实例、同一 session，不重新 loadUrl；启用网页触摸 |
| 自动提取失败 | 展示具体原因，保留普通“重试”；另提供“查看网页并重试” | 旧 WebView 已释放；查看入口明确开启可见的新 session，不声称能还原上一次现场 |
| 展开后操作网页 | 支持滚动、播放、网页内输入与既有站内导航；顶部状态与“收起网页”保持可达 | 继续拦截候选请求；不新增外部 App 唤起、多窗口或播放器全屏支持 |
| 收起网页 | 网页重新隐藏、屏蔽触摸并退出输入焦点；恢复摘要和自动提取提示 | 保留正在运行的实例，重新开始一段十秒自动预算；不重载入口 |
| 识别成功 | 自动收起并释放网页，显示已有成功状态和“去下载” | 立即关闭候选接收，只接受首个有效结果，不因点击查看而创建下载任务 |
| 显式重新提取 | 沿用当前显隐模式，新轮次重载原始入口 | 递增 session，清空旧诊断，旧候选和错误全部失效 |

布局采用“摘要 / 状态和操作区 / 有界网页内容区”。展开时网页使用剩余可用空间，避免把 AndroidView 放进无限高度的纵向滚动容器。摘要在小屏、大字体和键盘弹出时允许独立滚动，网页操作区不能被前景遮罩或广告覆盖；不固定一个只适配理想屏幕的网页高度。隐藏与展开共用同一个组合位置，不能写成两个条件分支分别创建 WebView。

标题优先级沿用当前候选名称、缓存标题、入口名称；不显示封面不等于停止为列表补全链接预览。摘要仍通过已有缓存读取路径刷新，不新增封面图片请求。

### 会话、计时与生命周期

- 已将 `ProbeState` 从 `VideoExtractVm.kt` 移到 video package 的 `VideoProbeState.kt`；运行态为 `Probing(sessionId, mode)`，模式使用具名 `Automatic` / `Interactive`，避免 `HiddenProbing` 名称与可见网页矛盾。`Failed` 携带可显示的失败分类，成功态继续保留候选；不把 WebView 放入 ViewModel。
- `VideoProbeSession` 负责模式切换、超时有效性、候选接受与重试。展开/收起不改变 session；每次进入自动模式更新计时版本，超时回调同时校验 session、模式和计时版本，防止“展开后又收起”时旧倒计时误杀本轮。
- 自动模式仍十秒未命中即失败并释放。进入交互模式取消自动倒计时，不在用户播放、登录或验证过程中强制关闭；交互满三十秒仍未命中，仅提示“尚未识别到视频，可尝试播放或重新提取”，提示每段交互只出现一次，不自动刷新网页。
- 交互模式允许页面前台持续等待，代价是网页可能持续占用网络/CPU；收起后恢复有界自动探测，成功、返回离开、页面 STOP 或组合移除立即关闭会话并释放网页。继续以生命周期决定能否工作，不能只依赖显隐按钮。
- 页面暂停时保留轻量显隐偏好，释放正在运行的 WebView；恢复未完成任务时以新 session 加载，并提示网页已重新加载。旋转按同一规则处理，不承诺恢复播放器/表单现场；进程重建默认回自动模式。已成功或已失败的任务恢复时不自动重试。
- 新文档开始取消旧预览补全并清理旧文档诊断；候选接受、暂停、失败和销毁均关闭旧回调。旁路预览继续保留 1.5 秒预算，不阻塞提取或重试。

### 错误提示与诊断

共享 WebView 通过具名加载事件回传开始、进度、完成、主文档错误及外部协议拦截事实；契约与失效规则由 [共享媒体 UI 主方案](shared_media_ui_plan.md) 统一维护。视频侧将事件映射为资源文案，不能把网页完成加载当作视频提取成功。

- 显示“网页加载中（百分比）”“网页已加载，等待视频请求”“网页加载失败”“自动提取超时，未识别到视频”等明确阶段；进度只代表网页加载，不代表提取成功率。
- 网络错误区分已获得的网络/连接/超时等错误分类；主文档 HTTP 错误可显示状态码。图片、广告、统计等子资源错误不直接判定整次提取失败。
- 自动模式遇到明确主文档错误可提前失败并释放；交互模式保留可见错误页面和错误提示，停止显示无限加载，允许用户查看、站内操作或显式重新提取。此时仍是可恢复的交互会话，不进入一个又继续接收候选的终态；新主文档成功加载后清除旧错误。
- 登录页、验证页和“必须打开 App”的页面主要靠用户直接看到内容判断；没有确切事件时，不自动猜测“需要登录”“网站不支持”等原因。外部协议被拦截时可以提示“网页尝试打开外部应用，当前仍停留在提取页”。
- SSL 错误保持取消策略，不为了查看或提取而放行；网页原始错误文本、HTML 和控制台输出不直接拼入应用错误提示。

### 组件与文件职责

| 文件 | 最终职责 |
| --- | --- |
| `app/src/main/java/com/cla/clip/master/ui/page/video/VideoExtractPage.kt` | 无封面摘要、可见网页操作接线、稳定单一宿主与页面生命周期编排 |
| `app/src/main/java/com/cla/clip/master/ui/page/video/VideoProbeState.kt`（新增） | 独立定义提取阶段、显隐模式、失败分类和轻量诊断状态，不含平台对象 |
| `app/src/main/java/com/cla/clip/master/ui/page/video/VideoProbeSession.kt` | 扩展现有状态归约、计时版本、交互模式与重试/暂停规则 |
| `app/src/main/java/com/cla/clip/master/ui/page/video/VideoExtractVm.kt` | 继续连接会话和下载提交，迁出状态定义；不增加 WebView 所有权 |
| `app/src/main/java/com/cla/clip/master/ui/page/video/VideoProbeWebViewLayer.kt` | 透传显隐/触摸和加载事件，保留视频请求判断与旁路补全 |
| `app/src/main/java/com/cla/clip/master/ui/page/video/VideoExtractStatusContent.kt` | 复用行内状态，接入视频语义的状态文案及重试/查看动作 |
| `app/src/main/java/com/cla/clip/master/ui/page/video/VideoProbeMessageMapper.kt`（新增） | 纯映射诊断状态到字符串资源，避免入口堆积错误分类分支 |
| `app/src/main/java/com/cla/clip/master/ui/widget/ProbeWebView.kt`、`ProbeLoadSession.kt` | 支持动态触摸配置、安全加载事件和已释放/旧文档过滤；复用单一 load/release 入口 |
| `app/src/main/java/com/cla/clip/master/ui/widget/ProbeWebViewEvent.kt`（新增） | 共享加载事件契约，不能携带 Cookie、完整 URL、HTML 或候选业务对象 |
| `app/src/main/java/com/cla/clip/master/ui/widget/ProbeWebViewClient.kt`、`ProbeWebViewOwner.kt`（新增） | 分别适配原生回调与拥有原生资源；STOP 和组合离场共用幂等释放，避免共享入口持续膨胀 |
| `app/src/main/java/com/cla/clip/master/ui/page/video/VideoTaskAdPolicy.kt` | 依据实际展示内容判断资格；交互查看期间不请求、不呈现广告 |
| `app/src/main/res/values/strings.xml` | 新增本页查看/收起/重试提示及错误文案，带变量文案使用占位符 |

不新增万能网页容器或第二套浏览器配置。共享控件只接收状态、modifier 和回调；视频专用适配与状态文案留在 video package，是因为它们绑定视频提取会话/候选语义。新增或修改的类型、方法、变量、回调、协程及关键分支均需补齐简体中文注释。

广告接入细节：取消封面后，缓存里有图片不再构成提取页广告资格，只考虑实际显示的描述或有效候选。展开期间应从布局中移除广告呈现并关闭请求门，不能仅设置 `canReveal = false` 或 `suppressed` 后假定已显示的广告会消失；收起后按既有资格重新接入。共享广告生命周期规则仍以 [广告主方案](ad_module_plan.md) 为准，不修改 SDK 适配器。

### 实施顺序与测试验证

已按 `$repo-unit-test-gate` 对状态、失败契约和派生广告资格执行测试先行，具体结果见本节顶部。去封面/三行标题属于展示接线，已用完整页面仪器测试及真机截图验收，不为样式本身造纯逻辑测试。以下保留本轮实施顺序和验收清单。

1. 先更新 `app/src/test/java/com/cla/clip/master/ui/page/video/VideoProbeSessionTest.kt`：展开不换 session；交互超过十秒不失败；收起新预算只接受最新计时版本；隐藏失败后查看启动可见新轮次；成功/失败/暂停后旧候选被拒绝；恢复保留模式但换轮次。用可控虚拟时间/显式事件，不等待真实十秒。
2. 先补 `VideoProbeMessageMapperTest.kt` 及共享 `ProbeLoadSessionTest.kt` 回归：主文档错误与超时有不同文案；子资源错误不终结提取；旧文档/已释放实例不更新诊断。扩展 `ui/widget/media/MediaTaskAdPolicyTest.kt`：仅有被隐藏的封面没有资格，交互模式无广告，成功候选仍使用原广告位。
3. 先实现共享 WebView 的动态触摸与加载事件，再接视频会话和错误映射；图片调用方沿用原默认行为。然后完成无封面摘要与展开布局，避免同时改识别算法。
4. 增补 `app/src/androidTest/java/com/cla/clip/master/ui/widget/ProbeWebViewTest.kt` 与现有 `ProbeLifecycleEffectTest.kt`：同一实例展开收起不重载、触摸拦截能实时解除/恢复、主文档与子资源错误分流、STOP 释放、恢复新建。测试页使用合成内容，禁止依赖真实登录态。
5. 运行 `.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain` 和 `git diff --check`；首次回归要确认先失败再通过。真实 WebView 用例默认在隔离设备运行；本次按用户明确授权在已连接真机定向执行，使用合成数据、不运行清库或全量设备测试。
6. 人工验收：无封面且长标题不超过三行；默认隐藏；加载中展开不刷新；手动点击播放能继续识别；失败后查看明确重试；展开时没有遮罩/广告挡网页；收起不残留焦点/键盘；后台/旋转/返回释放正确；小屏、大字体、横竖屏、慢网、HTTP 错误、登录/验证页及图片提取回归。

### 日志与诊断计划

| 级别 | 触发节点 | 允许字段与限频 |
| --- | --- | --- |
| INFO | 用户展开、收起、显式重试；有效候选接受 | `sessionId`、`mode`、`operation`、`durationMs`；每次有效动作一次 |
| DEBUG | 会话开始、主文档开始/完成、暂停/恢复、资源释放 | `sessionId`、`documentId`、`phase`、`reasonCode`、`durationMs`；按阶段变化记录 |
| WARN | 自动超时、主文档网络/HTTP/SSL 错误、外部协议拦截 | `sessionId`、`documentId`、`reasonCode`、数值错误码/HTTP 状态；同文档同原因去重 |

沿用日志封装，正文用简体中文；所有阶段携带同一 session，文档导航另加递增 documentId。加载百分比只更新 UI，不逐回调打印；子资源失败不逐条记录。禁止打印完整 URL/查询参数、网页标题/正文、输入内容、Cookie、Token、请求头/响应体、候选对象、HTML 和原始异常 message。代码已接入低频生命周期、用户动作、主文档与失败日志；本次合成网页真机日志已核对，见顶部验收记录。

### 数据、取舍与开放问题

- 2026-11-05 用户已选择仅做错误页提前失败；自动与交互模式都结束该轮并释放网页，新增失败统一提示“视频提取失败”，最终设计与验证以顶部主章节为准，替代此前草案的具体原因文案与模式待定项。共享原生错误仍只覆盖网络、HTTP、SSL；视频领域新增内容判定，处理 HTTP 200 的明确业务错误。
- 详情接口查询兜底延期：需要独立设计作品精确匹配、空详情/过滤原因、资源 URI 缺失及请求预算。网页错误只能说明当前网页提取无法继续，不代表所有下载路径不可用；对照视频此前可通过独立接口完整下载这一证据仍成立。
- 显隐模式、计时版本和诊断只存在内存，不新增设置、数据库字段、备份内容或导航参数；不能恢复上一轮网页现场，失败后查看需要重新加载。已有列表预览补全和下载数据契约不变。
- 新状态/事件仅由编译期 Kotlin 调用，不新增 JS bridge、反射、序列化或 keep 规则；本轮无需 release/R8。若实施引入这些机制，重新评估并补充验证。
- 本次不调整候选识别算法，仍可能先命中分片、广告视频或预加载资源；看到网页和诊断后再依据证据决定下一轮修复。
- 交互模式采用三十秒提示而不强制超时；持续网页活动仅限页面前台，退出/后台释放。可见页面诊断采用内联展开，不新增浏览历史、日志导出、多窗口或全屏播放器。
- 合成网页及公开 MP4 网页的提取、下载、系统播放真机验收已完成；现有 B 站短链接停留在要求打开 App 的内容页，用户指定的抖音记录在自动/交互重试均未提取成功。同一抖音链接在真机 Chrome 与三星浏览器也显示相同的站点错误、无法播放，已确认失败并非仅发生于本应用 WebView；后续公开详情请求返回目标作品过滤原因 `status_audit_self_see`，没有媒体详情。另一条既有抖音记录可通过详情接口取得资源 ID，固定模板在 Windows 主机完整下载 `6880899` 字节 MP4，约 53 秒的音视频完整解码通过，但该对照作品在同一手机 Chrome 分享页也出现同样错误。因此不能通过该错误文案判断资源不可用，更不能推断官方已全面关闭网页播放。详情解析尚未接入应用，未完成对照视频在应用内的提取/下载/播放验收，不能记录“提取失败已修复”或“抖音一律不支持 WebView”。详情解析兜底如进入实现，必须独立覆盖作品精确匹配、空详情/过滤原因、资源 URI 缺失及下载失败契约。

## 下载记录移除的接入边界（2026-10-26，已完成）

保留视频探测、下载详情、当前任务重试和冷启动取消；删除仅供历史页删除记录使用的单任务取消入口，不新增替代取消 UI。完整范围与验证见 [下载记录主方案](download_history_plan.md#下载媒体记录功能移除方案2026-10-26)，备份契约以 [备份主方案](webdav_backup_plan.md#下载媒体任务退出备份恢复方案2026-10-26) 为准。无新增运行时日志，保留流程继续复用现有诊断。

## 2026-10-16 修复执行记录（已完成）

用户已确认按审查顺序修复。本节作为本轮执行记录，覆盖下方审查，不另建分叉方案。使用现有工作区的新分支，保留既有版本和应用更新改动，不自动提交；测试仅在本机运行或编译，禁止对现用手机执行 connected 测试。

- [x] 1. 先补会话和加载回归：失败重试、暂停恢复、旧回调拒绝、重定向不重载、重复页面完成回调忽略。新建共享 `ProbeLoadSession` 与视频 `VideoProbeSession`，页面只编排 WebView 和状态。
- [x] 2. WebView 每个会话只加载一次；共享组件拥有释放职责。页面不可见时暂停会话并释放 WebView，返回后新建会话；成功/失败后不继续拦截。预览补全改为有界旁路，不能阻塞候选接受。
- [x] 3. 下载观察与启动分离，以 `VideoDownloadStartPolicy` 保护运行中下载/合并进度；保留显式重试。视频提交防重，并保留失败页媒体摘要。
- [x] 4. 图片会话、进度和页面连续性按图片主方案实施；清除两页不再调用的旧流程。
- [x] 5. 运行 app 单测、Kotlin 编译和仪器测试源码编译，最后独立评审；设备实测单列未验证边界。

测试先行：使用合成 URL 和纯会话/进度输入；先观察回归失败再改生产逻辑。日志仅记录 session/taskId、阶段、数量和异常类型；不新增 URL、Cookie 或用户正文输出。新增状态只存在内存，不改变数据库、备份协议或 keep 契约，因此本轮不要求 R8；图片下载/发布阶段由现有 item 状态推导。

执行裁定：本次已获用户明确实施授权，继续在当前会话实施，不重复请求设计批准；保留当前审查文档和用户未提交改动，使用独立分支而不迁移工作区。编译使用已有隔离输出脚本；Gradle 缓存访问需要沙箱外权限，已按工具流程申请。

独立复核补充：完成回调去重限定为每次主文档导航，新文档开始取消旧扫描/预览；不能把整个 WebView 的首次中转页完成当作最终文档。下载页同时读取当前任务的 WorkManager 状态，冷启动取消后遗留的 downloading/merging 显示中断提示，仅用户明确重试才重新入队；保留 App 当前不自动恢复旧视频下载的约定。新增平台观察协作者，不修改数据库/备份协议。

### 当前日志与验证边界

- 保留低频 DEBUG 任务创建/页面进入日志（taskId、sessionId、状态）与提交异常类型；识别仅记录成功事实。共享预览补写不打印预览对象，视频候选不打印 URL/Cookie。没有为高频回调逐条新增日志，避免隐私和性能噪声。
- JVM 回归验证会话重试/过期回调、重定向入口不重复加载、每文档完成去重、运行任务不归零、冷启动中断后显式重试及安全错误资源映射。Compose 生命周期测试源码已加入，运行仅限隔离设备。
- 不新增 Room 字段、协议、序列化模型或 keep 规则；备份内容无变化，无需迁移。本轮未做 release/R8；风险集中在真实 WebView/Coil 设备行为，编译和纯逻辑测试不能替代设备体验验收。

独立代码复核：首次发现的文档导航去重和冷启动中断两个 P2 已修复；定向复核确认无新增阻塞问题。

### 本机验证结果（2026-10-16）

- 命令：`.\gradlew.bat :app:testDebugUnitTest :base:general:testDebugUnitTest :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --init-script build\review-build-isolation.gradle --console=plain`，结果 BUILD SUCCESSFUL。
- app 324 条、base:general 109 条 JVM 测试通过，共 433 条；本轮新增 20 条 JVM 回归及 1 条 Compose 生命周期仪器测试。仪器测试只编译，未安装或执行设备测试。
- 重试、入口重载、阶段进度、读取错误、HTTP 取消、文档导航和冷启动中断均记录过真实断言失败后通过；没有改测试期待来掩盖原问题。未把 JVM/编译结果当作 WebView、滚动及图片缓存的实机体验验证。

## 2026-10-16 加载与显示流程审查

审查归档：以下保留修复前的源码证据与问题清单；当前行为以本文顶部修复执行记录为准。审查覆盖详情入口、视频识别页、独立下载页、共享探测 WebView、预览补全、Room 观察和 Worker 入队。没有运行设备，不把源码风险等同于已测得的卡顿、闪屏或网络请求次数。下文历史验收条目表示目标，不代表本轮已验证通过。

### 修复前的实际流程

| 阶段 | 加载与状态来源 | 前台显示与退出边界 |
| --- | --- | --- |
| 进入识别页 | 一次读取已有链接预览；`sessionId` effect 将 `Idle` 改为 `HiddenProbing`；`ProbeWebView.update` 和页面 effect 都有 `loadUrl` 入口 | 标题、已有媒体摘要、行内加载提示；无缓存时没有新封面 |
| 网页加载与识别 | `onPageFinished` 异步补写预览并尝试播放；请求拦截发现候选后再次等待预览补写，再回调成功 | 当前只接收首个完成成功回调的候选；成功后销毁 WebView |
| 超时 | 进入 `HiddenProbing` 后计时 10 秒，直接改为 `Failed` | 显示可点击错误行；WebView 仍挂载，没有随失败停止 |
| 确认下载 | 权限通过后创建新的任务记录，SharedFlow 通知导航到 `VideoDownloadRoute(taskId)` | 无独立“正在创建任务”状态；按钮仍可再次点击 |
| 进入下载页 | `startDownload` 对所有非成功任务先写入 0% 下载态，再以 KEEP 入队；随后启动同一任务的 Room 观察 | 准备、下载百分比、合并百分比、成功或失败；Worker 独立于页面运行 |
| 下载结束 | Worker 回写 Room | 成功行点击播放；失败行点击重试；失败原因虽进入 UI state，但组件没有展示 |

`downloadState` 和 `taskData` 来源于同一个任务观察，不是两次 Room 查询；这部分已有复用不应当作冗余删除。视频下载与合并保留不同阶段也有实际业务意义。

### 确定的问题与影响

1. **P1：识别失败后的重试被挡住。** `VideoExtractPage` 的重试仅执行 `sessionId += 1`，而新一轮 effect 遇到 `Failed` 就提前返回，无法回到 `HiddenProbing`。失败状态还保留 WebView，候选回调只排除 `Success`，因此可能在错误提示之后又被迟到候选改成成功。应让重试显式启动新会话，失败封闭旧会话，并用会话身份校验候选回调。
2. **P2：查看运行中任务会改写进度。** `VideoDownloadVm.startDownload` 只排除成功任务，查看正在下载或合并的任务也会执行 `updateProgress(taskId, 0)`；Worker 使用 KEEP，并不会因这次查看重新开始。UI 因而可能从合并退到下载 0%，直到下一次 Worker 回写。观察任务、首次启动和显式重试需要分开。
3. **P2：识别结果被预览补全阻塞，当前页却不观察补写结果。** `SharedVideoProbeWebViewLayer` 在候选成功回调之前等待 `onSaveLinkPreview`，多个命中请求又可能分别启动相同补写。`observeCachedPreview` 实际只是 `flow { emit(loadLinkPreview(...)) }`，本次收集期间不会收到新增封面。辅助缓存工作进入识别关键路径，却不能保证当前摘要刷新。应先固定候选与页面阶段，将有界的预览补全独立编排，并合并重复补写。
4. **P2：创建任务没有提交中状态或互斥。** `Success` 在权限通过时立即清空 `pendingCandidate`，`startDownloadAndGo` 每次调用都会创建新记录。快速重复点击可产生多个任务和导航事件；应在当前提交完成前禁用动作，并对创建入口防重。
5. **P2：页面可见性没有约束探测。** 现有清理依赖 `DisposableEffect.onDispose`，未监听前后台生命周期。Activity 暂停但组合仍存在时，计时、页面作用域协程和 WebView 没有显式暂停边界。Room 的生命周期感知订阅不覆盖这些任务。正式下载 Worker 应继续后台运行；网页探测需要独立的暂停/取消及恢复规则。
6. **P2：错误信息与内容上下文不足。** `VIdeoDownloadPage.Failed` 接收 `errorMsg` 却只展示统一文案；失败时媒体摘要整体消失。用户无法区分网络失败、权限失败、任务缺失，也容易感觉整个内容块突然消失。后续应保留任务摘要并提供经过脱敏和资源化的可行动原因。

### 共享加载风险与待实机验证项

- **共享 WebView 的重载判据不合理。** `ProbeWebView.update` 比较 `webView.url` 与原始 `targetUrl`，不是比较上一次请求加载的目标。短链重定向或站内导航后，两者正常情况下就不相等；后续 AndroidView update 若被触发，将再次加载入口。两个页面还各自保留显式 `loadUrl` 路径。应由一个会话/目标变更入口负责加载，不把合法重定向当作新请求。需要用合成短链重定向页验证加载次数，当前没有测定实际发生频率。
- `onPageFinished` 没有按主文档/会话完成去重，视频可能重复补预览和自动播放；图片会取消再启动滚动扫描。回调频率和对具体站点的影响待实机记录。
- `WebViewLinkPreviewExtractor.evaluateJavascriptAwait` 没有独立超时，返回为空与永远不回调是不同情况。预览补全不应无限等待，也不应在取消被捕获后继续驱动业务完成。
- 加载/成功区域和广告可能随阶段改变高度，但代码已实现滚动/触摸时延迟广告布局变化。没有录屏、帧时序或布局测量前，不把所有跳动归因于广告。

### 冗余与收敛方向

- 私有旧版 `VideoProbeWebViewLayer` 没有调用方，还保留第二套 WebView 配置与 scheme 判断；应在建立共享探测回归后删除对照实现。
- `NeedUserPlay`、25 秒计时、隐藏层第二个标题栏及相关注释属于未启用流程残留。应集中标明未支持状态，避免维护者误以为自动超时后真的可以手动播放。
- 后续优先复用 `ProbeWebView`、`MediaStateContent`、`MediaSourceSummary` 和既有 Repository/Worker；状态转移与平台生命周期分开治理，不把图片候选筛选和视频识别强行合成万能页面。
- 修复顺序建议：失败重试与迟到回调 → 共享加载/生命周期 → 查看任务不改进度 → 提交防重、预览补全 → 页面内容和错误展示。以上是修复前审查建议，执行结果见顶部记录。

### 日志与诊断计划

以下为修复前审查提出的诊断建议；当前日志实施见顶部记录。后续修复应以低频 DEBUG 记录会话开始/暂停/停止、加载原因和次数、预览补全耗时；INFO 记录候选接受、任务创建和用户重试；WARN 记录超时、过期回调丢弃和不可恢复失败。字段限于 sessionId、taskId、阶段、计数、耗时、reasonCode 与布尔值，网络/DOM 高频回调只汇总。禁止输出网页正文、完整 URL、查询参数、Cookie、Token 和候选对象。当前仍有 URL/预览对象日志，实施诊断前应同步收敛，不能直接收集或分享原始敏感日志。

### 验证与后续测试边界

- 本轮已沿调用链核对并搜索死代码引用；未修改 Kotlin，未新增测试，未运行 Gradle、R8 或设备测试，不能声称上述问题已修复。
- 现有 `MediaTaskAdPolicyTest` 覆盖广告资格，不覆盖失败重试、候选过期、任务启动幂等、查看进度不回退或预览补写状态。
- 后续按单元测试门禁，先补状态/事件回归测试：失败后重试进入新 session；旧候选不能覆写失败/新会话；查看下载和合并任务不重置进度；重复提交只创建一次；补预览超时不挡住识别结果。
- Android/WebView 验证覆盖重定向、重复完成回调、切后台再返回、慢网和连续重试；使用合成测试页，不依赖真实用户链接。届时运行 `./gradlew.bat :app:testDebugUnitTest`、`./gradlew.bat :app:compileDebugKotlin` 和受影响的仪器测试。

## 当前状态

视频提取入口位于剪贴板详情页。页面不显示封面，标题最多三行，默认使用隐藏且屏蔽交互的 `ProbeWebView` 自动探测；点击“查看提取过程”可展开同一实例并操作网页，失败后“查看网页并重试”启动可见新轮次。完整交互和验证边界见顶部 2026-11-04 实施记录。正常主文档完成后仍尝试 JavaScript 静音播放 `video` 标签，并通过 `shouldInterceptRequest` 与既有扩展名、MIME、关键词和抖音接口规则捕获候选。

当前实现以自动识别为主：如果在隐藏探测超时时间内捕获到候选视频地址，页面会展示识别成功状态；用户点击“去下载”后，先申请必要的存储权限，再创建视频下载任务并跳转到视频下载页。创建入口通过 WorkManager 启动 `DownloadVideoWorker`，下载页只做观察或显式重试，支持普通视频直链和 M3U8/HLS 下载，下载完成后将视频保存到系统媒体库目录并允许用户点击播放。

本次新增链接预览补全策略：剪贴板保存时仍使用 `LinkMetaParser` 做快速 OkHttp/Jsoup 解析；遇到知乎 403、登录态或反爬限制时不在首轮请求中强行绕过。用户后续进入视频提取页时，隐藏 `WebView` 会以浏览器上下文加载网页，页面加载完成后从 DOM meta、JSON-LD、`video poster` 和图片候选中提取标题、描述、站点名和预览图，并补写到 `link_previews` 表。用户返回列表页后，原本只有域名兜底的链接卡片可以显示 WebView 阶段补齐的预览图。

`VideoExtractPage.kt` 只做页面编排；`VideoProbeSession` 保存会话、计时和诊断；`VideoExtractStatusContent.kt` 提供查看/收起/重试及下载权限适配。行内状态复用 `MediaStateContent`，共享网页事件与资源边界以 `docs/shared_media_ui_plan.md` 为准；`VideoProbeWebViewLayer.kt` 保留视频请求识别与共享层适配。

视频提取页与下载页均复用 `SecondaryPageScaffold` 和 `PageBackground`；隐藏 WebView 只在探测阶段挂载，不再绘制第二套标题栏。

## 视频探测层隐藏调整（2026-09-30 历史范围，生命周期已由 2026-10-16 修复更新）（2026-09-30）

状态：已完成（探测层配置与中文注释已调整，Kotlin 编译通过；设备显隐与识别回归仍待验收）。

### 需求与现状

- 目标：视频地址提取期间隐藏网页内容，用户只看到现有标题栏、媒体摘要、提取进度和操作状态，行为与图片提取页一致。
- 图片页已通过 `ProbeWebView(modifier = Modifier.fillMaxSize().alpha(0f), consumeUserTouch = true)` 保留真实探测视口，同时隐藏网页并屏蔽用户触摸。
- 调整前，视频页实际调用 `SharedVideoProbeWebViewLayer`，其内部 `ProbeWebView` 使用默认可见 modifier，且没有启用触摸屏蔽；页面只依靠前景遮罩覆盖。现已按下述实现补齐两个配置。
- 以上是静态源码确认的差异；网页穿透遮罩的具体设备渲染原因尚未复现，不能据此断言 AndroidView 层级或视频渲染机制是唯一原因。

### 最终实现与影响范围

1. 只调整 `VideoProbeWebViewLayer.kt` 中实际使用的 `SharedVideoProbeWebViewLayer`：调用 `ProbeWebView` 时显式传入 `Modifier.fillMaxSize().alpha(0f)` 和 `consumeUserTouch = true`，增加 `alpha` import。
2. 补齐该 Composable 的简体中文说明和新增配置附近的注释，明确透明仅改变展示，完整视口用于网页加载和播放触发，用户触摸不参与自动探测。
3. 复用共享组件既有的 modifier 与触摸屏蔽接口，不新增隐藏策略类、状态字段或公共 Boolean 参数，也不修改 `ProbeWebView` 的默认行为。
4. 保留 `VideoExtractPage` 现有 Box 叠放、前景背景、触摸遮罩、状态展示和广告接入；本轮不重构标题栏或提取页骨架，也不调整未被调用的旧版 `VideoProbeWebViewLayer`。
5. 保留网页加载、自动播放 JS、请求拦截、Cookie/Referer/User-Agent 获取和链接预览补全；保持 10 秒自动探测超时、失败重试、识别成功清理 WebView 和退出清理的现有行为。

生产代码实际只修改 `app/src/main/java/com/cla/clip/master/ui/page/video/VideoProbeWebViewLayer.kt`。方案更新本主文档；图片页及共享媒体/广告契约未变化，不重复修改其他方案文档。

### 显示与资源边界

| 场景 | 目标表现 | 探测行为 |
| --- | --- | --- |
| 首次进入、Idle 到 HiddenProbing | 网页始终透明，只展示前景页面 | 保持现有加载、JS 和网络拦截 |
| 自动探测超时、Failed | 只显示失败重试内容，不露出网页 | 本轮不调整既有停止/清理时机 |
| 点击重试 | 新一轮探测保持透明，不闪现网页 | 使用现有 session 和重新加载逻辑 |
| Success | 显示候选和下载入口 | 保持现有成功清理 WebView |
| 返回离开页面 | 离开提取页 | 保持 DisposableEffect 退出清理 |

透明不等于停止加载，也不等于暂停媒体；本轮只处理显示和触摸。保留完整尺寸，避免删除 AndroidView、设置零尺寸或 GONE 后改变网页视口及资源请求时机。`NeedUserPlay` 当前正式流程未启用，本次不启用；以后若需要可见网页辅助播放，必须另行设计显隐和触摸切换，不能直接复用始终透明的探测层。

### 实施与测试验证

- 实施前再次确认实际调用链及工作区改动；按上述单文件范围调整配置和中文注释，完成后同步本节状态与实际结果。
- 单元测试门禁判断：本方案只复用既有 Compose modifier 与 Android WebView 触摸配置，没有新增纯规则或状态机；JVM 单测不能可靠证明真实 WebView 隐藏、播放和资源拦截，暂不新增单测或为此抽出纯策略。风险通过设备回归覆盖；以后若抽出显隐 predicate 或启用手动播放状态机，必须先补失败测试。
- 实施后运行 `./gradlew.bat :app:compileDebugKotlin` 和 `git diff --check`。
- 设备验证首次进入、网络慢加载、超时失败、连续重试与返回退出，确认网页和播放器内容无闪现，前台按钮正常，点击/滑动空白处不影响后台网页。
- 使用修改前可成功识别的视频网页验证仍能捕获候选，确认下载入口和链接预览补全不回归；同时检查小屏、横竖屏、深色模式及有/无缓存摘要的显示。
- 编译通过只证明源码可编译，不能替代 WebView 设备验收；如透明后识别率变化或仍出现视频画面，先定位实际渲染/播放路径，再决定后续方案。
- 本轮不改变下载数据、数据库、备份覆盖、外部协议或 keep 规则，暂不要求新增 release/R8 构建。

实际验证（2026-09-30）：常规编译及放宽沙箱后的同命令重试均因 Kotlin daemon 临时文件和 Gradle 缓存 `appcompat-1.7.1-api.jar` 的访问权限失败；随后运行 `./gradlew.bat :app:compileDebugKotlin --no-daemon "-Pkotlin.compiler.execution.strategy=in-process" --console=plain`，结果为 `BUILD SUCCESSFUL`。未修改项目 Gradle 配置。构建仍报告 `databaseEnabled` 弃用等警告；`git diff --check` 通过。未新增或运行单元测试，原因见上述门禁判断；未安装或操作设备，网页无闪现、触摸屏蔽与识别成功率尚未运行时验证。

### 日志与诊断计划

本轮不新增运行时日志：只调整展示和触摸配置，不引入新的网络、文件、数据库副作用或状态分支，高频显隐日志会增加噪声。定位时沿用现有探测开始、超时、成功与清理节点，仅关注 session、阶段、候选是否命中和耗时等低敏信息；禁止为验证本方案新增完整 URL 查询串、Cookie、Token、候选对象、HTML 或请求体输出。实际网页显示、触摸和视频渲染效果依靠设备观察验证。

### 后续待办

- 在设备验证后记录网页无闪现、触摸屏蔽和视频识别回归结果。
- 页面骨架统一、手动播放辅助、失败态资源停止与更广泛的生命周期治理继续作为独立需求，不扩大本次隐藏调整。

## 目标

- 从网页链接中自动识别可下载的视频资源地址。
- 尽量复用网页请求上下文，例如 Referer、User-Agent 和 Cookie，降低反盗链导致的下载失败概率。
- 视频地址识别成功后创建可观察的下载任务，并跳转到下载页展示进度。
- 支持普通视频文件和 M3U8/HLS 分片视频下载。
- 下载失败时清理半成品，避免用户在相册或文件管理器中看到损坏文件。
- 首轮链接预览失败不阻塞剪贴保存；视频提取 WebView 成功加载网页后补全链接预览缓存。

## 范围

- 涉及剪贴板详情页的视频入口、视频提取页、视频下载页、下载任务 Repository、Room 下载任务表、WorkManager 下载 Worker、文件保存工具和字符串资源。
- 当前只提取第一个被识别到的视频候选地址，不提供多候选列表、清晰度选择、字幕选择或音轨选择。
- 已启用同实例可见网页辅助；自动识别失败后提供普通重试与可见重试。
- 当前不实现断点续传；重试会重新拉取并覆盖本任务状态。
- 链接预览补全复用现有 `link_previews` 表和剪贴记录的 `link` 关联，不新增数据库字段或迁移。

## 用户体验

1. 用户在详情页看到网页链接提示，并点击“提取视频”。
2. 视频提取页显示无封面摘要和网页加载状态；可通过“查看提取过程”展开并操作网页，默认隐藏自动提取。
3. WebView 加载完成后自动尝试播放页面中的视频元素，以触发真实媒体请求。
4. 如果拦截到疑似视频请求，页面显示“视频地址提取成功”和“去下载”按钮。
5. 用户点击“去下载”后进入权限检查；权限满足后创建下载任务并跳转视频下载页。
6. 视频下载页展示准备下载、下载中、合并中、下载完成或下载失败状态。
7. 下载完成后用户点击成功状态可调用系统播放器打开视频。
8. 从下载页返回时会跳过视频提取页，回到视频提取入口之前的页面；如果从通知进入下载页，则执行普通返回。

## 最终实现

- `DetailPage` 在剪贴板内容包含链接时展示“提取视频”按钮，并通过 `VideoExtractRoute(url, name)` 进入提取页。
- `VideoExtractPage` 使用 `ProbeState` 管理空闲、自动/交互探测、成功及带原因的失败；可见网页辅助流程以顶部 2026-11-04 实施记录为准。
- `ProbeWebView` 统一承载网页探测配置：启用 JavaScript、DOM Storage、Cookie、第三方 Cookie、混合内容和移动端 User-Agent，并拦截外部 App scheme，避免网页跳出当前流程。
- 页面加载完成且进度达到完成状态后，`AUTO_PLAY_JS` 会尝试静音播放页面内所有 `video` 元素，提升捕获真实视频请求的概率。
- `isLikelyVideoRequest` 按抖音播放接口、常见视频扩展名、视频关键词、请求头 MIME 判断候选视频地址。
- `VideoCandidate` 记录视频地址、Referer、User-Agent、Cookie 和文件名，作为提取页到下载任务之间的核心数据结构。
- `VideoCandidate` 只作为提取页内存态和创建下载任务前的轻量候选对象使用，不承担导航参数、Parcelable 或 JSON 序列化契约。
- `VideoExtractVm.startDownloadAndGo` 防重创建新任务并入队，成功身份保存在 VM；页面消费后导航到 `VideoDownloadRoute`，失败保留候选允许再试。
- `DownloadRepository` 每次下载都会创建新的 `download_tasks` 记录；`video_url` 只保留普通索引，允许同一地址多次下载并分别指向各自的本地媒体文件。
- `VideoDownloadPage` 观察同一任务的 Room 与 WorkManager 状态；查看不重置进度。失败或中断且已无活动 Worker 时，明确重试才从头重新入队；成功任务只观察和播放。
- `DownloadVideoWorker` 使用前台通知下载视频，保存前先登记 MediaStore 输出 URI，失败时清理半成品，成功时标记媒体文件可见并发送下载结果通知。
- 下载直链时会校验响应类型和响应头内容，避免把 JSON、HTML 或错误页误保存成视频。
- 下载 M3U8 时会解析 master playlist 并选择最高带宽子流，随后解析分片列表和 AES-128 KEY，并发下载分片后按顺序合并成单个视频文件。
- 抖音 `/playwm/` 地址会先尝试替换为 `/play/` 无水印地址，失败后再回退到原地址。
- 每次主文档完成后旁路补写 `link_previews`，最多等待 1.5 秒；新文档、成功、失败、暂停均取消旧任务。候选先被接受，不等待预览。补写成功递增缓存版本以刷新当前摘要；提前完成识别时允许保留原摘要兜底。

## 状态流转

- `Idle`：页面初始状态，等待探测会话启动。
- `Probing(sessionId, Automatic)`：默认隐藏网页，十秒自动预算；切换交互取消该倒计时。
- `Probing(sessionId, Interactive)`：同一网页可操作，三十秒只提示；收起回自动模式并更新预算版本。
- `Success(candidate)`：已捕获视频候选地址，等待用户确认下载。
- `Failed(reason)`：自动超时或主文档加载失败，关闭旧回调并移除网页；可选择普通或可见重试。后台暂停回 Idle 并释放网页，恢复保留显示偏好但换 session；成功/失败不自动重试。

## 数据流

- 详情页读取剪贴板记录中的链接和标题，并跳转到 `VideoExtractRoute`。
- 视频提取页创建或复用 `ProbeWebView` 加载网页。
- WebView 请求被 `shouldInterceptRequest` 拦截，命中视频判断后构造 `VideoCandidate`。
- 提取成功后销毁 WebView，避免继续加载或占用资源。
- 用户确认下载后，ViewModel 将候选地址和请求上下文写入 `download_tasks`。
- 下载页通过 `observeTask(taskId)` 监听 Room 中的下载任务变化。
- Worker 根据任务中的 URL、Referer、User-Agent 和 Cookie 构造 OkHttp 请求。
- Worker 将下载进度、合并进度、成功或失败状态回写 Room，下载页随 Flow 更新 UI。
- 下载成功后，文件保存到媒体库，并通过系统媒体扫描或 MediaStore pending 状态更新对外可见。
- WebView 链接预览补全只更新 `link_previews`，列表页仍通过剪贴记录中的原始 `link` 读取关联预览；返回列表后分页数据重新加载即可显示补齐结果。

## 下载任务当前边界

历史页及其重试/删除/文件校验已移除，当前下载结果继续使用自身保存的 URI；每次新下载使用独立任务和输出身份。

## 涉及文件

- `app/src/main/java/com/cla/clip/master/ui/page/detail/DetailPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/video/VideoExtractPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/video/VideoExtractStatusContent.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/video/VideoProbeWebViewLayer.kt`
- `app/src/main/java/com/cla/clip/master/ui/widget/MediaStateContent.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/video/VideoExtractVm.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/video/VIdeoDownloadPage.kt`
- `app/src/main/java/com/cla/clip/master/ui/page/video/VideoDownloadVm.kt`
- `app/src/main/java/com/cla/clip/master/ui/widget/ProbeWebView.kt`
- `app/src/main/java/com/cla/clip/master/entity/VideoCandidate.kt`
- `app/src/main/java/com/cla/clip/master/work/DownloadVideoWorker.kt`
- `app/src/main/java/com/cla/clip/master/work/Download.kt`
- `app/src/main/java/com/cla/clip/master/utils/WebViewLinkPreviewExtractor.kt`
- `app/src/main/java/com/cla/clip/master/ui/navigation/AppNavigation.kt`
- `app/src/main/java/com/cla/clip/master/ui/navigation/Routes.kt`
- `base/general/src/main/java/com/cla/clip/base/general/repository/DownloadRepository.kt`
- `base/general/src/main/java/com/cla/clip/base/general/repository/ClipRepository.kt`
- `base/general/src/main/java/com/cla/clip/base/general/repository/ClipRepositoryImpl.kt`
- `base/general/src/main/java/com/cla/clip/base/general/dao/DownloadDao.kt`
- `base/general/src/main/java/com/cla/clip/base/general/utils/FileUtils.kt`
- `base/general/src/main/res/values/strings.xml`

## 测试验证

- 从详情页点击“提取视频”后能进入视频提取页。
- 普通 MP4/WebM/FLV 直链或网页触发的媒体请求可被识别，并进入成功状态。
- M3U8 地址可进入下载页，分片下载完成后进入合并中状态并最终保存为视频文件。
- 抖音 `/playwm/` 地址下载时优先尝试无水印 `/play/` 地址，失败后能回退原地址。
- Referer、User-Agent、Cookie 能从视频候选传入下载请求，反盗链站点尽量保持可下载。
- 自动识别超时后显示失败重试，点击重试会开启新的探测 session。
- 下载失败时任务状态变为失败，MediaStore pending 文件或旧文件路径会被清理。
- 下载成功后点击成功状态可唤起系统视频播放器；没有可用播放器时显示 Toast。
- 运行 `./gradlew :app:compileDebugKotlin` 验证编译。
- 如果后续重新启用候选对象跨路由或跨进程传递，需要重新评估 Parcelable、JSON 字段协议和 release R8 保留策略。
- 在知乎等首轮 Jsoup/OkHttp 返回 403 的页面中，复制链接后列表可先展示域名兜底；进入视频提取页并成功加载网页后返回列表，链接卡片可显示 WebView 阶段补齐后的标题或预览图。

## 已知取舍

- 当前只使用第一个命中的候选视频地址，命中 `.ts` 分片或低清晰度资源时可能提前结束探测。
- 自动播放受网页策略、登录态、脚本加载时机和 WebView 媒体策略影响，不能保证所有站点都能自动触发视频请求。
- 交互模式允许前台持续等待，代价是网页可能持续占用网络/CPU；收起后恢复十秒预算，后台直接释放。仍只捕获首个候选，不能保证所有站点识别成功。
- M3U8 下载会将分片临时写入 `cacheDir/m3u8/{taskId}`，完成或失败后清理；下载过程中仍可能占用较多缓存空间。
- 任务以 `video_url` 建普通索引，同一视频地址允许产生多条记录，确保每次新下载拥有独立任务和媒体输出身份。
- 当前不做断点续传，重新下载会从 0% 开始。
- WebView 补全预览是后置缓存更新，不改变视频候选识别和下载任务创建逻辑；如果网页没有暴露可用 meta、JSON-LD、poster 或图片候选，列表仍保持原兜底展示。

## 开放问题

- 手动播放辅助已实现，真机合成点击与键盘已验收；真实登录/验证及其他厂商 WebView 行为仍待验收，见顶部验证边界。
- 后续可以增加候选列表和质量筛选，避免第一个命中地址不是用户真正想下载的视频。
- 后续可以优化视频请求判断规则，降低 `.ts` 分片、广告视频、预加载片段或非主视频资源的误判。
- 后续可以为 M3U8 下载补充更完整的进度统计，例如区分分片下载、解密和合并阶段的总进度。
- 后续如果要支持大文件下载恢复，需要在任务表中记录断点信息，并调整 `VideoDownloadVm` 当前重置进度的逻辑。
- 当前任务成功后进入下载详情不会自动重新入队；当前失败/中断重试仍沿用已有状态策略。
- 已在用户授权的三星真机验证透明 WebView 触摸与候选识别；脚本导航、媒体播放和其他厂商差异仍需对应设备/站点覆盖。

## 变更记录

- 2026-11-06：视频下载失败通知改用已有本地化失败资源，不直接展示异常原文；持久化原因和页面已有分类映射保持不变。中英文回退约定见 [多语言主方案](multilingual_support_plan.md)。

- 2026-11-05：按用户要求提交并合入主分支；保留远端发布历史，将新增失败提示对齐 `host_` 资源规范并同步映射测试，避免重新引入主分支已修复的资源前缀问题。集成验证结果见顶部记录。

- 2026-11-05：按用户明确要求完成下载、合并、成功页面的安装与真机广告定位验收；新增可选设备用例，合成任务不写业务库，沿用真实广告资格与宿主。两轮设备测试通过，三个阶段广告底边均贴住 2960px 安全底边，关闭后零占位；补录截图与坐标证据、加强异常清理，明确实际滚动及完整下载链路未覆盖。

- 2026-11-05：完成视频下载、合并和成功结果的固定底栏接入，沿用共享边距、最小视口与手势区避让；11 项定向单测、Kotlin 编译、Debug 打包、独立审查与差异检查通过。明确未进行下载页实机验收、不新建真实下载任务，并保留其他任务改动。

- 2026-11-05：按用户追加要求将固定底部广告扩展到视频下载中、合并中和成功结果；核对三阶段共用下载页，记录共享底栏复用、Insets、生命周期与验证边界，开始实施。

- 2026-11-05：按已确认方案完成固定底部广告，复用共享媒体底栏并保持图片默认边距；真机发现三星零高导航栏仍有必需手势区，补本页 Insets 避让后截图验证通过。8 项定向 JVM、两项现有合成真机回归、编译打包及独立审查通过；记录未覆盖矩阵，不提交已有工作区改动。

- 2026-11-05：按用户要求新增视频提取页广告固定底部草案；确认现有广告与结果共用滚动容器，拟复用固定媒体底栏并保留内容优先、空间保护、资格与生命周期边界。仅更新方案，尚未实施或执行代码/设备验收。

- 2026-11-05：完成抖音明确错误页提前失败及统一提示；新增 6 条 JVM、6 条设备回归并完成真机验证，原错误样例两种模式约 2.53 / 2.15 秒结束。对照分享页本次显示正常播放器，保留原等待而未误判；记录环境变化与未验证下载边界，替换草案中的待选范围与具体失败原因文案。

- 2026-11-05：用户确认仅实施抖音明确错误页提前失败，新增失败统一提示“视频提取失败”，接口兜底后续再加；记录复用、领域拆分、两次快照确认、双模式终止、测试先行及隐私日志计划，进入实施。

- 2026-11-05：记录用户提出的抖音明确错误页提前失败讨论，核对现有原生加载错误与 HTTP 200 业务错误的差异；将最小网页提前失败与详情接口兜底列为待选范围，说明误判保护、交互模式边界及拟验证项，未修改代码或将草案标为已实施。

- 2026-11-04：按用户要求核查抖音网页播放现状，记录官方帮助/嵌入播放资料；补测已下载成功对照视频的手机 Chrome 分享页同样报错，明确网页错误与媒体可用性不可等同，保留精确页面匹配与脱敏证据，不将平台策略或过滤原因的字面推测作为根因。

- 2026-11-04：按用户要求完整下载已有抖音对照视频，确认资源 ID 固定模板返回的完整 MP4 为 `6880899` 字节，时长约 53 秒、720×1280、H.264/AAC，音视频完整解码通过；补充脱敏证据和主机验收边界，不修改应用逻辑，不扩展为原失败作品已修复的结论。

- 2026-11-04：按用户要求实测播放资源 ID 获取，确认原失败作品的公开详情被 `status_audit_self_see` 过滤；另一条既有剪贴对照成功取得资源 ID，固定模板返回 HTTP 206 / MP4 前 4 KB。记录主机网络验证范围、目标精确匹配、浏览器调试转发审批拒绝及脱敏证据，不修改应用逻辑或宣称完整下载成功。

- 2026-11-04：按用户要求查证抖音地址模板，记录原始开源实现中的资源 ID 拼接方式及带动态签名的其他地址形式；区分源码核查与当前样例实测，避免把作品 ID 可读取误写成已具备下载条件，不修改代码。

- 2026-11-04：针对错误页是否可获取 ID 的追问，只读检查同一 Chrome 页面的路径、内嵌状态及媒体元素，确认作品 ID 仍在、媒体字段缺失；同步澄清现有代码捕获完整地址而非由作品 ID 拼地址的真实行为，保存脱敏结构证据，不新增实现。

- 2026-11-04：按用户要求对同一抖音链接补充真机 Chrome 与三星浏览器对照，两者均出现与 WebView 相同的站点播放错误；更新结论和证据，明确不能把失败直接归因于 WebView，不修改代码。

- 2026-11-04：按用户指定使用现有抖音剪贴补测，记录自动超时、两轮交互页面错误及未生成下载任务的事实，保存脱敏诊断与截图；明确公开 MP4 成功不等于抖音成功，暂不臆测站点错误根因或修改代码。

- 2026-11-04：按用户要求补充真实公网网页真机验证，确认公开 MP4 从提取到下载、系统播放及完整解码均成功；记录现有 B 站短链接的 App 打开提示、验证边界和截图/日志证据。只更新验收文档，不修改生产或测试代码，避免把此前合成候选通过误当作真实视频可用。

- 2026-11-04：按用户新增授权完成三星真机验收，六条既有定向仪器测试及新增完整页面用例全部通过；补充键盘、实际 Activity 暂停恢复、候选成功、四张截图和低敏日志证据。未修改生产逻辑，保留真实站点及其他设备的验证边界。

- 2026-11-04：按用户确认完成可操作网页入口、无封面摘要、计时/诊断和后台直接释放；新增 15 条 JVM 回归及 5 条仪器用例源码，387 条 app 单测与两项 Kotlin 编译通过。独立审查的导航提交前错误过滤 P2 已通过失败回归修复；ADB daemon 无法启动，设备交互未验收。

- 2026-11-04：新增无封面、三行标题与可操作 WebView 的增量草案，明确同实例展开、失败后可见重试、计时/生命周期、错误诊断、共享接口与测试范围；原因是用户反馈频繁提取失败且无法查看网页过程。本轮只更新文档，同时纠正现行状态列表中残留的“需要用户播放”描述。

- 2026-10-26：本轮移除已完成；483 项单测、编译与 Release/R8 通过，设备验收未执行，详细证据见下载记录主方案。

- 2026-10-26：保留视频探测、下载详情、当前任务重试和冷启动取消；删除仅供历史页删除记录使用的单任务取消入口，不新增替代取消 UI。原因是用户确认执行宿主媒体历史退役。

- 2026-10-26：引用下载记录移除草案并明确视频下载执行与当前任务重试保留；原因是历史管理退役不等于移除任务执行存储。本轮仅改方案。

- 2026-10-16：按用户确认实施会话重试、页面生命周期、加载去重、进度/切换与旧链路清理；新增失败回归、预算、取消及状态测试，并补充独立复核发现的文档导航和中断任务边界。当前实现以顶部执行记录为准，旧审查保留为修复前证据。

- 2026-10-16：完成视频识别与下载页的源码流程审查，记录重试失效、迟到回调、查看任务重置进度、预览补全阻塞、重复提交及共享 WebView 加载/生命周期风险；原因是用户反馈两个下载页面体验不佳。仅同步现状、诊断计划和回归边界，未修改业务代码或宣称完成修复。

- 2026-09-30：完成视频探测层透明显示与触摸屏蔽，补齐中文说明并同步当前实现；使用不复用 daemon 的进程内 Kotlin 编译通过，设备验收待完成。原因是用户确认按图片页方式隐藏网页，本轮保持单文件生产代码调整。

- 2026-09-30：用户确认隐藏调整方案，开始在视频专用探测层复用透明 modifier 和触摸屏蔽配置；生产代码范围保持单文件，不改变视频识别规则和状态机。

- 2026-09-30：新增视频探测层隐藏调整草案，并修正当前状态对“隐藏”的描述；原因是用户反馈提取时仍显示 WebView，计划复用图片页的透明与触摸屏蔽配置，保留既有视频探测流程。本次仅更新方案，未修改业务代码。

- 2026-09-27：视频提取、下载进度和结果广告补齐与媒体摘要相同的左右 16dp 留白；下载页继续继承 PageBackground 的共同外层边距。纯布局调整，不改变任务状态和请求门控；完整广告布局与验证以 [广告主方案](ad_module_plan.md) 为准。

- 2026-09-27：视频提取/下载阶段广告在页面返回时建立新访问会话，阶段资格及媒体任务不变；修复广告释放后不重载，15 秒预算、日志及验证以 [广告主方案](ad_module_plan.md) 为准。

- 2026-05-27：视频下载页接入统一二级页面骨架，并记录视频提取页保留专属探测 chrome 的例外；原因是下载页只是状态展示和 Worker 观察，低风险适合统一骨架，提取页则绑定隐藏 WebView 与探测状态机，需要下一轮单独抽象。
- 2026-05-13：新增视频提取功能方案文档，记录当前视频入口、WebView 探测、候选判断、下载任务、直链/M3U8 下载、已知取舍和后续待办；原因是视频提取功能已有实现但缺少本地方案文档。
- 2026-05-14：补充下载记录页扩展契约，明确 Android 10+ 以保存的媒体 URI 作为读取和删除身份、视频重新下载创建新任务记录、同名文件使用序号后缀、删除进行中记录先取消 Worker 再删对应任务行；原因是下载记录页需要保留每次下载结果并避免误读同名副本、误删公共文件或误删整个数据库。
- 2026-05-14：将状态更新为实现中，并补充 WebView 链接预览补全方案；原因是用户明确希望首轮 HTTP 解析失败时先不调整 `LinkMetaParser`，等视频提取 WebView 成功加载网页后再补写预览图等信息，返回列表即可看到补齐后的链接预览。
- 2026-05-14：完成 WebView 链接预览补全实现并将状态更新为已完成，通过 `./gradlew :app:compileDebugKotlin` 验证；原因是视频提取页已能在页面加载完成或视频候选命中前补写 `link_previews`，且不改变首轮 `LinkMetaParser` 行为。
- 2026-05-14：同步下载记录页实现后的下载任务契约，记录 `video_url` 从唯一索引调整为普通索引、每次下载创建新任务、成功历史任务进入下载页不自动重新入队；原因是下载记录功能已经落地，需要避免文档继续描述旧的任务复用行为。
- 2026-05-18：按当前代码组织规则拆分视频提取页，新增状态 UI 组件和 WebView 探测辅助文件；原因是页面入口同时承担状态机、权限触发、WebView 配置、请求判断和 UI 展示，拆分后更符合入口负责流程编排、工具/组件按职责放置的约定。
- 2026-05-20：移除未接入导航链路的 `VideoCandidate` 自定义参数契约说明，代码同步退回普通内存态 data class；原因是当前下载导航已经只传递 `taskId`，候选对象无需 Parcelable、JSON 字段协议或 release R8 类名稳定契约。
- 2026-05-18：视频提取页加载、失败和成功行内提示接入共享媒体状态组件；原因是图片提取页和视频提取页存在相同的轻量状态展示模式，应由 `MediaStateContent` 统一维护图标、颜色、字号和点击区域。


## 2026-09-18 AdMob 接入变更（实现中）

提取、下载/合并、下载结果使用三个独立单元。复用缓存网页摘要、候选和既有任务元数据展示实际内容；广告接入不额外请求媒体探测，也不额外触发媒体自动播放，视频探测本身既有的自动播放 JS 仍保留。权限申请拒绝/结束回调解除页面等待。

共享广告的隐私、生命周期、资源、布局及低敏日志契约以 [广告主方案](ad_module_plan.md) 为准；本页验证重点为业务交互不回归及广告异步加载时的内容锚点保护。

变更记录：2026-09-19，按实际接入更新页面差异与边界；集成编译/测试结果见主方案。
