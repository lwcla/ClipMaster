package com.cla.clip.feature.torrent.search.backup

import com.cla.clip.base.general.backup.BackupJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TorrentSearch schema v4/v1 子协议的显式字段白名单回归。 */
class TorrentSearchBackupModelsTest {
    /** 恢复冲突必须新增缺失键、更新严格较旧本地，并跳过相同或更老备份。 */
    @Test
    fun restoreDecisionIsIdempotentAndKeepsNewerLocalData() {
        assertEquals(TorrentSearchRestoreDecision.INSERT, decideTorrentSearchRestore(10L, null))
        assertEquals(TorrentSearchRestoreDecision.UPDATE, decideTorrentSearchRestore(11L, 10L))
        assertEquals(TorrentSearchRestoreDecision.SKIP, decideTorrentSearchRestore(10L, 10L))
        assertEquals(TorrentSearchRestoreDecision.SKIP, decideTorrentSearchRestore(9L, 10L))
    }

    /** 同一恢复分页中的重复稳定键只保留最新记录，并把其余行准确计为跳过。 */
    @Test
    fun restorePageDeduplicatesStableKeysAndKeepsNewestRecord() {
        /** records 模拟同一 JSONL 分页内乱序出现的重复稳定键。 */
        val records = listOf("a" to 10L, "b" to 7L, "a" to 12L, "b" to 6L)

        /** result 是进入 Room 冲突判断前的去重结果。 */
        val result = deduplicateTorrentSearchRestoreCandidates(
            candidates = records,
            stableKey = Pair<String, Long>::first,
            timestamp = Pair<String, Long>::second,
        )

        assertEquals(listOf("a" to 12L, "b" to 7L), result.candidates)
        assertEquals(2, result.duplicateCount)
    }

    /** 排序设置只能序列化稳定 criteria/order code，不能混入本机网络与隐私状态。 */
    @Test
    fun settingsJsonContainsOnlyVersionAndSortCodes() {
        /** settings 是可跨安装恢复的完整 TorrentSearch 设置白名单。 */
        val settings = TorrentSearchBackupSettingsV1(version = 1, sortCriteriaCode = "seeders", sortOrderCode = "descending")

        /** encoded 是使用统一备份 Json 配置生成的外部协议文本。 */
        val encoded = BackupJson.json.encodeToString(settings)

        assertEquals("{\"version\":1,\"sort_criteria\":\"seeders\",\"sort_order\":\"descending\"}", encoded)
        assertFalse(encoded.contains("safe_mode"))
        assertFalse(encoded.contains("api_key"))
        assertFalse(encoded.contains("cookie"))
        assertFalse(encoded.contains("provider_ids"))
    }

    /** 书签协议必须显式保留稳定身份和用户可见快照，并容忍未来未知字段。 */
    @Test
    fun bookmarkJsonUsesStableNamesAndIgnoresUnknownFields() {
        /** bookmark 是最小合法 v1 书签备份记录。 */
        val bookmark = TorrentSearchBackupBookmarkV1(
            infoHash = HASH,
            name = "Fixture",
            providerId = "fixture",
            providerName = "Fixture",
            bookmarkedAtEpochMillis = 10L,
        )
        /** encoded 锁定字段名不依赖 Kotlin 属性或类名。 */
        val encoded = BackupJson.json.encodeToString(bookmark)
        assertTrue(encoded.contains("\"info_hash\":\"$HASH\""))
        assertTrue(encoded.contains("\"bookmarked_at_epoch_millis\":10"))

        /** decoded 从包含未来字段的文本恢复同一协议对象。 */
        val decoded = BackupJson.json.decodeFromString<TorrentSearchBackupBookmarkV1>(encoded.dropLast(1) + ",\"future\":true}")
        assertEquals(bookmark, decoded)
    }

    /** 合成 infoHash。 */
    companion object {
        /** 40 位小写十六进制身份。 */
        private const val HASH = "0123456789012345678901234567890123456789"
    }
}
