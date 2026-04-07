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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @param:ApplicationContext private val appContext: Context
) {

    companion object {
        /** shizuku状态通知 */
        const val STATUS_CHANNEL_ID = "clipboard_status_channel"
        const val STATUS_NOTIFICATION_ID = 1001   // 仅前台服务用，固定

        /** 剪贴板数据更新通知 */
        const val CLIP_CHANNEL_ID = "clipboard_update_channel"
        const val CLIP_NOTIFICATION_ID = 1002     // 剪贴板更新通知用

        /** 下载通知 */
        const val DOWNLOAD_CHANNEL_ID = "download_channel_id"
        const val DOWNLOAD_NOTIFICATION_ID = 1003
    }

    private val manager by lazy { appContext.getSystemService(NotificationManager::class.java) }

    private val packageManager by lazy { appContext.packageManager }

    private val packageName by lazy { appContext.packageName }

    private val pendingIntent: PendingIntent
        get() {
            // 1. 获取启动 App 的 Intent
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(appContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }

            // 2. 创建 PendingIntent
            return PendingIntent.getActivity(appContext, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }


    fun downloadForeground(service: Service, title: String, progress: Int) {
        createChannels()

        val notification = NotificationCompat.Builder(appContext, DOWNLOAD_CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(R.mipmap.base_general_ic_app)
            .setProgress(100, progress, false)
            .setSilent(true)          // 简单直接
            .setDefaults(0)           // 不用默认铃声/震动/灯
            .setVibrate(longArrayOf(0L))
            .setOngoing(true)
            .build()

        service.apply {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 14 (API 34) 强制要求指定前台服务类型
                    startForeground(
                        DOWNLOAD_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(DOWNLOAD_NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                // 如果 Manifest 中缺少 foregroundServiceType 属性，可能会抛出异常
                // 此时尝试不带 type 启动作为兜底
                startForeground(DOWNLOAD_NOTIFICATION_ID, notification)
            }
        }
    }

    fun startForeground(service: Service, title: String, content: String) {
        createChannels()

        val notification = NotificationCompat.Builder(appContext, STATUS_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.mipmap.base_general_ic_app) // 确保资源存在，或者使用 android.R.drawable.ic_menu_save
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent) // 3. 设置点击行为
            .setSilent(true)          // 简单直接
            .setDefaults(0)           // 不用默认铃声/震动/灯
            .setVibrate(longArrayOf(0L))
            .setOngoing(true)
            .build()

        service.apply {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 14 (API 34) 强制要求指定前台服务类型
                    startForeground(
                        STATUS_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(STATUS_NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                // 如果 Manifest 中缺少 foregroundServiceType 属性，可能会抛出异常
                // 此时尝试不带 type 启动作为兜底
                startForeground(STATUS_NOTIFICATION_ID, notification)
            }
        }
    }

    /** 普通消息通知 */
    fun notifyNormalMessage(
        title: String,
        content: String,
        channelId: String,
        notificationId: Int
    ) {
        createChannels()

        val notification = NotificationCompat.Builder(appContext, channelId)
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        manager.notify(notificationId, notification) // 固定ID=覆盖上一条；若想每条都保留可用递增ID
    }

    /** 创建渠道 */
    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val statusChannel = NotificationChannel(
            STATUS_CHANNEL_ID,
            appContext.getString(R.string.base_general_clipboard_service),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            enableVibration(false) // 关闭震动
            vibrationPattern = longArrayOf(0L)
            setSound(null, null) // 关闭铃声
            description = appContext.getString(R.string.base_general_listen_for_changes_in_the_clipboard_content)
        }

        val clipChannel = NotificationChannel(
            CLIP_CHANNEL_ID,
            appContext.getString(R.string.base_general_clipboard_update),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableVibration(false) // 关闭震动
            vibrationPattern = longArrayOf(0L)
            setSound(null, null) // 关闭铃声
            description = appContext.getString(R.string.base_general_display_clipboard_content_updates)
        }

        val downloadChannel = NotificationChannel(
            DOWNLOAD_CHANNEL_ID,
            appContext.getString(R.string.base_general_video_download),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            enableVibration(false) // 关闭震动
            vibrationPattern = longArrayOf(0L)
            setSound(null, null) // 关闭铃声
            description = appContext.getString(R.string.base_general_update_the_download_progress_and_status)
        }

        manager.createNotificationChannel(downloadChannel)
        manager.createNotificationChannel(statusChannel)
        manager.createNotificationChannel(clipChannel)
    }
}