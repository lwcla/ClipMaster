package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.provider.builtin.thepiratebay.ThePirateBayProvider
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import com.cla.clip.feature.torrent.search.provider.builtin.torrentscsv.TorrentsCsvProvider
import com.cla.clip.feature.torrent.search.provider.builtin.yts.YtsProvider
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 04E1 三个 JSON Provider 的调用协程网络边界与可注入计算 dispatcher 测试。 */
class JsonProviderDispatcherTest {
    /** 三站网络回调必须留在 caller，JSON tree 与领域映射进入 parser dispatcher。 */
    @Test
    fun separatesNetworkFromParsingForAllThreeProviders() = runBlocking {
        /** Provider 调用使用的单线程 dispatcher。 */
        val callerDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "provider-caller") }.asCoroutineDispatcher()
        /** 三个独立 parser dispatcher，确保每个入口均实际使用注入值。 */
        val parserDispatchers = List(3) { index -> RecordingDispatcher("provider-parser-$index") }
        /** 三站网络回调线程名。 */
        val networkThreads = mutableListOf<String>()
        /** 三站按 URL 返回各自完整合成响应。 */
        val network = FakeTorrentSearchNetworkClient(jsonHandler = { url ->
            networkThreads += Thread.currentThread().name
            when {
                "torrents-csv.com" in url -> readProviderFixture("provider/torrentscsv/search_normal.json")
                "apibay.org" in url -> readProviderFixture("provider/thepiratebay/search_mixed.json")
                else -> readProviderFixture("provider/yts/search_mixed.json")
            }
        })
        try {
            withContext(callerDispatcher) {
                TorrentsCsvProvider(network, parserDispatchers[0]).search("fixture", Category.ALL)
                ThePirateBayProvider(network, parserDispatchers[1]).search("fixture", Category.ALL)
                YtsProvider(network, parserDispatchers[2]).search("fixture", Category.MOVIES)
            }
        } finally {
            callerDispatcher.close()
            parserDispatchers.forEach(RecordingDispatcher::close)
        }
        assertEquals(3, networkThreads.size)
        /** threadName 是当前实际网络回调线程。 */
        assertTrue(networkThreads.all { threadName -> threadName.startsWith("provider-caller") })
        /** dispatcher 是当前站点的记录器，必须都观察到 parser continuation。 */
        parserDispatchers.forEachIndexed { index, dispatcher -> assertTrue(dispatcher.executedThreadNames.all { threadName -> threadName.startsWith("provider-parser-$index") } && dispatcher.executedThreadNames.isNotEmpty()) }
    }

    /** TPB 与 Yts 详情网络必须留在 caller，响应身份解析必须进入各自注入 dispatcher。 */
    @Test
    fun separatesDetailsNetworkFromParsingForTpbAndYts() = runBlocking {
        /** callerDispatcher 是详情网络回调唯一调用线程。 */
        val callerDispatcher = Executors.newSingleThreadExecutor {
            /** runnable 是 caller executor 待执行任务。 */
            runnable -> Thread(runnable, "details-caller")
        }.asCoroutineDispatcher()
        /** parserDispatchers 分别记录 TPB 与 Yts 的详情 JSON 解析线程。 */
        val parserDispatchers = List(2) {
            /** index 是当前站点对应的 dispatcher 序号。 */
            index -> RecordingDispatcher("details-parser-$index")
        }
        /** networkThreads 记录两个公开详情入口的 fake 回调线程。 */
        val networkThreads = mutableListOf<String>()
        /** network 按固定 API path 返回对应的中性详情 fixture。 */
        val network = FakeTorrentSearchNetworkClient(jsonHandler = {
            /** url 是 Provider 构造并交给共享网络边界的详情 API 地址。 */
            url ->
            networkThreads += Thread.currentThread().name
            if ("/t.php" in url) {
                readProviderFixture("provider/thepiratebay/details_normal.json")
            } else {
                readProviderFixture("provider/yts/details_normal.json")
            }
        })
        try {
            withContext(callerDispatcher) {
                ThePirateBayProvider(
                    networkClient = network,
                    parsingDispatcher = parserDispatchers[0],
                ).getDetails("https://thepiratebay.org/description.php?id=42")
                YtsProvider(
                    networkClient = network,
                    parsingDispatcher = parserDispatchers[1],
                ).getDetails(
                    "https://yts.bz/movies/synthetic-one?movieid=7&infohash=00112233445566778899aabbccddeeff00112233",
                )
            }
        } finally {
            callerDispatcher.close()
            parserDispatchers.forEach {
                /** dispatcher 是当前待释放的测试专用线程。 */
                dispatcher -> dispatcher.close()
            }
        }
        assertEquals(2, networkThreads.size)
        assertTrue(networkThreads.all {
            /** threadName 是当前详情网络回调线程。 */
            threadName -> threadName.startsWith("details-caller")
        })
        parserDispatchers.forEachIndexed {
            /** index 定位站点，dispatcher 提供实际解析线程记录。 */
            index, dispatcher ->
            assertTrue(dispatcher.executedThreadNames.isNotEmpty())
            assertTrue(dispatcher.executedThreadNames.all {
                /** threadName 是当前 parser continuation 的执行线程。 */
                threadName -> threadName.startsWith("details-parser-$index")
            })
        }
    }
}

/** 04E1 测试专用 dispatcher，记录实际线程并显式关闭 executor。 */
private class RecordingDispatcher(
    /** 专用计算线程稳定名称。 */
    threadName: String,
) : CoroutineDispatcher(), AutoCloseable {
    /** 唯一底层 dispatcher，避免测试结束后残留线程。 */
    private val delegate = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, threadName) }.asCoroutineDispatcher()
    /** 实际执行 continuation 的线程名集合。 */
    val executedThreadNames = mutableListOf<String>()

    /** 将 continuation 转交专用线程并在执行点记录。 */
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        delegate.dispatch(context) {
            executedThreadNames += Thread.currentThread().name
            block.run()
        }
    }

    /** 关闭测试 executor，防止 JVM 无法退出。 */
    override fun close() = delegate.close()
}
