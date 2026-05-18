package com.cla.clip.master.work

import android.content.Context
import android.content.pm.ServiceInfo
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
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.showName
import com.cla.clip.base.general.utils.success
import com.cla.clip.master.image.download.FilteredImageException
import com.cla.clip.master.image.download.ImageDownloadValidator
import com.cla.clip.master.image.download.ImageRequestHeaderBuilder
import com.cla.clip.master.image.download.cookieLogSummary
import com.cla.clip.master.image.download.sanitizeImageDownloadFolderName
import com.cla.clip.master.image.format.ImageFormatSniffer
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
import okhttp3.Response
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@HiltWorker
/**
 * 图片批量下载 Worker。
 *
 * 按批次读取用户确认后的图片项，先并发下载到临时目录并做内容质量校验，再按网页展示顺序发布到相册目录。
 * 这种两阶段流程可以避免网络完成顺序影响最终文件名，也能在发布前过滤透明占位图和错误图。
 */
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
        const val DOWNLOAD_IMAGES_TASK_TAG = "download_images"

        /** Worker 输入参数中的批次 ID 键名，用于读取本次需要下载的图片批次。 */
        private const val KEY_BATCH_ID = "key_batch_id"

        /** 临时下载目录名称，图片先落到缓存目录再按网页顺序发布。 */
        private const val TEMP_DIR_NAME = "image_extract"

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

        /** 取消指定图片批量下载任务；删除进行中批次前必须先取消，避免 Worker 迟到回写已删除批次。 */
        fun cancel(context: Context, batchId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork("${DOWNLOAD_IMAGES_TASK_TAG}:$batchId")
        }
    }

    /**
     * WorkManager 执行入口。
     *
     * 读取批次、创建唯一输出目录、更新批次状态并执行下载/发布；任何顶层异常都会把批次标为失败并发送结果通知。
     */
    override suspend fun doWork(): Result {
        val batchId = inputData.getLong(KEY_BATCH_ID, -1)
        val (batch, items) = imageExtractRepo.getBatchWithItems(batchId) ?: return Result.failure()
        val baseFolderName = sanitizeImageDownloadFolderName(batch.pageName).ifBlank { "images_$batchId" }
        // 下载开始前固定本批次的唯一目录名，后续所有图片都发布到同一个新文件夹。
        val outputFolder = applicationContext.createUniqueImageFolderName(baseFolderName)
        val outputFolderName = outputFolder.folderName
        val outputDir = outputFolder.relativePath
        logD(TAG) {
            "doWork: 开始图片批量下载 batchId=$batchId pageUrl=${batch.pageUrl} itemCount=${items.size} " +
                "outputFolderName=$outputFolderName outputDir=$outputDir"
        }
        items.forEach { item ->
            logD(TAG) {
                "doWork: 待下载图片 itemId=${item.id} order=${item.displayOrder} url=${item.url} " +
                    "referer=${item.referer} userAgent=${item.userAgent} cookie=${item.cookie.cookieLogSummary()} " +
                    "domSize=${item.width}x${item.height}"
            }
        }

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
            notifyResult(batchId, outputFolderName, outputDir, publishResult.successCount, failedCount, filteredCount)
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
            notifyResult(batchId, outputFolderName, outputDir, 0, items.size, 0)
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
                        // 响应头经常被 CDN 或图片代理写错，先保留为兜底值，真正入库前会以文件头识别结果为准。
                        val responseMimeType = ImageFormatSniffer.normalizeResponseImageMimeType(response.header("Content-Type"))
                        val rawContentType = response.header("Content-Type")
                        val contentLength = response.header("Content-Length")
                        logD(TAG) {
                            "downloadToTemp: 响应成功 itemId=${item.id} url=${item.url} " +
                                "code=${response.code} rawContentType=$rawContentType normalizedMime=$responseMimeType " +
                                "contentLength=$contentLength"
                        }
                        // 临时文件不使用响应头推断扩展名，避免错误后缀影响后续发布到相册的真实格式判断。
                        val tempFile = File(tempDir, "${item.id}.download")
                        response.use { resp ->
                            val body = resp.body ?: error("Empty image body")
                            body.byteStream().use { input ->
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        val imageFormat = ImageFormatSniffer.detectDownloadedImageFormat(tempFile, responseMimeType, item.url)
                        logD(TAG) {
                            "downloadToTemp: 临时文件识别完成 itemId=${item.id} size=${tempFile.length()} " +
                                "formatMime=${imageFormat.mimeType} extension=${imageFormat.extension} animated=${imageFormat.isAnimated} " +
                                "durationMs=${imageFormat.durationMs} tempPath=${tempFile.absolutePath}"
                        }
                        ImageDownloadValidator.validateDownloadedImage(tempFile, imageFormat.mimeType)
                        logD(TAG) {
                            "downloadToTemp: 图片内容校验通过 itemId=${item.id} mime=${imageFormat.mimeType} " +
                                "extension=${imageFormat.extension} animated=${imageFormat.isAnimated} durationMs=${imageFormat.durationMs}"
                        }
                        imageExtractRepo.updateItemStatus(item.id, ImageExtractItemData.STATUS_TEMP_READY, tempFile.absolutePath)
                        TempImage(item, tempFile, imageFormat.mimeType, imageFormat.extension, imageFormat.isAnimated, imageFormat.durationMs)
                    }.getOrElse { tr ->
                        if (tr is FilteredImageException) {
                            logD(TAG) {
                                "downloadToTemp: 图片被过滤 itemId=${item.id} url=${item.url} reason=${tr.message}"
                            }
                            imageExtractRepo.updateItemStatus(item.id, ImageExtractItemData.STATUS_FILTERED, errorMsg = tr.message)
                            filteredCount.incrementAndGet()
                        } else {
                            logE(TAG, tr) {
                                "downloadToTemp: 图片下载失败 itemId=${item.id} url=${item.url}"
                            }
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
            val saveImage = SaveToFile.Image(finalName, folderName, tempImage.mimeType, tempImage.durationMs)
            val mediaTarget = saveImage.createPath(applicationContext)
            logD(TAG) {
                "publishInDisplayOrder: 准备发布 itemId=${tempImage.item.id} order=${tempImage.item.displayOrder} " +
                    "sourceUrl=${tempImage.item.url} tempPath=${tempImage.tempFile.absolutePath} tempSize=${tempImage.tempFile.length()} " +
                    "finalName=$finalName mime=${tempImage.mimeType} animated=${tempImage.isAnimated} durationMs=${tempImage.durationMs} " +
                    "folderName=$folderName targetUri=${mediaTarget.uri} targetPath=${mediaTarget.path}"
            }
            runCatching {
                // 发布阶段坚持原始字节直写；动图兼容性只通过 MIME/时长元数据辅助，不再做实验性转码。
                tempImage.tempFile.inputStream().use { input ->
                    mediaTarget.outputStream.use { output -> input.copyTo(output) }
                }
                saveImage.success(applicationContext, mediaTarget)
                successCount += 1
                logD(TAG) {
                    "publishInDisplayOrder: 发布成功 itemId=${tempImage.item.id} finalName=$finalName " +
                        "mime=${tempImage.mimeType} animated=${tempImage.isAnimated} durationMs=${tempImage.durationMs} " +
                        "outputUri=${mediaTarget.uri} outputPath=${mediaTarget.path}"
                }
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
            val progress = (50 + ((successCount * 50) / downloaded.size.coerceAtLeast(1))).coerceIn(50, 100)
            setForeground(buildForegroundInfo(applicationContext.getString(R.string.base_general_download_now), folderName.showName, progress))
        }
        return PublishResult(successCount, failedCount)
    }

    /**
     * 执行单张图片下载请求。
     *
     * 请求会尽量携带提取阶段保存的 Referer、User-Agent 和 Cookie；非 2xx 响应直接视为下载失败并关闭响应体。
     */
    private fun executeRequest(url: String, referer: String?, userAgent: String?, cookie: String?): Response {
        logD(TAG) {
            "executeRequest: 请求图片 url=$url referer=$referer userAgent=$userAgent " +
                "accept=${ImageRequestHeaderBuilder.IMAGE_REQUEST_ACCEPT} cookie=${cookie.cookieLogSummary()}"
        }
        val response = okHttpClient.get().newCall(
            ImageRequestHeaderBuilder.buildImageRequest(url, referer, userAgent, cookie)
        ).execute()
        if (!response.isSuccessful) {
            response.close()
            error("HTTP ${response.code} ${response.message}, url=$url")
        }
        return response
    }

    /**
     * 发送图片批量下载结果通知。
     *
     * 通知内容包含成功、过滤和失败数量；失败数量为 0 时使用下载完成标题，否则使用下载失败标题。
     */
    private fun notifyResult(batchId: Long, fileName: String, outputDir: String, successCount: Int, failedCount: Int, filteredCount: Int) {
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
        // 没有任何图片发布成功时不传 outputDir，通知点击只走相册/选择器兜底，避免把用户带到一个可能不存在或为空的目录。
        val openableOutputDir = outputDir.takeIf { successCount > 0 }
        notificationHelper.notifyImageDownloadResult(batchId, openableOutputDir, title, fileName.showName, content)
    }

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

    /** 构建前台下载通知信息，Android 10+ 标记为 DATA_SYNC 类型以满足前台服务要求。 */
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

    /**
     * 已通过内容校验的临时图片。
     *
     * 发布阶段只处理这个结构，确保无效图片不会进入相册目录。
     */
    private data class TempImage(
        /** 原始图片项记录，用于按 displayOrder 排序和回写状态。 */
        val item: ImageExtractItemData,

        /** 下载到缓存目录的临时文件，发布成功或失败后可清理。 */
        val tempFile: File,

        /** 规范化后的 MIME 类型，用于创建 MediaStore 记录。 */
        val mimeType: String,

        /** 最终文件扩展名，不包含点。 */
        val ext: String,

        /** 是否在下载字节中识别到动画帧/动画 chunk；仅用于日志诊断，不改变保存流程。 */
        val isAnimated: Boolean,

        /** 动图总时长，单位毫秒；写入 MediaStore 作为相册识别动图的辅助元数据。 */
        val durationMs: Long?,
    )

}
