package com.cla.clip.feature.torrent.search.search

import com.cla.clip.feature.torrent.search.logging.ProviderLogEvent
import com.cla.clip.feature.torrent.search.logging.ProviderLogStage
import com.cla.clip.feature.torrent.search.logging.ProviderLogger
import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 搜索聚合器测试，只使用可控 fake Provider 锁定并发、隔离和安全模式契约。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchCoordinatorTest {
    /** 全部允许 Provider 必须在任何一个结果完成前立即获得独立启动机会。 */
    @Test
    fun searchStartsEveryAllowedProviderBeforeWaitingForAnyResult() = runTest {
        /** 两个 Provider 的启动闸门，用于观察聚合器是否逐个串行等待。 */
        val startedProviders = mutableListOf<String>()
        /** Provider 结果释放闸门，确保测试先观察到全部启动再结束收集。 */
        val releaseResults = CompletableDeferred<Unit>()
        /** 两个同安全级别 fake Provider，分别在启动后等待同一个释放信号。 */
        val providers = listOf(
            FakeProvider("first", ProviderSafetyLevel.SAFE, startedProviders, releaseResults),
            FakeProvider("second", ProviderSafetyLevel.SAFE, startedProviders, releaseResults),
        )
        /** 被测聚合器，超时值由测试传入以避免依赖真实时间。 */
        val coordinator = SearchCoordinator(providerTimeoutMs = 1_000L)
        /** 在独立协程收集事件，避免主测试协程被 Flow 阻塞。 */
        val eventsDeferred = async { coordinator.search("ignored", Category.ALL, providers, safeModeEnabled = true, maxResults = 10).toList() }

        advanceUntilIdle()
        assertEquals(setOf("first", "second"), startedProviders.toSet())
        releaseResults.complete(Unit)
        advanceUntilIdle()
        // event 是等待确认正常收口的单个搜索事件。
        assertTrue(eventsDeferred.await().any { event -> event is SearchEvent.Completed })
    }

    /** 单一 Provider 异常必须映射失败事件，且不取消其他 Provider 的渐进结果。 */
    @Test
    fun searchIsolatesOneProviderFailureAndKeepsOtherResults() = runTest {
        /** 会抛出网络异常的 fake Provider，仅用于验证 supervisor 隔离边界。 */
        val failingProvider = ThrowingProvider("failing")
        /** 正常 fake Provider 返回合成结果，证明其不会被相邻失败取消。 */
        val successfulProvider = ResultProvider("working")
        /** 被测聚合器，使用足够大的虚拟超时避免测试关注超时逻辑。 */
        val coordinator = SearchCoordinator(providerTimeoutMs = 1_000L)
        /** 全部事件用于断言失败分类和成功结果可以共存。 */
        val events = coordinator.search("ignored", Category.ALL, listOf(failingProvider, successfulProvider), true, 10).toList()

        // event 是等待匹配失败来源的单个搜索事件。
        assertTrue(events.any { event -> event is SearchEvent.Failed && event.providerId.value == "failing" })
        // event 是等待匹配成功来源的单个搜索事件。
        assertTrue(events.any { event -> event is SearchEvent.Results && event.providerId.value == "working" })
    }

    /** 快速来源的结果必须在慢来源完成前渐进发射，不能等所有 Provider 后一次性返回。 */
    @Test
    fun searchEmitsFastProviderResultsWhileSlowProviderIsStillRunning() = runTest {
        /** 慢来源的启动记录，用于确认渐进断言发生时它已经进入执行。 */
        val startedProviders = mutableListOf<String>()
        /** 慢来源释放闸门，断言快速结果时保持未完成。 */
        val releaseSlowProvider = CompletableDeferred<Unit>()
        /** 一个同步快来源和一个受控慢来源，固定完成顺序而不依赖真实时间。 */
        val providers = listOf(
            ResultProvider("fast"),
            FakeProvider("slow", ProviderSafetyLevel.SAFE, startedProviders, releaseSlowProvider),
        )
        /** 被测协调器使用虚拟可控超时。 */
        val coordinator = SearchCoordinator(providerTimeoutMs = 1_000L)
        /** 渐进收集的事件缓冲，仅记录合成事件而不包含用户数据。 */
        val events = mutableListOf<SearchEvent>()
        /** 独立收集任务允许测试在慢来源完成前检查快速结果。 */
        val collectionJob = launch { coordinator.search("ignored", Category.ALL, providers, true, 10).collect { event -> events += event } }

        runCurrent()
        assertEquals(listOf("slow"), startedProviders)
        // event 是等待匹配快速来源结果的单个搜索事件。
        assertTrue(events.any { event -> event is SearchEvent.Results && event.providerId.value == "fast" })
        assertTrue(collectionJob.isActive)
        releaseSlowProvider.complete(Unit)
        advanceUntilIdle()
        collectionJob.join()
    }

    /** 安全模式必须跳过不安全 Provider，并输出稳定 safe_mode_disabled 原因。 */
    @Test
    fun searchSkipsUnsafeProviderWhenSafeModeIsEnabled() = runTest {
        /** 记录调用次数，确保被跳过的 Provider 不会执行搜索副作用。 */
        val unsafeProvider = ResultProvider("unsafe", ProviderSafetyLevel.UNSAFE)
        /** 被测聚合器，安全模式规则由它统一执行而不是散落在 Provider 内。 */
        val coordinator = SearchCoordinator(providerTimeoutMs = 1_000L)
        /** 全部事件用来验证跳过信号及其稳定 reasonCode。 */
        val events = coordinator.search("ignored", Category.ALL, listOf(unsafeProvider), true, 10).toList()

        assertEquals(0, unsafeProvider.invocationCount)
        // event 是等待匹配安全模式跳过原因的单个搜索事件。
        assertTrue(events.any { event -> event is SearchEvent.Skipped && event.reason.code == "safe_mode_disabled" })
    }

    /** 达到全局结果上限后必须取消未完成 Provider，但已发送结果流仍应以 Completed 正常收口。 */
    @Test
    fun searchCancelsPendingProviderAtMaxResultsAndCompletes() = runTest {
        /** 立即返回单条合成结果的 Provider，用于精确触发最大结果边界。 */
        val resultProvider = ResultProvider("result")
        /** 记录启动和取消的 Provider，用于验证未完成任务不会继续占用网络或协程资源。 */
        val pendingProvider = CancellationTrackingProvider("pending")
        /** 被测协调器以默认安全模式处理两个安全来源。 */
        val coordinator = SearchCoordinator(providerTimeoutMs = 1_000L)

        /** 事件流必须可正常收集完成，不能把内部提前停止泄漏成 collector 取消。 */
        val eventsDeferred = async {
            coordinator.search(
                query = "ignored",
                category = Category.ALL,
                providers = listOf(resultProvider, pendingProvider),
                safeModeEnabled = true,
                maxResults = 1,
            ).toList()
        }
        runCurrent()
        pendingProvider.started.await()
        advanceUntilIdle()
        /** 完成后的事件只保留允许数量的结果，并明确向 UI 报告搜索结束。 */
        val events = eventsDeferred.await()

        assertTrue(pendingProvider.cancellationObserved)
        assertEquals(1, events.filterIsInstance<SearchEvent.Results>().single().torrents.size)
        assertTrue(events.last() is SearchEvent.Completed)
    }

    /** 默认 45 秒总任务上限必须取消阻塞 Provider，并输出稳定 timeout 而非 cancelled。 */
    @Test
    fun searchUsesDefaultFortyFiveSecondProviderTimeout() = runTest {
        /** 永久阻塞并记录取消的 Provider，用于验证默认常量实际作用于执行流程。 */
        val blockingProvider = CancellationTrackingProvider("timeout")
        /** 不传超时参数的被测协调器，确保测试保护默认 45 秒契约。 */
        val coordinator = SearchCoordinator()
        /** 收集任务在虚拟时间到达超时前保持活动。 */
        val eventsDeferred = async { coordinator.search("ignored", Category.ALL, listOf(blockingProvider), true, 10).toList() }

        runCurrent()
        blockingProvider.started.await()
        advanceTimeBy(44_999L)
        runCurrent()
        assertTrue(eventsDeferred.isActive)
        advanceTimeBy(1L)
        runCurrent()
        /** 超时后的完整事件用于区分 timeout 与普通 collector 取消。 */
        val events = eventsDeferred.await()

        assertTrue(blockingProvider.cancellationObserved)
        // event 是等待匹配 Provider 总超时的单个搜索事件。
        assertTrue(events.any { event -> event is SearchEvent.Failed && event.reason.code == "timeout" })
        assertTrue(events.last() is SearchEvent.Completed)
    }

    /** collector 取消必须向下取消 Provider，且不能伪造 Completed 或吞掉父取消。 */
    @Test
    fun searchPropagatesCollectorCancellationToProviderWithoutCompletedEvent() = runTest {
        /** 永久阻塞并记录取消的 Provider，模拟页面离开时仍在执行的网络来源。 */
        val blockingProvider = CancellationTrackingProvider("collector")
        /** 被测协调器使用足够长的超时，确保停止原因只来自 collector。 */
        val coordinator = SearchCoordinator(providerTimeoutMs = 60_000L)
        /** 已收到事件列表用于确认取消路径没有追加虚假 Completed。 */
        val events = mutableListOf<SearchEvent>()
        /** 独立 collector 任务模拟页面生命周期结束。 */
        val collectionJob = launch {
            /** event 是 collector 取消前收到的单个搜索事件。 */
            coordinator.search("ignored", Category.ALL, listOf(blockingProvider), true, 10).collect { event -> events += event }
        }

        runCurrent()
        blockingProvider.started.await()
        collectionJob.cancelAndJoin()

        assertTrue(blockingProvider.cancellationObserved)
        // event 是等待确认没有伪造完成信号的单个搜索事件。
        assertTrue(events.none { event -> event is SearchEvent.Completed })
    }

    /** HTTP 拒绝必须把稳定 reason 和低敏状态码写入事件/日志，不能依赖异常 message。 */
    @Test
    fun searchLogsHttpDeniedStatusWithoutLosingStableFailureReason() = runTest {
        /** 只记录结构化事件的测试 Logger，用于观察真实协调器输出。 */
        val logger = CapturingProviderLogger()
        /** 抛出带合成状态码 HTTP 拒绝的 Provider，不包含真实 URL 或响应。 */
        val deniedProvider = HttpDeniedProvider("denied", 451)
        /** 被测协调器注入捕获 Logger，避免调用 Android Log。 */
        val coordinator = SearchCoordinator(providerTimeoutMs = 1_000L, logger = logger)
        /** 全部搜索事件用于断言稳定失败分类。 */
        val events = coordinator.search("ignored", Category.ALL, listOf(deniedProvider), true, 10).toList()
        /** 唯一失败日志用于断言可行动状态码。 */
        val failureLog = logger.events.single { event -> event.stage == ProviderLogStage.FAILED }

        // event 是等待匹配 HTTP 拒绝的单个搜索事件。
        assertTrue(events.any { event -> event is SearchEvent.Failed && event.reason.code == "http_denied" })
        assertEquals(451, failureLog.httpStatusCode)
        assertEquals("http_denied", failureLog.reason?.code)
    }

    /** Provider parser 的稳定异常必须映射 parse_failure，不能退化为 unknown。 */
    @Test
    fun searchMapsProviderParseExceptionToStableFailureReason() = runTest {
        /** 只抛出解析失败的合成 Provider，避免引入真实 HTML 或 JSON fixture。 */
        val parseProvider = ParseFailureProvider("parse")
        /** 被测协调器使用短虚拟超时，当前测试只覆盖失败分类。 */
        val coordinator = SearchCoordinator(providerTimeoutMs = 1_000L)
        /** 全部事件用于断言稳定解析失败协议。 */
        val events = coordinator.search("ignored", Category.ALL, listOf(parseProvider), true, 10).toList()

        // event 是等待匹配解析失败的单个搜索事件。
        assertTrue(events.any { event -> event is SearchEvent.Failed && event.reason.code == "parse_failure" })
    }
}

