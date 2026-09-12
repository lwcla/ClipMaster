package com.cla.clip.feature.torrent.search.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderFailureReason
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.ProviderSelectionPolicy
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.settings.TorrentSearchSettings
import com.cla.clip.feature.torrent.search.settings.TorrentSearchSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Browse 页面阶段，区分未加载、执行中和已完成快照。 */
internal enum class BrowsePhase {
    /** 尚未进入可见态或离页时取消了未完成请求。 */
    IDLE,
    /** Latest/Top 来源正在并发加载。 */
    LOADING,
    /** 当前模式和分类已经完成，无结果时同样属于完成。 */
    COMPLETED,
}

/** Browse 页面不可变 UI 状态。 */
internal data class TorrentBrowseUiState(
    /** 当前 Latest/Top 模式。 */
    val mode: BrowseMode = BrowseMode.LATEST,
    /** 当前分类。 */
    val category: Category = Category.ALL,
    /** 已去重且按模式默认规则排序的结果。 */
    val results: List<Torrent> = emptyList(),
    /** 单来源稳定失败分类。 */
    val failures: Map<ProviderId, ProviderFailureReason> = emptyMap(),
    /** 当前加载阶段。 */
    val phase: BrowsePhase = BrowsePhase.IDLE,
    /** 当前目的地是否至少 STARTED。 */
    val isScreenActive: Boolean = false,
    /** 设置首快照是否到达。 */
    val settingsReady: Boolean = false,
    /** 安全模式用于隐藏成人分类并与 Provider 选择保持一致。 */
    val safeModeEnabled: Boolean = true,
) {
    /** 是否正在执行可取消浏览任务。 */
    val isLoading: Boolean get() = phase == BrowsePhase.LOADING
}

