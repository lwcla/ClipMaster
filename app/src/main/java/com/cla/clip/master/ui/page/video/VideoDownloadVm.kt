package com.cla.clip.master.ui.page.video

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.R
import com.cla.clip.base.general.repository.DownloadRepository
import com.cla.clip.base.general.utils.logD
import com.cla.clip.master.entity.VideoDownloadState
import com.cla.clip.master.entity.toUi
import com.cla.clip.master.work.DownloadVideoWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.cla.clip.base.general.dao.DownloadTaskData
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
import androidx.work.await
import com.cla.clip.master.work.VideoDownloadWorkObserver
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import javax.inject.Inject


/**
 * 视频下载页 ViewModel。
 *
 * 负责启动指定下载任务、观察数据库中的任务状态，并把数据库状态映射为页面可直接展示的 UI 状态。
 * 下载实际执行交给 WorkManager，ViewModel 只做触发和状态订阅，避免页面退出时中断后台下载。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class VideoDownloadVm @Inject internal constructor(
    /** 下载任务仓库，提供任务读取、进度重置和状态观察能力。 */
    private val downloadRepository: DownloadRepository,

    /** 应用级 Context，用于入队 Worker 和读取兜底错误文案。 */
    @param:ApplicationContext val appContext: Context,
    /** 页面可见时才观察指定任务的 Worker，识别冷启动取消留下的进度记录。 */
    private val workObserver: VideoDownloadWorkObserver,
) : ViewModel() {

    companion object {
        /** 下载页只记录任务身份、阶段及异常类型，不输出资源地址。 */
        private const val TAG = "VideoDownloadVm"
    }

    /**
     * 当前页面正在观察的下载任务 ID。
     *
     * 使用 replay=1 是为了页面重组后仍能继续观察上一次任务；DROP_OLDEST 避免连续重试堆积过期任务。
     */
    private val _downloadState = MutableSharedFlow<Long>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 当前下载状态对应的轻量任务快照，来自同一观察流，不持有媒体文件或页面 Context。 */
    private val _taskData = MutableStateFlow<DownloadTaskData?>(null)

    /** 与状态流同时刷新同一数据库快照，避免为媒体信息建立第二个数据库观察。 */
    val taskData = _taskData.asStateFlow()

    /** 生命周期内合并业务进度与调度状态；无活动任务的遗留进度显示为可重试中断。 */
    val downloadState = _downloadState.transformLatest { taskId ->
        emitAll(combine(downloadRepository.observeTask(taskId), workObserver.observeActiveWork(taskId)) { task, active ->
            _taskData.value = task
            when {
                task == null -> VideoDownloadState.Failed(appContext.getString(R.string.base_general_the_download_task_was_not_found))
                VideoDownloadStartPolicy.isInterrupted(task.status, active) -> VideoDownloadState.Failed("download_interrupted")
                else -> task.toUi()
            }
        }.catch { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            logD(TAG) { "下载状态读取失败 taskId=$taskId type=${error.javaClass.simpleName}" }
            emit(VideoDownloadState.Failed("download_read_failed"))
        })
    }.stateIn(
        CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO),
        SharingStarted.WhileSubscribed(0),
        VideoDownloadState.Idle
    )

    /**
     * 下载触发轮次。
     *
     * 页面通过递增它触发重试；和 lastRequest 配合保证同一轮 Compose 重组不会重复入队 Worker。
     */
    var sessionId by mutableIntStateOf(0)

    /** 上一次已处理的轮次，避免 `LaunchedEffect` 或生命周期恢复造成重复下载请求。 */
    private var lastRequest: Pair<Long, Int>? = null
    /** 重试入队尚未落地时拒绝连点，防止两个 IO 分支先后把已开始的进度写回零。 */
    private val retryInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 启动或重试视频下载。
     *
     * 首次进入只订阅；只有明确重试失败或中断任务才从 0 开始，不改变运行中下载或合并进度。
     */
    fun startDownload(id: Int, taskId: Long) {
        if (lastRequest == (taskId to id)) {
            // 不要重复触发下载
            logD(TAG) { "sessionId 没变，忽略重复的下载请求 id=$id" }
            return
        }
        if (id > 0 && !retryInFlight.compareAndSet(false, true)) return
        lastRequest = taskId to id

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (id > 0) {
                    // 明确重试同时核对业务状态和调度器，运行中的 Worker 不会被重新归零。
                    val task = downloadRepository.getTask(taskId)
                    // 首份轻量调度快照即可判断，无需轮询或扫描所有下载记录。
                    val active = workObserver.observeActiveWork(taskId).first()
                    if (VideoDownloadStartPolicy.shouldRetry(task?.status, true, active)) {
                        downloadRepository.updateProgress(taskId, 0)
                        DownloadVideoWorker.enqueue(appContext, taskId).await()
                    }
                }
                logD(TAG) { "下载页观察 sessionId=$id taskId=$taskId" }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logD(TAG) { "下载重试失败 taskId=$taskId type=${error.javaClass.simpleName}" }
            } finally {
                // 重试失败也重新观察，缺失/中断/读取异常都能回到可行动提示。
                _downloadState.tryEmit(taskId)
                if (id > 0) retryInFlight.set(false)
            }
        }
    }
}
