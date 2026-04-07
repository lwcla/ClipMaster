package com.cla.clip.master.service

import android.Manifest
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.cla.clip.base.general.entity.DownloadRepository
import com.cla.clip.base.general.utils.logI
import com.cla.clip.master.utils.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
            throw Exception("HTTP ${response.code}")
        }

        val totalSize = response.body?.contentLength() ?: 0L

        // 根据 Android 版本选择不同的保存方式
        val (outputStream, filePath) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+：使用 MediaStore API
            saveViaMediaStore(taskId)
        } else {
            // Android 9 及以下：使用传统 File API
            saveViaFile(taskId)
        }

        response.body?.byteStream()?.use { input ->
            outputStream.use { output ->
                val buffer = ByteArray(8192)
                var downloadedSize = 0L
                var bytesRead: Int

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedSize += bytesRead

                    val progress = if (totalSize > 0) {
                        ((downloadedSize * 100) / totalSize).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }

                    downloadRepository.updateProgress(taskId, progress)
                    startForeground("下载中...", progress)
                }
            }
        }

        downloadRepository.markSuccess(taskId, filePath)
        startForeground("下载完成", 100)
    }

    //
    /**
     * Android 10+ 使用 MediaStore
     */
    private fun saveViaMediaStore(taskId: String): Pair<OutputStream, String> {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${taskId}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")  // 保存到相机相册
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw Exception("Failed to insert media")

        val outputStream = contentResolver.openOutputStream(uri)
            ?: throw Exception("Failed to open output stream")

        return Pair(outputStream, uri.toString())
    }

    /**
     * Android 9 及以下使用传统 File API
     */
    private fun saveViaFile(taskId: String): Pair<OutputStream, String> {
        val downloadDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "Camera"
        )
        downloadDir.mkdirs()

        val saveFile = File(downloadDir, "${taskId}.mp4")
        val outputStream = saveFile.outputStream()

        return Pair(outputStream, saveFile.absolutePath)
    }

    fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Android 10 及以下，申请写权限
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
//                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+，申请读权限
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
//                requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        // Android 11-12 不需要运行时权限（只需 Manifest 权限）
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
}