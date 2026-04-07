package com.cla.clip.master.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.cla.clip.base.general.R
import com.cla.clip.base.general.entity.DownloadRepository
import com.cla.clip.base.general.utils.SaveToFile
import com.cla.clip.base.general.utils.createPath
import com.cla.clip.base.general.utils.failure
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.success
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
import javax.inject.Inject

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
        startForeground(appContext.getString(R.string.base_general_initialize_download), 0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra("taskId") ?: return START_NOT_STICKY
        val videoUrl = intent.getStringExtra("videoUrl") ?: return START_NOT_STICKY
        val referer = intent.getStringExtra("referer")
        val userAgent = intent.getStringExtra("userAgent")
        val cookie = intent.getStringExtra("cookie")

        startForeground(appContext.getString(R.string.base_general_initialize_download), 0)

        // 启动协程下载
        serviceScope.launch {
            try {
                downloadVideo(taskId, videoUrl, referer, userAgent, cookie)
            } catch (e: Exception) {
                logE(TAG, e) { "下载失败" }
                downloadRepository.markFailed(taskId, e.message ?: "Unknown error")
                startForeground(appContext.getString(R.string.base_general_download_failed), 0)
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
            startForeground(appContext.getString(R.string.base_general_download_failed), 0)
            downloadRepository.markFailed(taskId, "HTTP ${response.code}")
            return
        }

        val totalSize = response.body.contentLength()
        val saveVideo = SaveToFile.Video(taskId)
        val mediaTarget = saveVideo.createPath(appContext)
        val (_, filePath, outputStream) = mediaTarget

        logD(TAG) { "开始下载，文件总大小: $totalSize bytes, 保存路径: $filePath" }

        runCatching {
            response.body.byteStream().use { input ->
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
                            startForeground(appContext.getString(R.string.base_general_download_now), progress)
                        }
                    }

                    saveVideo.success(appContext, mediaTarget)
                    logI(TAG) { "下载完成 $totalSize bytes filePath=${filePath} " }
                    serviceScope.launch(Dispatchers.IO) {
                        downloadRepository.markSuccess(taskId, filePath)
                        withContext(Dispatchers.Main) { startForeground(appContext.getString(R.string.base_general_download_completed), 100) }
                    }
                    return
                }
            }
        }.getOrElse {
            logE(TAG, it) { "保存文件到本地失败" }
            // 失败处理
            saveVideo.failure(appContext, mediaTarget)
        }
    }

    private fun startForeground(title: String, progress: Int) {
        notificationHelper.downloadForeground(this, title, progress)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}