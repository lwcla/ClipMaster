package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.provider.builtin.anilibria.AniLibriaProvider
import com.cla.clip.feature.torrent.search.provider.builtin.animetosho.AnimeToshoProvider
import com.cla.clip.feature.torrent.search.provider.builtin.anirena.AniRenaProvider
import com.cla.clip.feature.torrent.search.provider.builtin.audiobookbay.AudioBookBayProvider
import com.cla.clip.feature.torrent.search.provider.builtin.bangumimoe.BangumiMoeProvider
import com.cla.clip.feature.torrent.search.provider.builtin.bitsearch.BitSearchProvider
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 三个 Provider 的网络调用线程与可注入计算解析线程隔离测试。 */
class ProviderParsingDispatcherTest {
    /** AniLibria JSON 解析必须切到计算 dispatcher，两个网络阶段都保留在调用上下文。 */
    @Test
    fun aniLibriaSeparatesNetworkCallsFromJsonParsingDispatcher() = runBlocking {
        /** 单线程 Provider 调用 dispatcher，用名字锁定网络编排上下文。 */
        val callerDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "provider-caller") }
            .asCoroutineDispatcher()
        /** 记录实际执行线程的 parser dispatcher。 */
        val parsingDispatcher = RecordingCoroutineDispatcher("provider-parser")
        /** 每次网络回调实际运行的线程名。 */
        val networkThreads = mutableListOf<String>()
        /** 按搜索和 release 阶段返回独立合成 JSON 的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** url 只区分搜索与 release 端点，线程名用于验证网络上下文。 */
            jsonHandler = { url ->
                networkThreads += Thread.currentThread().name
                if (url.contains("/app/search/releases")) """[{"id":1}]""" else "[]"
            },
        )

        try {
            withContext(callerDispatcher) {
                AniLibriaProvider(networkClient, parsingDispatcher).search("fixture", Category.ANIME)
            }
        } finally {
            callerDispatcher.close()
            parsingDispatcher.close()
        }

        assertEquals(2, networkThreads.size)
        /** threadName 是当前网络回调线程，调试后缀不影响 caller 前缀判断。 */
        assertTrue(networkThreads.all { threadName -> threadName.startsWith("provider-caller") })
        assertTrue(parsingDispatcher.executedThreadNames.isNotEmpty())
        /** threadName 是当前解析 continuation 线程，必须属于 parser executor。 */
        assertTrue(parsingDispatcher.executedThreadNames.all { threadName -> threadName.startsWith("provider-parser") })
    }

    /** AnimeTosho Jsoup DOM 与领域映射必须切到计算 dispatcher，HTML 请求留在调用线程。 */
    @Test
    fun animeToshoSeparatesNetworkCallFromDomParsingDispatcher() = runBlocking {
        /** 单线程 Provider 调用 dispatcher。 */
        val callerDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "provider-caller") }
            .asCoroutineDispatcher()
        /** 记录 Jsoup 解析实际执行线程的 dispatcher。 */
        val parsingDispatcher = RecordingCoroutineDispatcher("provider-parser")
        /** HTML 网络回调实际运行的线程名。 */
        val networkThreads = mutableListOf<String>()
        /** 返回正常搜索 fixture 的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            textHandler = {
                networkThreads += Thread.currentThread().name
                readProviderFixture("provider/animetosho/search_normal.html")
            },
        )

        try {
            withContext(callerDispatcher) {
                AnimeToshoProvider(networkClient, parsingDispatcher = parsingDispatcher).search("fixture", Category.ANIME)
            }
        } finally {
            callerDispatcher.close()
            parsingDispatcher.close()
        }

        assertEquals(1, networkThreads.size)
        /** threadName 是当前页面网络线程，必须保持 caller executor。 */
        assertTrue(networkThreads.all { threadName -> threadName.startsWith("provider-caller") })
        assertTrue(parsingDispatcher.executedThreadNames.isNotEmpty())
        /** threadName 是当前 DOM/映射线程，必须属于 parser executor。 */
        assertTrue(parsingDispatcher.executedThreadNames.all { threadName -> threadName.startsWith("provider-parser") })
    }

    /** AniRena DOM/逐条映射必须切到计算 dispatcher，页面和 magnet 跳转请求都留在调用线程。 */
    @Test
    fun aniRenaSeparatesNetworkCallsFromDomParsingDispatcher() = runBlocking {
        /** 单线程 Provider 调用 dispatcher。 */
        val callerDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "provider-caller") }
            .asCoroutineDispatcher()
        /** 记录 AniRena DOM 与逐条映射线程的 dispatcher。 */
        val parsingDispatcher = RecordingCoroutineDispatcher("provider-parser")
        /** 页面读取与 redirect 回调实际运行的线程名。 */
        val networkThreads = mutableListOf<String>()
        /** 返回正常页面和合法 magnet 的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            textHandler = {
                networkThreads += Thread.currentThread().name
                readProviderFixture("provider/anirena/search_normal.html")
            },
            redirectHandler = {
                networkThreads += Thread.currentThread().name
                "magnet:?xt=urn:btih:00112233445566778899aabbccddeeff10203040"
            },
        )

        try {
            withContext(callerDispatcher) {
                AniRenaProvider(networkClient, parsingDispatcher = parsingDispatcher).search("fixture", Category.ANIME)
            }
        } finally {
            callerDispatcher.close()
            parsingDispatcher.close()
        }

        assertEquals(2, networkThreads.size)
        /** threadName 覆盖页面与 redirect 两次网络回调，都必须保持 caller executor。 */
        assertTrue(networkThreads.all { threadName -> threadName.startsWith("provider-caller") })
        assertTrue(parsingDispatcher.executedThreadNames.isNotEmpty())
        /** threadName 覆盖 DOM draft 与最终映射，只能属于 parser executor。 */
        assertTrue(parsingDispatcher.executedThreadNames.all { threadName -> threadName.startsWith("provider-parser") })
    }

    /** AudioBookBay 结果/详情 hash DOM 解析进入 parser，页面与顺序详情请求都留在 caller。 */
    @Test
    fun audioBookBaySeparatesTwoStageNetworkFromDomParsingDispatcher() = runBlocking {
        /** 单线程 Provider 调用 dispatcher。 */
        val callerDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "provider-caller") }
            .asCoroutineDispatcher()
        /** 记录两类 DOM 解析 continuation 的 dispatcher。 */
        val parsingDispatcher = RecordingCoroutineDispatcher("provider-parser")
        /** 结果页及两个详情 hash 网络回调线程。 */
        val networkThreads = mutableListOf<String>()
        /** 按 URL 返回结果 fixture 或合法 hash 页。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** url 只用于区分结果与详情阶段。 */
            textHandler = { url ->
                networkThreads += Thread.currentThread().name
                if (url.contains("?s=")) {
                    readProviderFixture("provider/audiobookbay/search_normal.html")
                } else {
                    "<html><body><table><tr><td>Info Hash:</td><td>00112233445566778899aabbccddeeff00112233</td></tr></table></body></html>"
                }
            },
        )

        try {
            withContext(callerDispatcher) {
                AudioBookBayProvider(networkClient, parsingDispatcher).search("fixture", Category.BOOKS)
            }
        } finally {
            callerDispatcher.close()
            parsingDispatcher.close()
        }

        assertEquals(3, networkThreads.size)
        /** threadName 覆盖结果页和两个详情页网络回调。 */
        assertTrue(networkThreads.all { threadName -> threadName.startsWith("provider-caller") })
        assertTrue(parsingDispatcher.executedThreadNames.isNotEmpty())
        /** threadName 覆盖结果 draft、hash 与领域映射。 */
        assertTrue(parsingDispatcher.executedThreadNames.all { threadName -> threadName.startsWith("provider-parser") })
    }

    /** BangumiMoe POST 网络保持 caller，JSON tree 与领域映射进入 parser。 */
    @Test
    fun bangumiMoeSeparatesNetworkCallFromJsonParsingDispatcher() = runBlocking {
        /** 单线程 Provider 调用 dispatcher。 */
        val callerDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "provider-caller") }
            .asCoroutineDispatcher()
        /** 记录 JSON 解析线程。 */
        val parsingDispatcher = RecordingCoroutineDispatcher("provider-parser")
        /** POST 网络回调线程。 */
        val networkThreads = mutableListOf<String>()
        /** 返回正常 Bangumi fixture 的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** 两个下划线参数分别为固定 URL 与不进入断言的 payload。 */
            postJsonHandler = { _, _ ->
                networkThreads += Thread.currentThread().name
                readProviderFixture("provider/bangumimoe/search_normal.json")
            },
        )

        try {
            withContext(callerDispatcher) {
                BangumiMoeProvider(networkClient, parsingDispatcher).search("fixture", Category.ANIME)
            }
        } finally {
            callerDispatcher.close()
            parsingDispatcher.close()
        }

        assertEquals(1, networkThreads.size)
        /** threadName 是唯一 POST 网络线程。 */
        assertTrue(networkThreads.all { threadName -> threadName.startsWith("provider-caller") })
        assertTrue(parsingDispatcher.executedThreadNames.isNotEmpty())
        /** threadName 是 JSON tree 与映射线程。 */
        assertTrue(parsingDispatcher.executedThreadNames.all { threadName -> threadName.startsWith("provider-parser") })
    }

    /** BitSearch 五页网络任务继承 caller，五份 DOM 与映射进入 parser。 */
    @Test
    fun bitSearchSeparatesPageNetworkCallsFromDomParsingDispatcher() = runBlocking {
        /** 单线程 Provider 调用 dispatcher。 */
        val callerDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "provider-caller") }
            .asCoroutineDispatcher()
        /** 记录五页 DOM 解析线程。 */
        val parsingDispatcher = RecordingCoroutineDispatcher("provider-parser")
        /** 五页网络回调线程。 */
        val networkThreads = mutableListOf<String>()
        /** 每页返回同一正常最小 fixture。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            textHandler = {
                networkThreads += Thread.currentThread().name
                readProviderFixture("provider/bitsearch/search_normal.html")
            },
        )

        try {
            withContext(callerDispatcher) {
                BitSearchProvider(networkClient, parsingDispatcher = parsingDispatcher).search("fixture", Category.ALL)
            }
        } finally {
            callerDispatcher.close()
            parsingDispatcher.close()
        }

        assertEquals(5, networkThreads.size)
        /** threadName 是每个页请求的网络回调线程。 */
        assertTrue(networkThreads.all { threadName -> threadName.startsWith("provider-caller") })
        assertTrue(parsingDispatcher.executedThreadNames.isNotEmpty())
        /** threadName 是每页 DOM 与领域映射线程。 */
        assertTrue(parsingDispatcher.executedThreadNames.all { threadName -> threadName.startsWith("provider-parser") })
    }
}

/** 用专用单线程执行计算任务并记录实际线程，测试结束后显式关闭。 */
private class RecordingCoroutineDispatcher(
    /** 专用解析线程名称，也是断言使用的稳定标识。 */
    threadName: String,
) : CoroutineDispatcher(), AutoCloseable {
    /** 唯一底层线程 dispatcher，避免测试依赖全局 Default 池。 */
    private val delegate = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, threadName) }
        .asCoroutineDispatcher()
    /** 每个被实际执行的解析 continuation 所在线程名。 */
    val executedThreadNames = mutableListOf<String>()

    /** 将 continuation 转交专用线程，并在执行点记录而不是在提交点猜测线程。 */
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        delegate.dispatch(context) {
            executedThreadNames += Thread.currentThread().name
            block.run()
        }
    }

    /** 关闭专用 executor，防止 JVM 测试残留非守护线程。 */
    override fun close() {
        delegate.close()
    }
}
