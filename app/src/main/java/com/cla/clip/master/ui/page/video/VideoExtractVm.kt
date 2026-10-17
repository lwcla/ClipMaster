package com.cla.clip.master.ui.page.video

import android.content.Context
import androidx.work.await
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.repository.DownloadRepository
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.base.general.dao.LinkPreviewData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.cla.clip.base.general.utils.logD
import com.cla.clip.master.entity.VideoCandidate
import com.cla.clip.master.utils.WebViewLinkPreviewExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
    /** 只读取已有网页预览缓存，提取页不为广告额外请求网络元数据。 */
    private val clipRepository: ClipRepository,
) : ViewModel() {

    companion object {
        /** 只记录提交身份和失败类型，避免日志泄露候选 URL 与 Cookie。 */
        private const val TAG = "VideoExtractVm"
    }

    /**
     * 当前视频探测状态。
     *
     * 使用 Compose state 是为了让页面状态切换即时触发重组；状态只保存轻量数据，不保存 WebView。
     */
    internal val probeSession = VideoProbeSession()
    /** 页面只读取控制器结果，不能绕过会话校验改写成功或失败。 */
    val probeState: ProbeState get() = probeSession.state

    /**
     * 当前重试轮次。
     *
     * 每次递增都会让页面重新加载目标 URL；与 `ProbeState` 中的 sessionId 配合过滤过期超时回调。
     */
    val sessionId: Int get() = probeSession.sessionId

    /** 同一提交未结束时禁用再次创建任务。 */
    var isSubmitting by mutableStateOf(false)
        private set
    /** 成功导航事件保存在内存，配置变化不会丢失。 */
    var createdTaskId by mutableStateOf<Long?>(null)
        private set
    /** 失败保留候选，允许用户明确再次提交。 */
    var submissionFailed by mutableStateOf(false)
        private set
    /** 辅助缓存补写后重新读取一次摘要，不增加持续数据库观察。 */
    var previewRevision by mutableIntStateOf(0)
        private set

    /** 导航前消费已创建任务身份，配置恢复不会重复跳转。 */
    fun consumeCreatedTask() { createdTaskId = null }

    /**
     * 使用当前 WebView DOM 补全链接预览缓存。
     *
     * 视频提取页的 WebView 能带上站点 Cookie、重定向和脚本执行结果，适合补齐 OkHttp/Jsoup 首轮拿不到的标题或封面；
     * 该操作只影响 `link_previews` 缓存，失败不会影响视频地址识别和下载任务创建。
     */
    suspend fun saveWebViewLinkPreview(webView: WebView, pageUrl: String, fallbackImageUrl: String? = null) {
        kotlinx.coroutines.withTimeoutOrNull(1_500L) {
            linkPreviewExtractor.extractAndSave(webView, pageUrl, fallbackImageUrl)
            previewRevision += 1
        }
    }

    /** 缓存读取随页面生命周期收集启动；无缓存时返回空值，不触发远端补全。 */
    fun observeCachedPreview(pageUrl: String): Flow<LinkPreviewData?> = flow {
        emit(clipRepository.loadLinkPreview(pageUrl))
    }

    /**
     * 根据视频候选地址创建下载任务并通知页面跳转。
     *
     * 创建任务在 IO 线程执行；候选中的 Referer、UserAgent、Cookie 会一起保存，供 Worker 下载防盗链资源时复用。
     */
    fun startDownloadAndGo(candidate: VideoCandidate) {
        if (isSubmitting || createdTaskId != null) return
        isSubmitting = true
        submissionFailed = false
        viewModelScope.launch {
            try {
                // 创建与入队属于明确的用户动作；下载页本身只观察任务。
                val taskId = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    // 新任务身份只在此处产生，后续页面使用同一身份观察。
                    val id = downloadRepo.createTask(
                        videoUrl = candidate.url,
                        fileName = candidate.fileName,
                        referer = candidate.referer,
                        userAgent = candidate.userAgent,
                        cookie = candidate.cookie
                    )
                    com.cla.clip.master.work.DownloadVideoWorker.enqueue(appContext, id).await()
                    id
                }
                logD(TAG) { "创建下载任务成功: taskId=$taskId" }
                createdTaskId = taskId
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logD(TAG) { "创建下载任务失败 type=${error.javaClass.simpleName}" }
                submissionFailed = true
            } finally {
                isSubmitting = false
            }
        }
    }
}
