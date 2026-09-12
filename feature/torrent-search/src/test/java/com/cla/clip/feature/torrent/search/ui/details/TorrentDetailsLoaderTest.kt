package com.cla.clip.feature.torrent.search.ui.details

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.logging.ProviderLogEvent
import com.cla.clip.feature.torrent.search.logging.ProviderLogStage
import com.cla.clip.feature.torrent.search.logging.ProviderLogger
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 原生详情加载器的能力、地址、身份一致性和取消边界回归。 */
class TorrentDetailsLoaderTest {
    /** 合法候选只调用选中 Provider 并返回身份一致详情。 */
    @Test
    fun loadUsesSelectedProviderAndAcceptsMatchingIdentity() = runTest {
        /** selected 是详情候选所指向的能力来源。 */
        val selected = FakeDetailsProvider("selected", details = details(HASH_A, "selected"))
        /** other 不得因展示名或 URL 相似而被调用。 */
        val other = FakeDetailsProvider("other", details = details(HASH_A, "other"))

        /** result 是经过请求和响应双向校验的详情结果。 */
        val result = TorrentDetailsLoader().load(candidate(HASH_A, "selected"), listOf(other, selected))

        assertTrue(result is TorrentDetailsLoadResult.Success)
        assertEquals(1, selected.calls)
        assertEquals(0, other.calls)
    }

    /** 非 HTTPS、userinfo、跨域和超长详情地址必须在网络前拒绝。 */
    @Test
    fun loadRejectsUnsafeDetailsUrlsBeforeNetwork() = runTest {
        /** provider 记录所有详情网络调用。 */
        val provider = FakeDetailsProvider("selected", details = details(HASH_A, "selected"))
        /** urls 覆盖明文、凭据、跨域和长度上限。 */
        val urls = listOf(
            "http://fixture.invalid/item",
            "https://user@fixture.invalid/item",
            "https://other.invalid/item",
            "https://fixture.invalid/${"a".repeat(2_100)}",
        )

        urls.forEach { /** url 是当前待拒绝的不可信候选。 */ url ->
            /** result 应稳定归类为请求无效且不调用 Provider。 */
            val result = TorrentDetailsLoader().load(candidate(HASH_A, "selected", url), listOf(provider))
            assertEquals(TorrentDetailsFailureReason.INVALID_REQUEST, (result as TorrentDetailsLoadResult.Failure).reason)
        }
        assertEquals(0, provider.calls)
    }

    /** 不具备详情能力、Provider ID 不匹配或返回 hash 不匹配都必须拒绝。 */
    @Test
    fun loadRejectsUnsupportedProviderAndMismatchedResponseIdentity() = runTest {
        /** searchOnly 与候选 ID 相同但不具备详情能力。 */
        val searchOnly = SearchOnlyProvider("selected")
        /** unsupported 是能力校验失败结果。 */
        val unsupported = TorrentDetailsLoader().load(candidate(HASH_A, "selected"), listOf(searchOnly))
        assertEquals(TorrentDetailsFailureReason.UNSUPPORTED_PROVIDER, (unsupported as TorrentDetailsLoadResult.Failure).reason)

        /** wrongHash 模拟 Provider 返回另一个 torrent 的详情。 */
        val wrongHash = FakeDetailsProvider("selected", details = details(HASH_B, "selected"))
        /** mismatch 必须丢弃身份冲突对象。 */
        val mismatch = TorrentDetailsLoader().load(candidate(HASH_A, "selected"), listOf(wrongHash))
        assertEquals(TorrentDetailsFailureReason.IDENTITY_MISMATCH, (mismatch as TorrentDetailsLoadResult.Failure).reason)
    }

    /** 页面离开触发的父任务取消必须抵达 Provider。 */
    @Test
    fun cancellationStopsDetailsProvider() = runTest {
        /** started 确认详情调用已进入挂起点。 */
        val started = CompletableDeferred<Unit>()
        /** provider 永久挂起以观察取消。 */
        val provider = FakeDetailsProvider("selected", suspendUntilCancelled = true, started = started)
        /** job 承载当前详情加载。 */
        val job = launch { TorrentDetailsLoader().load(candidate(HASH_A, "selected"), listOf(provider)) }
        started.await()

        job.cancelAndJoin()

        assertTrue(provider.wasCancelled)
    }

