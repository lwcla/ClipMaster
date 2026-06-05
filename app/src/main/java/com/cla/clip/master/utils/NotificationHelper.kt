package com.cla.clip.master.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.cla.clip.base.general.R
import com.cla.clip.master.MainActivity
import com.cla.clip.master.entity.ExtraData
import com.cla.clip.master.entity.ImageFolderOpenData
import com.cla.clip.shizuku.ShizukuStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用通知构建与渠道管理工具。
 *
 * 集中管理剪贴更新、Shizuku 状态和下载相关通知，保证渠道 ID、通知 ID 和 PendingIntent extra 约定一致。
 */
@Singleton
class NotificationHelper @Inject constructor(
    /** 应用级 Context，用于创建通知、读取资源和构造跳转 Intent。 */
    @param:ApplicationContext private val appContext: Context
) {

    companion object {
        /** 视频下载通知 */
        const val VIDEO_DOWNLOAD_CHANNEL_ID = "download_channel_id"
        /** 视频下载进度通知固定 ID，当前同一时间只展示一个下载进度。 */
        const val VIDEO_DOWNLOAD_NOTIFICATION_ID = 1002

        /** shizuku状态通知 */
        const val SHIZUKU_STATUS_CHANNEL_ID = "shizuku_status_channel_id"
        /** Shizuku 状态通知固定 ID，状态恢复时可直接取消同一条通知。 */
        const val SHIZUKU_STATUS_NOTIFICATION_ID = 1003

        /** 剪贴板数据更新通知 */
        const val CLIP_UPDATE_CHANNEL_ID = "clip_update_channel_id"
        /** 剪贴更新通知固定 ID，避免频繁复制时通知栏堆积。 */
        const val CLIP_UPDATE_NOTIFICATION_ID = 1004

        /** 视频下载完成或失败结果通知固定 ID。 */
        const val VIDEO_DOWNLOAD_RESULT_NOTIFICATION_ID = 1005

        /** 图片下载完成或失败结果通知固定 ID；固定 ID 覆盖上一次图片结果，避免通知栏堆积。 */
        const val IMAGE_DOWNLOAD_RESULT_NOTIFICATION_ID = 1006

        /** 通知 Intent 中描述目标页面类型的 key。 */
        const val EXTRA_TARGET = "extra_target"

        /** 通知 Intent 中写入的时间戳 key，用于区分同一目标的多次点击事件。 */
        const val EXTRA_TIMESTAMP = "extra_timestamp"


        /** 从通知跳转到详情页的字段 */
        const val TARGET_DETAIL = "target_detail"
        /** 剪贴详情通知携带的剪贴记录 ID。 */
        const val EXTRA_CLIP_ID = "extra_clip_id"

        /** 从通知跳转到下载结果页 */
        const val TARGET_VIDEO_DOWNLOAD_RESULT = "target_video_download_result"
        /** 视频下载结果通知携带的下载任务 ID。 */
        const val EXTRA_TASK_ID = "extra_task_id"

        /** 从通知直接打开图片批量下载目录。 */
        const val TARGET_IMAGE_FOLDER = "target_image_folder"
        /** 图片下载结果通知携带的批次输出目录。 */
        const val EXTRA_IMAGE_OUTPUT_DIR = "extra_image_output_dir"
        /**
         * 从通知 Intent 中解析剪贴详情跳转数据。
         *
         * 只有目标、ID 和时间戳都合法时才返回数据，避免普通启动 Intent 被误判为详情跳转。
         */
        fun Intent?.extractClipId(): ExtraData? {
            if (this == null) return null
            val target = getStringExtra(EXTRA_TARGET)
            if (target != TARGET_DETAIL) return null
            if (!hasExtra(EXTRA_CLIP_ID)) return null
            val id = getLongExtra(EXTRA_CLIP_ID, -1L).takeIf { it > 0 }
            if (id == null) {
                return null
            }

            val time = getLongExtra(EXTRA_TIMESTAMP, -1L)
            if (time <= 0) {
                return null
            }

            return ExtraData(id, time)
        }

        /**
         * 从通知 Intent 中解析视频下载结果跳转数据。
         *
         * timestamp 参与去重，确保同一 taskId 多次通知点击也能被 MainActivity 感知为新事件。
         */
        fun Intent?.extractTaskId(): ExtraData? {
            if (this == null) return null
            val target = getStringExtra(EXTRA_TARGET)
            if (target != TARGET_VIDEO_DOWNLOAD_RESULT) return null
            if (!hasExtra(EXTRA_TASK_ID)) return null
            val id = getLongExtra(EXTRA_TASK_ID, -1L).takeIf { it > 0 }
            if (id == null) {
                return null
            }

            val time = getLongExtra(EXTRA_TIMESTAMP, -1L)
            if (time <= 0) {
                return null
            }

            return ExtraData(id, time)
        }

        /**
         * 从通知 Intent 中解析图片保存目录打开数据。
         *
         * 图片下载结果通知不再复用视频下载页跳转，而是把批次输出目录交给 MainActivity 直接打开相册；timestamp 用于区分同一批次的多次点击。
         */
        fun Intent?.extractImageFolderOpenData(): ImageFolderOpenData? {
            if (this == null) return null
            val target = getStringExtra(EXTRA_TARGET)
            if (target != TARGET_IMAGE_FOLDER) return null

            val time = getLongExtra(EXTRA_TIMESTAMP, -1L)
            if (time <= 0) {
                return null
            }

            return ImageFolderOpenData(
                outputDir = getStringExtra(EXTRA_IMAGE_OUTPUT_DIR),
                timestamp = time
            )
        }
    }

    /** 系统通知管理器，懒加载以推迟系统服务访问。 */
    private val manager by lazy { appContext.getSystemService(NotificationManager::class.java) }

    /** 包管理器用于构造启动 App 的 PendingIntent。 */
    private val packageManager by lazy { appContext.packageManager }

    /** 当前应用包名，用于读取默认启动 Intent。 */
    private val packageName by lazy { appContext.packageName }

    /**
     * 点击通知时回到应用首页的 PendingIntent。
     *
     * 若系统无法返回 launcher Intent，则兜底打开 MainActivity，并清理顶部重复实例。
     */
    private val launchPendingIntent: PendingIntent
        get() {
            // 优先复用 launcher Intent，兜底 MainActivity，确保通知点击始终能回到应用。
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(appContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }

            return PendingIntent.getActivity(appContext, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    /**
     * 构建视频下载进度通知。
     *
     * progress 会收敛到 0..100；进度未完成时设置 ongoing，减少用户误滑掉正在进行的任务。
     */
    fun buildDownloadNotification(title: String, fileName: String, progress: Int) =
        NotificationCompat.Builder(appContext, VIDEO_DOWNLOAD_CHANNEL_ID)
            .setContentTitle(title)
            .setSubText(fileName)
            .setSmallIcon(R.mipmap.base_general_ic_app)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setSilent(true)
            .setDefaults(0)
            .setVibrate(longArrayOf(0L))
            .setOngoing(progress in 0..99)
            .build()

    /**
     * 展示视频下载结果通知。
     *
     * 点击通知会带 taskId 回到下载结果页；requestCode 使用 taskId，避免多个任务结果 PendingIntent 互相覆盖。
     */
    fun notifyDownloadResult(taskId: Long, title: String, fileName: String, content: String?) {
        createChannels()

        // 跳转到下载结果页
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_TARGET, TARGET_VIDEO_DOWNLOAD_RESULT)
            putExtra(EXTRA_TASK_ID, taskId)
            // 避免因 Intent 内容相同而 PendingIntent 被重用导致 extras 不更新。
            putExtra(EXTRA_TIMESTAMP, SystemClock.elapsedRealtime())
        }

        val pendingIntent = PendingIntent.getActivity(
            appContext,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, VIDEO_DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.mipmap.base_general_ic_app)
            .setContentTitle(title)
            .setSubText(fileName)
            .setContentText(content ?: "")
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        manager.notify(VIDEO_DOWNLOAD_RESULT_NOTIFICATION_ID, notification)
    }

    /**
     * 展示图片批量下载结果通知。
     *
     * 点击通知会把本批次 outputDir 带回 MainActivity，由统一工具直接打开相册；
     * requestCode 使用批次 id，避免不同图片批次的 PendingIntent extras 互相覆盖。
     */
    fun notifyImageDownloadResult(batchId: Long, outputDir: String?, title: String, fileName: String, content: String?) {
        createChannels()

        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_TARGET, TARGET_IMAGE_FOLDER)
            putExtra(EXTRA_IMAGE_OUTPUT_DIR, outputDir)
            // 同一批次可能被重新下载或再次通知，时间戳确保 MainVm 能把每次通知点击都当作独立事件消费。
            putExtra(EXTRA_TIMESTAMP, SystemClock.elapsedRealtime())
        }

        val pendingIntent = PendingIntent.getActivity(
            appContext,
            batchId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, VIDEO_DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.mipmap.base_general_ic_app)
            .setContentTitle(title)
            .setSubText(fileName)
            .setContentText(content ?: "")
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        manager.notify(IMAGE_DOWNLOAD_RESULT_NOTIFICATION_ID, notification)
    }

    /** 剪贴数据更新通知；只在数据库真实保存或更新剪贴记录后发送。 */
    fun notifyClipUpdate(
        title: String,
        content: String,
        clipId: Long
    ) {
        createChannels()

        // 跳转到详情页
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_TARGET, TARGET_DETAIL)
            putExtra(EXTRA_CLIP_ID, clipId)
            // 避免因 Intent 内容相同而 PendingIntent 被重用导致 extras 不更新。
            putExtra(EXTRA_TIMESTAMP, SystemClock.elapsedRealtime())
        }

        val pendingIntent = PendingIntent.getActivity(
            appContext,
            clipId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, CLIP_UPDATE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.base_general_ic_app)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setDefaults(0)
            .setVibrate(longArrayOf(0L))
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // 固定 ID 会覆盖上一条剪贴更新通知，避免连续复制时通知栏堆积。
        manager.notify(CLIP_UPDATE_NOTIFICATION_ID, notification)
    }

    /**
     * 展示 Shizuku 状态通知。
     *
     * 已连接状态会直接取消通知；未连接时使用状态资源或调用方提供的补充文案提示用户处理。
     */
    fun notifyShizukuStatus(status: ShizukuStatus, text: String? = null) {
        createChannels()

        val content = if (text.isNullOrBlank()) {
            if (status is ShizukuStatus.Connected) {
                cancelShizukuStatus()
                return
            }

            appContext.getString(status.textRes)
        } else {
            text
        }

        val notification = NotificationCompat.Builder(appContext, SHIZUKU_STATUS_CHANNEL_ID)
            .setSmallIcon(R.mipmap.base_general_ic_app)
            .setContentTitle(appContext.getString(R.string.base_general_shizuku_status))
            .setContentText(content)
            .setContentIntent(launchPendingIntent)
            .setOngoing(false)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setDefaults(0)
            .setVibrate(longArrayOf(0L))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        manager.notify(SHIZUKU_STATUS_NOTIFICATION_ID, notification)
    }

    /** 取消 Shizuku 状态通知，通常在服务恢复连接后调用。 */
    fun cancelShizukuStatus() {
        manager.cancel(SHIZUKU_STATUS_NOTIFICATION_ID)
    }

    /**
     * 创建通知渠道。
     *
     * Android O 以下没有渠道概念直接返回；所有渠道默认静音，避免剪贴保存和下载进度造成频繁打扰。
     */
    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val videoDownloadChannel = NotificationChannel(
            VIDEO_DOWNLOAD_CHANNEL_ID,
            appContext.getString(R.string.base_general_video_download),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            // 下载进度更新频繁，必须静音以免打扰用户。
            enableVibration(false)
            vibrationPattern = longArrayOf(0L)
            setSound(null, null)
            description = appContext.getString(R.string.base_general_update_the_download_progress_and_status)
        }

        val shizukuStatusChannel = NotificationChannel(
            SHIZUKU_STATUS_CHANNEL_ID,
            appContext.getString(R.string.base_general_shizuku_status),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            // Shizuku 状态变动可能由系统服务重启触发，保持静音即可。
            enableVibration(false)
            vibrationPattern = longArrayOf(0L)
            setSound(null, null)
            description = appContext.getString(R.string.base_general_shizuku_status_channel)
        }

        val clipUpdateChannel = NotificationChannel(
            CLIP_UPDATE_CHANNEL_ID,
            appContext.getString(R.string.base_general_clipboard_update),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            // 剪贴更新可能很频繁，只需要在通知栏展示最新内容，不打断当前操作。
            enableVibration(false)
            vibrationPattern = longArrayOf(0L)
            setSound(null, null)
            description = appContext.getString(R.string.base_general_display_clipboard_content_updates)
        }

        manager.createNotificationChannel(videoDownloadChannel)
        manager.createNotificationChannel(shizukuStatusChannel)
        manager.createNotificationChannel(clipUpdateChannel)
    }
}
