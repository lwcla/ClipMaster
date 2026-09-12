package com.cla.clip.feature.torrent.search.provider.builtin.common

import com.cla.clip.feature.torrent.search.input.sanitizeTorrentSearchText
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 内置 Provider 的 HTTPS 与精确 host 白名单；所有外部详情、跳转和解析链接
 * 必须在进入网络层前通过此边界，拒绝异常不携带原始 URL。
 */
internal class BuiltinProviderUrlPolicy(
    /** manifest 审计通过的精确小写 host，不自动放行子域名。 */
    allowedHosts: Set<String>,
) {
    /** 规范化后的精确 host 集合，构造后只读。 */
    private val normalizedAllowedHosts = allowedHosts.mapTo(linkedSetOf()) { host -> host.lowercase() }

    /** 拒绝空白名单，避免 Provider 误配时将网络边界降级为全放行。 */
    init {
        require(normalizedAllowedHosts.isNotEmpty())
    }

    /** 校验并返回无用户信息、默认端口、精确白名单 host 的 HTTPS URL；失败时统一抛出低敏拒绝异常。 */
    fun requireHttpsUrl(url: String): HttpUrl {
        /** OkHttp 解析结果只用于安全判定，不进入日志或异常正文。 */
        val parsedUrl = url.toHttpUrlOrNull() ?: throw HttpDeniedException()
        /** 内置来源固定访问标准 HTTPS 服务，禁止 authority 注入凭据或切换到同 host 的其他端口。 */
        if (
            !parsedUrl.isHttps ||
            parsedUrl.host.lowercase() !in normalizedAllowedHosts ||
            parsedUrl.port != 443 ||
            parsedUrl.username.isNotEmpty() ||
            parsedUrl.password.isNotEmpty()
        ) {
            throw HttpDeniedException()
        }
        return parsedUrl
    }

    /** 相对链接按已校验基址解析，空值返回 null，跨 host 或非 HTTPS 结果直接拒绝。 */
    fun resolveOptionalHttpsUrl(baseUrl: String, candidate: String?): String? {
        if (candidate.isNullOrBlank()) return null
        /** 基址先通过同一白名单，防止调用方注入非受信解析上下文。 */
        val trustedBaseUrl = requireHttpsUrl(baseUrl)
        /** OkHttp 按 RFC 解析相对路径，无法解析时使用稳定拒绝失败。 */
        val resolvedUrl = trustedBaseUrl.resolve(candidate) ?: throw HttpDeniedException()
        return requireHttpsUrl(resolvedUrl.toString()).toString()
    }
}

/** 删除查询中的 Unicode 控制字符并裁剪边界空白；参数编码仍交给 HttpUrl。 */
internal fun sanitizeProviderQuery(rawQuery: String): String = sanitizeTorrentSearchText(rawQuery)
