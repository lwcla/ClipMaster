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
import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "image_extract_batches",
    indices = [
        Index(value = ["status"]),
        Index(value = ["create_time"]),
    ]
)
/**
 * 网页图片批量提取任务的汇总记录。
 *
 * 这个实体保存一次网页图片提取/下载任务的整体状态，UI 通过 observeBatch 观察它来展示提取结果、下载进度、
 * 成功数量、过滤数量和失败数量。计数字段会被 Worker 在不同阶段持续更新，因此它承担跨页面和后台任务之间的状态契约。
 */
data class ImageExtractBatchData(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    /** 图片提取批次自增主键，UI、Repository 和 Worker 都通过它定位同一批图片。 */
    val id: Long = 0,

    @ColumnInfo(name = "page_url")
    /** 被提取图片的网页 URL，用于追踪来源和构造 Referer 兜底值。 */
    val pageUrl: String,

    @ColumnInfo(name = "page_name")
    /** 网页名称或标题，用作批量下载目录基础名，保存前会再做文件夹唯一化。 */
    val pageName: String,

    @ColumnInfo(name = "status")
    /** 批次状态，只能使用 STATUS_* 常量；UI 根据它切换选择、下载中、成功或失败展示。 */
    val status: String = STATUS_EXTRACTED,

    @ColumnInfo(name = "total_count")
    /** 当前批次需要下载的图片总数；用户确认选择后会更新为已选数量。 */
    val totalCount: Int,

    @ColumnInfo(name = "success_count")
    /** 已成功保存到目标目录的图片数量，由 Worker 持续回写。 */
    val successCount: Int = 0,

    @ColumnInfo(name = "failed_count")
    /** 真实下载或发布失败的图片数量，不包含用户取消和内容校验过滤。 */
    val failedCount: Int = 0,

    /** 被内容校验主动过滤的图片数量，例如透明占位图、1x1 跟踪像素或纯色错误图。 */
    @ColumnInfo(name = "filtered_count", defaultValue = "0")
    val filteredCount: Int = 0,

    @ColumnInfo(name = "output_dir")
    /** 批量图片最终保存目录或可打开 URI，下载完成后用于打开相册/文件夹。 */
    val outputDir: String? = null,

    @ColumnInfo(name = "error_msg")
    /** 批次级失败原因，可能为空；全部失败时 UI 可用它补充诊断信息。 */
    val errorMsg: String? = null,

    @ColumnInfo(name = "create_time")
    /** 批次创建时间，单位毫秒，用于排序和排查下载任务生命周期。 */
    val createTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "update_time")
    /** 批次最后更新时间，单位毫秒；状态、计数或输出目录变化时应同步更新。 */
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

        /** 候选图片全部被内容校验过滤，没有真实下载失败。 */
        const val STATUS_FILTERED = "filtered"

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
/**
 * 网页图片批量提取任务中的单张图片候选。
 *
 * 图片项会先作为待选候选展示给 UI，用户确认后未选中的记录会被删除，剩余记录再交给 Worker 下载。
 * referer、userAgent 和 cookie 是预览与下载共用的反盗链上下文，displayOrder 决定最终保存文件名顺序。
 */
