package com.cla.clip.base.general.repository

import androidx.paging.PagingSource
import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.base.general.dao.ImageExtractDao
import com.cla.clip.base.general.dao.ImageHistoryFileRef
import com.cla.clip.base.general.dao.ImageExtractItemData
import com.cla.clip.base.general.dao.ImageMediaReferenceUpdate
import com.cla.clip.base.general.dao.ImageRelocationBatchSummary
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 图片候选数据。
 *
 * 由图片提取页从 DOM 扫描和网络拦截合并得到，保存到数据库前先以轻量结构传递；字段会一一映射到 ImageExtractItemData。
 */
data class ImageCandidateData(
    /** 图片资源 URL，同一批次内会按 URL 去重，不能为空。 */
    val url: String,

    /** 预览和下载使用的 Referer，可能为空；来自 DOM 页面 URL 或网络请求头。 */
    val referer: String?,

    /** 预览和下载使用的 User-Agent，可能为空；来自 WebView 设置或请求头。 */
    val userAgent: String?,

    /** 预览和下载使用的 Cookie，可能为空；来自 WebView CookieManager。 */
    val cookie: String?,

    /** 图片展示顺序，DOM 候选优先保持网页顺序，网络补充候选排在后面。 */
    val displayOrder: Int,

    /** DOM 读取到的图片宽度，单位像素；未知时为空。 */
    val width: Int?,

    /** DOM 读取到的图片高度，单位像素；未知时为空。 */
    val height: Int?,
)

@Singleton
/**
 * 图片提取仓库。
 *
 * 负责把页面提取到的图片候选写入批次/图片项表，并为 UI 和 Worker 提供观察、读取和状态回写能力。
 */
