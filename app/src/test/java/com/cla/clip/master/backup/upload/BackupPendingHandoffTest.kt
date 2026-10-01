package com.cla.clip.master.backup.upload

import com.cla.clip.base.general.backup.settings.BackupPendingUpload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** 入队失败回滚引用，不能提前释放仍可用的旧等待包。 */
class BackupPendingHandoffTest {
    /** 新入队失败时恢复旧身份，旧文件与旧请求都不被释放。 */
    @Test fun enqueueFailureRestoresPreviousAndOnlyDiscardsNew() = runBlocking {
        /** 旧等待包和新的完整待接管包。 */
        val old = BackupPendingUpload("old", "hash", "auto", "queued", 1L)
        /** 新身份仍独立于旧文件。 */
        val fresh = old.copy(artifactId = "new", phase = "ready", createdAt = 2L)
        /** 模拟持久化指针，副作用事件验证释放顺序。 */
        var current = old
        /** 入队失败不能包含 release:old。 */
        val events = mutableListOf<String>()
        /** 使用真实交接规则和可失败边界，不运行 Android 平台。 */
        val handoff = BackupPendingHandoff(replace = { current = it; events += "replace:${it.artifactId}" },
            enqueue = { throw IOException("synthetic") }, release = { events += "release:${it.artifactId}" },
            discard = { events += "discard:${it.artifactId}" })
        try { handoff.accept(fresh, old); fail("入队失败必须传播") }
        catch (_: IOException) { /* 旧等待包已恢复，不把失败新包当作待上传任务。 */ }
        assertEquals("old", current.artifactId)
        assertEquals(listOf("replace:new", "replace:old", "discard:new"), events)
    }

    /** 已终态旧包不能因新入队失败复活。 */
    @Test fun enqueueFailureDoesNotRestoreTerminalPackage() = runBlocking {
        /** 历史终态仅等待清理。 */
        val old = BackupPendingUpload("old", "hash", "auto", "failed", 1L)
        /** 新等待身份。 */
        val fresh = old.copy(artifactId = "new", phase = "ready", createdAt = 2L)
        /** 实际被写入的身份。 */
        val replacements = mutableListOf<String>()
        /** 可失败的队列边界。 */
        val handoff = BackupPendingHandoff(replace = { replacements += it.artifactId }, enqueue = { throw IOException("synthetic") },
            release = {}, discard = {})
        try { handoff.accept(fresh, old); fail("入队失败必须传播") }
        catch (_: IOException) { /* 已结束旧包不再拥有上传资格。 */ }
        assertEquals(listOf("new"), replacements)
    }
}
