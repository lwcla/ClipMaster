package com.cla.clip.feature.torrent.search.ui.local

import android.net.Uri
import android.net.SyntheticUri
import com.cla.clip.feature.torrent.search.data.document.BookmarkDocumentGateway
import com.cla.clip.feature.torrent.search.data.importer.BookmarkImportCoordinator
import com.cla.clip.feature.torrent.search.data.exporter.BookmarkExportSerializer
import com.cla.clip.feature.torrent.search.data.importer.BookmarkImportResult
import com.cla.clip.feature.torrent.search.data.model.BookmarkedTorrent
import com.cla.clip.feature.torrent.search.data.model.TorrentSearchHistory
import com.cla.clip.feature.torrent.search.data.model.ViewedTorrent
import com.cla.clip.feature.torrent.search.data.repository.BookmarkRepository
import com.cla.clip.feature.torrent.search.data.repository.NoOpTorrentSearchDataLogger
import com.cla.clip.feature.torrent.search.data.repository.TorrentSearchHistoryRepository
import com.cla.clip.feature.torrent.search.data.repository.ViewedTorrentRepository
import com.cla.clip.feature.torrent.search.provider.ProviderId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
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

/** 三类本地数据 ViewModel 的激活收集、离页取消与清空确认测试。 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalDataViewModelsTest {
    /** dispatcher 固定 viewModelScope Main 时序。 */
    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    /** 每项测试前安装可控 Main。 */
    @Before
    fun setUpMainDispatcher() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    /** 每项测试后恢复真实 Main。 */
    @After
    fun tearDownMainDispatcher() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    /** 书签未激活不收集，激活取得首快照，离页停止 Flow。 */
    @Test
    fun bookmarksCollectOnlyWhileDestinationIsActive() = runTest(dispatcher.scheduler) {
        /** repository 提供可追踪订阅的合成书签流。 */
        val repository = TrackingBookmarkRepository()
        /** viewModel 不执行 SAF，仅验证生命周期边界。 */
        val viewModel = BookmarksViewModel(
            repository,
            FakeBookmarkDocumentGateway,
            BookmarkImportCoordinator(repository),
            NoOpTorrentSearchDataLogger,
        )
        runCurrent()
        assertEquals(0, repository.subscriptions)

        repository.state.value = listOf(bookmark())
        viewModel.setScreenActive(true)
        runCurrent()
        assertEquals(1, repository.subscriptions)
        assertTrue(viewModel.uiState.value.snapshotReady)
        assertEquals(1, viewModel.uiState.value.bookmarks.size)

        viewModel.setScreenActive(false)
        runCurrent()
        assertEquals(1, repository.cancellations)
    }

    /** 同一调度回合离页再重进时，旧 finally 不得清掉新书签收集任务。 */
    @Test
    fun bookmarksStopThenStartInSameTurnKeepsTheNewCollectorOwned() = runTest(dispatcher.scheduler) {
        /** repository 记录两代收集者是否都被最终离页取消。 */
        val repository = TrackingBookmarkRepository()
        /** viewModel 使用真实任务所有权编排。 */
        val viewModel = BookmarksViewModel(repository, FakeBookmarkDocumentGateway, BookmarkImportCoordinator(repository), NoOpTorrentSearchDataLogger)
        viewModel.setScreenActive(true)
        runCurrent()

        viewModel.setScreenActive(false)
        viewModel.setScreenActive(true)
        runCurrent()
        assertEquals(2, repository.subscriptions)

        viewModel.setScreenActive(false)
        runCurrent()
        assertEquals(2, repository.cancellations)
    }

    /** 同一调度回合重进后，旧导入 finally 不得清除新导入任务所有权。 */
    @Test
    fun bookmarksOperationStopThenStartInSameTurnKeepsNewOperationOwned() = runTest(dispatcher.scheduler) {
        /** repository 让每代精确删除挂起直到离页取消。 */
        val repository = BlockingBookmarkOperationRepository()
        /** viewModel 运行真实书签操作所有权编排。 */
        val viewModel = BookmarksViewModel(repository, FakeBookmarkDocumentGateway, BookmarkImportCoordinator(repository), NoOpTorrentSearchDataLogger)
        viewModel.setScreenActive(true)
        runCurrent()
        viewModel.remove(HASH)
        runCurrent()

        viewModel.setScreenActive(false)
        viewModel.setScreenActive(true)
        runCurrent()
        viewModel.remove(HASH)
        runCurrent()
        viewModel.setScreenActive(false)
        runCurrent()

        assertEquals(2, repository.deleteCancellations)
    }

    /** 超过 10 MiB 的快照必须在任何输出流网关调用前整体失败。 */
    @Test
    fun oversizedBookmarkExportNeverCallsDocumentGateway() = runTest(dispatcher.scheduler) {
        /** repository 提供总编码必然超限但每项字段合法的完整快照。 */
        val repository = TrackingBookmarkRepository()
        /** longUrl 保持在单字段 8192 字符内。 */
        val longUrl = "https://example.invalid/" + "x".repeat(8_100)
        repository.state.value = (0 until 1_500).map { /** index 生成唯一合法书签。 */ index ->
            bookmark().copy(infoHash = index.toString(16).padStart(40, '0'), descriptionPageUrl = longUrl)
        }
        /** gateway 记录是否错误打开了输出目标。 */
        val gateway = TrackingWriteBookmarkDocumentGateway()
        /** serializer 使用当前测试 dispatcher，确保预检完成可控。 */
        val serializer = BookmarkExportSerializer(StandardTestDispatcher(dispatcher.scheduler))
        /** viewModel 运行真实导出次序。 */
        val viewModel = BookmarksViewModel(repository, gateway, BookmarkImportCoordinator(repository), NoOpTorrentSearchDataLogger, serializer)
        viewModel.setScreenActive(true)
        runCurrent()

        viewModel.exportTo(SyntheticUri())
        runCurrent()

        assertEquals(0, gateway.writeCalls)
        assertEquals(LocalDataFailure.EXPORT_FAILED, viewModel.uiState.value.failure)
    }


    /** 搜索历史只有确认清空后才写入，离页会取消挂起清空。 */
    @Test
    fun historyClearRequiresConfirmationAndLeavingCancelsOperation() = runTest(dispatcher.scheduler) {
        /** repository 提供一条历史并让 clear 挂起。 */
        val repository = TrackingHistoryRepository()
        /** viewModel 是真实历史状态编排器。 */
        val viewModel = SearchHistoryViewModel(repository)
        viewModel.setScreenActive(true)
        runCurrent()
        viewModel.requestClear()
        assertTrue(viewModel.uiState.value.showClearConfirmation)
        assertEquals(0, repository.clearCalls)

        viewModel.confirmClear()
        runCurrent()
        assertEquals(1, repository.clearCalls)
        assertTrue(viewModel.uiState.value.isBusy)

        viewModel.setScreenActive(false)
        runCurrent()
        assertTrue(repository.clearCancelled)
        assertFalse(viewModel.uiState.value.isBusy)
    }

    /** 同一调度回合重进后，旧历史收集者结束不得释放新收集者所有权。 */
    @Test
    fun historyStopThenStartInSameTurnKeepsTheNewCollectorOwned() = runTest(dispatcher.scheduler) {
        /** repository 提供可追踪的连续历史流。 */
        val repository = TrackingHistoryRepository()
        /** viewModel 使用真实生命周期编排。 */
        val viewModel = SearchHistoryViewModel(repository)
        viewModel.setScreenActive(true)
        runCurrent()

        viewModel.setScreenActive(false)
        viewModel.setScreenActive(true)
        runCurrent()
        assertEquals(2, repository.subscriptions)

        viewModel.setScreenActive(false)
        runCurrent()
        assertEquals(2, repository.collectionCancellations)
    }

    /** 同一调度回合重进后，旧历史操作 finally 不得清除新清空任务。 */
    @Test
    fun historyOperationStopThenStartInSameTurnKeepsNewOperationOwned() = runTest(dispatcher.scheduler) {
        /** repository 让每代清空挂起并累计取消。 */
        val repository = TrackingHistoryRepository()
        /** viewModel 使用真实写任务代次。 */
        val viewModel = SearchHistoryViewModel(repository)
        viewModel.setScreenActive(true)
        runCurrent()
        viewModel.requestClear()
        viewModel.confirmClear()
        runCurrent()

        viewModel.setScreenActive(false)
        viewModel.setScreenActive(true)
        runCurrent()
        viewModel.requestClear()
        viewModel.confirmClear()
        runCurrent()
        viewModel.setScreenActive(false)
        runCurrent()

        assertEquals(2, repository.clearCancellations)
    }

    /** 已查看未激活不收集，确认后调用清空且离页停止 Flow。 */
    @Test
    fun viewedCollectsWhenActiveAndClearsOnlyAfterConfirmation() = runTest(dispatcher.scheduler) {
        /** repository 提供一条只含安全身份的查看记录。 */
        val repository = TrackingViewedRepository()
        /** viewModel 是真实已查看状态编排器。 */
        val viewModel = ViewedTorrentsViewModel(repository)
        assertEquals(0, repository.subscriptions)

        viewModel.setScreenActive(true)
        runCurrent()
        assertEquals(1, repository.subscriptions)
        viewModel.requestClear()
        assertEquals(0, repository.clearCalls)

        viewModel.confirmClear()
        runCurrent()
        assertEquals(1, repository.clearCalls)

        viewModel.setScreenActive(false)
        runCurrent()
        assertEquals(1, repository.cancellations)
    }

    /** 同一调度回合重进后，旧已查看收集者不得覆盖新任务引用。 */
    @Test
    fun viewedStopThenStartInSameTurnKeepsTheNewCollectorOwned() = runTest(dispatcher.scheduler) {
        /** repository 记录各代已查看收集者。 */
        val repository = TrackingViewedRepository()
        /** viewModel 使用真实任务所有权编排。 */
        val viewModel = ViewedTorrentsViewModel(repository)
        viewModel.setScreenActive(true)
        runCurrent()

        viewModel.setScreenActive(false)
        viewModel.setScreenActive(true)
        runCurrent()
        assertEquals(2, repository.subscriptions)

        viewModel.setScreenActive(false)
        runCurrent()
        assertEquals(2, repository.cancellations)
    }

    /** 同一调度回合重进后，旧已查看操作 finally 不得清除新清空任务。 */
    @Test
    fun viewedOperationStopThenStartInSameTurnKeepsNewOperationOwned() = runTest(dispatcher.scheduler) {
        /** repository 同时提供首快照和两代挂起清空。 */
        val repository = BlockingViewedRepository()
        /** viewModel 使用真实操作代次。 */
        val viewModel = ViewedTorrentsViewModel(repository)
        viewModel.setScreenActive(true)
        runCurrent()
        viewModel.requestClear()
        viewModel.confirmClear()
        runCurrent()

        viewModel.setScreenActive(false)
        viewModel.setScreenActive(true)
        runCurrent()
        viewModel.requestClear()
        viewModel.confirmClear()
        runCurrent()
        viewModel.setScreenActive(false)
        runCurrent()

        assertEquals(2, repository.clearCancellations)
    }

    /** 构造最小合法合成书签。 */
    private fun bookmark(): BookmarkedTorrent = BookmarkedTorrent(
        infoHash = HASH,
        name = "Synthetic",
        providerId = ProviderId("fixture"),
        providerName = "Fixture",
    )

    /** 合成稳定身份集中定义。 */
    companion object {
        /** 测试唯一 SHA-1 infoHash。 */
        private const val HASH = "0123456789012345678901234567890123456789"
    }
}

