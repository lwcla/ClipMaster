package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.knaben.KnabenProvider
import com.cla.clip.feature.torrent.search.provider.builtin.limetorrents.LimeTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.linuxtracker.LinuxTrackerProvider
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 第六批网络/计算线程隔离、详情解析与响应后取消边界。 */
class SixthBatchProviderDispatcherTest {
    /** 三站搜索网络留在 caller，JSON/DOM 必须进入注入 parser。 */
    @Test
    fun searchesSeparateNetworkFromParsing() = runBlocking {
        /** checks 是三个站点各自构造与搜索调用。 */
        val checks: List<suspend (TorrentSearchNetworkClient, CoroutineDispatcher) -> Unit> = listOf(
            /** client/dispatcher 分别注入 Knaben 网络与解析边界。 */
            { client, dispatcher -> KnabenProvider(client, dispatcher).search("x", Category.ALL) },
            /** client/dispatcher 分别注入 LimeTorrents 网络与解析边界。 */
            { client, dispatcher -> LimeTorrentsProvider(client, { 0L }, dispatcher).search("x", Category.ALL) },
            /** client/dispatcher 分别注入 LinuxTracker 网络与解析边界。 */
            { client, dispatcher -> LinuxTrackerProvider(client, dispatcher).search("x", Category.APPS) },
        )
        /** check 是当前站点的真实入口。 */
        for (check in checks) verifySearchDispatchers(check)
    }

    /** Lime 与 Linux 详情网络留在 caller，详情 DOM 进入注入 parser。 */
    @Test
    fun detailsSeparateNetworkFromParsing() = runBlocking {
        /** caller 是详情入口调用线程；runnable 是该 executor 的工作项。 */
        val caller = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "sixth-caller") }.asCoroutineDispatcher()
        /** parser 记录两个详情 parser 的执行线程。 */ val parser = SixthBatchRecordingDispatcher()
        /** networkThreads 记录 fake 回调所在线程。 */ val networkThreads = mutableListOf<String>()
        /** client 按 URL host 返回对应合成详情。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { url ->
            networkThreads += Thread.currentThread().name
            if (url.contains("linuxtracker")) readProviderFixture("provider/linuxtracker/details_normal.html") else readProviderFixture("provider/limetorrents/details_normal.html")
        })
        try {
            withContext(caller) {
                LimeTorrentsProvider(client, { 1704326400000L }, parser).getDetails("https://limetorrents.fun/item")
                LinuxTrackerProvider(client, parser).getDetails("https://linuxtracker.org/item")
            }
            assertEquals(2, networkThreads.size)
            /** thread 是当前记录的网络线程。 */
            assertTrue(networkThreads.all { thread -> thread.startsWith("sixth-caller") })
            assertEquals(2, parser.threads.size)
            /** thread 是当前记录的解析线程。 */
            assertTrue(parser.threads.all { thread -> thread.startsWith("sixth-parser") })
        } finally {
            caller.close(); parser.close()
        }
    }

    /** 响应返回后父任务取消，三个入口均不得把取消转换为空成功。 */
    @Test
    fun cancellationAfterResponsePropagates() = runBlocking {
        /** checks 是三个站点各自会在 fake 响应中取消的搜索。 */
        val checks: List<suspend () -> Unit> = listOf(
            /** Knaben POST 回调参数均为合成数据，返回前取消当前协程。 */
            { KnabenProvider(FakeTorrentSearchNetworkClient(postJsonHandler = { _, _ -> currentCoroutineContext().cancel(); "{\"hits\":[]}" })).search("x", Category.ALL) },
            /** LimeTorrents GET 回调返回前取消当前协程。 */
            { LimeTorrentsProvider(FakeTorrentSearchNetworkClient(textHandler = { currentCoroutineContext().cancel(); "<table></table>" })).search("x", Category.ALL) },
            /** LinuxTracker GET 回调返回前取消当前协程。 */
            { LinuxTrackerProvider(FakeTorrentSearchNetworkClient(textHandler = { currentCoroutineContext().cancel(); "<table></table>" })).search("x", Category.APPS) },
        )
        /** check 是当前需要验证的取消入口。 */
        for (check in checks) supervisorScope {
            /** result 隔离单个被取消子任务。 */ val result = async { check() }
            try { result.await(); fail("响应后取消不能返回成功") }
            /** failure 是结构化取消信号。 */ catch (failure: CancellationException) { assertTrue(result.isCancelled) }
        }
    }

    /** 使用真实单线程 caller 与记录 dispatcher 验证一次搜索。 */
    private suspend fun verifySearchDispatchers(
        /** 当前 Provider 搜索调用。 */ check: suspend (TorrentSearchNetworkClient, CoroutineDispatcher) -> Unit,
    ) {
        /** caller 承载网络回调；runnable 是该 executor 的工作项。 */
        val caller = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "sixth-caller") }.asCoroutineDispatcher()
        /** parser 记录计算线程。 */ val parser = SixthBatchRecordingDispatcher()
        /** networkThreads 记录 GET/POST fake 的实际线程。 */ val networkThreads = mutableListOf<String>()
        /** client 同时提供三站合法空响应。 */
        val client = FakeTorrentSearchNetworkClient(
            /** 文本回调记录线程并返回合法空 HTML。 */
            textHandler = { networkThreads += Thread.currentThread().name; "<table></table>" },
            /** POST 两参数只用于满足 fake 契约，回调记录线程并返回合法空 JSON。 */
            postJsonHandler = { _, _ -> networkThreads += Thread.currentThread().name; "{\"hits\":[]}" },
        )
        try {
            withContext(caller) { check(client, parser) }
            assertEquals(1, networkThreads.size)
            assertTrue(networkThreads.single().startsWith("sixth-caller"))
            assertEquals(1, parser.threads.size)
            assertTrue(parser.threads.single().startsWith("sixth-parser"))
        } finally {
            caller.close(); parser.close()
        }
    }
}

/** 第六批专用计算调度器，记录实际线程并可显式关闭。 */
private class SixthBatchRecordingDispatcher : CoroutineDispatcher(), AutoCloseable {
    /** 单线程 delegate 保证线程身份稳定；runnable 是解析工作项。 */
    private val delegate = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "sixth-parser") }.asCoroutineDispatcher()
    /** 实际执行 parser 工作的线程名。 */ val threads = mutableListOf<String>()

    /** 记录计算线程后执行原任务，并保留协程上下文。 */
    override fun dispatch(
        /** 目标协程上下文。 */ context: CoroutineContext,
        /** 待执行计算任务。 */ block: Runnable,
    ) {
        delegate.dispatch(context) { threads += Thread.currentThread().name; block.run() }
    }

    /** 释放测试私有 executor。 */
    override fun close() { delegate.close() }
}
