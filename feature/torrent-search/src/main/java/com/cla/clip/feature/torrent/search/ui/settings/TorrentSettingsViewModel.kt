package com.cla.clip.feature.torrent.search.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.SortCriteria
import com.cla.clip.feature.torrent.search.model.contract.SortOrder
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.ProviderSelectionPolicy
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.settings.TorrentSearchDnsMode
import com.cla.clip.feature.torrent.search.settings.TorrentSearchSettings
import com.cla.clip.feature.torrent.search.settings.TorrentSearchSettingsRepository
import com.cla.clip.feature.torrent.search.torznab.TorznabConfig
import com.cla.clip.feature.torrent.search.torznab.TorznabConfigFailureReason
import com.cla.clip.feature.torrent.search.torznab.TorznabConfigId
import com.cla.clip.feature.torrent.search.torznab.TorznabConfigManager
import com.cla.clip.feature.torrent.search.torznab.TorznabConfigMutationResult
import com.cla.clip.feature.torrent.search.torznab.toProviderDescriptor
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 设置页固定错误分类，不携带 URL、名称、密钥或异常 message。 */
internal enum class TorrentSettingsError {
    /** 名称、ID 或 URL 未通过正式校验。 */
    INVALID_CONFIG,
    /** API Key 加密、写入或删除失败。 */
    SECRET_FAILED,
    /** DataStore 设置写入失败。 */
    WRITE_FAILED,
}

/** Provider 管理列表的稳定展示状态。 */
internal data class TorrentProviderUiState(
    /** 内置或动态来源的完整非敏感描述符。 */
    val descriptor: ProviderDescriptor,
    /** 用户是否在本机启用该来源。 */
    val enabled: Boolean,
    /** 当前安全模式下是否会实际参与请求。 */
    val availableInCurrentSafeMode: Boolean,
    /** 仅动态来源携带编辑所需的非敏感配置。 */
    val torznabConfig: TorznabConfig? = null,
)

/** 设置与 Provider 页面共享的不可变状态。 */
internal data class TorrentSettingsUiState(
    /** 首个 DataStore 快照是否已到达。 */
    val settingsReady: Boolean = false,
    /** 当前安全模式。 */
    val safeModeEnabled: Boolean = true,
    /** 默认排序标准。 */
    val sortCriteria: SortCriteria = SortCriteria.SEEDERS,
    /** 默认排序方向。 */
    val sortOrder: SortOrder = SortOrder.DESCENDING,
    /** 当前 DNS 模式。 */
    val dnsMode: TorrentSearchDnsMode = TorrentSearchDnsMode.SYSTEM,
    /** 多来源披露是否已在本机确认。 */
    val multiSourceDisclosureAccepted: Boolean = false,
    /** 内置优先、动态随后排列的完整来源列表。 */
    val providers: List<TorrentProviderUiState> = emptyList(),
    /** 用户本机启用的来源数量，不等同于安全模式下有效数量。 */
    val enabledProviderCount: Int = 0,
    /** 最近一次固定错误。 */
    val error: TorrentSettingsError? = null,
)

