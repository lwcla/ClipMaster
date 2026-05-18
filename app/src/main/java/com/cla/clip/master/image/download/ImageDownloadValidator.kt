package com.cla.clip.master.image.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

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

/**
 * 图片下载内容质量校验器。
 *
 * 只校验已经成功落盘的临时图片，主动过滤跟踪像素、透明占位图和反盗链返回的纯色错误图；过滤结果通过
 * [FilteredImageException] 上报，让 Worker 能把“过滤”与“失败”分开计数。
 */
object ImageDownloadValidator {

    /** 下载后校验真实图片内容，主动过滤透明像素、占位图和反盗链返回的纯色错误图。 */
    fun validateDownloadedImage(file: File, mimeType: String) {
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

    /** 图片内容质量的轻量抽样结果，用于下载阶段过滤明显无效的资源。 */
    private data class ImageQuality(
        /** 抽样像素中透明或近透明像素比例，0 到 1。 */
        val transparentRatio: Double,

        /** 非透明像素平均亮度，用于判断黑白错误图。 */
        val avgLuminance: Int,

        /** 是否几乎没有亮度变化，常见于占位图或反盗链错误图。 */
        val isNearlySolid: Boolean,
    )
}