/** 生命周期测试专用书签 Repository。 */
private class TrackingBookmarkRepository : BookmarkRepository {
    /** state 允许测试在激活前设置首个快照。 */
    val state = MutableStateFlow<List<BookmarkedTorrent>>(emptyList())
    /** subscriptions 记录整表 Flow 实际开始收集次数。 */
    var subscriptions = 0
    /** cancellations 记录收集协程结束次数。 */
    var cancellations = 0

    /** 返回可追踪收集与取消的真实 Flow。 */
    override fun observeBookmarks(): Flow<List<BookmarkedTorrent>> = flow {
        subscriptions += 1
        try {
            emitAll(state)
        } finally {
            cancellations += 1
        }
    }

    /** 测试默认没有既存书签。 */
    override fun observeBookmarked(infoHash: String): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)
    /** 本测试不保存。 */
    override suspend fun save(bookmark: BookmarkedTorrent): Boolean = true
    /** 本测试不删除。 */
    override suspend fun delete(infoHash: String): Boolean = true
    /** 本测试清空固定返回零。 */
    override suspend fun clear(): Int = 0
}

/** 不执行真实 Android IO 的文档网关。 */
private object FakeBookmarkDocumentGateway : BookmarkDocumentGateway {
    /** 生命周期测试禁止导入。 */
    override suspend fun read(uri: Uri): BookmarkImportResult = error("unexpected read")
    /** 生命周期测试禁止导出。 */
    override suspend fun write(uri: Uri, bytes: ByteArray) = error("unexpected write")
}

