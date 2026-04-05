package com.cla.clip.master.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.cla.clip.base.general.utils.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import kotlin.toString

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val progress: Int) : DownloadState()
    data class Success(val filePath: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/** 下载视频的服务 */
@AndroidEntryPoint
class DownloadVideoService : Service() {

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    @Inject
    @ApplicationContext
    lateinit var appContext: Context

    private val client = OkHttpClient()
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): DownloadVideoService = this@DownloadVideoService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * 从网页中提取视频 URL 并下载
     * @param pageUrl 网页地址
     * @param outputPath 本地保存路径
     * @param onProgress 进度回调 (0-100)
     * @param onSuccess 成功回调
     * @param onError 失败回调
     */
    fun downloadVideoFromPage(
        pageUrl: String,
        outputPath: String,
        onProgress: (Int) -> Unit = {},
        onSuccess: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        scope.launch {
            try {
                // 1. 解析网页，提取视频 URL（这里假设你有提取逻辑）
                val videoUrl = extractVideoUrlFromPage(pageUrl)
                if (videoUrl.isNullOrBlank()) {
                    onError("未找到视频 URL")
                    return@launch
                }

                // 2. 下载视频
                downloadFile(videoUrl, outputPath, onProgress, onSuccess, onError)
            } catch (e: Exception) {
                onError("提取失败: ${e.message}")
                Log.e("VideoDownload", "Error", e)
            }
        }
    }

    /**
     * 直接下载视频（已知视频 URL）
     */
    fun downloadVideo(
        videoUrl: String,
        outputPath: String,
        onProgress: (Int) -> Unit = {},
        onSuccess: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        scope.launch {
            try {
                downloadFile(videoUrl, outputPath, onProgress, onSuccess, onError)
            } catch (e: Exception) {
                onError("下载失败: ${e.message}")
                Log.e("VideoDownload", "Error", e)
            }
        }
    }

    private suspend fun downloadFile(
        url: String,
        outputPath: String,
        onProgress: (Int) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                onError("HTTP ${response.code}")
                return
            }

            val body = response.body ?: run {
                onError("响应体为空")
                return
            }

            val totalSize = body.contentLength()
            val file = File(outputPath)
            file.parentFile?.mkdirs()

            var downloadedSize = 0L
            file.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedSize += read
                        val progress = (downloadedSize * 100 / totalSize).toInt()
                        onProgress(progress)
                    }
                }
            }

            onSuccess(outputPath)
        } catch (e: Exception) {
            onError("下载异常: ${e.message}")
            throw e
        }
    }

    /**
     * 从网页 HTML 中提取视频 URL
     * 这里需要根据你具体的网页结构来编写
     */
    private suspend fun extractVideoUrlFromPage(pageUrl: String): String? {
        return try {
            val request = Request.Builder().url(pageUrl).build()
            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: return null

            // TODO: 根据网页结构解析视频 URL
            // 示例：可以用 Jsoup 解析
            // val doc = Jsoup.parse(html)
            // val videoUrl = doc.select("video src").attr("href")
            // return videoUrl

            null
        } catch (e: Exception) {
            Log.e("VideoDownload", "Extract failed", e)
            null
        }
    }


}