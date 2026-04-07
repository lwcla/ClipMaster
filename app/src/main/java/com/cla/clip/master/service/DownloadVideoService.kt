package com.cla.clip.master.service

import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import com.cla.clip.base.general.entity.DownloadRepository
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.master.utils.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.OutputStream
import javax.inject.Inject

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val progress: Int) : DownloadState()
    data class Success(val filePath: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/** 下载视频的服务 */
@AndroidEntryPoint
class DownloadVideoService : Service() {

    companion object {
        private const val TAG = "DownloadVideoService"
    }

    @Inject
    @ApplicationContext
    lateinit var appContext: Context

    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var downloadRepository: DownloadRepository

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onCreate() {
        super.onCreate()
        logI(TAG) { "onCreate: " }
        startForeground("初始化下载", 0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra("taskId") ?: return START_NOT_STICKY
        val videoUrl = intent.getStringExtra("videoUrl") ?: return START_NOT_STICKY
        val referer = intent.getStringExtra("referer")
        val userAgent = intent.getStringExtra("userAgent")
        val cookie = intent.getStringExtra("cookie")

        startForeground("初始化下载", 0)

        // 启动协程下载
        serviceScope.launch {
            try {
                downloadVideo(taskId, videoUrl, referer, userAgent, cookie)
            } catch (e: Exception) {
                logE(TAG, e) { "下载失败" }
                downloadRepository.markFailed(taskId, e.message ?: "Unknown error")
                startForeground("下载失败", 0)
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun downloadVideo(
        taskId: String,
        videoUrl: String,
        referer: String?,
        userAgent: String?,
        cookie: String?
    ) {
        val request = Request.Builder()
            .url(videoUrl)
            .apply {
                if (referer != null) header("Referer", referer)
                if (userAgent != null) header("User-Agent", userAgent)
                if (cookie != null) header("Cookie", cookie)
            }
            .build()

        val response = okHttpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            logE(TAG) { "下载失败: HTTP ${response.code} - ${response.message}" }
            startForeground("下载失败", 0)
            downloadRepository.markFailed(taskId, "HTTP ${response.code}")
            return
        }

        val totalSize = response.body?.contentLength() ?: 0L

        // 根据 Android 版本选择不同的保存方式
        val (mediaUri, filePath, outputStream) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+：使用 MediaStore API
            saveViaMediaStore(taskId)
        } else {
            // Android 9 及以下：使用传统 File API
            saveViaFile(taskId)
        }

        logD(TAG) { "开始下载，文件总大小: $totalSize bytes, 保存路径: $filePath" }

        runCatching {
            response.body?.byteStream()?.use { input ->
                outputStream.use { output ->
                    val buffer = ByteArray(8192)
                    var downloadedSize = 0L
                    var bytesRead: Int

                    var lastProgress: Int? = null

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedSize += bytesRead

                        val progress = if (totalSize > 0) {
                            ((downloadedSize * 100) / totalSize).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }

                        if (lastProgress != progress) {
                            lastProgress = progress
                            logD(TAG) { "下载进度: $progress% ($downloadedSize / $totalSize bytes)" }
                            downloadRepository.updateProgress(taskId, progress)
                            startForeground("下载中...", progress)
                        }
                    }

                    if (mediaUri != null) {
                        markMediaReady(mediaUri)
                    }else{
                        scanVideoFile(filePath)
                    }

                    logI(TAG) { "下载完成 $totalSize bytes filePath=${filePath} " }
                    serviceScope.launch(Dispatchers.IO) {
                        downloadRepository.markSuccess(taskId, filePath)
                        withContext(Dispatchers.Main) { startForeground("下载完成", 100) }
                    }
                    return
                }
            }
        }.getOrElse {
            logE(TAG, it) { "保存文件到本地失败" }
            // 失败处理
            if (mediaUri != null) {
                // 可选1：删除半成品
                contentResolver.delete(mediaUri, null, null)
                // 可选2：不删，改 IS_PENDING=0（通常不推荐，可能露出损坏文件）
            }
        }
    }

    /** Android 10+ 使用 MediaStore */
    private fun saveViaMediaStore(taskId: String): MediaStoreTarget {
        //// 选项 1：保存到相机相册（用户最常用）
        //put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
        //// 选项 2：保存到 Movies（电影）
        //put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/MyApp")
        //// 选项 3：保存到 Pictures（图片）
        //put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/MyApp")
        //// 选项 4：保存到 Downloads（下载）
        //put(MediaStore.MediaColumns.RELATIVE_PATH, "Downloads/MyApp")

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${taskId}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/clipMaster") // 保存到 Movies/clipMaster/videos 目录下
            put(MediaStore.MediaColumns.IS_PENDING, 1) // 标记为正在下载，下载完成后再改为 0
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw Exception("Failed to insert media")

        val outputStream = contentResolver.openOutputStream(uri)
            ?: throw Exception("Failed to open output stream")

        return MediaStoreTarget(uri, uri.toString(), outputStream)
    }

    private fun markMediaReady(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0) // 标记下载完成，媒体文件现在可见
            }
            logD(TAG) { "下载完成，现在去标记媒体文件可见" }
            contentResolver.update(uri, values, null, null)
        }
    }

    /**
     * android 10 以下使用传统 File API 后，手动触发媒体扫描让新文件出现在图库等应用中
     */
    private fun scanVideoFile(path: String) {
        MediaScannerConnection.scanFile(
            this, // Service 本身就是 Context
            arrayOf(path),
            arrayOf("video/mp4"), // 也可以传 null，让系统自己判断
        ) { scannedPath, scannedUri ->
            if (scannedUri != null) {
                logI(TAG) { "媒体扫描成功: path=$scannedPath, uri=$scannedUri" }
            } else {
                logE(TAG) { "媒体扫描失败: path=$scannedPath" }
            }
        }
    }

    /**
     * Android 9 及以下使用传统 File API
     */
    private fun saveViaFile(taskId: String): MediaStoreTarget {
        val downloadDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "clipMaster"
        )
        downloadDir.mkdirs()

        val saveFile = File(downloadDir, "${taskId}.mp4")
        val outputStream = saveFile.outputStream()

        return MediaStoreTarget(uri = null, saveFile.absolutePath, outputStream)
    }


