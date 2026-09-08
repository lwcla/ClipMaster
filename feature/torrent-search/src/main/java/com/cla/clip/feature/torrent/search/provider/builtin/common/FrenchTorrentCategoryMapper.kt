package com.cla.clip.feature.torrent.search.provider.builtin.common

import com.cla.clip.feature.torrent.search.model.contract.Category

/** 法语 Torrent 站点共用的固定类别 slug 映射。 */
internal object FrenchTorrentCategoryMapper {
    /** 将宿主类别转换为上游固定 slug；不支持的类别返回空。 */
    fun toSlug(
        /** 宿主搜索类别。 */
        category: Category,
    ): String? = when (category) {
        Category.APPS -> "logiciels"
        Category.BOOKS -> "ebook"
        Category.GAMES -> "jeux-pc"
        Category.MOVIES -> "films"
        Category.MUSIC -> "musique"
        Category.SERIES -> "series"
        else -> null
    }

    /** 将详情链接中的固定 slug 转回宿主类别，未知值归入其他。 */
    fun fromSlug(
        /** 已从固定路径提取的类别 slug。 */
        slug: String,
    ): Category = when (slug) {
        "logiciels" -> Category.APPS
        "ebook" -> Category.BOOKS
        "jeux-pc", "jeux-consoles" -> Category.GAMES
        "films" -> Category.MOVIES
        "musique" -> Category.MUSIC
        "series" -> Category.SERIES
        else -> Category.OTHER
    }
}
