package com.cla.clip.master.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.cla.clip.base.general.R
import com.cla.clip.master.MainActivity
import com.cla.clip.master.entity.ExtraData
import com.cla.clip.shizuku.ShizukuStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用通知构建与渠道管理工具。
 *
 * 集中管理剪贴板前台服务、剪贴更新、Shizuku 状态和视频下载相关通知，保证渠道 ID、通知 ID 和 PendingIntent extra 约定一致。
 */
@Singleton
class NotificationHelper @Inject constructor(
    /** 应用级 Context，用于创建通知、读取资源和构造跳转 Intent。 */
    @param:ApplicationContext private val appContext: Context
) {

    companion object {
        /** 读取剪贴板数据通知 */
        const val READ_CLIP_CHANNEL_ID = "read_clip_channel_id"
        /** 读取剪贴板前台服务通知固定 ID，固定 ID 便于重复启动服务时更新同一条通知。 */
        const val READ_CLIP_NOTIFICATION_ID = 1001

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

    /** 读取剪贴板的前台服务通知 */
    fun readClipForeground(service: Service) {
        createChannels()

        val notification = NotificationCompat.Builder(appContext, READ_CLIP_CHANNEL_ID)
            .setContentTitle(appContext.getString(R.string.base_general_app_name))
            .setContentText(appContext.getString(R.string.base_general_the_clipboard_is_being_read))
            .setSmallIcon(R.mipmap.base_general_ic_app)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(launchPendingIntent)
            .setSilent(true)
            .setDefaults(0)
            .setVibrate(longArrayOf(0L))
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()

        service.apply {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ 支持声明前台服务类型；Android 14 会更严格校验 Manifest 中的声明。
                    startForeground(
                        READ_CLIP_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(READ_CLIP_NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                // 如果 Manifest 中缺少 foregroundServiceType 属性，兜底尝试不带 type 启动，避免服务完全不可用。
                startForeground(READ_CLIP_NOTIFICATION_ID, notification)
            }
        }
    }

    /** 剪贴数据更新通知 */
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
     * Android O 以下没有渠道概念直接返回；所有渠道默认静音，避免剪贴板监听和下载进度造成频繁打扰。
     */
    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val readClipChannel = NotificationChannel(
            READ_CLIP_CHANNEL_ID,
            appContext.getString(R.string.base_general_clipboard_service),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            // 前台服务通知只用于保活说明，不应反复震动或响铃。
            enableVibration(false)
            vibrationPattern = longArrayOf(0L)
            setSound(null, null)
            description = appContext.getString(R.string.base_general_read_clip_channel)
        }

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
            appContext.getString(R.string.base_general_clipboard_service),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            // 剪贴更新可能很频繁，只需要在通知栏展示最新内容，不打断当前操作。
            enableVibration(false)
            vibrationPattern = longArrayOf(0L)
            setSound(null, null)
            description = appContext.getString(R.string.base_general_display_clipboard_content_updates)
        }

        manager.createNotificationChannel(videoDownloadChannel)
        manager.createNotificationChannel(readClipChannel)
        manager.createNotificationChannel(shizukuStatusChannel)
        manager.createNotificationChannel(clipUpdateChannel)
    }
}
