package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.xxxclub.XXXClubProvider
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 04E7 XXXClub 的网络 caller、计算 dispatcher 与协作取消契约。 */
class ThirteenthProviderDispatcherTest {
    /** 搜索网络留在 caller，列表与领域解析进入注入 dispatcher。 */
    @Test
    fun searchSeparatesNetworkFromParsing() = runBlocking {
        // client 是调用线程 fake，dispatcher 是应承载解析的记录调度器。
        verifyDispatcher { client, dispatcher ->
            client to XXXClubProvider(client, dispatcher).search("x", Category.ALL)
        }
    }

    /** 详情网络留在 caller，详情解析进入注入 dispatcher。 */
    @Test
    fun detailsSeparateNetworkFromParsing() = runBlocking {
        // client 是调用线程 fake，dispatcher 是应承载解析的记录调度器。
        verifyDispatcher { client, dispatcher ->
            client to XXXClubProvider(client, dispatcher).getDetails("https://xxxclub.to/torrents/details/a")
        }
    }

    /** 搜索响应返回后父取消必须传播，不能变成空成功。 */
    @Test
    fun searchCancellationAfterResponsePropagates() = runBlocking {
        assertCancellation { XXXClubProvider(cancellingClient()).search("x", Category.ALL) }
    }

    /** 详情响应返回后父取消同样必须传播。 */
    @Test
    fun detailsCancellationAfterResponsePropagates() = runBlocking {
        assertCancellation {
            XXXClubProvider(cancellingClient()).getDetails("https://xxxclub.to/torrents/details/a")
        }
    }

    /** 用单线程 caller 和记录 dispatcher 验证公开入口的真实执行边界。 */
    private suspend fun verifyDispatcher(
        /** 当前 Provider 公开入口。 */ check: suspend (TorrentSearchNetworkClient, CoroutineDispatcher) -> Any?,
    ) {
        /** caller 是承载 Provider 调用和 fake 网络回调的线程。 */
        val caller = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "04e7-caller") }.asCoroutineDispatcher()
        /** parser 是记录计算 continuation 的专用 dispatcher。 */
        val parser = ThirteenthProviderRecordingDispatcher("04e7-parser")
        /** networkThreads 记录文本请求回调线程。 */
        val networkThreads = mutableListOf<String>()
        /** client 返回可安全处理的最小 HTML。 */
        val client = FakeTorrentSearchNetworkClient(
            textHandler = {
                networkThreads += Thread.currentThread().name
                "<html></html>"
            },
        )
        try {
            withContext(caller) { check(client, parser) }
            assertTrue(networkThreads.isNotEmpty())
            // thread 是当前 fake 网络回调实际所在的线程名。
            assertTrue(networkThreads.all { thread -> thread.startsWith("04e7-caller") })
            assertTrue(parser.threads.isNotEmpty())
            // thread 是当前 parser continuation 实际所在的线程名。
            assertTrue(parser.threads.all { thread -> thread.startsWith("04e7-parser") })
        } finally {
            caller.close()
            parser.close()
        }
    }

    /** 断言给定入口传播结构化取消。 */
    private suspend fun assertCancellation(
        /** 会在响应后被取消的 Provider 入口。 */ check: suspend () -> Unit,
    ) {
        supervisorScope {
            /** result 隔离被取消的子任务。 */
            val result = async { check() }
            try {
                result.await()
                fail("取消不能返回空成功")
            /** failure 是预期结构化取消。 */
            } catch (failure: CancellationException) {
                assertTrue(result.isCancelled)
            }
        }
    }

    /** 返回响应后立即取消当前协程的离线 fake。 */
    private fun cancellingClient(): FakeTorrentSearchNetworkClient = FakeTorrentSearchNetworkClient(
        textHandler = {
            currentCoroutineContext().cancel()
            "<html></html>"
        },
    )
}

/** 记录执行线程的 04E7 测试专用 dispatcher。 */
private class ThirteenthProviderRecordingDispatcher(
    /** 唯一计算线程名。 */ threadName: String,
) : CoroutineDispatcher(), AutoCloseable {
    /** delegate 承载唯一计算线程。 */
    private val delegate = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, threadName) }.asCoroutineDispatcher()
    /** threads 保存真正执行 continuation 的线程名。 */
    val threads = mutableListOf<String>()

    /** 记录线程后转交 continuation。 */
    override fun dispatch(
        /** 目标协程上下文。 */ context: CoroutineContext,
        /** 待执行计算任务。 */ block: Runnable,
    ) {
        delegate.dispatch(context) {
            threads += Thread.currentThread().name
            block.run()
        }
    }

    /** 释放测试 executor。 */
    override fun close() {
        delegate.close()
    }
}
