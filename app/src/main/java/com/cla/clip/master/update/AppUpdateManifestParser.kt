package com.cla.clip.master.update

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * 解析并校验 public release 仓库中的 update.json。
 *
 * 该类只做纯逻辑判断：JSON 协议、包名、渠道、版本比较和下载源排序；网络、日志和 UI 状态由上层协作者处理。
 */
class AppUpdateManifestParser @Inject constructor() {
    /** 宽松 JSON 解析器；允许新增字段向前兼容，避免服务端增字段后旧客户端直接崩。 */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * 解析 `update.json` 并输出检查结果。
     *
     * 序列化失败统一映射成 `InvalidJson`，避免把协议错误误报成网络失败。
     */
    fun parse(text: String, currentApp: AppUpdateCurrentApp): AppUpdateCheckResult {
        /** 反序列化后的远端 manifest；结构不合法时直接返回协议错误。 */
        val manifest = try {
            json.decodeFromString<AppUpdateManifestDto>(text)
        } catch (_: SerializationException) {
            return AppUpdateCheckResult.Failed(AppUpdateFailureReason.InvalidJson)
        } catch (_: IllegalArgumentException) {
            return AppUpdateCheckResult.Failed(AppUpdateFailureReason.InvalidJson)
        }

        return manifest.toCheckResult(currentApp)
    }

    /**
     * 从 Gitee Release 响应里提取 `update.json` 资产地址。
     *
     * 只关心名字等于 `update.json` 的附件，避免把 APK 下载地址误当成 manifest。
     */
    fun extractGiteeUpdateJsonUrl(text: String): String? {
        /** Gitee Release 的反序列化结果；结构异常时直接放弃当前 release。 */
        val release = try {
            json.decodeFromString<GiteeReleaseDto>(text)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return release.assets
            .firstOrNull { asset ->
                asset.name?.trim()?.equals(UPDATE_JSON_ASSET_NAME, ignoreCase = true) == true
            }
            ?.browserDownloadUrl
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    /**
     * 把远端 DTO 转成最终检查结果。
     *
     * 顺序是固定的：先做协议兼容和包名/渠道校验，再做版本比较，最后处理下载源排序和强更判断。
     */
    private fun AppUpdateManifestDto.toCheckResult(currentApp: AppUpdateCurrentApp): AppUpdateCheckResult {
        if (schemaVersion != APP_UPDATE_SCHEMA_VERSION) {
            return AppUpdateCheckResult.Failed(AppUpdateFailureReason.SchemaUnsupported)
        }
        if (packageName != currentApp.packageName) {
            return AppUpdateCheckResult.Failed(AppUpdateFailureReason.PackageMismatch)
        }
        if (channel != currentApp.channel) {
            return AppUpdateCheckResult.Failed(AppUpdateFailureReason.ChannelMismatch)
        }
        if (versionCode <= currentApp.versionCode) {
            return AppUpdateCheckResult.UpToDate(versionCode = versionCode, versionName = versionName)
        }

        /** 按“国内推荐优先，其次保持原始配置顺序”整理后的下载入口列表。 */
        val sortedDownloads = downloads
            .mapIndexedNotNull { index, source -> source.toDomainOrNull(index) }
            .sortedWith(compareByDescending<IndexedAppUpdateLink> { it.link.recommendedForChina }.thenBy { it.index })
            .map { it.link }
        /** 兜底发布页；下载列表为空时至少还能把用户引导到发布页手动处理。 */
        val fallback = fallbackReleasePage.toDomainOrNull()
        if (sortedDownloads.isEmpty() && fallback == null) {
            return AppUpdateCheckResult.Failed(AppUpdateFailureReason.NoDownloadSource)
        }

        return AppUpdateCheckResult.UpdateAvailable(
            AppUpdateInfo(
                versionCode = versionCode,
                versionName = versionName,
                minSupportedVersionCode = minSupportedVersionCode,
                forceUpdate = forceUpdate || minSupportedVersionCode?.let { currentApp.versionCode < it } == true,
                publishedAt = publishedAt?.takeIf { it.isNotBlank() },
                sha256 = sha256?.takeIf { it.isNotBlank() },
                changelog = changelog.mapNotNull { it.trim().takeIf(String::isNotBlank) },
                downloads = sortedDownloads,
                fallbackReleasePage = fallback,
            )
        )
    }

    /** 把单个下载源 DTO 规范化成领域对象；缺少关键字段时直接丢弃，避免 UI 展示半残入口。 */
    private fun AppUpdateDownloadDto.toDomainOrNull(index: Int): IndexedAppUpdateLink? {
        /** 下载源稳定标识；空白时无法区分不同源，直接忽略。 */
        val normalizedId = id.trim()
        /** 用户可见的下载源名称；空白时不展示。 */
        val normalizedName = name.trim()
        /** 真正可打开的下载链接；空白时视为无效配置。 */
        val normalizedUrl = url.trim()
        if (normalizedId.isBlank() || normalizedName.isBlank() || normalizedUrl.isBlank()) return null
        return IndexedAppUpdateLink(
            index = index,
            link = AppUpdateLink(
                id = normalizedId,
                name = normalizedName,
                url = normalizedUrl,
                extractCode = extractCode?.trim()?.takeIf(String::isNotBlank),
                recommendedForChina = recommendedForChina,
            )
        )
    }

    /** 把 fallback 发布页 DTO 规范化成领域对象；缺字段时直接忽略。 */
    private fun AppUpdateReleasePageDto?.toDomainOrNull(): AppUpdateLink? {
        /** 远端提供的 fallback 发布页；为空表示当前 manifest 没有额外兜底入口。 */
        val page = this ?: return null
        /** 规范化后的发布页名称。 */
        val normalizedName = page.name.trim()
        /** 规范化后的发布页地址。 */
        val normalizedUrl = page.url.trim()
        if (normalizedName.isBlank() || normalizedUrl.isBlank()) return null
        return AppUpdateLink(
            id = "releasePage",
            name = normalizedName,
            url = normalizedUrl,
            extractCode = page.extractCode?.trim()?.takeIf(String::isNotBlank),
            recommendedForChina = true,
        )
    }

    /** 下载源排序时临时保留原始索引，用来在推荐标记相同时维持服务端配置顺序。 */
    private data class IndexedAppUpdateLink(
        /** 远端 `downloads` 数组里的原始顺序。 */
        val index: Int,

        /** 可直接给 UI 使用的下载入口。 */
        val link: AppUpdateLink,
    )

    private companion object {
        /** Gitee Release 资产里约定的 manifest 文件名。 */
        const val UPDATE_JSON_ASSET_NAME = "update.json"
    }
}
