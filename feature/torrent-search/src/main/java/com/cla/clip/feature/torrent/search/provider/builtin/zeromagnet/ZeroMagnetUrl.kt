package com.cla.clip.feature.torrent.search.provider.builtin.zeromagnet

import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 0Magnet 固定 URL 构造器。 */
internal object ZeroMagnetUrlBuilder {
    /** 审计通过根地址。 */
    const val BASE_URL = "https://9mag.net"
    /** 搜索词通过结构化 query 参数编码。 */
    fun searchUrl(
        /** 原始搜索词。 */
        query: String,
    ): String = BASE_URL.toHttpUrl().newBuilder().addPathSegment("search").addQueryParameter("q", sanitizeProviderQuery(query)).build().toString()
}
/** 0Magnet 精确同站 URL 策略。 */
internal class ZeroMagnetUrlPolicy {
    /** 公共 URL 安全策略。 */
    private val delegate = BuiltinProviderUrlPolicy(setOf("9mag.net"))
    /** 请求前严格校验。 */
    fun requireUrl(
        /** 不可信地址。 */
        url: String,
    ): String = delegate.requireHttpsUrl(url).toString()
    /** 可选详情链接非法时降级为空。 */
    fun optionalUrl(
        /** 已验证页面基址。 */
        pageUrl: String,
        /** 不可信候选。 */
        candidate: String?,
    ): String? = runCatching { delegate.resolveOptionalHttpsUrl(pageUrl, candidate) }.getOrNull()
}
