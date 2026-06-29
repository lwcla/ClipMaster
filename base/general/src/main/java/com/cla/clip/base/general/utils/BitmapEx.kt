package com.cla.clip.base.general.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** 辅助方法：将 Bitmap 压缩为 PNG 格式的 byte[]，以便通过 Binder 传输。 */
fun Bitmap.toByteArray(): ByteArray {
    /** PNG 压缩输出流；只在内存中短暂保存图标字节，调用方负责跨进程或文件写入。 */
    val outputStream = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.PNG, 100, outputStream)
    return outputStream.toByteArray()
}

/**
 * 给图标计算稳定指纹。
 *
 * 先缩放到固定尺寸再计算 SHA-256，避免同一图标因原始尺寸差异得到不同哈希；该值用于判断是否需要重复保存图标和提取主色。
 */
fun Bitmap.toStableHash(size: Int = 64): String {
    /** 规范化尺寸后的图标位图；不同原始尺寸的同一图标会得到一致输入。 */
    val scaled = scale(size, size)
    /** 参与 hash 的 PNG 字节输出流；只用于本地计算，不写入磁盘或日志。 */
    val output = ByteArrayOutputStream()

    scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
    /** 参与 SHA-256 的 PNG 字节；不包含剪贴内容或用户文本。 */
    val bytes = output.toByteArray()

    /** 图标内容稳定摘要；调用方只保存摘要字符串用于缓存判断。 */
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

/**
 * 将应用图标 Drawable 转成小尺寸 Bitmap。
 *
 * 转换失败时返回 null；会优先复用已经足够小的 BitmapDrawable，减少来源应用信息采集时的内存分配。
 */
fun Drawable?.iconBitmap(size: Int = 74): Bitmap? = runCatching {
    this ?: return null

    /** 目标图标宽度；自适应图标可能没有固有尺寸，此时回退到协议指定尺寸。 */
    val width = boundedIconDimension(intrinsicWidth, size)
    /** 目标图标高度；自适应图标可能没有固有尺寸，此时回退到协议指定尺寸。 */
    val height = boundedIconDimension(intrinsicHeight, size)

    /** 已经满足尺寸限制的 BitmapDrawable 可以直接复用，避免重复绘制和分配。 */
    if (this is BitmapDrawable && bitmap.width <= size && bitmap.height <= size) {
        return bitmap
    }

    /** 新绘制的小图标 Bitmap；尺寸已经过兜底和上限裁剪，保证 createBitmap 参数合法。 */
    val bitmap = createBitmap(width, height)
    /** 将 Drawable 绘制进 Bitmap 的画布；只在当前转换过程中使用。 */
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap
}.getOrNull()

/**
 * 计算 Drawable 转 Bitmap 时使用的安全边长。
 *
 * @param intrinsicDimension Drawable 原始固有边长，系统自适应图标可能返回 0 或负数。
 * @param maxSize 调用方允许的最大边长，同时也是无固有尺寸时的兜底边长。
 */
internal fun boundedIconDimension(intrinsicDimension: Int, maxSize: Int): Int {
    /** 安全最大边长；避免调用方传入非正数导致 Bitmap 尺寸非法。 */
    val safeMaxSize = maxSize.coerceAtLeast(1)
    return when {
        intrinsicDimension <= 0 -> safeMaxSize
        intrinsicDimension > safeMaxSize -> safeMaxSize
        else -> intrinsicDimension
    }
}
