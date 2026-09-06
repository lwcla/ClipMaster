package com.cla.clip.feature.torrent.search.provider.builtin.limetorrents

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl

/** LimeTorrents 查询与分类结构化 URL 构造器。 */
internal class LimeTorrentsUrlBuilder {
    /** 搜索查询作为单个 path segment 编码，禁止斜杠改变路径层级。 */
    fun searchUrl(
        /** 原始用户查询。 */ query: String,
        /** 搜索分类，三个不支持 browse 的分类回退 all。 */ category: Category,
    ): String = BASE_URL.toHttpUrl().newBuilder()
        .addPathSegment("search").addPathSegment(searchCategory(category)).addPathSegment(sanitizeProviderQuery(query))
        .addPathSegment("date").addPathSegment("1").addPathSegment("").build().toString()

    /** 构造 latest 固定 browse 路径。 */
    fun latestUrl(/** 已确认支持 browse 的分类。 */ category: Category): String = "$BASE_URL/browse-torrents/${browseCategory(category)}/"

    /** 构造 top 固定路径。 */
    fun topUrl(/** 已确认支持 browse 的分类。 */ category: Category): String = "$BASE_URL/cat_top/16/${browseCategory(category)}/"

    /** 映射搜索小写分类段。 */
    private fun searchCategory(/** 当前领域分类。 */ category: Category): String = when (category) {
        Category.ALL, Category.BOOKS, Category.PORN -> "all"; Category.ANIME -> "anime"; Category.APPS -> "applications"
        Category.GAMES -> "games"; Category.MOVIES -> "movies"; Category.MUSIC -> "music"; Category.SERIES -> "tv"; Category.OTHER -> "other"
    }

    /** 映射 browse 精确大小写分类段；调用方保证输入受支持。 */
    private fun browseCategory(/** 当前支持分类。 */ category: Category): String = when (category) {
        Category.ANIME -> "Anime"; Category.APPS -> "Applications"; Category.GAMES -> "Games"; Category.MOVIES -> "Movies"
        Category.MUSIC -> "Music"; Category.SERIES -> "TV-shows"; Category.OTHER -> "Other"
        else -> throw IllegalArgumentException("不支持的 LimeTorrents 浏览分类")
    }

    /** 固定主页常量。 */ private companion object { /** 搜索与浏览根地址。 */ private const val BASE_URL = "https://limetorrents.fun" }
}
