package com.cla.clip.base.general.magnet

import java.net.URLEncoder

/**
 * 标准 magnet URI 构造器。
 *
 * 第一版只输出 `xt=urn:btih:` 和可选 `dn`，不内置 tracker；如果 infoHash 非法则返回 null，
 * 避免把不可用链接写入剪贴板、下载记录或备份。
 */
object MagnetUriBuilder {
    /** 根据 infoHash 和标题构造 magnet URI；infoHash 非法时返回 null。 */
    fun build(infoHash: String, displayName: String? = null): String? {
        val normalizedHash = MagnetInfoHashNormalizer.normalize(infoHash) ?: return null
        val safeName = displayName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20") }
        return buildString {
            append("magnet:?xt=urn:btih:")
            append(normalizedHash)
            if (safeName != null) {
                append("&dn=")
                append(safeName)
            }
        }
    }
}
