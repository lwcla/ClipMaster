package com.cla.clip.feature.torrent.search.provider.builtin.internetarchive

import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URI

/** IA 专属标识与 API 路径边界；避免外部详情变成另一个接口请求。 */
internal object InternetArchiveUrlBuilder {
    /** 仅允许 manifest 的精确 HTTPS host。 */
    private val policy = BuiltinProviderUrlPolicy(setOf("archive.org"))
    /** 固定 API 根地址，无远程配置。 */
    private val baseUrl = "https://archive.org".toHttpUrl()

    /** 单个标识保持原文编码，禁止空/点段/路径分隔和控制字符。 */
    fun validIdentifier(
        /** 外部资源标识，必须满足单路径段与长度约束。 */
        identifier: String,
    ): Boolean = identifier.isNotBlank() && identifier.length <= 512 &&
        identifier != "." && identifier != ".." &&
        // character 是外部标识字符，反斜杠同样不能改变解析路径。
        identifier.none { character -> character == '/' || character == '\\' || character.isISOControl() }

    /** 搜索产出详情链接；非法标识返回 null，让调用方只跳过单条。 */
    fun detailsUrl(
        /** 外部资源标识，必须满足单路径段与长度约束。 */
        identifier: String,
    ): String? = if (validIdentifier(identifier)) {
        baseUrl.newBuilder().addPathSegment("details").addPathSegment(identifier).build().toString()
    } else null

    /** 外部详情必须精确两段且无额外 URL 结构；失败在网络前抛低敏拒绝。 */
    fun requireDetailsIdentifier(
        /** 未信任的详情地址，只读取允许的 HTTPS 与路径结构。 */
        url: String,
    ): String {
        /** HttpUrl 解码后的段用于拒绝编码斜杠、点段和控制符。 */
        val parsed = policy.requireHttpsUrl(url)
        /** 原始路径也必须只有两段，防止 HttpUrl 将额外点段静默折叠成合法 API 路由。 */
        val rawPath = runCatching { URI(url).rawPath }.getOrNull() ?: throw HttpDeniedException()
        if (rawPath.split('/').size != 3 || parsed.port != 443) throw HttpDeniedException()
        if (parsed.query != null || parsed.fragment != null || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() ||
            parsed.pathSegments.size != 2 || parsed.pathSegments[0] != "details") throw HttpDeniedException()
        /** 第二段为请求资源唯一身份，后续必须与 metadata.identifier 匹配。 */
        val identifier = parsed.pathSegments[1]
        if (!validIdentifier(identifier)) throw HttpDeniedException()
        return identifier
    }

    /** 已验证 identifier 单独编码，不允许落入另一 API 路径。 */
    fun metadataUrl(
        /** 外部资源标识，必须满足单路径段与长度约束。 */
        identifier: String,
    ): String {
        if (!validIdentifier(identifier)) throw HttpDeniedException()
        return baseUrl.newBuilder().addPathSegment("metadata").addPathSegment(identifier).build().toString()
    }

    /** 文件名作为一个独立路径段编码；空/点段/控制字符不能构造下载 URL。 */
    fun downloadUrl(
        /** 外部资源标识，必须满足单路径段与长度约束。 */
        identifier: String,
        /** 外部文件名，独立编码且拒绝空值和点段。 */
        filename: String,
    ): String? {
        if (!validIdentifier(identifier) || filename.isBlank() || filename == "." || filename == ".." ||
            // character 是文件名字符，仅编码不执行路径分隔语义；控制字符直接拒绝。
            filename.any { character -> character.isISOControl() }) return null
        return baseUrl.newBuilder().addPathSegment("download").addPathSegment(identifier).addPathSegment(filename).build().toString()
    }
}
