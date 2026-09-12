package com.cla.clip.feature.torrent.search.ui.details

import com.cla.clip.feature.torrent.search.data.model.ViewedTorrent
import com.cla.clip.feature.torrent.search.data.model.BookmarkedTorrent
import com.cla.clip.feature.torrent.search.data.repository.BookmarkRepository
import com.cla.clip.feature.torrent.search.data.repository.ViewedTorrentRepository
import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 详情选中态、已查看写入和目的地生命周期取消回归。 */
@OptIn(ExperimentalCoroutinesApi::class)
class TorrentDetailsViewModelTest {
    /** dispatcher 控制两个 ViewModel 的 Main 协程。 */
    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    /** 每个测试前替换 Main。 */
    @Before
    fun setUpMainDispatcher() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    /** 每个测试后恢复 Main。 */
    @After
    fun tearDownMainDispatcher() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    /** 结果点击先保存在内存并刷新已查看记录，路由无需携带 URL 或正文。 */
    @Test
    fun selectionKeepsCandidateInMemoryAndMarksViewed() = runTest(dispatcher.scheduler) {
        /** repository 记录唯一已查看身份。 */
        val repository = FakeViewedRepository()
        /** viewModel 承载 Activity 内存选中态。 */
        val viewModel = TorrentDetailSelectionViewModel(repository)
        /** candidate 是搜索或浏览页点击的领域结果。 */
        val candidate = candidate()

        viewModel.select(candidate)
        runCurrent()

        assertEquals(candidate, viewModel.selected.value)
        assertEquals(listOf(HASH), repository.markedHashes)
    }

    /** 详情候选在未激活时不加载，激活后加载并生成安全富文本。 */
    @Test
    fun detailsLoadOnlyWhileDestinationIsActive() = runTest(dispatcher.scheduler) {
        /** provider 立即返回含主动内容的详情。 */
        val provider = FakeDetailsProvider(suspendUntilCancelled = false)
        /** viewModel 使用真实 loader。 */
        val viewModel = TorrentDetailsViewModel(listOf(provider), TorrentDetailsLoader())
        viewModel.setCandidate(candidate(), ProviderId("details_fixture"))
        runCurrent()
        assertEquals(0, provider.calls)

        viewModel.setScreenActive(true)
        runCurrent()

        assertEquals(1, provider.calls)
        assertTrue(viewModel.uiState.value.phase == TorrentDetailsPhase.CONTENT)
        assertFalse(viewModel.uiState.value.description?.text.orEmpty().contains("secret"))
    }

    /** 离页取消挂起详情，重进时按当前候选重新加载。 */
    @Test
    fun leavingCancelsAndReenteringRetriesDetails() = runTest(dispatcher.scheduler) {
        /** provider 每轮详情都挂起。 */
        val provider = FakeDetailsProvider(suspendUntilCancelled = true)
        /** viewModel 保存候选但构造不联网。 */
        val viewModel = TorrentDetailsViewModel(listOf(provider), TorrentDetailsLoader())
        viewModel.setCandidate(candidate(), ProviderId("details_fixture"))
        viewModel.setScreenActive(true)
        runCurrent()
        assertEquals(1, provider.calls)

        viewModel.setScreenActive(false)
        runCurrent()
        assertEquals(1, provider.cancelCount)
        assertFalse(viewModel.uiState.value.isLoading)

        viewModel.setScreenActive(true)
        runCurrent()
        assertEquals(2, provider.calls)
    }

    /** 详情书签只在页面可见时精确观察，保存/移除后更新且离页取消。 */
    @Test
    fun bookmarkActionUsesExactObservationAndStopsWhenLeaving() = runTest(dispatcher.scheduler) {
        /** provider 正常完成详情，避免网络挂起遮蔽书签断言。 */
        val provider = FakeDetailsProvider(suspendUntilCancelled = false)
        /** bookmarkRepository 提供可观察精确存在状态。 */
        val bookmarkRepository = FakeBookmarkRepository()
        /** viewModel 注入真实详情 loader 与可控书签仓库。 */
        val viewModel = TorrentDetailsViewModel(listOf(provider), TorrentDetailsLoader(), bookmarkRepository)
        viewModel.setCandidate(candidate(), ProviderId("details_fixture"))
        runCurrent()
        assertEquals(0, bookmarkRepository.observeCalls)

        viewModel.setScreenActive(true)
        runCurrent()
        assertEquals(1, bookmarkRepository.observeCalls)
        assertFalse(viewModel.uiState.value.isBookmarked)

        viewModel.toggleBookmark()
        runCurrent()
        assertTrue(viewModel.uiState.value.isBookmarked)

        viewModel.toggleBookmark()
        runCurrent()
        assertFalse(viewModel.uiState.value.isBookmarked)

        viewModel.setScreenActive(false)
        runCurrent()
        assertEquals(1, bookmarkRepository.cancelCount)
    }