/** Browse 状态编排器；构造与 init 仅收集轻量设置，不执行网络。 */
@HiltViewModel
internal class TorrentBrowseViewModel @Inject constructor(
    /** 固定注册表顺序的全部来源。 */
    private val providers: List<@JvmSuppressWildcards SearchProvider>,
    /** Latest/Top 并发、超时和失败隔离协作者。 */
    private val coordinator: BrowseCoordinator,
    /** 本机 Provider 与安全模式设置仓库。 */
    private val settingsRepository: TorrentSearchSettingsRepository,
) : ViewModel() {
    /** 页面唯一可变状态。 */
    private val mutableUiState = MutableStateFlow(TorrentBrowseUiState())
    /** Compose 生命周期感知收集的只读状态。 */
    val uiState: StateFlow<TorrentBrowseUiState> = mutableUiState.asStateFlow()
    /** 最近设置快照只用于每次加载前同步筛选来源。 */
    private var currentSettings = TorrentSearchSettings()
    /** 当前浏览 Job，切换模式/分类或离页会取消。 */
    private var loadJob: Job? = null
    /** 请求代次防止旧 Job 的 finally 覆盖新状态。 */
    private var loadGeneration = 0L

    /** 仅订阅轻量本机设置；不可见时不启动 Provider。 */
    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { /** settings 是当前本机安全设置快照。 */ settings ->
                /** firstSettingsSnapshot 显式标记设置由未就绪变为就绪，不能用默认值相等代替。 */
                val firstSettingsSnapshot = !mutableUiState.value.settingsReady
                /** settingsChanged 只表示首快照之后来源或安全边界发生实际变化。 */
                val settingsChanged = !firstSettingsSnapshot && settings != currentSettings
                currentSettings = settings
                mutableUiState.update { /** state 是合并设置就绪标志前的页面状态。 */ state ->
                    state.copy(
                        settingsReady = true,
                        safeModeEnabled = settings.safeModeEnabled,
                        category = if (settings.safeModeEnabled && state.category.isNsfw) Category.ALL else state.category,
                    )
                }
                /** shouldLoadAfterSettings 同时覆盖先激活后首快照与可见期间设置变化。 */
                val shouldLoadAfterSettings = mutableUiState.value.isScreenActive &&
                    ((firstSettingsSnapshot && mutableUiState.value.phase == BrowsePhase.IDLE) || settingsChanged)
                if (shouldLoadAfterSettings) reload()
            }
        }
    }

    /** 同步当前目的地可见性；离页立即取消网络，重进只重试未完成状态。 */
    fun setScreenActive(
        /** true 表示当前 NavBackStackEntry 至少 STARTED。 */
        active: Boolean,
    ) {
        mutableUiState.update { /** state 是切换生命周期前的页面状态。 */ state -> state.copy(isScreenActive = active) }
        if (!active) {
            cancelLoad(resetLoadingPhase = true)
        } else if (mutableUiState.value.settingsReady && mutableUiState.value.phase == BrowsePhase.IDLE) {
            reload()
        }
    }

    /** 切换 Latest/Top；变化时取消旧模式并加载新快照。 */
    fun updateMode(
        /** 用户选择的新浏览模式。 */
        mode: BrowseMode,
    ) {
        if (mode == mutableUiState.value.mode) return
        mutableUiState.update { /** state 是切换模式前的页面状态。 */ state ->
            state.copy(mode = mode, results = emptyList(), failures = emptyMap(), phase = BrowsePhase.IDLE)
        }
        if (mutableUiState.value.isScreenActive && mutableUiState.value.settingsReady) reload()
    }

    /** 切换分类；变化时取消旧分类并加载新快照。 */
    fun updateCategory(
        /** 用户选择的新分类。 */
        category: Category,
    ) {
        if (category == mutableUiState.value.category) return
        if (currentSettings.safeModeEnabled && category.isNsfw) return
        mutableUiState.update { /** state 是切换分类前的页面状态。 */ state ->
            state.copy(category = category, results = emptyList(), failures = emptyMap(), phase = BrowsePhase.IDLE)
        }
        if (mutableUiState.value.isScreenActive && mutableUiState.value.settingsReady) reload()
    }

    /** 以当前模式、分类和最新设置重新加载。 */
    fun reload() {
        cancelLoad(resetLoadingPhase = false)
        /** state 是本次加载的模式和分类快照。 */
        val state = mutableUiState.value
        if (!state.isScreenActive || !state.settingsReady) return
        /** activeProviders 是用户选择与安全模式共同允许的来源。 */
        val activeProviders = ProviderSelectionPolicy.select(providers, currentSettings)
        /** generation 固定本次请求身份。 */
        val generation = loadGeneration
        mutableUiState.update { /** current 是进入加载阶段前的页面状态。 */ current ->
            current.copy(phase = BrowsePhase.LOADING, results = emptyList(), failures = emptyMap())
        }
        loadJob = viewModelScope.launch {
            try {
                /** result 是协调器完成后的有序聚合快照。 */
                val result = coordinator.load(state.mode, state.category, activeProviders)
                if (generation == loadGeneration) {
                    mutableUiState.update { /** current 是写入完成结果前的页面状态。 */ current ->
                        current.copy(results = result.torrents, failures = result.failures, phase = BrowsePhase.COMPLETED)
                    }
                }
            /** cancellation 是切换条件或离开目的地的预期边界。 */
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                if (generation == loadGeneration) loadJob = null
            }
        }
    }

    /** 取消当前浏览并递增代次，避免旧任务回写。 */
    private fun cancelLoad(
        /** true 时把执行中状态恢复为 IDLE，供重进自动重试。 */
        resetLoadingPhase: Boolean,
    ) {
        loadGeneration += 1
        loadJob?.cancel(CancellationException("torrent_browse_load_cancelled"))
        loadJob = null
        if (resetLoadingPhase) {
            mutableUiState.update { /** state 是取消当前加载前的页面状态。 */ state ->
                state.copy(phase = if (state.phase == BrowsePhase.LOADING) BrowsePhase.IDLE else state.phase)
            }
        }
    }
}
