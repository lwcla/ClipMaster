package com.cla.clip.master.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cla.clip.base.general.R
import com.cla.clip.base.general.di.M3u8Client
import com.cla.clip.base.general.entity.DownloadRepository
import com.cla.clip.base.general.utils.MediaStoreTarget
import com.cla.clip.base.general.utils.SaveToFile
import com.cla.clip.base.general.utils.createPath
import com.cla.clip.base.general.utils.failure
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.showName
import com.cla.clip.base.general.utils.success
import com.cla.clip.master.utils.NotificationHelper
import com.cla.clip.master.work.DownloadVideoWorker.Companion.DOWNLOAD_VIDEO_TASK_TAG
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@HiltWorker
class DownloadVideoWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val okHttpClient: Lazy<OkHttpClient>,
    @param:M3u8Client private val m3u8Client: Lazy<OkHttpClient>,
    private val downloadRepo: DownloadRepository,
    private val notificationHelper: NotificationHelper,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "DownloadVideoWorker"

        const val DOWNLOAD_VIDEO_TASK_TAG = "download_video"

        const val KEY_TASK_ID = "key_task_id"

        private const val DOU_YIN_PLAYVM = "/playwm/"
        private const val DOU_YIN_PLAY = "/play/"
    }

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK_ID, -1)
        val task = downloadRepo.getTask(taskId) ?: return Result.failure()
        val videoUrl = task.videoUrl
        val referer = task.referer
        val userAgent = task.userAgent
        val cookie = task.cookie
        val fileName = task.fileName

        logD(TAG) { "doWork: 开始下载任务 taskId=$taskId task=$task" }

        // 首帧前台通知，避免后台限制
        setForeground(buildForegroundInfo(applicationContext.getString(R.string.base_general_initialize_download), fileName.showName, 0))

        val saveVideo = SaveToFile.Video(fileName)
        val mediaTarget = saveVideo.createPath(applicationContext)
        downloadRepo.markPendingOutputUri(taskId, mediaTarget.uri?.toString())

        return runCatching {
            downloadVideo(taskId, videoUrl, fileName, referer, userAgent, cookie, saveVideo, mediaTarget)
            Result.success()
        }.getOrElse { tr ->
            logE(TAG, tr) { "doWork: 下载失败" }
            downloadRepo.markFailed(taskId, tr.message ?: "Unknown error")
            notificationHelper.notifyDownloadResult(
                taskId,
                title = applicationContext.getString(R.string.base_general_download_failed),
                fileName = fileName.showName,
                content = tr.message ?: "Unknown error",
            )
            saveVideo.failure(applicationContext, mediaTarget)
            Result.failure()
        }
    }

    private suspend fun downloadVideo(
        taskId: Long,
        videoUrl: String,
        fileName: String,
        referer: String?,
        userAgent: String?,
        cookie: String?,
        saveVideo: SaveToFile,
        mediaTarget: MediaStoreTarget
    ) {
        suspend fun start(response: Response) {
            val download = if (isM3u8(response)) {
                Download.M3u8(taskId, response, mediaTarget) { url -> executeRequest(m3u8Client.get(), url, referer, userAgent, cookie) }
            } else {
                Download.Video(response, fileName, mediaTarget)
            }

            download.apply {
                start(
                    merge = { progress -> updateProgress(taskId, fileName, progress, isMerge = true) },
                    download = { progress -> updateProgress(taskId, fileName, progress, isMerge = false) }
                )
            }
        }

        val isDouYinVm = videoUrl.contains(DOU_YIN_PLAYVM)
        if (isDouYinVm) {
            runCatching {
                logD(TAG) { "downloadVideo: 抖音尝试下载无水印的地址" }
                val newUrl = videoUrl.replace(DOU_YIN_PLAYVM, DOU_YIN_PLAY)
                val response = executeRequest(okHttpClient.get(), newUrl, referer, userAgent, cookie)
                start(response)
            }.getOrElse {
                logE(TAG, it) { "downloadVideo: 抖音无水印地址连接失败，换回原地址" }
                val response = executeRequest(okHttpClient.get(), videoUrl, referer, userAgent, cookie)
                start(response)
            }
        } else {
            val response = executeRequest(okHttpClient.get(), videoUrl, referer, userAgent, cookie)
            start(response)
        }

        saveVideo.success(applicationContext, mediaTarget)
        val savePath = mediaTarget.path
        downloadRepo.markSuccess(taskId, savePath)
        logI(TAG) { "下载完成 taskId=$taskId path=${savePath}" }

        notificationHelper.notifyDownloadResult(
            taskId,
            title = applicationContext.getString(R.string.base_general_download_completed),
            fileName = fileName.showName,
            content = savePath,
        )
    }

    private suspend fun updateProgress(taskId: Long, fileName: String, progress: Int, isMerge: Boolean) {
        if (isMerge) {
            downloadRepo.updateMergeProgress(taskId, progress)
        } else {
            downloadRepo.updateProgress(taskId, progress)
        }

        setProgress(workDataOf("progress" to progress))
        val title = if (isMerge) {
            applicationContext.getString(R.string.base_general_merge_now)
        } else {
            applicationContext.getString(R.string.base_general_download_now)
        }
        setForeground(buildForegroundInfo(title, fileName.showName, progress))
    }

    private fun isM3u8(response: Response): Boolean {
        val contentType = response.header("Content-Type").orEmpty().lowercase()
        val byType = contentType.contains("mpegurl") || contentType.contains("x-mpegurl")
        if (byType) {
            return true
        }

        val headText = response.peekBody(128 * 1024).string()
        return looksLikeM3u8(headText)
    }

    private fun looksLikeM3u8(text: String): Boolean {
        val t = text.trim()
        if (!t.contains("#EXTM3U", ignoreCase = true)) return false
        return t.contains("#EXTINF", true) || t.contains("#EXT-X-STREAM-INF", true)
    }

    private fun executeRequest(
        client: OkHttpClient,
        url: String,
        referer: String?,
        userAgent: String?,
        cookie: String?
    ): Response {
        val response = client.newCall(requestBuilder(url, referer, userAgent, cookie)).execute()
        if (!response.isSuccessful) {
            response.close()
            error("HTTP ${response.code} ${response.message}, url=$url")
        }
        return response
    }

    private fun requestBuilder(
        url: String,
        referer: String?,
        userAgent: String?,
        cookie: String?
    ) = Request.Builder().url(url).apply {
        if (!referer.isNullOrBlank()) header("Referer", referer)
        if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent)
        if (!cookie.isNullOrBlank()) header("Cookie", cookie)
    }.build()

    private fun buildForegroundInfo(title: String, fileName: String, progress: Int): ForegroundInfo {
        val notification = notificationHelper.buildDownloadNotification(title, fileName, progress)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NotificationHelper.VIDEO_DOWNLOAD_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NotificationHelper.VIDEO_DOWNLOAD_NOTIFICATION_ID, notification)
        }
    }
}

object DownloadVideoWorkStarter {

    // todo 不知道能不能设置为如果是同一个taskId，则keep，如果是不同的taskId，则排队
    fun enqueue(context: Context, taskId: Long) {
        val data = workDataOf(DownloadVideoWorker.KEY_TASK_ID to taskId)

        val request = OneTimeWorkRequestBuilder<DownloadVideoWorker>()
            .setInputData(data)
            .addTag(DOWNLOAD_VIDEO_TASK_TAG)
            .addTag("${DOWNLOAD_VIDEO_TASK_TAG}:$taskId")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "${DOWNLOAD_VIDEO_TASK_TAG}:$taskId",
            ExistingWorkPolicy.KEEP, // 如果存在具有相同唯一名称的挂起（未完成）工作，则不执行任何操作。否则，插入新指定的作品
            request
        )
    }
}
