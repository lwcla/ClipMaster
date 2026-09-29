package com.cla.clip.feature.torrent.search.backup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** 跨库存储恢复批次的回归测试，保护已提交页在后续失败时仍有备份版本标记。 */
class TorrentSearchRestoreBatchDirtyTest {
    /** 第二页恢复失败不能抹去第一页已提交的 dirty 标记。 */
    @Test
    fun laterBatchFailureKeepsEarlierCommittedBatchMarked() = runBlocking {
        /** events 记录事务提交与标记的先后顺序。 */
        val events = mutableListOf<String>()
        /** marker 模拟可等待的宿主版本提交。 */
        val marker = TorrentSearchBackupDirtyMarker { events += "marked" }

        runRestoreBatchAndMarkDirty(marker) {
            events += "first_committed"
            1
        }
        try {
            runRestoreBatchAndMarkDirty(marker) {
                throw IllegalStateException("later batch failed")
            }
            fail("后续页故障必须传播")
        } catch (_: IllegalStateException) {
            /** 故障按原样传播，首批已经提交的标记不能被延后到分类末尾。 */
        }

        assertEquals(listOf("first_committed", "marked"), events)
    }

    /** 没有插入或更新的批次不能产生重复备份版本。 */
    @Test
    fun unchangedBatchDoesNotMarkDirty() = runBlocking {
        /** markCount 记录宿主变更版本提交次数。 */
        var markCount = 0
        /** marker 模拟可等待的宿主版本提交。 */
        val marker = TorrentSearchBackupDirtyMarker { markCount += 1 }

        runRestoreBatchAndMarkDirty(marker) { 0 }

        assertEquals(0, markCount)
    }
}
