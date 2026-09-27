状态：实现中

# local.properties 广告配置示例

## 当前状态

广告参数只从本机 `local.properties` 或 CI Gradle 属性读取，不写入源码、不进入备份、不输出到日志。模块开关统一在项目 `gradle.properties` 中声明，也可以由 CI/命令行 `-P` 覆盖；当前真实国内广告源支持 CSJ 与 uni-ad，但同一个 buildType 只能开启其中一套。

## 模块开关

项目 `gradle.properties` 默认开启 AdMob debug/release 模块并使用 Play 渠道，其余可选实现保持关闭。以下为国内广告开关；需要构建国内广告包时，应同时设置 `appDistribution=domestic`、关闭对应 buildType 的 AdMob，再开启目标国内开关，或在命令行传同名 `-P` 参数：

```properties
# debug/internal 穿山甲 adapter 开关。
enableCsjDebugAdFeature=false
# release 穿山甲 adapter 开关。
enableCsjReleaseAdFeature=false
# debug/internal uni-ad adapter 开关。
enableUniadDebugAdFeature=false
# release uni-ad adapter 开关。
enableUniadReleaseAdFeature=false
```

开关开启后，对应 buildType 的下列参数必须完整且为数字 ID，否则 Gradle 在配置期失败。只把 ID 写入 `local.properties` 不会自动启用模块。

## uni-ad 示例

```properties
# debug/internal：绑定项目固定 debug-internal.keystore 的测试后台应用和测试 adpid。
uniadDebugAppId=123456
uniadDebugUnionId=654321
uniadDebugDetailNativeAdpid=1000000001

# release：绑定正式 release 签名的正式后台应用和正式 adpid。
uniadReleaseAppId=234567
uniadReleaseUnionId=765432
uniadReleaseDetailNativeAdpid=1000000002

# 可选：要求用户已同意包含当前 uni-ad、章鱼、泛连 SDK 清单的隐私政策版本。
uniadRequiredPrivacyPolicyVersion=2026-06-uniad
```

## CSJ 示例

```properties
# debug/internal：绑定项目固定 debug-internal.keystore 的测试应用和测试代码位。
csjDebugAppId=123456
csjDebugDetailNativeAdSlotId=987654321
csjDebugUseTestAdSlot=true

# release：绑定正式 release 签名的正式应用和正式代码位。
csjReleaseAppId=234567
csjReleaseDetailNativeAdSlotId=876543210
csjReleaseUseTestAdSlot=false

# 可选：要求用户已同意包含当前 CSJ SDK 清单的隐私政策版本。
csjRequiredPrivacyPolicyVersion=2026-06-csj
```

## 临时关闭

- 临时关闭 debug/internal 真实广告：把对应 debug feature 开关设为 `false`，无需删除本机 ID。
- 临时关闭 release 真实广告：把对应 release feature 开关设为 `false`，无需删除本机 ID。
- 同一 buildType 不要同时开启 CSJ 与 uni-ad；Gradle 会在配置期失败，避免同包重复带入两套真实国内广告 SDK。

## 变更记录

- 2026-09-27：用户已提供完整真实 ID 并登记测试设备，debug/release 改为共用本机真实配置，release 默认开启；同步配置说明和测试设备边界。真实值不写入本示例，构建与设备验证结果统一记录于广告主方案。

- 2026-09-27：新增媒体关联页独立广告配置 `admobReleaseBackupMediaRelocationNativeAdUnitId`，正式单元总数增至 17；debug 继续自动生成测试 ID。无新敏感日志，配置验证与测试以广告主方案为准。

- 2026-09-26：默认开启 AdMob debug 和 Play 渠道，简化测试构建命令；补充国内构建覆盖方式及正式开关保留关闭的原因。复用既有日志与测试，不新增运行时逻辑。
- 2026-06-03：新增广告本机配置示例，记录 uni-ad 与 CSJ buildType 专属参数、隐私版本和临时关闭方式；原因是广告模块已改为配置齐全时自动打包，开发者不需要手动传启用开关。
- 2026-09-13：增加四个显式广告 feature 开关示例，并把临时关闭方式改为关闭开关；原因是可选模块统一由 `gradle.properties`/`-P` 控制，广告密钥只负责参数配置与开启后的完整性校验。

## Google Play / AdMob 配置（2026-09-18）

主设计以 [广告主方案](ad_module_plan.md) 为准。模块开关与发行渠道使用 Gradle 属性；ID 只保存在本机 `local.properties` 或 CI 属性中。不要提交真实 ID。

```properties
appDistribution=play
enableAdmobDebugAdFeature=true
enableAdmobReleaseAdFeature=true
# 国内广告开关全部关闭；同包混合 SDK 会在配置期失败。
enableCsjDebugAdFeature=false
enableCsjReleaseAdFeature=false
enableUniadDebugAdFeature=false
enableUniadReleaseAdFeature=false
```

以上是当前默认值。debug 和 release 共用下面这套真实 App ID 与广告单元，沿用 admobRelease 参数名以避免两份配置漂移；不再自动回退 Google 官方示例 ID。任一 AdMob 开关开启时，必须提供下面全部参数；17 个单元必须不同，缺失、格式错误或示例 ID 会在配置期失败，并影响 Android Studio 同步及其他任务。新电脑/CI 需先补齐本机或 Gradle 参数；要构建无广告包，可同时关闭两个 AdMob 开关。

