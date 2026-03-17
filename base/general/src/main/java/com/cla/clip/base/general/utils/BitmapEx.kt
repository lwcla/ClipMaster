package com.cla.clip.base.general.utils

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/** 辅助方法：将 Bitmap 压缩为 PNG 格式的 byte[]，以便通过 Binder 传输。 */
fun Bitmap.toByteArray(): ByteArray {
    val outputStream = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.PNG, 100, outputStream)
    return outputStream.toByteArray()
}