package com.cla.clip.base.general.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "download_tasks",
    indices = [
        Index(value = ["video_url"]),
        Index(value = ["status"]),
        Index(value = ["create_time"]),
    ]
)
/**
 * 视频下载任务表实体。
 *
 * 任务由视频提取页或下载记录页创建，下载页和 DownloadVideoWorker 共同读写。
 * `video_url` 只作为普通索引用于按来源排查和后续聚合，同一地址允许产生多条记录，以保留每一次下载对应的本地媒体文件。
 */
data class DownloadTaskData(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    /** 数据库自增主键，作为导航到视频下载页和 Worker 输入的稳定任务 id。 */
    val id: Long = 0,

    @ColumnInfo(name = "video_url")
    /** 视频资源 URL，不能为空；同一 URL 可多次下载，每条记录都独立指向自己的输出 URI 或路径。 */
    val videoUrl: String,

    @ColumnInfo(name = "referer")
    /** 下载请求 Referer，来自 WebView 捕获的请求头；为空时下载 Worker 不发送该头。 */
    val referer: String? = null,

    @ColumnInfo(name = "user_agent")
    /** 下载请求 User-Agent，来自 WebView 探测上下文；为空时使用 OkHttp 默认行为。 */
    val userAgent: String? = null,

    @ColumnInfo(name = "cookie")
    /** 下载请求 Cookie，来自 WebView CookieManager；为空时不发送 Cookie，可能影响需要登录态或反盗链的站点。 */
    val cookie: String? = null,

    @ColumnInfo(name = "progress")
    /** 当前下载或合并进度百分比，约定范围为 0..100，UI 会再次收敛范围。 */
    val progress: Int = 0,

    @ColumnInfo(name = "status")
    /** 当前任务状态，只能使用 companion object 中的 STATUS_* 常量，避免 UI 映射失败。 */
    val status: String = STATUS_DOWNLOADING, // downloading, success, failed

    @ColumnInfo(name = "error_msg")
    /** 最近一次失败原因，成功或下载中可以为空；用户可见展示前需兜底通用文案。 */
    val errorMsg: String? = null,

    @ColumnInfo(name = "save_path")
    /** 成功或下载中占用的输出路径/URI 字符串，用于用户点击播放和异常清理半成品。 */
    val savePath: String? = null,

    /**
     * 当前下载任务占用中的 MediaStore 输出 URI（Android 10+）。
     * 用于进程异常退出后在下次启动时清理半成品。
     */
    @ColumnInfo(name = "pending_output_uri")
    val pendingOutputUri: String? = null,

    @ColumnInfo(name = "total_size")
    /** 预留的总字节数，目前直链下载主要通过响应体进度计算，数据库字段暂未完整回写。 */
    val totalSize: Long = 0,

    @ColumnInfo(name = "downloaded_size")
    /** 预留的已下载字节数，后续实现断点续传时可用于恢复进度；当前默认保持 0。 */
    val downloadedSize: Long = 0,

    @ColumnInfo(name = "create_time")
    /** 任务创建时间，单位毫秒；用于历史任务排序或排查问题。 */
    val createTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "update_time")
    /** 任务最近更新时间，单位毫秒；每次状态或进度变更时应同步更新。 */
    val updateTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "file_name")
    /** 保存视频时使用的基础文件名，不包含最终扩展名；由网页标题或剪贴板内容生成。 */
    val fileName: String,
) {
    companion object {
        /** 合并中 */
        const val STATUS_MERGING = "merging"

        /** 下载中 */
        const val STATUS_DOWNLOADING = "downloading"

        /** 下载成功 */
        const val STATUS_SUCCESS = "success"

        /** 下载失败 */
        const val STATUS_FAILED = "failed"
    }
}

@Dao
/**
 * 视频下载任务 DAO。
 *
 * 负责创建、观察、更新和删除 `download_tasks` 记录；下载 Worker 依赖这些方法回写进度与状态。
 */
interface DownloadDao {

    /** 插入或更新下载任务；Room 在更新已有任务时返回 -1，Repository 会负责兜底查询真实 id。 */
    @Upsert
    suspend fun upsertTask(task: DownloadTaskData): Long // 在更新旧数据时，返回-1

    /** 插入一条全新下载任务，用于保留同一视频地址的多次下载历史。 */
    @Insert
    suspend fun insertTask(task: DownloadTaskData): Long

    /** 按主键读取单个下载任务，Worker 启动时使用。 */
    @Query("SELECT * FROM download_tasks WHERE id = :id")
    suspend fun getTask(id: Long): DownloadTaskData?

