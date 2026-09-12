package com.cla.clip.feature.torrent.search.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.cla.clip.feature.torrent.search.data.repository.NoOpTorrentSearchDataLogger
import com.cla.clip.feature.torrent.search.data.repository.TorrentSearchDataLogger
import com.cla.clip.feature.torrent.search.model.contract.SortCriteria
import com.cla.clip.feature.torrent.search.model.contract.SortOrder
import com.cla.clip.feature.torrent.search.provider.ProviderId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** 页面消费的 TorrentSearch 设置快照，只包含稳定 ID/code 对应的强类型值。 */
internal data class TorrentSearchSettings(
    /** 当前启用的 Provider 稳定 ID；新安装为空，等待后续首次推荐来源确认。 */
    val enabledProviderIds: Set<ProviderId> = emptySet(),
    /** 安全模式默认开启；关闭确认由后续页面流程负责。 */
    val safeModeEnabled: Boolean = true,
    /** 默认按做种数排序。 */
    val sortCriteria: SortCriteria = SortCriteria.SEEDERS,
    /** 默认使用降序。 */
    val sortOrder: SortOrder = SortOrder.DESCENDING,
    /** 是否已经写入过用户可编辑的 Provider 选择，区分新安装与明确关闭全部来源。 */
    val providerSelectionInitialized: Boolean = false,
    /** 首次多源查询隐私披露是否已在本机确认。 */
    val multiSourceDisclosureAccepted: Boolean = false,
)

