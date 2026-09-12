package com.cla.clip.feature.torrent.search.ui.search

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.cla.clip.feature.torrent.search.api.TorrentSearchLaunchSource
import com.cla.clip.feature.torrent.search.data.model.TorrentSearchHistory
import com.cla.clip.feature.torrent.search.data.repository.TorrentSearchHistoryRepository
import com.cla.clip.feature.torrent.search.input.TorrentSearchLaunchRequest
import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.SortCriteria
import com.cla.clip.feature.torrent.search.model.contract.SortOrder
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.search.SearchCoordinator
import com.cla.clip.feature.torrent.search.settings.TorrentSearchSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

/** 搜索 ViewModel 的隐私授权、渐进结果和生命周期取消回归。 */
@OptIn(ExperimentalCoroutinesApi::class)
class TorrentSearchViewModelTest {
    /** dispatcher 为 viewModelScope 提供可控 Main 调度器。 */
    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    /** 每个测试前替换 Main，禁止真实线程时序影响断言。 */
    @Before
    fun setUpMainDispatcher() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    /** 每个测试后恢复 Main，避免污染其他测试类。 */
    @After
    fun tearDownMainDispatcher() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    /** 构造和系统预填只能更新文本，未经用户提交不得启动任何 Provider。 */
    @Test
    fun launchRequestNeverStartsNetworkAutomatically() = runTest(dispatcher.scheduler) {
        /** provider 记录所有真实 search 调用。 */
        val provider = FakeSearchProvider("nyaa", listOf(torrent(HASH_A, 1u)))
        /** viewModel 使用真实协调器和内存设置仓库。 */
        val viewModel = createViewModel(listOf(provider))
        runCurrent()

        viewModel.acceptLaunchRequest(
            TorrentSearchLaunchRequest(
                query = "linux",
                canAutoSearch = true,
                wasTruncated = false,
                launchSource = TorrentSearchLaunchSource.ActionSearch,
            ),
        )
        advanceUntilIdle()

        assertEquals("linux", viewModel.uiState.value.query)
        assertEquals(0, provider.searchCalls)
    }

    /** 历史写入失败不得阻断已经通过门禁的 Provider 搜索。 */
    @Test
    fun historyWriteFailureDoesNotBlockSearch() = runTest(dispatcher.scheduler) {
        /** provider 记录真实网络边界是否仍被调用。 */
        val provider = FakeSearchProvider("nyaa", emptyList())
        /** repository 预置单来源以跳过多源披露。 */
        val settingsRepository = TorrentSearchSettingsRepository(FakePreferencesDataStore(preferencesOf()))
        settingsRepository.setEnabledProviderIds(setOf(provider.descriptor.id))
        /** historyRepository 固定抛出介质失败。 */
        val historyRepository = FakeHistoryRepository(throwOnSave = true)
        /** viewModel 同时连接真实搜索协调器与失败历史仓库。 */
        val viewModel = TorrentSearchViewModel(listOf(provider), SearchCoordinator(), settingsRepository, historyRepository)
        runCurrent()
        viewModel.setScreenActive(true)
        viewModel.updateQuery("linux")

        viewModel.submitSearch()
        advanceUntilIdle()

        assertEquals(1, provider.searchCalls)
        assertEquals(1, historyRepository.savedQueries.size)
        assertEquals(TorrentSearchPhase.COMPLETED, viewModel.uiState.value.phase)
    }

    /** 历史回放必须等搜索页恢复前台后只触发一次，并继续走正常提交入口。 */
    @Test
    fun historyReplayWaitsForSearchDestinationToBecomeActiveAndRunsOnce() = runTest(dispatcher.scheduler) {
        /** provider 记录回放是否在不可见阶段提前联网。 */
        val provider = FakeSearchProvider("nyaa", emptyList())
        /** settingsRepository 预置单来源避免多源弹窗遮蔽生命周期断言。 */
        val settingsRepository = TorrentSearchSettingsRepository(FakePreferencesDataStore(preferencesOf()))
        settingsRepository.setEnabledProviderIds(setOf(provider.descriptor.id))
        /** historyRepository 记录真正启动搜索后的历史写入。 */
        val historyRepository = FakeHistoryRepository()
        /** viewModel 模拟导航过渡期仍短暂 active 的搜索目的地。 */
        val viewModel = TorrentSearchViewModel(listOf(provider), SearchCoordinator(), settingsRepository, historyRepository)
        runCurrent()

        viewModel.setScreenActive(true)
        viewModel.requestHistoryReplay("linux")
        runCurrent()
        assertEquals(0, provider.searchCalls)

        viewModel.setScreenActive(false)
        viewModel.setScreenActive(true)
        advanceUntilIdle()
        viewModel.setScreenActive(true)
        advanceUntilIdle()

        assertEquals(1, provider.searchCalls)
        assertEquals(listOf("linux"), historyRepository.savedQueries)
    }

