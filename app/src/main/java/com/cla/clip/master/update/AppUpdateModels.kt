package com.cla.clip.master.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 当前客户端支持的 `update.json` 协议版本；服务端升级协议时需要同步提升并补兼容说明。 */
internal const val APP_UPDATE_SCHEMA_VERSION = 1

/** 当前安装包参与更新判断的稳定信息。 */
data class AppUpdateCurrentApp(
    /** 当前安装包名；用于拒绝错误分发到其他应用的 manifest。 */
    val packageName: String,

    /** 当前安装版本号；版本比较和强更判断都基于它。 */
    val versionCode: Int,

    /** 当前安装版本名；主要用于 UI 提示，不参与版本比较。 */
    val versionName: String,

    /** 当前安装渠道；不同发布渠道的 manifest 互不兼容。 */
    val channel: String,
)

/** 固定发布页或某个版本下载入口。 */
data class AppUpdateLink(
    /** 下载源稳定标识；用于日志、排序和去重。 */
    val id: String,

    /** 用户可见的下载源名称。 */
    val name: String,

    /** 实际可打开的下载或发布页地址。 */
    val url: String,

    /** 提取码；只有网盘类下载源才会提供。 */
    val extractCode: String? = null,

    /** 是否优先推荐给国内网络环境用户。 */
    val recommendedForChina: Boolean = false,
) {
    /** 日志里只记录低敏类型，不记录完整 URL、提取码或查询串。 */
    val logType: String
        get() {
            /** 归一化后的源 id，用来判断日志里的低敏类型。 */
            val normalizedId = id.lowercase()
            /** 归一化后的显示名称；当 id 不稳定时用它兜底判断来源类型。 */
            val normalizedName = name.lowercase()
            return when {
                "github" in normalizedId || "github" in normalizedName -> "github"
                "gitee" in normalizedId || "gitee" in normalizedName -> "gitee"
                "releasepage" in normalizedId -> "releasePage"
                else -> "download"
            }
        }
}

/** 可按顺序尝试的远端更新信息源。 */
data class AppUpdateManifestSource(
    /** 数据源稳定标识；用于日志中的回退提示。 */
    val id: String,

    /** 用户可读的数据源名称。 */
    val name: String,

    /** 请求 manifest 或 release API 的地址。 */
    val url: String,

    /** 该数据源返回的是直接 manifest 还是 release 包装响应。 */
    val format: AppUpdateManifestFormat,

    /** 当前数据源对应的发布页兜底入口。 */
    val releasePage: AppUpdateLink?,
)

/** manifest 数据源格式。 */
enum class AppUpdateManifestFormat {
    /** 直接返回 `update.json`。 */
    DirectJson,

    /** 先返回 Gitee Release 详情，再从资产里取 `update.json`。 */
    GiteeRelease,
}

/** 有新版可展示时的完整 UI 数据。 */
data class AppUpdateInfo(
    /** 远端目标版本号。 */
    val versionCode: Int,

    /** 远端目标版本名。 */
    val versionName: String,

    /** 当前版本低于它时需要强制更新；为空表示没有最低支持限制。 */
    val minSupportedVersionCode: Int?,

    /** 是否应在 UI 中按强制更新语义提示用户。 */
    val forceUpdate: Boolean,

    /** 发布时间文本；为空表示服务端未提供。 */
    val publishedAt: String?,

    /** APK 摘要；仅展示给用户校验，不参与客户端自动校验。 */
    val sha256: String?,

    /** 更新说明列表，已经过空白过滤。 */
    val changelog: List<String>,

    /** 可直接展示的下载入口列表。 */
    val downloads: List<AppUpdateLink>,

    /** 没有可直接下载入口时仍可跳转的发布页。 */
    val fallbackReleasePage: AppUpdateLink?,
)

/** 更新检查失败原因；`code` 用于日志和后续统计，不能随意改名。 */
enum class AppUpdateFailureReason(val code: String) {
    /** 网络层失败。 */
    NetworkError("network_error"),

    /** 服务器返回非 2xx 状态码。 */
    HttpError("http_error"),

    /** manifest JSON 结构不合法。 */
    InvalidJson("invalid_json"),

    /** manifest 协议版本超出当前客户端支持范围。 */
    SchemaUnsupported("schema_unsupported"),

