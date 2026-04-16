package com.cla.clip.base.general.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

private const val TAG = "FileUtils"

private const val APP_ICONS_DIR = "app_icons"

sealed class SaveToFile(open val fileName: String) {
    data class Video(override val fileName: String) : SaveToFile(fileName)
}

/**
 * 在 Android 10+ 上，推荐使用 MediaStore API 来保存媒体文件，这样可以让文件自动出现在相册等媒体库中，并且不需要申请存储权限。
 * 在 Android 10 以下，则需要使用传统的文件 API 来保存文件，并且需要申请存储权限（READ_EXTERNAL_STORAGE 和 WRITE_EXTERNAL_STORAGE）。
 */
fun SaveToFile.createPath(context: Context): MediaStoreTarget {
    runCatching {
        val name = when (this@createPath) {
            is SaveToFile.Video -> "${fileName}.mp4"
        }

        val type = when (this@createPath) {
            is SaveToFile.Video -> "video/mp4"
        }

        val url = when (this) {
            is SaveToFile.Video -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val target = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, type)
                //// 选项 1：保存到相机相册（用户最常用）
                //put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
                //// 选项 2：保存到 Movies（电影）
                //put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/MyApp")
                //// 选项 3：保存到 Pictures（图片）
                //put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/MyApp")
                //// 选项 4：保存到 Downloads（下载）
                //put(MediaStore.MediaColumns.RELATIVE_PATH, "Downloads/MyApp")
                // 设置相对路径，文件会自动保存在这个目录下，如果目录不存在会自动创建
                // 要设置到相机相册下，这样才能在下载完成之后，可以让用户在相册里看到这个视频
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/clipMaster")
                // 标记为正在下载，下载完成后再改为 0
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(url, contentValues)
            if (uri == null) {
                logE(TAG) { "创建文件失败" }
                throw Exception("创建文件失败")
            }

            val outputStream = context.contentResolver.openOutputStream(uri)
            if (outputStream == null) {
                logE(TAG) { "打开输出流失败" }
                throw Exception("打开输出流失败")
            }

            MediaStoreTarget(uri, uri.toString(), outputStream)
        } else {
            if (!context.hasStoragePermission()) {
                logE(TAG) { "没有存储权限，无法保存文件" }
                throw Exception("没有存储权限，无法保存文件")
            }

            val downloadDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "clipMaster"
            )
            downloadDir.mkdirs()

            val saveFile = File(downloadDir, name)
            val outputStream = saveFile.outputStream()

            MediaStoreTarget(uri = null, saveFile.absolutePath, outputStream)
        }

        logD(TAG) { "创建文件成功: target=${target}" }
        return target
    }.getOrElse {
        logE(TAG, it) { "创建文件失败 22" }
        throw Exception("创建文件失败", it)
    }
}

/**
 * 在 Android 10+ 上，下载完成后需要将 IS_PENDING 从 1 改为 0，才能让媒体文件对系统和其他应用可见。
 * 在 Android 10 以下，则需要调用 MediaScannerConnection.scanFile() 来通知系统扫描新文件，以便它出现在相册等媒体库中。
 */
fun SaveToFile.success(context: Context, target: MediaStoreTarget) {
    val uri = target.uri
    val path = target.path
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0) // 标记下载完成，媒体文件现在可见
        }
        logD(TAG) { "下载完成，现在去标记媒体文件可见" }
        context.contentResolver.update(uri, values, null, null)
    }
    // Android 10 以下需要调用 MediaScannerConnection 来扫描新文件，否则它不会出现在相册等媒体库中
    // 但我们这里不管android版本，统一调用 MediaScannerConnection 来扫描新文件，这样也能兼容一些特殊情况（比如某些设备的媒体库更新不及时等）
    if (path.isBlank()) {
        return
    }

    val type = when (this) {
        is SaveToFile.Video -> "video/mp4"
    }

    MediaScannerConnection.scanFile(
        context, // Service 本身就是 Context
        arrayOf(path),
        arrayOf(type), // 也可以传 null，让系统自己判断
    ) { scannedPath, scannedUri ->
        if (scannedUri != null) {
            logD(TAG) { "媒体扫描成功: path=$scannedPath, uri=$scannedUri" }
        } else {
            logE(TAG) { "媒体扫描失败: path=$scannedPath" }
        }
    }
}

/**
 * 下载失败时，删除半成品文件，避免用户看到损坏的文件。
 * 在 Android 10+ 上，直接删除 MediaStore 中的记录即可。
 * 在 Android 10 以下，则需要删除文件，并且调用 MediaScannerConnection.scanFile() 来通知系统更新媒体库。
 */
suspend fun SaveToFile.failure(context: Context, target: MediaStoreTarget) = withContext(Dispatchers.IO) {
    failure(context, target.uri, target.path)
}

suspend fun SaveToFile.failure(context: Context, uri: Uri?, path: String?) = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
        // 删除半成品
        runCatching {
            context.contentResolver.delete(uri, null, null)
            logD(TAG) { "下载失败，已删除半成品: uri=$uri" }
        }.onFailure {
            logE(TAG, it) { "下载失败，删除半成品失败: uri=$uri" }
        }
    } else {
        if (path.isNullOrBlank()) {
            return@withContext
        }

        val file = File(path)
        if (file.exists()) {
            runCatching {
                file.delete()
                logD(TAG) { "下载失败，已删除半成品: path=$path" }
            }.getOrElse {
                logE(TAG, it) { "下载失败，删除半成品失败: path=$path" }
            }
        }
    }
}

fun File.clear() {
    if (!exists()) {
        return
    }

    runCatching {
        if (isDirectory) {
            if (!deleteRecursively()) {
                logE(TAG) { "清理目录失败: path=${absolutePath}" }
            } else {
                logD(TAG) { "清理目录成功: path=${absolutePath}" }
            }
        } else {
            if (!delete()) {
                logE(TAG) { "清理文件失败: path=${absolutePath}" }
            } else {
                logD(TAG) { "清理文件成功: path=${absolutePath}" }
            }
        }
    }.onFailure {
        logE(TAG, it) { "清理文件或目录出错: path=${absolutePath}" }
    }
}

data class MediaStoreTarget(
    val uri: Uri?,
    val path: String,
    val outputStream: OutputStream
)

fun Context.saveIcon(packageName: String?, appIcon: Bitmap?): String? {
    if (appIcon == null || packageName.isNullOrBlank()) {
        return null
    }

    val iconDir = File(filesDir, APP_ICONS_DIR)
    if (!iconDir.exists()) {
        iconDir.mkdirs()
    }

    val iconFile = File(iconDir, "$packageName.png")
    return try {
        FileOutputStream(iconFile).use { out ->
            appIcon.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        iconFile.absolutePath
    } catch (e: IOException) {
        e.printStackTrace()
        null
    }
}