//    private suspend fun downloadVideo(
//        taskId: String,
//        videoUrl: String,
//        referer: String?,
//        userAgent: String?,
//        cookie: String?
//    ) {
//        val request = Request.Builder()
//            .url(videoUrl)
//            .apply {
//                if (referer != null) header("Referer", referer)
//                if (userAgent != null) header("User-Agent", userAgent)
//                if (cookie != null) header("Cookie", cookie)
//            }
//            .build()
//
//        val response = okHttpClient.newCall(request).execute()
//        if (!response.isSuccessful) {
//            throw Exception("HTTP ${response.code}")
//        }
//
//        val totalSize = response.body?.contentLength() ?: 0L
//        val downloadDir = File(cacheDir, "downloads")
//        downloadDir.mkdirs()
//        val saveFile = File(downloadDir, "${taskId}.mp4")
//
//        response.body?.byteStream()?.use { input ->
//            saveFile.outputStream().use { output ->
//                val buffer = ByteArray(8192)
//                var downloadedSize = 0L
//                var bytesRead: Int
//
//                while (input.read(buffer).also { bytesRead = it } != -1) {
//                    output.write(buffer, 0, bytesRead)
//                    downloadedSize += bytesRead
//
//                    val progress = if (totalSize > 0) {
//                        ((downloadedSize * 100) / totalSize).toInt().coerceIn(0, 100)
//                    } else {
//                        0
//                    }
//
//                    downloadRepository.updateProgress(taskId, progress)
//                    startForeground("下载中...", progress)
//                }
//            }
//        }
//
//        downloadRepository.markSuccess(taskId, saveFile.absolutePath)
//        startForeground("下载完成", 100)
//    }
//
//    suspend fun downloadFile(
//        url: String,
//        onProgress: (progress: Int) -> Unit = {}
//    ): File {
//        val request = Request.Builder()
//            .url(url)
//            .header("Range", "bytes=0-")  // 支持断点续传
//            .build()
//
//        val response = okHttpClient.newCall(request).execute()
//        val totalSize = response.body?.contentLength() ?: 0
//        val file = File(cacheDir, "${System.currentTimeMillis()}")
//
//        response.body?.byteStream()?.use { input ->
//            file.outputStream().use { output ->
//                val buffer = ByteArray(8192)
//                var downloadedSize = 0L
//                var bytesRead: Int
//
//                while (input.read(buffer).also { bytesRead = it } != -1) {
//                    output.write(buffer, 0, bytesRead)
//                    downloadedSize += bytesRead
//
//                    if (totalSize > 0) {
//                        val progress = ((downloadedSize * 100) / totalSize).toInt()
//                        onProgress(progress)
//                    }
//                }
//            }
//        }
//
//        return file
//    }

    private fun startForeground(title: String, progress: Int) {
        notificationHelper.downloadForeground(this, title, progress)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    data class MediaStoreTarget(
        val uri: Uri?,
        val path: String,
        val outputStream: OutputStream
    )
}