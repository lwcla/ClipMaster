package com.cla.clip.master.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.palette.graphics.Palette
import com.cla.clip.base.general.entity.ClipCaptureEntity
import com.cla.clip.base.general.logD
import com.cla.clip.base.general.logI
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.master.BuildConfig
import com.cla.clip.master.R
import com.cla.clip.shizuku.ClipboardShizukuService
import com.cla.clip.shizuku.IClipboardShizukuService
import com.cla.clip.shizuku.ShizukuCallback
import com.cla.clip.shizuku.ShizukuStatus
import com.cla.clip.shizuku.ShizukuUtils
import dagger.hilt.android.AndroidEntryPoint
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** 剪贴板监听服务 */
@AndroidEntryPoint
class ClipboardService : Service() {

    companion object {
        private const val TAG = "ClipboardService"

        private const val CHANNEL_ID = "clipboard_service_channel"
        private const val NOTIFICATION_ID = 1001

        private const val APP_ICONS_DIR = "app_icons"

        /** URL正则表达式，用于检测内容是否为链接 */
        private val URL_PATTERN = Regex(
            "^(https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]",
            RegexOption.IGNORE_CASE
        )

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

    @Inject
    lateinit var clipRepository: ClipRepository

    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    private val clipboardManager by lazy { getSystemService(ClipboardManager::class.java) }
    private val windowManager by lazy { getSystemService(WindowManager::class.java) as WindowManager }
    private val handler by lazy { Handler(mainLooper) }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

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

                        magic(packageName, appName, appIcon)
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
        logI(TAG) { "onDestroy : " }
        serviceJob.cancel()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        if (Shizuku.pingBinder()) Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    private fun magic(packageName: String, appName: String?, appIcon: Bitmap?) {
        // 通过添加一个不可见的 View 来触发系统读取剪贴板内容，从而获取最新的剪贴板数据
        handler.post {
            val view = View(applicationContext)
            windowManager.addView(view, WindowManager.LayoutParams(-2, -2, 2038, 32, -3).apply {
                x = 0
                y = 0
                width = 0
                height = 0
            })
            doClipboard(packageName, appName, appIcon)
            windowManager.removeView(view)
        }
    }

    private fun doClipboard(packageName: String, appName: String?, appIcon: Bitmap?) {
        clipboardManager.primaryClip?.let { clipData ->
            val clip = runCatching { clipData.getItemAt(0) }.getOrNull() ?: return@let
            logD(TAG) { "读取到剪贴板内容：${clip.text}" }

            serviceScope.launch(Dispatchers.IO) {
                processClip(clip, packageName, appName, appIcon)
            }
        }
    }

    /**
     * 处理新的剪贴板内容
     */
    private suspend fun processClip(
        item: android.content.ClipData.Item,
        packageName: String,
        appName: String?,
        appIcon: Bitmap?
    ) {
        try {
            // 保存剪贴板内容
            val contentUri = item.uri
            val contentText = item.text?.toString()

            // 确定内容类型和实际内容
//            val clipType: ClipType
            val clipContent = when {
                // 处理图片类型
                contentUri != null && contentUri.toString().startsWith("content://") -> {
                    //                    clipType = ClipType.IMAGE
                    saveImageAndGetPath(contentUri)
                }
                // 处理链接类型
                contentText != null && URL_PATTERN.matches(contentText) -> {
                    //                    clipType = ClipType.LINK
                    contentText
                }
                // 处理文本类型
                contentText != null -> {
                    //                    clipType = ClipType.TEXT
                    contentText
                }
                // 未知类型，忽略
                else -> null
            }?.trim()

            if (clipContent.isNullOrBlank()) {
                return
            }

            val lastClip = clipRepository.getLatestClip()
            if (lastClip != null && lastClip.content == clipContent) {
                // 内容未变化，不处理
                logD(TAG) { "processClip: 内容跟上条数据一样，不要重复保存" }
                return
            }

            // 对于链接类型，启动链接解析任务
//            if (clipType == ClipType.LINK) {
//                // TODO: 实现链接解析逻辑
//                // LinkParserWorker.enqueue(this@ClipboardService, clipContent, newClip.id)
//            }

            // 对于图片类型，启动OCR任务
//            if (clipType == ClipType.IMAGE) {
//                // TODO: 实现图片OCR逻辑
//                // OcrProcessingWorker.enqueue(this@ClipboardService, clipContent, newClip.id)
//            }

            val color = if (appIcon != null) {
                // 提取图标的主色调作为标签颜色
                runCatching {
                    val palette = Palette.from(appIcon).generate()
                    palette.getDominantColor(0xFF000000.toInt()) // 默认黑色
                }.getOrNull()
            } else {
                null
            }

            val captureEntity = ClipCaptureEntity(
                content = clipContent,
                timestamp = System.currentTimeMillis(),
                sourcePackage = packageName,
                sourceAppName = appName ?: "unknown",
                sourceAppIconPath = saveAppIcon(packageName, appIcon),
                sourcePrimaryColor = color,
                linkTitle = null,
                linkDescription = null,
                linkImageUrl = null,
                linkSiteName = null,
            )

            logI(TAG) { "processClip: captureEntity=$captureEntity" }

            // 保存到数据库
            clipRepository.addNewClip(captureEntity)

            withContext(Dispatchers.Main) {
                ensureForeground(
                    title = "$appName 写入了剪切板",
                    content = "内容：${clipContent}"
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 保存应用图标到内部存储，并返回保存路径
     */
    private fun saveAppIcon(packageName: String, icon: Bitmap?): String? {
        if (icon == null) {
            return null
        }

        val iconDir = File(filesDir, APP_ICONS_DIR)
        if (!iconDir.exists()) {
            iconDir.mkdirs()
        }

        val iconFile = File(iconDir, "$packageName.png")
        return try {
            FileOutputStream(iconFile).use { out ->
                icon.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            iconFile.absolutePath
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 保存剪贴板中的图片，并返回保存路径
     */
    private fun saveImageAndGetPath(imageUri: Uri): String {
        val imageDir = File(filesDir, "clip_images")
        if (!imageDir.exists()) {
            imageDir.mkdirs()
        }

        val fileName = "clip_img_${UUID.randomUUID()}.png"
        val imageFile = File(imageDir, fileName)

        return try {
            contentResolver.openInputStream(imageUri)?.use { input ->
                FileOutputStream(imageFile).use { output ->
                    input.copyTo(output)
                }
            }

            // 返回ContentProvider URI，确保应用内可访问
            val fileUri = FileProvider.getUriForFile(
                this@ClipboardService,
                "${applicationContext.packageName}.fileprovider",
                imageFile
            )

            fileUri.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            ""
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

        // 1. 获取启动 App 的 Intent
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)

        // 2. 创建 PendingIntent
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else null

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title ?: applicationContext.getString(R.string.host_app_name))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.host_ic_launcher_foreground) // 确保资源存在，或者使用 android.R.drawable.ic_menu_save
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent) // 3. 设置点击行为
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