package com.cla.clip.feature.torrent.search.provider.builtin.therarbag

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl

/** TheRarBg 固定 path 语义和分类映射。 */
internal object TheRarBgUrlBuilder {
    /** manifest 审计通过的根地址。 */
    const val BASE_URL = "https://therarbg.com"

    /** 构造关键词搜索路径。 */
    fun searchUrl(
        /** 原始搜索词。 */ rawQuery: String,
        /** 可选分类。 */ category: Category,
    ): String = buildPath("keywords:${sanitizeProviderQuery(rawQuery)}", category)

    /** 构造最近两日路径。 */
    fun latestUrl(
        /** 可选分类。 */ category: Category,
    ): String = buildPath("time:2D", category)

    /** 构造最近两日按做种降序路径。 */
    fun topUrl(
        /** 可选分类。 */ category: Category,
    ): String = buildPath("time:2D:order:-se", category)

    /** 把筛选语义作为单个路径段交给 HttpUrl 编码。 */
    private fun buildPath(
        /** 关键词或时间筛选主体。 */ filter: String,
        /** ALL 以外追加的分类。 */ category: Category,
    ): String {
        /** categorySuffix 保持固定冒号协议。 */
        val categorySuffix = TheRarBgCategoryMapper.toPathName(category)?.let { name -> ":category:$name" }.orEmpty()
        return BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("get-posts")
            .addPathSegment(filter + categorySuffix)
            .build()
            .toString()
    }
}

/** TheRarBg 请求与页面分类双向映射。 */
internal object TheRarBgCategoryMapper {
    /** ALL 不写路径，其余九类输出固定上源名称。 */
    fun toPathName(
        /** 领域分类。 */ category: Category,
    ): String? = when (category) {
        Category.ALL -> null
        Category.ANIME -> "Anime"
        Category.APPS -> "Apps"
        Category.BOOKS -> "Books"
        Category.GAMES -> "Games"
        Category.MOVIES -> "Movies"
        Category.MUSIC -> "Music"
        Category.PORN -> "XXX"
        Category.SERIES -> "Tv"
        Category.OTHER -> "Other"
    }

    /** 未知页面值保守归为 OTHER。 */
    fun fromPageText(
        /** 页面分类文本。 */ rawCategory: String,
    ): Category = when (rawCategory.trim()) {
        "Anime" -> Category.ANIME
        "Apps" -> Category.APPS
        "Books" -> Category.BOOKS
        "Games" -> Category.GAMES
        "Movies" -> Category.MOVIES
        "Music" -> Category.MUSIC
        "XXX" -> Category.PORN
        "Tv" -> Category.SERIES
        else -> Category.OTHER
    }
}
