package com.cla.clip.feature.torrent.search.provider.builtin.uindex

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl

/** UIndex search/latest/top 结构化 URL 构造器。 */
internal class UIndexUrlBuilder {
    /** 构造带查询和完整类别矩阵的搜索地址。 */
    fun searchUrl(
        /** 原始用户查询。 */ query: String,
        /** 当前领域分类。 */ category: Category,
    ): String = searchBuilder(category)
        .addQueryParameter("search", sanitizeProviderQuery(query))
        .build()
        .toString()

    /** 构造仅含 c 参数的最新地址。 */
    fun latestUrl(
        /** 当前领域分类。 */ category: Category,
    ): String = searchBuilder(category).build().toString()

    /** 构造固定 24h 和 c 参数的热门地址。 */
    fun topUrl(
        /** 当前领域分类。 */ category: Category,
    ): String = BASE_URL.toHttpUrl().newBuilder()
        .addPathSegment("top.php")
        .addQueryParameter("t", "24h")
        .addQueryParameter("c", UIndexCategoryMapper.requestId(category))
        .build()
        .toString()

    /** 建立 search.php 与固定类别参数，供 search/latest 复用。 */
    private fun searchBuilder(
        /** 当前领域分类。 */ category: Category,
    ) = BASE_URL.toHttpUrl().newBuilder()
        .addPathSegment("search.php")
        .addQueryParameter("c", UIndexCategoryMapper.requestId(category))

    /** URL 构造固定站点。 */ private companion object { /** 审核根地址。 */ private const val BASE_URL = "https://uindex.org" }
}

/** UIndex 同站网络输入和可选页面输出策略。 */
internal class UIndexUrlPolicy {
    /** 仅允许精确 uindex.org。 */ private val delegate = BuiltinProviderUrlPolicy(setOf("uindex.org"))

    /** 网络前要求审核 HTTPS authority。 */
    fun requireSiteUrl(
        /** 待验证地址。 */ url: String,
    ): String = delegate.requireHttpsUrl(url).toString()

    /** 相对可选链接解析失败或越界时降级为空。 */
    fun optionalSiteUrl(
        /** 已验证页面基址。 */ pageUrl: String,
        /** 未信任 href/src。 */ candidate: String?,
    ): String? = runCatching { delegate.resolveOptionalHttpsUrl(pageUrl, candidate) }.getOrNull()
}
