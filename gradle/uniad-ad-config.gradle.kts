import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Properties

/** 本机 uni-ad 构建配置文件；只在本机/CI 构建期读取，不进入运行时备份。 */
val uniadLocalPropertiesFile = rootProject.file("local.properties")

/** 本机 uni-ad 构建配置集合；用于让 Android Studio 普通 debug/run 自动启用已配置的广告包。 */
val uniadLocalBuildProperties = Properties().apply {
    if (uniadLocalPropertiesFile.isFile) {
        /** 本机配置输入流；读取完成后立即关闭，避免 Gradle 配置阶段泄漏文件句柄。 */
        FileInputStream(uniadLocalPropertiesFile).use(::load)
    }
}

/** 读取 uni-ad 构建参数；Gradle 属性优先，`local.properties` 兜底，便于 CI 覆盖本机默认值。 */
fun uniadBuildProperty(propertyName: String): String? {
    /** 命令行 `-P`、CI secret 或 `gradle.properties` 中的值；优先级高于本机文件。 */
    val gradlePropertyValue = providers.gradleProperty(propertyName).orNull
    if (!gradlePropertyValue.isNullOrBlank()) {
        return gradlePropertyValue
    }

    /** 本机 `local.properties` 中的值；空白值按未配置处理，避免误启用真实广告模块。 */
    val localPropertyValue = uniadLocalBuildProperties.getProperty(propertyName)
    return localPropertyValue?.takeIf(String::isNotBlank)
}

/** 校验 uni-ad 后台 ID；DCloud AppId、联盟 ID 和 adpid 都按数字 ID 处理，非数字占位文案按未配置。 */
fun String.asValidUniAdIdOrEmpty(): String {
    /** 去掉本机配置里可能误带的空白，避免纯空格触发广告模块。 */
    val trimmedValue = trim()
    /** uni-ad 后台 ID 的数字格式；用于挡住“你的AppId”这类占位文本。 */
    val uniadIdPattern = Regex("\\d+")
    return trimmedValue.takeIf { uniadIdPattern.matches(it) }.orEmpty()
}

/** 计算文件 SHA-256；只在配置了 uni-ad 广告 ID 时用于 AAR 完整性预检。 */
fun java.io.File.uniadSha256Hex(): String {
    /** SHA-256 摘要器；用于确认本地 AAR 没被替换或损坏。 */
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        /** 分块读取缓冲区；避免一次性把 AAR 全部载入内存。 */
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            /** 本次读取的字节数；-1 表示文件读取完成。 */
            val read = input.read(buffer)
            if (read == -1) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}

/** debug/internal uni-ad DCloud AppId；来自本机或 CI secret，不能硬编码到源码。 */
val uniadDebugAppIdValue = uniadBuildProperty("uniadDebugAppId").orEmpty().asValidUniAdIdOrEmpty()

/** debug/internal uni-ad 联盟 ID；来自本机或 CI secret，不能硬编码到源码。 */
val uniadDebugUnionIdValue = uniadBuildProperty("uniadDebugUnionId").orEmpty().asValidUniAdIdOrEmpty()

/** debug/internal 详情页信息流 adpid；用于测试后台应用和内部验证。 */
val uniadDebugDetailNativeAdpidValue = uniadBuildProperty("uniadDebugDetailNativeAdpid").orEmpty().asValidUniAdIdOrEmpty()

/** release uni-ad DCloud AppId；来自本机或 CI secret，不能硬编码到源码。 */
val uniadReleaseAppIdValue = uniadBuildProperty("uniadReleaseAppId").orEmpty().asValidUniAdIdOrEmpty()

/** release uni-ad 联盟 ID；来自本机或 CI secret，不能硬编码到源码。 */
val uniadReleaseUnionIdValue = uniadBuildProperty("uniadReleaseUnionId").orEmpty().asValidUniAdIdOrEmpty()

/** release 详情页信息流 adpid；用于正式广告收益。 */
val uniadReleaseDetailNativeAdpidValue = uniadBuildProperty("uniadReleaseDetailNativeAdpid").orEmpty().asValidUniAdIdOrEmpty()