    /** 精确查询首值到达前书签状态必须未知，点击不能猜测为新增。 */
    @Test
    fun bookmarkToggleWaitsForTheFirstExactPresenceValue() = runTest(dispatcher.scheduler) {
        /** bookmarkRepository 延迟首个精确结果并记录任何误写。 */
        val bookmarkRepository = DelayedBookmarkRepository()
        /** viewModel 连接真实详情书签状态机。 */
        val viewModel = TorrentDetailsViewModel(listOf(FakeDetailsProvider(false)), TorrentDetailsLoader(), bookmarkRepository)
        viewModel.setCandidate(candidate(), ProviderId("details_fixture"))
        viewModel.setScreenActive(true)
        runCurrent()

        assertEquals(BookmarkPresencePhase.UNKNOWN, viewModel.uiState.value.bookmarkPresencePhase)
        viewModel.toggleBookmark()
        runCurrent()
        assertEquals(0, bookmarkRepository.saveCalls + bookmarkRepository.deleteCalls)

        bookmarkRepository.emit(HASH, true)
        runCurrent()
        assertEquals(BookmarkPresencePhase.READY, viewModel.uiState.value.bookmarkPresencePhase)
        assertTrue(viewModel.uiState.value.isBookmarked)
        viewModel.toggleBookmark()
        runCurrent()
        assertEquals(1, bookmarkRepository.deleteCalls)
    }

    /** 精确查询失败必须进入错误态并继续禁止猜测写入。 */
    @Test
    fun bookmarkPresenceFailureDisablesToggle() = runTest(dispatcher.scheduler) {
        /** repository 在精确查询时立即抛出固定合成异常。 */
        val repository = FailingObserveBookmarkRepository()
        /** viewModel 使用真实错误收敛逻辑。 */
        val viewModel = TorrentDetailsViewModel(listOf(FakeDetailsProvider(false)), TorrentDetailsLoader(), repository)
        viewModel.setCandidate(candidate(), ProviderId("details_fixture"))
        viewModel.setScreenActive(true)
        runCurrent()

        assertEquals(BookmarkPresencePhase.ERROR, viewModel.uiState.value.bookmarkPresencePhase)
        viewModel.toggleBookmark()
        runCurrent()
        assertEquals(0, repository.saveCalls)
    }

    /** 同一调度回合切换候选时，旧观察任务不得覆盖新候选或清除新任务。 */
    @Test
    fun switchingCandidateInSameTurnKeepsNewBookmarkObservationOwned() = runTest(dispatcher.scheduler) {
        /** repository 为每个 hash 提供独立延迟精确结果。 */
        val repository = DelayedBookmarkRepository()
        /** viewModel 使用真实候选代次编排。 */
        val viewModel = TorrentDetailsViewModel(listOf(FakeDetailsProvider(false)), TorrentDetailsLoader(), repository)
        viewModel.setCandidate(candidate(HASH), ProviderId("details_fixture"))
        viewModel.setScreenActive(true)
        runCurrent()

        viewModel.setCandidate(candidate(HASH_B), ProviderId("details_fixture"))
        runCurrent()
        repository.emit(HASH_B, false)
        runCurrent()
        assertEquals(BookmarkPresencePhase.READY, viewModel.uiState.value.bookmarkPresencePhase)

        viewModel.setScreenActive(false)
        runCurrent()
        assertEquals(2, repository.cancelCount)
    }

