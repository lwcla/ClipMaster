package com.cla.clip.base.general.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "download_tasks",
    indices = [
        Index(value = ["task_id"], unique = true),
        Index(value = ["status"]),
        Index(value = ["create_time"])
    ]
)
data class DownloadTaskData(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "task_id")
    val taskId: String,

    @ColumnInfo(name = "video_url")
    val videoUrl: String,

    @ColumnInfo(name = "referer")
    val referer: String? = null,

    @ColumnInfo(name = "user_agent")
    val userAgent: String? = null,

    @ColumnInfo(name = "cookie")
    val cookie: String? = null,

    @ColumnInfo(name = "progress")
    val progress: Int = 0,

    @ColumnInfo(name = "status")
    val status: String = STATUS_DOWNLOADING, // downloading, success, failed

    @ColumnInfo(name = "error_msg")
    val errorMsg: String? = null,

    @ColumnInfo(name = "save_path")
    val savePath: String? = null,

    @ColumnInfo(name = "total_size")
    val totalSize: Long = 0,

    @ColumnInfo(name = "downloaded_size")
    val downloadedSize: Long = 0,

    @ColumnInfo(name = "create_time")
    val createTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "update_time")
    val updateTime: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"
    }
}

@Dao
interface DownloadDao {

    @Upsert
    suspend fun upsertTask(task: DownloadTaskData): Long

    @Query("SELECT * FROM download_tasks WHERE task_id = :taskId")
    suspend fun getTask(taskId: String): DownloadTaskData?

    @Query("SELECT * FROM download_tasks WHERE task_id = :taskId")
    fun observeTask(taskId: String): Flow<DownloadTaskData?>

    @Query("UPDATE download_tasks SET progress = :progress, status = :status, update_time = :updateTime WHERE task_id = :taskId")
    suspend fun updateProgress(taskId: String, progress: Int, status: String, updateTime: Long = System.currentTimeMillis())

    @Query("UPDATE download_tasks SET status = :status, error_msg = :errorMsg, save_path = :savePath, update_time = :updateTime WHERE task_id = :taskId")
    suspend fun updateStatus(taskId: String, status: String, errorMsg: String? = null, savePath: String? = null, updateTime: Long = System.currentTimeMillis())

    @Query("DELETE FROM download_tasks WHERE task_id = :taskId")
    suspend fun deleteTask(taskId: String)
}