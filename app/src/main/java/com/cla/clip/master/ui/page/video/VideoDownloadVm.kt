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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
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
class VideoDownloadVm @Inject constructor(
    /** 下载任务仓库，提供任务读取、进度重置和状态观察能力。 */
    private val downloadRepository: DownloadRepository,

    /** 应用级 Context，用于入队 Worker 和读取兜底错误文案。 */
    @param:ApplicationContext val appContext: Context
) : ViewModel() {

    companion object {
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

    /**
     * 下载页展示状态。
     *
     * 每次收到新的 taskId 都切换到对应任务的数据库观察流；任务缺失时直接转换成失败态，避免页面无限停留在准备中。
     */
    val downloadState = _downloadState.transformLatest { taskId ->
        downloadRepository.observeTask(taskId).collectLatest { taskData ->
            emit(taskData?.toUi() ?: VideoDownloadState.Failed(appContext.getString(R.string.base_general_the_download_task_was_not_found)))
        }
    }.stateIn(
        CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO),
        SharingStarted.WhileSubscribed(5000),
        VideoDownloadState.Idle
    )

    /**
     * 下载触发轮次。
     *
     * 页面通过递增它触发重试；和 lastSessionId 配合保证同一轮 Compose 重组不会重复入队 Worker。
     */
    var sessionId by mutableIntStateOf(0)

    /** 上一次已处理的轮次，避免 `LaunchedEffect` 或生命周期恢复造成重复下载请求。 */
    private var lastSessionId: Int? = null

    /**
     * 启动或重试视频下载。
     *
     * 在 Worker 入队前把进度重置为 0，避免失败/完成后的旧状态残留到新一轮下载；
     * 当前实现暂不做断点续传，因此重试会从头开始。
     */
    fun startDownload(id: Int, taskId: Long) {
        if (lastSessionId == id) {
            // 不要重复触发下载
            logD(TAG) { "sessionId 没变，忽略重复的下载请求 id=$id" }
            return
        }
        lastSessionId = id

        viewModelScope.launch(Dispatchers.IO) {
            val task = downloadRepository.getTask(taskId)
            if (task != null) {
                // 开启下载任务之前，把之前的下载状态重置为 downloading，避免 UI 卡在完成或失败状态
                // todo 如果要做断点续传的话，这里就需要改
                downloadRepository.updateProgress(taskId, 0)
            }
            DownloadVideoWorker.enqueue(appContext, taskId)

            logD(TAG) { "startDownload 开始下载，sessionId=$id, taskId=$taskId" }
            _downloadState.tryEmit(taskId)
        }
    }
}
