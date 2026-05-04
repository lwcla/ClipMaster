package com.cla.clip.master.ui.page.video

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.R
import com.cla.clip.base.general.entity.DownloadRepository
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


@HiltViewModel
class VideoDownloadVm @Inject constructor(
    private val downloadRepository: DownloadRepository,
    @param:ApplicationContext val appContext: Context
) : ViewModel() {

    companion object {
        private const val TAG = "VideoDownloadVm"
    }

    private val _downloadState = MutableSharedFlow<Long>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val downloadState = _downloadState.transformLatest { taskId ->
        downloadRepository.observeTask(taskId).collectLatest { taskData ->
            emit(taskData?.toUi() ?: VideoDownloadState.Failed(appContext.getString(R.string.base_general_the_download_task_was_not_found)))
        }
    }.stateIn(
        CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO),
        SharingStarted.WhileSubscribed(5000),
        VideoDownloadState.Idle
    )

    var sessionId by mutableIntStateOf(0)

    private var lastSessionId: Int? = null

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