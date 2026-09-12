package com.cla.clip.feature.torrent.search.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.feature.torrent.search.data.document.NoOpTorrentDocumentGateway
import com.cla.clip.feature.torrent.search.data.document.TorrentDocumentGateway
import com.cla.clip.feature.torrent.search.data.document.TorrentDocumentTarget
import com.cla.clip.feature.torrent.search.data.repository.ViewedTorrentRepository
import com.cla.clip.feature.torrent.search.data.mapper.toBookmarkedTorrent
import com.cla.clip.feature.torrent.search.data.repository.BookmarkRepository
import com.cla.clip.feature.torrent.search.data.repository.NoOpBookmarkRepository
import com.cla.clip.feature.torrent.search.download.NoOpTorrentDownloadPreparer
import com.cla.clip.feature.torrent.search.download.TorrentDownloadFailureReason
import com.cla.clip.feature.torrent.search.download.TorrentDownloadPreparation
import com.cla.clip.feature.torrent.search.download.TorrentDownloadPreparer
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
import kotlinx.coroutines.flow.collect
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

/** 当前候选精确书签查询的就绪阶段，未知与失败都禁止推测写入。 */
internal enum class BookmarkPresencePhase {
    /** 尚未收到当前候选的首个精确结果。 */
    UNKNOWN,
    /** 已收到当前候选的首个精确结果，可安全保存或移除。 */
    READY,
    /** 当前候选精确查询失败。 */
    ERROR,
}

/** 详情页 Torrent 下载和一次性 SAF 保存阶段。 */
internal enum class TorrentDownloadPhase {
    /** 尚未请求、用户取消或页面离开后的空闲态。 */
    IDLE,
    /** 正在下载并验证 Torrent。 */
    PREPARING,
    /** 已验证字节仅驻留 ViewModel 内存，等待系统文档目标。 */
    AWAITING_DESTINATION,
    /** 正在向用户选择的 SAF 文档分块写出。 */
    SAVING,
    /** 本次文档已完整保存且内存字节已清除。 */
    SAVED,
    /** 下载、校验或保存失败，原因使用固定枚举。 */
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
    /** 当前候选是否已保存为书签，来自精确 infoHash Flow。 */
    val isBookmarked: Boolean = false,
    /** 当前候选精确书签查询是否已就绪。 */
    val bookmarkPresencePhase: BookmarkPresencePhase = BookmarkPresencePhase.UNKNOWN,
    /** 书签保存或移除是否运行中。 */
    val isBookmarkBusy: Boolean = false,
    /** 最近一次书签写入是否失败，不携带异常正文。 */
    val bookmarkWriteFailed: Boolean = false,
    /** Torrent 下载与 SAF 保存阶段。 */
    val downloadPhase: TorrentDownloadPhase = TorrentDownloadPhase.IDLE,
    /** 仅供 CreateDocument 展示的已净化文件名，不含路径。 */
    val downloadSuggestedFileName: String? = null,
    /** 每次进入等待目标阶段递增，供 Compose 只启动一次系统选择器。 */
    val downloadRequestId: Long = 0L,
    /** 下载或保存失败的固定分类，不携带地址和异常正文。 */
    val downloadFailureReason: TorrentDownloadFailureReason? = null,
) {
    /** 是否正在执行详情网络任务。 */
    val isLoading: Boolean get() = phase == TorrentDetailsPhase.LOADING
}

