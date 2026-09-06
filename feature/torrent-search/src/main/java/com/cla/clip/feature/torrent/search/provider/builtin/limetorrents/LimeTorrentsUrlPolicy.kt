package com.cla.clip.feature.torrent.search.provider.builtin.limetorrents

import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** LimeTorrents 双站详情与 iTorrents 离线 marker 的独立安全策略。 */
internal class LimeTorrentsUrlPolicy {
    /** 仅站点两个 host 可访问网络。 */ private val sitePolicy = BuiltinProviderUrlPolicy(setOf("limetorrents.fun", "www.limetorrents.lol"))

    /** 网络前要求站点 HTTPS 默认端口且禁止用户信息。 */
    fun requireDetailsUrl(/** 待访问详情或列表 URL。 */ url: String): String {
        /** parsedUrl 补充通用 policy 的端口和用户信息约束。 */ val parsedUrl = url.toHttpUrlOrNull() ?: throw HttpDeniedException()
        if (parsedUrl.port != 443 || parsedUrl.username.isNotEmpty() || parsedUrl.password.isNotEmpty()) throw HttpDeniedException()
        return sitePolicy.requireHttpsUrl(url).toString()
    }

    /** 相对可选详情非法时只降级为空。 */
    fun optionalDetailsUrl(
        /** 当前已验证页面基址。 */ pageUrl: String,
        /** 未信任 href。 */ candidate: String?,
    ): String? = runCatching {
        /** resolved 先由通用策略完成相对解析和 host/scheme 校验。 */ val resolved = sitePolicy.resolveOptionalHttpsUrl(pageUrl, candidate) ?: return null
        requireDetailsUrl(resolved)
    }.getOrNull()

    /** 从 HTTP/HTTPS marker 离线提取 hash；绝不返回或访问原始地址。 */
    fun markerInfoHash(/** 未信任 marker href。 */ candidate: String?): String? {
        /** parsedUrl 只用于离线结构检查。 */ val parsedUrl = candidate?.toHttpUrlOrNull() ?: return null
        /** expectedPort 仅允许 scheme 对应默认端口。 */ val expectedPort = if (parsedUrl.isHttps) 443 else if (parsedUrl.scheme == "http") 80 else return null
        if (parsedUrl.host != "itorrents.net" || parsedUrl.port != expectedPort || parsedUrl.username.isNotEmpty() || parsedUrl.password.isNotEmpty()) return null
        /** segments 必须精确为 torrent/文件名。 */ val segments = parsedUrl.pathSegments
        if (segments.size != 2 || segments[0] != "torrent") return null
        /** fileName 必须以固定 torrent 后缀结束；name 是待检查的路径文件名。 */
        val fileName = segments[1].takeIf { name -> name.endsWith(".torrent", ignoreCase = false) } ?: return null
        return MagnetInfoHashParser.normalize(fileName.removeSuffix(".torrent"))
    }

    /** 用审核 host 和规范 hash 重建唯一 HTTPS 下载地址，丢弃不可信 query。 */
    fun canonicalDownloadUrl(/** 已规范化 hash。 */ infoHash: String): String = "https://itorrents.net/torrent/$infoHash.torrent"
}