    /** 首次多源搜索必须先披露，确认后才联网，并按稳定 hash 去重排序。 */
    @Test
    fun firstMultiSourceSearchWaitsForDisclosureThenMergesResults() = runTest(dispatcher.scheduler) {
        /** slowerResult 与 fasterResult 使用不同做种数验证默认降序。 */
        val lowerSeedResult = torrent(HASH_A, 2u)
        /** duplicateResult 验证第二来源重复 hash 不产生第二张卡片。 */
        val duplicateResult = torrent(HASH_A, 9u)
        /** higherSeedResult 应排序到最终列表首位。 */
        val higherSeedResult = torrent(HASH_B, 7u)
        /** firstProvider 返回第一个稳定结果。 */
        val firstProvider = FakeSearchProvider("nyaa", listOf(lowerSeedResult))
        /** secondProvider 返回重复项和高做种项。 */
        val secondProvider = FakeSearchProvider("yts", listOf(duplicateResult, higherSeedResult))
        /** viewModel 使用两个默认启用来源触发多源披露。 */
        val viewModel = createViewModel(listOf(firstProvider, secondProvider))
        runCurrent()
        viewModel.setScreenActive(true)
        viewModel.updateQuery("linux")

        viewModel.submitSearch()
        runCurrent()

        assertTrue(viewModel.uiState.value.showMultiSourceDisclosure)
        assertEquals(2, viewModel.uiState.value.pendingDisclosureProviderCount)
        assertEquals(0, firstProvider.searchCalls + secondProvider.searchCalls)

        viewModel.confirmMultiSourceDisclosure()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showMultiSourceDisclosure)
        assertEquals(2, firstProvider.searchCalls + secondProvider.searchCalls)
        assertEquals(listOf(HASH_B, HASH_A), viewModel.uiState.value.results.map { /** result 是排序后的展示结果。 */ result -> result.infoHash })
        assertFalse(viewModel.uiState.value.isSearching)
        assertEquals(TorrentSearchPhase.COMPLETED, viewModel.uiState.value.phase)
    }

    /** 页面离开 STARTED 后必须取消仍在运行的 Provider，且不能伪造完成结果。 */
    @Test
    fun leavingVisibleLifecycleCancelsActiveSearch() = runTest(dispatcher.scheduler) {
        /** provider 在收到取消前永久挂起，用于观察结构化取消传播。 */
        val provider = FakeSearchProvider("nyaa", results = emptyList(), suspendUntilCancelled = true)
        /** dataStore 预先接受披露并启用单来源，避免测试被确认弹窗阻断。 */
        val dataStore = FakePreferencesDataStore(preferencesOf())
        /** repository 写入真实设置协议后交给 ViewModel。 */
        val repository = TorrentSearchSettingsRepository(dataStore)
        repository.setEnabledProviderIds(setOf(provider.descriptor.id))
        repository.setMultiSourceDisclosureAccepted(true)
        /** viewModel 执行可取消搜索。 */
        val viewModel = TorrentSearchViewModel(listOf(provider), SearchCoordinator(), repository)
        runCurrent()
        viewModel.setScreenActive(true)
        viewModel.updateQuery("linux")
        viewModel.submitSearch()
        runCurrent()
        assertTrue(viewModel.uiState.value.isSearching)

        viewModel.setScreenActive(false)
        runCurrent()

        assertTrue(provider.wasCancelled)
        assertFalse(viewModel.uiState.value.isSearching)
        assertTrue(viewModel.uiState.value.results.isEmpty())
    }

    /** 运行中重新开启安全模式必须立刻取消可能包含不安全来源的旧搜索。 */
    @Test
    fun enablingSafeModeCancelsUnsafeSearch() = runTest(dispatcher.scheduler) {
        /** provider 是允许在关闭安全模式时运行的合成不安全来源。 */
        val provider = FakeSearchProvider(
            rawId = "unsafe_fixture",
            results = emptyList(),
            suspendUntilCancelled = true,
            safetyLevel = ProviderSafetyLevel.UNSAFE,
        )
        /** repository 预置明确来源选择、披露确认和关闭的安全模式。 */
        val repository = TorrentSearchSettingsRepository(FakePreferencesDataStore(preferencesOf()))
        repository.setEnabledProviderIds(setOf(provider.descriptor.id))
        repository.setMultiSourceDisclosureAccepted(true)
        repository.setSafeModeEnabled(false)
        /** viewModel 启动包含不安全来源的搜索。 */
        val viewModel = TorrentSearchViewModel(listOf(provider), SearchCoordinator(), repository)
        runCurrent()
        viewModel.setScreenActive(true)
        viewModel.updateQuery("linux")
        viewModel.submitSearch()
        runCurrent()
        assertTrue(viewModel.uiState.value.isSearching)

        viewModel.requestSafeModeEnabled(true)
        viewModel.updateCategory(Category.PORN)

        assertTrue(viewModel.uiState.value.safeModeEnabled)
        assertEquals(Category.ALL, viewModel.uiState.value.category)
        runCurrent()

        assertTrue(provider.wasCancelled)
        assertTrue(viewModel.uiState.value.safeModeEnabled)
        assertFalse(viewModel.uiState.value.isSearching)
    }

    /** 首个设置快照到达前必须拒绝提交，不能按临时默认来源联网。 */
    @Test
    fun settingsLoadingBlocksSearchUntilFirstSnapshot() = runTest(dispatcher.scheduler) {
        /** provider 记录设置加载窗口是否错误启动网络。 */
        val provider = FakeSearchProvider("nyaa", emptyList())
        /** dataStore 主动延迟首个设置快照。 */
        val dataStore = DelayedPreferencesDataStore()
        /** viewModel 必须显式等待设置就绪。 */
        val viewModel = TorrentSearchViewModel(
            listOf(provider),
            SearchCoordinator(),
            TorrentSearchSettingsRepository(dataStore),
        )
        viewModel.setScreenActive(true)
        viewModel.updateQuery("linux")

        viewModel.submitSearch()

        assertEquals(TorrentSearchUiError.SETTINGS_LOADING, viewModel.uiState.value.error)
        assertEquals(0, provider.searchCalls)

        dataStore.emitInitial(preferencesOf())
        runCurrent()

        assertTrue(viewModel.uiState.value.settingsReady)
    }

    /** 新 Intent 必须废弃旧披露快照，不能让新词界面确认后发送旧词。 */
    @Test
    fun newLaunchRequestClearsPendingDisclosure() = runTest(dispatcher.scheduler) {
        /** firstProvider 与 secondProvider 触发首次多源披露。 */
        val firstProvider = FakeSearchProvider("nyaa", emptyList())
        /** secondProvider 提供第二个默认来源。 */
        val secondProvider = FakeSearchProvider("yts", emptyList())
        /** viewModel 使用未确认披露的初始设置。 */
        val viewModel = createViewModel(listOf(firstProvider, secondProvider))
        runCurrent()
        viewModel.setScreenActive(true)
        viewModel.updateQuery("old query")
        viewModel.submitSearch()
        assertTrue(viewModel.uiState.value.showMultiSourceDisclosure)

        viewModel.acceptLaunchRequest(
            TorrentSearchLaunchRequest(
                query = "new query",
                canAutoSearch = true,
                wasTruncated = false,
                launchSource = TorrentSearchLaunchSource.ActionSearch,
            ),
        )
        viewModel.confirmMultiSourceDisclosure()
        advanceUntilIdle()

        assertEquals("new query", viewModel.uiState.value.query)
        assertFalse(viewModel.uiState.value.showMultiSourceDisclosure)
        assertEquals(0, firstProvider.searchCalls + secondProvider.searchCalls)
    }

    /** 空结果完成必须区别于初始态，页面才能显示明确的完成空态。 */
    @Test
    fun emptySearchEndsInCompletedPhase() = runTest(dispatcher.scheduler) {
        /** provider 正常完成但返回空列表。 */
        val provider = FakeSearchProvider("nyaa", emptyList())
        /** repository 预先确认来源选择，避免其他状态遮蔽完成阶段。 */
        val repository = TorrentSearchSettingsRepository(FakePreferencesDataStore(preferencesOf()))
        repository.setEnabledProviderIds(setOf(provider.descriptor.id))
        /** viewModel 执行单来源空结果搜索。 */
        val viewModel = TorrentSearchViewModel(listOf(provider), SearchCoordinator(), repository)
        runCurrent()
        viewModel.setScreenActive(true)
        viewModel.updateQuery("linux")

        viewModel.submitSearch()
        advanceUntilIdle()

        assertEquals(TorrentSearchPhase.COMPLETED, viewModel.uiState.value.phase)
        assertTrue(viewModel.uiState.value.results.isEmpty())
    }

    /** 已有渐进结果时停止仍必须保留 STOPPED 阶段和现有结果。 */
    @Test
    fun stoppingAfterPartialResultsKeepsStoppedPhase() = runTest(dispatcher.scheduler) {
        /** fastProvider 先返回一条渐进结果。 */
        val fastProvider = FakeSearchProvider("nyaa", listOf(torrent(HASH_A, 3u)))
        /** slowProvider 保持运行，让页面有机会在完成前停止。 */
        val slowProvider = FakeSearchProvider("yts", emptyList(), suspendUntilCancelled = true)
        /** repository 明确启用两个来源并预先接受披露。 */
        val repository = TorrentSearchSettingsRepository(FakePreferencesDataStore(preferencesOf()))
        repository.setEnabledProviderIds(setOf(fastProvider.descriptor.id, slowProvider.descriptor.id))
        repository.setMultiSourceDisclosureAccepted(true)
        /** viewModel 启动可观察渐进状态的多源搜索。 */
        val viewModel = TorrentSearchViewModel(listOf(fastProvider, slowProvider), SearchCoordinator(), repository)
        runCurrent()
        viewModel.setScreenActive(true)
        viewModel.updateQuery("linux")
        viewModel.submitSearch()
        runCurrent()
        assertEquals(1, viewModel.uiState.value.results.size)
        assertTrue(viewModel.uiState.value.isSearching)

        viewModel.stopSearch()
        runCurrent()

        assertEquals(TorrentSearchPhase.STOPPED, viewModel.uiState.value.phase)
        assertEquals(1, viewModel.uiState.value.results.size)
        assertTrue(slowProvider.wasCancelled)
    }

    /** DataStore 写失败必须收敛为低敏 UI 错误，不能逃逸并取消测试作用域。 */
    @Test
    fun settingsWriteFailureIsContained() = runTest(dispatcher.scheduler) {
        /** viewModel 使用读取正常但所有写入失败的设置介质。 */
        val viewModel = TorrentSearchViewModel(
            listOf(FakeSearchProvider("nyaa", emptyList())),
            SearchCoordinator(),
            TorrentSearchSettingsRepository(FailingWritePreferencesDataStore(preferencesOf())),
        )
        runCurrent()

        viewModel.updateSort(SortCriteria.NAME, SortOrder.ASCENDING)
        advanceUntilIdle()

        assertEquals(TorrentSearchUiError.SETTINGS_WRITE_FAILED, viewModel.uiState.value.error)
    }

    /** 页面离开必须取消仍在持久化的前置任务，返回后不得自动启动旧搜索。 */
    @Test
    fun leavingPageCancelsPendingPreflightPermanently() = runTest(dispatcher.scheduler) {
        /** firstProvider 与 secondProvider 触发披露和前置设置写入。 */
        val firstProvider = FakeSearchProvider("nyaa", emptyList())
        /** secondProvider 是第二个默认启用来源。 */
        val secondProvider = FakeSearchProvider("yts", emptyList())
        /** dataStore 让首次写入保持挂起，直到页面取消。 */
        val dataStore = BlockingWritePreferencesDataStore(preferencesOf())
        /** viewModel 执行真实披露确认前置链路。 */
        val viewModel = TorrentSearchViewModel(
            listOf(firstProvider, secondProvider),
            SearchCoordinator(),
            TorrentSearchSettingsRepository(dataStore),
        )
        runCurrent()
        viewModel.setScreenActive(true)
        viewModel.updateQuery("linux")
        viewModel.submitSearch()
        viewModel.confirmMultiSourceDisclosure()
        runCurrent()
        assertTrue(dataStore.writeStarted)

        viewModel.setScreenActive(false)
        runCurrent()
        viewModel.setScreenActive(true)
        advanceUntilIdle()

        assertTrue(dataStore.wasCancelled)
        assertEquals(0, firstProvider.searchCalls + secondProvider.searchCalls)
    }

    /** 披露或默认来源持久化失败不得启动 Provider。 */
    @Test
    fun preflightWriteFailureDoesNotStartSearch() = runTest(dispatcher.scheduler) {
        /** provider 记录前置写失败后是否错误联网。 */
        val provider = FakeSearchProvider("nyaa", emptyList())
        /** repository 使用读取正常、写入失败的介质。 */
        val repository = TorrentSearchSettingsRepository(FailingWritePreferencesDataStore(preferencesOf()))
        /** viewModel 发起无需披露但需要初始化默认来源的搜索。 */
        val viewModel = TorrentSearchViewModel(listOf(provider), SearchCoordinator(), repository)
        runCurrent()
        viewModel.setScreenActive(true)
        viewModel.updateQuery("linux")

        viewModel.submitSearch()
        advanceUntilIdle()

        assertEquals(TorrentSearchUiError.SETTINGS_WRITE_FAILED, viewModel.uiState.value.error)
        assertEquals(0, provider.searchCalls)
    }

    /** 安全模式必须拒绝成人分类，并在从关闭状态重新开启时回退全部分类。 */
    @Test
    fun safeModeRejectsAndResetsAdultCategory() = runTest(dispatcher.scheduler) {
        /** repository 初始保持默认安全模式，先验证直接选择会被拒绝。 */
        val repository = TorrentSearchSettingsRepository(FakePreferencesDataStore(preferencesOf()))
        /** viewModel 使用单个安全来源即可验证纯本地分类状态。 */
        val viewModel = TorrentSearchViewModel(
            listOf(FakeSearchProvider("nyaa", emptyList())),
            SearchCoordinator(),
            repository,
        )
        runCurrent()

        viewModel.updateCategory(Category.PORN)

        assertEquals(Category.ALL, viewModel.uiState.value.category)

        repository.setSafeModeEnabled(false)
        runCurrent()
        viewModel.updateCategory(Category.PORN)
        assertEquals(Category.PORN, viewModel.uiState.value.category)

        viewModel.requestSafeModeEnabled(true)
        runCurrent()

        assertTrue(viewModel.uiState.value.safeModeEnabled)
        assertEquals(Category.ALL, viewModel.uiState.value.category)
    }

    /** 创建使用空 Preferences 的 ViewModel，默认来源由描述符首次推荐。 */
    private fun createViewModel(providers: List<SearchProvider>): TorrentSearchViewModel {
        /** repository 使用内存 DataStore 保留真实偏好事务。 */
        val repository = TorrentSearchSettingsRepository(FakePreferencesDataStore(preferencesOf()))
        return TorrentSearchViewModel(providers, SearchCoordinator(), repository)
    }

    /** 创建字段完整的合成 Torrent，只暴露排序与稳定身份所需差异。 */
    private fun torrent(infoHash: String, seeders: UInt): Torrent {
        return Torrent(
            infoHash = infoHash,
            name = "Synthetic",
            seeders = seeders,
            providerId = ProviderId("fixture"),
            providerName = "Fixture",
            category = Category.OTHER,
        )
    }

    /** 合成稳定身份集中定义，测试不使用真实 torrent 或用户数据。 */
    companion object {
        /** 第一条合成 SHA-1 infoHash。 */
        private const val HASH_A = "0123456789012345678901234567890123456789"
        /** 第二条合成 SHA-1 infoHash。 */
        private const val HASH_B = "abcdefabcdefabcdefabcdefabcdefabcdefabcd"
    }
}

