package com.cla.clip.feature.torrent.search.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.cla.clip.feature.torrent.search.backup.TorrentSearchBackupDirtyMarker
import com.cla.clip.feature.torrent.search.model.contract.SortCriteria
import com.cla.clip.feature.torrent.search.model.contract.SortOrder
import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.torznab.TorznabConfig
import com.cla.clip.feature.torrent.search.torznab.TorznabConfigId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TorrentSearch Preferences DataStore 的默认值、稳定 code 与写入测试。 */
class TorrentSearchSettingsRepositoryTest {
    /** 只有排序白名单变化入口标记 dirty，本机网络与隐私设置不触发。 */
    @Test
    fun onlySortWritesMarkBackupDirty() = runTest {
        /** marker 记录设置仓库触发统一 dirty 的次数。 */
        val marker = RecordingSettingsBackupDirtyMarker()
        /** repository 使用内存 DataStore 隔离 Android 持久化。 */
        val repository = TorrentSearchSettingsRepository(
            dataStore = FakePreferencesDataStore(preferencesOf()),
            backupDirtyMarker = marker,
        )

        repository.setSafeModeEnabled(false)
        repository.setEnabledProviderIds(setOf(ProviderId("nyaa")))
        repository.setDnsMode(TorrentSearchDnsMode.CLOUDFLARE)
        repository.setMultiSourceDisclosureAccepted(true)
        assertEquals(0, marker.count)

        repository.setSort(SortCriteria.DATE, SortOrder.ASCENDING)
        assertEquals(1, marker.count)
    }

    /** 损坏或未知持久值必须安全回退，非法 Provider ID 不得进入运行时设置。 */
    @Test
    fun settingsFlowFallsBackAndFiltersMalformedProviderIds() = runTest {
        /** initialPreferences 使用同名 key 模拟已有 DataStore 文件中的未知值。 */
        val initialPreferences = preferencesOf(
            stringSetPreferencesKey("enabled_provider_ids") to setOf("nyaa", "bad id", "Nyaa"),
            booleanPreferencesKey("safe_mode_enabled") to false,
            stringPreferencesKey("sort_criteria_code") to "future-sort",
            stringPreferencesKey("sort_order_code") to SortOrder.ASCENDING.code,
            stringPreferencesKey("dns_mode_code") to "future-dns",
            stringPreferencesKey("torznab_configs_json") to "not-json",
        )
        /** repository 读取真实 Preferences 快照，只有存储介质由内存 fake 替代。 */
        val repository = TorrentSearchSettingsRepository(FakePreferencesDataStore(initialPreferences))

        /** settings 是映射后的首个运行时快照。 */
        val settings = repository.settings.first()
        assertEquals(setOf(ProviderId("nyaa")), settings.enabledProviderIds)
        assertFalse(settings.safeModeEnabled)
        assertEquals(SortCriteria.SEEDERS, settings.sortCriteria)
        assertEquals(SortOrder.ASCENDING, settings.sortOrder)
        assertFalse(settings.providerSelectionInitialized)
        assertFalse(settings.multiSourceDisclosureAccepted)
        assertEquals(TorrentSearchDnsMode.SYSTEM, settings.dnsMode)
        assertTrue(settings.torznabConfigs.isEmpty())
    }

    /** 空文件默认必须开启安全模式并采用做种数降序。 */
    @Test
    fun settingsFlowUsesSafeDefaultsForEmptyStore() = runTest {
        /** repository 从空 Preferences 文件构造默认设置。 */
        val repository = TorrentSearchSettingsRepository(FakePreferencesDataStore(preferencesOf()))

        /** settings 锁定新安装的保守默认行为。 */
        val settings = repository.settings.first()
        assertTrue(settings.enabledProviderIds.isEmpty())
        assertTrue(settings.safeModeEnabled)
        assertEquals(SortCriteria.SEEDERS, settings.sortCriteria)
        assertEquals(SortOrder.DESCENDING, settings.sortOrder)
        assertFalse(settings.providerSelectionInitialized)
        assertFalse(settings.multiSourceDisclosureAccepted)
        assertEquals(TorrentSearchDnsMode.SYSTEM, settings.dnsMode)
        assertTrue(settings.torznabConfigs.isEmpty())
    }

