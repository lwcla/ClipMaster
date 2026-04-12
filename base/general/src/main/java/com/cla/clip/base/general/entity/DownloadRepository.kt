package com.cla.clip.base.general.entity

import com.cla.clip.base.general.dao.DownloadDao
import com.cla.clip.base.general.dao.DownloadTaskData
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_DOWNLOADING
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_FAILED
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_SUCCESS
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.compareTo

@Singleton
class DownloadRepository @Inject constructor(
    private val downloadDao: DownloadDao
) {

    /** 创建下载任务 */
    suspend fun createTask(
        videoUrl: String,
        fileName: String,
        referer: String? = null,
        userAgent: String? = null,
        cookie: String? = null,
    ): Long {
        val history = downloadDao.getTask(videoUrl)
        val task = history?.copy(
            referer = referer,
            userAgent = userAgent,
            cookie = cookie,
            fileName = fileName
        ) ?: DownloadTaskData(
            videoUrl = videoUrl,
            referer = referer,
            userAgent = userAgent,
            cookie = cookie,
            status = "downloading",
            progress = 0,
            fileName = fileName
        )

        val rowId = downloadDao.upsertTask(task)

        // 关键：如果是更新旧任务，直接返回旧 id
        return when {
            history != null -> history.id
            rowId > 0L -> rowId
            else -> downloadDao.getTask(videoUrl)?.id
                ?: error("createTask: upsert 后未找到任务, videoUrl=$videoUrl")
        }
    }

    /** 观察下载进度 */
    fun observeTask(taskId: Long): Flow<DownloadTaskData?> {
        return downloadDao.observeTask(taskId)
    }

    /** 更新进度 */
    suspend fun updateProgress(taskId: Long, progress: Int) {
        downloadDao.updateProgress(taskId, progress, STATUS_DOWNLOADING)
    }

    /** 标记成功 */
    suspend fun markSuccess(taskId: Long, savePath: String) {
        downloadDao.updateStatus(taskId, STATUS_SUCCESS, savePath = savePath)
    }

    /** 标记失败 */
    suspend fun markFailed(taskId: Long, errorMsg: String) {
        downloadDao.updateStatus(taskId, STATUS_FAILED, errorMsg = errorMsg)
    }

    /** 获取任务 */
    suspend fun getTask(taskId: Long): DownloadTaskData? {
        return downloadDao.getTask(taskId)
    }

    /** 删除任务 */
    suspend fun deleteTask(taskId: Long) {
        downloadDao.deleteTask(taskId)
    }
}