/** 搜索 ViewModel 测试专用历史仓库。 */
private class FakeHistoryRepository(
    /** true 时保存查询抛出合成介质失败。 */
    private val throwOnSave: Boolean = false,
) : TorrentSearchHistoryRepository {
    /** 按实际调用顺序记录安全合成查询。 */
    val savedQueries = mutableListOf<String>()

    /** 本测试不观察历史页。 */
    override fun observeHistory(): Flow<List<TorrentSearchHistory>> = kotlinx.coroutines.flow.emptyFlow()

    /** 记录调用后按配置成功或抛错。 */
    override suspend fun saveQuery(query: String): Boolean {
        savedQueries += query
        if (throwOnSave) throw IllegalStateException("synthetic_history_failure")
        return true
    }

    /** 本测试不删除历史。 */
    override suspend fun deleteQuery(query: String): Boolean = false

    /** 本测试不清空历史。 */
    override suspend fun clear(): Int = 0
}

/** 可记录调用和取消的合成 Provider，不访问网络。 */
private class FakeSearchProvider(
    /** Provider 稳定 ID。 */
    rawId: String,
    /** 正常搜索时返回的合成结果。 */
    private val results: List<Torrent>,
    /** true 时保持挂起直到 ViewModel 取消任务。 */
    private val suspendUntilCancelled: Boolean = false,
    /** 当前合成来源安全等级。 */
    safetyLevel: ProviderSafetyLevel = ProviderSafetyLevel.SAFE,
) : SearchProvider {
    /** descriptor 默认标记为安全且首次推荐启用。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId(rawId),
        displayName = "Fixture",
        baseUrl = "https://example.invalid",
        safetyLevel = safetyLevel,
        enabledByDefault = true,
    )
    /** searchCalls 记录实际进入网络边界的次数。 */
    var searchCalls: Int = 0
        private set
    /** wasCancelled 证明页面不可见取消已到达 Provider。 */
    var wasCancelled: Boolean = false
        private set

    /** 返回合成结果或挂起等待结构化取消。 */
    override suspend fun search(query: String, category: Category): List<Torrent> {
        searchCalls += 1
        if (!suspendUntilCancelled) return results
        return try {
            awaitCancellation()
        } finally {
            wasCancelled = true
        }
    }
}

