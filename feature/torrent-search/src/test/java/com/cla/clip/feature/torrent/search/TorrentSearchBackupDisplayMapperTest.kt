package com.cla.clip.feature.torrent.search

import com.cla.clip.feature.torrent.search.api.TorrentSearchBackupCountItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TorrentSearch 备份预检与恢复报告展示协议测试，防止 key、顺序或资源映射静默漂移。 */
class TorrentSearchBackupDisplayMapperTest {
    /** 恢复报告必须按书签、搜索历史、已查看、设置的固定 code 顺序展示。 */
    @Test
    fun restoreCategoryCodesMatchStableOrder() {
        /** 手工固定的恢复分类顺序；期望值不复用生产常量，确保拼写变化会被测试发现。 */
        val expectedCategories = listOf(
            "torrent_search_bookmarks",
            "torrent_search_search_histories",
            "torrent_search_viewed_items",
            "torrent_search_settings",
        )

        assertEquals(expectedCategories, TorrentSearchBackupDisplayMapper.restoreReportCategoryCodes)
    }

    /** 预检计数必须使用稳定 manifest key，并保持四种资源标签和数量一一对应。 */
    @Test
    fun backupCountItemsMapStableKeysToLabels() {
        /** 合成的功能计数，只使用无用户数据的不同数值来识别 key 交换。 */
        val featureCounts = mapOf(
            "torrent_search_bookmark_count" to 11,
            "torrent_search_search_history_count" to 22,
            "torrent_search_viewed_item_count" to 33,
            "torrent_search_setting_count" to 44,
        )
        /** 手工固定的展示行，标签和数量同时参与相等性断言。 */
        val expectedItems = listOf(
            TorrentSearchBackupCountItem(R.string.torrent_search_backup_bookmarks, 11),
            TorrentSearchBackupCountItem(R.string.torrent_search_backup_search_histories, 22),
            TorrentSearchBackupCountItem(R.string.torrent_search_backup_viewed_items, 33),
            TorrentSearchBackupCountItem(R.string.torrent_search_backup_settings, 44),
        )

        assertEquals(expectedItems, TorrentSearchBackupDisplayMapper.backupCountItems(featureCounts))
    }

    /** 缺失的 featureCounts key 必须回退为零，不能抛错或沿用其他分类数量。 */
    @Test
    fun backupCountItemsFallBackToZeroForMissingKeys() {
        /** 空计数映射模拟旧备份或尚未写入 TorrentSearch 统计的 manifest。 */
        val emptyFeatureCounts = emptyMap<String, Int>()
        /** 四个分类的期望零值，固定数量同时验证分类没有被静默省略。 */
        val expectedCounts = listOf(0, 0, 0, 0)

        assertEquals(
            expectedCounts,
            // item 是当前备份分类展示项，只提取计数验证缺省值。
            TorrentSearchBackupDisplayMapper.backupCountItems(emptyFeatureCounts).map { item -> item.count },
        )
    }

    /** 已知分类必须映射到精确资源，未来未知分类必须返回 null 交给宿主兜底。 */
    @Test
    fun restoreCategoryLabelsMatchStableCodesAndRejectUnknown() {
        /** 手工固定的分类到资源映射；不同资源 ID 可检测分类交换。 */
        val expectedLabels = mapOf(
            "torrent_search_bookmarks" to R.string.torrent_search_backup_bookmarks,
            "torrent_search_search_histories" to R.string.torrent_search_backup_search_histories,
            "torrent_search_viewed_items" to R.string.torrent_search_backup_viewed_items,
            "torrent_search_settings" to R.string.torrent_search_backup_settings,
        )

        // categoryCode 是备份分类 code，expectedLabelRes 是对应展示资源。
        expectedLabels.forEach { (categoryCode, expectedLabelRes) ->
            assertEquals(
                expectedLabelRes,
                TorrentSearchBackupDisplayMapper.restoreCategoryLabelRes(categoryCode),
            )
        }
        assertNull(TorrentSearchBackupDisplayMapper.restoreCategoryLabelRes("torrent_search_future_category"))
    }
}
