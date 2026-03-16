package com.cla.clip.base.general.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cla.clip.base.general.BuildConfig
import com.cla.clip.base.general.logD
import com.cla.clip.base.general.logE
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    companion object {

        private const val TAG = "NotificationManager"

        private const val SHIZUKU_CHANNEL_ID = "shizuku_status"

        private const val SHIZUKU_NOTIFICATION_ID = 0x001 // 可以使用固定 ID 来更新同一条通知

    }

    private val packageName get() = BuildConfig.APPLICATION_ID

    private val manager by lazy { NotificationManagerCompat.from(context) }


    val hasPermission
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    @SuppressLint("MissingPermission")
    fun shizukuStatus(text: String) {
        if (!hasPermission) {
            logE(TAG) { "shizukuStatus: 没有通知权限，无法显示通知" }

            return
        }

        if (text == context.getString(com.cla.clip.base.general.R.string.base_general_service_is_running)) {
            // 服务正在运行，不需要通知
            logD(TAG) { "shizukuStatus: 服务正在运行，关闭通知" }
            manager.cancel(SHIZUKU_NOTIFICATION_ID)
            return
        }


        logD(TAG) { "shizukuStatus: 更新通知 text=${text}" }

        // 1. 获取启动 App 的 Intent
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)

        // 2. 创建 PendingIntent
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(context, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else null

        createNotificationChannel()

        val notification = NotificationCompat.Builder(context, SHIZUKU_CHANNEL_ID)
            .setContentTitle(context.getString(com.cla.clip.base.general.R.string.base_general_app_name))
            .setContentText(text)
            .setSmallIcon(com.cla.clip.base.general.R.drawable.icon_app) // 确保资源存在，或者使用 android.R.drawable.ic_menu_save
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pendingIntent) // 3. 设置点击行为
            .setOngoing(true)
            .build()


        manager.notify(SHIZUKU_NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                SHIZUKU_CHANNEL_ID,
                context.getString(com.cla.clip.base.general.R.string.base_general_clipboard_service),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(com.cla.clip.base.general.R.string.base_general_listen_for_changes_in_the_clipboard_content)
            }
            manager.createNotificationChannel(serviceChannel)
        }
    }
}