/** 内存 DataStore 仅替代磁盘，保留真实 Preferences updateData 与 Flow 语义。 */
private class FakePreferencesDataStore(
    /** 初始不可变设置快照。 */
    initialPreferences: Preferences,
) : DataStore<Preferences> {
    /** state 保存每次事务后的当前快照。 */
    private val state = MutableStateFlow(initialPreferences)
    /** data 对外提供连续设置流。 */
    override val data: Flow<Preferences> = state

    /** 在当前快照执行 Repository 提供的原子变换。 */
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        /** updated 是当前事务生成的新不可变快照。 */
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

/** 延迟首个快照的 DataStore，用于锁定设置就绪门禁。 */
private class DelayedPreferencesDataStore : DataStore<Preferences> {
    /** snapshots 在测试显式触发前不发射任何设置。 */
    private val snapshots = MutableSharedFlow<Preferences>(replay = 1)
    /** data 暴露受测试控制的设置流。 */
    override val data: Flow<Preferences> = snapshots

    /** 发射首个设置快照，模拟慢磁盘读取完成。 */
    suspend fun emitInitial(
        /** 要交给 Repository 的不可变设置。 */
        preferences: Preferences,
    ) {
        snapshots.emit(preferences)
    }

    /** 本测试不允许在首快照前写入。 */
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        error("unexpected_update")
    }
}

