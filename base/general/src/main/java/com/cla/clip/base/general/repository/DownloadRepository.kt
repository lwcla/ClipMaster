package com.cla.clip.base.general.repository

import android.content.Context
import androidx.paging.PagingSource
import com.cla.clip.base.general.R
import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.base.general.dao.DownloadDao
import com.cla.clip.base.general.dao.DownloadTaskData
import com.cla.clip.base.general.dao.VideoMediaReferenceUpdate
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
     * 创建视频下载任务。
     *
     * 每次用户确认下载都插入新记录，不再按 `videoUrl` 去重；这样下载记录页可以保留同一地址的多次下载结果，
     * 每条记录都独立指向本次创建的 MediaStore URI 或旧系统文件路径。
     */
    suspend fun createTask(
        videoUrl: String,
        fileName: String,
        referer: String? = null,
        userAgent: String? = null,
        cookie: String? = null,
    ): Long {
        val now = System.currentTimeMillis()
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

        val id = downloadDao.insertTask(task)
        AppSetting.markBackupDirty()
        return id
    }

    /** 观察全部视频下载历史，按最近更新倒序返回，供下载记录页展示和多选管理。 */
    fun observeHistory(): Flow<List<DownloadTaskData>> {
        return downloadDao.observeHistory()
    }

    /** 分页加载视频下载历史，下载记录页使用它避免一次性读取全部任务和媒体元信息。 */
    fun pagingHistory(): PagingSource<Int, DownloadTaskData> {
        return downloadDao.pagingHistory()
    }

    /** 观察视频历史总数；用于标题栏动作可用性和清空确认，不触发实体全量加载。 */
    fun observeHistoryCount(): Flow<Int> {
        return downloadDao.observeHistoryCount()
    }

    /** 观察进行中的视频历史数量；用于清空当前分类前提示会先停止后台任务。 */
    fun observeRunningHistoryCount(): Flow<Int> {
        return downloadDao.observeRunningHistoryCount()
    }

    /** 按当前排序读取全部视频历史 id；只在全选或清空时调用。 */
    suspend fun getHistoryIds(): List<Long> {
        return downloadDao.getHistoryIds()
    }

    /** 统计选中视频任务中仍在下载或合并的数量；用于删除确认提示。 */
    suspend fun countRunningTasks(taskIds: Set<Long>): Int {
        if (taskIds.isEmpty()) return 0
        return downloadDao.countRunningTasks(taskIds)
    }

    /**
     * 基于旧任务创建一条重新下载记录。
     *
     * 只复制 URL、请求上下文和文件名，不复制进度、状态、错误和输出路径，确保新任务拥有独立生命周期和本地文件身份。
     */
    suspend fun createRetryTask(sourceTaskId: Long): Long? {
        val source = downloadDao.getTask(sourceTaskId) ?: return null
        return createTask(
            videoUrl = source.videoUrl,
            fileName = source.fileName,
            referer = source.referer,
            userAgent = source.userAgent,
            cookie = source.cookie
        )
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
        AppSetting.markBackupDirty()
    }

    /** 标记任务下载成功；输出路径应已通过 markPath 写入。 */
    suspend fun markSuccess(taskId: Long) {
        downloadDao.updateStatus(taskId, STATUS_SUCCESS)
        AppSetting.markBackupDirty()
    }

    /** 标记任务失败，并使用字符串资源兜底错误文案，避免 UI 展示空错误。 */
    suspend fun markFailed(context: Context, taskId: Long, errorMsg: String?) {
        downloadDao.updateStatus(id = taskId, status = STATUS_FAILED, errorMsg = errorMsg ?: context.getString(R.string.base_general_download_failed))
        AppSetting.markBackupDirty()
    }

    /** 按 id 获取任务，Worker 启动时用来读取 URL、请求头和文件名。 */
    suspend fun getTask(taskId: Long): DownloadTaskData? {
        return downloadDao.getTask(taskId)
    }

    /** 统计恢复后媒体重新定位需要检查的成功视频数量；只做数据库预估，不访问媒体库。 */
    suspend fun countVideosForMediaRelocation(): Int {
        return downloadDao.countSuccessfulTasksForMediaRelocation()
    }

    /** 按 id 分页读取成功视频，供 app 层媒体定位器验证引用和扫描候选。 */
    suspend fun loadVideosForMediaRelocation(lastId: Long, limit: Int): List<DownloadTaskData> {
        return downloadDao.loadSuccessfulTasksForMediaRelocation(lastId, limit)
    }

    /** 按 chunk 写回高可信重新定位出的视频引用；不修改下载状态。 */
    suspend fun updateVideoMediaReferencesForRelocation(updates: List<VideoMediaReferenceUpdate>) {
        if (updates.isEmpty()) return
        downloadDao.updateMediaReferencesForRelocation(updates)
        AppSetting.markBackupDirty()
    }

    /** 删除任务记录；不会删除已经发布到媒体库的视频文件。 */
    suspend fun deleteTask(taskId: Long) {
        downloadDao.deleteTask(taskId)
        AppSetting.markBackupDirty()
    }

    /** 批量读取任务，用于删除前取消 Worker、清理精确关联的媒体项或生成结果汇总。 */
    suspend fun getTasks(taskIds: Set<Long>): List<DownloadTaskData> {
        if (taskIds.isEmpty()) return emptyList()
        return downloadDao.getTasks(taskIds)
    }

    /** 精确删除选中任务记录；调用方负责在需要时先处理本地文件和 Worker 取消。 */
    suspend fun deleteTasks(taskIds: Set<Long>) {
        if (taskIds.isEmpty()) return
        downloadDao.deleteTasks(taskIds)
        AppSetting.markBackupDirty()
    }
}
