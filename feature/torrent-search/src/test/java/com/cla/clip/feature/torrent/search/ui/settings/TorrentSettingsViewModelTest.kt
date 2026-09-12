package com.cla.clip.feature.torrent.search.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.settings.TorrentSearchDnsMode
import com.cla.clip.feature.torrent.search.settings.TorrentSearchSettingsRepository
import com.cla.clip.feature.torrent.search.torznab.TorznabConfigManager
import com.cla.clip.feature.torrent.search.torznab.TorznabConfigId
import com.cla.clip.feature.torrent.search.torznab.security.TorznabApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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

/** 设置页 ViewModel 的状态映射与本机设置写回测试。 */
@OptIn(ExperimentalCoroutinesApi::class)
class TorrentSettingsViewModelTest {
    /** dispatcher 控制 viewModelScope Main 任务。 */
    private val dispatcher = StandardTestDispatcher()

    /** 测试前替换 Main。 */
    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    /** 测试后恢复 Main。 */
    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    /** 页面应显示完整 Provider 数量，并把开关、DoH 和披露重置写回同一仓库。 */
    @Test
    fun mapsProvidersAndPersistsUserActions() = runTest(dispatcher.scheduler) {
        /** providers 包含一个默认安全来源和一个默认关闭来源。 */
        val providers = listOf(
            SettingsFakeProvider("nyaa", true),
            SettingsFakeProvider("fixture", false),
        )
        /** repository 预置两个已启用来源、披露确认和系统 DNS。 */
        val repository = TorrentSearchSettingsRepository(SettingsFakeDataStore(preferencesOf()))
        repository.setEnabledProviderIds(providers.mapTo(linkedSetOf()) { /** provider 提供待启用的稳定身份。 */ provider ->
            provider.descriptor.id
        })
        repository.setMultiSourceDisclosureAccepted(true)
        /** manager 在本测试只满足构造依赖，不执行配置或密钥操作。 */
        val manager = TorznabConfigManager(repository, SettingsEmptyApiKeyStore, Dispatchers.Unconfined)
        /** viewModel 收集真实设置 Flow 并映射稳定列表项。 */
        val viewModel = TorrentSettingsViewModel(providers, repository, manager)
        runCurrent()

        assertTrue(viewModel.uiState.value.settingsReady)
        assertEquals(2, viewModel.uiState.value.providers.size)
        assertEquals(2, viewModel.uiState.value.enabledProviderCount)
        assertTrue(viewModel.uiState.value.multiSourceDisclosureAccepted)

        viewModel.setProviderEnabled(ProviderId("fixture"), false)
        viewModel.setDnsMode(TorrentSearchDnsMode.CLOUDFLARE)
        viewModel.resetMultiSourceDisclosure()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.enabledProviderCount)
        assertEquals(TorrentSearchDnsMode.CLOUDFLARE, viewModel.uiState.value.dnsMode)
        assertFalse(viewModel.uiState.value.multiSourceDisclosureAccepted)
    }
}

/** 设置页测试使用的无网络 Provider。 */
private class SettingsFakeProvider(
    /** Provider 稳定身份。 */
    rawId: String,
    /** 是否属于首次推荐来源。 */
    enabledByDefault: Boolean,
) : SearchProvider {
    /** descriptor 只承载页面展示所需元数据。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId(rawId),
        displayName = "Fixture",
        baseUrl = "https://example.invalid",
        supportedCategories = setOf(Category.ALL),
        safetyLevel = ProviderSafetyLevel.SAFE,
        enabledByDefault = enabledByDefault,
    )

    /** 页面状态测试不得启动搜索。 */
    override suspend fun search(query: String, category: Category) = error("unexpected_network")
}

/** 设置页测试使用的内存 Preferences DataStore。 */
private class SettingsFakeDataStore(
    /** 初始设置快照。 */
    initialPreferences: Preferences,
) : DataStore<Preferences> {
    /** state 保存每次事务后的快照。 */
    private val state = MutableStateFlow(initialPreferences)
    /** data 对外提供连续设置流。 */
    override val data: Flow<Preferences> = state

    /** 执行真实 Preferences 事务变换。 */
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        /** updated 是变换后的不可变快照。 */
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

/** 设置页测试不使用 API Key 的安全仓库。 */
private object SettingsEmptyApiKeyStore : TorznabApiKeyStore {
    /** 测试不保存密钥。 */
    override fun save(configId: TorznabConfigId, apiKey: String?): Boolean = true

    /** 测试没有既有密钥。 */
    override fun read(configId: TorznabConfigId): String? = null

    /** 测试不删除密钥。 */
    override fun remove(configId: TorznabConfigId): Boolean = true
}