    /** Provider 返回空详情时必须以固定失败阶段关闭已开始的日志生命周期。 */
    @Test
    fun unavailableDetailsLogsFailedStage() = runTest {
        /** events 收集封闭低敏日志，不读取 URL 或异常正文。 */
        val events = mutableListOf<ProviderLogEvent>()
        /** provider 返回 null 模拟页面结构无可用详情。 */
        val provider = FakeDetailsProvider("selected", details = null)
        /** loader 注入记录型 Logger 观察详情阶段。 */
        val loader = TorrentDetailsLoader(logger = ProviderLogger { /** event 是当前固定字段日志。 */ event -> events += event })

        /** result 是 Provider 空详情对应的稳定失败结果。 */
        val result = loader.load(candidate(HASH_A, "selected"), listOf(provider))

        assertEquals(TorrentDetailsFailureReason.UNAVAILABLE, (result as TorrentDetailsLoadResult.Failure).reason)
        assertEquals(listOf(ProviderLogStage.STARTED, ProviderLogStage.FAILED), events.map { /** event 是待检查阶段的固定日志。 */ event -> event.stage })
    }

    /** 可控详情 Provider fake，只返回合成模型。 */
    private class FakeDetailsProvider(
        /** 稳定测试 ID。 */
        rawId: String,
        /** 可选合成详情。 */
        private val details: TorrentDetails? = null,
        /** 是否永久挂起等待取消。 */
        private val suspendUntilCancelled: Boolean = false,
        /** 进入详情调用时的同步信号。 */
        private val started: CompletableDeferred<Unit>? = null,
    ) : TorrentDetailsProvider {
        /** 描述符只允许固定保留域名。 */
        override val descriptor = descriptor(rawId)
        /** 实际详情调用次数。 */
        var calls = 0
        /** 取消是否到达 fake。 */
        var wasCancelled = false

        /** 搜索不属于详情加载测试。 */
        override suspend fun search(query: String, category: Category): List<Torrent> = error("unexpected search")

        /** 返回合成详情或挂起等待取消。 */
        override suspend fun getDetails(detailsPageUrl: String): TorrentDetails? {
            calls += 1
            started?.complete(Unit)
            if (suspendUntilCancelled) {
                try {
                    awaitCancellation()
                } finally {
                    wasCancelled = true
                }
            }
            return details
        }
    }

    /** 只具备搜索能力的来源。 */
    private class SearchOnlyProvider(
        /** 稳定测试 ID。 */
        rawId: String,
    ) : SearchProvider {
        /** 合成描述符。 */
        override val descriptor = descriptor(rawId)

        /** 测试不应调用基础搜索。 */
        override suspend fun search(query: String, category: Category): List<Torrent> = error("unexpected search")
    }

    /** 构造详情入口候选。 */
    private fun candidate(
        /** 期望 infoHash。 */
        infoHash: String,
        /** 选中 Provider ID。 */
        providerId: String,
        /** 不可信详情地址。 */
        url: String = "https://fixture.invalid/item",
    ): Torrent = Torrent(
        infoHash = infoHash,
        name = "Synthetic",
        providerId = ProviderId(providerId),
        providerName = "Fixture",
        descriptionPageUrl = url,
    )

    /** 构造详情返回对象。 */
    private fun details(infoHash: String, providerId: String): TorrentDetails = TorrentDetails(
        infoHash = infoHash,
        name = "Synthetic",
        providerId = ProviderId(providerId),
        providerName = "Fixture",
        magnetUri = "magnet:?xt=urn:btih:$infoHash",
    )

    /** 合成稳定身份集中定义。 */
    companion object {
        /** 构造固定保留域名描述符，静态位置供嵌套 fake 复用。 */
        private fun descriptor(rawId: String): ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(rawId),
            displayName = "Fixture",
            baseUrl = "https://fixture.invalid",
            safetyLevel = ProviderSafetyLevel.SAFE,
            enabledByDefault = true,
        )

        /** 第一条合成 infoHash。 */
        private const val HASH_A = "0123456789012345678901234567890123456789"
        /** 第二条合成 infoHash。 */
        private const val HASH_B = "1123456789012345678901234567890123456789"
    }
}
