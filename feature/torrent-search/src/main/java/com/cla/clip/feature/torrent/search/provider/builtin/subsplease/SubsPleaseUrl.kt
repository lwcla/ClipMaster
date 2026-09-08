package com.cla.clip.feature.torrent.search.provider.builtin.subsplease

import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 已验证的 SubsPlease 详情请求，参数只在内存用于精确 show 匹配。 */
internal data class SubsPleaseDetailsRequest(
    /** 规范化的同站详情页 URL。 */ val pageUrl: String,
    /** 非空剧集编号。 */ val episode: String,
    /** 非空分辨率。 */ val resolution: String,
)

/** SubsPlease 结构化 URL 构造与详情参数校验。 */
internal object SubsPleaseUrlBuilder {
    /** manifest 审计通过的固定站点根地址。 */
    const val BASE_URL = "https://subsplease.org"

    /** 构造搜索 API，查询值由 HttpUrl 编码且控制字符先清除。 */
    fun searchUrl(
        /** 原始搜索词。 */ rawQuery: String,
    ): String = apiBuilder(trailingSlash = false)
        .addQueryParameter("f", "search")
        .addQueryParameter("tz", "$")
        .addQueryParameter("s", sanitizeProviderQuery(rawQuery))
        .build()
        .toString()

    /** 构造固定最新 API。 */
    fun latestUrl(): String = apiBuilder(trailingSlash = true)
        .addQueryParameter("f", "latest")
        .addQueryParameter("tz", "$")
        .build()
        .toString()

    /** 构造按 show ID 查询的 API。 */
    fun showUrl(
        /** 从静态详情页读取的非空 show ID。 */ showId: String,
    ): String = apiBuilder(trailingSlash = true)
        .addQueryParameter("f", "show")
        .addQueryParameter("tz", "$")
        .addQueryParameter("sid", showId)
        .build()
        .toString()

    /** 为列表结果附加唯一 ep/res 参数，非法 page 候选只跳当前项。 */
    fun detailsUrl(
        /** 同站相对或绝对 page 候选。 */ pageCandidate: String,
        /** 剧集编号。 */ episode: String,
        /** 分辨率。 */ resolution: String,
        /** 与 Provider 共用的同站 policy。 */ urlPolicy: BuiltinProviderUrlPolicy,
    ): String? {
        /** resolvedUrl 必须同站且不能携带已有查询或片段污染。 */
        val resolvedUrl = runCatching { urlPolicy.resolveOptionalHttpsUrl(BASE_URL, pageCandidate) }.getOrNull()
            ?.toHttpUrl()
            ?: return null
        return resolvedUrl.newBuilder()
            .query(null)
            .fragment(null)
            .addQueryParameter("ep", episode)
            .addQueryParameter("res", resolution)
            .build()
            .toString()
    }

    /** 校验详情 authority 和精确唯一参数；失败统一抛无正文拒绝异常。 */
    fun parseDetailsRequest(
        /** 外部详情 URL。 */ detailsPageUrl: String,
        /** 固定同站 URL policy。 */ urlPolicy: BuiltinProviderUrlPolicy,
    ): SubsPleaseDetailsRequest {
        /** trustedUrl 先拒绝非 HTTPS、跨站、userinfo 和异常端口。 */
        val trustedUrl = urlPolicy.requireHttpsUrl(detailsPageUrl)
        /** episodeValues 必须恰好一个非空值。 */
        val episodeValues = trustedUrl.queryParameterValues("ep")
        /** resolutionValues 必须恰好一个非空值。 */
        val resolutionValues = trustedUrl.queryParameterValues("res")
        /** queryNames 禁止额外参数改变固定详情选择语义。 */
        val queryNames = trustedUrl.queryParameterNames
        /** episode 是唯一非空剧集参数。 */
        val episode = episodeValues.singleOrNull()?.takeIf(String::isNotBlank) ?: throw HttpDeniedException()
        /** resolution 是唯一非空分辨率参数。 */
        val resolution = resolutionValues.singleOrNull()?.takeIf(String::isNotBlank) ?: throw HttpDeniedException()
        if (queryNames != setOf("ep", "res")) throw HttpDeniedException()
        return SubsPleaseDetailsRequest(trustedUrl.toString(), episode, resolution)
    }

    /** 生成带或不带尾斜杠的固定 API builder。 */
    private fun apiBuilder(
        /** true 保留上源 `/api/`，false 使用 `/api`。 */ trailingSlash: Boolean,
    ): okhttp3.HttpUrl.Builder {
        /** builder 从受信常量构造。 */
        val builder = BASE_URL.toHttpUrl().newBuilder().addPathSegment("api")
        if (trailingSlash) builder.addPathSegment("")
        return builder
    }
}
