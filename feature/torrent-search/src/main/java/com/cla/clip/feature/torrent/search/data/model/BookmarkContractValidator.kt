package com.cla.clip.feature.torrent.search.data.model

import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.security.ExternalLinkPolicy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 05C 导入、Room 保存与 06C 导出共用的单条书签稳定协议。 */
internal object BookmarkContractValidator {
    /** 展示名称最大字符数。 */
    const val MAX_NAME_CHARS = 512
    /** Provider 展示名最大字符数。 */
    const val MAX_PROVIDER_NAME_CHARS = 128
    /** 大小展示文本最大字符数。 */
    const val MAX_SIZE_TEXT_CHARS = 128
    /** 单个外部链接最大字符数。 */
    const val MAX_LINK_CHARS = 8192

    /** 校验并规范化完整书签；任一字段无法由 05C parser 完整恢复时返回 null。 */
    fun normalize(
        /** 来自 Provider、Room 导入或导出调用方的单条书签。 */
        bookmark: BookmarkedTorrent,
    ): BookmarkedTorrent? {
        /** infoHash 复用磁力解析器的 v1 稳定身份规范化。 */
        val infoHash = MagnetInfoHashParser.normalize(bookmark.infoHash) ?: return null
        /** name 删除控制字符并裁剪，结果必须非空且不超限。 */
        val name = bookmark.name.sanitizeContractText().takeIf { /** value 是待校验的规范展示名。 */ value ->
            value.isNotEmpty() && value.length <= MAX_NAME_CHARS
        } ?: return null
        /** providerName 使用与名称一致的不可见字符规则和独立上限。 */
        val providerName = bookmark.providerName.sanitizeContractText().takeIf { /** value 是待校验的来源展示名。 */ value ->
            value.isNotEmpty() && value.length <= MAX_PROVIDER_NAME_CHARS
        } ?: return null
        /** size 是可选短展示字段，空文本允许按原协议保存。 */
        val size = bookmark.size?.takeIf { /** value 是待校验的大小展示文本。 */ value -> value.length <= MAX_SIZE_TEXT_CHARS } ?: if (bookmark.size != null) return null else null
        if (bookmark.uploadEpochMillis != null && bookmark.uploadEpochMillis < 0L) return null
        if (bookmark.bookmarkedAtEpochMillis < 0L) return null
        /** descriptionPageUrl 是规范化且无 userinfo 的 HTTP/HTTPS 地址。 */
        val descriptionPageUrl = bookmark.descriptionPageUrl.normalizeHttpUrlOrNull() ?: if (bookmark.descriptionPageUrl != null) return null else null
        /** fileDownloadLink 与详情地址共享外部 URL 安全契约。 */
        val fileDownloadLink = bookmark.fileDownloadLink.normalizeHttpUrlOrNull() ?: if (bookmark.fileDownloadLink != null) return null else null
        /** magnetUri 存在时必须可解析且与显式 infoHash 完全一致。 */
        val magnetUri = bookmark.magnetUri?.trim()?.takeIf { /** value 是待验证的磁力链接。 */ value ->
            value.length <= MAX_LINK_CHARS &&
                ExternalLinkPolicy.isAllowed(value) &&
                MagnetInfoHashParser.parse(value) == infoHash
        } ?: if (bookmark.magnetUri != null) return null else null
        return bookmark.copy(
            infoHash = infoHash,
            name = name,
            providerName = providerName,
            size = size,
            descriptionPageUrl = descriptionPageUrl,
            magnetUri = magnetUri,
            fileDownloadLink = fileDownloadLink,
        )
    }
}

/** 规范化可选 HTTP/HTTPS 地址；失败返回 null。 */
private fun String?.normalizeHttpUrlOrNull(): String? {
    /** value 是裁剪边界空白后的候选地址。 */
    val value = this?.trim() ?: return null
    if (value.length > BookmarkContractValidator.MAX_LINK_CHARS || !ExternalLinkPolicy.isAllowed(value)) return null
    /** parsedUrl 复用 OkHttp authority 校验并拒绝 userinfo。 */
    val parsedUrl = value.toHttpUrlOrNull() ?: return null
    if (parsedUrl.username.isNotEmpty() || parsedUrl.password.isNotEmpty()) return null
    /** normalizedUrl 是持久化与导出真正使用的百分号编码表示，必须再次满足字符上限。 */
    val normalizedUrl = parsedUrl.toString()
    if (normalizedUrl.length > BookmarkContractValidator.MAX_LINK_CHARS) return null
    return normalizedUrl
}

/** 删除 Unicode 控制字符并裁剪边界空白。 */
private fun String.sanitizeContractText(): String {
    /** sanitized 只保留可展示字符。 */
    val sanitized = buildString(length) {
        /** character 是当前候选 Unicode 字符。 */
        for (character in this@sanitizeContractText) if (!character.isISOControl()) append(character)
    }
    return sanitized.trim()
}
