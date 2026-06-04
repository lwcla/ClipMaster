状态：已完成

# 详情页方案

## 当前状态

详情页按路由传入的 `clipId` 加载单条剪贴记录，展示正文内容，并通过点击正文卡片复制剪贴数据；页面继续提供删除、链接复制、图片提取和视频提取入口。当 `:feature:magnet` 通过 `-PenableMagnetFeature=true` 编译进应用时，页面通过 `MagnetFeatureEntry.DetailAction` 追加磁力搜索动作，完整磁力模块化规则以 `docs/magnet_search_plan.md` 为准。页面入口 `DetailPage` 负责触发加载、收集详情状态、处理删除成功后返回和接入统一删除选择弹窗；详情页已接入 `SecondaryPageScaffold` 和 `ClipMasterCard`，正文卡片点击复用已有复制能力，删除入口位于标题栏右侧低频危险操作位，底部链接区从正文实时识别全部链接并以紧凑入口展示。详情页新增一个信息流/原生广告位，位于正文卡片和底部链接/磁力操作区之间；广告模块化、运行时选择、隐私边界和 adapter 验收规则以 `docs/ad_module_plan.md` 为准，当前真实 adapter 包含 CSJ 与 uni-ad。完整卡片规则以 `docs/shared_card_component_plan.md` 为准。

## 目标

- 保持详情页作为单条剪贴记录的阅读和操作页面。
- 保持可选磁力搜索、删除、复制、图片提取和视频提取的既有行为边界清晰，其中整条剪贴复制由正文卡片点击触发。
- 普通内容卡片复用公共卡片外壳，避免详情页继续手写独立 `Card` 视觉。
- 底部链接区从 `clip.content` 实时识别全部 URL，历史剪贴无需迁移即可支持多链接。
- 长链接只展示摘要，完整 URL 只用于复制、跳转和提取参数，避免底部区域挤压正文阅读空间。
- 多链接场景先选择具体链接再执行复制、提取视频或提取图片，识别到的链接列表不额外添加序号，避免误操作和底部拥挤。
- 列表页、搜索页、链接预览缓存、Room schema、DAO、备份协议和历史剪贴数据本次不变。

## 范围

- `DetailPage.kt` 的加载态、错误态、正文内容卡片点击复制和底部多链接能力区渲染。
- `LinkUtils` 的多链接提取、首个预览链接和首个媒体下载链接选择逻辑。
- `ClipDeleteChoiceDialog` 的删除选择语义保持由现有共享弹窗维护。
- 链接摘要、操作分区、正文滚动、复制和导航仍由详情页维护，不下沉到公共卡片组件。
- `ClipData`、`ClipShowEntity`、`ClipCaptureEntity`、Room 表结构、备份恢复协议、列表页和搜索页不在本次改动范围内。

## 用户体验

- 页面标题仍显示“剪贴详情”。
- 加载中、错误和正文内容都使用统一圆角、阴影和描边外壳。
- 正文长内容仍在卡片内纵向滚动，轻点正文卡片会把当前剪贴内容写入系统剪贴板，并沿用列表页复制后的时间戳刷新和 Toast 提示。
- 如果剪贴内容包含 1 条链接，底部展示链接摘要、链接类型提示、复制链接入口，并在链接允许提取时直接展示图片/视频提取入口。
- 如果剪贴内容包含多条链接，底部默认只展示前 3 条紧凑摘要，超过 3 条显示“查看全部链接”，用户点击某条摘要后在底部弹层里执行复制、提取视频或提取图片。
- 链接摘要使用 `host + 前 1-2 段 path + ...`，默认隐藏 query；识别列表不额外添加序号，摘要冲突时依赖点击后的完整 URL 供用户核对。
- 底部链接区域限制最大高度并在区域内部滚动，长链接或大量链接不会挤压正文卡片出屏幕。
- 点击链接打开的操作弹层以链接类型作为标题，直接展示可滚动的完整 URL，不再展示摘要版或限制完整 URL 的显示行数；复制、打开视频提取和打开图片提取始终使用完整 URL。
- `file://`、`ftp://`、`localhost`、内网地址和其他非公网 http/https 链接仍可复制，但不会进入 WebView 或下载提取流程，弹层提示该链接不支持提取。
- 底部不再展示普通复制按钮；如果没有链接提取入口且没有磁力扩展动作，则不渲染空操作区。
- 详情页成功态会在正文和底部操作区之间尝试渲染一个原生广告位；无广告源、广告关闭、隐私未同意、敏感详情、无填充、加载失败或保险丝禁用时直接隐藏，不占空白。
- 国内广告包启用 `:feature:ad-csj` 后可展示穿山甲模板信息流广告；广告异步加载，成功后才插入容器，容器保留“广告”标识且最大高度由 adapter 限制。
- 国内广告包启用 `:feature:ad-uniad` 后可展示 uni-ad 信息流广告；广告通过 `DCFeedAdLoader` 异步加载，渲染成功后才插入容器，找不到 Activity、无网、无填充、加载失败或超时时直接隐藏。
- 详情页广告不做用户可感知频控；同一个 `clipId` 在一次详情页生命周期内最多创建一次广告请求，切换剪贴记录后生成新的低敏 request nonce。
- 删除作为低频危险操作放在标题栏右上角图标按钮中，点击后仍进入统一删除选择弹窗。
- 磁力搜索按钮只在磁力模块启用时出现，点击后通过 `MagnetFeatureEntry.openSearch(initialQuery = clip.linkTitle ?: clip.content)` 进入磁力搜索页；该动作只传内部路由参数，不读取或写入系统剪贴板，也不保存磁力搜索历史。

