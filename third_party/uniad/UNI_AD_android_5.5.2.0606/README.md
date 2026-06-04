# uni-ad 原生 SDK 制品记录

当前状态：实现中

本目录保存详情页 uni-ad 信息流广告 v1 需要的官方 AAR。制品来自 DCloud uni-ad 原生 SDK 包 `UNI_AD_android_5.5.2.0606`，当前仅保留基础广告、章鱼和泛连相关 AAR，不包含优量汇、穿山甲、百度、快手、Sigmob、GroMore 或华夏乐游。

## 制品范围

- `uniad-native-release.aar`：uni-ad 原生基础广告 SDK。
- `android-gif-drawable-1.2.29.aar`：uni-ad 基础包示例要求的 GIF 依赖。
- `uniad-zy-release.aar`：章鱼渠道 adapter。
- `octopus_ad_sdk_2.5.10.5.aar`：章鱼渠道 SDK。
- `Funlink_2.8.8_76006310_release.aar`：泛连渠道 SDK。
- `Funlink_adapter_uniad_2.8.4_74659082_release.aar`：泛连 uni-ad adapter。

## 来源和授权边界

- 来源：DCloud uni-ad 后台/原生 SDK 下载包 `UNI_AD_android_5.5.2.0606`。
- 更新时间：2026-06-03。
- 校验：见同目录 `checksums.sha256`。
- 授权：这些 AAR 只允许保存在当前私有仓库或私有 CI 制品环境中；禁止发布到公开仓库、公开 Maven 源或公开下载地址。

## 更新规则

- 更新 SDK 前必须重新记录官方包版本、下载入口、AAR 列表、SHA-256、包体变化、`.so`/ABI 覆盖、manifest 权限、重复类风险和 R8 验证结果。
- 默认构建未配置 uni-ad ID 时，不要求校验这些 AAR；配置 debug/release uni-ad 三项 ID 后，Gradle 会校验 AAR 是否存在且 SHA-256 匹配。
- 如审核失败或需要回退广告包，注释 `local.properties` 中对应 buildType 的 uni-ad `AppId` 或 `adpid`，宿主会自动回退到不带真实 uni-ad adapter 的包。