data class ImageExtractItemData(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    /** 图片项自增主键，UI 选择状态和 Worker 更新单项状态都依赖它。 */
    val id: Long = 0,

    @ColumnInfo(name = "batch_id")
    /** 所属图片提取批次 id，外键指向 image_extract_batches.id，批次删除时会级联删除图片项。 */
    val batchId: Long,

    @ColumnInfo(name = "url")
    /** 图片资源 URL，同一批次内唯一；必须是可下载的网络图片地址。 */
    val url: String,

    @ColumnInfo(name = "referer")
    /** 图片请求 Referer，来自 DOM 页面或网络请求头；为空时预览/下载不发送 Referer。 */
    val referer: String? = null,

    @ColumnInfo(name = "user_agent")
    /** WebView User-Agent 或请求头 User-Agent，用于预览和下载时复用浏览器上下文。 */
    val userAgent: String? = null,

    @ColumnInfo(name = "cookie")
    /** WebView CookieManager 读取到的 Cookie，可能为空；用于需要登录态或反盗链校验的图片。 */
    val cookie: String? = null,

    @ColumnInfo(name = "display_order")
    /** 图片在网页中的展示顺序，同一批次内唯一；最终文件名按它保持网页顺序。 */
    val displayOrder: Int,

    @ColumnInfo(name = "width")
    /** DOM 或解码得到的图片宽度，单位像素；未知时为空，不影响下载。 */
    val width: Int? = null,

    @ColumnInfo(name = "height")
    /** DOM 或解码得到的图片高度，单位像素；未知时为空，不影响下载。 */
    val height: Int? = null,

    @ColumnInfo(name = "status")
    /** 图片项状态，只能使用 STATUS_* 常量；Worker 根据它区分待下载、已临时下载、成功、过滤和失败。 */
    val status: String = STATUS_PENDING,

    @ColumnInfo(name = "temp_path")
    /** 临时下载文件路径，发布到相册前使用；成功或失败清理后可以为空。 */
    val tempPath: String? = null,

    @ColumnInfo(name = "output_uri")
    /** 最终保存到 MediaStore 后的 URI 字符串；Android 10 以下直接写文件时可能为空。 */
    val outputUri: String? = null,

    @ColumnInfo(name = "final_name")
    /** 最终保存文件名，包含扩展名；发布成功后用于结果追踪和调试。 */
    val finalName: String? = null,

    @ColumnInfo(name = "error_msg")
    /** 单张图片失败原因，下载、校验或发布失败时写入；成功和待下载状态为空。 */
    val errorMsg: String? = null,
) {
    companion object {
        /** 等待 Worker 下载。 */
        const val STATUS_PENDING = "pending"

        /** 已下载到临时目录，等待按网页顺序发布。 */
        const val STATUS_TEMP_READY = "temp_ready"

        /** 已发布到相册/文件夹。 */
        const val STATUS_SUCCESS = "success"

        /** 被内容质量校验主动过滤，不应计入下载失败。 */
        const val STATUS_FILTERED = "filtered"

        /** 当前图片下载或发布失败。 */
        const val STATUS_FAILED = "failed"
    }
}

/**
 * 图片下载历史列表校验本地文件时使用的轻量投影。
 *
 * 下载记录页只需要最终媒体身份、最终文件名和网页顺序来判断图片是否还可读取；不读取 URL、请求头、尺寸和错误信息，
 * 可以避免一个批次包含几百张图片时把完整实体全部加载到内存后再做存在性判断。
 */
data class ImageHistoryFileRef(
    /** 图片项自增主键，用于调试和后续必要时定位单张图片，不直接参与 UI 展示。 */
    @ColumnInfo(name = "id")
    val id: Long,

    /** 最终保存到 MediaStore 后的 URI；Android 10 以下直接写文件时可能为空。 */
    @ColumnInfo(name = "output_uri")
    val outputUri: String?,

    /** 最终公开文件名，旧系统需要用它和批次目录组合定位图片文件。 */
    @ColumnInfo(name = "final_name")
    val finalName: String?,

    /** 图片在网页中的展示顺序，列表缩略图需要继续保持用户下载时的顺序。 */
    @ColumnInfo(name = "display_order")
    val displayOrder: Int,
)

@Dao
/**
 * 图片提取 DAO。
 *
 * 管理批次和图片项的事务性写入、观察、状态更新和用户选择过滤；Repository 和 DownloadImagesWorker 共享这些契约。
 */
interface ImageExtractDao {

    /** 插入图片提取批次，返回自增批次 id。 */
    @Insert
    suspend fun insertBatch(batch: ImageExtractBatchData): Long

    /**
     * 备份恢复批量写入图片下载批次。
     *
     * 批次 id 会保留在备份包中；重复恢复时通过 Upsert 覆盖同一批次，保证图片项外键关系仍可重建。
     */
    @androidx.room.Upsert
    suspend fun upsertBatchesForBackup(batches: List<ImageExtractBatchData>)

    /** 批量插入图片项；冲突时忽略，避免同一批次重复 URL 或顺序导致任务失败。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<ImageExtractItemData>)

    /**
     * 备份恢复批量写入图片项。
     *
     * 图片项使用 Upsert 而不是 IGNORE，确保同一备份重复恢复时可以修正旧数据，但 mapper 会清理临时路径和 Cookie。
     */
    @androidx.room.Upsert
    suspend fun upsertItemsForBackup(items: List<ImageExtractItemData>)

    /** 更新完整批次对象，适合 Repository 已经拿到最新对象后整体回写。 */
    @Update
    suspend fun updateBatch(batch: ImageExtractBatchData)

