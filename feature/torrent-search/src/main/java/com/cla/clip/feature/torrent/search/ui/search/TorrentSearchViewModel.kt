package com.cla.clip.feature.torrent.search.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.feature.torrent.search.input.TorrentSearchInputNormalizer
import com.cla.clip.feature.torrent.search.input.TorrentSearchLaunchRequest
import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderFailureReason
import com.cla.clip.feature.torrent.search.model.contract.SortCriteria
import com.cla.clip.feature.torrent.search.model.contract.SortOrder
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.ProviderSelectionPolicy
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.search.SearchCoordinator
import com.cla.clip.feature.torrent.search.search.SearchEvent
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

/** 搜索页可本地化错误状态；不携带查询、URL 或异常正文。 */
internal enum class TorrentSearchUiError {
    /** 设置首个安全快照尚未加载完成。 */
    SETTINGS_LOADING,
    /** 当前页面不可见，拒绝启动网络。 */
    SCREEN_NOT_ACTIVE,
    /** 查询为空。 */
    EMPTY_QUERY,
    /** 查询超过 512 字符且已被截断，仅允许用户确认编辑后重新提交。 */
    QUERY_TOO_LONG,
    /** 用户明确关闭了全部来源。 */
    NO_ENABLED_PROVIDERS,
    /** 本机设置写入失败；不携带底层异常正文。 */
    SETTINGS_WRITE_FAILED,
}

/** 搜索主流程阶段，区分初始、运行、停止及完成空结果。 */
internal enum class TorrentSearchPhase {
    /** 尚未提交过搜索。 */
    IDLE,
    /** Provider 正在渐进执行。 */
    SEARCHING,
    /** 用户或生命周期主动停止。 */
    STOPPED,
    /** 协调器已完成，无论是否有结果。 */
    COMPLETED,
}

/** 搜索页不可变状态；Compose 只渲染该快照并回传事件。 */
internal data class TorrentSearchUiState(
    /** 当前输入框文本，不写日志。 */
    val query: String = "",
    /** 当前分类筛选。 */
    val category: Category = Category.ALL,
    /** 当前排序字段。 */
    val sortCriteria: SortCriteria = SortCriteria.SEEDERS,
    /** 当前排序方向。 */
    val sortOrder: SortOrder = SortOrder.DESCENDING,
    /** 安全模式当前值。 */
    val safeModeEnabled: Boolean = true,
    /** 首个 DataStore 设置快照是否已经到达。 */
    val settingsReady: Boolean = false,
    /** 当前有效来源数量，仅供低敏 UI 提示。 */
    val enabledProviderCount: Int = 0,
    /** 已按 infoHash 去重和当前规则排序的渐进结果。 */
    val results: List<Torrent> = emptyList(),
    /** 当前仍在执行的来源稳定 ID。 */
    val runningProviderIds: Set<ProviderId> = emptySet(),
    /** 当前搜索中各来源的稳定失败分类。 */
    val providerFailures: Map<ProviderId, ProviderFailureReason> = emptyMap(),
    /** 当前搜索阶段，用于区分初始空态与完成空结果。 */
    val phase: TorrentSearchPhase = TorrentSearchPhase.IDLE,
    /** 页面是否处于 STARTED 可见状态。 */
    val isScreenActive: Boolean = false,
    /** 是否展示首次多源查询隐私披露。 */
    val showMultiSourceDisclosure: Boolean = false,
    /** 披露弹窗提交时冻结的实际来源数量，后续设置变化不得改写该语义。 */
    val pendingDisclosureProviderCount: Int = 0,
    /** 是否展示关闭安全模式二次确认。 */
    val showDisableSafeModeConfirmation: Boolean = false,
    /** 最近一次本地输入/状态错误。 */
    val error: TorrentSearchUiError? = null,
    /** 系统预填是否因超限被截断，用于提示用户重新确认文本。 */
    val launchQueryWasTruncated: Boolean = false,
) {
    /** 是否存在用户主动启动且尚未结束的搜索。 */
    val isSearching: Boolean get() = phase == TorrentSearchPhase.SEARCHING
}

/** 已通过输入、来源和隐私前置校验的待执行搜索快照。 */
private data class PendingSearch(
    /** 已规范化且不为空的查询。 */
    val query: String,
    /** 提交时的分类快照。 */
    val category: Category,
    /** 披露时捕获的来源稳定 ID，启动前还会按最新设置重新收紧。 */
    val providerIds: Set<ProviderId>,
    /** 首次确认后需要持久化的注册表推荐来源。 */
    val recommendedProviderIds: Set<ProviderId>,
)