/** Preferences DataStore 设置仓库，集中处理稳定 code、损坏降级和原子写入。 */
internal class TorrentSearchSettingsRepository(
    /** 功能模块独立的 Preferences DataStore，避免污染宿主持久化协议。 */
    private val dataStore: DataStore<Preferences>,
    /** 只接收固定操作/状态 code 的低敏日志出口。 */
    private val logger: TorrentSearchDataLogger = NoOpTorrentSearchDataLogger,
) {
    /** 设置 Flow；磁盘读取失败降级为安全默认，其他编程错误继续传播。 */
    val settings: Flow<TorrentSearchSettings> = dataStore.data
        /** exception lambda 仅将可恢复 IO 读取失败降级为安全默认。 */
        .catch { exception ->
            if (exception is IOException) {
                logger.log("settings_read", "fallback")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map(::toSettings)

    /** 原子替换启用 Provider 集合，只写稳定 ID 字符串。 */
    suspend fun setEnabledProviderIds(
        /** 已经过 ProviderId 构造校验的完整启用集合。 */
        providerIds: Set<ProviderId>,
    ) {
        /** preferences lambda 在单次事务内替换完整 Provider 集合。 */
        update("settings_provider_ids") { preferences ->
            /** providerId lambda 只提取已验证的稳定 ID 字符串。 */
            preferences[ENABLED_PROVIDER_IDS] = providerIds.mapTo(linkedSetOf()) { providerId -> providerId.value }
            preferences[PROVIDER_SELECTION_INITIALIZED] = true
        }
    }

    /** 首次确认后原子写入推荐来源；已初始化时保持用户当前选择并返回 false。 */
    suspend fun initializeProviderSelection(
        /** 注册表中标记默认启用且已通过安全筛选的稳定 Provider ID。 */
        recommendedProviderIds: Set<ProviderId>,
    ): Boolean {
        /** initialized 标记本次事务是否真正写入默认来源。 */
        var initialized = false
        logger.log("settings_provider_defaults", "started")
        try {
            /** preferences lambda 在单事务内检查哨兵并写入默认集合。 */
            dataStore.edit { preferences ->
                if (preferences[PROVIDER_SELECTION_INITIALIZED] != true) {
                    /** providerId lambda 只提取注册表已验证的稳定字符串。 */
                    preferences[ENABLED_PROVIDER_IDS] = recommendedProviderIds.mapTo(linkedSetOf()) { providerId -> providerId.value }
                    preferences[PROVIDER_SELECTION_INITIALIZED] = true
                    initialized = true
                }
            }
            logger.log("settings_provider_defaults", "completed")
            return initialized
        /** cancellation 保持设置事务的结构化取消语义。 */
        } catch (cancellation: CancellationException) {
            logger.log("settings_provider_defaults", "cancelled")
            throw cancellation
        /** exception 不记录设置值或来源集合。 */
        } catch (exception: Exception) {
            logger.log("settings_provider_defaults", "failed")
            throw exception
        }
    }

    /** 原子写入安全模式开关；关闭前确认属于后续页面状态机。 */
    suspend fun setSafeModeEnabled(
        /** true 保持只运行安全来源，false 仅表示用户已在页面流程中完成确认。 */
        enabled: Boolean,
    ) {
        /** preferences lambda 只写入明确布尔值，不在 Repository 隐式关闭安全模式。 */
        update("settings_safe_mode") { preferences -> preferences[SAFE_MODE_ENABLED] = enabled }
    }

    /** 在同一事务中写入排序字段，避免页面短暂观察到不一致组合。 */
    suspend fun setSort(
        /** 排序标准强类型值，持久化使用稳定 code。 */
        criteria: SortCriteria,
        /** 排序方向强类型值，持久化使用稳定 code。 */
        order: SortOrder,
    ) {
        /** preferences lambda 原子写入一组排序设置。 */
        update("settings_sort") { preferences ->
            preferences[SORT_CRITERIA_CODE] = criteria.code
            preferences[SORT_ORDER_CODE] = order.code
        }
    }

    /** 保存首次多源隐私披露确认；该本机状态不进入任务 7 自定义备份。 */
    suspend fun setMultiSourceDisclosureAccepted(
        /** true 表示用户已明确知晓查询将发送到多个第三方来源。 */
        accepted: Boolean,
    ) {
        /** preferences lambda 只写入本机确认布尔值。 */
        update("settings_multi_source_disclosure") { preferences ->
            preferences[MULTI_SOURCE_DISCLOSURE_ACCEPTED] = accepted
        }
    }

    /** 包装单次 DataStore edit，并用固定 code 记录开始、完成、取消和失败。 */
    private suspend fun update(
        /** 固定操作 code，不包含设置值或 Provider 集合。 */
        operationCode: String,
        /** 仅修改目标 key 的同步事务体。 */
        transform: (androidx.datastore.preferences.core.MutablePreferences) -> Unit,
    ) {
        logger.log(operationCode, "started")
        try {
            dataStore.edit(transform)
            logger.log(operationCode, "completed")
        /** cancellation 分支记录取消并保持结构化并发语义。 */
        } catch (cancellation: CancellationException) {
            logger.log(operationCode, "cancelled")
            throw cancellation
        /** exception 分支只记录固定失败 code，不包含设置值。 */
        } catch (exception: Exception) {
            logger.log(operationCode, "failed")
            throw exception
        }
    }

    /** 将任意 Preferences 快照映射为保守、强类型运行时设置。 */
    private fun toSettings(preferences: Preferences): TorrentSearchSettings {
        /** enabledProviderIds 过滤非法大小写、空白和未来不符合稳定格式的 ID。 */
        val enabledProviderIds = preferences[ENABLED_PROVIDER_IDS]
            .orEmpty()
            /** rawId lambda 逐项容错，损坏集合成员不影响其余合法设置。 */
            .mapNotNull { rawId -> runCatching { ProviderId(rawId) }.getOrNull() }
            .toSet()
        return TorrentSearchSettings(
            enabledProviderIds = enabledProviderIds,
            safeModeEnabled = preferences[SAFE_MODE_ENABLED] ?: true,
            sortCriteria = SortCriteria.fromCode(preferences[SORT_CRITERIA_CODE]),
            sortOrder = SortOrder.fromCode(preferences[SORT_ORDER_CODE]),
            providerSelectionInitialized = preferences[PROVIDER_SELECTION_INITIALIZED] ?: false,
            multiSourceDisclosureAccepted = preferences[MULTI_SOURCE_DISCLOSURE_ACCEPTED] ?: false,
        )
    }

    /** DataStore key 集中在仓库内部，避免页面依赖持久化协议。 */
    companion object {
        /** Provider 启用集合 key，只存稳定 ID。 */
        private val ENABLED_PROVIDER_IDS = stringSetPreferencesKey("enabled_provider_ids")
        /** 安全模式 key；缺失时必须回退开启。 */
        private val SAFE_MODE_ENABLED = booleanPreferencesKey("safe_mode_enabled")
        /** 排序标准稳定 code key。 */
        private val SORT_CRITERIA_CODE = stringPreferencesKey("sort_criteria_code")
        /** 排序方向稳定 code key。 */
        private val SORT_ORDER_CODE = stringPreferencesKey("sort_order_code")
        /** Provider 选择初始化哨兵，防止默认值覆盖用户明确提交的空集合。 */
        private val PROVIDER_SELECTION_INITIALIZED = booleanPreferencesKey("provider_selection_initialized")
        /** 首次多源查询隐私披露的本机确认状态。 */
        private val MULTI_SOURCE_DISCLOSURE_ACCEPTED = booleanPreferencesKey("multi_source_disclosure_accepted")
    }
}
