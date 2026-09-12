package com.cla.clip.feature.torrent.search.provider.builtin.xxxclub

import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl

/** XXXClub 搜索与浏览固定路径构造器。 */
internal object XXXClubUrlBuilder {
    /** manifest 审计通过的主站根地址。 */
    const val BASE_URL = "https://xxxclub.to"

    /** 将查询作为单个路径段编码，禁止控制字符改变请求结构。 */
    fun searchUrl(
        /** 原始用户查询。 */ rawQuery: String,
    ): String {
        /** query 是删除控制字符并裁剪边界后的路径值。 */
        val query = sanitizeProviderQuery(rawQuery)
        return BASE_URL.toHttpUrl().newBuilder()
            .addPathSegments("torrents/search/all")
            .addPathSegment(query)
            .build()
            .toString()
    }

    /** 返回固定全部分类最新列表地址。 */
    fun latestUrl(): String = "$BASE_URL/torrents/browse/all"

    /** 返回固定热门前一百地址。 */
    fun topUrl(): String = "$BASE_URL/torrents/top100"
}
