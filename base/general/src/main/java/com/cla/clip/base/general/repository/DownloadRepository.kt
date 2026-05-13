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
/**
 * 视频下载任务仓库。
 *
 * 封装 `download_tasks` 表的创建、观察和状态更新，作为视频提取页、下载页和 DownloadVideoWorker 之间的唯一数据契约。
 */
class DownloadRepository @Inject constructor(
    private val downloadDao: DownloadDao
) {
    companion object {
        /** 仓库日志标签，当前保留给后续诊断任务创建和状态回写。 */
        private const val TAG = "DownloadRepository"
    }

    /**
     * 创建或复用视频下载任务。
     *
     * `videoUrl` 是唯一键；如果历史任务已存在，会更新请求上下文、文件名和 pending 输出信息，并返回旧任务 id。
     * 这样同一视频地址不会在数据库里产生多条任务，同时能用最新 Referer/User-Agent/Cookie 重试下载。
     */
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

    /** 观察下载任务变化，下载页用它实时映射为 UI 状态。 */
    fun observeTask(taskId: Long): Flow<DownloadTaskData?> {
        return downloadDao.observeTask(taskId)
    }

    /** 更新普通下载进度，状态同步标记为 downloading。 */
    suspend fun updateProgress(taskId: Long, progress: Int) {
        downloadDao.updateProgress(taskId, progress, STATUS_DOWNLOADING)
    }

    /** 更新 M3U8 合并进度，合并进度单独一个状态，避免和下载进度混淆。 */
    suspend fun updateMergeProgress(taskId: Long, progress: Int) {
        downloadDao.updateProgress(taskId, progress, STATUS_MERGING)
    }

    /** 记录当前下载占用的 MediaStore 输出 URI（用于异常恢复时清理半成品） */
    suspend fun markPath(taskId: Long, uri: String?, savePath: String?) {
        downloadDao.updatePath(taskId, uri, savePath)
    }

    /** 标记任务下载成功；输出路径应已通过 markPath 写入。 */
    suspend fun markSuccess(taskId: Long) {
        downloadDao.updateStatus(taskId, STATUS_SUCCESS)
    }

    /** 标记任务失败，并使用字符串资源兜底错误文案，避免 UI 展示空错误。 */
    suspend fun markFailed(context: Context, taskId: Long, errorMsg: String?) {
        downloadDao.updateStatus(id = taskId, status = STATUS_FAILED, errorMsg = errorMsg ?: context.getString(R.string.base_general_download_failed))
    }

    /** 按 id 获取任务，Worker 启动时用来读取 URL、请求头和文件名。 */
    suspend fun getTask(taskId: Long): DownloadTaskData? {
        return downloadDao.getTask(taskId)
    }

    /** 删除任务记录；不会删除已经发布到媒体库的视频文件。 */
    suspend fun deleteTask(taskId: Long) {
        downloadDao.deleteTask(taskId)
    }
}
