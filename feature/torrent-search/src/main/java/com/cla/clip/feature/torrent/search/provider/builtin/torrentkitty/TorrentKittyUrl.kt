package com.cla.clip.feature.torrent.search.provider.builtin.torrentkitty

import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl

/** TorrentKitty 固定 search URL 构造器。 */
internal class TorrentKittyUrlBuilder {
    /** 查询作为单个 path segment 清洗并编码。 */
    fun searchUrl(
        /** 原始用户查询。 */
        query: String,
    ): String = BASE_URL.toHttpUrl().newBuilder()
        .addPathSegment("search")
        .addPathSegment(sanitizeProviderQuery(query))
        .build()
        .toString()

    /** TorrentKitty 固定地址。 */
    companion object {
        /** manifest 审计通过的站点根地址。 */
        const val BASE_URL = "https://torrentkitty.tv"
    }
}

/** TorrentKitty 请求与产出链接的精确同站策略。 */
internal class TorrentKittyUrlPolicy {
    /** 公共策略统一校验 HTTPS、host、443 和无 userinfo。 */
    private val delegate = BuiltinProviderUrlPolicy(setOf(HOST))

    /** 网络前返回规范地址，非法输入抛低敏拒绝。 */
    fun requireUrl(
        /** 不可信详情或内部请求 URL。 */
        url: String,
    ): String = delegate.requireHttpsUrl(url).toString()

    /** 可选链接按真实 page base 解析，非法候选独立降级。 */
    fun optionalUrl(
        /** 已审核页面基址。 */
        pageUrl: String,
        /** 不可信相对或绝对候选。 */
        candidate: String?,
    ): String? = runCatching {
        delegate.resolveOptionalHttpsUrl(pageUrl, candidate)
    }.getOrNull()

    /** URL 策略常量。 */
    private companion object {
        /** 唯一允许的精确 host。 */
        private const val HOST = "torrentkitty.tv"
    }
}
