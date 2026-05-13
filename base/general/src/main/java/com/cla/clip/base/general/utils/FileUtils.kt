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

/** 文件保存工具日志标签，用于排查 MediaStore 写入、扫描和清理问题。 */
private const val TAG = "FileUtils"

/** 应用图标缓存目录名称，用于保存剪贴板来源应用的图标文件。 */
private const val APP_ICONS_DIR = "app_icons"

/** 媒体文件统一保存的根目录名称，视频和图片都会归到该应用目录下。 */
private const val MEDIA_ROOT_DIR = "clipMaster"

/** 图片保存到 MediaStore 时使用的相对路径前缀。 */
private const val IMAGE_MEDIA_RELATIVE_PREFIX = "DCIM/$MEDIA_ROOT_DIR"

/** 生成不重名图片文件夹时的最大尝试次数，避免异常情况下无限循环。 */
private const val MAX_UNIQUE_FOLDER_ATTEMPTS = 1_000

/** 进程内已预留的图片文件夹名，避免并发下载任务抢到同一个目录。 */
private val reservedImageFolderNames = mutableSetOf<String>()

/**
 * 媒体保存目标描述。
 *
 * Video 会固定保存为 mp4；Image 需要调用方提供最终文件名、目录名和 MIME，确保批量图片能按网页标题分目录保存。
 */
sealed class SaveToFile(open val fileName: String) {
    /** 视频保存目标，fileName 不包含扩展名，最终会追加 `.mp4`。 */
    data class Video(override val fileName: String) : SaveToFile(fileName)

    data class Image(
        /** 图片最终文件名，包含扩展名；由 Worker 根据网页顺序和响应类型生成。 */
        override val fileName: String,

        /** 图片批量下载目录名，已经由 createUniqueImageFolderName 做过冲突规避。 */
        val folderName: String,

        /** 图片 MIME 类型，写入 MediaStore 时用于系统识别媒体格式。 */
        val mimeType: String
    ) : SaveToFile(fileName)
}

/**
 * 在 Android 10+ 上，推荐使用 MediaStore API 来保存媒体文件，这样可以让文件自动出现在相册等媒体库中，并且不需要申请存储权限。
 * 在 Android 10 以下，则需要使用传统的文件 API 来保存文件，并且需要申请存储权限（READ_EXTERNAL_STORAGE 和 WRITE_EXTERNAL_STORAGE）。
 */
fun SaveToFile.createPath(context: Context): MediaStoreTarget {
    runCatching {
        val name = when (this@createPath) {
            is SaveToFile.Video -> "${fileName}.mp4"
            is SaveToFile.Image -> fileName
        }

        val type = when (this@createPath) {
            is SaveToFile.Video -> "video/mp4"
            is SaveToFile.Image -> mimeType
        }

        val url = when (this) {
            is SaveToFile.Video -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            is SaveToFile.Image -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
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
                val relativePath = when (this@createPath) {
                    is SaveToFile.Video -> IMAGE_MEDIA_RELATIVE_PREFIX
                    is SaveToFile.Image -> "$IMAGE_MEDIA_RELATIVE_PREFIX/${folderName}"
                }
                // 图片批量下载需要按网页标题单独建目录，方便用户在相册或文件管理器里查看一组图片。
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
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

            val downloadDir = when (this@createPath) {
                is SaveToFile.Video -> File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    MEDIA_ROOT_DIR
                )

                is SaveToFile.Image -> File(
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), MEDIA_ROOT_DIR),
                    folderName
                )
            }
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
        is SaveToFile.Image -> mimeType
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
/** 使用 MediaStoreTarget 清理下载失败半成品；封装目标对象形式，供 Worker 失败路径直接调用。 */
suspend fun SaveToFile.failure(context: Context, target: MediaStoreTarget) = withContext(Dispatchers.IO) {
    failure(context, target.uri, target.path)
}

/**
 * 按 URI 或真实路径清理下载失败半成品。
 *
 * Android 10+ 优先删除 MediaStore 记录；旧系统删除文件路径。调用方应在 IO 线程使用，避免主线程文件操作。
 */
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