/** 读取正常但写入失败的 DataStore，用于验证 ViewModel 异常边界。 */
private class FailingWritePreferencesDataStore(
    /** 正常读取的初始设置。 */
    initialPreferences: Preferences,
) : DataStore<Preferences> {
    /** state 让设置首快照正常完成。 */
    private val state = MutableStateFlow(initialPreferences)
    /** data 提供正常读取流。 */
    override val data: Flow<Preferences> = state

    /** 所有设置写入都模拟稳定介质失败。 */
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        throw IllegalStateException("synthetic_write_failure")
    }
}

/** 读取正常但写入永久挂起的 DataStore，用于验证目的地离开取消。 */
private class BlockingWritePreferencesDataStore(
    /** 正常读取的初始设置。 */
    initialPreferences: Preferences,
) : DataStore<Preferences> {
    /** state 提供首个设置快照。 */
    private val state = MutableStateFlow(initialPreferences)
    /** data 对外提供正常读取流。 */
    override val data: Flow<Preferences> = state
    /** writeStarted 标记前置链路已经进入持久化边界。 */
    var writeStarted: Boolean = false
        private set
    /** wasCancelled 标记页面离开已取消挂起写入。 */
    var wasCancelled: Boolean = false
        private set

    /** 挂起写入直到结构化取消到达。 */
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        writeStarted = true
        return try {
            awaitCancellation()
        } finally {
            wasCancelled = true
        }
    }
}
