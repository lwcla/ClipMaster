package com.cla.clip.feature.torrent.search.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.feature.torrent.search.data.repository.ViewedTorrentRepository
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Activity 内存详情选中态；路由只携带稳定 Provider ID。 */
@HiltViewModel
internal class TorrentDetailSelectionViewModel @Inject constructor(
    /** 已查看写入复用现有 Room Repository。 */
    private val viewedRepository: ViewedTorrentRepository,
) : ViewModel() {
    /** 最近点击的完整候选只存在当前 Activity ViewModel 内存。 */
    private val mutableSelected = MutableStateFlow<Torrent?>(null)
    /** 导航根读取的只读候选。 */
    val selected: StateFlow<Torrent?> = mutableSelected.asStateFlow()

    /** 保存点击候选并异步刷新已查看时间；写入失败不阻断原生详情导航。 */
    fun select(
        /** 搜索或浏览结果的完整领域对象。 */
        torrent: Torrent,
    ) {
        mutableSelected.value = torrent
        viewModelScope.launch {
            try {
                viewedRepository.markViewed(torrent.infoHash)
            /** 页面/Activity 清理时保持取消语义。 */
            } catch (cancellation: CancellationException) {
                throw cancellation
            /** Repository 已记录固定低敏失败，导航不回显介质异常。 */
            } catch (failure: Throwable) {
                Unit
            }
        }
    }
}

/** 原生详情页面阶段。 */
internal enum class TorrentDetailsPhase {
    /** 尚未具备可见加载条件或离页取消后等待恢复。 */
    IDLE,
    /** 选中 Provider 正在加载详情。 */
    LOADING,
    /** 已取得并校验详情内容。 */
    CONTENT,
    /** 请求、能力、身份或来源执行失败。 */
    ERROR,
}

/** 原生详情不可变 UI 状态。 */
internal data class TorrentDetailsUiState(
    /** 页面阶段。 */
    val phase: TorrentDetailsPhase = TorrentDetailsPhase.IDLE,
    /** 当前目的地是否至少 STARTED。 */
    val isScreenActive: Boolean = false,
    /** 已通过 Provider 与 infoHash 身份校验的详情。 */
    val details: TorrentDetails? = null,
    /** 描述转换后的不可执行富文本。 */
    val description: SafeRichTextDocument? = null,
    /** 通过同源 HTTPS 策略的海报。 */
    val posterUrl: String? = null,
    /** 通过同源 HTTPS 策略且去重的截图。 */
    val screenshotUrls: List<String> = emptyList(),
    /** 不携带异常正文的失败分类。 */
    val failureReason: TorrentDetailsFailureReason? = null,
) {
    /** 是否正在执行详情网络任务。 */
    val isLoading: Boolean get() = phase == TorrentDetailsPhase.LOADING
}

