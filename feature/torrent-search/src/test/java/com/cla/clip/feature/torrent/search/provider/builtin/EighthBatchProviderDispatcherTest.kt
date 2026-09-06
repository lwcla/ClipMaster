package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.nekobt.NekoBtProvider
import com.cla.clip.feature.torrent.search.provider.builtin.nyaa.NyaaProvider
import com.cla.clip.feature.torrent.search.provider.builtin.sukebei.SukebeiProvider
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第八批三 Provider 的调用线程、parser dispatcher 与网络取消契约测试。 */
class EighthBatchProviderDispatcherTest {
    /** 三站网络回调留在 caller，DOM 与领域映射进入注入的 parser dispatcher。 */
    @Test
    fun providersSeparateNetworkFromDomParsingDispatcher() = runBlocking {
        /** providerCases 分别记录三站构造入口和合法详情地址。 */
        val providerCases = listOf(
            EighthProviderCase(
                /** networkClient 与 parsingDispatcher 分别固定 NekoBT 网络和计算边界。 */
                factory = { networkClient, parsingDispatcher -> NekoBtProvider(networkClient, parsingDispatcher) },
                detailsUrl = "https://nekobt.to/torrent/42",
            ),
            EighthProviderCase(
                /** networkClient 与 parsingDispatcher 分别固定 Nyaa 网络和计算边界。 */
                factory = { networkClient, parsingDispatcher -> NyaaProvider(networkClient, parsingDispatcher) },
                detailsUrl = "https://nyaa.si/view/42",
            ),
            EighthProviderCase(
                /** networkClient 与 parsingDispatcher 分别固定 Sukebei 网络和计算边界。 */
                factory = { networkClient, parsingDispatcher -> SukebeiProvider(networkClient, parsingDispatcher) },
                detailsUrl = "https://sukebei.nyaa.si/view/42",
            ),
        )
        /** providerCase 是当前待验证的站点入口和详情地址。 */
        providerCases.forEach { providerCase ->
            /** callerDispatcher 模拟 Provider 调用协程。 */
            val callerDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "provider-caller") }
                .asCoroutineDispatcher()
            /** parsingDispatcher 记录 DOM continuation 的实际线程。 */
            val parsingDispatcher = EighthBatchRecordingDispatcher("provider-parser")
            /** networkThreads 记录 fake 被调用时所在线程。 */
            val networkThreads = mutableListOf<String>()
            /** networkClient 返回合法空表，避免结果内容干扰线程断言。 */
            val networkClient = FakeTorrentSearchNetworkClient(
                textHandler = {
                    networkThreads += Thread.currentThread().name
                    "<table class='torrent-list'><tbody></tbody></table>"
                },
            )
            try {
                withContext(callerDispatcher) {
                    /** provider 同时验证搜索和详情入口的线程分层。 */
                    val provider = providerCase.factory(networkClient, parsingDispatcher)
                    provider.search("fixture", Category.ALL)
                    provider.getDetails(providerCase.detailsUrl)
                }
            } finally {
                callerDispatcher.close()
                parsingDispatcher.close()
            }
            assertEquals(2, networkThreads.size)
            /** threadName 是当前网络 fake 执行线程。 */
            assertTrue(networkThreads.all { threadName -> threadName.startsWith("provider-caller") })
            assertTrue(parsingDispatcher.executedThreadNames.isNotEmpty())
            /** threadName 是当前 parser continuation 执行线程。 */
            assertTrue(parsingDispatcher.executedThreadNames.all { threadName -> threadName.startsWith("provider-parser") })
        }
    }

    /** 三站都必须原样传播网络层父取消，不得转为空列表或解析失败。 */
    @Test
    fun providersPropagateNetworkCancellation() = runBlocking {
        /** networkClient 模拟调用协程在网络阶段被取消。 */
        val networkClient = FakeTorrentSearchNetworkClient(textHandler = { throw CancellationException() })
        /** providers 覆盖三个独立入口。 */
        val providers = listOf<SearchProvider>(
            NekoBtProvider(networkClient),
            NyaaProvider(networkClient),
            SukebeiProvider(networkClient),
        )
        /** provider 是当前取消传播断言对象。 */
        providers.forEach { provider ->
            /** failure 必须保持 CancellationException 类型。 */
            val failure = runCatching { provider.search("fixture", Category.ALL) }.exceptionOrNull()
            assertTrue(failure is CancellationException)
        }
    }

    /** 父任务在网络挂起时取消必须及时终止三个入口。 */
    @Test
    fun parentCancellationStopsSuspendedNetworkCalls() = runBlocking {
        /** networkClient 模拟尚未返回响应的真实挂起网络阶段。 */
        val networkClient = FakeTorrentSearchNetworkClient(textHandler = { awaitCancellation() })
        /** providers 覆盖三个独立搜索入口。 */
        val providers = listOf<SearchProvider>(
            NekoBtProvider(networkClient),
            NyaaProvider(networkClient),
            SukebeiProvider(networkClient),
        )
        /** provider 是当前真实父取消对象。 */
        providers.forEach { provider ->
            /** job 是持有 Provider 调用的真实子任务。 */
            val job = launch { provider.search("fixture", Category.ALL) }
            yield()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }
    }

    /** 响应返回后、parser 尚未执行时取消，不得继续交付解析结果。 */
    @Test
    fun cancellationAfterResponseStopsQueuedParsing() = runBlocking {
        /** parsingDispatcher 暂存 parser continuation，模拟响应与解析之间的取消窗口。 */
        val parsingDispatcher = EighthBatchQueuedDispatcher()
        /** provider 使用立即返回的合成响应。 */
        val provider = NyaaProvider(
            FakeTorrentSearchNetworkClient(
                textHandler = { "<table class='torrent-list'><tbody></tbody></table>" },
            ),
            parsingDispatcher,
        )
        /** job 在解析 continuation 排队后保持挂起。 */
        val job = launch { provider.search("fixture", Category.ALL) }
        yield()
        assertTrue(parsingDispatcher.hasQueuedWork())
        job.cancel()
        parsingDispatcher.runAll()
        job.join()
        assertTrue(job.isCancelled)
    }
}

