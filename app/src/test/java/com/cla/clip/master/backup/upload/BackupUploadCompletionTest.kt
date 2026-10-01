package com.cla.clip.master.backup.upload

import com.cla.clip.base.general.backup.settings.BackupPendingUpload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import com.cla.clip.master.backup.BackupResultPersistenceFailure

/** 上传终态与文件所有权顺序，失败时不能先删除可核对的私有包。 */
class BackupUploadCompletionTest {
    /** 写盘失败时文件和指针都保持，不能回头重传。 */
    @Test fun resultCommitFailureDoesNotDeletePackage() = runBlocking {
        /** 受控文件副作用事件。 */
        val events = mutableListOf<String>()
        /** commit 模拟磁盘失败，不接触真实 Android 存储。 */
        val completion = BackupUploadCompletion(read = { null }, commit = { _, _, _ -> throw IOException("synthetic") },
            remove = { events += "remove" }, clear = { events += "clear" })
        try { completion.finish("old", 100L, "remote_failed"); fail("提交失败必须传播") }
        catch (failure: BackupResultPersistenceFailure) { assertTrue(failure.cause is IOException) }
        assertTrue(events.isEmpty())
    }

    /** 已提交终态先于清理，清理失败继续保留终态供重启仅做清理。 */
    @Test fun terminalCommitPrecedesDeletionAndCleanupFailureKeepsPointer() = runBlocking {
        /** 最初等待身份，随后由 fake commit 推进终态。 */
        var pending: BackupPendingUpload? = BackupPendingUpload("new", "hash", "auto", "queued", 1L)
        /** 可观察的副作用顺序。 */
        val events = mutableListOf<String>()
        /** 不可写文件目录模拟清理失败。 */
        val completion = BackupUploadCompletion(read = { pending }, commit = { _, _, _ ->
            events += "commit"
            pending = pending?.copy(phase = "failed")
            true
        }, remove = { events += "remove"; throw IOException("synthetic") }, clear = { events += "clear"; pending = null })
        try { completion.finish("new", 100L, "remote_failed"); fail("清理失败必须传播") }
        catch (_: IOException) { /* 已保存的终态不能被清理错误撤销。 */ }
        assertEquals(listOf("commit", "remove"), events)
        assertEquals("failed", pending?.phase)
    }

    /** 旧任务退出时不能清理新身份，即使新身份恰好是终态。 */
    @Test fun staleIdentityCannotDeleteReplacement() = runBlocking {
        /** 新身份的私有包仍归新流程管理。 */
        val pending = BackupPendingUpload("new", "hash", "auto", "failed", 1L)
        /** 文件与指针清理计数，均应为零。 */
        val events = mutableListOf<String>()
        /** 旧身份提交被事务比较拒绝。 */
        val completion = BackupUploadCompletion(read = { pending }, commit = { _, _, _ -> false },
            remove = { events += "remove" }, clear = { events += "clear" })
        completion.finish("old", 100L, "remote_failed")
        completion.cleanup("old")
        assertTrue(events.isEmpty())
    }
}
