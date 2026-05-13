package com.cla.clip.base.general.repository

import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.base.general.dao.ImageExtractDao
import com.cla.clip.base.general.dao.ImageExtractItemData
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
        return imageExtractDao.replaceBatchItems(batch, items)
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
    }
}