class ImageExtractRepository @Inject constructor(
    private val imageExtractDao: ImageExtractDao
) {

    /** 保存本次网页提取结果，先落库再交给 Worker 下载，避免页面退出后候选丢失。 */
    suspend fun createBatch(pageUrl: String, pageName: String, candidates: List<ImageCandidateData>): Long {
        val batch = ImageExtractBatchData(
            pageUrl = pageUrl,
            pageName = pageName,
            totalCount = candidates.size
        )
        val items = candidates.map { candidate ->
            ImageExtractItemData(
                batchId = 0,
                url = candidate.url,
                referer = candidate.referer,
                userAgent = candidate.userAgent,
                cookie = candidate.cookie,
                displayOrder = candidate.displayOrder,
                width = candidate.width,
                height = candidate.height
            )
        }
        val batchId = imageExtractDao.replaceBatchItems(batch, items)
        AppSetting.markBackupDirty()
        return batchId
    }

    /** 观察图片下载历史；Repository 隐藏“未确认提取批次不展示”的 SQL 细节，页面只关心历史列表。 */
    fun observeHistory(): Flow<List<ImageExtractBatchData>> {
        return imageExtractDao.observeHistory()
    }

    /** 分页加载图片下载历史，避免下载记录页一次性读取大量批次和图片项。 */
    fun pagingHistory(): PagingSource<Int, ImageExtractBatchData> {
        return imageExtractDao.pagingHistory()
    }

    /** 观察图片历史总数；用于标题栏动作可用性和清空确认，不触发批次实体全量加载。 */
    fun observeHistoryCount(): Flow<Int> {
        return imageExtractDao.observeHistoryCount()
    }

    /** 观察进行中的图片批次数量；用于清空当前分类前提示会先停止后台任务。 */
    fun observeRunningHistoryCount(): Flow<Int> {
        return imageExtractDao.observeRunningHistoryCount()
    }

    /** 按当前排序读取全部图片历史批次 id；只在全选或清空时调用。 */
    suspend fun getHistoryIds(): List<Long> {
        return imageExtractDao.getHistoryIds()
    }

    /** 统计选中图片批次中仍在下载的数量；用于删除确认提示。 */
    suspend fun countRunningBatches(batchIds: Set<Long>): Int {
        if (batchIds.isEmpty()) return 0
        return imageExtractDao.countRunningBatches(batchIds)
    }

    /**
     * 基于旧图片批次创建重新下载批次。
     *
     * 只复制 URL、反盗链上下文、尺寸和展示顺序，输出目录、状态、计数和错误信息都重新开始，避免覆盖旧批次和旧公共文件夹。
     */
    suspend fun cloneBatchForRetry(batchId: Long): Long? {
        val batch = imageExtractDao.getBatch(batchId) ?: return null
        val items = imageExtractDao.getItems(batchId)
            .filter { it.url.isNotBlank() }
            .sortedBy { it.displayOrder }
            .mapIndexed { index, item ->
                ImageCandidateData(
                    url = item.url,
                    referer = item.referer,
                    userAgent = item.userAgent,
                    cookie = item.cookie,
                    displayOrder = index,
                    width = item.width,
                    height = item.height
                )
            }
        if (items.isEmpty()) return null
        return createBatch(batch.pageUrl, batch.pageName, items)
    }

    /** 观察批量任务状态，用于页面展示下载进度和最终成功/失败数量。 */
    fun observeBatch(batchId: Long): Flow<ImageExtractBatchData?> {
        return imageExtractDao.observeBatch(batchId)
    }

    /** 观察当前批次的图片候选，供提取结果页展示缩略图网格和选择状态。 */
    fun observeItems(batchId: Long): Flow<List<ImageExtractItemData>> {
        return imageExtractDao.observeItems(batchId)
    }

    /** Worker 读取批次和图片列表，统一按 displayOrder 发布最终文件。 */
    suspend fun getBatchWithItems(batchId: Long): Pair<ImageExtractBatchData, List<ImageExtractItemData>>? {
        val batch = imageExtractDao.getBatch(batchId) ?: return null
        return batch to imageExtractDao.getItems(batchId)
    }

    /** 统计恢复后媒体重新定位需要检查的图片批次和成功图片项数量；只做数据库预估。 */
    suspend fun countImagesForMediaRelocation(): Pair<Int, Int> {
        return imageExtractDao.countBatchesForMediaRelocation() to imageExtractDao.countItemsForMediaRelocation()
    }

    /** 按批次分页读取恢复后媒体重新定位候选，避免一次性加载全部图片历史。 */
    suspend fun loadImageBatchSummariesForMediaRelocation(lastBatchId: Long, limit: Int): List<ImageRelocationBatchSummary> {
        return imageExtractDao.loadBatchSummariesForMediaRelocation(lastBatchId, limit)
    }

    /** 读取批次和成功图片项，供 app 层媒体定位器按文件夹和 finalName 精确匹配。 */
    suspend fun getBatchWithSuccessfulItemsForMediaRelocation(batchId: Long): Pair<ImageExtractBatchData, List<ImageExtractItemData>>? {
        val batch = imageExtractDao.getBatch(batchId) ?: return null
        return batch to imageExtractDao.getSuccessfulItemsForMediaRelocation(batchId)
    }

    /** 按 chunk 写回高可信重新定位出的图片 URI；不修改图片项状态或批次状态。 */
    suspend fun updateImageMediaReferencesForRelocation(updates: List<ImageMediaReferenceUpdate>) {
        if (updates.isEmpty()) return
        imageExtractDao.updateMediaReferencesForRelocation(updates)
        AppSetting.markBackupDirty()
    }

    /** 读取图片下载历史页校验本地文件所需的轻量图片引用，避免列表分页映射完整加载每张图片实体。 */
    suspend fun getHistoryFileRefs(batchId: Long): List<ImageHistoryFileRef> {
        return imageExtractDao.getHistoryFileRefs(batchId)
    }

    /** 批量读取批次及其图片项，供下载记录页删除本地文件和生成缩略图列表。 */
    suspend fun getBatchesWithItems(batchIds: Set<Long>): List<Pair<ImageExtractBatchData, List<ImageExtractItemData>>> {
        if (batchIds.isEmpty()) return emptyList()
        val batches = imageExtractDao.getBatches(batchIds).associateBy { it.id }
        return batchIds.mapNotNull { id ->
            val batch = batches[id] ?: return@mapNotNull null
            batch to imageExtractDao.getItems(id)
        }
    }

    /** 更新批量任务汇总状态，让 UI 和通知能看到最新结果。 */
    suspend fun updateBatchStatus(
        batchId: Long,
        status: String,
        successCount: Int,
        failedCount: Int,
        filteredCount: Int = 0,
        outputDir: String? = null,
        errorMsg: String? = null
    ) {
        imageExtractDao.updateBatchStatus(batchId, status, successCount, failedCount, filteredCount, outputDir, errorMsg)
        if (status != ImageExtractBatchData.STATUS_DOWNLOADING) {
            AppSetting.markBackupDirty()
        }
    }

    /** 更新单张图片状态，记录临时文件、最终 URI 或失败原因。 */
    suspend fun updateItemStatus(
        itemId: Long,
        status: String,
        tempPath: String? = null,
        outputUri: String? = null,
        finalName: String? = null,
        errorMsg: String? = null
    ) {
        imageExtractDao.updateItemStatus(itemId, status, tempPath, outputUri, finalName, errorMsg)
    }

    /**
     * 用户确认下载前裁剪候选列表。
     *
     * 未选中的图片会从本批次删除，批次总数同步改为最终下载数量，这样 Worker 可以继续复用原有读取逻辑，
     * 也避免把“用户主动取消”误算成下载失败或过滤数量。
     */
    suspend fun keepSelectedItems(batchId: Long, selectedItemIds: Set<Long>) {
        imageExtractDao.keepSelectedItems(batchId, selectedItemIds)
        AppSetting.markBackupDirty()
    }

    /** 精确删除选中图片批次；图片项只通过外键级联删除这些批次下的数据。 */
    suspend fun deleteBatches(batchIds: Set<Long>) {
        if (batchIds.isEmpty()) return
        imageExtractDao.deleteBatches(batchIds)
        AppSetting.markBackupDirty()
    }
}
