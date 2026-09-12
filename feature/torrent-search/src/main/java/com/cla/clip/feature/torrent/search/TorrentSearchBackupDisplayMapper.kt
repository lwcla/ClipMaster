package com.cla.clip.feature.torrent.search

import androidx.annotation.StringRes
import com.cla.clip.feature.torrent.search.backup.TorrentSearchBackupContract
import com.cla.clip.feature.torrent.search.api.TorrentSearchBackupCountItem

/**
 * TorrentSearch 备份预检与恢复报告的纯展示映射器。
 *
 * 只把稳定 manifest key/category code 映射为数量与资源，不读取数据库、Context 或用户内容；缺失计数
 * 回退为 0，未知分类返回 null 交给宿主统一兜底。
 */
internal object TorrentSearchBackupDisplayMapper {
    /** 恢复报告按书签、搜索历史、已查看和设置的固定顺序展示。 */
    val restoreReportCategoryCodes: List<String> = listOf(
        TorrentSearchBackupContract.BOOKMARK_REPORT_CODE,
        TorrentSearchBackupContract.HISTORY_REPORT_CODE,
        TorrentSearchBackupContract.VIEWED_REPORT_CODE,
        TorrentSearchBackupContract.SETTINGS_REPORT_CODE,
        TorrentSearchBackupContract.LEGACY_INCOMPATIBLE_REPORT_CODE,
    )

    /** 按稳定 featureCounts key 生成宿主备份预检的四行计数，缺失 key 一律返回零。 */
    fun backupCountItems(featureCounts: Map<String, Int>): List<TorrentSearchBackupCountItem> {
        return listOf(
            TorrentSearchBackupCountItem(
                labelRes = R.string.torrent_search_backup_bookmarks,
                count = featureCounts[TorrentSearchBackupContract.BOOKMARK_COUNT_KEY] ?: 0,
            ),
            TorrentSearchBackupCountItem(
                labelRes = R.string.torrent_search_backup_search_histories,
                count = featureCounts[TorrentSearchBackupContract.HISTORY_COUNT_KEY] ?: 0,
            ),
            TorrentSearchBackupCountItem(
                labelRes = R.string.torrent_search_backup_viewed_items,
                count = featureCounts[TorrentSearchBackupContract.VIEWED_COUNT_KEY] ?: 0,
            ),
            TorrentSearchBackupCountItem(
                labelRes = R.string.torrent_search_backup_settings,
                count = featureCounts[TorrentSearchBackupContract.SETTINGS_COUNT_KEY] ?: 0,
            ),
        )
    }

    /** 把稳定恢复分类 code 映射为实现模块资源，未知值返回 null 交给宿主兜底。 */
    @StringRes
    fun restoreCategoryLabelRes(categoryCode: String): Int? {
        return when (categoryCode) {
            TorrentSearchBackupContract.BOOKMARK_REPORT_CODE -> R.string.torrent_search_backup_bookmarks
            TorrentSearchBackupContract.HISTORY_REPORT_CODE -> R.string.torrent_search_backup_search_histories
            TorrentSearchBackupContract.VIEWED_REPORT_CODE -> R.string.torrent_search_backup_viewed_items
            TorrentSearchBackupContract.SETTINGS_REPORT_CODE -> R.string.torrent_search_backup_settings
            TorrentSearchBackupContract.LEGACY_INCOMPATIBLE_REPORT_CODE -> R.string.torrent_search_backup_legacy_incompatible
            else -> null
        }
    }
}