/** 设置页状态编排器；构造只收集轻量 DataStore，不启动网络或读取 API Key。 */
@HiltViewModel
internal class TorrentSettingsViewModel @Inject constructor(
    /** 固定注册表顺序的全部内置 Provider。 */
    private val builtinProviders: List<@JvmSuppressWildcards SearchProvider>,
    /** 本机强类型设置仓库。 */
    private val settingsRepository: TorrentSearchSettingsRepository,
    /** 配置与密钥跨仓库 IO 协调器。 */
    private val torznabConfigManager: TorznabConfigManager,
) : ViewModel() {
    /** 页面唯一可变状态。 */
    private val mutableUiState = MutableStateFlow(TorrentSettingsUiState())
    /** Compose 生命周期感知收集的只读状态。 */
    val uiState: StateFlow<TorrentSettingsUiState> = mutableUiState.asStateFlow()
    /** 最新设置只用于用户动作时构造完整集合。 */
    private var currentSettings = TorrentSearchSettings()

    /** 持续映射轻量设置快照；不触发网络或密钥读取。 */
    init {
        viewModelScope.launch {
            /** settings 是当前磁盘强类型快照。 */
            settingsRepository.settings.collect { settings ->
                currentSettings = settings
                mutableUiState.update { /** state 保留最近错误直到用户下一动作。 */ state ->
                    mapUiState(settings, state.error)
                }
            }
        }
    }

    /** 原子切换单个 Provider。 */
    fun setProviderEnabled(providerId: ProviderId, enabled: Boolean) {
        launchWrite { settingsRepository.setProviderEnabled(providerId, enabled) }
    }

    /** 启用全部已知内置与动态来源；安全模式仍决定实际可执行集合。 */
    fun enableAllProviders() {
        /** providerIds 来自当前稳定页面目录。 */
        val providerIds = mutableUiState.value.providers.mapTo(linkedSetOf()) { item -> item.descriptor.id }
        launchWrite { settingsRepository.setEnabledProviderIds(providerIds) }
    }

    /** 明确关闭全部来源。 */
    fun disableAllProviders() {
        launchWrite { settingsRepository.setEnabledProviderIds(emptySet()) }
    }

    /** 恢复内置推荐来源并关闭所有动态来源开关，但不删除 Torznab 配置。 */
    fun resetProvidersToDefault() {
        /** defaultIds 只来自固定内置注册表，动态来源从不默认启用。 */
        val defaultIds = ProviderSelectionPolicy.defaultProviderIds(builtinProviders)
        launchWrite { settingsRepository.setEnabledProviderIds(defaultIds) }
    }

    /** 写入安全模式；关闭确认由 Compose 页面先完成。 */
    fun setSafeModeEnabled(enabled: Boolean) {
        launchWrite { settingsRepository.setSafeModeEnabled(enabled) }
    }

    /** 原子写入默认排序。 */
    fun setSort(criteria: SortCriteria, order: SortOrder) {
        launchWrite { settingsRepository.setSort(criteria, order) }
    }

    /** 写入 DNS 模式，设置 Flow 会同步共享 OkHttp DNS 路由。 */
    fun setDnsMode(mode: TorrentSearchDnsMode) {
        launchWrite { settingsRepository.setDnsMode(mode) }
    }

    /** 重置本机多来源披露，下一次多来源搜索会再次确认。 */
    fun resetMultiSourceDisclosure() {
        launchWrite { settingsRepository.setMultiSourceDisclosureAccepted(false) }
    }

    /** 生成不含 URL/名称的随机动态 Provider 稳定身份。 */
    fun newTorznabConfigId(): TorznabConfigId {
        /** suffix 使用 UUID 十六进制且去除分隔符，满足 ProviderId 稳定格式。 */
        val suffix = UUID.randomUUID().toString().replace("-", "").take(24)
        return TorznabConfigId("torznab_$suffix")
    }

    /** 保存 Torznab 编辑输入，并把固定结果映射为页面状态。 */
    fun saveTorznabConfig(
        /** 新建或编辑配置的稳定身份。 */
        configId: TorznabConfigId,
        /** 用户输入的展示名。 */
        rawName: String,
        /** 用户输入的 HTTP/HTTPS feed URL。 */
        rawFeedUrl: String,
        /** 用户选择的索引器分类。 */
        category: Category,
        /** 用户明确标注的安全等级。 */
        safetyLevel: ProviderSafetyLevel,
        /** 空白表示保留既有密钥，非空表示覆盖。 */
        apiKeyDraft: String?,
        /** 是否显式删除安全仓库内的既有密钥。 */
        removeStoredApiKey: Boolean,
    ) {
        mutableUiState.update { /** state 是待清除旧错误的页面状态。 */ state -> state.copy(error = null) }
        viewModelScope.launch {
            /** result 不含密钥或底层异常。 */
            val result = torznabConfigManager.save(
                configId = configId,
                rawName = rawName,
                rawFeedUrl = rawFeedUrl,
                category = category,
                safetyLevel = safetyLevel,
                apiKeyDraft = apiKeyDraft,
                removeStoredApiKey = removeStoredApiKey,
            )
            /** error 只从固定结果分类派生。 */
            val error = when (result) {
                is TorznabConfigMutationResult.Success -> null
                is TorznabConfigMutationResult.Invalid -> when (result.reason) {
                    TorznabConfigFailureReason.INVALID_ID,
                    TorznabConfigFailureReason.INVALID_NAME,
                    TorznabConfigFailureReason.INVALID_URL,
                    -> TorrentSettingsError.INVALID_CONFIG
                }
                TorznabConfigMutationResult.SecretFailed -> TorrentSettingsError.SECRET_FAILED
                TorznabConfigMutationResult.StorageFailed -> TorrentSettingsError.WRITE_FAILED
            }
            mutableUiState.update { /** state 是待写入保存结果的页面状态。 */ state -> state.copy(error = error) }
        }
    }

    /** 删除动态配置及其密钥；内置 Provider 不经过该入口。 */
    fun deleteTorznabConfig(configId: TorznabConfigId) {
        viewModelScope.launch {
            /** deleted 只反映两类介质操作是否都完成。 */
            val deleted = torznabConfigManager.delete(configId)
            if (!deleted) mutableUiState.update { /** state 是待写入删除错误的页面状态。 */ state ->
                state.copy(error = TorrentSettingsError.WRITE_FAILED)
            }
        }
    }

    /** 清除已展示错误，便于用户修改后重试。 */
    fun clearError() {
        mutableUiState.update { /** state 是待清除错误的页面状态。 */ state -> state.copy(error = null) }
    }

    /** 把设置和静态注册表映射为页面状态。 */
    private fun mapUiState(settings: TorrentSearchSettings, error: TorrentSettingsError?): TorrentSettingsUiState {
        /** dynamicDescriptors 保留用户 Torznab 配置顺序。 */
        val dynamicDescriptors = settings.torznabConfigs.map { config -> config to config.toProviderDescriptor() }
        /** builtinItems 保留固定 manifest 注册顺序。 */
        val builtinItems = builtinProviders.map { provider ->
            provider.descriptor.toUiState(settings, torznabConfig = null)
        }
        /** dynamicItems 携带编辑所需的非敏感配置。 */
        val dynamicItems = dynamicDescriptors.map { (config, descriptor) ->
            descriptor.toUiState(settings, torznabConfig = config)
        }
        /** providerItems 是最终稳定列表目录。 */
        val providerItems = builtinItems + dynamicItems
        return TorrentSettingsUiState(
            settingsReady = true,
            safeModeEnabled = settings.safeModeEnabled,
            sortCriteria = settings.sortCriteria,
            sortOrder = settings.sortOrder,
            dnsMode = settings.dnsMode,
            multiSourceDisclosureAccepted = settings.multiSourceDisclosureAccepted,
            providers = providerItems,
            enabledProviderCount = providerItems.count { /** item 是待统计启用状态的来源。 */ item -> item.enabled },
            error = error,
        )
    }

    /** 在 ViewModel 边界收敛普通设置写入异常。 */
    private fun launchWrite(write: suspend () -> Unit) {
        mutableUiState.update { /** state 是普通写入前待清除错误的页面状态。 */ state -> state.copy(error = null) }
        viewModelScope.launch {
            try {
                write()
            /** cancellation 保持 ViewModel 清理语义。 */
            } catch (cancellation: CancellationException) {
                throw cancellation
            /** failure 不读取底层异常 message。 */
            } catch (failure: Exception) {
                mutableUiState.update { /** state 是待写入固定存储错误的页面状态。 */ state ->
                    state.copy(error = TorrentSettingsError.WRITE_FAILED)
                }
            }
        }
    }
}

/** 将描述符与当前设置合并为单个稳定列表项。 */
private fun ProviderDescriptor.toUiState(
    /** 当前完整本机设置。 */
    settings: TorrentSearchSettings,
    /** 动态来源的非敏感配置；内置来源为 null。 */
    torznabConfig: TorznabConfig?,
): TorrentProviderUiState {
    /** enabled 按用户完整选择判断，不把安全过滤误当作开关关闭。 */
    val enabled = id in settings.enabledProviderIds
    return TorrentProviderUiState(
        descriptor = this,
        enabled = enabled,
        availableInCurrentSafeMode = enabled && (!settings.safeModeEnabled || safetyLevel == ProviderSafetyLevel.SAFE),
        torznabConfig = torznabConfig,
    )
}
