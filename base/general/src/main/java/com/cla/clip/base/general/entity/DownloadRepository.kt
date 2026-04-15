package com.cla.clip.base.general.entity

import android.content.Context
import android.net.Uri
import com.cla.clip.base.general.dao.DownloadDao
import com.cla.clip.base.general.dao.DownloadTaskData
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_DOWNLOADING
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_FAILED
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_MERGING
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_SUCCESS
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.net.toUri

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
            status = "downloading",
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
    suspend fun markPendingOutputUri(taskId: Long, pendingOutputUri: String?) {
        downloadDao.updatePendingOutputUri(taskId, pendingOutputUri)
    }

    /** 标记成功 */
    suspend fun markSuccess(taskId: Long, savePath: String) {
        downloadDao.updateStatus(taskId, STATUS_SUCCESS, savePath = savePath, pendingOutputUri = null)
    }

    /** 标记失败 */
    suspend fun markFailed(taskId: Long, errorMsg: String) {
        downloadDao.updateStatus(taskId, STATUS_FAILED, errorMsg = errorMsg, pendingOutputUri = null)
    }

    /** 获取任务 */
    suspend fun getTask(taskId: Long): DownloadTaskData? {
        return downloadDao.getTask(taskId)
    }

    /** 删除任务 */
    suspend fun deleteTask(taskId: Long) {
        downloadDao.deleteTask(taskId)
    }

    /**
     * 清理未完成任务遗留的 pending 输出文件。
     *
     * @return 实际清理的条目数
     */
    suspend fun cleanupOrphanPendingOutputs(context: Context, staleThresholdMs: Long = 10 * 60 * 1000L): Int {
        val now = System.currentTimeMillis()
        val tasks = downloadDao.listTasksWithPendingOutput()
        if (tasks.isEmpty()) return 0

        var cleaned = 0
        tasks.forEach { task ->
            val pendingUri = task.pendingOutputUri.orEmpty()
            if (pendingUri.isBlank()) return@forEach

            val isStale = now - task.updateTime >= staleThresholdMs
            if (!isStale) return@forEach

            runCatching {
                context.contentResolver.delete(pendingUri.toUri(), null, null)
            }.onFailure {
                logE(TAG, it) { "cleanupOrphanPendingOutputs: 删除遗留文件失败 uri=$pendingUri taskId=${task.id}" }
            }

            downloadDao.updateStatus(
                id = task.id,
                status = STATUS_FAILED,
                errorMsg = "App process restarted, pending output was cleaned",
                savePath = null,
                pendingOutputUri = null
            )
            cleaned++
        }

        if (cleaned > 0) {
            logD(TAG) { "cleanupOrphanPendingOutputs: 已清理遗留 pending 输出 $cleaned 条" }
        }
        return cleaned
    }
}