/** Hilt 搜索状态编排器；构造与 Intent 预填都不会启动网络。 */
@HiltViewModel
internal class TorrentSearchViewModel @Inject constructor(
    /** 固定注册表顺序的全部内置 Provider。 */
    private val providers: List<SearchProvider>,
    /** 渐进、多源隔离且可取消的搜索协调器。 */
    private val coordinator: SearchCoordinator,
    /** 本机 Provider、安全模式、排序和披露状态仓库。 */
    private val settingsRepository: TorrentSearchSettingsRepository,
) : ViewModel() {
    /** 页面唯一可变状态出口。 */
    private val mutableUiState = MutableStateFlow(TorrentSearchUiState())
    /** Compose 生命周期感知收集的只读状态。 */
    val uiState: StateFlow<TorrentSearchUiState> = mutableUiState.asStateFlow()
    /** 最近设置快照只用于提交时做同步安全决策。 */
    private var currentSettings = TorrentSearchSettings()
    /** 当前搜索 Job；新搜索、停止或页面离开都会取消它。 */
    private var searchJob: Job? = null
    /** 披露/默认设置持久化前置 Job；离开目的地后不得恢复旧请求。 */
    private var preflightJob: Job? = null
    /** 每次开始或取消都递增，防止旧 Job 的 finally 覆盖新搜索状态。 */
    private var searchGeneration = 0L
    /** 等待首次多源披露确认的搜索，不含任何外部响应。 */
    private var pendingSearch: PendingSearch? = null

    /** 只收集轻量 DataStore；网络始终由显式 submitSearch 启动。 */
    init {
        viewModelScope.launch {
            /** settings 是当前本机安全设置快照。 */
            settingsRepository.settings.collect { settings ->
                /** safeModeWasEnabled 用于识别外部从关闭到开启的 fail-closed 变化。 */
                val safeModeWasEnabled = currentSettings.safeModeEnabled
                currentSettings = settings
                if (settings.safeModeEnabled && !safeModeWasEnabled) {
                    pendingSearch = null
                    stopSearch()
                }
                mutableUiState.update { /** state 是等待合并设置的旧页面快照。 */ state ->
                    state.copy(
                        category = if (settings.safeModeEnabled && state.category.isNsfw) Category.ALL else state.category,
                        sortCriteria = settings.sortCriteria,
                        sortOrder = settings.sortOrder,
                        safeModeEnabled = settings.safeModeEnabled,
                        settingsReady = true,
                        enabledProviderCount = selectProviders(settings).size,
                        showMultiSourceDisclosure = if (settings.safeModeEnabled && !safeModeWasEnabled) false else state.showMultiSourceDisclosure,
                        pendingDisclosureProviderCount = if (settings.safeModeEnabled && !safeModeWasEnabled) 0 else state.pendingDisclosureProviderCount,
                    )
                }
            }
        }
    }

    /** 消费安全启动快照，只预填文本并清除旧输入错误，绝不自动搜索。 */
    fun acceptLaunchRequest(
        /** Activity 已通过统一 resolver 处理的不可信 Intent 快照。 */
        request: TorrentSearchLaunchRequest,
    ) {
        pendingSearch = null
        stopSearch()
        mutableUiState.update { /** state 是当前页面快照。 */ state ->
            state.copy(
                query = request.query,
                results = emptyList(),
                providerFailures = emptyMap(),
                phase = TorrentSearchPhase.IDLE,
                showMultiSourceDisclosure = false,
                pendingDisclosureProviderCount = 0,
                launchQueryWasTruncated = request.wasTruncated,
                error = null,
            )
        }
    }

    /** 更新输入框文本；用户重新编辑后清除系统截断标记。 */
    fun updateQuery(
        /** 尚未提交的用户输入。 */
        query: String,
    ) {
        mutableUiState.update { /** state 是当前页面快照。 */ state ->
            state.copy(query = query, launchQueryWasTruncated = false, error = null)
        }
    }

    /** 更新搜索分类；运行中的请求保持提交时快照，下一次提交使用新分类。 */
    fun updateCategory(
        /** 用户选择的稳定分类。 */
        category: Category,
    ) {
        if (currentSettings.safeModeEnabled && category.isNsfw) return
        mutableUiState.update { /** state 是当前页面快照。 */ state -> state.copy(category = category, error = null) }
    }

    /** 持久化排序并立即用于当前结果；失败由仓库固定日志覆盖。 */
    fun updateSort(
        /** 新排序标准。 */
        criteria: SortCriteria,
        /** 新排序方向。 */
        order: SortOrder,
    ) {
        mutableUiState.update { /** state 是当前页面快照。 */ state ->
            state.copy(
                sortCriteria = criteria,
                sortOrder = order,
                results = TorrentResultSorter.sort(state.results, criteria, order),
            )
        }
        launchSettingsWrite { settingsRepository.setSort(criteria, order) }
    }

    /** 同步页面 STARTED 状态；离开可见态立即取消网络。 */
    fun setScreenActive(
        /** true 表示页面可见且允许用户显式发起搜索。 */
        active: Boolean,
    ) {
        mutableUiState.update { /** state 是当前页面快照。 */ state -> state.copy(isScreenActive = active) }
        if (!active) {
            pendingSearch = null
            mutableUiState.update { /** state 是正在退出可见态的页面快照。 */ state ->
                state.copy(showMultiSourceDisclosure = false, pendingDisclosureProviderCount = 0)
            }
            stopSearch()
        }
    }

    /** 校验当前输入、来源和披露状态，并只在页面可见时启动搜索。 */
    fun submitSearch() {
        /** state 是提交瞬间的一致 UI 快照。 */
        val state = mutableUiState.value
        if (!state.settingsReady) {
            setError(TorrentSearchUiError.SETTINGS_LOADING)
            return
        }
        if (!state.isScreenActive) {
            setError(TorrentSearchUiError.SCREEN_NOT_ACTIVE)
            return
        }
        /** normalizedInput 复用全功能唯一 512 字符和控制字符规则。 */
        val normalizedInput = TorrentSearchInputNormalizer.normalize(state.query)
        if (normalizedInput.query.isEmpty()) {
            setError(TorrentSearchUiError.EMPTY_QUERY)
            return
        }
        if (!normalizedInput.canAutoSearch) {
            setError(TorrentSearchUiError.QUERY_TOO_LONG)
            return
        }
        /** selectedProviders 是安全模式和用户选择共同允许的实际网络来源。 */
        val selectedProviders = selectProviders(currentSettings)
        if (selectedProviders.isEmpty()) {
            setError(TorrentSearchUiError.NO_ENABLED_PROVIDERS)
            return
        }
        /** recommendedIds 仅用于首次确认后写入注册表默认集合。 */
        val recommendedIds = defaultProviderIds()
        /** request 固定本次搜索所有输入，弹窗期间后续编辑不会悄悄改变待授权内容。 */
        val request = PendingSearch(
            query = normalizedInput.query,
            category = state.category,
            providerIds = selectedProviders.mapTo(linkedSetOf()) { /** provider 是披露时捕获的来源。 */ provider -> provider.descriptor.id },
            recommendedProviderIds = recommendedIds,
        )
        if (selectedProviders.size > 1 && !currentSettings.multiSourceDisclosureAccepted) {
            pendingSearch = request
            mutableUiState.update { /** oldState 是进入首次披露前的页面快照。 */ oldState ->
                oldState.copy(
                    showMultiSourceDisclosure = true,
                    pendingDisclosureProviderCount = selectedProviders.size,
                    error = null,
                )
            }
            return
        }
        persistDefaultsAndStart(request, acceptDisclosure = false)
    }

    /** 确认首次多源披露，先本机持久化授权与推荐来源，再执行捕获的搜索。 */
    fun confirmMultiSourceDisclosure() {
        /** request 是弹窗展示时固定的待授权搜索快照。 */
        val request = pendingSearch ?: return
        pendingSearch = null
        mutableUiState.update { /** state 是关闭披露弹窗前的页面快照。 */ state ->
            state.copy(showMultiSourceDisclosure = false, pendingDisclosureProviderCount = 0)
        }
        persistDefaultsAndStart(request, acceptDisclosure = true)
    }

    /** 取消首次多源披露；不写设置也不启动任何 Provider。 */
    fun dismissMultiSourceDisclosure() {
        pendingSearch = null
        mutableUiState.update { /** state 是关闭披露弹窗前的页面快照。 */ state ->
            state.copy(showMultiSourceDisclosure = false, pendingDisclosureProviderCount = 0)
        }
    }

    /** 请求安全模式目标值；关闭必须先进入二次确认，开启可直接持久化。 */
    fun requestSafeModeEnabled(
        /** true 立即恢复安全过滤，false 只请求展示确认。 */
        enabled: Boolean,
    ) {
        if (enabled) {
            pendingSearch = null
            currentSettings = currentSettings.copy(safeModeEnabled = true)
            stopSearch()
            mutableUiState.update { /** state 是同步收紧安全边界前的页面快照。 */ state ->
                state.copy(
                    category = if (state.category.isNsfw) Category.ALL else state.category,
                    safeModeEnabled = true,
                    showMultiSourceDisclosure = false,
                    pendingDisclosureProviderCount = 0,
                )
            }
            launchSettingsWrite { settingsRepository.setSafeModeEnabled(true) }
        } else if (currentSettings.safeModeEnabled) {
            mutableUiState.update { /** state 是准备展示安全警告的页面快照。 */ state ->
                state.copy(showDisableSafeModeConfirmation = true)
            }
        }
    }

    /** 用户确认风险后关闭安全模式；确认状态本机保存且不进入自定义备份。 */
    fun confirmDisableSafeMode() {
        mutableUiState.update { /** state 是关闭确认弹窗前的页面快照。 */ state ->
            state.copy(showDisableSafeModeConfirmation = false)
        }
        launchSettingsWrite {
            settingsRepository.setSafeModeEnabled(false)
            currentSettings = currentSettings.copy(safeModeEnabled = false)
            mutableUiState.update { /** state 是安全设置成功关闭前的页面快照。 */ state ->
                state.copy(safeModeEnabled = false)
            }
        }
    }

    /** 取消关闭安全模式，保持当前安全设置不变。 */
    fun dismissDisableSafeModeConfirmation() {
        mutableUiState.update { /** state 是关闭确认弹窗前的页面快照。 */ state ->
            state.copy(showDisableSafeModeConfirmation = false)
        }
    }

    /** 用户停止或页面离开时取消当前搜索，并立即收敛运行态。 */
    fun stopSearch() {
        searchGeneration += 1
        preflightJob?.cancel(CancellationException("torrent_search_preflight_stopped"))
        preflightJob = null
        searchJob?.cancel(CancellationException("torrent_search_stopped"))
        searchJob = null
        mutableUiState.update { /** state 是待停止的搜索快照。 */ state ->
            state.copy(
                phase = if (state.phase == TorrentSearchPhase.SEARCHING) TorrentSearchPhase.STOPPED else state.phase,
                runningProviderIds = emptySet(),
            )
        }
    }

    /** 首次使用时写推荐来源，并按需保存隐私确认后启动搜索。 */
    private fun persistDefaultsAndStart(
        /** 已完成同步安全校验的搜索请求。 */
        request: PendingSearch,
        /** true 表示同时持久化首次多源披露确认。 */
        acceptDisclosure: Boolean,
    ) {
        stopSearch()
        /** generation 固定本次持久化与启动资格，离页或新请求会使其失效。 */
        val generation = searchGeneration
        preflightJob = viewModelScope.launch {
            try {
                if (!currentSettings.providerSelectionInitialized) {
                    settingsRepository.initializeProviderSelection(request.recommendedProviderIds)
                }
                if (acceptDisclosure) {
                    settingsRepository.setMultiSourceDisclosureAccepted(true)
                }
                if (searchGeneration != generation || !mutableUiState.value.isScreenActive) return@launch
                /** latestProviders 只保留披露时已捕获且最新设置仍允许的来源。 */
                val latestProviders = selectProviders(currentSettings).filter { /** provider 是启动前重新校验的来源。 */ provider ->
                    provider.descriptor.id in request.providerIds
                }
                if (latestProviders.isEmpty()) {
                    setError(TorrentSearchUiError.NO_ENABLED_PROVIDERS)
                    return@launch
                }
                startSearch(request, latestProviders, generation)
            /** cancellation 保持页面离开和新请求的结构化取消语义。 */
            } catch (cancellation: CancellationException) {
                throw cancellation
            /** exception 只映射固定低敏 UI 错误，绝不回显介质异常。 */
            } catch (exception: Exception) {
                setError(TorrentSearchUiError.SETTINGS_WRITE_FAILED)
            } finally {
                if (searchGeneration == generation) preflightJob = null
            }
        }
    }

    /** 取消旧任务、清空旧结果并收集当前协调器事件。 */
    private fun startSearch(
        /** 已获授权且固定输入的搜索快照。 */
        request: PendingSearch,
        /** 已按最新设置、披露快照和安全模式重新收紧的来源。 */
        activeProviders: List<SearchProvider>,
        /** 本次请求 generation，旧任务不得覆盖新状态。 */
        generation: Long,
    ) {
        preflightJob = null
        /** activeCategory 在安全模式重新开启后强制回退全部分类。 */
        val activeCategory = if (currentSettings.safeModeEnabled && request.category.isNsfw) Category.ALL else request.category
        mutableUiState.update { /** state 是开始新搜索前的页面快照。 */ state ->
            state.copy(
                query = request.query,
                category = activeCategory,
                results = emptyList(),
                runningProviderIds = emptySet(),
                providerFailures = emptyMap(),
                phase = TorrentSearchPhase.SEARCHING,
                error = null,
            )
        }
        searchJob = viewModelScope.launch {
            try {
                coordinator.search(
                    query = request.query,
                    category = activeCategory,
                    providers = activeProviders,
                    safeModeEnabled = currentSettings.safeModeEnabled,
                    maxResults = MAX_RESULTS,
                ).collect(::applySearchEvent)
            } finally {
                if (searchGeneration == generation) {
                    mutableUiState.update { /** state 是本次任务结束时的页面快照。 */ state ->
                        state.copy(
                            phase = if (state.phase == TorrentSearchPhase.SEARCHING) TorrentSearchPhase.COMPLETED else state.phase,
                            runningProviderIds = emptySet(),
                        )
                    }
                    searchJob = null
                }
            }
        }
    }

    /** 将单个渐进事件归并到不可变 UI state。 */
    private fun applySearchEvent(
        /** 协调器发出的稳定事件。 */
        event: SearchEvent,
    ) {
        mutableUiState.update { /** state 是归并事件前的页面快照。 */ state ->
            when (event) {
                is SearchEvent.Started -> state.copy(runningProviderIds = state.runningProviderIds + event.providerId)
                is SearchEvent.Results -> {
                    /** mergedByHash 按首次到达顺序覆盖重复身份的内存放大。 */
                    val mergedByHash = linkedMapOf<String, Torrent>()
                    state.results.forEach { /** torrent 是此前已经展示的稳定结果。 */ torrent ->
                        mergedByHash.putIfAbsent(torrent.infoHash, torrent)
                    }
                    event.torrents.forEach { /** torrent 是当前 Provider 新到达的候选结果。 */ torrent ->
                        mergedByHash.putIfAbsent(torrent.infoHash, torrent)
                    }
                    state.copy(results = TorrentResultSorter.sort(mergedByHash.values.toList(), state.sortCriteria, state.sortOrder))
                }
                is SearchEvent.Failed -> state.copy(
                    runningProviderIds = state.runningProviderIds - event.providerId,
                    providerFailures = state.providerFailures + (event.providerId to event.reason),
                )
                is SearchEvent.Skipped -> state.copy(
                    runningProviderIds = state.runningProviderIds - event.providerId,
                    providerFailures = state.providerFailures + (event.providerId to event.reason),
                )
                SearchEvent.Completed -> state.copy(phase = TorrentSearchPhase.COMPLETED, runningProviderIds = emptySet())
            }
        }
    }

    /** 返回用户当前选择或未初始化时的注册表推荐来源，并应用安全模式过滤。 */
    private fun selectProviders(
        /** 本次选择使用的设置快照。 */
        settings: TorrentSearchSettings,
    ): List<SearchProvider> {
        /** enabledIds 区分已初始化空集合与新安装推荐集合。 */
        return ProviderSelectionPolicy.select(providers, settings)
    }

    /** 从固定注册表提取首次推荐稳定 ID；不触发网络或持久化。 */
    private fun defaultProviderIds(): Set<ProviderId> {
        return ProviderSelectionPolicy.defaultProviderIds(providers)
    }

    /** 在 ViewModel 边界收敛设置介质异常，避免普通 launch 把异常交给主线程。 */
    private fun launchSettingsWrite(
        /** 单次本机设置写入；不得包含网络副作用。 */
        write: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                write()
            /** cancellation 保持 ViewModel 清理的结构化取消语义。 */
            } catch (cancellation: CancellationException) {
                throw cancellation
            /** exception 只映射固定 UI 分类，不回显异常 message。 */
            } catch (exception: Exception) {
                setError(TorrentSearchUiError.SETTINGS_WRITE_FAILED)
            }
        }
    }

    /** 写入稳定本地错误并关闭可能过期的披露弹窗。 */
    private fun setError(
        /** 不含外部文本的本地错误分类。 */
        error: TorrentSearchUiError,
    ) {
        pendingSearch = null
        mutableUiState.update { /** state 是写入本地错误前的页面快照。 */ state ->
            state.copy(error = error, showMultiSourceDisclosure = false, pendingDisclosureProviderCount = 0)
        }
    }

    /** 搜索页固定结果上限，与协调器的全局并发上限配合。 */
    companion object {
        /** 第一版页面最多展示 200 条，后续设置页可在不破坏协调器契约的前提下扩展。 */
        private const val MAX_RESULTS = 200
    }
}
