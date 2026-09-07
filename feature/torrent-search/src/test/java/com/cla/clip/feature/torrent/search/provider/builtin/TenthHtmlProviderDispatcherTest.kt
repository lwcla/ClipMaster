package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.tokyotoshokan.TokyoToshokanProvider
import com.cla.clip.feature.torrent.search.provider.builtin.torrentdatabase.TorrentDatabaseProvider
import com.cla.clip.feature.torrent.search.provider.builtin.uindex.UIndexProvider
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

/** 04E3 三站网络 caller、DOM 计算 dispatcher 与协作取消测试。 */
class TenthHtmlProviderDispatcherTest {
    /** 三站 search 网络留在 caller，DOM 与领域映射进入注入 dispatcher。 */
    @Test
    fun searchSeparatesNetworkFromParsingForAllProviders() = runBlocking {
        /** checks 是三站各自的搜索入口。 */
        val checks: List<suspend (TorrentSearchNetworkClient, CoroutineDispatcher) -> Unit> = listOf(
            /** TokyoToshokan 搜索入口。 */
            { client, dispatcher ->
                TokyoToshokanProvider(client, dispatcher).search("x", Category.ALL)
            },
            /** UIndex 搜索入口。 */
            { client, dispatcher ->
                UIndexProvider(client, { 0L }, dispatcher).search("x", Category.ALL)
            },
            /** TorrentDatabase 搜索入口。 */
            { client, dispatcher ->
                TorrentDatabaseProvider(client, dispatcher).search("x", Category.ALL)
            },
        )

        /** check 是当前入口，index 用于隔离线程名。 */
        checks.forEachIndexed { index, check ->
            verifyDispatcher(index, check)
        }
    }

    /** 三站 details 网络留在 caller，详情 DOM 与领域映射进入注入 dispatcher。 */
    @Test
    fun detailsSeparateNetworkFromParsingForAllProviders() = runBlocking {
        /** checks 是三站详情入口及其合法 URL。 */
        val checks: List<suspend (TorrentSearchNetworkClient, CoroutineDispatcher) -> Unit> = listOf(
            /** TokyoToshokan 详情入口。 */
            { client, dispatcher ->
                TokyoToshokanProvider(client, dispatcher).getDetails("https://tokyotosho.info/a")
            },
            /** UIndex 详情入口。 */
            { client, dispatcher ->
                UIndexProvider(client, { 0L }, dispatcher).getDetails("https://uindex.org/a")
            },
            /** TorrentDatabase 详情入口。 */
            { client, dispatcher ->
                TorrentDatabaseProvider(client, dispatcher).getDetails("https://developify.ca/a")
            },
        )

        /** check 是当前入口，index 与搜索线程编号错开。 */
        checks.forEachIndexed { index, check ->
            verifyDispatcher(index + checks.size, check)
        }
    }

    /** 响应返回后 search 父取消必须传播，不能被空页容错吞掉。 */
    @Test
    fun searchCancellationAfterResponsePropagatesForAllProviders() = runBlocking {
        /** checks 是三个会在 fake 响应后取消的搜索入口。 */
        val checks: List<suspend () -> Unit> = listOf(
            /** TokyoToshokan 取消入口。 */
            { TokyoToshokanProvider(cancellingClient()).search("x", Category.ALL) },
            /** UIndex 取消入口。 */
            { UIndexProvider(cancellingClient()).search("x", Category.ALL) },
            /** TorrentDatabase 取消入口。 */
            { TorrentDatabaseProvider(cancellingClient()).search("x", Category.ALL) },
        )

        /** check 是当前取消入口。 */
        for (check in checks) {
            assertCancellation(check)
        }
    }

    /** 响应返回后 details 父取消必须传播。 */
    @Test
    fun detailsCancellationAfterResponsePropagatesForAllProviders() = runBlocking {
        /** checks 是三个会在 fake 响应后取消的详情入口。 */
        val checks: List<suspend () -> Unit> = listOf(
            /** TokyoToshokan 详情取消入口。 */
            { TokyoToshokanProvider(cancellingClient()).getDetails("https://tokyotosho.info/a") },
            /** UIndex 详情取消入口。 */
            { UIndexProvider(cancellingClient()).getDetails("https://uindex.org/a") },
            /** TorrentDatabase 详情取消入口。 */
            { TorrentDatabaseProvider(cancellingClient()).getDetails("https://developify.ca/a") },
        )

        /** check 是当前取消入口。 */
        for (check in checks) {
            assertCancellation(check)
        }
    }

    /** 用单线程 caller 和记录 dispatcher 验证网络与计算边界。 */
    private suspend fun verifyDispatcher(
        /** 当前线程名序号。 */
        index: Int,
        /** 当前待执行 Provider 入口。 */
        check: suspend (TorrentSearchNetworkClient, CoroutineDispatcher) -> Unit,
    ) {
        /** caller 是网络回调线程。 */
        val caller = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "04e3-caller-$index")
        }.asCoroutineDispatcher()
        /** parser 是当前站点计算线程。 */
        val parser = TenthBatchRecordingDispatcher("04e3-parser-$index")
        /** networkThreads 记录 fake 回调位置。 */
        val networkThreads = mutableListOf<String>()
        /** client 返回三站都接受的空 HTML。 */
        val client = FakeTorrentSearchNetworkClient(
            textHandler = {
                networkThreads += Thread.currentThread().name
                "<html></html>"
            },
        )
        try {
            withContext(caller) {
                check(client, parser)
            }
            assertEquals(1, networkThreads.size)
            assertTrue(networkThreads.single().startsWith("04e3-caller-$index"))
            assertTrue(parser.threads.isNotEmpty())
            assertTrue(parser.threads.all { it.startsWith("04e3-parser-$index") })
        } finally {
            caller.close()
            parser.close()
        }
    }

    /** 断言给定入口传播结构化取消。 */
    private suspend fun assertCancellation(
        /** 会在响应后取消的 Provider 入口。 */
        check: suspend () -> Unit,
    ) {
        supervisorScope {
            /** result 隔离被取消子任务。 */
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
    private fun cancellingClient(): FakeTorrentSearchNetworkClient =
        FakeTorrentSearchNetworkClient(
            textHandler = {
                currentCoroutineContext().cancel()
                "<html></html>"
            },
        )
}

/** 记录执行线程的 04E3 测试专用 dispatcher。 */
private class TenthBatchRecordingDispatcher(
    /** 唯一计算线程名。 */
    threadName: String,
) : CoroutineDispatcher(), AutoCloseable {
    /** delegate 承载唯一计算线程。 */
    private val delegate = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName)
    }.asCoroutineDispatcher()

    /** threads 保存真正执行 continuation 的线程名。 */
    val threads = mutableListOf<String>()

    /** 记录线程后转交 continuation。 */
    override fun dispatch(
        /** 目标协程上下文。 */
        context: CoroutineContext,
        /** 待执行计算任务。 */
        block: Runnable,
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
