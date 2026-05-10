package com.cla.clip.base.general.repository

import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.base.general.dao.ImageExtractDao
import com.cla.clip.base.general.dao.ImageExtractItemData
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

data class ImageCandidateData(
    val url: String,
    val referer: String?,
    val userAgent: String?,
    val cookie: String?,
    val displayOrder: Int,
    val width: Int?,
    val height: Int?,
)

@Singleton
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
        outputDir: String? = null,
        errorMsg: String? = null
    ) {
        imageExtractDao.updateBatchStatus(batchId, status, successCount, failedCount, outputDir, errorMsg)
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
}
