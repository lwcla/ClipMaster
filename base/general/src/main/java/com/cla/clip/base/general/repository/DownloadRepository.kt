package com.cla.clip.base.general.repository

import android.content.Context
import com.cla.clip.base.general.R
import com.cla.clip.base.general.dao.DownloadDao
import com.cla.clip.base.general.dao.DownloadTaskData
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_DOWNLOADING
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_FAILED
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_MERGING
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_SUCCESS
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepository @Inject constructor(
    private val downloadDao: DownloadDao
) {
    companion object {
        private const val TAG = "DownloadRepository"
    }

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
            fileName = fileName,
            pendingOutputUri = null
        ) ?: DownloadTaskData(
            videoUrl = videoUrl,
            referer = referer,
            userAgent = userAgent,
            cookie = cookie,
            status = STATUS_DOWNLOADING,
            progress = 0,
            pendingOutputUri = null,
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

    /** 更新合并m3u8的进度，合并进度单独一个状态，避免和下载进度混淆 */
    suspend fun updateMergeProgress(taskId: Long, progress: Int) {
        downloadDao.updateProgress(taskId, progress, STATUS_MERGING)
    }

    /** 记录当前下载占用的 MediaStore 输出 URI（用于异常恢复时清理半成品） */
    suspend fun markPath(taskId: Long, uri: String?, savePath: String?) {
        downloadDao.updatePath(taskId, uri, savePath)
    }

    /** 标记成功 */
    suspend fun markSuccess(taskId: Long) {
        downloadDao.updateStatus(taskId, STATUS_SUCCESS)
    }

    /** 标记失败 */
    suspend fun markFailed(context: Context, taskId: Long, errorMsg: String?) {
        downloadDao.updateStatus(id = taskId, status = STATUS_FAILED, errorMsg = errorMsg ?: context.getString(R.string.base_general_download_failed))
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