## 数据流

- `DetailPage` 继续通过 `DetailViewModel.loadClip(clipId)` 加载单条记录，成功态拿到 `ClipShowEntity` 后渲染正文卡片。
- 正文卡片点击调用 `DetailViewModel.copyToClipboard(clip)`，由已委托的 `DefaultClipboardDataProcessor` 写入系统剪贴板、刷新记录时间戳并安排备份 dirty 标记。
- 详情页底部通过 `LinkUtils.extractUrls(clip.content)` 从正文实时提取全部链接，历史剪贴无需迁移即可支持多链接。
- `LinkUtils.extractUrls` 复用既有 URL 正则和尾部标点清理规则，并按清理后的完整 URL 顺序去重；同 host 但 query 不同的链接不会被误合并。
- `extractFirstUrl`、`extractFirstPreviewableUrl` 和 `extractFirstDownloadableMediaUrl` 委托 `extractUrls`，多候选场景仍按原文顺序选择第一条满足条件的链接。
- 详情页内部将完整 URL 转为 `DetailLinkUiState`，记录摘要、类型提示和是否允许提取；该模型保持 feature-local，不作为跨页面公共契约。
- 链接复制只调用 `copyToClipboard(link)`，不刷新整条剪贴记录时间戳；图片/视频提取和磁力搜索仍走各自导航回调。
- 详情页通过 `LaunchedEffect(clip.id)` 在剪贴切换时清空已选链接和底部弹层状态，避免 A 剪贴的旧链接残留到 B 剪贴。
- 详情页先用本地 `DetailAdSensitivityPolicy` 判断剪贴内容是否明显像验证码、密码、Token、密钥或银行卡号，只把敏感布尔值传给广告选择器和 adapter，不记录原文或命中片段。
- 详情页通过 `AdSourceSelector` 选择当前可用广告源，并向 adapter 传入 `AdSlotRequest(placement = DetailNative, requestNonce, isDebugRequest, isSensitiveContext)`；请求对象不包含剪贴正文、完整 URL 或搜索词。
- 详情页从 `AppSetting.adConsentStateFlow` 和 `AppSetting.adPrivacyPolicyVersionFlow` 收集广告隐私状态；真实 SDK 只有在明确同意且版本满足 adapter 要求时可用，debug 占位源仍可在调试构建降级展示。
- 广告源初始化失败或渲染失败事件会触发 `AdSessionFailureFuse`，当前会话后续跳过该 source，回退到其它 source 或隐藏广告位。

## 涉及文件

- `app/src/main/java/com/cla/clip/master/ui/page/detail/DetailPage.kt`
- `app/src/test/java/com/cla/clip/master/ui/page/detail/DetailLinkFormatterTest.kt`
- `base/general/src/main/java/com/cla/clip/base/general/utils/LinkUtils.kt`
- `base/general/src/test/java/com/cla/clip/base/general/utils/LinkUtilsTest.kt`
- `base/general/src/main/res/values/strings.xml`
- `docs/detail_page_plan.md`
- `docs/ad_module_plan.md`

## 实现步骤

1. 在 `LinkUtils` 增加 `extractUrls(text: String?): List<String>`，并让首链接、首个可预览链接和首个可下载媒体链接选择逻辑复用该候选列表。
2. 在详情页构建 `DetailLinkUiState`，生成隐藏 query 的摘要、类型提示和公网 http/https 提取许可，不为识别链接追加序号。
3. 将底部链接区改为单链接快捷操作、多链接前三条摘要和“查看全部链接”入口；所有链接详情与操作通过 BottomSheet 承载，弹层标题使用“网页链接 / 图片链接 / 媒体链接 / 其他链接”等类型文案。
4. 使用 URL 作为链接列表稳定 key，限制底部区域最大高度，并在剪贴切换时清空已选链接和弹层状态。
5. 补充多链接数量、查看全部链接、选择链接、链接类型、完整链接和不支持提取等字符串资源。
6. 为 `LinkUtils` 多链接解析和详情页链接摘要/展示模型补充单元测试。

