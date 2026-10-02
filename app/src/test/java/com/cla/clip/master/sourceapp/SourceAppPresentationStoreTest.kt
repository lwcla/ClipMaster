package com.cla.clip.master.sourceapp

import com.cla.clip.base.general.dao.SourceAppData
import com.cla.clip.master.ui.widget.sourceapp.SourceAppPresentationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 可替换数据/副作用/时钟验证 Store 的共享、等值分发、冷却及取消。 */
class SourceAppPresentationStoreTest {
    /** 文件清理也必须共享总 IO 配额，不能在两项修复旁再启动第三项。 */
    @Test
    fun cleanupAndRepairsShareTwoIoPermits() = runBlocking {
        /** 同线程可控作用域与三个副作用共用的完成信号。 */
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val gate = CompletableDeferred<Unit>()
        /** 实际进入 IO 边界的数量与峰值。 */
        var running = 0
        var peak = 0
        var completed = 0
        val started = MutableStateFlow(0)
        /** 假 IO 明确挂起，直到所有待处理任务都进入调度队列后再放行。 */
        val io: suspend () -> Unit = {
            running++
            peak = maxOf(peak, running)
            started.value++
            try { gate.await() } finally { running--; completed++ }
        }
        /** 大于一小时的固定时间允许本轮清理进入队列。 */
        val store = SourceAppPresentationStore({ MutableStateFlow(listOf(source("a"), source("b"))) },
            { io(); true }, { io() }, scope, { 10_000_000L })
        try {
            store.acquire("page", setOf("a", "b"))
            withTimeout(5000) { started.first { it >= 2 } }
            delay(10)
            assertEquals("修复和清理的合计并发不能超过两项", 2, peak)
            gate.complete(Unit)
            withTimeout(5000) { while (completed != 3) delay(1) }
            assertEquals(2, peak)
        } finally { scope.cancel() }
    }

    /** 更新 A 不发射相等的 B；同包名的两处需求只使用一次聚合观察。 */
    @Test
    fun sharedDemandDoesNotRestartQueriesOrEmitUnchangedPackage() = runBlocking {
        /** 测试作用域由 finally 取消，不留下应用级任务。 */
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        /** 初始元数据使用合成包名和路径。 */
        val rows = MutableStateFlow(listOf(source("a"), source("b")))
        /** 聚合查询创建计数保护无重复 SQL 的边界。 */
        var queries = 0
        /** 修复返回缓存命中，不读系统或文件。 */
        val store = SourceAppPresentationStore({ queries++; rows }, { true }, {}, scope, { 0L })
        try {
            /** 取得状态不会启动查询。 */
            val a = store.state("a")
            val b = store.state("b")
            assertEquals(0, queries)
            store.acquire("page", setOf("a", "b"))
            withTimeout(5000) { a.first { it is SourceAppPresentationState.Ready } }
            store.acquire("menu", setOf("a"))
            assertEquals(1, queries)
            /** B 的下一次不同值必须是删除，而不是 A 的图标变化。 */
            val nextB = async { b.drop(1).first() }
            delay(1)
            rows.value = listOf(source("a").copy(iconPath = "new"), source("b"))
            withTimeout(5000) { a.first { (it as? SourceAppPresentationState.Ready)?.source?.iconPath == "new" } }
            assertFalse(nextB.isCompleted)
            rows.value = listOf(source("a"))
            assertEquals(SourceAppPresentationState.Unavailable, withTimeout(5000) { nextB.await() })
            assertEquals(1, queries)
        } finally { scope.cancel() }
    }

    /** 元数据重复发射不重启慢项，两个页面释放最后一个需求时才取消修复。 */
    @Test
    fun inFlightRepairIsSharedAndLastReleaseCancelsIt() = runBlocking {
        /** 所有测试任务在当前线程串行调度。 */
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        /** 测试控制的元数据通知。 */
        val rows = MutableStateFlow(listOf(source("shared")))
        /** 手动阻塞系统阶段，避免用执行时长推断在途状态。 */
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Boolean>()
        /** 同包名实际修复次数。 */
        var repairs = 0
        /** 假修复直到取消前都不会提交。 */
        val store = SourceAppPresentationStore({ rows }, {
            repairs++
            started.complete(Unit)
            try { gate.await() } catch (error: CancellationException) { cancelled.complete(Unit); throw error }
        }, {}, scope, { 0L })
        try {
            store.acquire("page", setOf("shared"))
            withTimeout(5000) { started.await() }
            store.acquire("menu", setOf("shared"))
            rows.value = listOf(source("shared").copy(appName = "新名称"))
            withTimeout(5000) { store.state("shared").first { (it as? SourceAppPresentationState.Ready)?.source?.appName == "新名称" } }
            assertEquals(1, repairs)
            store.release("page")
            assertFalse(cancelled.isCompleted)
            store.release("menu")
            withTimeout(5000) { cancelled.await() }
            assertEquals(1, repairs)
        } finally { scope.cancel() }
    }

    /** 查询异常保留已有状态，失败项在相同会话内不因通知无限重试。 */
    @Test
    fun failedRepairCoolsDownAndMetadataErrorKeepsLastValue() = runBlocking {
        /** 可控作用域与查询结束信号。 */
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val failure = CompletableDeferred<Unit>()
        val queryEnded = CompletableDeferred<Unit>()
        /** 一次修复失败后不能被相同元数据重新触发。 */
        var attempts = 0
        /** 两次同值发射后主动模拟查询故障。 */
        val store = SourceAppPresentationStore({ flow {
            try {
                emit(listOf(source("a")))
                emit(listOf(source("a")))
                failure.await()
                throw java.io.IOException("fixture")
            } finally { queryEnded.complete(Unit) }
        } }, { attempts++; false }, {}, scope, { 0L })
        try {
            store.acquire("page", setOf("a"))
            withTimeout(5000) { store.state("a").first { it is SourceAppPresentationState.Ready } }
            /** 显式让已排队修复运行，随后才断言冷却次数。 */
            withTimeout(5000) { while (attempts == 0) delay(1) }
            failure.complete(Unit)
            withTimeout(5000) { queryEnded.await() }
            assertEquals(1, attempts)
            assertTrue(store.state("a").value is SourceAppPresentationState.Ready)
        } finally { scope.cancel() }
    }

    /** 合成持久记录，不依赖 Android 平台。 */
    private fun source(packageName: String) = SourceAppData(packageName, "来源", "old", null, "hash")
}
