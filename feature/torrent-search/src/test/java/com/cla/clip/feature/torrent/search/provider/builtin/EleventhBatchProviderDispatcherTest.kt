package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.subsplease.SubsPleaseProvider
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.therarbag.TheRarBgProvider
import com.cla.clip.feature.torrent.search.provider.builtin.thirteenthirtysevenx.ThirteenThirtySevenXProvider
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

/** 04E5 三站网络 caller、计算 dispatcher 与协作取消契约。 */
class EleventhBatchProviderDispatcherTest {
    /** 固定时钟只用于 1337x 空页构造。 */
    private val now = 1704326400000L

    /** 三站搜索网络留在 caller，JSON/DOM 与领域映射进入注入 dispatcher。 */
    @Test
    fun searchSeparatesNetworkFromParsingForAllProviders() = runBlocking {
        /** checks 是三站各自的搜索入口。 */
        val checks: List<suspend (TorrentSearchNetworkClient, CoroutineDispatcher) -> Unit> = listOf(
            /** SubsPlease JSON 搜索入口。 */
            { client, dispatcher -> SubsPleaseProvider(client, dispatcher).search("x", Category.ANIME) },
            /** TheRarBg HTML 搜索入口。 */
            { client, dispatcher -> TheRarBgProvider(client, dispatcher).search("x", Category.ALL) },
            /** 1337x HTML 搜索入口。 */
            { client, dispatcher -> ThirteenThirtySevenXProvider(client, { now }, dispatcher).search("x", Category.ALL) },
        )
        /** check 是当前入口，index 用于隔离线程名。 */
        checks.forEachIndexed { index, check -> verifyDispatcher(index, check) }
    }

    /** 三站详情网络留在 caller，详情解析进入注入 dispatcher。 */
    @Test
    fun detailsSeparateNetworkFromParsingForAllProviders() = runBlocking {
        /** checks 是三站详情入口与合法 URL。 */
        val checks: List<suspend (TorrentSearchNetworkClient, CoroutineDispatcher) -> Unit> = listOf(
            /** SubsPlease 详情会先解析 HTML，再读取并解析 show JSON。 */
            { client, dispatcher ->
                SubsPleaseProvider(client, dispatcher).getDetails("https://subsplease.org/shows/x?ep=1&res=1080")
            },
            /** TheRarBg 详情入口。 */
            { client, dispatcher -> TheRarBgProvider(client, dispatcher).getDetails("https://therarbg.com/a") },
            /** 1337x 详情入口。 */
            { client, dispatcher -> ThirteenThirtySevenXProvider(client, { now }, dispatcher).getDetails("https://1337x.to/a") },
        )
        /** check 是当前入口，index 与搜索编号错开。 */
        checks.forEachIndexed { index, check -> verifyDispatcher(index + checks.size, check) }
    }

    /** 搜索响应返回后父取消必须传播，不能变成空成功。 */
    @Test
    fun searchCancellationAfterResponsePropagatesForAllProviders() = runBlocking {
        /** checks 是三个会在 fake 响应后取消的搜索入口。 */
        val checks: List<suspend () -> Unit> = listOf(
            /** SubsPlease JSON 取消入口。 */
            { SubsPleaseProvider(cancellingClient()).search("x", Category.ANIME) },
            /** TheRarBg HTML 取消入口。 */
            { TheRarBgProvider(cancellingClient()).search("x", Category.ALL) },
            /** 1337x HTML 取消入口。 */
            { ThirteenThirtySevenXProvider(cancellingClient(), { now }).search("x", Category.ALL) },
        )
        /** check 是当前待验证入口。 */
        for (check in checks) assertCancellation(check)
    }

    /** 详情响应返回后父取消同样必须传播。 */
    @Test
    fun detailsCancellationAfterResponsePropagatesForAllProviders() = runBlocking {
        /** checks 是三个会在第一段详情响应后取消的入口。 */
        val checks: List<suspend () -> Unit> = listOf(
            /** SubsPlease 详情取消入口。 */
            { SubsPleaseProvider(cancellingClient()).getDetails("https://subsplease.org/shows/x?ep=1&res=1080") },
            /** TheRarBg 详情取消入口。 */
            { TheRarBgProvider(cancellingClient()).getDetails("https://therarbg.com/a") },
            /** 1337x 详情取消入口。 */
            { ThirteenThirtySevenXProvider(cancellingClient(), { now }).getDetails("https://1337x.to/a") },
        )
        /** check 是当前待验证入口。 */
        for (check in checks) assertCancellation(check)
    }

    /** 用单线程 caller 和记录 dispatcher 验证真实执行边界。 */
    private suspend fun verifyDispatcher(
        /** 当前线程名序号。 */ index: Int,
        /** 当前 Provider 公开入口。 */ check: suspend (TorrentSearchNetworkClient, CoroutineDispatcher) -> Unit,
    ) {
        /** caller 是承载 Provider 调用和 fake 网络回调的线程。 */
        val caller = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "04e5-caller-$index") }.asCoroutineDispatcher()
        /** parser 是记录计算 continuation 的专用 dispatcher。 */
        val parser = EleventhBatchRecordingDispatcher("04e5-parser-$index")
        /** networkThreads 同时记录文本和 JSON 请求回调线程。 */
        val networkThreads = mutableListOf<String>()
        /** client 返回三站都可安全处理的最小响应。 */
        val client = FakeTorrentSearchNetworkClient(
            // url 用于让 SubsPlease 获得可继续到第二段 JSON 的最小 HTML。
            textHandler = { url ->
                networkThreads += Thread.currentThread().name
                if (url.contains("subsplease.org")) {
                    "<table id='show-release-table' sid='1'></table><h1 class='entry-title'>x</h1>"
                } else {
                    "<html></html>"
                }
            },
            jsonHandler = {
                networkThreads += Thread.currentThread().name
                "{\"episode\":{}}"
            },
        )
        try {
            withContext(caller) { check(client, parser) }
            assertTrue(networkThreads.isNotEmpty())
            // thread 是当前 fake 网络回调实际所在的线程名。
            assertTrue(networkThreads.all { thread -> thread.startsWith("04e5-caller-$index") })
            assertTrue(parser.threads.isNotEmpty())
            // thread 是当前 parser continuation 实际所在的线程名。
            assertTrue(parser.threads.all { thread -> thread.startsWith("04e5-parser-$index") })
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
        jsonHandler = {
            currentCoroutineContext().cancel()
            "{}"
        },
    )
}

/** 记录执行线程的 04E5 测试专用 dispatcher。 */
private class EleventhBatchRecordingDispatcher(
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
