package com.cla.clip.master.ui.page.video

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.dao.DownloadTaskData
import com.cla.clip.base.general.entity.DownloadRepository
import com.cla.clip.master.service.DownloadVideoService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.jvm.java

@HiltViewModel
class VideoExtractVm @Inject constructor(
    private val downloadRepository: DownloadRepository,
    @param:ApplicationContext private val appContext: Context
) : ViewModel() {

    fun startDownload(
        videoUrl: String,
        referer: String? = null,
        userAgent: String? = null,
        cookie: String? = null
    ): Flow<DownloadTaskData?> {
        var taskId = ""

        viewModelScope.launch {
            // 创建任务，得到 taskId
            taskId = downloadRepository.createTask(videoUrl, referer, userAgent, cookie)

            // 启动前台服务
            val intent = Intent(appContext, DownloadVideoService::class.java).apply {
                putExtra("taskId", taskId)
                putExtra("videoUrl", videoUrl)
                putExtra("referer", referer)
                putExtra("userAgent", userAgent)
                putExtra("cookie", cookie)
            }
            appContext.startService(intent)
        }

        // 返回观察流（即使 taskId 还在异步赋值，观察仍会生效）
        return downloadRepository.observeTask(taskId)
    }
}