/** 测试专用 Provider：启动后阻塞，供测试检查全部任务是否立即创建。 */
private class FakeProvider(
    /** 稳定 Provider ID，帮助测试识别并发启动记录。 */
    private val id: String,
    /** 测试传入的安全等级，模拟注册表元数据。 */
    private val safetyLevel: ProviderSafetyLevel,
    /** 启动记录集合，仅在测试协程中使用。 */
    private val startedProviders: MutableList<String>,
    /** 统一释放闸门，避免使用真实延时。 */
    private val releaseResults: CompletableDeferred<Unit>,
) : SearchProvider {
    /** Provider 描述符，生产聚合器只消费稳定 ID 与安全等级。 */
    override val descriptor: ProviderDescriptor = testDescriptor(id, safetyLevel)

    /** 记录启动后等待测试释放，返回一个不包含真实内容的合成 Torrent。 */
    override suspend fun search(query: String, category: Category): List<Torrent> {
        startedProviders += id
        releaseResults.await()
        return listOf(fixtureTorrent(id))
    }
}

/** 测试专用失败 Provider，使用普通异常模拟尚未分类的网络边界错误。 */
private class ThrowingProvider(
    /** 稳定 Provider ID，用于断言失败不会影响其他来源。 */
    id: String,
) : SearchProvider {
    /** Provider 描述符固定安全，以便测试只覆盖失败隔离。 */
    override val descriptor: ProviderDescriptor = testDescriptor(id, ProviderSafetyLevel.SAFE)

    /** 直接抛出异常，验证聚合器负责映射且不会把异常泄出 Flow。 */
    override suspend fun search(query: String, category: Category): List<Torrent> = error("synthetic network failure")
}

