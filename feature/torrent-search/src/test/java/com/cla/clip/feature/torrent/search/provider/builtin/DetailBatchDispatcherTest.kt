package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.DetailBatchSite
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 04E4 三站 N+1 详情并发、失败传播、取消和线程隔离契约。 */
class DetailBatchDispatcherTest {
    /** 两项详情必须具备并发机会，逆序完成后仍保持列表顺序。 */
    @Test
    fun detailFanOutRunsConcurrentlyAndPreservesSourceOrder() = runBlocking {
        /** site 是当前独立验证的固定来源。 */
        for (site in DetailBatchSite.entries) {
            /** active 统计当前尚未完成的详情请求数。 */
            val active = AtomicInteger()
            /** maximum 保存观测到的最大并行详情数。 */
            val maximum = AtomicInteger()
            /** betaStarted 允许 Alpha 等到 Beta 已开始，证明不是串行请求。 */
            val betaStarted = CompletableDeferred<Unit>()
            /** releaseAlpha 让 Beta 先完成，再释放 Alpha。 */
            val releaseAlpha = CompletableDeferred<Unit>()
            /** client 只执行中性列表与详情回调。 */
            val client = FakeTorrentSearchNetworkClient(textHandler = { url ->
                when (url) {
                    site.url() -> {
                        /** count 是 Alpha 开始后的并行数。 */
                        val count = active.incrementAndGet()
                        // previous 是更新前的并发峰值。
                        maximum.updateAndGet { previous -> maxOf(previous, count) }
                        withTimeout(2_000L) { betaStarted.await() }
                        withTimeout(2_000L) { releaseAlpha.await() }
                        active.decrementAndGet()
                        site.detailsHtml()
                    }
                    site.url("sample-beta") -> {
                        /** count 是 Beta 开始后的并行数。 */
                        val count = active.incrementAndGet()
                        // previous 是更新前的并发峰值。
                        maximum.updateAndGet { previous -> maxOf(previous, count) }
                        betaStarted.complete(Unit)
                        active.decrementAndGet()
                        releaseAlpha.complete(Unit)
                        site.betaHtml()
                    }
                    else -> site.listHtml()
                }
            })
            /** names 必须按 draft 顺序而不是网络完成顺序返回。 */
            val names = site.provider(client).search("fixture", Category.ALL).map { torrent -> torrent.name }
            assertEquals(listOf("Sample Alpha", "Sample Beta"), names)
            assertTrue(maximum.get() >= 2)
        }
    }

    /** 任一详情传输失败必须让整个 Provider 失败，不能伪装部分成功。 */
    @Test
    fun detailNetworkFailureFailsWholeProvider() = runBlocking {
        /** site 是当前独立验证的固定来源。 */
        for (site in DetailBatchSite.entries) {
            /** client 让 Beta 详情抛出无敏感正文的 IO 异常。 */
            val client = FakeTorrentSearchNetworkClient(textHandler = { url ->
                when (url) {
                    site.url() -> site.detailsHtml()
                    site.url("sample-beta") -> throw IOException()
                    else -> site.listHtml()
                }
            })
            try {
                site.provider(client).search("fixture", Category.ALL)
                fail("详情网络失败必须传播")
            // failure 是必须保持原类型传播的网络异常。
            } catch (failure: IOException) {
                assertTrue(failure.message == null)
            }
        }
    }

    /** 详情响应后取消当前子任务时，父搜索必须结束为取消而不是空成功。 */
    @Test
    fun cancellationDuringDetailFanOutPropagates() = runBlocking {
        /** site 是当前独立验证的固定来源。 */
        for (site in DetailBatchSite.entries) supervisorScope {
            /** client 仅在 Alpha 详情回调中取消当前协程。 */
            val client = FakeTorrentSearchNetworkClient(textHandler = { url ->
                when (url) {
                    site.url() -> {
                        currentCoroutineContext().cancel()
                        site.detailsHtml()
                    }
                    site.url("sample-beta") -> site.betaHtml()
                    else -> site.listHtml()
                }
            })
            /** result 隔离当前站点的预期取消。 */
            val result = async { site.provider(client).search("fixture", Category.ALL) }
            try {
                result.await()
                fail("父搜索必须传播详情取消")
            // failure 是预期的取消结果，不读取其可能包含的消息。
            } catch (failure: CancellationException) {
                assertTrue(result.isCancelled)
            }
        }
    }

    /** 网络回调留在 caller，列表和详情 DOM 解析进入注入计算调度器。 */
    @Test
    fun networkAndParsingStayOnTheirOwnedDispatchers() = runBlocking {
        /** site 是当前独立验证的固定来源。 */
        for (site in DetailBatchSite.entries) {
            /** caller 承载 Provider 的网络编排。 */
            val caller = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "detail-batch-caller")
            }.asCoroutineDispatcher()
            /** parser 记录被注入的计算线程。 */
            val parser = DetailBatchRecordingDispatcher()
            /** networkThreads 记录 fake 网络回调所在调用线程。 */
            val networkThreads = mutableListOf<String>()
            /** client 返回一条合法列表与详情，覆盖两个解析阶段。 */
            val client = FakeTorrentSearchNetworkClient(textHandler = { url ->
                networkThreads += Thread.currentThread().name
                if (url == site.url()) site.detailsHtml() else site.listHtml().replace(
                    "<tr><td><a href=\"/details/sample-beta\">Sample Beta</a></td></tr>",
                    "",
                )
            })
            try {
                /** results 从真实 Provider 流程产出，不能只断言 dispatcher 本身。 */
                val results = withContext(caller) {
                    site.provider(client, parser).search("fixture", Category.ALL)
                }
                // torrent 是等待提取名称的单个公开结果。
                assertEquals(listOf("Sample Alpha"), results.map { torrent -> torrent.name })
                assertEquals(2, networkThreads.size)
                // thread 是网络 fake 记录的调用线程名。
                assertTrue(networkThreads.all { thread -> thread.startsWith("detail-batch-caller") })
                assertTrue(parser.threads.isNotEmpty())
                // thread 是 parser 调度器记录的执行线程名。
                assertTrue(parser.threads.all { thread -> thread.startsWith("detail-batch-parser") })
            } finally {
                caller.close()
                parser.close()
            }
        }
    }
}

/** 04E4 专用计算调度器，记录线程并显式释放测试 executor。 */
private class DetailBatchRecordingDispatcher : CoroutineDispatcher(), AutoCloseable {
    /** delegate 是当前测试唯一的计算线程。 */
    private val delegate = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "detail-batch-parser")
    }.asCoroutineDispatcher()

    /** threads 只记录解析任务实际运行的线程名。 */
    val threads = mutableListOf<String>()

    /** 先记录线程，再执行真实协程任务。 */
    override fun dispatch(
        /** 目标协程上下文。 */
        context: CoroutineContext,
        /** 待执行的计算任务。 */
        block: Runnable,
    ) {
        delegate.dispatch(context) {
            threads += Thread.currentThread().name
            block.run()
        }
    }

    /** 释放测试私有线程，避免 JVM 进程泄漏。 */
    override fun close() {
        delegate.close()
    }
}
