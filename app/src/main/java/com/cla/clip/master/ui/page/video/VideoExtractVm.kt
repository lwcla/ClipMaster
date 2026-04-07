package com.cla.clip.master.ui.page.video

import android.content.Context
import androidx.lifecycle.ViewModel
import com.cla.clip.base.general.dao.DownloadTaskData
import com.cla.clip.base.general.entity.DownloadRepository
import com.cla.clip.master.entity.VideoCandidate
import com.cla.clip.master.service.DownloadVideoService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class VideoExtractVm @Inject constructor(
    private val downloadRepository: DownloadRepository,
    @param:ApplicationContext private val appContext: Context
) : ViewModel() {

    suspend fun startDownload(
        candidate: VideoCandidate
    ): Flow<DownloadTaskData?> {
        // 创建任务，得到 taskId
        val (url, referer, userAgent, cookie) = candidate
        val taskId = downloadRepository.createTask(url, referer, userAgent, cookie)
        // 启动前台服务
        DownloadVideoService.start(appContext, taskId, candidate)

        // 返回观察流（即使 taskId 还在异步赋值，观察仍会生效）
        return downloadRepository.observeTask(taskId)
    }
}