    /** 同一调度回合切候选后，旧书签写 finally 不得释放新候选写任务。 */
    @Test
    fun switchingCandidateInSameTurnKeepsNewBookmarkWriteOwned() = runTest(dispatcher.scheduler) {
        /** repository 让每代保存挂起直到候选切换或离页。 */
        val repository = BlockingBookmarkWriteRepository()
        /** viewModel 使用真实观察与写入代次。 */
        val viewModel = TorrentDetailsViewModel(listOf(FakeDetailsProvider(false)), TorrentDetailsLoader(), repository)
        viewModel.setCandidate(candidate(HASH), ProviderId("details_fixture"))
        viewModel.setScreenActive(true)
        runCurrent()
        viewModel.toggleBookmark()
        runCurrent()

        viewModel.setCandidate(candidate(HASH_B), ProviderId("details_fixture"))
        runCurrent()
        viewModel.toggleBookmark()
        runCurrent()
        viewModel.setScreenActive(false)
        runCurrent()

        assertEquals(2, repository.writeCancellations)
    }

    /** 可观察已查看写入的内存 Repository。 */
    private class FakeViewedRepository : ViewedTorrentRepository {
        /** 按调用顺序保存合成 hash。 */
        val markedHashes = mutableListOf<String>()

        /** 本测试不观察列表。 */
        override fun observeViewed(): Flow<List<ViewedTorrent>> = emptyFlow()

        /** 记录已查看身份并返回成功。 */
        override suspend fun markViewed(infoHash: String): Boolean {
            markedHashes += infoHash
            return true
        }

        /** 本测试不清空数据。 */
        override suspend fun clear(): Int = 0
    }

    /** 可观察单个稳定身份的书签 Repository fake。 */
    private class FakeBookmarkRepository : BookmarkRepository {
        /** exactState 表示当前候选是否已保存。 */
        private val exactState = MutableStateFlow(false)
        /** observeCalls 记录精确 Flow 的订阅创建次数。 */
        var observeCalls = 0
        /** cancelCount 记录详情离页取消次数。 */
        var cancelCount = 0

        /** 本测试禁止整表订阅。 */
        override fun observeBookmarks(): Flow<List<BookmarkedTorrent>> = error("unexpected full table observation")

        /** 返回精确存在流并记录取消。 */
        override fun observeBookmarked(infoHash: String): Flow<Boolean> {
            observeCalls += 1
            return exactState.onCompletion { cancelCount += 1 }
        }

        /** 保存后发布存在状态。 */
        override suspend fun save(bookmark: BookmarkedTorrent): Boolean {
            exactState.value = true
            return true
        }

        /** 删除后发布不存在状态。 */
        override suspend fun delete(infoHash: String): Boolean {
            exactState.value = false
            return true
        }

        /** 本测试不清空整表。 */
        override suspend fun clear(): Int = 0
    }

    /** 延迟精确结果并按 hash 隔离流的 Repository。 */
    private class DelayedBookmarkRepository : BookmarkRepository {
        /** flows 保存每个合成身份的精确结果通道。 */
        private val flows = mutableMapOf<String, MutableSharedFlow<Boolean>>()
        /** saveCalls 记录保存次数。 */
        var saveCalls = 0
        /** deleteCalls 记录删除次数。 */
        var deleteCalls = 0
        /** cancelCount 记录被候选切换或离页关闭的观察者。 */
        var cancelCount = 0

        /** 本测试禁止整表订阅。 */
        override fun observeBookmarks(): Flow<List<BookmarkedTorrent>> = error("unexpected full table observation")

        /** 返回当前 hash 的延迟流。 */
        override fun observeBookmarked(infoHash: String): Flow<Boolean> = flow {
            /** source 是当前稳定身份独享的热流。 */
            val source = flows.getOrPut(infoHash) { MutableSharedFlow(extraBufferCapacity = 1) }
            try {
                source.collect { /** bookmarked 是测试注入的精确值。 */ bookmarked -> emit(bookmarked) }
            } finally {
                cancelCount += 1
            }
        }

        /** 发布指定 hash 的精确存在状态。 */
        suspend fun emit(infoHash: String, bookmarked: Boolean) {
            /** source 是与生产观察调用共享的测试通道。 */
            val source = flows.getOrPut(infoHash) { MutableSharedFlow(extraBufferCapacity = 1) }
            source.emit(bookmarked)
        }

        /** 记录保存调用。 */
        override suspend fun save(bookmark: BookmarkedTorrent): Boolean {
            saveCalls += 1
            return true
        }

