package com.cla.clip.feature.torrent.search.provider.builtin.torrent9

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.FrenchTorrentCategoryMapper
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Torrent9 固定请求路径构造器。 */
internal object Torrent9UrlBuilder {
    /** 审计通过的根地址。 */
    const val BASE_URL = "https://www6.torrent9.to"
    /** 搜索将 query 与 .html 作为同一个编码路径段。 */
    fun searchUrl(
        /** 原始查询。 */
        query: String,
        /** 可选固定类别。 */
        category: Category,
    ): String {
        /** builder 只组装结构化路径段。 */
        val builder = BASE_URL.toHttpUrl().newBuilder().addPathSegment("search_torrent")
        /** categorySlug 只在固定六类中出现。 */
        val categorySlug = FrenchTorrentCategoryMapper.toSlug(category)
        if (categorySlug != null) builder.addPathSegment(categorySlug)
        /** querySegment 保留扩展名并作为单一段编码。 */
        val querySegment = sanitizeProviderQuery(query) + ".html"
        return builder.addPathSegment(querySegment).build().toString()
    }
    /** 最新页只支持固定六类。 */
    fun latestUrl(
        /** 浏览类别。 */
        category: Category,
    ): String? = FrenchTorrentCategoryMapper.toSlug(category)?.let {
        /** slug 来自固定映射。 */
        slug -> BASE_URL.toHttpUrl().newBuilder().addPathSegment("torrents_" + slug + ".html").build().toString()
    }
    /** 热门页固定且忽略类别。 */
    fun topUrl(): String = BASE_URL + "/top_torrent.html"
}

/** Torrent9 精确同站 URL 策略。 */
internal class Torrent9UrlPolicy {
    /** 公共策略处理 HTTPS、host、port 与 userinfo。 */
    private val delegate = BuiltinProviderUrlPolicy(setOf("www6.torrent9.to"))
    /** 请求前严格校验。 */
    fun requireUrl(
        /** 不可信地址。 */
        url: String,
    ): String = delegate.requireHttpsUrl(url).toString()
    /** 可选链接非法时降级为空。 */
    fun optionalUrl(
        /** 已验证页面地址。 */
        pageUrl: String,
        /** 不可信候选。 */
        candidate: String?,
    ): String? = runCatching { delegate.resolveOptionalHttpsUrl(pageUrl, candidate) }.getOrNull()
}
