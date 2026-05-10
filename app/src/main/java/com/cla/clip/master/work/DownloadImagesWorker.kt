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

@HiltWorker
class DownloadImagesWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val okHttpClient: Lazy<OkHttpClient>,
    private val imageExtractRepo: ImageExtractRepository,
    private val notificationHelper: NotificationHelper,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "DownloadImagesWorker"
        private const val DOWNLOAD_IMAGES_TASK_TAG = "download_images"
        private const val KEY_BATCH_ID = "key_batch_id"
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
    }

    override suspend fun doWork(): Result {
        val batchId = inputData.getLong(KEY_BATCH_ID, -1)
        val (batch, items) = imageExtractRepo.getBatchWithItems(batchId) ?: return Result.failure()
        val safeFolderName = sanitizeFileName(batch.pageName).ifBlank { "images_$batchId" }
        val outputDir = "Pictures/clipMaster/$safeFolderName"

        setForeground(buildForegroundInfo(applicationContext.getString(R.string.base_general_initialize_download), safeFolderName, 0))
        imageExtractRepo.updateBatchStatus(batchId, ImageExtractBatchData.STATUS_DOWNLOADING, 0, 0, outputDir)

        val tempDir = File(applicationContext.cacheDir, "$TEMP_DIR_NAME${File.separator}$batchId").apply { mkdirs() }
        return runCatching {
            val downloaded = downloadToTemp(items, tempDir, batch.pageUrl, safeFolderName)
            val publishResult = publishInDisplayOrder(downloaded, safeFolderName)
            val failedCount = items.size - publishResult.successCount
            val status = when {
                publishResult.successCount == items.size -> ImageExtractBatchData.STATUS_SUCCESS
                publishResult.successCount > 0 -> ImageExtractBatchData.STATUS_PARTIAL_SUCCESS
                else -> ImageExtractBatchData.STATUS_FAILED
            }
            imageExtractRepo.updateBatchStatus(batchId, status, publishResult.successCount, failedCount, outputDir)
            notifyResult(batchId, safeFolderName, publishResult.successCount, failedCount)
            Result.success()
        }.getOrElse { tr ->
            logE(TAG, tr) { "doWork: 图片批量下载失败" }
            imageExtractRepo.updateBatchStatus(
                batchId = batchId,
                status = ImageExtractBatchData.STATUS_FAILED,
                successCount = 0,
                failedCount = items.size,
                outputDir = outputDir,
                errorMsg = tr.message
            )
            notifyResult(batchId, safeFolderName, 0, items.size)
            Result.failure()
        }
    }

    /** 先并发下载到临时目录，避免网络完成顺序影响最终文件名顺序。 */
    private suspend fun downloadToTemp(
        items: List<ImageExtractItemData>,
        tempDir: File,
        pageUrl: String,
        fileName: String
    ): List<TempImage> = coroutineScope {
        val semaphore = Semaphore(4)
        var done = 0
        items.map { item ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    if (isStopped) error("Worker stopped")
                    runCatching {
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
                        imageExtractRepo.updateItemStatus(item.id, ImageExtractItemData.STATUS_TEMP_READY, tempFile.absolutePath)
                        TempImage(item, tempFile, mimeType, ext)
                    }.getOrElse { tr ->
                        imageExtractRepo.updateItemStatus(item.id, ImageExtractItemData.STATUS_FAILED, errorMsg = tr.message)
                        null
                    }.also {
                        done += 1
                        val progress = ((done * 50) / items.size.coerceAtLeast(1)).coerceIn(0, 50)
                        setProgress(workDataOf("progress" to progress))
                        setForeground(buildForegroundInfo(applicationContext.getString(R.string.base_general_download_now), fileName.showName, progress))
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    /** 所有临时文件下载完成后，再按网页显示顺序发布为 001、002、003。 */
    private suspend fun publishInDisplayOrder(downloaded: List<TempImage>, folderName: String): PublishResult {
        var successCount = 0
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
        return PublishResult(successCount)
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

    /** 清理文件夹名里的非法字符，避免网页标题直接作为目录名时创建失败。 */
    private fun sanitizeFileName(raw: String): String {
        return raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_").trim().take(60)
    }

    private fun notifyResult(batchId: Long, fileName: String, successCount: Int, failedCount: Int) {
        val title = if (failedCount == 0) {
            applicationContext.getString(R.string.base_general_download_completed)
        } else {
            applicationContext.getString(R.string.base_general_download_failed)
        }
        val content = if (failedCount == 0) {
            "已保存 ${successCount} 张图片"
        } else {
            "已保存 ${successCount} 张图片，失败 ${failedCount} 张"
        }
        notificationHelper.notifyDownloadResult(batchId, title, fileName.showName, content)
    }

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

    private data class PublishResult(val successCount: Int)
}