    /** 按 id 读取批次，Worker 启动和 Repository 业务判断时使用。 */
    @Query("SELECT * FROM image_extract_batches WHERE id = :batchId")
    suspend fun getBatch(batchId: Long): ImageExtractBatchData?

    /**
     * 备份导出读取全部图片提取/下载批次。
     *
     * 未确认下载的 extracted 批次也保留在数据库中，因此第一版作为元数据一起导出，后续可按产品决策裁剪。
     */
    @Query("SELECT * FROM image_extract_batches ORDER BY id ASC")
    suspend fun loadAllBatchesForBackup(): List<ImageExtractBatchData>

    /**
     * 备份导出读取全部图片项。
     *
     * 具体敏感字段过滤由备份 mapper 负责，DAO 只提供一致性快照。
     */
    @Query("SELECT * FROM image_extract_items ORDER BY id ASC")
    suspend fun loadAllItemsForBackup(): List<ImageExtractItemData>

    /**
     * 备份恢复前按 id 批量读取已有图片批次。
     *
     * 用于冲突合并时保护本地较新的批次状态。
     */
    @Query("SELECT * FROM image_extract_batches WHERE id IN (:batchIds)")
    suspend fun loadBatchesByIdsForBackup(batchIds: List<Long>): List<ImageExtractBatchData>

    /**
     * 备份恢复前按 id 批量读取已有图片项。
     *
     * 用于重复恢复时跳过本地较新的单项结果，避免旧备份覆盖新下载结果。
     */
    @Query("SELECT * FROM image_extract_items WHERE id IN (:itemIds)")
    suspend fun loadItemsByIdsForBackup(itemIds: List<Long>): List<ImageExtractItemData>

    /** 批量读取选中的图片下载批次，供删除、清空和重新下载前组装精确操作范围。 */
    @Query("SELECT * FROM image_extract_batches WHERE id IN (:batchIds)")
    suspend fun getBatches(batchIds: Set<Long>): List<ImageExtractBatchData>

    /** 观察批次变化，图片提取页用它刷新下载进度和结果统计。 */
    @Query("SELECT * FROM image_extract_batches WHERE id = :batchId")
    fun observeBatch(batchId: Long): Flow<ImageExtractBatchData?>

    /** 观察图片下载历史；保留给少量全量观察场景，下载记录列表优先使用分页接口。 */
    @Query("SELECT * FROM image_extract_batches WHERE status != :extractedStatus ORDER BY update_time DESC, id DESC")
    fun observeHistory(extractedStatus: String = ImageExtractBatchData.STATUS_EXTRACTED): Flow<List<ImageExtractBatchData>>

    /** 分页加载图片下载历史；未确认下载的提取候选批次不进入下载记录页，避免一次性读取大量批次。 */
    @Query("SELECT * FROM image_extract_batches WHERE status != :extractedStatus ORDER BY update_time DESC, id DESC")
    fun pagingHistory(extractedStatus: String = ImageExtractBatchData.STATUS_EXTRACTED): PagingSource<Int, ImageExtractBatchData>

    /** 观察图片下载历史总数；只读取 COUNT，用于标题栏按钮和清空确认数量。 */
    @Query("SELECT COUNT(*) FROM image_extract_batches WHERE status != :extractedStatus")
    fun observeHistoryCount(extractedStatus: String = ImageExtractBatchData.STATUS_EXTRACTED): Flow<Int>

    /** 观察仍在下载的图片批次数量，用于清空当前分类前提示会先停止后台任务。 */
    @Query("SELECT COUNT(*) FROM image_extract_batches WHERE status = :downloadingStatus")
    fun observeRunningHistoryCount(downloadingStatus: String = ImageExtractBatchData.STATUS_DOWNLOADING): Flow<Int>

    /** 按当前排序读取全部图片历史批次 id；只在全选或清空时调用，避免常规浏览加载完整实体。 */
    @Query("SELECT id FROM image_extract_batches WHERE status != :extractedStatus ORDER BY update_time DESC, id DESC")
    suspend fun getHistoryIds(extractedStatus: String = ImageExtractBatchData.STATUS_EXTRACTED): List<Long>

    /** 统计选中图片批次中仍在下载的任务数量，供删除确认文案判断是否需要提示停止下载。 */
    @Query("SELECT COUNT(*) FROM image_extract_batches WHERE id IN (:batchIds) AND status = :downloadingStatus")
    suspend fun countRunningBatches(
        batchIds: Set<Long>,
        downloadingStatus: String = ImageExtractBatchData.STATUS_DOWNLOADING
    ): Int