/** 书签操作代次测试专用 Repository。 */
private class BlockingBookmarkOperationRepository : BookmarkRepository {
    /** state 提供首个快照以保持页面正常就绪。 */
    private val state = MutableStateFlow(listOf(BookmarkedTorrent("0123456789012345678901234567890123456789", "Synthetic", ProviderId("fixture"), "Fixture")))
    /** deleteCancellations 记录每代删除收到的取消。 */
    var deleteCancellations = 0

    /** 返回连续书签快照。 */
    override fun observeBookmarks(): Flow<List<BookmarkedTorrent>> = state
    /** 本测试不观察详情精确状态。 */
    override fun observeBookmarked(infoHash: String): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true)
    /** 本测试不保存。 */
    override suspend fun save(bookmark: BookmarkedTorrent): Boolean = true
    /** 挂起删除直到离页。 */
    override suspend fun delete(infoHash: String): Boolean {
        return try {
            awaitCancellation()
        } finally {
            deleteCancellations += 1
        }
    }
    /** 本测试不清空。 */
    override suspend fun clear(): Int = 0
}

/** 只记录输出调用次数的文档网关。 */
private class TrackingWriteBookmarkDocumentGateway : BookmarkDocumentGateway {
    /** writeCalls 记录输出流边界是否被打开。 */
    var writeCalls = 0
    /** 本测试禁止读取。 */
    override suspend fun read(uri: Uri): BookmarkImportResult = error("unexpected read")
    /** 记录任何不应发生的写出。 */
    override suspend fun write(uri: Uri, bytes: ByteArray) {
        writeCalls += 1
    }
}

