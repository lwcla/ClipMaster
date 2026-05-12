package com.cla.clip.master.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cla.clip.base.general.R
import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.base.general.dao.ImageExtractItemData
import com.cla.clip.base.general.repository.ImageExtractRepository
import com.cla.clip.base.general.utils.SaveToFile
import com.cla.clip.base.general.utils.createPath
import com.cla.clip.base.general.utils.createUniqueImageFolderName
import com.cla.clip.base.general.utils.failure
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.showName
import com.cla.clip.base.general.utils.success
import com.cla.clip.master.utils.NotificationHelper
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@HiltWorker
class DownloadImagesWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val okHttpClient: Lazy<OkHttpClient>,
    private val imageExtractRepo: ImageExtractRepository,
    private val notificationHelper: NotificationHelper,
) : CoroutineWorker(appContext, params) {

    companion object {
        /** 日志标签，用于定位图片下载 Worker 的运行和失败信息。 */
        private const val TAG = "DownloadImagesWorker"

        /** WorkManager 任务标签，便于统一识别图片批量下载任务。 */
        private const val DOWNLOAD_IMAGES_TASK_TAG = "download_images"

        /** Worker 输入参数中的批次 ID 键名，用于读取本次需要下载的图片批次。 */
        private const val KEY_BATCH_ID = "key_batch_id"

        /** 临时下载目录名称，图片先落到缓存目录再按网页顺序发布。 */
        private const val TEMP_DIR_NAME = "image_extract"

        /** 有效图片最小文件大小，低于该阈值通常是 1x1 跟踪像素或占位图。 */
        private const val MIN_VALID_IMAGE_BYTES = 512L

        /** 有效图片最小边长，过小图片通常不是正文内容。 */
        private const val MIN_VALID_IMAGE_EDGE_PX = 3

        /** 图片质量检测的最大抽样边长，控制解码内存占用。 */
        private const val IMAGE_SAMPLE_MAX_EDGE_PX = 96

        /** 判断像素近似透明的 alpha 阈值，用于识别透明占位图。 */
        private const val TRANSPARENT_ALPHA_THRESHOLD = 12

        /** 透明像素比例阈值，超过该比例认为整张图基本不可见。 */
        private const val TRANSPARENT_RATIO_THRESHOLD = 0.95

        /** 亮度跨度阈值，低于该值认为图片几乎是单色。 */
        private const val SOLID_LUMINANCE_DELTA = 6

        /** 近似纯黑图片的平均亮度阈值，用于过滤黑色错误图或占位图。 */
        private const val DARK_LUMINANCE_THRESHOLD = 12

        /** 近似纯白图片的平均亮度阈值，用于过滤白色错误图或占位图。 */
        private const val LIGHT_LUMINANCE_THRESHOLD = 243

        /** 启动图片批量下载任务，同一个批次只保留一个 Worker，避免重复保存。 */
        fun enqueue(context: Context, batchId: Long) {
            val data = workDataOf(KEY_BATCH_ID to batchId)
            val request = OneTimeWorkRequestBuilder<DownloadImagesWorker>()
                .setInputData(data)
                .addTag(DOWNLOAD_IMAGES_TASK_TAG)
                .addTag("${DOWNLOAD_IMAGES_TASK_TAG}:$batchId")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "${DOWNLOAD_IMAGES_TASK_TAG}:$batchId",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        val batchId = inputData.getLong(KEY_BATCH_ID, -1)
        val (batch, items) = imageExtractRepo.getBatchWithItems(batchId) ?: return Result.failure()
        val baseFolderName = sanitizeFileName(batch.pageName).ifBlank { "images_$batchId" }
        // 下载开始前固定本批次的唯一目录名，后续所有图片都发布到同一个新文件夹。
        val outputFolder = applicationContext.createUniqueImageFolderName(baseFolderName)
        val outputFolderName = outputFolder.folderName
        val outputDir = outputFolder.relativePath

        setForeground(buildForegroundInfo(applicationContext.getString(R.string.base_general_initialize_download), outputFolderName, 0))
        imageExtractRepo.updateBatchStatus(
            batchId = batchId,
            status = ImageExtractBatchData.STATUS_DOWNLOADING,
            successCount = 0,
            failedCount = 0,
            filteredCount = 0,
            outputDir = outputDir
        )

        val tempDir = File(applicationContext.cacheDir, "$TEMP_DIR_NAME${File.separator}$batchId").apply { mkdirs() }
        return runCatching {
            val downloadResult = downloadToTemp(
                batchId = batchId,
                items = items,
                tempDir = tempDir,
                pageUrl = batch.pageUrl,
                fileName = outputFolderName,
                outputDir = outputDir
            )
            val publishResult = publishInDisplayOrder(downloadResult.downloaded, outputFolderName)
            val failedCount = downloadResult.failedCount + publishResult.failedCount
            val filteredCount = downloadResult.filteredCount
            val status = when {
                publishResult.successCount == items.size -> ImageExtractBatchData.STATUS_SUCCESS
                publishResult.successCount > 0 -> ImageExtractBatchData.STATUS_PARTIAL_SUCCESS
                failedCount == 0 && filteredCount > 0 -> ImageExtractBatchData.STATUS_FILTERED
                else -> ImageExtractBatchData.STATUS_FAILED
            }
            imageExtractRepo.updateBatchStatus(
                batchId = batchId,
                status = status,
                successCount = publishResult.successCount,
                failedCount = failedCount,
                filteredCount = filteredCount,
                outputDir = outputDir
            )
            notifyResult(batchId, outputFolderName, publishResult.successCount, failedCount, filteredCount)
            Result.success()
        }.getOrElse { tr ->
            logE(TAG, tr) { "doWork: 图片批量下载失败" }
            imageExtractRepo.updateBatchStatus(
                batchId = batchId,
                status = ImageExtractBatchData.STATUS_FAILED,
                successCount = 0,
                failedCount = items.size,
                filteredCount = 0,
                outputDir = outputDir,
                errorMsg = tr.message
            )
            notifyResult(batchId, outputFolderName, 0, items.size, 0)
            Result.failure()
        }
    }

    /** 先并发下载到临时目录，避免网络完成顺序影响最终文件名顺序。 */
    private suspend fun downloadToTemp(
        batchId: Long,
        items: List<ImageExtractItemData>,
        tempDir: File,
        pageUrl: String,
        fileName: String,
        outputDir: String,
    ): DownloadResult = coroutineScope {
        val semaphore = Semaphore(4)
        /** 已处理完成的候选图片数量，用于通知和 WorkManager 进度。 */
        val doneCount = AtomicInteger(0)
        /** 已成功下载到临时目录的图片数量，下载阶段暂存到批次 successCount 供 UI 展示进度。 */
        val tempReadyCount = AtomicInteger(0)
        /** 被内容质量校验主动过滤的图片数量，不应计入失败。 */
        val filteredCount = AtomicInteger(0)
        /** 网络请求、解码异常之外的真实下载失败数量，下载阶段暂存到批次 failedCount。 */
        val failedCount = AtomicInteger(0)
        val downloaded = items.map { item ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    if (isStopped) error("Worker stopped")
                    val tempImage = runCatching {
                        val response = executeRequest(item.url, item.referer ?: pageUrl, item.userAgent, item.cookie)
                        val mimeType = normalizeImageMimeType(response)
                        val ext = imageExtension(mimeType, item.url)
                        val tempFile = File(tempDir, "${item.id}.$ext")
                        response.use { resp ->
                            val body = resp.body ?: error("Empty image body")
                            body.byteStream().use { input ->
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        validateDownloadedImage(tempFile, mimeType)
                        imageExtractRepo.updateItemStatus(item.id, ImageExtractItemData.STATUS_TEMP_READY, tempFile.absolutePath)
                        TempImage(item, tempFile, mimeType, ext)
                    }.getOrElse { tr ->
                        if (tr is FilteredImageException) {
                            imageExtractRepo.updateItemStatus(item.id, ImageExtractItemData.STATUS_FILTERED, errorMsg = tr.message)
                            filteredCount.incrementAndGet()
                        } else {
                            imageExtractRepo.updateItemStatus(item.id, ImageExtractItemData.STATUS_FAILED, errorMsg = tr.message)
                            failedCount.incrementAndGet()
                        }
                        null
                    }
                    if (tempImage != null) {
                        tempReadyCount.incrementAndGet()
                    }

                    tempImage.also {
                        val done = doneCount.incrementAndGet()
                        updateDownloadProgress(
                            batchId = batchId,
                            tempReadyCount = tempReadyCount.get(),
                            failedCount = failedCount.get(),
                            filteredCount = filteredCount.get(),
                            outputDir = outputDir
                        )
                        val progress = ((done * 50) / items.size.coerceAtLeast(1)).coerceIn(0, 50)
                        setProgress(workDataOf("progress" to progress))
                        setForeground(buildForegroundInfo(applicationContext.getString(R.string.base_general_download_now), fileName.showName, progress))
                    }
                }
            }
        }.awaitAll().filterNotNull()
        DownloadResult(
            downloaded = downloaded,
            failedCount = failedCount.get(),
            filteredCount = filteredCount.get()
        )
    }

    /** 下载阶段刷新批次进度，让 UI 观察数据库时能实时显示已处理数量。 */
    private suspend fun updateDownloadProgress(
        batchId: Long,
        tempReadyCount: Int,
        failedCount: Int,
        filteredCount: Int,
        outputDir: String,
    ) {
        // 下载中 successCount 表示“已下载到临时目录”的数量，最终成功数会在发布到相册后重新写入。
        imageExtractRepo.updateBatchStatus(
            batchId = batchId,
            status = ImageExtractBatchData.STATUS_DOWNLOADING,
            successCount = tempReadyCount,
            failedCount = failedCount,
            filteredCount = filteredCount,
            outputDir = outputDir
        )
    }

    /** 所有临时文件下载完成后，再按网页显示顺序发布为 001、002、003。 */
    private suspend fun publishInDisplayOrder(downloaded: List<TempImage>, folderName: String): PublishResult {
        var successCount = 0
        var failedCount = 0
        downloaded.sortedBy { it.item.displayOrder }.forEach { tempImage ->
            val finalName = "${(successCount + 1).toString().padStart(3, '0')}.${tempImage.ext}"
            val saveImage = SaveToFile.Image(finalName, folderName, tempImage.mimeType)
            val mediaTarget = saveImage.createPath(applicationContext)
            runCatching {
                tempImage.tempFile.inputStream().use { input ->
                    mediaTarget.outputStream.use { output -> input.copyTo(output) }
                }
                saveImage.success(applicationContext, mediaTarget)
                successCount += 1
                imageExtractRepo.updateItemStatus(
                    itemId = tempImage.item.id,
                    status = ImageExtractItemData.STATUS_SUCCESS,
                    tempPath = tempImage.tempFile.absolutePath,
                    outputUri = mediaTarget.uri?.toString(),
                    finalName = finalName
                )
            }.getOrElse { tr ->
                saveImage.failure(applicationContext, mediaTarget.uri, mediaTarget.path)
                failedCount += 1
                imageExtractRepo.updateItemStatus(
                    itemId = tempImage.item.id,
                    status = ImageExtractItemData.STATUS_FAILED,
                    tempPath = tempImage.tempFile.absolutePath,
                    errorMsg = tr.message
                )
            }
            val progress = (50 + ((successCount * 50) / downloaded.size.coerceAtLeast(1))).coerceIn(50, 100)
            setForeground(buildForegroundInfo(applicationContext.getString(R.string.base_general_download_now), folderName.showName, progress))
        }
        return PublishResult(successCount, failedCount)
    }

    private fun executeRequest(url: String, referer: String?, userAgent: String?, cookie: String?): Response {
        val response = okHttpClient.get().newCall(
            Request.Builder().url(url).apply {
                if (!referer.isNullOrBlank()) header("Referer", referer)
                if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent)
                if (!cookie.isNullOrBlank()) header("Cookie", cookie)
            }.build()
        ).execute()
        if (!response.isSuccessful) {
            response.close()
            error("HTTP ${response.code} ${response.message}, url=$url")
        }
        return response
    }

    /** 优先使用响应头判断图片类型，避免 URL 无后缀时无法生成合适扩展名。 */
    private fun normalizeImageMimeType(response: Response): String {
        val contentType = response.header("Content-Type").orEmpty().substringBefore(";").trim().lowercase()
        return when (contentType) {
            "image/png", "image/webp", "image/gif", "image/avif", "image/jpeg" -> contentType
            else -> "image/jpeg"
        }
    }

    private fun imageExtension(mimeType: String, url: String): String {
        return when (mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/avif" -> "avif"
            else -> url.substringBefore("?").substringAfterLast('.', "jpg").lowercase()
                .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif", "avif") }
                ?: "jpg"
        }
    }

    /** 下载后校验真实图片 内容，主动过滤透明像素、占位图和反盗链返回的纯色错误图。 */
    private fun validateDownloadedImage(file: File, mimeType: String) {
        if (file.length() < MIN_VALID_IMAGE_BYTES) {
            throw FilteredImageException("Ignore tiny image file: ${file.length()} bytes")
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            error("Invalid image content")
        }
        if (bounds.outWidth < MIN_VALID_IMAGE_EDGE_PX || bounds.outHeight < MIN_VALID_IMAGE_EDGE_PX) {
            throw FilteredImageException("Ignore tracking/placeholder image: ${bounds.outWidth}x${bounds.outHeight}")
        }

        val sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
        val sample = BitmapFactory.Options().run {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            BitmapFactory.decodeFile(file.absolutePath, this)
        } ?: error("Invalid image pixels")

        try {
            val quality = inspectBitmapQuality(sample, mimeType)
            if (quality.transparentRatio >= TRANSPARENT_RATIO_THRESHOLD) {
                throw FilteredImageException("Ignore transparent placeholder image")
            }
            if (quality.isNearlySolid && (quality.avgLuminance <= DARK_LUMINANCE_THRESHOLD || quality.avgLuminance >= LIGHT_LUMINANCE_THRESHOLD)) {
                throw FilteredImageException("Ignore nearly solid black/white image")
            }
        } finally {
            sample.recycle()
        }
    }

    /** 根据原图尺寸计算抽样倍率，只取小图检查颜色分布，避免校验大图时占用过多内存。 */
    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > IMAGE_SAMPLE_MAX_EDGE_PX || height / sampleSize > IMAGE_SAMPLE_MAX_EDGE_PX) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /** 抽样统计透明比例和亮度跨度，用来识别纯色占位图。 */
    private fun inspectBitmapQuality(bitmap: Bitmap, mimeType: String): ImageQuality {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var transparentCount = 0
        var minLuminance = 255
        var maxLuminance = 0
        var luminanceSum = 0L
        var opaqueCount = 0

        pixels.forEach { color ->
            val alpha = color ushr 24
            if (alpha <= TRANSPARENT_ALPHA_THRESHOLD) {
                transparentCount += 1
                return@forEach
            }

            val red = color shr 16 and 0xff
            val green = color shr 8 and 0xff
            val blue = color and 0xff
            val luminance = ((red * 299) + (green * 587) + (blue * 114)) / 1000
            minLuminance = minOf(minLuminance, luminance)
            maxLuminance = maxOf(maxLuminance, luminance)
            luminanceSum += luminance.toLong()
            opaqueCount += 1
        }

        val totalCount = pixels.size.coerceAtLeast(1)
        val transparentRatio = transparentCount.toDouble() / totalCount
        val avgLuminance = if (opaqueCount > 0) (luminanceSum / opaqueCount).toInt() else 0
        val isNearlySolid = opaqueCount > 0 && maxLuminance - minLuminance <= SOLID_LUMINANCE_DELTA

        // GIF 经常用于极小透明跟踪像素，保留 MIME 参数方便后续按格式细分规则。
        return ImageQuality(
            transparentRatio = transparentRatio,
            avgLuminance = avgLuminance,
            isNearlySolid = isNearlySolid || mimeType == "image/gif" && transparentRatio >= TRANSPARENT_RATIO_THRESHOLD
        )
    }

    /** 清理文件夹名里的非法字符，避免网页标题直接作为目录名时创建失败。 */
    private fun sanitizeFileName(raw: String): String {
        return raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_").trim().take(60)
    }

    private fun notifyResult(batchId: Long, fileName: String, successCount: Int, failedCount: Int, filteredCount: Int) {
        val title = if (failedCount == 0) {
            applicationContext.getString(R.string.base_general_download_completed)
        } else {
            applicationContext.getString(R.string.base_general_download_failed)
        }
        val content = buildList {
            add(applicationContext.getString(R.string.base_general_image_saved_count, successCount))
            if (filteredCount > 0) add(applicationContext.getString(R.string.base_general_image_filtered_count, filteredCount))
            if (failedCount > 0) add(applicationContext.getString(R.string.base_general_image_failed_count, failedCount))
        }.joinToString(applicationContext.getString(R.string.base_general_text_separator))
        notificationHelper.notifyDownloadResult(batchId, title, fileName.showName, content)
    }

    /** 内容质量校验主动过滤图片时使用的异常，调用方据此区分过滤和真实失败。 */
    private class FilteredImageException(message: String) : Exception(message)

    /** 下载阶段结果，分别记录可发布图片、主动过滤数量和真实下载失败数量。 */
    private data class DownloadResult(
        val downloaded: List<TempImage>,
        val failedCount: Int,
        val filteredCount: Int,
    )

    /** 发布阶段结果，记录实际保存到相册的成功数量和发布失败数量。 */
    private data class PublishResult(
        val successCount: Int,
        val failedCount: Int,
    )

    private fun buildForegroundInfo(title: String, fileName: String, progress: Int): ForegroundInfo {
        val notification = notificationHelper.buildDownloadNotification(title, fileName, progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NotificationHelper.VIDEO_DOWNLOAD_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NotificationHelper.VIDEO_DOWNLOAD_NOTIFICATION_ID, notification)
        }
    }

    private data class TempImage(
        val item: ImageExtractItemData,
        val tempFile: File,
        val mimeType: String,
        val ext: String,
    )

    /** 图片 内容质量的轻量抽样结果，用于下载阶段过滤明显无效的资源。 */
    private data class ImageQuality(
        val transparentRatio: Double,
        val avgLuminance: Int,
        val isNearlySolid: Boolean,
    )

}
