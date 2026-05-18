package com.cla.clip.master.image.format

import java.io.File

/** 动画标记扫描上限，单位字节；只取前段文件即可覆盖 GIF 帧头和 WebP ANIM/ANMF chunk，避免为日志读取超大图片。 */
private const val ANIMATION_MARKER_SCAN_BYTES = 512 * 1024

/**
 * 图片动图元数据读取器。
 *
 * 只负责从已下载的真实文件字节中读取 GIF/WebP/APNG/AVIF 是否可能为动图以及可解析时长；不会修改、转码或重写图片内容。
 * Worker 通过它区分“文件本身是动图但相册不播放”和“服务端返回静态资源”。
 */
object ImageAnimationMetadataReader {

    /**
     * 识别 WebP 是否包含动画 chunk。
     *
     * 下载保存不会转码，所以这里仅作为诊断字段：如果日志显示 `animated=true`，但系统相册仍只显示首帧，
     * 说明问题更可能是外部相册不播放 Animated WebP，而不是 Worker 写坏了文件。
     */
    fun hasWebpAnimationMarker(file: File): Boolean {
        val bytes = file.readAnimationMarkerBytes()
        return bytes.containsAsciiMarker("ANIM") || bytes.containsAsciiMarker("ANMF")
    }

    /**
     * 读取 Animated WebP 的总时长。
     *
     * WebP 动画帧以 `ANMF` chunk 存储，帧持续时间是 24-bit little-endian 毫秒值；这里只做元数据解析，
     * 不参与文件转码，失败时返回空并保持原样保存。
     */
    fun readWebpDurationMs(file: File): Long? {
        val bytes = file.readBytes()
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
    fun hasApngAnimationMarker(file: File): Boolean {
        return file.readAnimationMarkerBytes().containsAsciiMarker("acTL")
    }

    /**
     * 读取 APNG 的总时长。
     *
     * APNG 的每帧时长在 `fcTL` chunk 中，以分子/分母形式表示；只用于 MediaStore DURATION 元数据，
     * 不改变 PNG/APNG 原始字节。
     */
    fun readApngDurationMs(file: File): Long? {
        val bytes = file.readBytes()
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
    fun hasAvifAnimationMarker(file: File): Boolean {
        return file.readAnimationMarkerBytes().containsAsciiMarker("avis")
    }

    /**
     * 识别 GIF 是否包含多帧。
     *
     * 这里按 GIF 块结构跳过扩展块和图像数据块，而不是直接搜索 0x2C，减少压缩数据中偶然字节导致的误判。
     */
    fun hasMultipleGifFrames(file: File): Boolean {
        val bytes = file.readAnimationMarkerBytes()
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
    fun readGifDurationMs(file: File): Long? {
        val bytes = file.readBytes()
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
}

/** 读取用于动画标记诊断的前段字节，限制大小避免日志增强拖慢大图下载。 */
private fun File.readAnimationMarkerBytes(): ByteArray {
    val maxRead = length().coerceAtMost(ANIMATION_MARKER_SCAN_BYTES.toLong()).toInt()
    if (maxRead <= 0) return ByteArray(0)
    val buffer = ByteArray(maxRead)
    val readSize = inputStream().use { input -> input.read(buffer) }
    return if (readSize > 0) buffer.copyOf(readSize) else ByteArray(0)
}
