package com.cla.clip.feature.torrent.search.ui.browse

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.provider.LatestTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.TopTorrentsProvider
import com.cla.clip.feature.torrent.search.settings.TorrentSearchSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Browse ViewModel 的可见性启动、离页取消与重进恢复回归。 */
@OptIn(ExperimentalCoroutinesApi::class)
class TorrentBrowseViewModelTest {
    /** dispatcher 替换 viewModelScope Main 并提供可控虚拟时序。 */
    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    /** 每个测试前安装可控 Main。 */
    @Before
    fun setUpMainDispatcher() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    /** 每个测试后恢复全局 Main。 */
    @After
    fun tearDownMainDispatcher() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    /** 构造和设置首快照都不得联网，进入可见态后才加载。 */
    @Test
    fun loadsOnlyAfterDestinationBecomesActive() = runTest(dispatcher.scheduler) {
        /** provider 返回一条短小 Latest 结果。 */
        val provider = FakeBrowseProvider(suspendLatest = false)
        /** viewModel 依赖真实选择策略与协调器。 */
        val viewModel = createViewModel(provider)
        runCurrent()
        assertEquals(0, provider.latestCalls)

        viewModel.setScreenActive(true)
        runCurrent()

        assertEquals(1, provider.latestCalls)
        assertEquals(BrowsePhase.COMPLETED, viewModel.uiState.value.phase)
        assertEquals(1, viewModel.uiState.value.results.size)
    }

    /** 离开目的地取消当前加载，重新进入按当前模式和分类重新加载。 */
    @Test
    fun leavingCancelsAndReenteringReloadsCurrentBrowseState() = runTest(dispatcher.scheduler) {
        /** provider 每次 Latest 都挂起，便于观察两轮调用和取消。 */
        val provider = FakeBrowseProvider(suspendLatest = true)
        /** viewModel 初始不可见。 */
        val viewModel = createViewModel(provider)
        runCurrent()
        viewModel.setScreenActive(true)
        runCurrent()
        assertEquals(1, provider.latestCalls)

        viewModel.setScreenActive(false)
        runCurrent()
        assertEquals(1, provider.cancelCount)
        assertFalse(viewModel.uiState.value.isLoading)

        viewModel.setScreenActive(true)
        runCurrent()
        assertEquals(2, provider.latestCalls)
    }

    /** 切换模式或分类必须取消旧请求并立即以新快照启动。 */
    @Test
    fun modeAndCategoryChangesCancelOldLoadAndUseNewSnapshot() = runTest(dispatcher.scheduler) {
        /** provider 的 Latest 挂起，Top 立即返回以证明模式切换。 */
        val provider = FakeBrowseProvider(suspendLatest = true)
        /** viewModel 进入 Latest 加载。 */
        val viewModel = createViewModel(provider)
        runCurrent()
        viewModel.setScreenActive(true)
        runCurrent()

        viewModel.updateCategory(Category.MOVIES)
        runCurrent()
        assertEquals(1, provider.cancelCount)
        assertEquals(Category.MOVIES, provider.lastLatestCategory)

        viewModel.updateMode(BrowseMode.TOP)
        runCurrent()
        assertEquals(2, provider.cancelCount)
        assertEquals(1, provider.topCalls)
        assertEquals(Category.MOVIES, provider.lastTopCategory)
    }

    /** 使用内存 Preferences 流构造真实设置仓库和 ViewModel。 */
    private fun createViewModel(
        /** 本次测试唯一浏览来源。 */
        provider: FakeBrowseProvider,
    ): TorrentBrowseViewModel {
        /** repository 以空设置表示首次使用注册表默认来源。 */
        val repository = TorrentSearchSettingsRepository(FakePreferencesDataStore(preferencesOf()))
        return TorrentBrowseViewModel(listOf(provider), BrowseCoordinator(), repository)
    }
}

/** 可切换挂起行为的浏览 fake，记录模式、分类与取消。 */
private class FakeBrowseProvider(
    /** true 时每次 Latest 调用永久挂起。 */
    private val suspendLatest: Boolean,
) : LatestTorrentsProvider, TopTorrentsProvider {
    /** 固定安全且默认启用的合成描述符。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("browse_fixture"),
        displayName = "Fixture",
        baseUrl = "https://fixture.invalid",
        safetyLevel = ProviderSafetyLevel.SAFE,
        enabledByDefault = true,
    )
    /** Latest 实际调用次数。 */
    var latestCalls = 0
    /** Top 实际调用次数。 */
    var topCalls = 0
    /** 挂起 Latest 收到取消的次数。 */
    var cancelCount = 0
    /** 最近一次 Latest 分类。 */
    var lastLatestCategory: Category? = null
    /** 最近一次 Top 分类。 */
    var lastTopCategory: Category? = null

    /** 搜索不是当前测试路径。 */
    override suspend fun search(query: String, category: Category): List<Torrent> = error("unexpected search")

    /** 返回单条合成结果或挂起等待取消。 */
    override suspend fun getLatestTorrents(category: Category): List<Torrent> {
        latestCalls += 1
        lastLatestCategory = category
        if (suspendLatest) {
            try {
                awaitCancellation()
            } finally {
                cancelCount += 1
            }
        }
        return listOf(torrent())
    }

    /** 立即返回 Top 合成结果。 */
    override suspend fun getTopTorrents(category: Category): List<Torrent> {
        topCalls += 1
        lastTopCategory = category
        return listOf(torrent())
    }

    /** 构造短小且可排序的合成结果。 */
    private fun torrent(): Torrent = Torrent(
        infoHash = "0123456789012345678901234567890123456789",
        name = "Synthetic",
        seeders = 1u,
        providerId = descriptor.id,
        providerName = descriptor.displayName,
        uploadEpochMillis = 1L,
    )
}

/** 内存 DataStore 只替代磁盘，保留真实设置 Flow 和原子更新语义。 */
private class FakePreferencesDataStore(
    /** 初始设置快照。 */
    initialPreferences: Preferences,
) : DataStore<Preferences> {
    /** state 保存当前不可变 Preferences。 */
    private val state = MutableStateFlow(initialPreferences)
    /** data 暴露连续设置流。 */
    override val data: Flow<Preferences> = state

    /** 原子应用真实 Repository 传入的设置变换。 */
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        /** updated 是当前事务的新设置快照。 */
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