/** 测试专用成功 Provider，公开调用次数以验证安全模式跳过不触发请求。 */
private class ResultProvider(
    /** 稳定 Provider ID，用于断言输出来源。 */
    id: String,
    /** 测试传入的安全等级，默认安全。 */
    safetyLevel: ProviderSafetyLevel = ProviderSafetyLevel.SAFE,
) : SearchProvider {
    /** 生产聚合器消费的稳定描述符。 */
    override val descriptor: ProviderDescriptor = testDescriptor(id, safetyLevel)
    /** 搜索调用次数，只在测试断言中读取。 */
    var invocationCount: Int = 0
        private set

    /** 增加调用计数并返回一个无敏感内容的合成结果。 */
    override suspend fun search(query: String, category: Category): List<Torrent> {
        invocationCount += 1
        return listOf(fixtureTorrent(descriptor.id.value))
    }
}

/** 测试专用阻塞 Provider，证明协调器达到 maxResults 后会取消仍在执行的来源。 */
private class CancellationTrackingProvider(
    /** 稳定 Provider ID，只用于测试事件关联。 */
    id: String,
) : SearchProvider {
    /** Provider 已开始执行的测试同步点，避免依赖真实延时。 */
    val started = CompletableDeferred<Unit>()
    /** 取消 finally 分支写入的可观测标记，证明协程确实停止。 */
    var cancellationObserved: Boolean = false
        private set
    /** 生产协调器消费的安全 Provider 描述符。 */
    override val descriptor: ProviderDescriptor = testDescriptor(id, ProviderSafetyLevel.SAFE)

    /** 持续等待取消；没有结果时不会自行结束或触发真实网络。 */
    override suspend fun search(query: String, category: Category): List<Torrent> {
        started.complete(Unit)
        try {
            awaitCancellation()
        } finally {
            cancellationObserved = true
        }
    }
}