/**
 * 递归清理文件或目录。
 *
 * 用于删除 M3U8 分片临时目录和异常遗留文件；失败只记录日志，不向外抛出，避免清理失败覆盖原始下载错误。
 */
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
    /** Android 10+ MediaStore 插入得到的 URI；旧系统直接写文件时为空。 */
    val uri: Uri?,

    /** 可记录或展示的输出位置，Android 10+ 通常是 URI 字符串，旧系统是真实文件路径。 */
    val path: String,

    /** 已打开的输出流，调用方负责写入并关闭。 */
    val outputStream: OutputStream
)

/**
 * 图片批量下载最终使用的唯一目录信息。
 *
 * folderName 用于传给 SaveToFile.Image 创建实际文件，relativePath 用于业务层记录本批次输出目录并展示给用户。
 * 这个结构把“目录如何命名”和“目录在媒体库中的相对路径”放在保存工具层统一维护，避免上层 Worker 复制存储规则。
 */
data class UniqueImageFolder(
    /** 实际创建文件时使用的目录名，不包含父级 DCIM/clipMaster 前缀。 */
    val folderName: String,

    /** 记录给业务层或用户查看的媒体库相对路径。 */
    val relativePath: String,
)

/**
 * 规范化图片批量下载目录。
 *
 * 批次表里保存的是 `DCIM/clipMaster/<目录名>` 这类相对路径；不同调用方可能传入空字符串、带首尾斜杠或尾部 `/` 的值。
 * 打开文件夹前统一清洗，可以避免 DocumentsUI 或文件管理器因为路径格式细节无法定位目录。
 */
fun normalizeImageOutputDir(outputDir: String?): String? {
    return outputDir
        ?.trim()
        ?.replace('\\', '/')
        ?.trim('/')
        ?.takeIf { it.isNotBlank() }
}

/**
 * 将图片批量下载相对目录转换成公共存储 File。
 *
 * 这个方法只用于构建旧式文件管理器兜底 Intent；Android 10+ 主流程仍应依赖 MediaStore/DocumentsUI，不能把真实路径当作唯一可用入口。
 */
fun Context.imageOutputDirToPublicFile(outputDir: String?): File? {
    val normalizedOutputDir = normalizeImageOutputDir(outputDir) ?: return null
    return File(Environment.getExternalStorageDirectory(), normalizedOutputDir)
}

/** 为图片批量下载选择未占用的文件夹，避免同名网页任务保存到同一个相册目录。 */
fun Context.createUniqueImageFolderName(baseFolderName: String): UniqueImageFolder {
    synchronized(reservedImageFolderNames) {
        repeat(MAX_UNIQUE_FOLDER_ATTEMPTS) { index ->
            val candidate = if (index == 0) baseFolderName else "${baseFolderName}_${index}"
            if (!reservedImageFolderNames.contains(candidate) && !imageFolderExists(candidate)) {
                // 先预留名称，避免并发下载在媒体库记录创建前同时拿到同一个文件夹。
                reservedImageFolderNames.add(candidate)
                return UniqueImageFolder(candidate, "$IMAGE_MEDIA_RELATIVE_PREFIX/$candidate")
            }
        }
        val fallback = "${baseFolderName}_${System.currentTimeMillis()}"
        reservedImageFolderNames.add(fallback)
        return UniqueImageFolder(fallback, "$IMAGE_MEDIA_RELATIVE_PREFIX/$fallback")
    }
}

/** 按系统版本判断图片文件夹是否存在：Android 10+ 查询媒体库，旧系统查询真实目录。 */
private fun Context.imageFolderExists(folderName: String): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        mediaStoreImageFolderExists(folderName)
    } else {
        val parentDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            MEDIA_ROOT_DIR
        )
        File(parentDir, folderName).exists()
    }
}

/** Android 10+ 的公共媒体目录由 MediaStore 管理，需要通过 RELATIVE_PATH 判断目录里是否已有图片。 */
private fun Context.mediaStoreImageFolderExists(folderName: String): Boolean {
    val relativePath = "$IMAGE_MEDIA_RELATIVE_PREFIX/$folderName"
    val projection = arrayOf(MediaStore.Images.Media._ID)
    val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?)"
    val selectionArgs = arrayOf(relativePath, "$relativePath/")

    return contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        selectionArgs,
        null
    )?.use { cursor ->
        cursor.moveToFirst()
    } ?: false
}

/**
 * 保存来源应用图标到应用私有目录。
 *
 * packageName 或 appIcon 为空时返回 null；同一包名会覆盖旧图标，调用方可结合 iconHash 判断是否需要重新保存。
 */
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
