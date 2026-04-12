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
        Index(value = ["video_url"], unique = true), // unique = true 的作用是：该索引列的值必须全表唯一，不能重复。 数据库层会创建唯一索引，插入/更新产生重复值时会报冲突错误
        Index(value = ["status"]),
        Index(value = ["create_time"]),
    ]
)
data class DownloadTaskData(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

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
    val updateTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "file_name")
    val fileName: String,
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
    suspend fun upsertTask(task: DownloadTaskData): Long // 在更新旧数据时，返回-1

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    suspend fun getTask(id: Long): DownloadTaskData?

    @Query("SELECT * FROM download_tasks WHERE video_url = :url")
    suspend fun getTask(url: String): DownloadTaskData?

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    fun observeTask(id: Long): Flow<DownloadTaskData?>

    @Query("UPDATE download_tasks SET progress = :progress, status = :status, update_time = :updateTime WHERE id = :id")
    suspend fun updateProgress(id: Long, progress: Int, status: String, updateTime: Long = System.currentTimeMillis())

    @Query("UPDATE download_tasks SET status = :status, error_msg = :errorMsg, save_path = :savePath, update_time = :updateTime WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, errorMsg: String? = null, savePath: String? = null, updateTime: Long = System.currentTimeMillis())

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteTask(id: Long)
}