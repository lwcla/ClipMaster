package com.cla.clip.master.sourceapp

import com.cla.clip.base.general.dao.SourceAppData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用合成图标验证逐项修复与提交，不依赖系统或真实文件。 */
class SourceAppIconRepairTest {
    /** 缓存有效时必须零系统读取、零写入。 */
    @Test
    fun cacheHitHasNoSystemOrCommitSideEffects() = runBlocking {
        /** 验证顺序及副作用的合成调用记录。 */
        val events = mutableListOf<String>()
        /** 缓存命中的实际修复协作者。 */
        val repair = SourceAppIconRepair<String>(
            { "cached" }, { events += "system"; "system" },
            { _, _ -> events += "publish"; PublishedSourceAppIcon("new", "hash", null) },
            { _, _ -> events += "commit"; true },
        )
        assertTrue(repair.repair(source()))
        assertTrue(events.isEmpty())
    }

    /** 发布成功才提交，提交不等待其他包名的慢读取。 */
    @Test
    fun firstSuccessfulPackageCommitsBeforeSlowPackageCompletes() = runBlocking {
        /** 慢来源由测试显式放行，不使用时间长短猜测顺序。 */
        val slow = CompletableDeferred<String?>()
        /** 已写库来源的合成身份。 */
        val commits = mutableListOf<String>()
        /** 不同包名独立执行，但使用同一个提交契约。 */
        val repair = SourceAppIconRepair<String>(
            { null }, { if (it == "slow") slow.await() else "icon" },
            { name, _ -> PublishedSourceAppIcon(name, "hash", null) },
            { original, _ -> commits += original.packageName; true },
        )
        /** 正在等待系统响应的第二项。 */
        val pending = async { repair.repair(source("slow")) }
        assertTrue(repair.repair(source("fast")))
        assertEquals(listOf("fast"), commits)
        assertFalse(pending.isCompleted)
        slow.complete(null)
        assertFalse(pending.await())
    }

    /** 写盘失败不能更新数据库，保留旧引用。 */
    @Test
    fun publicationFailureDoesNotCommit() = runBlocking {
        /** 不应执行的提交次数。 */
        var commits = 0
        /** 文件发布返回不可用的协作者。 */
        val repair = SourceAppIconRepair<String>({ null }, { "icon" }, { _, _ -> null }, { _, _ -> commits++; true })
        assertFalse(repair.repair(source()))
        assertEquals(0, commits)
    }

    /** 取消必须传播，不能吞掉后继续写盘或提交。 */
    @Test(expected = CancellationException::class)
    fun cancellationDoesNotFallBackOrCommit() = runBlocking {
        /** 缓存读取直接取消，用于验证后面的系统分支不运行。 */
        val repair = SourceAppIconRepair<String>(
            { throw CancellationException("fixture") }, { error("unexpected system read") },
            { _, _ -> error("unexpected publication") }, { _, _ -> error("unexpected commit") },
        )
        repair.repair(source())
        Unit
    }

    /** 空身份不查询系统，条件冲突按失败返回，不发布成功假象。 */
    @Test
    fun unknownSourceSkipsSystemAndConflictIsNotSuccess() = runBlocking {
        /** 系统读取计数保护未知来源分支。 */
        var reads = 0
        /** 提交回调返回 false 模拟并发更新或删除来源。 */
        val repair = SourceAppIconRepair<String>({ null }, { reads++; "icon" },
            { _, _ -> PublishedSourceAppIcon("file", "hash", null) }, { _, _ -> false })
        assertFalse(repair.repair(source("")))
        assertEquals(0, reads)
        assertFalse(repair.repair(source()))
    }

    /** 只创建合成来源，无真实包名、路径或用户数据。 */
    private fun source(packageName: String = "fixture") = SourceAppData(packageName, "来源", "old", null, "old-hash")
}
