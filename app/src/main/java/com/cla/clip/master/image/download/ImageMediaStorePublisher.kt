package com.cla.clip.master.image.download

import android.content.Context
import com.cla.clip.base.general.dao.ImageExtractItemData
import com.cla.clip.base.general.repository.ImageExtractRepository
import com.cla.clip.base.general.utils.SaveToFile
import com.cla.clip.base.general.utils.createPath
import com.cla.clip.base.general.utils.failure
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.success

/**
 * 图片临时文件发布器。
 *
 * 只负责把已通过下载和校验的 [DownloadedTempImage] 写入 MediaStore 或旧系统公共目录，并回写单项最终状态。
 * 发布器不关心批次最终状态、不发送通知，避免平台存储副作用和 Worker 流程编排继续耦合在一起。
 */
internal class ImageMediaStorePublisher(
    private val context: Context,
    private val imageExtractRepo: ImageExtractRepository,
) {

    /**
     * 按网页展示顺序发布临时图片。
     *
     * [onProgress] 会在每张图片发布尝试后回调当前发布成功数和待发布总数，Worker 用它更新前台通知；
     * 最终文件名只按成功发布的图片递增，保持旧行为：发布失败的图片不会占用序号。
     */
    suspend fun publishInDisplayOrder(
        downloaded: List<DownloadedTempImage>,
        folderName: String,
        onProgress: suspend (successCount: Int, totalCount: Int) -> Unit,
    ): ImagePublishResult {
        var successCount = 0
        var failedCount = 0
        downloaded.sortedBy { it.item.displayOrder }.forEach { tempImage ->
            val finalName = "${(successCount + 1).toString().padStart(3, '0')}.${tempImage.extension}"
            val saveImage = SaveToFile.Image(finalName, folderName, tempImage.mimeType, tempImage.durationMs)
            val mediaTarget = saveImage.createPath(context)
            logBeforePublish(tempImage, finalName, folderName, mediaTarget.uri?.toString(), mediaTarget.path)
            runCatching {
                // 发布阶段坚持原始字节直写；动图兼容性只通过 MIME/时长元数据辅助，不再做实验性转码。
                tempImage.tempFile.inputStream().use { input ->
                    mediaTarget.outputStream.use { output -> input.copyTo(output) }
                }
                saveImage.success(context, mediaTarget)
                successCount += 1
                logPublishSuccess(tempImage, finalName, mediaTarget.uri?.toString(), mediaTarget.path)
                imageExtractRepo.updateItemStatus(
                    itemId = tempImage.item.id,
                    status = ImageExtractItemData.STATUS_SUCCESS,
                    tempPath = tempImage.tempFile.absolutePath,
                    outputUri = mediaTarget.uri?.toString(),
                    finalName = finalName
                )
            }.getOrElse { tr ->
                saveImage.failure(context, mediaTarget.uri, mediaTarget.path)
                failedCount += 1
                logE(TAG, tr) {
                    "publishInDisplayOrder: 发布失败 itemId=${tempImage.item.id} finalName=$finalName " +
                        "mime=${tempImage.mimeType} targetUri=${mediaTarget.uri} targetPath=${mediaTarget.path}"
                }
                imageExtractRepo.updateItemStatus(
                    itemId = tempImage.item.id,
                    status = ImageExtractItemData.STATUS_FAILED,
                    tempPath = tempImage.tempFile.absolutePath,
                    errorMsg = tr.message
                )
            }
            onProgress(successCount, downloaded.size)
        }
        return ImagePublishResult(successCount, failedCount)
    }

    /** 发布前记录目标 URI、路径和真实 MIME，方便排查保存结果和媒体库字段是否一致。 */
    private fun logBeforePublish(
        tempImage: DownloadedTempImage,
        finalName: String,
        folderName: String,
        targetUri: String?,
        targetPath: String?,
    ) {
        logD(TAG) {
            "publishInDisplayOrder: 准备发布 itemId=${tempImage.item.id} order=${tempImage.item.displayOrder} " +
                "sourceUrl=${tempImage.item.url} tempPath=${tempImage.tempFile.absolutePath} tempSize=${tempImage.tempFile.length()} " +
                "finalName=$finalName mime=${tempImage.mimeType} animated=${tempImage.isAnimated} durationMs=${tempImage.durationMs} " +
                "folderName=$folderName targetUri=$targetUri targetPath=$targetPath"
        }
    }

    /** 发布成功后记录最终文件名和输出 URI，和 Worker 下载日志形成完整诊断链。 */
    private fun logPublishSuccess(
        tempImage: DownloadedTempImage,
        finalName: String,
        outputUri: String?,
        outputPath: String?,
    ) {
        logD(TAG) {
            "publishInDisplayOrder: 发布成功 itemId=${tempImage.item.id} finalName=$finalName " +
                "mime=${tempImage.mimeType} animated=${tempImage.isAnimated} durationMs=${tempImage.durationMs} " +
                "outputUri=$outputUri outputPath=$outputPath"
        }
    }

    private companion object {
        /** 日志标签，单独区分 MediaStore 发布链路，避免和网络下载日志混在一起。 */
        private const val TAG = "ImageMediaStorePublisher"
    }
}