/** 详情状态编排器；候选设置和构造都不会在目的地不可见时联网。 */
@HiltViewModel
internal class TorrentDetailsViewModel @Inject constructor(
    /** 固定注册表顺序的全部来源。 */
  private val providers: List<@JvmSuppressWildcards SearchProvider>,
    /** 详情安全请求与身份校验协作者。 */
    private val loader: TorrentDetailsLoader,
    /** 精确书签存在观察与保存/移除边界。 */
    private val bookmarkRepository: BookmarkRepository = NoOpBookmarkRepository,
    /** 下载、大小限制、Bencode 与当前详情身份校验边界。 */
    private val downloadPreparer: TorrentDownloadPreparer = NoOpTorrentDownloadPreparer,
    /** 已校验字节的一次性 SAF 写出边界。 */
    private val torrentDocumentGateway: TorrentDocumentGateway = NoOpTorrentDocumentGateway,
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
    /** 精确书签存在状态只在详情目的地可见时收集。 */
    private var bookmarkObserveJob: Job? = null
    /** 精确书签观察代次，阻止旧候选结果与 finally 覆盖新任务。 */
    private var bookmarkObserveGeneration = 0L
    /** 书签保存或移除任务离页时取消。 */
    private var bookmarkWriteJob: Job? = null
    /** 书签写入代次，阻止旧任务结束清除新任务忙碌态。 */
    private var bookmarkWriteGeneration = 0L
    /** 下载准备或 SAF 写出的当前任务，离页与候选替换必须取消。 */
    private var downloadJob: Job? = null
    /** 下载代次阻止旧任务在取消后覆盖新候选状态。 */
    private var downloadGeneration = 0L
    /** 已校验 Torrent 字节只在等待/执行本次 SAF 写出期间驻留内存。 */
    private var pendingTorrentBytes: ByteArray? = null

    /** 接收导航根提供的内存候选，并校验路由 Provider ID 一致。 */
    fun setCandidate(
        /** Activity 内存中最近点击的候选。 */
        selectedTorrent: Torrent?,
        /** 路由中唯一携带的稳定 Provider ID。 */
        routeProviderId: ProviderId?,
    ) {
        cancelLoad(resetLoadingPhase = false)
        cancelBookmarkWork()
        cancelTorrentDownload(resetState = true)
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
                isBookmarked = false,
                bookmarkPresencePhase = BookmarkPresencePhase.UNKNOWN,
                isBookmarkBusy = false,
                bookmarkWriteFailed = false,
                downloadPhase = TorrentDownloadPhase.IDLE,
                downloadSuggestedFileName = null,
                downloadFailureReason = null,
            )
        }
        if (mutableUiState.value.isScreenActive && candidate != null) {
            startLoad()
            startBookmarkObservation()
        }
    }

    /** 同步详情目的地可见性；离页取消，重进按当前候选恢复或重试。 */
    fun setScreenActive(
        /** true 表示当前 NavBackStackEntry 至少 STARTED。 */
        active: Boolean,
    ) {
        mutableUiState.update { /** state 是切换生命周期前的页面状态。 */ state -> state.copy(isScreenActive = active) }
        if (!active) {
            cancelLoad(resetLoadingPhase = true)
            cancelBookmarkWork()
            cancelTorrentDownload(resetState = true)
        } else if (candidate != null && mutableUiState.value.phase != TorrentDetailsPhase.CONTENT) {
            startLoad()
            startBookmarkObservation()
        } else if (candidate != null) {
            startBookmarkObservation()
        }
    }

    /** 用户在固定错误态显式重试当前候选。 */
    fun retry() {
        if (mutableUiState.value.isScreenActive && candidate != null) startLoad()
    }

    /** 保存或移除当前候选书签；操作只允许在详情可见且无前一写入时启动。 */
    fun toggleBookmark() {
        /** selectedTorrent 固定本次书签操作候选，URL 和磁力不会进入 UI 事件参数。 */
        val selectedTorrent = candidate ?: return
        if (!mutableUiState.value.isScreenActive || mutableUiState.value.bookmarkPresencePhase != BookmarkPresencePhase.READY || bookmarkWriteJob?.isActive == true) return
        /** removeBookmark 固定点击瞬间的目标动作，避免 Flow 更新中途反转语义。 */
        val removeBookmark = mutableUiState.value.isBookmarked
        /** generation 固定本次写入所有权。 */
        val generation = ++bookmarkWriteGeneration
        bookmarkWriteJob = viewModelScope.launch {
            if (generation == bookmarkWriteGeneration) mutableUiState.update { /** state 是书签写入开始前快照。 */ state -> state.copy(isBookmarkBusy = true, bookmarkWriteFailed = false) }
            try {
                /** succeeded 是 Repository 对稳定身份写入或删除的固定结果。 */
                val succeeded = if (removeBookmark) {
                    bookmarkRepository.delete(selectedTorrent.infoHash)
                } else {
                    bookmarkRepository.save(selectedTorrent.toBookmarkedTorrent())
                }
                if (!succeeded && generation == bookmarkWriteGeneration) {
                    mutableUiState.update { /** state 是书签未命中或拒绝前快照。 */ state -> state.copy(bookmarkWriteFailed = true) }
                }
            /** 页面离开或候选替换时保持结构化取消。 */
            } catch (cancellation: CancellationException) {
                throw cancellation
            /** 书签介质异常只映射为固定页面失败状态。 */
            } catch (exception: Exception) {
                if (generation == bookmarkWriteGeneration) mutableUiState.update { /** state 是书签介质失败前快照。 */ state -> state.copy(bookmarkWriteFailed = true) }
            } finally {
                if (generation == bookmarkWriteGeneration) {
                    mutableUiState.update { /** state 是书签写入结束前快照。 */ state -> state.copy(isBookmarkBusy = false) }
                    bookmarkWriteJob = null
                }
            }
        }
    }

    /** 下载并严格验证当前详情 Torrent；成功前不打开 SAF 目标。 */
    fun requestTorrentDownload() {
        /** details 固定本次下载的已校验详情快照。 */
        val details = mutableUiState.value.details ?: return
        /** downloadUrl 必须由 Provider 详情明确提供。 */
        val downloadUrl = details.fileDownloadLink ?: return
        if (!mutableUiState.value.isScreenActive || downloadJob?.isActive == true) return
        cancelTorrentDownload(resetState = false)
        /** generation 固定本次下载所有权。 */
        val generation = downloadGeneration
        mutableUiState.update { /** state 是下载准备开始前快照。 */ state ->
            state.copy(
                downloadPhase = TorrentDownloadPhase.PREPARING,
                downloadSuggestedFileName = null,
                downloadFailureReason = null,
            )
        }
        downloadJob = viewModelScope.launch {
            try {
                when (/** preparation 是下载、Bencode 和身份校验后的固定结果。 */ val preparation = downloadPreparer.prepare(downloadUrl, details.infoHash, details.name)) {
                    is TorrentDownloadPreparation.Ready -> if (generation == downloadGeneration && mutableUiState.value.isScreenActive) {
                        pendingTorrentBytes = preparation.bytes
                        mutableUiState.update { /** state 是请求系统目标前快照。 */ state ->
                            state.copy(
                                downloadPhase = TorrentDownloadPhase.AWAITING_DESTINATION,
                                downloadSuggestedFileName = preparation.suggestedFileName,
                                downloadRequestId = state.downloadRequestId + 1,
                                downloadFailureReason = null,
                            )
                        }
                    }
                    is TorrentDownloadPreparation.Failure -> if (generation == downloadGeneration) {
                        pendingTorrentBytes = null
                        mutableUiState.update { /** state 是写入固定下载失败前快照。 */ state ->
                            state.copy(
                                downloadPhase = TorrentDownloadPhase.ERROR,
                                downloadSuggestedFileName = null,
                                downloadFailureReason = preparation.reason,
                            )
                        }
                    }
                }
            /** 页面离开或候选替换保持结构化取消。 */
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                if (generation == downloadGeneration) downloadJob = null
            }
        }
    }

    /** 把已验证内存字节写入本次系统返回 URI；URI 不进入状态或字段。 */
    fun saveTorrentDownload(
        /** 用户刚从 CreateDocument 返回的一次性目标。 */
        target: TorrentDocumentTarget,
    ) {
        /** bytes 固定本次写出内容，写出结束或失败后始终清除字段引用。 */
        val bytes = pendingTorrentBytes ?: return
        if (!mutableUiState.value.isScreenActive || mutableUiState.value.downloadPhase != TorrentDownloadPhase.AWAITING_DESTINATION) return
        /** generation 固定本次 SAF 写出所有权。 */
        val generation = downloadGeneration
        mutableUiState.update { /** state 是 SAF 写出开始前快照。 */ state ->
            state.copy(downloadPhase = TorrentDownloadPhase.SAVING, downloadFailureReason = null)
        }
        downloadJob = viewModelScope.launch {
            try {
                torrentDocumentGateway.write(target, bytes)
                if (generation == downloadGeneration) mutableUiState.update { /** state 是完整写出成功前快照。 */ state ->
                    state.copy(
                        downloadPhase = TorrentDownloadPhase.SAVED,
                        downloadSuggestedFileName = null,
                        downloadFailureReason = null,
                    )
                }
            /** 页面离开时保持结构化取消。 */
            } catch (cancellation: CancellationException) {
                throw cancellation
            /** Provider/存储异常统一映射为固定保存失败。 */
            } catch (failure: Exception) {
                if (generation == downloadGeneration) mutableUiState.update { /** state 是保存失败前快照。 */ state ->
                    state.copy(
                        downloadPhase = TorrentDownloadPhase.ERROR,
                        downloadSuggestedFileName = null,
                        downloadFailureReason = TorrentDownloadFailureReason.SAVE_FAILED,
                    )
                }
            } finally {
                if (generation == downloadGeneration) {
                    pendingTorrentBytes = null
                    downloadJob = null
                }
            }
        }
    }

    /** 用户关闭系统文档选择器时放弃本次字节，不保留 URI 或可恢复文件。 */
    fun cancelTorrentSave() {
        if (mutableUiState.value.downloadPhase == TorrentDownloadPhase.AWAITING_DESTINATION) {
            cancelTorrentDownload(resetState = true)
        }
    }

    /** 单元测试只读取是否仍持有字节，不暴露具体内容给 UI。 */
    internal fun hasPendingTorrentBytesForTest(): Boolean = pendingTorrentBytes != null

    /** 可见时按当前候选 infoHash 精确观察存在状态，不订阅整表。 */
    private fun startBookmarkObservation() {
        if (bookmarkObserveJob != null) return
        /** selectedTorrent 固定本次观察身份，候选替换会先取消该任务。 */
        val selectedTorrent = candidate ?: return
        /** generation 固定本次精确观察所有权。 */
        val generation = ++bookmarkObserveGeneration
        mutableUiState.update { /** state 是当前候选开始精确查询前快照。 */ state ->
            state.copy(bookmarkPresencePhase = BookmarkPresencePhase.UNKNOWN, bookmarkWriteFailed = false)
        }
        bookmarkObserveJob = viewModelScope.launch {
            try {
                bookmarkRepository.observeBookmarked(selectedTorrent.infoHash).collect { /** bookmarked 是当前精确存在状态。 */ bookmarked ->
                    if (generation == bookmarkObserveGeneration) mutableUiState.update { /** state 是写入存在状态前快照。 */ state ->
                        state.copy(isBookmarked = bookmarked, bookmarkPresencePhase = BookmarkPresencePhase.READY)
                    }
                }
            /** 离页停止精确观察时保持结构化取消。 */
            } catch (cancellation: CancellationException) {
                throw cancellation
            /** 精确查询异常只映射为固定页面失败状态。 */
            } catch (exception: Exception) {
                if (generation == bookmarkObserveGeneration) mutableUiState.update { /** state 是存在查询失败前快照。 */ state ->
                    state.copy(bookmarkPresencePhase = BookmarkPresencePhase.ERROR, bookmarkWriteFailed = true)
                }
            } finally {
                if (generation == bookmarkObserveGeneration) bookmarkObserveJob = null
            }
        }
    }

    /** 取消精确观察和书签写入，离页后不得继续更新状态。 */
    private fun cancelBookmarkWork() {
        bookmarkObserveGeneration += 1
        bookmarkObserveJob?.cancel(CancellationException("torrent_details_bookmark_observe_cancelled"))
        bookmarkObserveJob = null
        bookmarkWriteGeneration += 1
        bookmarkWriteJob?.cancel(CancellationException("torrent_details_bookmark_write_cancelled"))
        bookmarkWriteJob = null
        mutableUiState.update { /** state 是取消书签工作前快照。 */ state ->
            state.copy(isBookmarkBusy = false, bookmarkPresencePhase = BookmarkPresencePhase.UNKNOWN)
        }
    }

    /** 取消下载/写出并清除全部短暂字节；可选恢复页面空闲态。 */
    private fun cancelTorrentDownload(
        /** true 表示候选切换、离页或用户取消后恢复 IDLE。 */
        resetState: Boolean,
    ) {
        downloadGeneration += 1
        downloadJob?.cancel(CancellationException("torrent_download_cancelled"))
        downloadJob = null
        pendingTorrentBytes = null
        if (resetState) mutableUiState.update { /** state 是释放短暂下载状态前快照。 */ state ->
            state.copy(
                downloadPhase = TorrentDownloadPhase.IDLE,
                downloadSuggestedFileName = null,
                downloadFailureReason = null,
            )
        }
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
