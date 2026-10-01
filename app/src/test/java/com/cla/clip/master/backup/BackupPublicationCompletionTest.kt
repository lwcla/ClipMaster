package com.cla.clip.master.backup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** 发布与状态写盘不是同一种失败，使用一次失败后恢复的存储桩复现误报。 */
class BackupPublicationCompletionTest {
    /** 已完整发布后首次保存失败，不得再次记录备份失败。 */
    @Test fun transientSuccessCommitFailureNeverWritesFalseBackupFailure() = runBlocking {
        /** 每次结果写入的真实结论，第二次调用会成功以复现旧错误路径。 */
        val writes = mutableListOf<Boolean>()
        /** 合成发布副作用是否完整完成。 */
        var published = false
        /** 第一笔写盘失败；错误恢复不能制造另一笔失败结果。 */
        val completion = BackupPublicationCompletion { success, _ ->
            writes += success
            if (writes.size == 1) throw IOException("synthetic")
        }
        try { completion.publish { published = true }; fail("状态写盘失败必须明确传播") }
        catch (failure: BackupResultPersistenceFailure) { assertTrue(failure.publicationSucceeded) }
        assertTrue(published)
        assertEquals(listOf(true), writes)
    }

    /** 真正的发布失败只记录失败，继续向入口传播原始失败。 */
    @Test fun publicationFailureRecordsActualFailureOnce() = runBlocking {
        /** 副作用失败后提交的唯一结论。 */
        val writes = mutableListOf<Boolean>()
        /** 受控结果记录器。 */
        val completion = BackupPublicationCompletion { success, _ -> writes += success }
        try { completion.publish { throw IOException("synthetic") }; fail("发布失败应传播") }
        catch (_: IOException) { /* 只确认真实 IO 失败，没有结果写盘失败。 */ }
        assertEquals(listOf(false), writes)
    }
}
