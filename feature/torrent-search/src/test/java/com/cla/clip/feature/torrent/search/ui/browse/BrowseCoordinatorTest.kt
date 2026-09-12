package com.cla.clip.feature.torrent.search.ui.browse

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderFailureReason
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.provider.LatestTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.provider.TopTorrentsProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Browse 协调器的能力筛选、并发隔离、排序去重和取消回归。 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseCoordinatorTest {
    /** Latest 只调用对应能力来源，并按日期降序且按 infoHash 首次结果去重。 */
    @Test
    fun latestUsesCapableProvidersAndReturnsDeduplicatedDateOrder() = runTest {
        /** duplicateOld 是首个来源返回的旧日期身份。 */
        val duplicateOld = torrent(HASH_A, epochMillis = 10L, seeders = 1u, providerId = "latest_a")
        /** duplicateNew 与旧结果同身份，不能覆盖首次到达项。 */
        val duplicateNew = torrent(HASH_A, epochMillis = 30L, seeders = 9u, providerId = "latest_b")
        /** newest 是最终应排首位的独立结果。 */
        val newest = torrent(HASH_B, epochMillis = 20L, seeders = 2u, providerId = "latest_b")
        /** first 是第一个 Latest 来源。 */
        val first = FakeBrowseProvider("latest_a", latestResults = listOf(duplicateOld))
        /** second 是第二个 Latest 来源。 */
        val second = FakeBrowseProvider("latest_b", latestResults = listOf(duplicateNew, newest))
        /** searchOnly 不具备浏览能力，不能被调用。 */
        val searchOnly = SearchOnlyProvider()

        /** result 是一次并发 Latest 聚合快照。 */
        val result = BrowseCoordinator().load(BrowseMode.LATEST, Category.ALL, listOf(first, searchOnly, second))

        assertEquals(listOf(HASH_B, HASH_A), result.torrents.map { /** item 是最终排序结果。 */ item -> item.infoHash })
        assertEquals(1, first.latestCalls)
        assertEquals(1, second.latestCalls)
        assertEquals(0, searchOnly.searchCalls)
        assertTrue(result.failures.isEmpty())
    }

    /** Top 按做种数降序并隔离单个来源失败。 */
    @Test
    fun topSortsBySeedersAndIsolatesProviderFailure() = runTest {
        /** healthy 返回两个做种数不同的结果。 */
        val healthy = FakeBrowseProvider(
            rawId = "healthy",
            topResults = listOf(
                torrent(HASH_A, epochMillis = null, seeders = 2u, providerId = "healthy"),
                torrent(HASH_B, epochMillis = null, seeders = 8u, providerId = "healthy"),
            ),
        )
        /** failing 模拟不包含响应正文的解析失败。 */
        val failing = FakeBrowseProvider("failing", topFailure = IllegalStateException())

        /** result 应保留健康来源并对失败做稳定分类。 */
        val result = BrowseCoordinator().load(BrowseMode.TOP, Category.MOVIES, listOf(failing, healthy))

        assertEquals(listOf(HASH_B, HASH_A), result.torrents.map { /** item 是按热门规则排序的结果。 */ item -> item.infoHash })
        assertEquals(ProviderFailureReason.UNKNOWN, result.failures[failing.descriptor.id])
    }

    /** 父任务取消必须传播到所有仍在运行的浏览来源。 */
    @Test
    fun cancellationStopsAllActiveProviders() = runTest {
        /** started 用于确认 fake 已进入可取消挂起点。 */
        val started = CompletableDeferred<Unit>()
        /** provider 永久挂起直到协调器父任务取消。 */
        val provider = FakeBrowseProvider("cancellable", suspendLatest = true, started = started)
        /** job 承载当前浏览请求。 */
        val job = launch { BrowseCoordinator().load(BrowseMode.LATEST, Category.ALL, listOf(provider)) }
        started.await()

        job.cancelAndJoin()

        assertTrue(provider.wasCancelled)
    }

    /** 同一批来源应在任一结果完成前全部进入执行，避免串行浏览。 */
    @Test
    fun providersStartConcurrently() = runTest {
        /** firstGate 控制第一来源完成。 */
        val firstGate = CompletableDeferred<Unit>()
        /** secondGate 控制第二来源完成。 */
        val secondGate = CompletableDeferred<Unit>()
        /** first 是受控完成的第一个来源。 */
        val first = FakeBrowseProvider("first", completionGate = firstGate)
        /** second 是受控完成的第二个来源。 */
        val second = FakeBrowseProvider("second", completionGate = secondGate)
        /** job 启动聚合但先不释放任一来源。 */
        val job = launch { BrowseCoordinator().load(BrowseMode.LATEST, Category.ALL, listOf(first, second)) }
        runCurrent()

        assertEquals(1, first.latestCalls)
        assertEquals(1, second.latestCalls)
        firstGate.complete(Unit)
        secondGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(job.isCompleted)
    }

    /** 同时实现 Latest/Top 的测试 Provider，可精确观察能力调用与取消。 */
    private class FakeBrowseProvider(
        /** 稳定测试 ID。 */
        rawId: String,
        /** Latest 合成结果。 */
        private val latestResults: List<Torrent> = emptyList(),
        /** Top 合成结果。 */
        private val topResults: List<Torrent> = emptyList(),
        /** Top 可选合成失败。 */
        private val topFailure: Throwable? = null,
        /** 是否让 Latest 永久挂起等待取消。 */
        private val suspendLatest: Boolean = false,
        /** 进入 Latest 时发出的同步信号。 */
        private val started: CompletableDeferred<Unit>? = null,
        /** 可选完成闸门，用于证明并发启动。 */
        private val completionGate: CompletableDeferred<Unit>? = null,
    ) : LatestTorrentsProvider, TopTorrentsProvider {
        /** 合成 Provider 描述符只使用保留域名。 */
        override val descriptor = descriptor(rawId)
        /** Latest 实际调用次数。 */
        var latestCalls = 0
        /** 请求取消是否抵达 fake。 */
        var wasCancelled = false

        /** 搜索不属于本测试路径。 */
        override suspend fun search(query: String, category: Category): List<Torrent> = error("unexpected search")

        /** 返回 Latest fixture，必要时挂起以观察结构化取消。 */
        override suspend fun getLatestTorrents(category: Category): List<Torrent> {
            latestCalls += 1
            started?.complete(Unit)
            completionGate?.await()
            if (suspendLatest) {
                try {
                    awaitCancellation()
                } finally {
                    wasCancelled = true
                }
            }
            return latestResults
        }

        /** 返回 Top fixture 或抛出合成失败。 */
        override suspend fun getTopTorrents(category: Category): List<Torrent> {
            topFailure?.let { /** failure 是当前测试注入的稳定失败。 */ failure -> throw failure }
            return topResults
        }
    }

    /** 仅搜索来源用于证明能力筛选发生在调用前。 */
    private class SearchOnlyProvider : SearchProvider {
        /** 合成描述符。 */
        override val descriptor = descriptor("search_only")
        /** search 调用次数，正确实现应保持零。 */
        var searchCalls = 0

        /** 若协调器错误调用基础搜索则留下可观察计数。 */
        override suspend fun search(query: String, category: Category): List<Torrent> {
            searchCalls += 1
            return emptyList()
        }
    }

    /** 构造只包含排序与身份字段的短小 Torrent fixture。 */
    private fun torrent(infoHash: String, epochMillis: Long?, seeders: UInt?, providerId: String): Torrent = Torrent(
        infoHash = infoHash,
        name = "Synthetic",
        seeders = seeders,
        providerId = ProviderId(providerId),
        providerName = "Fixture",
        uploadEpochMillis = epochMillis,
    )

    /** 合成稳定身份集中定义。 */
    companion object {
        /** 构造不含真实地址的测试描述符，静态位置供嵌套 fake 复用。 */
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