/** 需要匹配的 uni-ad 隐私政策版本；空值表示当前构建暂不启用版本绑定。 */
val uniadRequiredPrivacyPolicyVersionValue = uniadBuildProperty("uniadRequiredPrivacyPolicyVersion").orEmpty()

/** debug/internal 是否已经具备打包 uni-ad 模块的最小配置。 */
val uniadDebugAdParametersConfigured = uniadDebugAppIdValue.isNotBlank() &&
    uniadDebugUnionIdValue.isNotBlank() &&
    uniadDebugDetailNativeAdpidValue.isNotBlank()

/** release 是否已经具备打包 uni-ad 模块的最小配置。 */
val uniadReleaseAdParametersConfigured = uniadReleaseAppIdValue.isNotBlank() &&
    uniadReleaseUnionIdValue.isNotBlank() &&
    uniadReleaseDetailNativeAdpidValue.isNotBlank()

/** uni-ad adapter 是否具备任一构建类型的接入参数；用于宿主判断是否依赖真实广告模块。 */
val uniadAdFeatureConfiguredValue = uniadDebugAdParametersConfigured || uniadReleaseAdParametersConfigured

/** debug/internal 是否具备 uni-ad adapter 接入参数；只由 debug 三项 ID 齐全决定。 */
val uniadDebugAdFeatureConfiguredValue = uniadDebugAdParametersConfigured

/** release 是否具备 uni-ad adapter 接入参数；只由 release 三项 ID 齐全决定。 */
val uniadReleaseAdFeatureConfiguredValue = uniadReleaseAdParametersConfigured

/** debug/internal 默认使用测试 adpid；该值写入 BuildConfig 用于诊断和 release 保护逻辑。 */
val uniadDebugUseTestAdpidValue = true

/** release 默认使用正式 adpid；用户不能通过属性把正式包切到测试位。 */
val uniadReleaseUseTestAdpidValue = false

/** uni-ad SDK 固定版本；和 third_party 目录名保持一致，用于日志和构建报告。 */
val uniadSdkVersionValue = "5.5.2.0606"

/** uni-ad AAR 固定目录；只保存 v1 章鱼 + 泛连信息流需要的官方制品。 */
val uniadArtifactDirValue = rootProject.file("third_party/uniad/UNI_AD_android_5.5.2.0606")

/** uni-ad v1 必需 AAR 与 SHA-256；配置广告 ID 后必须逐项匹配。 */
val uniadRequiredArtifactsValue = linkedMapOf(
    "uniad-native-release.aar" to "a7b2940e976c6618d652899046042d159aa5731d3f0de1d2f5c421dd961db6c5",
    "android-gif-drawable-1.2.29.aar" to "611e2699782ee0d56168b6546962f75a54bdff03136d9db94019a65c0924eddd",
    "uniad-zy-release.aar" to "e5a3a71baaac54b7be355a2b0ac6ffd8b05760845452b1824c123505f34ab5fc",
    "octopus_ad_sdk_2.5.10.5.aar" to "9a6979b4521dbff1698050ffc36ccf3d51eda9baec335644c867c6fcc546b9bc",
    "Funlink_2.8.8_76006310_release.aar" to "93265aed7be72da39bdf5e64324c451c6528264fccc2fbaf5bbb64ca656e314a",
    "Funlink_adapter_uniad_2.8.4_74659082_release.aar" to "0d22430179566ee62d82fde84dab2f7bdb800a9f4a224b503b5804567130c789",
)

/** 校验 uni-ad AAR 制品；默认包未配置广告 ID 时跳过，避免默认/海外包被广告 SDK 卡住。 */
fun validateUniAdArtifactsIfNeeded() {
    if (!uniadAdFeatureConfiguredValue) {
        return
    }
    uniadRequiredArtifactsValue.forEach { (artifactName, expectedSha256) ->
        /** 当前待校验 AAR 文件；文件名显式列出，禁止 fileTree 泛扫。 */
        val artifactFile = uniadArtifactDirValue.resolve(artifactName)
        if (!artifactFile.isFile) {
            throw GradleException("uni-ad 已配置广告参数，但缺少 AAR：${artifactFile.absolutePath}")
        }
        /** 当前 AAR 的 SHA-256；只用于构建期校验，不写入运行时日志。 */
        val actualSha256 = artifactFile.uniadSha256Hex()
        if (actualSha256 != expectedSha256) {
            throw GradleException("uni-ad AAR 校验失败：$artifactName，期望 $expectedSha256，实际 $actualSha256")
        }
    }
}

