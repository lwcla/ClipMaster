package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.provider.builtin.btsow.BtsowProvider
import com.cla.clip.feature.torrent.search.provider.builtin.dmhy.DmhyProvider
import com.cla.clip.feature.torrent.search.provider.builtin.ext.ExtProvider
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

/** 第四批三 Provider 的调用协程网络边界与可注入计算 dispatcher 隔离测试。 */
class FourthBatchProviderDispatcherTest {
    /** Btsow POST 网络必须留在 caller，JSON tree 与领域映射进入 parser。 */
    @Test
    fun btsowSeparatesNetworkFromJsonParsingDispatcher() = runBlocking {
        /** Provider 调用使用的单线程 dispatcher。 */
        val callerDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "provider-caller") }
            .asCoroutineDispatcher()
        /** 记录 JSON 解析 continuation 的 dispatcher。 */
        val parsingDispatcher = FourthBatchRecordingDispatcher("provider-parser")
        /** 实际网络回调线程名。 */
        val networkThreads = mutableListOf<String>()
        /** 返回正常 Btsow JSON 的 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** 此回调只记录线程，固定响应不依赖请求地址与正文。 */
            postJsonHandler = { _, _ ->
                networkThreads += Thread.currentThread().name
                readProviderFixture("provider/btsow/search_normal.json")
            },
        )

        try {
            withContext(callerDispatcher) {
                BtsowProvider(networkClient, parsingDispatcher).search("fixture", Category.ALL)
            }
        } finally {
            callerDispatcher.close()
            parsingDispatcher.close()
        }

        assertEquals(1, networkThreads.size)
        /** threadName 是当前 POST 回调线程。 */
        assertTrue(networkThreads.all { threadName -> threadName.startsWith("provider-caller") })
        assertTrue(parsingDispatcher.executedThreadNames.isNotEmpty())
        /** threadName 是实际 JSON 解析线程。 */
        assertTrue(parsingDispatcher.executedThreadNames.all { threadName -> threadName.startsWith("provider-parser") })
    }

    /** Dmhy HTML 网络必须留在 caller，DOM 与领域映射进入 parser。 */
    @Test
    fun dmhySeparatesNetworkFromDomParsingDispatcher() = runBlocking {
        /** Provider 调用使用的单线程 dispatcher。 */
        val callerDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "provider-caller") }
            .asCoroutineDispatcher()
        /** 记录 DOM 解析 continuation 的 dispatcher。 */
        val parsingDispatcher = FourthBatchRecordingDispatcher("provider-parser")
        /** 实际 HTML 网络回调线程名。 */
        val networkThreads = mutableListOf<String>()
        /** 返回正常 Dmhy HTML 的 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            textHandler = {
                networkThreads += Thread.currentThread().name
                readProviderFixture("provider/dmhy/search_normal.html")
            },
        )

        try {
            withContext(callerDispatcher) {
                DmhyProvider(networkClient, parsingDispatcher).search("fixture", Category.ALL)
            }
        } finally {
            callerDispatcher.close()
            parsingDispatcher.close()
        }

        assertEquals(1, networkThreads.size)
        /** threadName 是当前 HTML 回调线程。 */
        assertTrue(networkThreads.all { threadName -> threadName.startsWith("provider-caller") })
        assertTrue(parsingDispatcher.executedThreadNames.isNotEmpty())
        /** threadName 是实际 DOM 解析线程。 */
        assertTrue(parsingDispatcher.executedThreadNames.all { threadName -> threadName.startsWith("provider-parser") })
    }

    /** Ext 页面和顺序表单网络留在 caller，DOM/HMAC/领域映射进入 parser。 */
    @Test
    fun extSeparatesTwoStageNetworkFromParsingDispatcher() = runBlocking {
        /** Provider 调用使用的单线程 dispatcher。 */
        val callerDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "provider-caller") }
            .asCoroutineDispatcher()
        /** 记录 Ext 计算 continuation 的 dispatcher。 */
        val parsingDispatcher = FourthBatchRecordingDispatcher("provider-parser")
        /** 页面与表单网络回调线程名。 */
        val networkThreads = mutableListOf<String>()
        /** 返回正常 Ext 页面与合法 magnet JSON 的 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            textHandler = {
                networkThreads += Thread.currentThread().name
                readProviderFixture("provider/ext/search_normal.html")
            },
            /** 此表单回调只记录线程，忽略合成请求字段。 */
            formHandler = { _, _ ->
                networkThreads += Thread.currentThread().name
                """{"url":"magnet:?xt=urn:btih:00112233445566778899aabbccddeeff00112233"}"""
            },
        )

        try {
            withContext(callerDispatcher) {
                ExtProvider(
                    networkClient = networkClient,
                    currentTimeMillis = { 1_700_000_000_000L },
                    parsingDispatcher = parsingDispatcher,
                ).search("fixture", Category.ALL)
            }
        } finally {
            callerDispatcher.close()
            parsingDispatcher.close()
        }

        assertEquals(2, networkThreads.size)
        /** threadName 覆盖页面和 magnet 表单两次调用。 */
        assertTrue(networkThreads.all { threadName -> threadName.startsWith("provider-caller") })
        assertTrue(parsingDispatcher.executedThreadNames.isNotEmpty())
        /** threadName 覆盖 DOM、HMAC 和领域映射 continuation。 */
        assertTrue(parsingDispatcher.executedThreadNames.all { threadName -> threadName.startsWith("provider-parser") })
    }
}

/** 第四批测试专用 dispatcher，记录实际执行线程并显式关闭 executor。 */
private class FourthBatchRecordingDispatcher(
    /** 专用计算线程稳定名称。 */
    threadName: String,
) : CoroutineDispatcher(), AutoCloseable {
    /** 唯一底层 dispatcher，防止测试结束后残留线程。 */
    private val delegate = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, threadName) }
        .asCoroutineDispatcher()
    /** 每次 continuation 实际执行时记录的线程名。 */
    val executedThreadNames = mutableListOf<String>()

    /** 把 continuation 转交专用线程，并在执行点记录线程。 */
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        delegate.dispatch(context) {
            executedThreadNames += Thread.currentThread().name
            block.run()
        }
    }

    /** 关闭专用 executor，避免 JVM 测试泄漏非守护线程。 */
    override fun close() {
        delegate.close()
    }
}