        /** 记录删除调用。 */
        override suspend fun delete(infoHash: String): Boolean {
            deleteCalls += 1
            return true
        }

        /** 本测试不清空。 */
        override suspend fun clear(): Int = 0
    }

    /** 精确查询固定失败的 Repository。 */
    private class FailingObserveBookmarkRepository : BookmarkRepository {
        /** saveCalls 记录错误态是否被错误写入。 */
        var saveCalls = 0

        /** 本测试禁止整表订阅。 */
        override fun observeBookmarks(): Flow<List<BookmarkedTorrent>> = error("unexpected full table observation")
        /** 精确观察启动即失败。 */
        override fun observeBookmarked(infoHash: String): Flow<Boolean> = flow { throw IllegalStateException("synthetic") }
        /** 记录任何错误保存。 */
        override suspend fun save(bookmark: BookmarkedTorrent): Boolean {
            saveCalls += 1
            return true
        }
        /** 本测试不删除。 */
        override suspend fun delete(infoHash: String): Boolean = true
        /** 本测试不清空。 */
        override suspend fun clear(): Int = 0
    }

    /** 每代书签保存都挂起的 Repository。 */
    private class BlockingBookmarkWriteRepository : BookmarkRepository {
        /** exactState 让每个候选精确查询立即 ready 且未收藏。 */
        private val exactState = MutableStateFlow(false)
        /** writeCancellations 记录候选切换与离页分别取消写入。 */
        var writeCancellations = 0

        /** 本测试禁止整表订阅。 */
        override fun observeBookmarks(): Flow<List<BookmarkedTorrent>> = error("unexpected full table observation")
        /** 每个候选都立即报告未收藏。 */
        override fun observeBookmarked(infoHash: String): Flow<Boolean> = exactState
        /** 挂起保存直到当前任务失去所有权。 */
        override suspend fun save(bookmark: BookmarkedTorrent): Boolean {
            return try {
                awaitCancellation()
            } finally {
                writeCancellations += 1
            }
        }
        /** 本测试不删除。 */
        override suspend fun delete(infoHash: String): Boolean = true
        /** 本测试不清空。 */
        override suspend fun clear(): Int = 0
    }

    /** 可挂起的详情 Provider fake。 */
    private class FakeDetailsProvider(
        /** true 时详情调用永久挂起。 */
        private val suspendUntilCancelled: Boolean,
    ) : TorrentDetailsProvider {
        /** 固定详情能力描述符。 */
        override val descriptor = ProviderDescriptor(
            id = ProviderId("details_fixture"),
            displayName = "Fixture",
            baseUrl = "https://fixture.invalid",
            safetyLevel = ProviderSafetyLevel.SAFE,
            enabledByDefault = true,
        )
        /** 详情调用次数。 */
        var calls = 0
        /** 挂起调用收到取消的次数。 */
        var cancelCount = 0

        /** 搜索不属于本测试路径。 */
        override suspend fun search(query: String, category: Category): List<Torrent> = error("unexpected search")

        /** 返回含不可信描述的详情或挂起等待取消。 */
        override suspend fun getDetails(detailsPageUrl: String): TorrentDetails? {
            calls += 1
            if (suspendUntilCancelled) {
                try {
                    awaitCancellation()
                } finally {
                    cancelCount += 1
                }
            }
            return TorrentDetails(
                infoHash = HASH,
                name = "Synthetic",
                providerId = descriptor.id,
                providerName = descriptor.displayName,
                magnetUri = "magnet:?xt=urn:btih:$HASH",
                description = "<p>Visible</p><script>secret()</script>",
            )
        }
    }

    /** 构造详情入口候选。 */
    private fun candidate(infoHash: String = HASH): Torrent = Torrent(
        infoHash = infoHash,
        name = "Synthetic",
        providerId = ProviderId("details_fixture"),
        providerName = "Fixture",
        descriptionPageUrl = "https://fixture.invalid/item",
    )

    /** 合成身份集中定义。 */
    companion object {
        /** 唯一合成 infoHash。 */
        private const val HASH = "0123456789012345678901234567890123456789"
        /** 第二个合成 infoHash 用于候选切换。 */
        private const val HASH_B = "abcdefabcdefabcdefabcdefabcdefabcdefabcd"
    }
}
