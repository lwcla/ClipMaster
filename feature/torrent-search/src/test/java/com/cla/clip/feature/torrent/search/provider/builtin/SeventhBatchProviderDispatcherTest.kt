package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.megapeer.MegaPeerProvider
import com.cla.clip.feature.torrent.search.provider.builtin.mikan.MikanProvider
import com.cla.clip.feature.torrent.search.provider.builtin.mypornclub.MyPornClubProvider
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

/** 第七批网络/计算线程隔离、详情并发和取消边界。 */
class SeventhBatchProviderDispatcherTest {
    /** 三站搜索网络留在 caller，DOM 与映射进入注入 parser。 */
    @Test
    fun searchesSeparateNetworkFromParsing() = runBlocking {
        /** checks 是三个站点各自的搜索入口。 */
        val checks: List<suspend (TorrentSearchNetworkClient, CoroutineDispatcher) -> Unit> = listOf(
            /** MegaPeer 搜索入口。 */
            { client, dispatcher -> MegaPeerProvider(client, dispatcher).search("x", Category.ALL) },
            /** Mikan 搜索入口。 */
            { client, dispatcher -> MikanProvider(client, dispatcher).search("x", Category.ANIME) },
            /** MyPornClub 搜索入口。 */
            { client, dispatcher -> MyPornClubProvider(client, { 0L }, dispatcher).search("x", Category.PORN) },
        )
        /** check 是当前站点入口。 */
        for (check in checks) verifySearchDispatchers(check)
    }

    /** MegaPeer 与 MyPornClub 必须同时启动详情并保持列表顺序。 */
    @Test
    fun detailFanOutProvidesConcurrencyAndPreservesOrder() = runBlocking {
        verifyConcurrentOrder(isMegaPeer = true)
        verifyConcurrentOrder(isMegaPeer = false)
    }

