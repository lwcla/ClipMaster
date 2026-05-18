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
import com.cla.clip.base.general.utils.logD
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

        /** 动画标记扫描上限，单位字节；只取前段文件即可覆盖 GIF 帧头和 WebP ANIM/ANMF chunk，避免为日志读取超大图片。 */
        private const val ANIMATION_MARKER_SCAN_BYTES = 512 * 1024

        /** 浏览器图片请求常见 Accept；用于降低 CDN 因 OkHttp 默认 Accept 缺失而返回静态降级图的概率。 */
        private const val IMAGE_REQUEST_ACCEPT = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"

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
        val baseFolderName = sanitizeFileName(batch.pageName).ifBlank { "images_$batchId" }
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
                        val responseMimeType = normalizeResponseImageMimeType(response)
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
                        val imageFormat = detectDownloadedImageFormat(tempFile, responseMimeType, item.url)
                        logD(TAG) {
                            "downloadToTemp: 临时文件识别完成 itemId=${item.id} size=${tempFile.length()} " +
                                "formatMime=${imageFormat.mimeType} extension=${imageFormat.extension} animated=${imageFormat.isAnimated} " +
                                "durationMs=${imageFormat.durationMs} tempPath=${tempFile.absolutePath}"
                        }
                        validateDownloadedImage(tempFile, imageFormat.mimeType)
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
                "accept=$IMAGE_REQUEST_ACCEPT cookie=${cookie.cookieLogSummary()}"
        }
        val response = okHttpClient.get().newCall(
            Request.Builder().url(url).apply {
                // 与 WebView/浏览器图片加载保持相近的内容协商条件，避免服务端因缺少 Accept 返回静态转码版本。
                header("Accept", IMAGE_REQUEST_ACCEPT)
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

    /** 从响应头提取可用图片 MIME，只作为真实文件识别失败时的兜底，不直接决定最终相册媒体类型。 */
    private fun normalizeResponseImageMimeType(response: Response): String? {
        val contentType = response.header("Content-Type").orEmpty().substringBefore(";").trim().lowercase()
        return normalizeImageMimeType(contentType)
    }

    /**
     * 识别已经下载到本地的真实图片格式。
     *
     * 优先读取文件头，能避开响应头或 URL 后缀不可信导致 GIF/Animated WebP 被当成 JPEG 入库的问题；如果文件头无法识别，
     * 再退回 Android 解码器、响应头和 URL 后缀，最后才使用 JPEG 兜底。
     */
    private fun detectDownloadedImageFormat(file: File, responseMimeType: String?, url: String): ImageFileFormat {
        sniffImageFormat(file)?.let {
            logD(TAG) {
                "detectDownloadedImageFormat: 文件头识别 url=$url mime=${it.mimeType} ext=${it.extension} " +
                    "animated=${it.isAnimated} durationMs=${it.durationMs}"
            }
            return it
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        normalizeImageMimeType(bounds.outMimeType)?.let { mimeType ->
            logD(TAG) { "detectDownloadedImageFormat: Android 解码 MIME url=$url mime=$mimeType bounds=${bounds.outWidth}x${bounds.outHeight}" }
            return ImageFileFormat(mimeType = mimeType, extension = imageExtensionForMimeType(mimeType))
        }

        normalizeImageMimeType(responseMimeType)?.let { mimeType ->
            logD(TAG) { "detectDownloadedImageFormat: 响应头兜底 url=$url mime=$mimeType" }
            return ImageFileFormat(mimeType = mimeType, extension = imageExtensionForMimeType(mimeType))
        }

        imageExtensionFromUrl(url)?.let { extension ->
            mimeTypeForImageExtension(extension)?.let { mimeType ->
                logD(TAG) { "detectDownloadedImageFormat: URL 后缀兜底 url=$url mime=$mimeType ext=$extension" }
                return ImageFileFormat(mimeType = mimeType, extension = extension)
            }
        }

        logD(TAG) { "detectDownloadedImageFormat: 无法识别格式，使用 JPEG 兜底 url=$url" }
        return ImageFileFormat(mimeType = "image/jpeg", extension = "jpg")
    }

    /**
     * 按文件头识别常见图片格式。
     *
     * 动图问题主要发生在 GIF/WebP 被错误 MIME 标成 JPEG；这里直接检查魔数，确保后续写入 MediaStore 的类型与真实字节一致。
     */
    private fun sniffImageFormat(file: File): ImageFileFormat? {
        val header = ByteArray(32)
        val readSize = file.inputStream().use { input -> input.read(header) }
        if (readSize < 4) return null

        return when {
            header.matchesBytes(readSize, 0, 0xff, 0xd8, 0xff) -> ImageFileFormat("image/jpeg", "jpg")
            header.matchesBytes(readSize, 0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> {
                val durationMs = file.readApngDurationMs()
                ImageFileFormat("image/png", "png", isAnimated = durationMs != null || file.hasApngAnimationMarker(), durationMs = durationMs)
            }
            header.matchesAscii(readSize, 0, "GIF87a") || header.matchesAscii(readSize, 0, "GIF89a") -> {
                val durationMs = file.readGifDurationMs()
                ImageFileFormat("image/gif", "gif", isAnimated = durationMs != null || file.hasMultipleGifFrames(), durationMs = durationMs)
            }
            header.matchesAscii(readSize, 0, "RIFF") && header.matchesAscii(readSize, 8, "WEBP") -> {
                val durationMs = file.readWebpDurationMs()
                ImageFileFormat("image/webp", "webp", isAnimated = durationMs != null || file.hasWebpAnimationMarker(), durationMs = durationMs)
            }
            header.matchesAscii(readSize, 0, "BM") -> ImageFileFormat("image/bmp", "bmp")
            header.isAvifHeader(readSize) -> ImageFileFormat("image/avif", "avif", isAnimated = file.hasAvifAnimationMarker())
            else -> null
        }
    }

    /** 将外部来源的 MIME 规范化到本 Worker 支持写入相册的图片类型。 */
    private fun normalizeImageMimeType(rawMimeType: String?): String? {
        return when (rawMimeType?.substringBefore(";")?.trim()?.lowercase()) {
            "image/jpeg", "image/jpg", "image/pjpeg" -> "image/jpeg"
            "image/png", "image/x-png" -> "image/png"
            "image/webp" -> "image/webp"
            "image/gif" -> "image/gif"
            "image/avif" -> "image/avif"
            "image/bmp", "image/x-bmp", "image/x-ms-bmp" -> "image/bmp"
            else -> null
        }
    }

    /** 根据规范化 MIME 生成最终扩展名，确保文件名和 MediaStore MIME 保持一致。 */
    private fun imageExtensionForMimeType(mimeType: String): String {
        return when (mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/avif" -> "avif"
            "image/bmp" -> "bmp"
            else -> "jpg"
        }
    }

    /** 从 URL 路径提取图片扩展名，只作为文件头和 MIME 都不可用时的兜底。 */
    private fun imageExtensionFromUrl(url: String): String? {
        return url.substringBefore("?")
            .substringBefore("#")
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp") }
            ?.let { if (it == "jpeg") "jpg" else it }
    }

    /** 将 URL 扩展名映射回 MIME，避免兜底路径生成扩展名和媒体类型不一致的文件。 */
    private fun mimeTypeForImageExtension(extension: String): String? {
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "avif" -> "image/avif"
            "bmp" -> "image/bmp"
            else -> null
        }
    }

    /** 检查字节数组指定位置是否匹配一组无符号字节，供文件头格式识别复用。 */
    private fun ByteArray.matchesBytes(readSize: Int, offset: Int, vararg expected: Int): Boolean {
        if (readSize < offset + expected.size) return false
        return expected.indices.all { index -> this[offset + index].toInt() and 0xff == expected[index] }
    }

    /** 检查字节数组指定位置是否匹配 ASCII 文本，避免把二进制头转成字符串后再做模糊判断。 */
    private fun ByteArray.matchesAscii(readSize: Int, offset: Int, expected: String): Boolean {
        if (readSize < offset + expected.length) return false
        return expected.indices.all { index -> this[offset + index].toInt().toChar() == expected[index] }
    }

    /** 判断 ISO BMFF 文件头是否声明 AVIF/AVIS 品牌，用于识别 URL 无后缀的 AVIF 图片。 */
    private fun ByteArray.isAvifHeader(readSize: Int): Boolean {
        if (!matchesAscii(readSize, 4, "ftyp")) return false
        // AVIF 的主品牌或兼容品牌会出现在 ftyp box 中；只扫描已读取头部，避免额外读完整文件。
        val brandText = copyOfRange(8, readSize).toString(Charsets.US_ASCII)
        return brandText.contains("avif") || brandText.contains("avis")
    }

    /**
     * 识别 WebP 是否包含动画 chunk。
     *
     * 下载保存不会转码，所以这里仅作为诊断字段：如果日志显示 `animated=true`，但系统相册仍只显示首帧，
     * 说明问题更可能是外部相册不播放 Animated WebP，而不是本 Worker 写坏了文件。
     */
    private fun File.hasWebpAnimationMarker(): Boolean {
        val bytes = readAnimationMarkerBytes()
        return bytes.containsAsciiMarker("ANIM") || bytes.containsAsciiMarker("ANMF")
    }

    /**
     * 读取 Animated WebP 的总时长。
     *
     * WebP 动画帧以 `ANMF` chunk 存储，帧持续时间是 24-bit little-endian 毫秒值；这里只做元数据解析，
     * 不参与文件转码，失败时返回空并保持原样保存。
     */
    private fun File.readWebpDurationMs(): Long? {
        val bytes = readBytes()
        if (!bytes.containsAsciiMarker("ANMF")) return null
        var offset = 12
        var frameCount = 0
        var duration = 0L
        while (offset + 8 <= bytes.size) {
            val chunk = bytes.asciiAt(offset, 4)
            val chunkSize = bytes.littleEndianInt(offset + 4)
            val payloadOffset = offset + 8
            if (chunkSize <= 0 || payloadOffset + chunkSize > bytes.size) break
            if (chunk == "ANMF" && chunkSize >= 16) {
                frameCount += 1
                duration += bytes.littleEndian24(payloadOffset + 12).coerceAtLeast(1).toLong()
            }
            offset = payloadOffset + chunkSize + (chunkSize and 1)
        }
        return duration.takeIf { frameCount > 1 && it > 0L }
    }

    /**
     * 识别 PNG 是否包含 APNG 动画 chunk。
     *
     * APNG 仍使用 image/png 和 .png 扩展名；部分相册会把它按普通 PNG 首帧展示，因此这里把动画标记打到日志里。
     */
    private fun File.hasApngAnimationMarker(): Boolean {
        return readAnimationMarkerBytes().containsAsciiMarker("acTL")
    }

    /**
     * 读取 APNG 的总时长。
     *
     * APNG 的每帧时长在 `fcTL` chunk 中，以分子/分母形式表示；只用于 MediaStore DURATION 元数据，
     * 不改变 PNG/APNG 原始字节。
     */
    private fun File.readApngDurationMs(): Long? {
        val bytes = readBytes()
        if (!bytes.containsAsciiMarker("acTL")) return null
        var offset = 8
        var frameCount = 0
        var duration = 0L
        while (offset + 8 <= bytes.size) {
            val chunkSize = bytes.bigEndianInt(offset)
            val chunkTypeOffset = offset + 4
            val chunk = bytes.asciiAt(chunkTypeOffset, 4)
            val payloadOffset = offset + 8
            if (payloadOffset + chunkSize > bytes.size) break
            if (chunk == "fcTL" && chunkSize >= 26) {
                frameCount += 1
                val numerator = bytes.bigEndianShort(payloadOffset + 20)
                val denominator = bytes.bigEndianShort(payloadOffset + 22).takeIf { it > 0 } ?: 100
                duration += ((numerator.toLong().coerceAtLeast(1L) * 1000L) / denominator).coerceAtLeast(1L)
            }
            offset = payloadOffset + chunkSize + 4
        }
        return duration.takeIf { frameCount > 1 && it > 0L }
    }

    /**
     * 识别 AVIF 是否声明动画品牌。
     *
     * Android/相册对 Animated AVIF 支持差异很大；日志记录该标记可区分“文件是动画但播放器不支持”和“服务端返回静态 AVIF”。
     */
    private fun File.hasAvifAnimationMarker(): Boolean {
        return readAnimationMarkerBytes().containsAsciiMarker("avis")
    }

    /**
     * 识别 GIF 是否包含多帧。
     *
     * 这里按 GIF 块结构跳过扩展块和图像数据块，而不是直接搜索 0x2C，减少压缩数据中偶然字节导致的误判。
     */
    private fun File.hasMultipleGifFrames(): Boolean {
        val bytes = readAnimationMarkerBytes()
        if (bytes.size < 13) return false
        var position = 13
        val logicalScreenPacked = bytes[10].toInt() and 0xff
        if (logicalScreenPacked and 0x80 != 0) {
            position += 3 * (1 shl ((logicalScreenPacked and 0x07) + 1))
        }

        var imageFrameCount = 0
        while (position < bytes.size) {
            when (bytes[position].toInt() and 0xff) {
                0x2c -> {
                    imageFrameCount += 1
                    if (imageFrameCount > 1) return true
                    if (position + 9 >= bytes.size) return false
                    val imagePacked = bytes[position + 9].toInt() and 0xff
                    position += 10
                    if (imagePacked and 0x80 != 0) {
                        position += 3 * (1 shl ((imagePacked and 0x07) + 1))
                    }
                    if (position >= bytes.size) return false
                    position += 1
                    position = bytes.skipGifSubBlocks(position)
                }
                0x21 -> {
                    position += 2
                    position = bytes.skipGifSubBlocks(position)
                }
                0x3b -> return false
                else -> return false
            }
        }
        return false
    }

    /**
     * 读取 GIF 多帧总时长。
     *
     * GIF 帧延迟来自 Graphic Control Extension，单位是 1/100 秒；很多相册会参考媒体库 DURATION，
     * 因此这里把可解析的总时长写到 MediaStore，但不对 GIF 内容做任何重编码。
     */
    private fun File.readGifDurationMs(): Long? {
        val bytes = readBytes()
        if (bytes.size < 13) return null
        var position = 13
        val logicalScreenPacked = bytes[10].toInt() and 0xff
        if (logicalScreenPacked and 0x80 != 0) {
            position += 3 * (1 shl ((logicalScreenPacked and 0x07) + 1))
        }

        var frameCount = 0
        var pendingDelayCs = 10
        var duration = 0L
        while (position < bytes.size) {
            when (bytes[position].toInt() and 0xff) {
                0x2c -> {
                    frameCount += 1
                    duration += pendingDelayCs.coerceAtLeast(1) * 10L
                    pendingDelayCs = 10
                    if (position + 9 >= bytes.size) break
                    val imagePacked = bytes[position + 9].toInt() and 0xff
                    position += 10
                    if (imagePacked and 0x80 != 0) {
                        position += 3 * (1 shl ((imagePacked and 0x07) + 1))
                    }
                    if (position >= bytes.size) break
                    position += 1
                    position = bytes.skipGifSubBlocks(position)
                }
                0x21 -> {
                    val label = bytes.getOrNull(position + 1)?.toInt()?.and(0xff) ?: break
                    if (label == 0xf9 && position + 7 < bytes.size && (bytes[position + 2].toInt() and 0xff) >= 4) {
                        pendingDelayCs = bytes.littleEndianShort(position + 4).takeIf { it > 0 } ?: 10
                    }
                    position += 2
                    position = bytes.skipGifSubBlocks(position)
                }
                0x3b -> break
                else -> break
            }
        }
        return duration.takeIf { frameCount > 1 && it > 0L }
    }

    /** 读取用于动画标记诊断的前段字节，限制大小避免日志增强拖慢大图下载。 */
    private fun File.readAnimationMarkerBytes(): ByteArray {
        val maxRead = length().coerceAtMost(ANIMATION_MARKER_SCAN_BYTES.toLong()).toInt()
        if (maxRead <= 0) return ByteArray(0)
        val buffer = ByteArray(maxRead)
        val readSize = inputStream().use { input -> input.read(buffer) }
        return if (readSize > 0) buffer.copyOf(readSize) else ByteArray(0)
    }

    /** 检查字节数组中是否包含指定 ASCII 标记，用于 WebP chunk 诊断。 */
    private fun ByteArray.containsAsciiMarker(marker: String): Boolean {
        val markerBytes = marker.toByteArray(Charsets.US_ASCII)
        if (size < markerBytes.size) return false
        return (0..size - markerBytes.size).any { start ->
            markerBytes.indices.all { offset -> this[start + offset] == markerBytes[offset] }
        }
    }

    /** 按 ASCII 读取固定长度 chunk 类型，超出边界时返回空字符串以便解析器安全终止。 */
    private fun ByteArray.asciiAt(offset: Int, length: Int): String {
        if (offset < 0 || offset + length > size) return ""
        return copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)
    }

    /** 读取 24-bit little-endian 整数，用于 WebP 帧持续时间。 */
    private fun ByteArray.littleEndian24(offset: Int): Int {
        if (offset + 3 > size) return 0
        return (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16)
    }

    /** 读取 16-bit little-endian 整数，用于 GIF 帧延迟。 */
    private fun ByteArray.littleEndianShort(offset: Int): Int {
        if (offset + 2 > size) return 0
        return (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
    }

    /** 读取 32-bit little-endian 整数，用于 WebP chunk 大小。 */
    private fun ByteArray.littleEndianInt(offset: Int): Int {
        if (offset + 4 > size) return 0
        return (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)
    }

    /** 读取 16-bit big-endian 整数，用于 APNG 分子/分母。 */
    private fun ByteArray.bigEndianShort(offset: Int): Int {
        if (offset + 2 > size) return 0
        return ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)
    }

    /** 读取 32-bit big-endian 整数，用于 PNG/APNG chunk 大小。 */
    private fun ByteArray.bigEndianInt(offset: Int): Int {
        if (offset + 4 > size) return 0
        return ((this[offset].toInt() and 0xff) shl 24) or
            ((this[offset + 1].toInt() and 0xff) shl 16) or
            ((this[offset + 2].toInt() and 0xff) shl 8) or
            (this[offset + 3].toInt() and 0xff)
    }

    /** 跳过 GIF 子块序列，调用方传入第一个子块长度字节所在位置。 */
    private fun ByteArray.skipGifSubBlocks(start: Int): Int {
        var position = start
        while (position < size) {
            val blockSize = this[position].toInt() and 0xff
            position += 1
            if (blockSize == 0) return position
            position += blockSize
        }
        return position
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

    /** 日志中只记录 Cookie 是否存在和长度，不输出真实 Cookie 内容，避免泄露登录态。 */
    private fun String?.cookieLogSummary(): String {
        return if (isNullOrBlank()) "empty" else "present(length=$length)"
    }

    /** 清理文件夹名里的非法字符，避免网页标题直接作为目录名时创建失败；最终长度由保存工具统一限制。 */
    private fun sanitizeFileName(raw: String): String {
        return raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_").trim()
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

    /** 已识别出的真实图片格式，发布相册时同时决定 MediaStore MIME 和最终文件扩展名。 */
    private data class ImageFileFormat(
        /** 规范化后的图片 MIME 类型，例如 image/gif 或 image/webp；写入 MediaStore 供系统相册识别。 */
        val mimeType: String,

        /** 与 MIME 对应的文件扩展名，不包含点；用于生成 001.gif、002.webp 这类最终文件名。 */
        val extension: String,

        /** 是否在真实文件字节中识别到 GIF 多帧或 WebP 动画 chunk；用于判断相册静态展示是否只是播放器能力问题。 */
        val isAnimated: Boolean = false,

        /** 可解析出的动画总时长，单位毫秒；为空时只表示无法识别，不代表文件一定是静态。 */
        val durationMs: Long? = null,
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

    /** 图片 内容质量的轻量抽样结果，用于下载阶段过滤明显无效的资源。 */
    private data class ImageQuality(
        val transparentRatio: Double,
        val avgLuminance: Int,
        val isNearlySolid: Boolean,
    )

}