/** 详情状态编排器；候选设置和构造都不会在目的地不可见时联网。 */
@HiltViewModel
internal class TorrentDetailsViewModel @Inject constructor(
    /** 固定注册表顺序的全部来源。 */
    private val providers: List<SearchProvider>,
    /** 详情安全请求与身份校验协作者。 */
    private val loader: TorrentDetailsLoader,
) : ViewModel() {
    /** 页面唯一可变状态。 */
    private val mutableUiState = MutableStateFlow(TorrentDetailsUiState())
    /** Compose 生命周期感知收集的只读状态。 */
    val uiState: StateFlow<TorrentDetailsUiState> = mutableUiState.asStateFlow()
    /** 当前内存候选，不持久化 URL、磁力或正文。 */
    private var candidate: Torrent? = null
    /** 当前详情 Job，离页或候选替换会取消。 */
    private var loadJob: Job? = null
    /** 请求代次防止旧任务覆盖新候选。 */
    private var loadGeneration = 0L

    /** 接收导航根提供的内存候选，并校验路由 Provider ID 一致。 */
    fun setCandidate(
        /** Activity 内存中最近点击的候选。 */
        selectedTorrent: Torrent?,
        /** 路由中唯一携带的稳定 Provider ID。 */
        routeProviderId: ProviderId?,
    ) {
        cancelLoad(resetLoadingPhase = false)
        candidate = selectedTorrent?.takeIf { /** torrent 是待与路由稳定 ID 比较的候选。 */ torrent ->
            routeProviderId != null && torrent.providerId == routeProviderId
        }
        mutableUiState.update { /** state 是切换候选前的页面状态。 */ state ->
            state.copy(
                phase = if (candidate == null) TorrentDetailsPhase.ERROR else TorrentDetailsPhase.IDLE,
                details = null,
                description = null,
                posterUrl = null,
                screenshotUrls = emptyList(),
                failureReason = if (candidate == null) TorrentDetailsFailureReason.INVALID_REQUEST else null,
            )
        }
        if (mutableUiState.value.isScreenActive && candidate != null) startLoad()
    }

    /** 同步详情目的地可见性；离页取消，重进按当前候选恢复或重试。 */
    fun setScreenActive(
        /** true 表示当前 NavBackStackEntry 至少 STARTED。 */
        active: Boolean,
    ) {
        mutableUiState.update { /** state 是切换生命周期前的页面状态。 */ state -> state.copy(isScreenActive = active) }
        if (!active) {
            cancelLoad(resetLoadingPhase = true)
        } else if (candidate != null && mutableUiState.value.phase != TorrentDetailsPhase.CONTENT) {
            startLoad()
        }
    }

    /** 用户在固定错误态显式重试当前候选。 */
    fun retry() {
        if (mutableUiState.value.isScreenActive && candidate != null) startLoad()
    }

    /** 取消旧任务并启动当前候选的安全详情加载。 */
    private fun startLoad() {
        cancelLoad(resetLoadingPhase = false)
        /** selectedTorrent 固定本次详情候选。 */
        val selectedTorrent = candidate ?: return
        /** generation 固定本次请求身份。 */
        val generation = loadGeneration
        mutableUiState.update { /** state 是进入加载阶段前的页面状态。 */ state ->
            state.copy(phase = TorrentDetailsPhase.LOADING, failureReason = null)
        }
        loadJob = viewModelScope.launch {
            try {
                when (/** result 是通过超时、URL 与身份校验的加载结果。 */ val result = loader.load(selectedTorrent, providers)) {
                    is TorrentDetailsLoadResult.Success -> applyDetails(result.details, selectedTorrent, generation)
                    is TorrentDetailsLoadResult.Failure -> if (generation == loadGeneration) {
                        mutableUiState.update { /** state 是写入固定失败分类前的页面状态。 */ state ->
                            state.copy(phase = TorrentDetailsPhase.ERROR, failureReason = result.reason)
                        }
                    }
                }
            /** 页面离开或候选替换的取消不得转成错误。 */
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                if (generation == loadGeneration) loadJob = null
            }
        }
    }

    /** 把已校验详情转换为安全富文本和受限媒体 UI 状态。 */
    private fun applyDetails(
        /** 经过 loader 身份校验的领域详情。 */
        details: TorrentDetails,
        /** 本次请求的入口候选。 */
        selectedTorrent: Torrent,
        /** 本次请求代次。 */
        generation: Long,
    ) {
        if (generation != loadGeneration) return
        /** provider 是与详情稳定 ID 匹配的能力来源。 */
        val provider = providers.firstOrNull { /** registered 是待匹配稳定 ID 的来源。 */ registered ->
            registered.descriptor.id == details.providerId
        } as? TorrentDetailsProvider ?: return
        /** detailsUrl 已由 loader 校验，供相对媒体解析使用。 */
        val detailsUrl = selectedTorrent.descriptionPageUrl.orEmpty()
        /** description 是不可信正文的纯展示模型。 */
        val description = details.description?.let { /** rawDescription 是 Provider 返回的原始正文。 */ rawDescription ->
            SafeRichTextParser.parse(rawDescription, detailsUrl, provider.descriptor.baseUrl, provider.alternateUrlDomains)
        }
        /** posterUrl 只保留允许域 HTTPS 图片。 */
        val posterUrl = details.posterUrl?.let { /** rawPosterUrl 是不可信海报候选。 */ rawPosterUrl ->
            SafeRichTextParser.normalizeImageUrl(rawPosterUrl, detailsUrl, provider.descriptor.baseUrl, provider.alternateUrlDomains)
        }
        /** screenshotUrls 按来源顺序规范化、拒绝、去重和限量。 */
        val screenshotUrls = details.screenshotUrls.mapNotNull { /** rawScreenshotUrl 是不可信截图候选。 */ rawScreenshotUrl ->
            SafeRichTextParser.normalizeImageUrl(rawScreenshotUrl, detailsUrl, provider.descriptor.baseUrl, provider.alternateUrlDomains)
        }.distinct().take(MAX_SCREENSHOTS)
        mutableUiState.update { /** state 是写入安全详情内容前的页面状态。 */ state ->
            state.copy(
                phase = TorrentDetailsPhase.CONTENT,
                details = details,
                description = description,
                posterUrl = posterUrl,
                screenshotUrls = screenshotUrls,
                failureReason = null,
            )
        }
    }

    /** 取消当前详情任务并递增代次。 */
    private fun cancelLoad(
        /** true 时把加载态恢复 IDLE，供重进自动重试。 */
        resetLoadingPhase: Boolean,
    ) {
        loadGeneration += 1
        loadJob?.cancel(CancellationException("torrent_details_load_cancelled"))
        loadJob = null
        if (resetLoadingPhase) {
            mutableUiState.update { /** state 是取消加载前的页面状态。 */ state ->
                state.copy(phase = if (state.phase == TorrentDetailsPhase.LOADING) TorrentDetailsPhase.IDLE else state.phase)
            }
        }
    }

    /** 详情媒体第一版展示上限。 */
    companion object {
        /** 截图最多展示 32 张，与富文本图片数量上限一致。 */
        private const val MAX_SCREENSHOTS = 32
    }
}
