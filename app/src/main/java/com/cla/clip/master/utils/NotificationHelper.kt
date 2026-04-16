package com.cla.clip.master.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import com.cla.clip.base.general.R
import com.cla.clip.master.MainActivity
import com.cla.clip.shizuku.ShizukuStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @param:ApplicationContext private val appContext: Context
) {

    companion object {
        /** 读取剪贴板数据通知 */
        const val READ_CLIP_CHANNEL_ID = "read_clip_channel_id"
        const val READ_CLIP_NOTIFICATION_ID = 1001

        /** 视频下载通知 */
        const val VIDEO_DOWNLOAD_CHANNEL_ID = "download_channel_id"
        const val VIDEO_DOWNLOAD_NOTIFICATION_ID = 1002

        /** shizuku状态通知 */
        const val SHIZUKU_STATUS_CHANNEL_ID = "shizuku_status_channel_id"
        const val SHIZUKU_STATUS_NOTIFICATION_ID = 1003

        /** 剪贴板数据更新通知 */
        const val CLIP_UPDATE_CHANNEL_ID = "clip_update_channel_id"
        const val CLIP_UPDATE_NOTIFICATION_ID = 1004

        /** 下载结果 */
        const val VIDEO_DOWNLOAD_RESULT_NOTIFICATION_ID = 1005

        const val EXTRA_TARGET = "extra_target"


        /** 从通知跳转到详情页的字段 */
        const val TARGET_DETAIL = "target_detail"
        const val EXTRA_CLIP_ID = "extra_clip_id"

        /** 从通知跳转到下载结果页 */
        const val TARGET_VIDEO_DOWNLOAD_RESULT = "target_video_download_result"
        const val EXTRA_TASK_ID = "extra_task_id"

        fun Intent?.extractClipId(): Long? {
            if (this == null) return null
            val target = getStringExtra(EXTRA_TARGET)
            if (target != TARGET_DETAIL) return null
            if (!hasExtra(EXTRA_CLIP_ID)) return null
            return getLongExtra(EXTRA_CLIP_ID, -1L).takeIf { it > 0 }
        }

        fun Intent?.extractTaskId(): Long? {
            if (this == null) return null
            val target = getStringExtra(EXTRA_TARGET)
            if (target != TARGET_VIDEO_DOWNLOAD_RESULT) return null
            if (!hasExtra(EXTRA_TASK_ID)) return null
            return getLongExtra(EXTRA_TASK_ID, -1L).takeIf { it > 0 }
        }
    }

    private val manager by lazy { appContext.getSystemService(NotificationManager::class.java) }

    private val packageManager by lazy { appContext.packageManager }

    private val packageName by lazy { appContext.packageName }

    private val launchPendingIntent: PendingIntent
        get() {
            // 1. 获取启动 App 的 Intent
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(appContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }

            // 2. 创建 PendingIntent
            return PendingIntent.getActivity(appContext, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

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

    fun notifyDownloadResult(taskId: Long, title: String, fileName: String, content: String?) {
        createChannels()

        // 跳转到下载结果页
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_TARGET, TARGET_VIDEO_DOWNLOAD_RESULT)
            putExtra(EXTRA_TASK_ID, taskId)
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
            .setSubText(fileName) // 显示在标题下方，突出显示文件名
            .setContentText(content ?: "")
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true) // 点击后自动消失
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
            .setSmallIcon(R.mipmap.base_general_ic_app) // 确保资源存在，或者使用 android.R.drawable.ic_menu_save
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(launchPendingIntent) // 3. 设置点击行为
            .setSilent(true)          // 简单直接
            .setDefaults(0)           // 不用默认铃声/震动/灯
            .setVibrate(longArrayOf(0L))
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)  // 更新文案时不重复提示音
            .setOngoing(true) // true ：常驻样式（用户通常不能滑掉）
            .build()

        service.apply {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 14 (API 34) 强制要求指定前台服务类型
                    startForeground(
                        READ_CLIP_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(READ_CLIP_NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                // 如果 Manifest 中缺少 foregroundServiceType 属性，可能会抛出异常
                // 此时尝试不带 type 启动作为兜底
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
            .setSilent(true)          // 简单直接
            .setDefaults(0)           // 不用默认铃声/震动/灯
            .setVibrate(longArrayOf(0L))
            .setOngoing(false) // true ：常驻样式（用户通常不能滑掉）
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        manager.notify(CLIP_UPDATE_NOTIFICATION_ID, notification) // 固定ID=覆盖上一条；若想每条都保留可用递增ID
    }

    /** shizuku状态通知 */
    fun notifyShizukuStatus(status: ShizukuStatus) {
        createChannels()

        if (status is ShizukuStatus.Connected) {
            cancelShizukuStatus()
            return
        }

        val content = appContext.getString(status.textRes)
        val notification = NotificationCompat.Builder(appContext, SHIZUKU_STATUS_CHANNEL_ID)
            .setSmallIcon(R.mipmap.base_general_ic_app)
            .setContentTitle(appContext.getString(R.string.base_general_shizuku_status))
            .setContentText(content)
            .setContentIntent(launchPendingIntent)
            .setOngoing(false)  // true ：常驻样式（用户通常不能滑掉）
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)   // 更新文案时不重复提示音
            .setSilent(true)          // 简单直接
            .setDefaults(0)           // 不用默认铃声/震动/灯
            .setVibrate(longArrayOf(0L))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        manager.notify(SHIZUKU_STATUS_NOTIFICATION_ID, notification)
    }

    /** 取消shizuku状态通知 */
    fun cancelShizukuStatus() {
        manager.cancel(SHIZUKU_STATUS_NOTIFICATION_ID)
    }

    /** 创建渠道 */
    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val readClipChannel = NotificationChannel(
            READ_CLIP_CHANNEL_ID,
            appContext.getString(R.string.base_general_clipboard_service),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            enableVibration(false) // 关闭震动
            vibrationPattern = longArrayOf(0L)
            setSound(null, null) // 关闭铃声
            description = appContext.getString(R.string.base_general_read_clip_channel)
        }

        val videoDownloadChannel = NotificationChannel(
            VIDEO_DOWNLOAD_CHANNEL_ID,
            appContext.getString(R.string.base_general_video_download),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            enableVibration(false) // 关闭震动
            vibrationPattern = longArrayOf(0L)
            setSound(null, null) // 关闭铃声
            description = appContext.getString(R.string.base_general_update_the_download_progress_and_status)
        }

        val shizukuStatusChannel = NotificationChannel(
            SHIZUKU_STATUS_CHANNEL_ID,
            appContext.getString(R.string.base_general_shizuku_status),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            enableVibration(false) // 关闭震动
            vibrationPattern = longArrayOf(0L)
            setSound(null, null) // 关闭铃声
            description = appContext.getString(R.string.base_general_shizuku_status_channel)
        }

        val clipUpdateChannel = NotificationChannel(
            CLIP_UPDATE_CHANNEL_ID,
            appContext.getString(R.string.base_general_clipboard_service),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            enableVibration(false) // 关闭震动
            vibrationPattern = longArrayOf(0L)
            setSound(null, null) // 关闭铃声
            description = appContext.getString(R.string.base_general_display_clipboard_content_updates)
        }

        manager.createNotificationChannel(videoDownloadChannel)
        manager.createNotificationChannel(readClipChannel)
        manager.createNotificationChannel(shizukuStatusChannel)
        manager.createNotificationChannel(clipUpdateChannel)
    }
}