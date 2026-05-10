package com.cla.clip.base.general.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "image_extract_batches",
    indices = [
        Index(value = ["status"]),
        Index(value = ["create_time"]),
    ]
)
data class ImageExtractBatchData(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "page_url")
    val pageUrl: String,

    @ColumnInfo(name = "page_name")
    val pageName: String,

    @ColumnInfo(name = "status")
    val status: String = STATUS_EXTRACTED,

    @ColumnInfo(name = "total_count")
    val totalCount: Int,

    @ColumnInfo(name = "success_count")
    val successCount: Int = 0,

    @ColumnInfo(name = "failed_count")
    val failedCount: Int = 0,

    @ColumnInfo(name = "output_dir")
    val outputDir: String? = null,

    @ColumnInfo(name = "error_msg")
    val errorMsg: String? = null,

    @ColumnInfo(name = "create_time")
    val createTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "update_time")
    val updateTime: Long = System.currentTimeMillis(),
) {
    companion object {
        /** 已提取图片候选，等待用户确认下载。 */
        const val STATUS_EXTRACTED = "extracted"

        /** Worker 正在下载或发布图片。 */
        const val STATUS_DOWNLOADING = "downloading"

        /** 所有图片都保存成功。 */
        const val STATUS_SUCCESS = "success"

        /** 只有部分图片保存成功。 */
        const val STATUS_PARTIAL_SUCCESS = "partial_success"

        /** 全部图片都保存失败。 */
        const val STATUS_FAILED = "failed"
    }
}

@Entity(
    tableName = "image_extract_items",
    foreignKeys = [
        ForeignKey(
            entity = ImageExtractBatchData::class,
            parentColumns = ["id"],
            childColumns = ["batch_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["batch_id", "display_order"], unique = true),
        Index(value = ["batch_id", "url"], unique = true),
        Index(value = ["status"]),
    ]
)
data class ImageExtractItemData(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "batch_id")
    val batchId: Long,

    @ColumnInfo(name = "url")
    val url: String,

    @ColumnInfo(name = "referer")
    val referer: String? = null,

    @ColumnInfo(name = "user_agent")
    val userAgent: String? = null,

    @ColumnInfo(name = "cookie")
    val cookie: String? = null,

    @ColumnInfo(name = "display_order")
    val displayOrder: Int,

    @ColumnInfo(name = "width")
    val width: Int? = null,

    @ColumnInfo(name = "height")
    val height: Int? = null,

    @ColumnInfo(name = "status")
    val status: String = STATUS_PENDING,

    @ColumnInfo(name = "temp_path")
    val tempPath: String? = null,

    @ColumnInfo(name = "output_uri")
    val outputUri: String? = null,

    @ColumnInfo(name = "final_name")
    val finalName: String? = null,

    @ColumnInfo(name = "error_msg")
    val errorMsg: String? = null,
) {
    companion object {
        /** 等待 Worker 下载。 */
        const val STATUS_PENDING = "pending"

        /** 已下载到临时目录，等待按网页顺序发布。 */
        const val STATUS_TEMP_READY = "temp_ready"

        /** 已发布到相册/文件夹。 */
        const val STATUS_SUCCESS = "success"

        /** 当前图片下载或发布失败。 */
        const val STATUS_FAILED = "failed"
    }
}

@Dao
interface ImageExtractDao {

    @Insert
    suspend fun insertBatch(batch: ImageExtractBatchData): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<ImageExtractItemData>)

    @Update
    suspend fun updateBatch(batch: ImageExtractBatchData)

    @Query("SELECT * FROM image_extract_batches WHERE id = :batchId")
    suspend fun getBatch(batchId: Long): ImageExtractBatchData?

    @Query("SELECT * FROM image_extract_batches WHERE id = :batchId")
    fun observeBatch(batchId: Long): Flow<ImageExtractBatchData?>

    @Query("SELECT * FROM image_extract_items WHERE batch_id = :batchId ORDER BY display_order ASC")
    suspend fun getItems(batchId: Long): List<ImageExtractItemData>

    @Query(
        """
            UPDATE image_extract_batches
            SET status = :status, success_count = :successCount, failed_count = :failedCount,
                output_dir = :outputDir, error_msg = :errorMsg, update_time = :updateTime
            WHERE id = :batchId
        """
    )
    suspend fun updateBatchStatus(
        batchId: Long,
        status: String,
        successCount: Int,
        failedCount: Int,
        outputDir: String?,
        errorMsg: String?,
        updateTime: Long = System.currentTimeMillis()
    )

    @Query(
        """
            UPDATE image_extract_items
            SET status = :status, temp_path = :tempPath, output_uri = :outputUri,
                final_name = :finalName, error_msg = :errorMsg
            WHERE id = :itemId
        """
    )
    suspend fun updateItemStatus(
        itemId: Long,
        status: String,
        tempPath: String?,
        outputUri: String?,
        finalName: String?,
        errorMsg: String?
    )

    @Query("DELETE FROM image_extract_items WHERE batch_id = :batchId")
    suspend fun deleteItems(batchId: Long)

    @Query("DELETE FROM image_extract_batches WHERE id = :batchId")
    suspend fun deleteBatch(batchId: Long)

    @Transaction
    suspend fun replaceBatchItems(batch: ImageExtractBatchData, items: List<ImageExtractItemData>): Long {
        val batchId = insertBatch(batch)
        insertItems(items.map { it.copy(batchId = batchId) })
        return batchId
    }
}
