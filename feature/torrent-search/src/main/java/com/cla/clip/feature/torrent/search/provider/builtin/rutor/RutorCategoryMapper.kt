package com.cla.clip.feature.torrent.search.provider.builtin.rutor

import com.cla.clip.feature.torrent.search.model.contract.Category
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Rutor 固定请求 ID 与详情类别路径映射器。 */
internal object RutorCategoryMapper {
    /** 将调用分类映射为固定请求 ID，未知 PORN 回退全部分类。 */
    fun requestId(
        /** 调用方分类。 */
        category: Category,
    ): Int = when (category) {
        Category.ANIME -> 10
        Category.APPS -> 9
        Category.BOOKS -> 11
        Category.GAMES -> 8
        Category.MOVIES -> 1
        Category.MUSIC -> 2
        Category.OTHER -> 3
        Category.SERIES -> 4
        else -> 0
    }

    /** 从已经解析的完整 URL 最后一个 path segment 映射详情分类。 */
    fun detailsCategory(
        /** 已经过同站策略的类别 URL。 */
        categoryUrl: String?,
    ): Category {
        /** rawCategory 只读取结构化 path，不受 query 中斜线污染。 */
        val rawCategory = categoryUrl?.toHttpUrlOrNull()?.pathSegments?.lastOrNull()
        return when (rawCategory) {
            "kino", "nashe_kino", "multiki" -> Category.MOVIES
            "seriali", "nashi_seriali", "tv" -> Category.SERIES
            "anime" -> Category.ANIME
            "audio" -> Category.MUSIC
            "games" -> Category.GAMES
            "soft" -> Category.APPS
            "knigi" -> Category.BOOKS
            else -> Category.OTHER
        }
    }
}
