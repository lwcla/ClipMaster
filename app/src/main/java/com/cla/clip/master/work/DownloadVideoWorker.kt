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
import com.cla.clip.master.work.DownloadVideoWorker.Companion.TASK_TAG
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@HiltWorker
class DownloadVideoWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val okHttpClient: OkHttpClient,
    private val downloadRepo: DownloadRepository,
    private val notificationHelper: NotificationHelper,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "DownloadVideoWorker"

        const val TASK_TAG = "download"

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
        setForeground(buildForegroundInfo(applicationContext.getString(R.string.base_general_initialize_download, fileName.showName), 0))

        val saveVideo = SaveToFile.Video(fileName)
        val mediaTarget = saveVideo.createPath(applicationContext)

        return runCatching {
            downloadVideo(taskId, videoUrl, fileName, referer, userAgent, cookie, saveVideo, mediaTarget)
            Result.success()
        }.getOrElse { tr ->
            logE(TAG, tr) { "doWork: 下载失败" }
            downloadRepo.markFailed(taskId, tr.message ?: "Unknown error")
            notificationHelper.notifyDownloadResult(
                taskId,
                title = applicationContext.getString(R.string.base_general_download_failed, fileName.showName),
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

        fun call(url: String): Response {
            val request = Request.Builder()
                .url(url)
                .apply {
                    if (!referer.isNullOrBlank()) header("Referer", referer)
                    if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent)
                    if (!cookie.isNullOrBlank()) header("Cookie", cookie)
                }
                .build()

            return okHttpClient.newCall(request).execute()
        }

        val response = if (videoUrl.contains(DOU_YIN_PLAYVM)) {
            runCatching {
                // 抖音先尝试无水印的地址，如果失败换回原地址
                val newUrl = videoUrl.replace(DOU_YIN_PLAYVM, DOU_YIN_PLAY)
                logD(TAG) { "downloadVideo: 抖音尝试下载无水印的地址" }
                call(newUrl)
            }.getOrElse {
                logD(TAG) { "downloadVideo: 抖音无水印地址连接失败，换回原地址" }
                call(videoUrl)
            }
        } else {
            call(videoUrl)
        }

        response.use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} ${response.message}")
            }

            val body = response.body ?: throw IllegalStateException("Empty response body")
            val totalSize = body.contentLength()

            val (_, filePath, outputStream) = mediaTarget
            logD(TAG) { "$fileName 开始下载 total=$totalSize path=$filePath" }

            body.byteStream().use { input ->
                outputStream.use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var read: Int
                    var lastProgress = -1

                    while (input.read(buffer).also { read = it } != -1) {
                        if (isStopped) throw IllegalStateException("Worker stopped")

                        output.write(buffer, 0, read)
                        downloaded += read

                        val progress = if (totalSize > 0L) {
                            ((downloaded * 100) / totalSize).toInt().coerceIn(0, 100)
                        } else 0

                        if (progress != lastProgress) {
                            lastProgress = progress
                            downloadRepo.updateProgress(taskId, progress)
                            setProgress(workDataOf("progress" to progress))
                            setForeground(buildForegroundInfo(applicationContext.getString(R.string.base_general_download_now, fileName.showName), progress))
                        }
                    }
                }
            }

            saveVideo.success(applicationContext, mediaTarget)
            downloadRepo.markSuccess(taskId, filePath)
            logI(TAG) { "下载完成 taskId=$taskId path=$filePath" }

            notificationHelper.notifyDownloadResult(
                taskId,
                title = applicationContext.getString(R.string.base_general_download_completed, fileName.showName),
                content = filePath,
            )
        }
    }

    private fun buildForegroundInfo(title: String, progress: Int): ForegroundInfo {
        val notification = notificationHelper.buildDownloadNotification(title, progress)

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

    fun enqueue(context: Context, taskId: Long) {
        val data = workDataOf(DownloadVideoWorker.KEY_TASK_ID to taskId)

        val request = OneTimeWorkRequestBuilder<DownloadVideoWorker>()
            .setInputData(data)
            .addTag(TASK_TAG)
            .addTag("${TASK_TAG}:$taskId")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "${TASK_TAG}:$taskId",
            ExistingWorkPolicy.KEEP, // 如果存在具有相同唯一名称的挂起（未完成）工作，则不执行任何操作。否则，插入新指定的作品
            request
        )
    }
}