    /** manifest 指向了其他应用包名。 */
    PackageMismatch("package_mismatch"),

    /** manifest 渠道与当前安装包渠道不一致。 */
    ChannelMismatch("channel_mismatch"),

    /** 远端版本不比当前版本新。 */
    VersionNotNewer("version_not_newer"),

    /** 没有任何可展示的下载入口或发布页。 */
    NoDownloadSource("no_download_source"),

    /** 其他未归类的异常。 */
    UnknownError("unknown_error"),
}

/** 检查更新最终结果。 */
sealed interface AppUpdateCheckResult {
    /** 发现了比当前版本更新的可用版本。 */
    data class UpdateAvailable(val info: AppUpdateInfo) : AppUpdateCheckResult

    /** 已经是最新版本或远端版本不更新。 */
    data class UpToDate(
        /** 远端返回的版本号。 */
        val versionCode: Int,

        /** 远端返回的版本名。 */
        val versionName: String,
    ) : AppUpdateCheckResult

    /** 检查失败；UI 可根据失败原因和兜底发布页决定是否提示用户。 */
    data class Failed(
        /** 统一失败原因。 */
        val reason: AppUpdateFailureReason,

        /** 失败时的 HTTP 状态码；非 HTTP 失败为空。 */
        val statusCode: Int? = null,

        /** 即使失败也可给用户展示的发布页兜底入口。 */
        val fallbackReleasePage: AppUpdateLink? = null,
    ) : AppUpdateCheckResult
}

@Serializable
internal data class AppUpdateManifestDto(
    /** manifest 协议版本。 */
    @SerialName("schemaVersion") val schemaVersion: Int,

    /** 目标发布渠道。 */
    @SerialName("channel") val channel: String,

    /** 目标应用包名。 */
    @SerialName("packageName") val packageName: String,

    /** 目标版本号。 */
    @SerialName("versionCode") val versionCode: Int,

    /** 目标版本名。 */
    @SerialName("versionName") val versionName: String,

    /** 最低支持版本号；当前版本低于它时需要强更。 */
    @SerialName("minSupportedVersionCode") val minSupportedVersionCode: Int? = null,

    /** 服务端显式标记的强更开关。 */
    @SerialName("forceUpdate") val forceUpdate: Boolean = false,

    /** 发布日期文本。 */
    @SerialName("publishedAt") val publishedAt: String? = null,

    /** APK 摘要文本。 */
    @SerialName("sha256") val sha256: String? = null,

    /** 更新说明列表。 */
    @SerialName("changelog") val changelog: List<String> = emptyList(),

    /** 没有下载源时仍可展示的 fallback 发布页。 */
    @SerialName("fallbackReleasePage") val fallbackReleasePage: AppUpdateReleasePageDto? = null,

    /** 可选下载源列表。 */
    @SerialName("downloads") val downloads: List<AppUpdateDownloadDto> = emptyList(),
)

@Serializable
internal data class GiteeReleaseDto(
    /** Release 资产列表；只从中挑出 `update.json`。 */
    @SerialName("assets") val assets: List<GiteeReleaseAssetDto> = emptyList(),
)

@Serializable
internal data class GiteeReleaseAssetDto(
    /** 资产文件名。 */
    @SerialName("name") val name: String? = null,

    /** 资产下载地址。 */
    @SerialName("browser_download_url") val browserDownloadUrl: String? = null,
)

@Serializable
internal data class AppUpdateReleasePageDto(
    /** 发布页显示名称。 */
    @SerialName("name") val name: String,

    /** 发布页地址。 */
    @SerialName("url") val url: String,

    /** 发布页关联提取码；通常只有网盘类入口才会提供。 */
    @SerialName("extractCode") val extractCode: String? = null,
)

@Serializable
internal data class AppUpdateDownloadDto(
    /** 下载源稳定标识。 */
    @SerialName("id") val id: String,

    /** 下载源显示名称。 */
    @SerialName("name") val name: String,

    /** 下载地址。 */
    @SerialName("url") val url: String,

    /** 下载源提取码。 */
    @SerialName("extractCode") val extractCode: String? = null,

    /** 是否优先推荐给国内用户。 */
    @SerialName("recommendedForChina") val recommendedForChina: Boolean = false,
)