/** 测试专用 HTTP 拒绝 Provider，用稳定状态码验证错误到日志的映射边界。 */
private class HttpDeniedProvider(
    /** 稳定 Provider ID，只用于事件关联。 */
    id: String,
    /** 合成 HTTP 状态码，不对应真实站点。 */
    private val statusCode: Int,
) : SearchProvider {
    /** 生产协调器消费的安全 Provider 描述符。 */
    override val descriptor: ProviderDescriptor = testDescriptor(id, ProviderSafetyLevel.SAFE)

    /** 抛出不带响应正文的稳定拒绝异常，证明协调器不依赖异常 message。 */
    override suspend fun search(query: String, category: Category): List<Torrent> = throw HttpDeniedException(statusCode)
}

/** 测试专用结构化 Logger，只在内存中保留字段白名单事件。 */
private class CapturingProviderLogger : ProviderLogger {
    /** 已收到的低敏事件，按协调器输出顺序保存。 */
    val events = mutableListOf<ProviderLogEvent>()

    /** 记录单个结构化事件，不格式化或扩充任何敏感字段。 */
    override fun log(event: ProviderLogEvent) {
        events += event
    }
}

/** 测试专用解析失败 Provider，证明 parser 错误不会被笼统归类 unknown。 */
private class ParseFailureProvider(
    /** 稳定 Provider ID，只用于事件关联。 */
    id: String,
) : SearchProvider {
    /** 生产协调器消费的安全 Provider 描述符。 */
    override val descriptor: ProviderDescriptor = testDescriptor(id, ProviderSafetyLevel.SAFE)

    /** 抛出无正文的稳定解析异常，避免异常 message 泄漏站点内容。 */
    override suspend fun search(query: String, category: Category): List<Torrent> = throw ProviderParseException()
}

/** 构造完整测试描述符，集中避免 fake Provider 重复无关元数据。 */
private fun testDescriptor(id: String, safetyLevel: ProviderSafetyLevel): ProviderDescriptor = ProviderDescriptor(
    id = ProviderId(id),
    displayName = id,
    baseUrl = "https://example.invalid",
    supportedCategories = Category.entries.toSet(),
    safetyLevel = safetyLevel,
    enabledByDefault = safetyLevel == ProviderSafetyLevel.SAFE,
)

/** 构造完整合成 Torrent，测试仅关心来源和事件调度。 */
private fun fixtureTorrent(providerId: String): Torrent = Torrent(
    infoHash = providerId.padEnd(40, '0').take(40),
    name = providerId,
    providerId = ProviderId(providerId),
    providerName = providerId,
    descriptionPageUrl = "https://example.invalid/$providerId",
)
