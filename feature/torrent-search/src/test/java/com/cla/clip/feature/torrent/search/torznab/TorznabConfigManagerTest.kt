package com.cla.clip.feature.torrent.search.torznab

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.settings.TorrentSearchSettingsRepository
import com.cla.clip.feature.torrent.search.torznab.security.TorznabApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Torznab 配置与加密密钥跨仓库协调测试。 */
class TorznabConfigManagerTest {
    /** 保存新密钥必须写入加密仓库，并让非敏感配置元数据与启用集合一致。 */
    @Test
    fun savePersistsSecretMetadataAndEnablesPublicHttpConfig() = runTest {
        /** repository 使用内存 DataStore 保留真实事务语义。 */
        val repository = TorrentSearchSettingsRepository(ManagerFakeDataStore(preferencesOf()))
        /** keyStore 记录密钥调用但不执行真实加密。 */
        val keyStore = RecordingApiKeyStore()
        /** manager 使用 Unconfined 避免测试依赖真实 IO 线程。 */
        val manager = TorznabConfigManager(repository, keyStore, Dispatchers.Unconfined)
        /** configId 是页面预先生成的非敏感稳定身份。 */
        val configId = TorznabConfigId("torznab_fixture")

        /** result 是保存流程的固定成功/失败分类。 */
        val result = manager.save(
            configId = configId,
            rawName = "Fixture",
            rawFeedUrl = "http://indexer.example:9117/api",
            category = Category.BOOKS,
            safetyLevel = ProviderSafetyLevel.SAFE,
            apiKeyDraft = " secret ",
            removeStoredApiKey = false,
        )

        assertTrue(result is TorznabConfigMutationResult.Success)
        assertEquals(listOf(configId to "secret"), keyStore.saved)
        /** settings 验证页面不会把密钥明文写进普通配置。 */
        val settings = repository.settings.first()
        assertTrue(settings.torznabConfigs.single().hasApiKey)
        assertEquals("http://indexer.example:9117/api", settings.torznabConfigs.single().feedUrl)
        assertTrue(settings.enabledProviderIds.any { /** providerId 是待匹配动态配置身份的启用项。 */ providerId ->
            providerId.value == configId.value
        })
    }

    /** 删除配置必须先清理密钥，再原子移除配置与启用身份。 */
    @Test
    fun deleteRemovesSecretConfigAndEnabledId() = runTest {
        /** repository 先保存一个带密钥元数据的动态配置。 */
        val repository = TorrentSearchSettingsRepository(ManagerFakeDataStore(preferencesOf()))
        /** keyStore 默认允许安全删除。 */
        val keyStore = RecordingApiKeyStore()
        /** manager 使用同步测试 dispatcher。 */
        val manager = TorznabConfigManager(repository, keyStore, Dispatchers.Unconfined)
        /** config 是删除前的非敏感快照。 */
        val config = TorznabConfig(
            id = TorznabConfigId("torznab_fixture"),
            name = "Fixture",
            feedUrl = "https://indexer.example/api",
            category = Category.ALL,
            safetyLevel = ProviderSafetyLevel.SAFE,
            hasApiKey = true,
        )
        repository.upsertTorznabConfig(config)

        assertTrue(manager.delete(config.id))
        assertEquals(listOf(config.id), keyStore.removed)
        /** settings 是删除后的完整一致快照。 */
        val settings = repository.settings.first()
        assertTrue(settings.torznabConfigs.isEmpty())
        assertTrue(settings.enabledProviderIds.isEmpty())
    }
}

/** 配置协调测试使用的内存 Preferences DataStore。 */
private class ManagerFakeDataStore(
    /** 初始设置快照。 */
    initialPreferences: Preferences,
) : DataStore<Preferences> {
    /** state 保存每次原子事务后的快照。 */
    private val state = MutableStateFlow(initialPreferences)
    /** data 对外暴露连续设置流。 */
    override val data: Flow<Preferences> = state

    /** 执行 Repository 提供的真实 Preferences 变换。 */
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        /** updated 是事务生成的新不可变快照。 */
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

/** 记录 API Key 保存、读取与删除的无加密 fake。 */
private class RecordingApiKeyStore : TorznabApiKeyStore {
    /** saved 只在测试内保存输入，用于证明普通配置不携带该值。 */
    val saved = mutableListOf<Pair<TorznabConfigId, String?>>()
    /** removed 记录密钥清理身份。 */
    val removed = mutableListOf<TorznabConfigId>()

    /** 记录裁剪后的测试密钥。 */
    override fun save(configId: TorznabConfigId, apiKey: String?): Boolean {
        saved += configId to apiKey
        return true
    }

    /** 当前测试不需要恢复旧密钥。 */
    override fun read(configId: TorznabConfigId): String? = null

    /** 记录并确认删除。 */
    override fun remove(configId: TorznabConfigId): Boolean {
        removed += configId
        return true
    }
}