    /** 按视频 URL 读取最近一条历史任务；仅作兼容旧调用和调试，不用于去重创建。 */
    @Query("SELECT * FROM download_tasks WHERE video_url = :url ORDER BY update_time DESC, id DESC LIMIT 1")
    suspend fun getTask(url: String): DownloadTaskData?

    /** 按更新时间倒序观察全部视频下载历史；保留给少量全量观察场景，下载记录列表优先使用分页接口。 */
    @Query("SELECT * FROM download_tasks ORDER BY update_time DESC, id DESC")
    fun observeHistory(): Flow<List<DownloadTaskData>>

    /** 按更新时间倒序分页加载视频下载历史，避免下载记录页一次性读取大量任务和本地媒体元信息。 */
    @Query("SELECT * FROM download_tasks ORDER BY update_time DESC, id DESC")
    fun pagingHistory(): PagingSource<Int, DownloadTaskData>

    /** 观察视频历史总数；只读取 COUNT，不加载完整记录，用于标题栏按钮和清空确认数量。 */
    @Query("SELECT COUNT(*) FROM download_tasks")
    fun observeHistoryCount(): Flow<Int>

    /** 观察仍在下载或合并的视频任务数量，用于清空当前分类前提示会先停止后台任务。 */
    @Query("SELECT COUNT(*) FROM download_tasks WHERE status = :downloadingStatus OR status = :mergingStatus")
    fun observeRunningHistoryCount(
        downloadingStatus: String = DownloadTaskData.STATUS_DOWNLOADING,
        mergingStatus: String = DownloadTaskData.STATUS_MERGING
    ): Flow<Int>

    /** 按当前排序读取全部视频历史 id；只在全选或清空时调用，避免常规浏览加载完整实体。 */
    @Query("SELECT id FROM download_tasks ORDER BY update_time DESC, id DESC")
    suspend fun getHistoryIds(): List<Long>

    /** 统计选中视频记录中仍在运行的任务数量，供删除确认文案判断是否需要提示停止下载。 */
    @Query("SELECT COUNT(*) FROM download_tasks WHERE id IN (:ids) AND (status = :downloadingStatus OR status = :mergingStatus)")
    suspend fun countRunningTasks(
        ids: Set<Long>,
        downloadingStatus: String = DownloadTaskData.STATUS_DOWNLOADING,
        mergingStatus: String = DownloadTaskData.STATUS_MERGING
    ): Int

    /** 批量读取待删除或待重新下载的任务，调用方会先过滤空集合，避免 SQL IN 空列表歧义。 */
    @Query("SELECT * FROM download_tasks WHERE id IN (:ids)")
    suspend fun getTasks(ids: Set<Long>): List<DownloadTaskData>

    /** 观察单个任务变化，视频下载页用它实时刷新进度和结果状态。 */
    @Query("SELECT * FROM download_tasks WHERE id = :id")
    fun observeTask(id: Long): Flow<DownloadTaskData?>

    /** 找出仍占用 MediaStore pending 输出的未成功任务，用于下次下载前清理半成品。 */
    @Query("SELECT * FROM download_tasks WHERE pending_output_uri IS NOT NULL AND status != :successStatus")
    suspend fun listTasksWithPendingOutput(successStatus: String = DownloadTaskData.STATUS_SUCCESS): List<DownloadTaskData>

    /** 更新下载或合并进度，同时写入状态和更新时间；调用方需要保证 progress 在 0..100。 */
    @Query("UPDATE download_tasks SET progress = :progress, status = :status, update_time = :updateTime WHERE id = :id")
    suspend fun updateProgress(id: Long, progress: Int, status: String, updateTime: Long = System.currentTimeMillis())

    /** 更新任务最终状态和错误信息；成功时 errorMsg 通常为空，失败时用于 UI 和通知展示。 */
    @Query(
        """
            UPDATE download_tasks 
            SET status = :status, error_msg = :errorMsg, update_time = :updateTime 
            WHERE id = :id
        """
    )
    suspend fun updateStatus(
        id: Long,
        status: String,
        errorMsg: String? = null,
        updateTime: Long = System.currentTimeMillis()
    )

    /** 记录当前任务占用的输出位置，失败或异常恢复时依赖它删除半成品。 */
    @Query("UPDATE download_tasks SET pending_output_uri = :pendingOutputUri, save_path =:savePath WHERE id = :id")
    suspend fun updatePath(id: Long, pendingOutputUri: String?, savePath: String?)

    /** 删除指定下载任务记录；不会自动删除已经保存成功的媒体文件。 */
    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteTask(id: Long)

    /** 精确删除选中的下载任务记录；不会影响剪贴板、图片批次或未选中的下载记录。 */
    @Query("DELETE FROM download_tasks WHERE id IN (:ids)")
    suspend fun deleteTasks(ids: Set<Long>)
}