/** 当前 buildType 是否已配置穿山甲 debug 广告；用于阻止同一包同时塞入两套真实国内广告源。 */
val uniadCsjDebugConfigured = extra.properties["csjDebugAdFeatureConfigured"] as? Boolean ?: false

/** 当前 buildType 是否已配置穿山甲 release 广告；用于阻止同一包同时塞入两套真实国内广告源。 */
val uniadCsjReleaseConfigured = extra.properties["csjReleaseAdFeatureConfigured"] as? Boolean ?: false

if (uniadDebugAdFeatureConfiguredValue && uniadCsjDebugConfigured) {
    throw GradleException("debug/internal 同时配置了 uni-ad 和 CSJ，请只保留一套真实国内广告源。")
}

if (uniadReleaseAdFeatureConfiguredValue && uniadCsjReleaseConfigured) {
    throw GradleException("release 同时配置了 uni-ad 和 CSJ，请只保留一套真实国内广告源。")
}

validateUniAdArtifactsIfNeeded()

/** 对宿主和 adapter 暴露统一的 uni-ad 参数配置结果。 */
extra["uniadAdFeatureConfigured"] = uniadAdFeatureConfiguredValue

/** 对宿主暴露 debug/internal uni-ad 参数配置结果，用于 debugImplementation 裁剪依赖。 */
extra["uniadDebugAdFeatureConfigured"] = uniadDebugAdFeatureConfiguredValue

/** 对宿主暴露 release uni-ad 参数配置结果，用于 releaseImplementation 裁剪依赖。 */
extra["uniadReleaseAdFeatureConfigured"] = uniadReleaseAdFeatureConfiguredValue

/** 对 adapter 暴露 debug/internal DCloud AppId；仅写入调试构建产物的 BuildConfig。 */
extra["uniadDebugAppId"] = uniadDebugAppIdValue

/** 对 adapter 暴露 debug/internal 联盟 ID；仅写入调试构建产物的 BuildConfig。 */
extra["uniadDebugUnionId"] = uniadDebugUnionIdValue

/** 对 adapter 暴露 debug/internal 详情页信息流 adpid；仅写入调试构建产物的 BuildConfig。 */
extra["uniadDebugDetailNativeAdpid"] = uniadDebugDetailNativeAdpidValue

/** 对 adapter 暴露 release DCloud AppId；仅写入正式构建产物的 BuildConfig。 */
extra["uniadReleaseAppId"] = uniadReleaseAppIdValue

/** 对 adapter 暴露 release 联盟 ID；仅写入正式构建产物的 BuildConfig。 */
extra["uniadReleaseUnionId"] = uniadReleaseUnionIdValue

/** 对 adapter 暴露 release 详情页信息流 adpid；仅写入正式构建产物的 BuildConfig。 */
extra["uniadReleaseDetailNativeAdpid"] = uniadReleaseDetailNativeAdpidValue

/** 对 adapter 暴露 debug/internal 测试 adpid 标记；用于低敏诊断和正式包保护逻辑。 */
extra["uniadDebugUseTestAdpid"] = uniadDebugUseTestAdpidValue

/** 对 adapter 暴露 release 测试 adpid 标记；固定 false，避免正式包误切测试位。 */
extra["uniadReleaseUseTestAdpid"] = uniadReleaseUseTestAdpidValue

/** 对 adapter 暴露隐私政策版本绑定值；运行时只比较版本号，不输出隐私内容。 */
extra["uniadRequiredPrivacyPolicyVersion"] = uniadRequiredPrivacyPolicyVersionValue

/** 对 adapter 暴露 SDK 版本；只用于低敏日志和诊断面板。 */
extra["uniadSdkVersion"] = uniadSdkVersionValue

/** 对 adapter 暴露 AAR 目录；依赖声明必须显式列出文件名。 */
extra["uniadArtifactDir"] = uniadArtifactDirValue
