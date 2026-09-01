package com.cla.clip.feature.torrent.search.provider.builtin.anilibria

import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 集中构造固定 AniLibria URL，保证查询和路径都由 OkHttp `HttpUrl` 编码。 */
internal object AniLibriaUrlBuilder {
    /** AniLibria API 的 HTTPS 根地址。 */
    private const val API_BASE_URL = "https://anilibria.top/api/v1"
    /** AniLibria release 详情的 HTTPS 展示根地址，Provider 描述符也复用该常量。 */
    internal const val DISPLAY_BASE_URL = "https://www.anilibria.top"

    /** 构造 release 搜索 URL，原始查询不通过字符串拼接。 */
    fun buildSearchUrl(query: String): String = API_BASE_URL.toHttpUrl().newBuilder()
        .addPathSegments("app/search/releases")
        .addQueryParameter("query", sanitizeProviderQuery(query))
        .build()
        .toString()

    /** 构造单 release torrent 列表 URL，ID 作为独立 path segment。 */
    fun buildReleaseTorrentsUrl(releaseId: Long): String = API_BASE_URL.toHttpUrl().newBuilder()
        .addPathSegments("anime/torrents/release")
        .addPathSegment(releaseId.toString())
        .build()
        .toString()

    /** 构造 release 详情 URL，alias 作为独立 path segment 以阻断路径注入。 */
    fun buildReleaseDetailsUrl(alias: String): String = DISPLAY_BASE_URL.toHttpUrl().newBuilder()
        .addPathSegments("anime/releases/release")
        .addPathSegment(alias)
        .build()
        .toString()
}