## 测试验证

- 单元测试覆盖：空文本、单链接、多链接顺序、完整 URL 去重、同 host 不同 query、尾部标点清理、括号平衡、首个可预览/可下载候选选择、公网 http/https 提取边界。
- 单元测试覆盖：摘要隐藏 query、长 path 压缩、摘要冲突不追加序号、图片/媒体/网页/其他链接分类、`file://`/`ftp://`/localhost/内网地址不可提取但可复制、无法解析 host 时回退为截断原文。
- 本次验证命令：`./gradlew :base:general:testDebugUnitTest --tests "com.cla.clip.base.general.utils.LinkUtilsTest" --console=plain`、`./gradlew :app:testDebugUnitTest --tests "com.cla.clip.master.ui.page.detail.*" --console=plain`、`./gradlew :app:compileDebugKotlin --console=plain` 和 `git diff --check`。
- 手动验证点：A 剪贴打开链接 Sheet 后切换到 B 剪贴，不显示 A 的旧链接；长链接和很多链接都不会把正文挤出屏幕。
- 手动验证点：详情页加载态、错误态、正文滚动、点击正文卡片复制、底部复制按钮已移除、右上角删除入口、删除选择弹窗、图片提取和视频提取入口行为正确。
- 手动验证点：启用磁力模块时，点击磁力搜索按钮后进入磁力搜索页并带入标题或正文作为初始关键词，不写入剪贴板；默认禁用构建不展示该按钮。
- 手动验证点：debug 构建显示调试广告占位，release 默认无真实广告源时不显示广告；同一详情页重组不重复创建请求，切换到另一条剪贴记录后生成新的请求。
- 手动验证点：在 `local.properties` 或 CI Gradle 属性配置 `csjDebugAppId`/`csjDebugDetailNativeAdSlotId` 或 `csjReleaseAppId`/`csjReleaseDetailNativeAdSlotId` 后，对应 debug/release 构建会携带穿山甲详情页广告模块；debug 包使用项目固定 internal keystore，release 包使用正式 release keystore。隐私未同意或敏感详情不初始化穿山甲，同意后广告异步展示，离开详情页释放，弱网、无网、超时和无填充不占空白。
- 手动验证点：在 `local.properties` 或 CI Gradle 属性配置 `uniadDebugAppId`/`uniadDebugUnionId`/`uniadDebugDetailNativeAdpid` 或 `uniadReleaseAppId`/`uniadReleaseUnionId`/`uniadReleaseDetailNativeAdpid` 后，对应 debug/release 构建会携带 uni-ad 详情页广告模块；CSJ 与 uni-ad 同一 buildType 同时配置时构建失败。隐私未同意或敏感详情不初始化 uni-ad，同意后广告异步展示，离开详情页释放，弱网、无网、超时和无填充不占空白。

## 日志与诊断计划

- 链接提取本身不新增运行时日志；复制动作复用 `DefaultClipboardDataProcessor` 的现有 Toast 反馈，提取动作复用既有导航入口。
- 广告位新增低敏诊断日志：隐藏原因、请求、加载、展示、点击、释放、初始化失败和渲染失败只记录 `providerId`、`placementId`、`eventType`、`reasonCode`、`durationMs`、`isDebug` 和低敏 `sdkVersion`。
- 禁止输出内容：剪贴正文、完整链接、query 参数、内网地址、文件路径、用户输入、可恢复登录态、广告素材内容、SDK 原始响应和设备标识。
- 诊断方式以单元测试、编译验证和人工交互验证为主：确认多链接提取顺序、广告源选择、保险丝跳过、请求去重、剪贴切换状态清理和底部区域高度限制。

## 已知取舍