    /** 原子更新必须只写稳定 Provider ID 和排序 code，并立即反映到 Flow。 */
    @Test
    fun updatesPersistStableIdsAndCodes() = runTest {
        /** dataStore 允许测试读取 Repository 更新后的真实 Preferences。 */
        val dataStore = FakePreferencesDataStore(preferencesOf())
        /** repository 执行三个公开设置更新。 */
        val repository = TorrentSearchSettingsRepository(dataStore)

        repository.setEnabledProviderIds(setOf(ProviderId("nyaa"), ProviderId("yts")))
        repository.setSafeModeEnabled(false)
        repository.setSort(SortCriteria.DATE, SortOrder.ASCENDING)
        repository.setMultiSourceDisclosureAccepted(true)
        repository.setDnsMode(TorrentSearchDnsMode.CLOUDFLARE)
        /** torznabConfig 是不含 API Key 明文的动态来源配置。 */
        val torznabConfig = TorznabConfig(
            id = TorznabConfigId("torznab_fixture"),
            name = "Fixture",
            feedUrl = "http://indexer.example:9117/api",
            category = Category.BOOKS,
            safetyLevel = ProviderSafetyLevel.UNSAFE,
            hasApiKey = true,
        )
        repository.setTorznabConfigs(listOf(torznabConfig))

        /** settings 验证更新后的运行时契约，不断言 fake 调用次数。 */
        val settings = repository.settings.first()
        assertEquals(setOf(ProviderId("nyaa"), ProviderId("yts")), settings.enabledProviderIds)
        assertFalse(settings.safeModeEnabled)
        assertEquals(SortCriteria.DATE, settings.sortCriteria)
        assertEquals(SortOrder.ASCENDING, settings.sortOrder)
        assertTrue(settings.providerSelectionInitialized)
        assertTrue(settings.multiSourceDisclosureAccepted)
        assertEquals(TorrentSearchDnsMode.CLOUDFLARE, settings.dnsMode)
        assertEquals(listOf(torznabConfig), settings.torznabConfigs)
    }

    /** 首次推荐来源初始化只能执行一次，用户后续明确关闭全部来源时不得被默认值覆盖。 */
    @Test
    fun initializeProviderSelectionDoesNotOverwriteExplicitEmptySelection() = runTest {
        /** repository 从未初始化的空设置开始。 */
        val repository = TorrentSearchSettingsRepository(FakePreferencesDataStore(preferencesOf()))
        /** recommendedIds 是首次披露后准备启用的安全默认来源。 */
        val recommendedIds = setOf(ProviderId("nyaa"), ProviderId("yts"))

        assertTrue(repository.initializeProviderSelection(recommendedIds))
        repository.setEnabledProviderIds(emptySet())
        assertFalse(repository.initializeProviderSelection(setOf(ProviderId("btdigg"))))

        /** settings 必须保留用户明确提交的空集合和已初始化标记。 */
        val settings = repository.settings.first()
        assertTrue(settings.providerSelectionInitialized)
        assertTrue(settings.enabledProviderIds.isEmpty())
    }

    /** Torznab upsert/删除必须与启用集合在同一设置事务内保持一致。 */
    @Test
    fun torznabMutationKeepsConfigAndEnabledIdsConsistent() = runTest {
        /** repository 从已初始化空选择开始，避免默认来源语义干扰。 */
        val repository = TorrentSearchSettingsRepository(FakePreferencesDataStore(preferencesOf()))
        repository.setEnabledProviderIds(emptySet())
        /** config 是准备保存并自动启用的动态来源。 */
        val config = TorznabConfig(
            id = TorznabConfigId("torznab_fixture"),
            name = "Fixture",
            feedUrl = "http://indexer.example:9117/api",
            category = Category.BOOKS,
            safetyLevel = ProviderSafetyLevel.SAFE,
            hasApiKey = false,
        )

        repository.upsertTorznabConfig(config)
        /** savedSettings 必须同时包含配置和启用身份。 */
        val savedSettings = repository.settings.first()
        assertEquals(listOf(config), savedSettings.torznabConfigs)
        assertEquals(setOf(ProviderId("torznab_fixture")), savedSettings.enabledProviderIds)

        repository.removeTorznabConfig(config.id)
        /** removedSettings 不得留下动态配置或悬空启用 ID。 */
        val removedSettings = repository.settings.first()
        assertTrue(removedSettings.torznabConfigs.isEmpty())
        assertTrue(removedSettings.enabledProviderIds.isEmpty())
    }
}

/** 设置测试用 dirty 标记器，不接触宿主 MMKV。 */
private class RecordingSettingsBackupDirtyMarker : TorrentSearchBackupDirtyMarker {
    /** count 是排序白名单持久化后的标记次数。 */
    var count: Int = 0

    /** 每次标记只增加确定性计数。 */
    override fun markDirty() {
        count += 1
    }
}

/** 内存 DataStore 保留真实 updateData 变换与 Flow 语义，只替代磁盘 IO。 */
private class FakePreferencesDataStore(
    /** 初始 Preferences 快照。 */
    initialPreferences: Preferences,
) : DataStore<Preferences> {
    /** state 保存当前不可变 Preferences。 */
    private val state = MutableStateFlow(initialPreferences)

    /** 对外暴露每次原子更新后的快照。 */
    override val data: Flow<Preferences> = state

    /** 在当前快照上执行真实 Repository 提供的原子变换。 */
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        /** updated 是变换完成后的不可变设置快照。 */
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
