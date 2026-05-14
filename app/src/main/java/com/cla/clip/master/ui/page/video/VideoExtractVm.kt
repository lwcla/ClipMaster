package com.cla.clip.master.ui.page.video

import android.content.Context
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.repository.DownloadRepository
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.master.entity.VideoCandidate
import com.cla.clip.master.utils.WebViewLinkPreviewExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 视频资源探测状态。
 *
 * 状态由页面和 ViewModel 共同驱动：页面负责 WebView 生命周期和网络拦截，ViewModel 保存当前阶段和下载任务事件。
 * `sessionId` 用来区分多次重试，避免旧的超时协程把新一轮探测错误地标记为失败。
 */
sealed interface ProbeState {
    /** 初始状态，页面尚未开始加载目标网页。 */
    data object Idle : ProbeState

    /** 后台隐藏 WebView 正在加载页面并尝试自动播放视频。 */
    data class HiddenProbing(val sessionId: Int) : ProbeState

    /** 自动探测不足时预留的用户手动播放阶段，目前页面仍以失败重试为主。 */
    data class NeedUserPlay(val sessionId: Int) : ProbeState

    /** 已捕获到可下载的视频候选地址。 */
    data class Success(val candidate: VideoCandidate) : ProbeState

    /** 当前探测轮次失败，用户可以触发重试。 */
    data object Failed : ProbeState
}

/**
 * 视频提取页 ViewModel。
 *
 * 负责保存探测状态、维护重试轮次，并在识别到候选视频后创建下载任务。
 * WebView 相关对象不放在这里，避免 ViewModel 持有页面生命周期资源导致泄漏。
 */
@HiltViewModel
class VideoExtractVm @Inject constructor(
    /** 应用级 Context，保留给下载任务或资源读取使用，不持有 Activity。 */
    @param:ApplicationContext val appContext: Context,

    /** 下载任务仓库，用于把识别出的候选视频落库为可观察的下载任务。 */
    private val downloadRepo: DownloadRepository,

    /** WebView 链接预览补全器，用真实页面 DOM 回写列表卡片预览。 */
    private val linkPreviewExtractor: WebViewLinkPreviewExtractor,
) : ViewModel() {

    companion object {
        private const val TAG = "VideoExtractVm"
    }

    /**
     * 当前视频探测状态。
     *
     * 使用 Compose state 是为了让页面状态切换即时触发重组；状态只保存轻量数据，不保存 WebView。
     */
    var probeState by mutableStateOf<ProbeState>(ProbeState.Idle)

    /**
     * 当前重试轮次。
     *
     * 每次递增都会让页面重新加载目标 URL；与 `ProbeState` 中的 sessionId 配合过滤过期超时回调。
     */
    var sessionId by mutableIntStateOf(0)

    /**
     * 下载任务创建结果事件。
     *
     * 使用 SharedFlow 而不是状态，避免配置变化后重复导航；失败用 -1 表示，调用方只需要做一次 Toast。
     */
    private val _createDownloadTaskFlow = MutableSharedFlow<Long>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 页面订阅的下载任务创建事件，成功时携带任务 ID，失败时携带 -1。 */
    val createDownloadTaskFlow = _createDownloadTaskFlow.asSharedFlow()

    /**
     * 使用当前 WebView DOM 补全链接预览缓存。
     *
     * 视频提取页的 WebView 能带上站点 Cookie、重定向和脚本执行结果，适合补齐 OkHttp/Jsoup 首轮拿不到的标题或封面；
     * 该操作只影响 `link_previews` 缓存，失败不会影响视频地址识别和下载任务创建。
     */
    suspend fun saveWebViewLinkPreview(webView: WebView, pageUrl: String, fallbackImageUrl: String? = null) {
        linkPreviewExtractor.extractAndSave(webView, pageUrl, fallbackImageUrl)
    }

    /**
     * 根据视频候选地址创建下载任务并通知页面跳转。
     *
     * 创建任务在 IO 线程执行；候选中的 Referer、UserAgent、Cookie 会一起保存，供 Worker 下载防盗链资源时复用。
     */
    fun startDownloadAndGo(candidate: VideoCandidate) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                downloadRepo.createTask(
                    videoUrl = candidate.url,
                    fileName = candidate.fileName,
                    referer = candidate.referer,
                    userAgent = candidate.userAgent,
                    cookie = candidate.cookie
                )
            }.onSuccess { taskId ->
                logD(TAG) { "创建下载任务成功: taskId=$taskId" }
                _createDownloadTaskFlow.emit(taskId)
            }.onFailure {
                logE(TAG, it) { "创建下载任务失败: " }
                _createDownloadTaskFlow.emit(-1)
            }
        }
    }
}
