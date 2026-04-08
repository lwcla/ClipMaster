package com.cla.clip.master.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
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
import com.cla.clip.master.entity.VideoCandidate
import com.cla.clip.master.utils.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/** 下载视频的服务 */
@AndroidEntryPoint
class DownloadVideoService : Service() {

    companion object {
        private const val TAG = "DownloadVideoService"

        private const val TASK_ID_KEY = "task_id_key"
        private const val CANDIDATE_KEY = "candidate_key"

        fun start(context: Context, taskId: String, candidate: VideoCandidate) {
            logI(TAG) { "start: " }
            val serviceIntent = Intent(context, DownloadVideoService::class.java)
            serviceIntent.putExtra(TASK_ID_KEY, taskId)
            serviceIntent.putExtra(CANDIDATE_KEY, candidate)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
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
        val taskId = intent?.getStringExtra(TASK_ID_KEY) ?: return START_NOT_STICKY
        val candidate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(CANDIDATE_KEY, VideoCandidate::class.java)
        } else {
            intent.getParcelableExtra<VideoCandidate>(CANDIDATE_KEY)
        } ?: return START_NOT_STICKY

        val (videoUrl, referer, userAgent, cookie) = candidate

        startForeground(appContext.getString(R.string.base_general_initialize_download), 0)

        // 启动协程下载
//        serviceScope.launch(Dispatchers.IO) {
//            try {
//                downloadVideo(taskId, videoUrl, referer, userAgent, cookie)
//            } catch (e: Exception) {
//                logE(TAG, e) { "下载失败" }
//                downloadRepository.markFailed(taskId, e.message ?: "Unknown error")
//                startForeground(appContext.getString(R.string.base_general_download_failed), 0)
//            }
//        }

        return START_STICKY
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
                    downloadRepository.markSuccess(taskId, filePath)
                    startForeground(appContext.getString(R.string.base_general_download_completed), 100)
                    return
                }
            }
        }.getOrElse {
            logE(TAG, it) { "保存文件到本地失败" }
            // 失败处理
            startForeground(appContext.getString(R.string.base_general_download_failed), 0)
            saveVideo.failure(appContext, mediaTarget)
        }
    }

    // 断点续传
