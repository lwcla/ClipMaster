package com.cla.clip.feature.torrent.search.ui.local

import android.net.Uri
import com.cla.clip.feature.torrent.search.data.document.BookmarkDocumentGateway
import com.cla.clip.feature.torrent.search.data.importer.BookmarkImportCoordinator
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

/** 可挂起清空的搜索历史 Repository。 */
private class TrackingHistoryRepository : TorrentSearchHistoryRepository {
    /** state 初始提供一条可清空记录。 */
    private val state = MutableStateFlow(listOf(TorrentSearchHistory("linux", "linux", 1L)))
    /** clearCalls 记录确认后的副作用次数。 */
    var clearCalls = 0
    /** clearCancelled 记录离页取消是否到达。 */
    var clearCancelled = false

    /** 返回当前历史快照。 */
    override fun observeHistory(): Flow<List<TorrentSearchHistory>> = state
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
