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

/** 给图标计算一个指纹，这样同一个图标就不用重复保存和取色 */
fun Bitmap.toStableHash(size: Int = 64): String {
    // 先把 Bitmap 规范化成固定大小的缩略图，这样即使原图有不同的分辨率，也能得到相同的指纹
    val scaled = scale(size, size)
    val output = ByteArrayOutputStream()

    scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
    val bytes = output.toByteArray()

    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

/** 辅助方法：将 Drawable 转为 Bitmap，并限制最大尺寸为 72x72 */
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