//    private suspend fun downloadVideo(
//        taskId: String,
//        videoUrl: String,
//        referer: String?,
//        userAgent: String?,
//        cookie: String?
//    ) {
//        // 1) 读取任务断点信息（你需要在 DownloadRepository 里实现）
//        // 期望字段：downloadedBytes, totalBytes, etag, tempPath, finalPath, status
//        val state = downloadRepository.getOrCreateResumeState(taskId, videoUrl)
//
//        // 2) 准备临时文件（建议 app 私有目录）
//        val tempFile = java.io.File(state.tempPath)
//        tempFile.parentFile?.mkdirs()
//
//        // 如果 DB 记录和实际文件长度不一致，做一次修正，避免 seek 错位
//        var localBytes = if (tempFile.exists()) tempFile.length() else 0L
//        if (state.downloadedBytes != localBytes) {
//            downloadRepository.updateDownloadedBytes(taskId, localBytes)
//        }
//
//        // 3) 组装请求（续传时带 Range）
//        val requestBuilder = okhttp3.Request.Builder()
//            .url(videoUrl)
//            .apply {
//                referer?.let { header("Referer", it) }
//                userAgent?.let { header("User-Agent", it) }
//                cookie?.let { header("Cookie", it) }
//
//                if (localBytes > 0L) {
//                    header("Range", "bytes=$localBytes-")
//                    state.etag?.let { header("If-Range", it) } // 可选，但推荐
//                }
//            }
//
//        val response = okHttpClient.newCall(requestBuilder.build()).execute()
//        if (!response.isSuccessful) {
//            downloadRepository.markFailed(taskId, "HTTP ${response.code}")
//            startForeground(appContext.getString(R.string.base_general_download_failed), 0)
//            return
//        }
//
//        // 4) 判断服务端是否支持续传
//        val isResumeRequest = localBytes > 0L
//        val code = response.code
//        val acceptRanges = response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
//
//        // 服务端不接受续传：Range 请求却返回 200，说明要全量重下
//        if (isResumeRequest && code == 200) {
//            localBytes = 0L
//            if (tempFile.exists()) tempFile.delete()
//            tempFile.parentFile?.mkdirs()
//            tempFile.createNewFile()
//            downloadRepository.resetResumeState(taskId) // downloadedBytes=0,totalBytes=0,etag=null...
//        }
//
//        // 续传成功时一般是 206；首次通常是 200
//        if (isResumeRequest && code !in listOf(200, 206)) {
//            downloadRepository.markFailed(taskId, "Resume not supported: HTTP $code")
//            startForeground(appContext.getString(R.string.base_general_download_failed), 0)
//            return
//        }
//
//        // 5) 计算总大小（非常关键：206 时 contentLength 是“剩余长度”）
//        val body = response.body ?: run {
//            downloadRepository.markFailed(taskId, "Empty body")
//            startForeground(appContext.getString(R.string.base_general_download_failed), 0)
//            return
//        }
//
//        val incomingLength = body.contentLength().coerceAtLeast(0L)
//        val totalBytes = when {
//            code == 206 -> localBytes + incomingLength
//            else -> incomingLength
//        }
//
//        val etag = response.header("ETag")
//        if (!etag.isNullOrBlank()) {
//            downloadRepository.updateEtag(taskId, etag)
//        }
//        downloadRepository.updateTotalBytes(taskId, totalBytes)
//
//        // 6) 断点写入（RandomAccessFile + seek）
//        var downloadedBytes = localBytes
//        var lastProgress = -1
//        var lastPersistAt = System.currentTimeMillis()
//
//        runCatching {
//            java.io.RandomAccessFile(tempFile, "rw").use { raf ->
//                raf.seek(downloadedBytes)
//
//                body.byteStream().use { input ->
//                    val buffer = ByteArray(8 * 1024)
//                    var read: Int
//                    while (input.read(buffer).also { read = it } != -1) {
//                        raf.write(buffer, 0, read)
//                        downloadedBytes += read
//
//                        val progress = if (totalBytes > 0L) {
//                            ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
//                        } else 0
//
//                        // UI/通知节流：进度变化时更新
//                        if (progress != lastProgress) {
//                            lastProgress = progress
//                            downloadRepository.updateProgress(taskId, progress)
//                            startForeground(appContext.getString(R.string.base_general_download_now), progress)
//                        }
//
//                        // DB 节流：每 500ms 持久化一次断点，降低 IO 压力
//                        val now = System.currentTimeMillis()
//                        if (now - lastPersistAt >= 500) {
//                            lastPersistAt = now
//                            downloadRepository.updateDownloadedBytes(taskId, downloadedBytes)
//                        }
//                    }
//                }
//            }
//
//            // 最后一次持久化
//            downloadRepository.updateDownloadedBytes(taskId, downloadedBytes)
//
//            // 7) 完成校验
//            if (totalBytes > 0L && downloadedBytes < totalBytes) {
//                error("incomplete: $downloadedBytes/$totalBytes")
//            }
//
//            // 8) 写入最终位置（你现有 SaveToFile 流程）
//            val saveVideo = SaveToFile.Video(taskId)
//            val mediaTarget = saveVideo.createPath(appContext)
//            val (_, finalPath, outputStream) = mediaTarget
//
//            tempFile.inputStream().use { input ->
//                outputStream.use { output ->
//                    input.copyTo(output)
//                }
//            }
//
//            saveVideo.success(appContext, mediaTarget)
//            downloadRepository.markSuccess(taskId, finalPath)
//            startForeground(appContext.getString(R.string.base_general_download_completed), 100)
//
//            // 成功后可删除 .part
//            tempFile.delete()
//        }.onFailure { e ->
//            logE(TAG, e) { "下载/落盘失败" }
//            downloadRepository.markFailed(taskId, e.message ?: "Unknown error")
//            startForeground(appContext.getString(R.string.base_general_download_failed), 0)
//            // 注意：失败时保留 tempFile，供下次续传
//        }
//    }

    private fun startForeground(title: String, progress: Int) {
        notificationHelper.downloadForeground(this, title, progress)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}