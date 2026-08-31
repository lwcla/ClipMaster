package com.cla.clip.feature.torrent.search

import androidx.annotation.StringRes
import com.cla.clip.feature.torrent.search.api.TorrentSearchBackupCountItem

/** TorrentSearch 书签在备份 featureCounts 中的稳定计数 key。 */
private const val TORRENT_SEARCH_BOOKMARK_COUNT_KEY = "torrent_search_bookmark_count"

/** TorrentSearch 搜索历史在备份 featureCounts 中的稳定计数 key。 */
private const val TORRENT_SEARCH_SEARCH_HISTORY_COUNT_KEY = "torrent_search_search_history_count"

/** TorrentSearch 已查看条目在备份 featureCounts 中的稳定计数 key。 */
private const val TORRENT_SEARCH_VIEWED_ITEM_COUNT_KEY = "torrent_search_viewed_item_count"

/** TorrentSearch 非敏感设置在备份 featureCounts 中的稳定计数 key。 */
private const val TORRENT_SEARCH_SETTING_COUNT_KEY = "torrent_search_setting_count"

/** TorrentSearch 书签恢复报告的稳定分类 code。 */
private const val TORRENT_SEARCH_BOOKMARK_CATEGORY = "torrent_search_bookmarks"

/** TorrentSearch 搜索历史恢复报告的稳定分类 code。 */
private const val TORRENT_SEARCH_SEARCH_HISTORY_CATEGORY = "torrent_search_search_histories"

/** TorrentSearch 已查看条目恢复报告的稳定分类 code。 */
private const val TORRENT_SEARCH_VIEWED_ITEM_CATEGORY = "torrent_search_viewed_items"

/** TorrentSearch 非敏感设置恢复报告的稳定分类 code。 */
private const val TORRENT_SEARCH_SETTING_CATEGORY = "torrent_search_settings"

/**
 * TorrentSearch 备份预检与恢复报告的纯展示映射器。
 *
 * 只把稳定 manifest key/category code 映射为数量与资源，不读取数据库、Context 或用户内容；缺失计数
 * 回退为 0，未知分类返回 null 交给宿主统一兜底。
 */
internal object TorrentSearchBackupDisplayMapper {
    /** 恢复报告按书签、搜索历史、已查看和设置的固定顺序展示。 */
    val restoreReportCategoryCodes: List<String> = listOf(
        TORRENT_SEARCH_BOOKMARK_CATEGORY,
        TORRENT_SEARCH_SEARCH_HISTORY_CATEGORY,
        TORRENT_SEARCH_VIEWED_ITEM_CATEGORY,
        TORRENT_SEARCH_SETTING_CATEGORY,
    )

    /** 按稳定 featureCounts key 生成宿主备份预检的四行计数，缺失 key 一律返回零。 */
    fun backupCountItems(featureCounts: Map<String, Int>): List<TorrentSearchBackupCountItem> {
        return listOf(
            TorrentSearchBackupCountItem(
                labelRes = R.string.torrent_search_backup_bookmarks,
                count = featureCounts[TORRENT_SEARCH_BOOKMARK_COUNT_KEY] ?: 0,
            ),
            TorrentSearchBackupCountItem(
                labelRes = R.string.torrent_search_backup_search_histories,
                count = featureCounts[TORRENT_SEARCH_SEARCH_HISTORY_COUNT_KEY] ?: 0,
            ),
            TorrentSearchBackupCountItem(
                labelRes = R.string.torrent_search_backup_viewed_items,
                count = featureCounts[TORRENT_SEARCH_VIEWED_ITEM_COUNT_KEY] ?: 0,
            ),
            TorrentSearchBackupCountItem(
                labelRes = R.string.torrent_search_backup_settings,
                count = featureCounts[TORRENT_SEARCH_SETTING_COUNT_KEY] ?: 0,
            ),
        )
    }

    /** 把稳定恢复分类 code 映射为实现模块资源，未知值返回 null 交给宿主兜底。 */
    @StringRes
    fun restoreCategoryLabelRes(categoryCode: String): Int? {
        return when (categoryCode) {
            TORRENT_SEARCH_BOOKMARK_CATEGORY -> R.string.torrent_search_backup_bookmarks
            TORRENT_SEARCH_SEARCH_HISTORY_CATEGORY -> R.string.torrent_search_backup_search_histories
            TORRENT_SEARCH_VIEWED_ITEM_CATEGORY -> R.string.torrent_search_backup_viewed_items
            TORRENT_SEARCH_SETTING_CATEGORY -> R.string.torrent_search_backup_settings
            else -> null
        }
    }
}
