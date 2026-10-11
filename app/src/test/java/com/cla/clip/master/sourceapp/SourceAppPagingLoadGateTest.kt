package com.cla.clip.master.sourceapp

import androidx.paging.PagingData
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.Assert.*
import org.junit.Test

/** 使用真实 cachedIn 验证 UI 退出不能让未完成装配继续读取下一项。 */
class SourceAppPagingLoadGateTest {
    /** 缓存父任务保持存活，隐藏取消当前项，重新收集复用已完成快照。 */
    @Test fun cachedPagingPausesHeavyMappingWithoutLosingGeneration() = runBlocking {
        /** 可见页面绑定身份。 */
        val owner = Any()
        /** 同一分页代次的页面门控。 */
        val gate = SourceAppLoadGate()
        /** 独立缓存作用域模拟 ViewModel，不随 UI collector 退出。 */
        val cacheScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        /** 首次读取启动信号。 */
        val started = CompletableDeferred<Unit>()
        /** 被隐藏取消的读取信号。 */
        val stopped = CompletableDeferred<Unit>()
        /** 完整分页快照已交付信号。 */
        val presented = CompletableDeferred<Unit>()
        /** 读取次数用于检测隐藏时是否继续下一项。 */
        var reads = 0
        /** 真实 Paging 缓存链路，装配仍在 cachedIn 之前。 */
        val pages = flowOf(PagingData.from(listOf("a", "b"))).map { data ->
            data.map { item ->
                gate.run {
                    if (++reads == 1) {
                        started.complete(Unit)
                        try { awaitCancellation() } finally { stopped.complete(Unit) }
                    }
                    item
                }
            }
        }.cachedIn(cacheScope)
        /** 无 Android 主线程依赖的 Paging presenter。 */
        val presenter = object : PagingDataPresenter<String>(Dispatchers.Unconfined) {
            /** 仅完整结果发布才确认交付，不能把加载状态当作已装配。 */
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<String>) {
                if (snapshot().items == listOf("a", "b")) presented.complete(Unit)
            }
        }
        try {
            gate.setActive(owner, true)
            /** 第一处 UI 收集者。 */
            val first = launch { pages.collectLatest { presenter.collectFrom(it) } }
            withTimeout(5_000) { started.await() }
            gate.setActive(owner, false)
            first.cancelAndJoin()
            withTimeout(5_000) { stopped.await() }
            yield()
            assertEquals(1, reads)
            gate.setActive(owner, true)
            /** 恢复后的 UI 复用同一 cachedIn 代次。 */
            val second = launch { pages.collectLatest { presenter.collectFrom(it) } }
            withTimeout(5_000) { presented.await() }
            assertEquals(listOf("a", "b"), presenter.snapshot().items)
            assertEquals(3, reads)
            second.cancelAndJoin()
            /** 新收集者确认已完成快照被回放，不再执行装配读取。 */
            val replayed = CompletableDeferred<Unit>()
            /** 独立 presenter 防止断言只读到上一收集者的残留结果。 */
            val replayPresenter = object : PagingDataPresenter<String>(Dispatchers.Unconfined) {
                /** 缓存回放完整列表后通知测试。 */
                override suspend fun presentPagingDataEvent(event: PagingDataEvent<String>) {
                    if (snapshot().items == listOf("a", "b")) replayed.complete(Unit)
                }
            }
            /** 第三次 UI 收集同一个 cachedIn 结果。 */
            val third = launch { pages.collectLatest { replayPresenter.collectFrom(it) } }
            withTimeout(5_000) { replayed.await() }
            assertEquals(3, reads)
            third.cancelAndJoin()
        } finally {
            cacheScope.cancel()
        }
    }
}
