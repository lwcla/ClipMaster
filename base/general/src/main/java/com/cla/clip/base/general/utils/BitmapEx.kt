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
    // 先把 Bitmap 规范化成固定大小的缩略图，这样即使原图有不同的分辨率，也能得到相同的指纹
    val scaled = scale(size, size)
    val output = ByteArrayOutputStream()

    scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
    val bytes = output.toByteArray()

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

    val width = if (intrinsicWidth > size) size else intrinsicWidth
    val height = if (intrinsicHeight > size) size else intrinsicHeight

    // 如果本身就是合适大小的 BitmapDrawable，直接复用
    if (this is BitmapDrawable && bitmap.width <= size && bitmap.height <= size) {
        return bitmap
    }

    // 否则绘制一个新的 Bitmap
    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap
}.getOrNull()
