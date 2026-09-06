package com.cla.clip.feature.torrent.search.provider.builtin.rutor

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Rutor 固定 search/browse/top URL 构造器。 */
internal class RutorUrlBuilder {
    /** 构造完整短语且按做种数排序的搜索路径。 */
    fun searchUrl(
        /** 原始用户查询。 */
        query: String,
        /** 固定类别 ID。 */
        categoryId: Int,
    ): String = BASE_URL.toHttpUrl().newBuilder()
        .addPathSegment("search")
        .addPathSegment("0")
        .addPathSegment(categoryId.toString())
        .addPathSegment("010")
        .addPathSegment("2")
        .addPathSegment(sanitizeProviderQuery(query))
        .build()
        .toString()

    /** 构造按固定分类浏览的最新路径。 */
    fun latestUrl(
        /** 固定类别 ID。 */
        categoryId: Int,
    ): String = BASE_URL.toHttpUrl().newBuilder()
        .addPathSegment("browse")
        .addPathSegment("0")
        .addPathSegment(categoryId.toString())
        .addPathSegment("0")
        .addPathSegment("0")
        .build()
        .toString()

    /** 构造与调用分类无关的固定热门路径。 */
    fun topUrl(): String = BASE_URL.toHttpUrl().newBuilder()
        .addPathSegment("top")
        .build()
        .toString()

    /** Rutor 固定地址。 */
    companion object {
        /** manifest 审计通过的站点根地址。 */
        const val BASE_URL = "https://rutor.info"
    }
}

/** Rutor 请求与产出链接的精确同站策略。 */
internal class RutorUrlPolicy {
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
        private const val HOST = "rutor.info"
    }
}
