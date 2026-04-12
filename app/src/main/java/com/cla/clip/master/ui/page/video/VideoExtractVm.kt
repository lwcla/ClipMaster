package com.cla.clip.master.ui.page.video

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.entity.DownloadRepository
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.master.BaseViewModel
import com.cla.clip.master.entity.VideoCandidate
import com.cla.clip.master.work.DownloadVideoWorkStarter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ProbeState {
    data object Idle : ProbeState
    data class HiddenProbing(val sessionId: Int) : ProbeState
    data class NeedUserPlay(val sessionId: Int) : ProbeState
    data class Success(val candidate: VideoCandidate) : ProbeState
    data object Failed : ProbeState
}

@HiltViewModel
class VideoExtractVm @Inject constructor(
    @param:ApplicationContext override val appContext: Context,
    private val downloadRepo: DownloadRepository,
) : BaseViewModel(appContext) {

    companion object {
        private const val TAG = "VideoExtractVm"
    }

    var probeState by mutableStateOf<ProbeState>(ProbeState.Idle)

    var sessionId by mutableIntStateOf(0)

    private val _createDownloadTaskFlow = MutableSharedFlow<Long>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val createDownloadTaskFlow = _createDownloadTaskFlow.asSharedFlow()

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

                // 启动下载任务
                val task = downloadRepo.getTask(taskId)
                if (task != null) {
                    // 开启下载任务之前，把之前的下载状态重置为 downloading，避免 UI 卡在完成或失败状态
                    // todo 如果要做断点续传的话，这里就需要改
                    downloadRepo.updateProgress(taskId, 0)
                }
                logD(TAG) { "startDownloadAndGo: 启动下载" }
                DownloadVideoWorkStarter.enqueue(appContext, taskId)

                _createDownloadTaskFlow.emit(taskId)
            }.onFailure {
                logE(TAG, it) { "创建下载任务失败: " }
                _createDownloadTaskFlow.emit(-1)
            }
        }
    }
}