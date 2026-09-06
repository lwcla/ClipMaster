package com.cla.clip.feature.torrent.search.provider.builtin.torrentscsv

import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl

/** TorrentsCSV 固定 API URL 构造器，所有查询编码交给 OkHttp。 */
internal object TorrentsCsvUrlBuilder {
    /** 固定展示与 API 根地址。 */
    const val BASE_URL = "https://torrents-csv.com"

    /** 构造唯一 search 端点，控制字符不会进入请求。 */
    fun searchUrl(
        /** 原始用户查询，不进入日志或持久状态。 */
        query: String,
    ): String = BASE_URL.toHttpUrl()
        .newBuilder()
        .addPathSegment("service")
        .addPathSegment("search")
        .addQueryParameter("q", sanitizeProviderQuery(query))
        .build()
        .toString()
}
