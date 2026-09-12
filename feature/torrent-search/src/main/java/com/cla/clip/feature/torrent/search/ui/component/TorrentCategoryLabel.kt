package com.cla.clip.feature.torrent.search.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cla.clip.feature.torrent.search.R
import com.cla.clip.feature.torrent.search.model.contract.Category

/** 搜索、浏览和详情共用的分类稳定枚举资源映射。 */
@Composable
internal fun torrentCategoryLabel(
    /** 待展示的稳定分类。 */
    category: Category,
): String = stringResource(
    when (category) {
        Category.ALL -> R.string.torrent_search_category_all
        Category.ANIME -> R.string.torrent_search_category_anime
        Category.APPS -> R.string.torrent_search_category_apps
        Category.BOOKS -> R.string.torrent_search_category_books
        Category.GAMES -> R.string.torrent_search_category_games
        Category.MOVIES -> R.string.torrent_search_category_movies
        Category.MUSIC -> R.string.torrent_search_category_music
        Category.PORN -> R.string.torrent_search_category_porn
        Category.SERIES -> R.string.torrent_search_category_series
        Category.OTHER -> R.string.torrent_search_category_other
    },
)
