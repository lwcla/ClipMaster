/** 读取统一的可选模块开关；Gradle 属性未声明时默认关闭，并保持与既有 `enableMagnetFeature` 相同的布尔解析语义。 */
fun readOptionalFeatureFlag(propertyName: String): Boolean = providers.gradleProperty(propertyName)
    .map(String::toBoolean)
    .getOrElse(false)

/** 是否把 TorrentSearch 实现模块编译进宿主；默认关闭以缩短普通构建并隔离可选能力。 */
val enableMagnetFeatureValue = readOptionalFeatureFlag("enableMagnetFeature")

/** 是否把穿山甲 adapter 编译进 debug/internal 包；开启后必须同时提供对应测试后台参数。 */
val enableCsjDebugAdFeatureValue = readOptionalFeatureFlag("enableCsjDebugAdFeature")

/** 是否把穿山甲 adapter 编译进 release 包；开启后必须同时提供对应正式后台参数。 */
val enableCsjReleaseAdFeatureValue = readOptionalFeatureFlag("enableCsjReleaseAdFeature")

/** 是否把 uni-ad adapter 编译进 debug/internal 包；开启后必须同时提供对应测试后台参数与本地 AAR。 */
val enableUniadDebugAdFeatureValue = readOptionalFeatureFlag("enableUniadDebugAdFeature")

/** 是否把 uni-ad adapter 编译进 release 包；开启后必须同时提供对应正式后台参数与本地 AAR。 */
val enableUniadReleaseAdFeatureValue = readOptionalFeatureFlag("enableUniadReleaseAdFeature")

/** 向宿主暴露 TorrentSearch 显式开关，避免各模块重复实现属性读取规则。 */
extra["enableMagnetFeature"] = enableMagnetFeatureValue

/** 向宿主和广告配置脚本暴露 debug/internal 穿山甲显式开关。 */
extra["enableCsjDebugAdFeature"] = enableCsjDebugAdFeatureValue

/** 向宿主和广告配置脚本暴露 release 穿山甲显式开关。 */
extra["enableCsjReleaseAdFeature"] = enableCsjReleaseAdFeatureValue

/** 向宿主和广告配置脚本暴露 debug/internal uni-ad 显式开关。 */
extra["enableUniadDebugAdFeature"] = enableUniadDebugAdFeatureValue

/** 向宿主和广告配置脚本暴露 release uni-ad 显式开关。 */
extra["enableUniadReleaseAdFeature"] = enableUniadReleaseAdFeatureValue