    /** 任一详情网络失败必须让整个 Provider 失败，不能返回部分成功。 */
    @Test
    fun detailNetworkFailureFailsWholeProvider() = runBlocking {
        /** list 是含两项合法 draft 的 MegaPeer 合成列表。 */
        val list = megaList("Alpha" to "/torrent/a", "Beta" to "/torrent/b")
        /** client 对第二项详情抛出传输失败。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { url ->
            when {
                url.endsWith("/torrent/a") -> megaDetails()
                url.endsWith("/torrent/b") -> throw IOException()
                else -> list
            }
        })
        try {
            MegaPeerProvider(client).search("x", Category.ALL)
            fail("单项详情网络失败必须传播")
        /** failure 仅用于确认传输失败原样传播。 */
        } catch (failure: IOException) {
            assertTrue(failure.message == null)
            /** 预期异常类型证明未伪造部分成功。 */
        }
    }

    /** 响应返回后父任务取消不得转换为空成功。 */
    @Test
    fun cancellationAfterResponsePropagates() = runBlocking {
        /** checks 是三个会在 fake 响应中取消的搜索。 */
        val checks: List<suspend () -> Unit> = listOf(
            /** MegaPeer 取消入口。 */
            { MegaPeerProvider(cancellingClient()).search("x", Category.ALL) },
            /** Mikan 取消入口。 */
            { MikanProvider(cancellingClient()).search("x", Category.ANIME) },
            /** MyPornClub 取消入口。 */
            { MyPornClubProvider(cancellingClient()).search("x", Category.PORN) },
        )
        /** check 是当前取消入口。 */
        for (check in checks) supervisorScope {
            /** result 隔离单个被取消子任务。 */
            val result = async { check() }
            try {
                result.await()
                fail("父取消不能返回成功")
            /** failure 是结构化取消信号。 */
            } catch (failure: CancellationException) {
                assertTrue(result.isCancelled)
            }
        }
    }

    /** 用真实单线程 caller 与 recording dispatcher 验证搜索边界。 */
    private suspend fun verifySearchDispatchers(
        /** 当前 Provider 搜索入口。 */
        check: suspend (TorrentSearchNetworkClient, CoroutineDispatcher) -> Unit,
    ) {
        /** caller 承载网络回调。 */
        val caller = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "seventh-caller")
        }.asCoroutineDispatcher()
        /** parser 记录计算线程。 */
        val parser = SeventhBatchRecordingDispatcher()
        /** networkThreads 记录 fake 回调线程。 */
        val networkThreads = mutableListOf<String>()
        /** client 返回三个站点都可接受的空 HTML。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = {
            networkThreads += Thread.currentThread().name
            "<html></html>"
        })
        try {
            withContext(caller) { check(client, parser) }
            assertEquals(1, networkThreads.size)
            assertTrue(networkThreads.single().startsWith("seventh-caller"))
            assertTrue(parser.threads.isNotEmpty())
            /** thread 是当前记录的解析线程名。 */
            assertTrue(parser.threads.all { thread -> thread.startsWith("seventh-parser") })
        } finally {
            caller.close()
            parser.close()
        }
    }

    /** 验证一个两阶段站点同时开始两项详情且按列表顺序输出。 */
    private suspend fun verifyConcurrentOrder(
        /** true 验证 MegaPeer，false 验证 MyPornClub。 */
        isMegaPeer: Boolean,
    ) {
        /** active 是当前并行详情数。 */
        val active = AtomicInteger()
        /** maximum 是观察到的最大并行详情数。 */
        val maximum = AtomicInteger()
        /** bothStarted 在两项详情都开始后释放。 */
        val bothStarted = CompletableDeferred<Unit>()
        /** list 是当前站点的两项 draft 页面。 */
        val list = if (isMegaPeer) {
            megaList("Alpha" to "/torrent/a", "Beta" to "/torrent/b")
        } else {
            clubList("Alpha" to "/torrent/a", "Beta" to "/torrent/b")
        }
        /** client 使用屏障证明两个详情子任务有并发机会。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { url ->
            if (!url.contains("/torrent/")) {
                list
            } else {
                /** count 是进入后的并行数。 */
                val count = active.incrementAndGet()
                /** previous 是此前观察到的最大并行数。 */
                maximum.updateAndGet { previous -> maxOf(previous, count) }
                if (count == 2) bothStarted.complete(Unit)
                withTimeout(2_000L) { bothStarted.await() }
                active.decrementAndGet()
                if (isMegaPeer) megaDetails() else clubDetails()
            }
        })
        /** names 是最终结果原始顺序。 */
        val names = if (isMegaPeer) {
            /** item 是当前 MegaPeer 结果。 */
            MegaPeerProvider(client).search("x", Category.ALL).map { item -> item.name }
        } else {
            /** item 是当前 MyPornClub 结果。 */
            MyPornClubProvider(client).search("x", Category.PORN).map { item -> item.name }
        }
        assertEquals(listOf("Alpha", "Beta"), names)
        assertTrue(maximum.get() >= 2)
    }

    /** 返回会在响应后取消当前协程的 fake。 */
    private fun cancellingClient(): FakeTorrentSearchNetworkClient =
        FakeTorrentSearchNetworkClient(textHandler = {
            currentCoroutineContext().cancel()
            "<html></html>"
        })

    /** 构造 MegaPeer 两项列表。 */
    private fun megaList(
        /** 标题与详情路径。 */
        vararg items: Pair<String, String>,
    ): String = "<div id='index'><table><tbody>" + items.joinToString("") {
        /** item 是当前标题与详情路径。 */ item ->
        "<tr class='table_fon'><td>1 Янв 24</td><td><a href='/download/x'>f</a><a href='${item.second}'>${item.first}</a></td><td>1 GB</td><td><font>x</font><font>1</font><font>x</font><font>0</font></td></tr>"
    } + "</tbody></table></div>"

    /** 构造 MegaPeer 合法详情。 */
    private fun megaDetails(): String =
        "<h1>Detail</h1><a href='magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567'>m</a>"

    /** 构造 MyPornClub 两项中性列表。 */
    private fun clubList(
        /** 标题与详情路径。 */
        vararg items: Pair<String, String>,
    ): String = "<div class='torrents_list'>" + items.joinToString("") {
        /** item 是当前标题与详情路径。 */ item ->
        "<div class='torrent_element'><div class='torrent_element_text_div'><a>x</a><a href='${item.second}'><span class='torrent_element_text_span'>${item.first}</span></a></div></div>"
    } + "</div>"

    /** 构造 MyPornClub 合法中性详情。 */
    private fun clubDetails(): String =
        "<div class='torrent_text'>Detail #meta</div><div class='torrent_info_div'><div>[hash_info]: 0123456789abcdef0123456789abcdef01234567</div></div>"
}

/** 第七批专用计算调度器，记录线程并可显式关闭。 */
private class SeventhBatchRecordingDispatcher : CoroutineDispatcher(), AutoCloseable {
    /** delegate 是唯一计算线程。 */
    private val delegate = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "seventh-parser")
    }.asCoroutineDispatcher()

    /** 实际执行 parser 工作的线程名。 */
    val threads = mutableListOf<String>()

    /** 记录计算线程后执行原任务。 */
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

    /** 释放测试私有 executor。 */
    override fun close() {
        delegate.close()
    }
}
