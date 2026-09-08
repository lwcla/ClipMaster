package com.cla.clip.feature.torrent.search.provider.builtin.torrentdownloads

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl

/** TorrentDownloads 搜索、最新与热门路径构造和分类映射。 */
internal object TorrentDownloadsUrlBuilder {
    /** manifest 审计通过的固定主站根地址。 */
    const val BASE_URL = "https://torrentdownloads.pro"

    /** 构造带固定数字分类和结构化查询的搜索地址。 */
    fun searchUrl(
        /** 原始搜索词。 */ rawQuery: String,
        /** 调用方分类。 */ category: Category,
    ): String {
        /** query 删除控制字符并裁剪边界。 */
        val query = sanitizeProviderQuery(rawQuery)
        return BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addPathSegment("")
            .addQueryParameter("s_cat", categoryId(category).toString())
            .addQueryParameter("search", query)
            .build()
            .toString()
    }

    /** 分类有专属 today 页面，其他分类回退 most-active。 */
    fun latestUrl(
        /** 浏览分类。 */ category: Category,
    ): String = categoryPathName(category)?.let { pathName ->
        BASE_URL.toHttpUrl().newBuilder()
            .addPathSegments("view/today")
            .addPathSegment("$pathName.html")
            .build()
            .toString()
    } ?: BASE_URL.toHttpUrl().newBuilder().addPathSegment("most-active").build().toString()

    /** 分类有专属 popular 页面，其他分类回退 most-seeded。 */
    fun topUrl(
        /** 浏览分类。 */ category: Category,
    ): String = categoryPathName(category)?.let { pathName ->
        BASE_URL.toHttpUrl().newBuilder()
            .addPathSegments("view/popular")
            .addPathSegment("$pathName.html")
            .build()
            .toString()
    } ?: BASE_URL.toHttpUrl().newBuilder().addPathSegment("most-seeded").build().toString()

    /** 搜索类别数字 ID，PORN 与 OTHER 延续固定上游 9 的保守回退。 */
    private fun categoryId(
        /** 调用方分类。 */ category: Category,
    ): Int = when (category) {
        Category.ALL -> 0
        Category.ANIME -> 1
        Category.BOOKS -> 2
        Category.GAMES -> 3
        Category.MOVIES -> 4
        Category.MUSIC -> 5
        Category.APPS -> 7
        Category.SERIES -> 8
        Category.PORN, Category.OTHER -> 9
    }

    /** 只有固定八类有 latest/top 路径。 */
    private fun categoryPathName(
        /** 调用方分类。 */ category: Category,
    ): String? = when (category) {
        Category.ANIME -> "Anime"
        Category.APPS -> "Software"
        Category.BOOKS -> "Books"
        Category.GAMES -> "Games"
        Category.MOVIES -> "Movies"
        Category.MUSIC -> "Music"
        Category.SERIES -> "TV_Shows"
        Category.OTHER -> "Other"
        Category.ALL, Category.PORN -> null
    }
}

/** TorrentDownloads 分类图标文件映射。 */
internal object TorrentDownloadsCategoryMapper {
    /** 从任意 URL 的最后路径段提取固定图标文件名，未知值归 OTHER。 */
    fun fromIconUrl(
        /** 页面图标 src。 */ iconUrl: String,
    ): Category = when (iconUrl.substringAfterLast('/')) {
        "menu_icon1.png" -> Category.ANIME
        "menu_icon2.png" -> Category.BOOKS
        "menu_icon3.png" -> Category.GAMES
        "menu_icon4.png" -> Category.MOVIES
        "menu_icon5.png" -> Category.MUSIC
        "menu_icon7.png" -> Category.APPS
        "menu_icon8.png" -> Category.SERIES
        else -> Category.OTHER
    }
}
