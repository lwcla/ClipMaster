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
    /** 仅提交下载任务业务状态，不连接备份运行态。 */
    private val downloadDao: DownloadDao,
) {
    companion object {
        /** 仓库日志标签，当前保留给后续诊断任务创建和状态回写。 */
        private const val TAG = "DownloadRepository"
    }

    /**
     * 创建视频下载任务。
     *
     * 每次用户确认下载都插入新任务，不按 `videoUrl` 复用旧输出，
     * 每条记录都独立指向本次创建的 MediaStore URI 或旧系统文件路径。
     */
    suspend fun createTask(
        videoUrl: String,
        fileName: String,
        referer: String? = null,
        userAgent: String? = null,
        cookie: String? = null,
    ): Long {
        /** 同一新任务的创建和更新时间。 */
        val now = System.currentTimeMillis()
        /** 新记录只保存本次下载输入，不复用旧输出身份。 */
        val task = DownloadTaskData(
            videoUrl = videoUrl,
            referer = referer,
            userAgent = userAgent,
            cookie = cookie,
            status = STATUS_DOWNLOADING,
            progress = 0,
            pendingOutputUri = null,
            createTime = now,
            updateTime = now,
            fileName = fileName
        )

        /** 已提交记录的数据库身份，提交失败直接传播。 */
        val id = downloadDao.insertTask(task)
        return id
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

}
