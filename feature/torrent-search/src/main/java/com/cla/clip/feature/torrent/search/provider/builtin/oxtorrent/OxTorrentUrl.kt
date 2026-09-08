package com.cla.clip.feature.torrent.search.provider.builtin.oxtorrent

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.FrenchTorrentCategoryMapper
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl

/** OxTorrent 固定请求路径构造器。 */
internal object OxTorrentUrlBuilder {
    /** 审计通过的站点根地址。 */
    const val BASE_URL = "https://oxtorrent.co"
    /** 搜索路径按类别和清洗后的查询逐段编码。 */
    fun searchUrl(
        /** 原始搜索词。 */
        query: String,
        /** 可选站点类别。 */
        category: Category,
    ): String {
        /** builder 只添加结构化路径段，避免查询逃逸。 */
        val builder = BASE_URL.toHttpUrl().newBuilder().addPathSegment("recherche")
        /** categorySlug 只在站点支持时出现。 */
        val categorySlug = FrenchTorrentCategoryMapper.toSlug(category)
        if (categorySlug != null) builder.addPathSegment(categorySlug)
        return builder.addPathSegment(sanitizeProviderQuery(query)).build().toString()
    }
    /** 最新页只为支持类别生成地址。 */
    fun latestUrl(
        /** 浏览类别。 */
        category: Category,
    ): String? = FrenchTorrentCategoryMapper.toSlug(category)?.let {
        /** categorySlug 已来自固定映射。 */
        categorySlug -> BASE_URL.toHttpUrl().newBuilder().addPathSegment("torrents").addPathSegment(categorySlug).build().toString()
    }
    /** 热门页固定且忽略类别。 */
    fun topUrl(): String = "$BASE_URL/top"
}

/** OxTorrent 精确同站 HTTPS 策略。 */
internal class OxTorrentUrlPolicy {
    /** 公共策略已经校验 HTTPS、默认端口与空 userinfo。 */
    private val delegate = BuiltinProviderUrlPolicy(setOf("oxtorrent.co"))
    /** 请求前严格校验 URL。 */
    fun requireUrl(
        /** 不可信地址。 */
        url: String,
    ): String = delegate.requireHttpsUrl(url).toString()
    /** 可选同站链接非法时降级为空。 */
    fun optionalUrl(
        /** 已验证页面基址。 */
        pageUrl: String,
        /** 不可信链接候选。 */
        candidate: String?,
    ): String? = runCatching { delegate.resolveOptionalHttpsUrl(pageUrl, candidate) }.getOrNull()
}
