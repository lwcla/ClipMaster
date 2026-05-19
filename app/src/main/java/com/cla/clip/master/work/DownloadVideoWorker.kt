package com.cla.clip.master.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cla.clip.base.general.R
import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.base.general.dao.DownloadTaskData
import com.cla.clip.base.general.di.M3u8Client
import com.cla.clip.base.general.repository.DownloadRepository
import com.cla.clip.base.general.utils.MediaStoreTarget
import com.cla.clip.base.general.utils.SaveToFile
import com.cla.clip.base.general.utils.clear
import com.cla.clip.base.general.utils.createPath
import com.cla.clip.base.general.utils.failure
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.showName
import com.cla.clip.base.general.utils.success
import com.cla.clip.master.utils.NotificationHelper
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File

@HiltWorker
/**
 * 视频下载 Worker。
 *
 * 根据 `download_tasks` 中保存的视频 URL 和请求头下载直链或 M3U8 视频，写入 MediaStore/公共目录，
 * 并通过前台通知和数据库状态向 UI 汇报进度。
 */
class DownloadVideoWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val okHttpClient: Lazy<OkHttpClient>,
    @param:M3u8Client private val m3u8Client: Lazy<OkHttpClient>,
    private val downloadRepo: DownloadRepository,
    private val notificationHelper: NotificationHelper,
) : CoroutineWorker(appContext, params) {

    companion object {
        /** 视频下载 Worker 日志标签。 */
        private const val TAG = "DownloadVideoWorker"

        /** WorkManager 标签，用于取消或筛选全部视频下载任务。 */
        const val DOWNLOAD_VIDEO_TASK_TAG = "download_video"

        /** Worker 输入数据中的任务 id key，对应 download_tasks.id。 */
        const val KEY_TASK_ID = "key_task_id"

        /** M3U8 分片临时目录名，最终路径为 cacheDir/m3u8/{taskId}。 */
        const val M3U8_DIR_NAME = "m3u8"

        /** 抖音带水印播放接口路径，下载前会优先尝试替换为无水印路径。 */
        private const val DOU_YIN_PLAYVM = "/playwm/"

        /** 抖音无水印播放接口路径，连接失败时会回退到原始 playwm。 */
        private const val DOU_YIN_PLAY = "/play/"

        // todo 不知道能不能设置为如果是同一个taskId，则keep，如果是不同的taskId，则排队
        /** 入队指定视频下载任务；同一 taskId 的未完成任务会被 KEEP，避免重复下载同一文件。 */
        fun enqueue(context: Context, taskId: Long) {
            logD(TAG) { "enqueue: 启动下载 taskId=$taskId" }
            val data = workDataOf(KEY_TASK_ID to taskId)

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

        /** 取消所有视频下载 Worker，应用启动时用来避免自动恢复上次遗留任务。 */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(DOWNLOAD_VIDEO_TASK_TAG)
        }

        /** 取消指定视频下载任务；下载记录页删除进行中记录时先调用它，避免 Worker 继续写入已删除记录。 */
        fun cancel(context: Context, taskId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork("${DOWNLOAD_VIDEO_TASK_TAG}:$taskId")
        }
    }

    /**
     * Worker 执行入口。
     *
     * 读取任务、创建输出文件、清理上次失败的 pending 输出和 M3U8 临时目录；下载失败时会清理本次半成品并通知用户。
     */
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

        val lastTask = AppSetting.videoDownloadTaskId
        downloadRepo.getTask(lastTask)?.let { task ->
            if (task.status != DownloadTaskData.STATUS_SUCCESS) {
                // 上次的任务还未成功，说明可能是异常中断了，需要清除上次未完成的 pending 输出，避免这个遗留文件一直占用空间（尤其是m3u8的临时文件可能非常大）
                logD(TAG) { "doWork: 上次下载任务 ${task.fileName} 可能异常中断了，正在清理遗留的 pending 输出  errorMsg=${task.errorMsg}" }
                val saveToFile = SaveToFile.Video(task.fileName)
                saveToFile.failure(applicationContext, task.pendingOutputUri?.toUri(), task.savePath)
            }
        }

        // m3u8 的下载会在 cacheDir 下创建一个临时目录来保存 ts 分片，命名为 m3u8/{taskId}，下载完成后会删除这个目录。这里清理掉 cacheDir 下遗留的 m3u8 目录，避免占用空间
        val dir = File(applicationContext.cacheDir, M3U8_DIR_NAME)
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.forEach {
                if (it.isDirectory && it.name != taskId.toString()) {
                    // 可能是上次下载遗留的临时目录，清理掉
                    logD(TAG) { "doWork : 清理之前遗留的临时文件夹 taskId=$taskId dirName=${it.absolutePath}" }
                    it.clear()
                }
            }
        }

        AppSetting.videoDownloadTaskId = taskId
        val saveVideo = SaveToFile.Video(fileName)
        val mediaTarget = saveVideo.createPath(applicationContext)
        downloadRepo.markPath(taskId, mediaTarget.uri?.toString(), mediaTarget.path)

        return runCatching {
            downloadVideo(taskId, videoUrl, fileName, referer, userAgent, cookie, saveVideo, mediaTarget)
            Result.success()
        }.getOrElse { tr ->
            logE(TAG, tr) { "doWork: 下载失败" }
            val errorMsg = tr.message ?: applicationContext.getString(R.string.base_general_download_failed)
            downloadRepo.markFailed(applicationContext, taskId, errorMsg)
            BackupAutoScheduler.markDirtyAndSchedule(applicationContext)
            saveVideo.failure(applicationContext, mediaTarget)
            notificationHelper.notifyDownloadResult(
                taskId,
                title = applicationContext.getString(R.string.base_general_download_failed),
                fileName = fileName.showName,
                content = errorMsg,
            )
            Result.failure()
        }
    }

    /**
     * 根据视频 URL 类型执行下载。
     *
     * 普通媒体流直接写入输出；M3U8 交给 Download.M3u8 下载分片并合并；抖音 playwm 地址优先尝试无水印 play 地址。
     */
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
        /**
         * 根据入口响应选择直链或 M3U8 下载器并启动。
         *
         * 这里统一桥接下载进度和合并进度回调，避免同一进度重复写数据库和刷新前台通知。
         */
        suspend fun start(response: Response) {
            val download = if (isM3u8(response)) {
                Download.M3u8(taskId, response, mediaTarget) { url -> executeRequest(m3u8Client.get(), url, referer, userAgent, cookie) }
            } else {
                Download.Video(response, fileName, mediaTarget)
            }

            var lastProgress: Int? = null
            download.apply {
                start(
                    merge = { progress ->
                        if (lastProgress == null || lastProgress != progress) {
                            lastProgress = progress
                            logD(TAG) { "start : 合并进度${progress}% $fileName" }
                            updateProgress(taskId, fileName, progress, isMerge = true)
                        }
                    },
                    download = { progress ->
                        if (lastProgress == null || lastProgress != progress) {
                            lastProgress = progress
                            logD(TAG) { "start : 下载进度 ${progress}% $fileName" }
                            updateProgress(taskId, fileName, progress, isMerge = false)
                        }
                    }
                )
            }
        }

        val isDouYinVm = videoUrl.contains(DOU_YIN_PLAYVM)
        if (isDouYinVm) {
            runCatching {
                logD(TAG) { "downloadVideo: 抖音尝试下载无水印的地址 fileName=$fileName" }
                val newUrl = videoUrl.replace(DOU_YIN_PLAYVM, DOU_YIN_PLAY)
                val response = executeRequest(okHttpClient.get(), newUrl, referer, userAgent, cookie)
                start(response)
            }.getOrElse {
                logE(TAG, it) { "downloadVideo: 抖音无水印地址连接失败，换回原地址 fileName=$fileName" }
                val response = executeRequest(okHttpClient.get(), videoUrl, referer, userAgent, cookie)
                start(response)
            }
        } else {
            val response = executeRequest(okHttpClient.get(), videoUrl, referer, userAgent, cookie)
            start(response)
        }
        downloadRepo.markSuccess(taskId)
        BackupAutoScheduler.markDirtyAndSchedule(applicationContext)
        saveVideo.success(applicationContext, mediaTarget)
        val savePath = mediaTarget.path
        logI(TAG) { "下载完成 taskId=$taskId path=${savePath}" }

        notificationHelper.notifyDownloadResult(
            taskId,
            title = applicationContext.getString(R.string.base_general_download_completed),
            fileName = fileName.showName,
            content = savePath,
        )
    }

    /** 更新数据库进度、WorkManager progress 和前台通知；isMerge 为 true 时展示“合并中”。 */
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

    /** 判断响应是否为 M3U8：先看 Content-Type，再嗅探响应头部文本，兼容服务端 MIME 不规范的情况。 */
    private fun isM3u8(response: Response): Boolean {
        val contentType = response.header("Content-Type").orEmpty().lowercase()
        val byType = contentType.contains("mpegurl") || contentType.contains("x-mpegurl")
        if (byType) {
            return true
        }

        val headText = response.peekBody(128 * 1024).string()
        return looksLikeM3u8(headText)
    }

    /** 判断文本是否像 HLS playlist，要求包含 EXT-M3U 以及分片或子码流标记。 */
    private fun looksLikeM3u8(text: String): Boolean {
        val t = text.trim()
        if (!t.contains("#EXTM3U", ignoreCase = true)) return false
        return t.contains("#EXTINF", true) || t.contains("#EXT-X-STREAM-INF", true)
    }

    /** 执行视频或 M3U8 子资源请求；非成功响应会关闭 body 并抛错，让 Worker 进入失败清理路径。 */
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

    /** 构建下载请求，按需携带 WebView 捕获到的反盗链上下文。 */
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

    /** 构建视频下载前台通知，Android 10+ 标记为 DATA_SYNC 前台服务类型。 */
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
