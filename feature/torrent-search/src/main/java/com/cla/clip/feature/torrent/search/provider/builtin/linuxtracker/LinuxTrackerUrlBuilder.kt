package com.cla.clip.feature.torrent.search.provider.builtin.linuxtracker

import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl

/** LinuxTracker 固定列表查询 URL 构造器。 */
internal class LinuxTrackerUrlBuilder {
    /** 用户查询由 HttpUrl 编码为单个 query 参数。 */
    fun searchUrl(/** 原始用户查询。 */ query: String): String = build(sanitizeProviderQuery(query))
    /** latest/top 共用空查询形状，但调用方每次重新执行网络。 */
    fun latestUrl(): String = build("")
    /** 按固定参数顺序构造列表 URL。 */
    private fun build(/** 已清洗查询。 */ query: String): String = BASE_URL.toHttpUrl().newBuilder()
        .addPathSegment("index.php").addQueryParameter("page", "torrents").addQueryParameter("search", query)
        .addQueryParameter("category", "0").addQueryParameter("active", "0").build().toString()
    /** 固定地址常量。 */
    private companion object { /** 唯一请求根地址。 */ private const val BASE_URL = "https://linuxtracker.org" }
}
