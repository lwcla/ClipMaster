import java.io.FileInputStream
import java.util.Properties

/** 复用既有广告配置约定：local.properties 只保存本机 ID，不进入源码和用户备份。 */
val admobLocalPropertiesFile = rootProject.file("local.properties")
/** 本机 AdMob 参数；读取流立即关闭，避免 Windows 文件句柄滞留。 */
val admobLocalBuildProperties = Properties().apply {
    if (admobLocalPropertiesFile.isFile) {
        FileInputStream(admobLocalPropertiesFile).use(::load)
    }
}

/** 非空 Gradle 参数优先，本机文件兜底；去除首尾空白后仍须通过正式 ID 校验。 */
fun admobBuildProperty(propertyName: String): String? {
    /** CI 的 -P 或 gradle.properties 显式值，保持与 CSJ/uni-ad 相同优先级。 */
    val gradlePropertyValue = providers.gradleProperty(propertyName).orNull?.trim()
    if (!gradlePropertyValue.isNullOrBlank()) return gradlePropertyValue
    /** 本机 ID 仅作为非空兜底；开关仍由统一 Gradle 显式参数决定。 */
    val localPropertyValue = admobLocalBuildProperties.getProperty(propertyName)?.trim()
    return localPropertyValue?.takeIf(String::isNotBlank)
}
/** 读取 AdMob 调试开关；仓库默认在 gradle.properties 开启，未声明属性时仍保守关闭。 */
val admobDebugEnabled = providers.gradleProperty("enableAdmobDebugAdFeature").map(String::toBoolean).getOrElse(false)
/** 正式广告开关，必须配齐独立广告位。 */
val admobReleaseEnabled = providers.gradleProperty("enableAdmobReleaseAdFeature").map(String::toBoolean).getOrElse(false)
/** 发布渠道只控制行为，不根据语言、时区或设备推测。 */
val distribution = providers.gradleProperty("appDistribution").getOrElse("domestic")
require(distribution in setOf("domestic", "play")) { "appDistribution 必须为 domestic 或 play" }
/** 同一构建类型不能同时引入国内广告 SDK 和 AdMob。 */
for (variant in listOf("Debug", "Release")) {
    /** 当前变体国内 SDK 是否开启。 */
    val domesticEnabled = listOf("Csj", "Uniad").any {
        providers.gradleProperty("enable${it}${variant}AdFeature").map(String::toBoolean).getOrElse(false)
    }
    /** 当前变体 AdMob 是否开启。 */
    val admobEnabled = if (variant == "Debug") admobDebugEnabled else admobReleaseEnabled
    require(!(domesticEnabled && (admobEnabled || distribution == "play"))) {
        "Play 构建禁止 CSJ/uni-ad；同一 buildType 的 AdMob 与国内广告 SDK 互斥"
    }
}
/** 发布配置中的稳定广告位名称，必须与 API 枚举一一对应。 */
val admobPlacementNames = listOf(
    "DetailNative", "HomeFeedNative", "SearchFeedNative", "FoldedFeedNative",
    "FoldedSearchNative", "RecycleFeedNative", "VideoExtractNative",
    "VideoDownloadProgressNative", "VideoDownloadResultNative", "ImageExtractNative",
    "ImageDownloadProgressNative", "ImageDownloadResultNative", "VideoHistoryNative",
    "ImageHistoryNative", "BackupOverviewNative", "BackupRestoreNative",
    "BackupMediaRelocationNative",
)
/** 官方测试发布者，正式包必须拒绝其全部样例 ID。 */
val samplePublisher = "ca-app-pub-3940256099942544"
/** 正式 App ID 不提供样例兜底。 */
val releaseAppId = admobBuildProperty("admobReleaseAppId").orEmpty()
/** 正式广告单元优先使用 Gradle 参数，本机文件兜底；不记录到构建日志。 */
val releaseUnits = admobPlacementNames.associateWith {
    admobBuildProperty("admobRelease${it}AdUnitId").orEmpty()
}
if (admobReleaseEnabled) {
    require(Regex("ca-app-pub-[0-9]{16}~[0-9]{10}").matches(releaseAppId) && !releaseAppId.startsWith(samplePublisher)) {
        "AdMob release 必须设置真实 admobReleaseAppId"
    }
    require(releaseUnits.values.all { Regex("ca-app-pub-[0-9]{16}/[0-9]{10}").matches(it) && !it.startsWith(samplePublisher) }) {
        "AdMob release 必须配置全部 ${admobPlacementNames.size} 个真实 admobRelease<Placement>AdUnitId"
    }
    require(releaseUnits.values.toSet().size == admobPlacementNames.size) { "AdMob release 的 ${admobPlacementNames.size} 个原生广告位必须互不相同" }
}
extra["enableAdmobDebugAdFeature"] = admobDebugEnabled
extra["enableAdmobReleaseAdFeature"] = admobReleaseEnabled
extra["isPlayDistribution"] = distribution == "play"
extra["admobPlacementNames"] = admobPlacementNames
extra["admobReleaseAppId"] = releaseAppId
extra["admobReleaseUnits"] = releaseUnits
