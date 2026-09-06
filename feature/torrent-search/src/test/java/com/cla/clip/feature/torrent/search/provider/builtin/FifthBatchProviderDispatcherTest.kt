package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.eztv.EztvProvider
import com.cla.clip.feature.torrent.search.provider.builtin.filemood.FileMoodProvider
import com.cla.clip.feature.torrent.search.provider.builtin.internetarchive.InternetArchiveProvider
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
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
import org.junit.Assert.*
import org.junit.Test

/** 第五批的网络/计算线程隔离与响应后取消边界；使用受控线程并显式清理。 */
class FifthBatchProviderDispatcherTest {
    /** Eztv 搜索和详情都必须解析在独立调度器。 */
    @Test
    fun eztvSearchAndDetailsSeparateNetworkFromParsing() = runBlocking {
        verifyDispatchers(::EztvProvider)
    }

    /** FileMood 搜索和详情都必须解析在独立调度器。 */
    @Test
    fun fileMoodSearchAndDetailsSeparateNetworkFromParsing() = runBlocking {
        verifyDispatchers(::FileMoodProvider)
    }

    /** IA 搜索 JSON 与详情 JSON 都不能占用调用线程解析。 */
    @Test
    fun archiveSearchAndDetailsSeparateNetworkFromParsing() = runBlocking {
        verifyDispatchers(::InternetArchiveProvider)
    }

    /** 响应取得后父任务取消，三个入口均不得产出伪成功。 */
    @Test
    fun cancellationAfterResponseIsNotConvertedToEmptyResults() = runBlocking {
        /** 每个工厂只构造当前 Provider，不增加运行时测试开关。 */
        val factories: List<(TorrentSearchNetworkClient, CoroutineDispatcher) -> TorrentDetailsProvider> =
            listOf(::EztvProvider, ::FileMoodProvider, ::InternetArchiveProvider)
        /** factory 表示当前需要验证的入口类型。 */
        for (factory in factories) {
            supervisorScope {
                /** fake 在恢复 Provider 前取消当前请求协程，复现页面离开边界。 */
                val client = FakeTorrentSearchNetworkClient(
                    textHandler = { currentCoroutineContext().cancel(); "<table></table>" },
                    jsonHandler = { currentCoroutineContext().cancel(); "{\"response\":{\"docs\":[]}}" },
                )
                /** 子任务取消不能取消承载断言的父测试。 */
                val result = async { factory(client, kotlinx.coroutines.Dispatchers.Default).search("fixture", Category.ALL) }
                try { result.await(); fail("取消后不能返回空结果成功") }
                /** failure 为结构化协程取消，不允许被 parser 容错吞掉。 */
                catch (failure: CancellationException) { assertTrue(result.isCancelled) }
            }
        }
    }

    /** 同时执行 search/details，以真实线程观测网络与解析 continuation 的边界。 */
    private suspend fun verifyDispatchers(
        /** 当前 Provider 的标准构造，不向生产增加测试方法。 */
        factory: (TorrentSearchNetworkClient, CoroutineDispatcher) -> TorrentDetailsProvider,
    ) {
        /** runnable 是调用线程工作项，线程名称固定便于独立断言。 */
        val caller = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "fifth-caller") }.asCoroutineDispatcher()
        /** parser 调度器记录实际执行位置，测试结束后关闭线程。 */
        val parser = FifthBatchRecordingDispatcher()
        /** 实际网络回调线程名，响应由 fake 构造。 */
        val networkThreads = mutableListOf<String>()
        /** 兼容三站空列表与空详情的最小响应，不人为增加 HTML 哨兵。 */
        val client = FakeTorrentSearchNetworkClient(
            textHandler = { networkThreads += Thread.currentThread().name; "<table></table>" },
            jsonHandler = { networkThreads += Thread.currentThread().name; "{\"response\":{\"docs\":[]},\"metadata\":{}}" },
        )
        try {
            withContext(caller) {
                /** 被测入口与 parser 共用注入调度器，网络仍应处于 caller。 */
                val provider = factory(client, parser)
                provider.search("fixture", Category.ALL)
                provider.getDetails(provider.descriptor.baseUrl + "/details/fixture")
            }
            assertEquals(2, networkThreads.size)
            /** thread 为实际网络执行线程；允许协程调试模式添加 @coroutine 后缀。 */
            assertTrue(networkThreads.all { thread -> thread.startsWith("fifth-caller") })
            assertEquals(2, parser.threads.size)
            /** thread 为实际解析调度执行线程，同样忽略非业务调试后缀。 */
            assertTrue(parser.threads.all { thread -> thread.startsWith("fifth-parser") })
        } finally {
            caller.close()
            parser.close()
        }
    }
}

/** 当前批次专用计算调度器，只记录线程名，不读取用户输入或响应。 */
private class FifthBatchRecordingDispatcher : CoroutineDispatcher(), AutoCloseable {
    /** runnable 是单个计算工作项，线程归测试所有并显式关闭。 */
    private val delegate = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "fifth-parser") }.asCoroutineDispatcher()
    /** 每次调度的实际执行线程，按调用完成顺序记录。 */
    val threads = mutableListOf<String>()

    /** 计算任务的执行点由专用 executor 承载，并记录低敏线程名。 */
    override fun dispatch(
        /** 目标协程上下文，保留结构化取消边界。 */
        context: CoroutineContext,
        /** 待执行计算任务，仅在独立测试线程调度。 */
        block: Runnable,
    ) {
        delegate.dispatch(context) {
            threads += Thread.currentThread().name
            block.run()
        }
    }

    /** 释放当前测试私有线程，避免 JVM 退出后残留资源。 */
    override fun close() { delegate.close() }
}