/** 线程边界用例把构造逻辑和合法详情地址绑定，避免测试扩大站点白名单。 */
private data class EighthProviderCase(
    /** 构造使用同一 fake 网络和注入 dispatcher 的详情 Provider。 */
    val factory: (TorrentSearchNetworkClient, CoroutineDispatcher) -> TorrentDetailsProvider,
    /** 当前站点已审计的合法详情地址。 */
    val detailsUrl: String,
)

/** 响应后取消测试使用的手动 dispatcher，只在测试显式推进时执行队列。 */
private class EighthBatchQueuedDispatcher : CoroutineDispatcher() {
    /** queuedBlocks 保存尚未执行的 parser continuation。 */
    private val queuedBlocks = ArrayDeque<Runnable>()

    /** 将 parser continuation 入队而不立即执行。 */
    override fun dispatch(
        /** 当前协程上下文。 */ context: CoroutineContext,
        /** 待排队 continuation。 */ block: Runnable,
    ) {
        queuedBlocks.addLast(block)
    }

    /** 判断网络响应后是否已有 parser 工作排队。 */
    fun hasQueuedWork(): Boolean = queuedBlocks.isNotEmpty()

    /** 执行包括取消 continuation 在内的全部排队工作。 */
    fun runAll() {
        while (queuedBlocks.isNotEmpty()) {
            /** block 是当前队首 continuation。 */
            val block = queuedBlocks.removeFirst()
            block.run()
        }
    }
}

/** 第八批测试专用 dispatcher，记录实际执行线程并显式关闭 executor。 */
private class EighthBatchRecordingDispatcher(
    /** 专用计算线程稳定名称。 */ threadName: String,
) : CoroutineDispatcher(), AutoCloseable {
    /** 唯一底层 dispatcher，测试结束后显式关闭。 */
    private val delegate = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, threadName) }
        .asCoroutineDispatcher()
    /** continuation 实际执行线程名。 */
    val executedThreadNames = mutableListOf<String>()

    /** 转交 continuation 并在执行点记录线程。 */
    override fun dispatch(
        /** 当前协程上下文。 */ context: CoroutineContext,
        /** 待执行 continuation。 */ block: Runnable,
    ) {
        delegate.dispatch(context) {
            executedThreadNames += Thread.currentThread().name
            block.run()
        }
    }

    /** 关闭测试 executor，避免残留非守护线程。 */
    override fun close() {
        delegate.close()
    }
}
