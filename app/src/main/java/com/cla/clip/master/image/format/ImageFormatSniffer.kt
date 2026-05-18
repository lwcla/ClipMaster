package com.cla.clip.master.image.format

import android.graphics.BitmapFactory
import com.cla.clip.base.general.utils.logD
import java.io.File

/** 图片格式识别日志标签，单独拆出后便于从 Worker 日志中定位格式判断链路。 */
private const val TAG = "ImageFormatSniffer"

/**
 * 图片真实格式识别器。
 *
 * 下载阶段的响应头、URL 后缀和真实图片字节经常不一致；该对象统一负责从临时文件识别 JPEG、PNG、GIF、WebP、
 * AVIF、BMP 等真实格式，并在文件头不可识别时按 Android 解码结果、响应 MIME 和 URL 后缀依次兜底。
 */
object ImageFormatSniffer {

    /**
     * 识别已经下载到本地的真实图片格式。
     *
     * 优先读取文件头，能避开响应头或 URL 后缀不可信导致 GIF/Animated WebP 被当成 JPEG 入库的问题；如果文件头无法识别，
     * 再退回 Android 解码器、响应头和 URL 后缀，最后才使用 JPEG 兜底。
     */
    fun detectDownloadedImageFormat(file: File, responseMimeType: String?, url: String): ImageFileFormat {
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

    /** 从响应头提取可用图片 MIME，只作为真实文件识别失败时的兜底，不直接决定最终相册媒体类型。 */
    fun normalizeResponseImageMimeType(rawContentType: String?): String? {
        val contentType = rawContentType.orEmpty().substringBefore(";").trim().lowercase()
        return normalizeImageMimeType(contentType)
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
                val durationMs = ImageAnimationMetadataReader.readApngDurationMs(file)
                ImageFileFormat(
                    mimeType = "image/png",
                    extension = "png",
                    isAnimated = durationMs != null || ImageAnimationMetadataReader.hasApngAnimationMarker(file),
                    durationMs = durationMs,
                )
            }
            header.matchesAscii(readSize, 0, "GIF87a") || header.matchesAscii(readSize, 0, "GIF89a") -> {
                val durationMs = ImageAnimationMetadataReader.readGifDurationMs(file)
                ImageFileFormat(
                    mimeType = "image/gif",
                    extension = "gif",
                    isAnimated = durationMs != null || ImageAnimationMetadataReader.hasMultipleGifFrames(file),
                    durationMs = durationMs,
                )
            }
            header.matchesAscii(readSize, 0, "RIFF") && header.matchesAscii(readSize, 8, "WEBP") -> {
                val durationMs = ImageAnimationMetadataReader.readWebpDurationMs(file)
                ImageFileFormat(
                    mimeType = "image/webp",
                    extension = "webp",
                    isAnimated = durationMs != null || ImageAnimationMetadataReader.hasWebpAnimationMarker(file),
                    durationMs = durationMs,
                )
            }
            header.matchesAscii(readSize, 0, "BM") -> ImageFileFormat("image/bmp", "bmp")
            header.isAvifHeader(readSize) -> ImageFileFormat("image/avif", "avif", isAnimated = ImageAnimationMetadataReader.hasAvifAnimationMarker(file))
            else -> null
        }
    }

    /** 将外部来源的 MIME 规范化到 Worker 支持写入相册的图片类型。 */
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
}
