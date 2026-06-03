import java.io.FileInputStream
import java.util.Properties

/** 本机广告构建配置文件；只在本机/CI 构建期读取，不进入源码和运行时备份。 */
val csjLocalPropertiesFile = rootProject.file("local.properties")

/** 本机广告构建配置集合；用于让开发者无需命令行参数即可启用穿山甲包。 */
val csjLocalBuildProperties = Properties().apply {
    if (csjLocalPropertiesFile.isFile) {
        /** 本机配置输入流；读取完成后立即关闭，避免 Gradle 配置阶段泄漏文件句柄。 */
        FileInputStream(csjLocalPropertiesFile).use(::load)
    }
}

/** 读取广告构建参数；Gradle 属性优先，`local.properties` 兜底，便于 CI 临时覆盖本机默认值。 */
fun csjBuildProperty(propertyName: String): String? {
    /** 命令行 `-P` 或 `gradle.properties` 中的值；优先级高于本机文件。 */
    val gradlePropertyValue = providers.gradleProperty(propertyName).orNull
    if (!gradlePropertyValue.isNullOrBlank()) {
        return gradlePropertyValue
    }

    /** 本机 `local.properties` 中的值；空白值按未配置处理，避免误启用广告模块。 */
    val localPropertyValue = csjLocalBuildProperties.getProperty(propertyName)
    return localPropertyValue?.takeIf(String::isNotBlank)
}

/** 读取广告布尔构建参数；只接受 true/false，非法值按未配置处理并交给默认规则兜底。 */
fun csjBuildBooleanProperty(propertyName: String): Boolean? {
    /** 原始布尔文本；允许本机和 CI 使用大小写不同的 true/false。 */
    val rawPropertyValue = csjBuildProperty(propertyName)?.trim() ?: return null
    return when (rawPropertyValue.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

/** 校验穿山甲后台 ID；AppId 和代码位 ID 都应为数字，非数字占位文案按未配置处理。 */
fun String.asValidCsjIdOrEmpty(): String {
    /** 去掉本机配置里可能误带的空白，避免纯空格触发广告模块。 */
    val trimmedValue = trim()
    /** 穿山甲 AppId/代码位 ID 的数字格式；用于挡住“你的AppId”这类占位文本。 */
    val csjIdPattern = Regex("\\d+")
    return trimmedValue.takeIf { csjIdPattern.matches(it) }.orEmpty()
}

/** debug/internal 穿山甲 AppId；来自本机或 CI secret，不能硬编码到源码。 */
val csjDebugAppIdValue = csjBuildProperty("csjDebugAppId").orEmpty().asValidCsjIdOrEmpty()

/** release 穿山甲 AppId；来自本机或 CI secret，不能硬编码到源码。 */
val csjReleaseAppIdValue = csjBuildProperty("csjReleaseAppId").orEmpty().asValidCsjIdOrEmpty()

/** debug/internal 详情页信息流/原生广告位 ID；用于测试广告展示。 */
val csjDebugDetailNativeAdSlotIdValue = csjBuildProperty("csjDebugDetailNativeAdSlotId").orEmpty().asValidCsjIdOrEmpty()

/** release 详情页信息流/原生广告位 ID；用于正式广告收益。 */
val csjReleaseDetailNativeAdSlotIdValue = csjBuildProperty("csjReleaseDetailNativeAdSlotId").orEmpty().asValidCsjIdOrEmpty()

/** debug/internal 是否已经具备打包穿山甲模块的最小配置。 */
val csjDebugAdParametersConfigured = csjDebugAppIdValue.isNotBlank() && csjDebugDetailNativeAdSlotIdValue.isNotBlank()

/** release 是否已经具备打包穿山甲模块的最小配置。 */
val csjReleaseAdParametersConfigured = csjReleaseAppIdValue.isNotBlank() && csjReleaseDetailNativeAdSlotIdValue.isNotBlank()

/** 穿山甲 adapter 是否具备任一构建类型的接入参数；用于 adapter 模块判断是否需要编译官方 SDK。 */
val csjAdFeatureConfiguredValue = csjDebugAdParametersConfigured || csjReleaseAdParametersConfigured

/** debug/internal 是否具备穿山甲 adapter 接入参数；只由 debug ID 齐全决定。 */
val csjDebugAdFeatureConfiguredValue = csjDebugAdParametersConfigured

/** release 是否具备穿山甲 adapter 接入参数；只由 release ID 齐全决定。 */
val csjReleaseAdFeatureConfiguredValue = csjReleaseAdParametersConfigured

/** debug/internal 默认使用测试广告位，避免内部验证污染正式收益。 */
val csjDebugUseTestAdSlotValue = csjBuildBooleanProperty("csjDebugUseTestAdSlot") ?: true

/** release 默认使用正式广告位，避免正式包被测试位保护逻辑隐藏。 */
val csjReleaseUseTestAdSlotValue = csjBuildBooleanProperty("csjReleaseUseTestAdSlot") ?: false

/** 需要匹配的广告隐私政策版本；空值表示当前构建暂不启用版本绑定。 */
val csjRequiredPrivacyPolicyVersionValue = csjBuildProperty("csjRequiredPrivacyPolicyVersion").orEmpty()

/** 对 adapter 暴露统一的穿山甲参数配置结果，避免官方 SDK 依赖判断口径不一致。 */
extra["csjAdFeatureConfigured"] = csjAdFeatureConfiguredValue

/** 对宿主暴露 debug/internal 穿山甲参数配置结果，用于 debugImplementation 裁剪依赖。 */
extra["csjDebugAdFeatureConfigured"] = csjDebugAdFeatureConfiguredValue

/** 对宿主暴露 release 穿山甲参数配置结果，用于 releaseImplementation 裁剪依赖。 */
extra["csjReleaseAdFeatureConfigured"] = csjReleaseAdFeatureConfiguredValue

/** 对 adapter 暴露 debug/internal 穿山甲 AppId；仅写入调试构建产物的 BuildConfig。 */
extra["csjDebugAppId"] = csjDebugAppIdValue

/** 对 adapter 暴露 release 穿山甲 AppId；仅写入正式构建产物的 BuildConfig。 */
extra["csjReleaseAppId"] = csjReleaseAppIdValue

/** 对 adapter 暴露 debug/internal 详情页广告位 ID；仅写入调试构建产物的 BuildConfig。 */
extra["csjDebugDetailNativeAdSlotId"] = csjDebugDetailNativeAdSlotIdValue

/** 对 adapter 暴露 release 详情页广告位 ID；仅写入正式构建产物的 BuildConfig。 */
extra["csjReleaseDetailNativeAdSlotId"] = csjReleaseDetailNativeAdSlotIdValue

/** 对宿主和 adapter 暴露 debug/internal 测试广告位标记。 */
extra["csjDebugUseTestAdSlot"] = csjDebugUseTestAdSlotValue

/** 对宿主和 adapter 暴露 release 测试广告位标记，默认 false。 */
extra["csjReleaseUseTestAdSlot"] = csjReleaseUseTestAdSlotValue

/** 对 adapter 暴露隐私政策版本绑定值；运行时只比较版本号，不输出隐私内容。 */
extra["csjRequiredPrivacyPolicyVersion"] = csjRequiredPrivacyPolicyVersionValue
