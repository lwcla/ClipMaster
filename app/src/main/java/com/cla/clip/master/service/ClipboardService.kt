package com.cla.clip.master.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.cla.clip.base.general.logD
import com.cla.clip.base.general.logI
import com.cla.clip.master.BuildConfig
import com.cla.clip.master.R
import com.cla.clip.shizuku.ClipboardShizukuService
import com.cla.clip.shizuku.IClipboardShizukuService
import com.cla.clip.shizuku.ShizukuCallback
import com.cla.clip.shizuku.ShizukuStatus
import com.cla.clip.shizuku.ShizukuUtils
import rikka.shizuku.Shizuku

/** 剪贴板监听服务 */
class ClipboardService : Service() {

    companion object {
        private const val TAG = "ClipboardService"

        private const val CHANNEL_ID = "clipboard_service_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            logI(TAG) { "start" }
            val serviceIntent = Intent(context, ClipboardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }

        fun stop(context: Context) {
            logI(TAG) { "stop" }
            val serviceIntent = Intent(context, ClipboardService::class.java)
            context.stopService(serviceIntent)
        }
    }

    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    private val clipboardManager by lazy { getSystemService(ClipboardManager::class.java) }
    private val windowManager by lazy { getSystemService(WindowManager::class.java) as WindowManager }
    private val handler by lazy { Handler(mainLooper) }

    private val userServiceArgs = Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ClipboardShizukuService::class.java.name))
        .daemon(false)
        .processNameSuffix("cla_clip_master_shizuku")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                val service = IClipboardShizukuService.Stub.asInterface(binder)
                service.start()
                service.addCallback(object : ShizukuCallback.Stub() {
                    override fun onOpNoted(packageName: String, appName: String?, appIcon: Bitmap?) {
                        if (packageName == BuildConfig.APPLICATION_ID) {
                            return
                        }

                        logD(TAG) {
                            """
                            剪贴板有更新了：
                            packageName=$packageName
                            appName=$appName
                            appIcon=${appIcon?.width} x ${appIcon?.height}
                            """.trimIndent()
                        }

                        magic(appName ?: packageName)
                    }
                })
                ensureForeground()
            } else {
                ensureForeground()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            ensureForeground()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        logD(TAG) { "binderReceivedListener: " }
        ensureForeground()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        logD(TAG) { "binderDeadListener: " }
        ensureForeground()
    }


    override fun onCreate() {
        super.onCreate()
        // 服务创建时，立即尝试提升为前台服务
        logI(TAG) { "onCreate: " }
        ensureForeground()

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.bindUserService(userServiceArgs, userServiceConnection)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 关键：每次调用 startForegroundService 后，必须再次调用 startForeground，
        // 否则在 API 26+ 设备上可能会因为“未能在规定时间内进入前台”而崩溃。
        logI(TAG) { "onStartCommand: " }

        ensureForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        if (Shizuku.pingBinder()) Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    private fun magic(appName: String) {
        // 通过添加一个不可见的 View 来触发系统读取剪贴板内容，从而获取最新的剪贴板数据
        handler.post {
            val view = View(applicationContext)
            windowManager.addView(view, WindowManager.LayoutParams(-2, -2, 2038, 32, -3).apply {
                x = 0
                y = 0
                width = 0
                height = 0
            })
            doClipboard(appName)
            windowManager.removeView(view)
        }
    }

    private fun doClipboard(appName: String) {
        clipboardManager.primaryClip?.getItemAt(0)?.text?.let {
            logD(TAG) { "读取到剪贴板内容：$it" }
            if (it.isBlank()) {
                return@let
            }

            ensureForeground(
                title = "$appName 写入了剪切板",
                content = "内容：$it"
            )
        }
    }

    private fun ensureForeground(title: String? = null, content: String? = null) {
        createNotificationChannel()

        val contentText = content ?: when (ShizukuUtils.checkStatus(applicationContext).also { logD(TAG) { "ensureForeground: Shizuku 状态：$it" } }) {
            is ShizukuStatus.Connected -> applicationContext.getString(R.string.host_service_is_running)
            is ShizukuStatus.Disconnect.NotInstalled -> applicationContext.getString(R.string.host_shizuku_not_install)
            is ShizukuStatus.Disconnect.ServiceNotAlive -> applicationContext.getString(R.string.host_shizuku_service_not_alive)
            is ShizukuStatus.Disconnect.VersionTooLow -> applicationContext.getString(R.string.host_shizuku_version_too_low)
            is ShizukuStatus.Disconnect.NotGranted -> applicationContext.getString(R.string.host_shizuku_not_granted)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title ?: applicationContext.getString(R.string.host_clipboard_assistant))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // 确保资源存在，或者使用 android.R.drawable.ic_menu_save
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 14 (API 34) 强制要求指定前台服务类型
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // 如果 Manifest 中缺少 foregroundServiceType 属性，可能会抛出异常
            // 此时尝试不带 type 启动作为兜底
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.host_clipboard_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = applicationContext.getString(R.string.host_listen_for_changes_in_the_clipboard_content)
            }
            manager.createNotificationChannel(serviceChannel)
        }
    }
}