    /** 按网页顺序读取批次内全部图片项，Worker 下载前使用。 */
    @Query("SELECT * FROM image_extract_items WHERE batch_id = :batchId ORDER BY display_order ASC")
    suspend fun getItems(batchId: Long): List<ImageExtractItemData>

    /** 读取下载记录页存在性校验所需的成功图片轻量字段，避免历史列表加载完整图片项实体。 */
    @Query(
        """
            SELECT id, output_uri, final_name, display_order
            FROM image_extract_items
            WHERE batch_id = :batchId AND status = :successStatus
            ORDER BY display_order ASC
        """
    )
    suspend fun getHistoryFileRefs(
        batchId: Long,
        successStatus: String = ImageExtractItemData.STATUS_SUCCESS
    ): List<ImageHistoryFileRef>

    /** 观察批次图片项列表，选择页用它展示候选网格。 */
    @Query("SELECT * FROM image_extract_items WHERE batch_id = :batchId ORDER BY display_order ASC")
    fun observeItems(batchId: Long): Flow<List<ImageExtractItemData>>

    /** 更新批次状态、统计和输出目录；Worker 每次状态变化都通过这里回写 UI 可观察结果。 */
    @Query(
        """
            UPDATE image_extract_batches
            SET status = :status, success_count = :successCount, failed_count = :failedCount,
                filtered_count = :filteredCount,
                output_dir = :outputDir, error_msg = :errorMsg, update_time = :updateTime
            WHERE id = :batchId
        """
    )
    suspend fun updateBatchStatus(
        batchId: Long,
        status: String,
        successCount: Int,
        failedCount: Int,
        filteredCount: Int,
        outputDir: String?,
        errorMsg: String?,
        updateTime: Long = System.currentTimeMillis()
    )

    /** 更新单张图片状态和路径信息；下载临时文件、发布成功、过滤或失败时调用。 */
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

    /** 删除某批次全部图片项，通常在批次重建或用户未选择任何图片时使用。 */
    @Query("DELETE FROM image_extract_items WHERE batch_id = :batchId")
    suspend fun deleteItems(batchId: Long)

    /** 删除用户未选择的图片项，只保留 keepItemIds 中的候选供 Worker 下载。 */
    @Query("DELETE FROM image_extract_items WHERE batch_id = :batchId AND id NOT IN (:keepItemIds)")
    suspend fun deleteItemsExcept(batchId: Long, keepItemIds: Set<Long>)

    /** 更新批次总数，用户确认选择后用于让下载进度总数与实际下载数量一致。 */
    @Query(
        """
            UPDATE image_extract_batches
            SET total_count = :totalCount, update_time = :updateTime
            WHERE id = :batchId
        """
    )
    suspend fun updateBatchTotalCount(
        batchId: Long,
        totalCount: Int,
        updateTime: Long = System.currentTimeMillis()
    )

    /** 删除批次记录；图片项会因外键级联同步删除。 */
    @Query("DELETE FROM image_extract_batches WHERE id = :batchId")
    suspend fun deleteBatch(batchId: Long)

    /** 精确删除选中的图片下载批次；图片项仅通过外键级联删除这些批次下的数据。 */
    @Query("DELETE FROM image_extract_batches WHERE id IN (:batchIds)")
    suspend fun deleteBatches(batchIds: Set<Long>)

    /** 在同一事务中创建批次并写入图片项，保证 Worker 不会看到只有批次没有图片项的中间状态。 */
    @Transaction
    suspend fun replaceBatchItems(batch: ImageExtractBatchData, items: List<ImageExtractItemData>): Long {
        val batchId = insertBatch(batch)
        insertItems(items.map { it.copy(batchId = batchId) })
        return batchId
    }

    /** 在同一事务中删除未选图片并更新批次总数，保证 UI 和 Worker 读取到一致的用户选择结果。 */
    @Transaction
    suspend fun keepSelectedItems(batchId: Long, keepItemIds: Set<Long>) {
        if (keepItemIds.isEmpty()) {
            deleteItems(batchId)
        } else {
            deleteItemsExcept(batchId, keepItemIds)
        }
        updateBatchTotalCount(batchId, keepItemIds.size)
    }
}
