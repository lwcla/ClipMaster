package com.cla.clip.master.image.download

import com.cla.clip.base.general.dao.ImageExtractItemData
import com.cla.clip.base.general.repository.ImageExtractRepository
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.master.image.format.ImageFormatSniffer
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File

/**
 * 单张图片临时文件下载器。
 *
 * 负责从网络请求到缓存落盘、真实格式识别、内容质量校验和图片项状态回写的完整单图流程。
 * 它不决定批次进度、不发送通知，也不写入 MediaStore；这些由 Worker 和发布器负责，避免单图能力变成新的大入口。
 */
internal class ImageTempFileDownloader(
    private val okHttpClient: OkHttpClient,
    private val imageExtractRepo: ImageExtractRepository,
) {

    /**
     * 下载单张图片到临时目录。
     *
     * [pageUrl] 只作为 Referer 兜底；下载成功返回 [ImageTempDownloadResult.Success]，内容质量过滤返回
     * [ImageTempDownloadResult.Filtered]，网络、IO、格式或解码异常返回 [ImageTempDownloadResult.Failed]。
     */
    suspend fun downloadToTemp(item: ImageExtractItemData, tempDir: File, pageUrl: String): ImageTempDownloadResult {
        return runCatching {
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

            val tempFile = File(tempDir, "${item.id}.download")
            writeResponseBodyToTempFile(response, tempFile)

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
            ImageTempDownloadResult.Success(
                DownloadedTempImage(
                    item = item,
                    tempFile = tempFile,
                    mimeType = imageFormat.mimeType,
                    extension = imageFormat.extension,
                    isAnimated = imageFormat.isAnimated,
                    durationMs = imageFormat.durationMs,
                )
            )
        }.getOrElse { tr ->
            handleDownloadFailure(item, tr)
        }
    }

    /**
     * 执行单张图片请求。
     *
     * 请求头统一交给 [ImageRequestHeaderBuilder]，保证 Worker、预览和后续复用入口尽量使用相同图片协商语义。
     * 非 2xx 响应会主动关闭响应体并作为真实失败上报。
     */
    private fun executeRequest(url: String, referer: String?, userAgent: String?, cookie: String?): Response {
        logD(TAG) {
            "executeRequest: 请求图片 url=$url referer=$referer userAgent=$userAgent " +
                "accept=${ImageRequestHeaderBuilder.IMAGE_REQUEST_ACCEPT} cookie=${cookie.cookieLogSummary()}"
        }
        val response = okHttpClient.newCall(
            ImageRequestHeaderBuilder.buildImageRequest(url, referer, userAgent, cookie)
        ).execute()
        if (!response.isSuccessful) {
            response.close()
            error("HTTP ${response.code} ${response.message}, url=$url")
        }
        return response
    }

    /**
     * 将响应体写入临时文件。
     *
     * 临时文件固定使用 `.download` 后缀，避免在真实格式识别前让错误 URL 后缀影响后续发布判断。
     */
    private fun writeResponseBodyToTempFile(response: Response, tempFile: File) {
        response.use { resp ->
            val body = resp.body ?: error("Empty image body")
            body.byteStream().use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    /**
     * 统一处理单图失败并回写数据库状态。
     *
     * [FilteredImageException] 表示内容质量过滤，会进入 filtered 计数；其他异常都视为真实下载失败。
     */
    private suspend fun handleDownloadFailure(item: ImageExtractItemData, tr: Throwable): ImageTempDownloadResult {
        return if (tr is FilteredImageException) {
            logD(TAG) {
                "downloadToTemp: 图片被过滤 itemId=${item.id} url=${item.url} reason=${tr.message}"
            }
            imageExtractRepo.updateItemStatus(item.id, ImageExtractItemData.STATUS_FILTERED, errorMsg = tr.message)
            ImageTempDownloadResult.Filtered(item, tr.message)
        } else {
            logE(TAG, tr) {
                "downloadToTemp: 图片下载失败 itemId=${item.id} url=${item.url}"
            }
            imageExtractRepo.updateItemStatus(item.id, ImageExtractItemData.STATUS_FAILED, errorMsg = tr.message)
            ImageTempDownloadResult.Failed(item, tr)
        }
    }

    private companion object {
        /** 日志标签，保持和旧 Worker 日志字段一致，方便对比重构前后的下载链路。 */
        private const val TAG = "ImageTempFileDownloader"
    }
}
