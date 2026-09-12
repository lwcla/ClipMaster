package com.cla.clip.feature.torrent.search.torznab

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.settings.TorrentSearchSettingsRepository
import com.cla.clip.feature.torrent.search.torznab.security.TorznabApiKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Torznab 配置写入结果，只暴露固定分类和非敏感配置。 */
internal sealed interface TorznabConfigMutationResult {
    /** 配置与所需密钥操作均成功。 */
    data class Success(
        /** 已保存且不含 API Key 明文的配置。 */
        val config: TorznabConfig,
    ) : TorznabConfigMutationResult

    /** 输入未通过正式配置校验。 */
    data class Invalid(
        /** 页面可映射的固定失败原因。 */
        val reason: TorznabConfigFailureReason,
    ) : TorznabConfigMutationResult

    /** API Key 加密、介质写入或删除失败。 */
    data object SecretFailed : TorznabConfigMutationResult

    /** DataStore 配置事务失败。 */
    data object StorageFailed : TorznabConfigMutationResult
}

/** 在 IO 调度器协调非敏感 DataStore 配置与独立加密 API Key 仓库。 */
internal class TorznabConfigManager(
    /** 非敏感配置和启用 ID 的原子设置仓库。 */
    private val settingsRepository: TorrentSearchSettingsRepository,
    /** API Key 唯一安全仓库。 */
    private val apiKeyStore: TorznabApiKeyStore,
    /** 设置读取、Keystore 和介质操作统一离开 Main。 */
    private val ioDispatcher: CoroutineDispatcher,
) {
    /** 校验输入、按需更新密钥并原子 upsert 配置。 */
    suspend fun save(
        /** 页面生成或编辑项沿用的稳定配置身份。 */
        configId: TorznabConfigId,
        /** 用户输入展示名。 */
        rawName: String,
        /** 用户输入 HTTP/HTTPS feed URL。 */
        rawFeedUrl: String,
        /** 用户选择的缺省分类。 */
        category: Category,
        /** 用户选择的安全等级。 */
        safetyLevel: ProviderSafetyLevel,
        /** 仅本次调用内存活的新 API Key；空白表示保留既有值。 */
        apiKeyDraft: String?,
        /** true 表示明确删除既有 API Key。 */
        removeStoredApiKey: Boolean,
    ): TorznabConfigMutationResult = withContext(ioDispatcher) {
        /** existingConfig 用于在未输入新密钥时保留准确的存在元数据。 */
        val existingConfig = settingsRepository.settings.first().torznabConfigs
            /** config 是当前检查稳定身份的既有配置。 */
            .firstOrNull { config -> config.id == configId }
        /** normalizedDraft 只在当前调用内使用，空白不会覆盖既有密钥。 */
        val normalizedDraft = apiKeyDraft?.trim().orEmpty()
        /** targetHasApiKey 由明确删除、新密钥或既有元数据三种状态决定。 */
        val targetHasApiKey = when {
            removeStoredApiKey -> false
            normalizedDraft.isNotEmpty() -> true
            else -> existingConfig?.hasApiKey ?: false
        }
        /** validation 在任何密钥副作用前执行，避免坏 URL 导致无主密文。 */
        val validation = TorznabConfigValidator.validate(
            rawId = configId.value,
            rawName = rawName,
            rawFeedUrl = rawFeedUrl,
            category = category,
            safetyLevel = safetyLevel,
            hasApiKey = targetHasApiKey,
        )
        /** config 只从正式 Valid 结果取得。 */
        val config = when (validation) {
            is TorznabConfigValidationResult.Valid -> validation.config
            is TorznabConfigValidationResult.Invalid -> return@withContext TorznabConfigMutationResult.Invalid(validation.reason)
        }
        try {
            /** secretSucceeded 精确反映明确删除或替换是否完成。 */
            val secretSucceeded = when {
                removeStoredApiKey -> apiKeyStore.remove(configId)
                normalizedDraft.isNotEmpty() -> apiKeyStore.save(configId, normalizedDraft)
                else -> true
            }
            if (!secretSucceeded) return@withContext TorznabConfigMutationResult.SecretFailed
            settingsRepository.upsertTorznabConfig(config)
            TorznabConfigMutationResult.Success(config)
        /** cancellation 保持页面离开或 ViewModel 清理的结构化取消。 */
        } catch (cancellation: CancellationException) {
            throw cancellation
        /** failure 不读取异常 message，页面只看到固定存储失败。 */
        } catch (failure: Exception) {
            TorznabConfigMutationResult.StorageFailed
        }
    }

    /** 清理密钥后删除配置与启用身份；任一步失败返回 false。 */
    suspend fun delete(configId: TorznabConfigId): Boolean = withContext(ioDispatcher) {
        try {
            if (!apiKeyStore.remove(configId)) return@withContext false
            settingsRepository.removeTorznabConfig(configId)
            true
        /** cancellation 保持页面生命周期取消语义。 */
        } catch (cancellation: CancellationException) {
            throw cancellation
        /** failure 不回显密钥介质或 DataStore 异常。 */
        } catch (failure: Exception) {
            false
        }
    }
}
