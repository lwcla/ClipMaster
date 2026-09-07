package com.cla.clip.feature.torrent.search.provider.builtin.torrentdatabase

import com.cla.clip.feature.torrent.search.model.contract.Category

/** TorrentDatabase search/browse slug 与页面文本映射入口。 */
internal object TorrentDatabaseCategoryMapper {
    /** 搜索专用 slug，BOOKS 保留 e-books 连字符。 */
    fun searchSlug(
        /** 当前领域分类。 */ category: Category,
    ): String? = when (category) {
        Category.APPS -> "software"
        Category.BOOKS -> "e-books"
        Category.GAMES -> "games"
        Category.MOVIES -> "movies"
        Category.MUSIC -> "music"
        Category.PORN -> "porn"
        Category.SERIES -> "tv"
        else -> null
    }

    /** browse/top 专用 slug，BOOKS 固定使用 ebooks。 */
    fun browseSlug(
        /** 当前领域分类。 */ category: Category,
    ): String? = when (category) {
        Category.BOOKS -> "ebooks"
        else -> searchSlug(category)
    }

    /** 页面类别先 trim，再映射图书双文本和保守 OTHER 回退。 */
    fun fromPageText(
        /** 页面类别文本。 */ rawCategory: String,
    ): Category = when (rawCategory.trim()) {
        "Software" -> Category.APPS
        "E-Books", "AudioBooks" -> Category.BOOKS
        "Games" -> Category.GAMES
        "Movies" -> Category.MOVIES
        "Music" -> Category.MUSIC
        "Porn" -> Category.PORN
        "TV" -> Category.SERIES
        else -> Category.OTHER
    }
}