- 不把详情页操作分区抽成共享组件，因为它绑定剪贴记录、链接提取路由、复制和删除业务。
- 不把链接展示模型、摘要 formatter 和 BottomSheet 抽成公共组件，因为当前只有详情页需要多链接选择、复制和提取组合语义；后续若列表页或搜索页复用，再评估抽为共享组件。
- 不修改 `ClipData`、`ClipShowEntity`、`ClipCaptureEntity`、DAO、Room schema、备份协议或链接预览缓存；详情页实时从正文提取全部链接，列表页和搜索页继续使用 `clip.link` 展示主链接预览。
- 不为本次新增数据迁移或备份覆盖，因为没有新增持久化字段、设置项、跨安装状态或备份协议。
- 不新增 R8/release 验证，因为新增模型和 formatter 均为详情页内部纯 UI/纯 Kotlin 能力，不参与序列化、反射、Intent、通知或跨模块稳定协议。
- 不把底部磁力搜索按钮抽成 app 共享操作区，因为它现在由磁力模块通过 `MagnetFeatureEntry.DetailAction` 提供，宿主只负责传入候选关键词和打开回调。
- 不把广告位写成详情页私有 SDK 逻辑，因为广告源需要独立模块热插拔；详情页只保留广告位插槽和低敏事件处理。
- 多链接摘要默认隐藏 query，牺牲部分可见精确度换取紧凑性和隐私；实际复制和提取始终使用完整 URL，摘要冲突不再通过序号补偿，用户进入弹层后核对完整 URL。

## 开放问题

- 后续如果列表页、搜索页或链接预览卡片也需要多链接选择，可以把详情页内部 `DetailLinkUiState` 和摘要 formatter 收敛为 feature 级或共享链接展示能力。
- 后续如果用户需要更明确的可访问性提示，可考虑为正文卡片补充“复制内容”、为链接摘要补充“打开链接操作”的语义说明，但当前公共卡片入口还没有统一暴露语义标签参数，需要先评估共享组件契约。

## 变更记录

- 2026-06-01：详情页新增模块化原生广告位，位于正文卡片和底部操作区之间，并接入 `:feature:ad-api` 的选择器、请求去重和会话保险丝；原因是详情页需要先用调试广告源验证广告模块边界、隐私边界和页面布局。
- 2026-06-01：详情页广告位补充穿山甲 CSJ 国内 adapter 接入边界、隐私同意流、敏感详情保护、低敏 sdkVersion 日志和人工验证点；原因是国内渠道包开始接入真实信息流广告，页面文档需要记录详情页自身受影响的体验与生命周期约束。
- 2026-06-01：调整穿山甲手动验证入口，从必须显式传启用开关改为广告 ID 配置存在时默认带入广告模块；原因是本机/CI 已配置广告参数时，详情页验证不应依赖额外命令行开关。
- 2026-06-02：同步穿山甲 buildType 专属广告 ID 与固定 debug/internal 签名验证点；原因是详情页广告展示依赖当前包签名和对应广告位，debug/release 不能共用同一套后台配置。
- 2026-06-02：同步删除广告总启用配置的验证口径；原因是临时关闭详情页真实广告模块只需要注释对应 buildType 的 AppId 或广告位 ID。
- 2026-06-03：同步 uni-ad 详情页信息流 adapter 接入方式、CSJ/uni-ad 互斥和手动验证点；原因是当前详情页真实国内广告优先切换到 uni-ad 章鱼 + 泛连渠道验证。
- 2026-05-31：详情页底部改为从正文实时识别全部链接，长链接显示摘要，多链接通过 BottomSheet 选择后再复制或提取；原因是单条长链接会挤压正文、多条链接此前只能识别第一条。
- 2026-05-31：详情页链接列表去掉序号，链接操作弹层改为类型标题、完整 URL 可滚动展示和主色文案动作；原因是序号和按钮式操作在小屏下增加视觉负担，长链接仍需要完整核对。
- 2026-05-31：移除详情页底部复制按钮，改为点击正文卡片复制剪贴内容；原因是用户希望详情页阅读卡片本身承担复制动作，减少底部重复主操作。
- 2026-05-31：将详情页删除入口从正文底部危险操作区移动到标题栏右上角；原因是删除属于低频危险操作，不应占用底部高频操作区，但仍保留统一二次确认弹窗降低误触风险。
- 2026-05-27：详情页接入 `SecondaryPageScaffold` 并将操作区拆为链接提取、普通操作和危险操作；原因是本轮 UI 刷新要求提高详情页操作层级清晰度，并降低删除误触风险。
- 2026-05-25：详情页磁力搜索按钮改为通过 `MagnetFeatureEntry.DetailAction` 可选接入；原因是磁力搜索已独立为编译期可选模块，默认构建不能保留磁力 route 或页面实现引用。
- 2026-05-22：详情页新增磁力搜索入口，并记录通过路由传入候选关键词、不写剪贴板和不保存历史的边界；原因是磁力搜索第一版需要支持从单条剪贴内容直接发起搜索。
- 2026-05-18：新增详情页方案文档，并记录详情页普通内容卡片接入 `ClipMasterCard`；原因是本次整理触及详情页 UI 外壳，需要同步本地方案文档说明最终实现和保留的页面业务边界。