/** 可挂起清空的搜索历史 Repository。 */
private class TrackingHistoryRepository : TorrentSearchHistoryRepository {
    /** state 初始提供一条可清空记录。 */
    private val state = MutableStateFlow(listOf(TorrentSearchHistory("linux", "linux", 1L)))
    /** clearCalls 记录确认后的副作用次数。 */
    var clearCalls = 0
    /** clearCancelled 记录离页取消是否到达。 */
    var clearCancelled = false
    /** clearCancellations 记录每代挂起清空收到的取消。 */
    var clearCancellations = 0
    /** subscriptions 记录历史 Flow 实际订阅代数。 */
    var subscriptions = 0
    /** collectionCancellations 记录历史 Flow 实际结束代数。 */
    var collectionCancellations = 0

    /** 返回当前历史快照。 */
    override fun observeHistory(): Flow<List<TorrentSearchHistory>> = flow {
        subscriptions += 1
        try {
            emitAll(state)
        } finally {
            collectionCancellations += 1
        }
    }
    /** 本测试不保存查询。 */
    override suspend fun saveQuery(query: String): Boolean = true
    /** 本测试不删除单项。 */
    override suspend fun deleteQuery(query: String): Boolean = true

    /** 挂起清空直到目的地离开。 */
    override suspend fun clear(): Int {
        clearCalls += 1
        try {
            awaitCancellation()
        } finally {
            clearCancelled = true
            clearCancellations += 1
        }
    }
}

/** 已查看操作代次测试专用 Repository。 */
private class BlockingViewedRepository : ViewedTorrentRepository {
    /** state 始终提供一条记录以允许每代请求清空。 */
    private val state = MutableStateFlow(listOf(ViewedTorrent("0123456789012345678901234567890123456789", 1L)))
    /** clearCancellations 记录每代挂起清空的取消。 */
    var clearCancellations = 0

    /** 返回连续已查看快照。 */
    override fun observeViewed(): Flow<List<ViewedTorrent>> = state
    /** 本测试不写查看记录。 */
    override suspend fun markViewed(infoHash: String): Boolean = true
    /** 挂起清空直到离页。 */
    override suspend fun clear(): Int {
        return try {
            awaitCancellation()
        } finally {
            clearCancellations += 1
        }
    }
}

/** 可追踪订阅和清空的已查看 Repository。 */
private class TrackingViewedRepository : ViewedTorrentRepository {
    /** state 只包含稳定身份和时间。 */
    private val state = MutableStateFlow(listOf(ViewedTorrent("0123456789012345678901234567890123456789", 1L)))
    /** subscriptions 记录实际开始收集次数。 */
    var subscriptions = 0
    /** cancellations 记录离页停止次数。 */
    var cancellations = 0
    /** clearCalls 记录确认后的清空次数。 */
    var clearCalls = 0

    /** 返回可追踪生命周期的已查看 Flow。 */
    override fun observeViewed(): Flow<List<ViewedTorrent>> = flow {
        subscriptions += 1
        try {
            emitAll(state)
        } finally {
            cancellations += 1
        }
    }

    /** 本测试不写入查看记录。 */
    override suspend fun markViewed(infoHash: String): Boolean = true

    /** 记录清空调用并立即成功。 */
    override suspend fun clear(): Int {
        clearCalls += 1
        return 1
    }
}
