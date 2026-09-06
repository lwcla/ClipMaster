package com.cla.clip.feature.torrent.search.provider.builtin.yts

import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Yts 固定 API 与页面 URL 构造器。 */
internal object YtsUrlBuilder {
    /** 固定展示页根地址。 */
    const val PAGE_BASE_URL = "https://yts.bz"

    /** API 根地址只允许固定 accel host 与 v2 路径。 */
    private val apiBaseUrl = "https://movies-api.accel.li/api/v2".toHttpUrl()

    /** 构造带 limit=50 的搜索请求。 */
    fun searchUrl(
        /** 原始搜索词先删除控制字符。 */
        query: String,
    ): String = apiBaseUrl.newBuilder()
        .addPathSegment("list_movies.json")
        .addQueryParameter("query_term", sanitizeProviderQuery(query))
        .addQueryParameter("limit", "50")
        .build()
        .toString()

    /** 构造每次独立调用的 latest/top 列表请求。 */
    fun latestUrl(): String = apiBaseUrl.newBuilder()
        .addPathSegment("list_movies.json")
        .build()
        .toString()

    /** 构造详情 API 请求，movie ID 必须是有界正整数。 */
    fun detailsUrl(
        /** 严格页面 token 提取出的电影 ID。 */
        movieId: Long,
    ): String {
        if (movieId <= 0L) {
            throw HttpDeniedException()
        }
        return apiBaseUrl.newBuilder()
            .addPathSegment("movie_details.json")
            .addQueryParameter("movie_id", movieId.toString())
            .addQueryParameter("with_images", "true")
            .build()
            .toString()
    }
}