开发测试使用自己的真实广告单元，并在 AdMob「设置 → 测试设备」登记手机；用户已完成本机登记。测试模式由后台设备配置生效情况决定，debug 标记本身不等于测试设备。确认 Google 原生素材带 Test Ad 标记后再做交互测试；设备登记可能需要时间生效。应用审核未完成可以先做测试接入验证，但不代表已获得正式投放资格。实际请求仍需网络、UMP canRequestAds 和页面资格均满足。[官方测试说明](https://developers.google.com/admob/android/next-gen/test-ads)。

```properties
# 同一正式 App ID 供 debug/release 初始化和 Manifest 元数据使用。
admobReleaseAppId=REPLACE_WITH_REAL_APP_ID
# 详情页原生单元。
admobReleaseDetailNativeAdUnitId=REPLACE_WITH_DISTINCT_NATIVE_UNIT_ID
# 首页列表原生单元；使用最新后台创建并确认的值。
admobReleaseHomeFeedNativeAdUnitId=REPLACE_WITH_DISTINCT_NATIVE_UNIT_ID
# 普通搜索结果原生单元。
admobReleaseSearchFeedNativeAdUnitId=REPLACE_WITH_DISTINCT_NATIVE_UNIT_ID
# 折叠列表原生单元。
admobReleaseFoldedFeedNativeAdUnitId=REPLACE_WITH_DISTINCT_NATIVE_UNIT_ID
# 折叠内搜索原生单元。
admobReleaseFoldedSearchNativeAdUnitId=REPLACE_WITH_DISTINCT_NATIVE_UNIT_ID
# 回收站列表原生单元。
admobReleaseRecycleFeedNativeAdUnitId=REPLACE_WITH_DISTINCT_NATIVE_UNIT_ID
# 视频提取阶段原生单元。
admobReleaseVideoExtractNativeAdUnitId=REPLACE_WITH_DISTINCT_NATIVE_UNIT_ID
# 视频下载进度阶段原生单元。
admobReleaseVideoDownloadProgressNativeAdUnitId=REPLACE_WITH_DISTINCT_NATIVE_UNIT_ID
# 视频下载结果阶段原生单元。
admobReleaseVideoDownloadResultNativeAdUnitId=REPLACE_WITH_DISTINCT_NATIVE_UNIT_ID
# 图片提取阶段原生单元。
admobReleaseImageExtractNativeAdUnitId=REPLACE_WITH_DISTINCT_NATIVE_UNIT_ID
# 图片下载进度阶段原生单元。
admobReleaseImageDownloadProgressNativeAdUnitId=REPLACE_WITH_DISTINCT_NATIVE_UNIT_ID
# 图片下载结果阶段原生单元。
admobReleaseImageDownloadResultNativeAdUnitId=REPLACE_WITH_DISTINCT_NATIVE_UNIT_ID
# 视频下载历史原生单元。
admobReleaseVideoHistoryNativeAdUnitId=REPLACE_WITH_DISTINCT_NATIVE_UNIT_ID
# 图片下载历史原生单元。
admobReleaseImageHistoryNativeAdUnitId=REPLACE_WITH_DISTINCT_NATIVE_UNIT_ID
# 备份总览原生单元。
admobReleaseBackupOverviewNativeAdUnitId=REPLACE_WITH_DISTINCT_NATIVE_UNIT_ID
# 恢复预检和结果原生单元。
admobReleaseBackupRestoreNativeAdUnitId=REPLACE_WITH_DISTINCT_NATIVE_UNIT_ID
# 恢复后媒体关联原生单元。
admobReleaseBackupMediaRelocationNativeAdUnitId=REPLACE_WITH_DISTINCT_NATIVE_UNIT_ID
```

PowerShell 验证示例（属性参数整体加引号）：

```powershell
.\gradlew.bat :app:assembleDebug --no-parallel
.\gradlew.bat :app:assembleRelease --no-parallel
.\gradlew.bat :app:bundleRelease '-PappDistribution=play' '-PenableAdmobReleaseAdFeature=true' --no-parallel
```

### 后台与上架清单

- 在自己的 Google Play / AdMob 账号完成应用创建、商店关联和审核；为 17 个逻辑位置分别创建原生广告单元。
- 在开发者网站发布隐私政策与 AdMob 后台提供的 `app-ads.txt`，不要使用其他发布商标识。
- 在 UMP 后台配置所需隐私消息，测试地区及隐私选项入口；运行时以 `canRequestAds()` 为准。
- 核对包含广告、目标受众、广告 ID、Data safety、SDK 数据用途和最终 merged manifest；不得仅依据模板填写商店声明。
- 正式 ID + 签名配置完成后生成 Play AAB，真机覆盖小屏、大字体、TalkBack、旋转、弱网、撤回同意、首屏与长列表。
- 内部测试后分阶段发布。观察低敏加载/曝光/失败事件、后台填充/收益与崩溃/ANR；首期不引入远程配置服务。

这些账号、真实 ID、后台政策声明和发布动作无法用测试配置代替，需由应用所有者在对应后台完成。

### 日志与诊断

仅记录位置、阶段、原因码、耗时和 SDK 版本；不输出广告单元 ID、原始 SDK 响应、凭据、媒体 URL、备份路径或业务内容 key。

- 2026-09-18：补充 Play/AdMob 开关、16 个独立单元配置、测试命令及后台发布清单；支持本期渠道隔离接入。
