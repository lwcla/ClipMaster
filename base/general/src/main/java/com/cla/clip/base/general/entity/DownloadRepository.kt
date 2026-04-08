package com.cla.clip.base.general.entity

import com.cla.clip.base.general.dao.DownloadDao
import com.cla.clip.base.general.dao.DownloadTaskData
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_DOWNLOADING
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_FAILED
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_SUCCESS
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepository @Inject constructor(
    private val downloadDao: DownloadDao
) {

    /**
     * 创建下载任务
     */
    suspend fun createTask(
        videoUrl: String,
        referer: String? = null,
        userAgent: String? = null,
        cookie: String? = null
    ): String {
        val taskId = UUID.randomUUID().toString()
        val task = DownloadTaskData(
            taskId = taskId,
            videoUrl = videoUrl,
            referer = referer,
            userAgent = userAgent,
            cookie = cookie,
            status = "downloading",
            progress = 0
        )
        downloadDao.upsertTask(task)
        return taskId
    }

    /**
     * 观察下载进度
     */
    fun observeTask(taskId: String): Flow<DownloadTaskData?> {
        return downloadDao.observeTask(taskId)
    }

    /**
     * 更新进度
     */
    suspend fun updateProgress(taskId: String, progress: Int) {
        downloadDao.updateProgress(taskId, progress, STATUS_DOWNLOADING)
    }

    /**
     * 标记成功
     */
    suspend fun markSuccess(taskId: String, savePath: String) {
        downloadDao.updateStatus(taskId, STATUS_SUCCESS, savePath = savePath)
    }

    /**
     * 标记失败
     */
    suspend fun markFailed(taskId: String, errorMsg: String) {
        downloadDao.updateStatus(taskId, STATUS_FAILED, errorMsg = errorMsg)
    }

    /**
     * 获取任务
     */
    suspend fun getTask(taskId: String): DownloadTaskData? {
        return downloadDao.getTask(taskId)
    }

    /**
     * 删除任务
     */
    suspend fun deleteTask(taskId: String) {
        downloadDao.deleteTask(taskId)
    }
}