package com.cla.clip.feature.torrent.search.provider.builtin.uindex

import com.cla.clip.feature.torrent.search.model.contract.Category

/** UIndex 请求编号与页面文本的单一映射入口。 */
internal object UIndexCategoryMapper {
    /** 十类请求完整矩阵，ALL 与 BOOKS 均按固定上源回退 0。 */
    fun requestId(
        /** 当前领域分类。 */ category: Category,
    ): String = when (category) {
        Category.ANIME -> "7"
        Category.APPS -> "5"
        Category.GAMES -> "3"
        Category.MOVIES -> "1"
        Category.MUSIC -> "4"
        Category.PORN -> "6"
        Category.SERIES -> "2"
        Category.OTHER -> "8"
        Category.ALL, Category.BOOKS -> "0"
    }

    /** 页面类别先 trim，再按固定大小写映射；未知值回退 OTHER。 */
    fun fromPageText(
        /** 页面类别文本。 */ rawCategory: String,
    ): Category = when (rawCategory.trim()) {
        "Anime" -> Category.ANIME
        "Apps" -> Category.APPS
        "Games" -> Category.GAMES
        "Movies" -> Category.MOVIES
        "Music" -> Category.MUSIC
        "XXX" -> Category.PORN
        "TV" -> Category.SERIES
        else -> Category.OTHER
    }
}
