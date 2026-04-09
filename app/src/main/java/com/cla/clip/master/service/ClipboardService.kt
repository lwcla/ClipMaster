package com.cla.clip.master.service

import android.app.Service
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import androidx.core.content.FileProvider
import com.cla.clip.base.general.R
import com.cla.clip.base.general.entity.ClipCaptureEntity
import com.cla.clip.base.general.repository.ClipDao
import com.cla.clip.base.general.utils.ApplicationScope
import com.cla.clip.base.general.utils.LinkUtils
import com.cla.clip.base.general.utils.extractUsableColor
import com.cla.clip.base.general.utils.hasOverlayPermission
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.toast
import com.cla.clip.master.BuildConfig
import com.cla.clip.master.utils.LinkMeta
import com.cla.clip.master.utils.LinkMetaParser
import com.cla.clip.master.utils.NotificationHelper
import com.cla.clip.shizuku.ClipboardShizukuService
import com.cla.clip.shizuku.IClipboardShizukuService
import com.cla.clip.shizuku.ShizukuCallback
import com.cla.clip.shizuku.ShizukuStatus
import com.cla.clip.shizuku.ShizukuUtils
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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


        private const val APP_ICONS_DIR = "app_icons"

        fun start(context: Context) {
            logI(TAG) { "start" }
            val serviceIntent = Intent(context, ClipboardService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    @Inject
    lateinit var clipDao: ClipDao

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    @Inject
    @ApplicationContext
    lateinit var appContext: Context

    @Inject
    lateinit var notificationHelper: NotificationHelper

    private val clipboardManager by lazy { getSystemService(ClipboardManager::class.java) }
    private val windowManager by lazy { getSystemService(WindowManager::class.java) as WindowManager }

    private var shizukuService: IClipboardShizukuService? = null

    private val userServiceArgs = Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ClipboardShizukuService::class.java.name))
        .daemon(true) // 守护进程，确保服务在后台持续运行
        .processNameSuffix("shizuku")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)
        .tag(BuildConfig.APPLICATION_ID + TAG)

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            logI(TAG) { "userServiceConnection : 已经连接 pingBinder=${binder?.pingBinder()}" }
            if (binder != null && binder.pingBinder()) {
                shizukuService = IClipboardShizukuService.Stub.asInterface(binder).also { service ->
                    service.start()
                    service.setCallback(object : ShizukuCallback.Stub() {
                        override fun onOpNoted(packageName: String?, appName: String?, appIcon: Bitmap?, iconHash: String?) {
                            if (packageName == BuildConfig.APPLICATION_ID) {
                                // 自己复制的内容，不处理
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

                            scope.launch(Dispatchers.Main) {
                                // 在这里刷新一次前台服务的通知，因为手机隔了一夜之后，app和shizuku的进程虽然都还在，但没有前台通知，
                                // 在其他app复制内容到剪贴板时，shizuku的回调能正常触发，但因为没有前台通知，所以无法添加view到windowManager里去读取剪贴板内容，导致无法获取剪贴板数据。
                                // 通过刷新前台通知，可以让服务重新进入前台，从而恢复添加view的能力。
                                startForeground()
                                magic(packageName, appName, appIcon, iconHash)
                            }
                        }
                    })
                }
            } else {
                runCatching { shizukuService?.setCallback(null) }.getOrElse {
                    logE(TAG, it) { "callback 置空出错 1" }
                }
            }

            startForeground()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            logE(TAG) { "userServiceConnection: 断开连接" }
            runCatching { shizukuService?.setCallback(null) }.getOrElse {
                logE(TAG, it) { "callback 置空出错 2" }
            }
            startForeground()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        logD(TAG) { "binderReceivedListener: " }
        startForeground()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        logD(TAG) { "binderDeadListener: " }
        startForeground()
    }

    override fun onCreate() {
        super.onCreate()
        // 服务创建时，立即尝试提升为前台服务
        logI(TAG) { "onCreate: " }
        notificationHelper.createChannels()
        startForeground()

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        val result = Shizuku.peekUserService(userServiceArgs, userServiceConnection)
        if (result == -1) {
            logI(TAG) { "去绑定 shizuku 远程服务" }
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        } else {
            logI(TAG) { "连接 shizuku 远程服务成功" }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 关键：每次调用 startForegroundService 后，必须再次调用 startForeground，
        // 否则在 API 26+ 设备上可能会因为“未能在规定时间内进入前台”而崩溃。
        logD(TAG) { "onStartCommand: " }
        startForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        logI(TAG) { "onDestroy : " }
        removeListener()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        logI(TAG) { "onTaskRemoved: " }
        // 不要在这里做清除监听的操作，因为从任务栈移除时，进程并不一定被杀死，前台服务可能还在运行中
        //removeListener()
        super.onTaskRemoved(rootIntent)
    }

    private fun removeListener() {
        runCatching { shizukuService?.setCallback(null) }.getOrElse {
            logE(TAG, it) { "callback 置空出错 3" }
        }
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        runCatching {
            Shizuku.unbindUserService(userServiceArgs, userServiceConnection, false)
        }.getOrElse {
            logE(TAG, it) { "unbindUserService 失败" }
        }
    }

    private suspend fun magic(
        packageName: String?,
        appName: String?,
        appIcon: Bitmap?,
        iconHash: String?
    ) = withContext(Dispatchers.Main.immediate) {
        // 检查当前有没有悬浮窗权限
        if (!appContext.hasOverlayPermission()) {
            appContext.toast(R.string.base_general_without_the_floating_window_permission)
            logE(TAG) { "没有悬浮窗权限，无法读取剪贴板内容" }
            return@withContext
        }

        // 通过添加一个不可见的 View 来触发系统读取剪贴板内容，从而获取最新的剪贴板数据
        val view = View(appContext)

        windowManager.addView(view, WindowManager.LayoutParams().apply {
            // 根据 API 版本选择窗口类型
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY  // API 26+
            } else {
                WindowManager.LayoutParams.TYPE_PHONE  // API 24-25
            }
            format = android.graphics.PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            x = 0
            y = 0
            width = 1
            height = 1
        })

        runCatching {
            // 读取剪贴板内容
            val clipData = clipboardManager.primaryClip
            val clip = clipData?.let { runCatching { it.getItemAt(0) }.getOrNull() }

            windowManager.removeView(view)

            if (clip != null) {
                withContext(Dispatchers.IO) {
                    processClip(clip, packageName, appName, appIcon, iconHash)
                }
            }
        }.getOrElse {
            logE(TAG, it) { "读取剪贴板内容出错" }
            // 确保移除 view，避免泄漏
            runCatching {
                windowManager.removeView(view)
            }.getOrElse { tr ->
                logE(TAG, tr) { "移除悬浮View出错" }
            }
        }
    }

    /** 处理新的剪贴板内容 */
    private suspend fun processClip(
        item: android.content.ClipData.Item,
        packageName: String?,
        appName: String?,
        appIcon: Bitmap?,
        iconHash: String?
    ) {
        try {
            // 保存剪贴板内容
            val contentUri = item.uri
            val contentText = item.text?.toString()

            val clipContent = when {
                // 处理图片类型
                contentUri != null && contentUri.toString().startsWith("content://") -> {
                    saveImageAndGetPath(contentUri)
                }

                else -> contentText
            }?.trim()

            if (clipContent.isNullOrBlank()) {
                return
            }

            // 对于图片类型，启动OCR任务
//            if (clipType == ClipType.IMAGE) {
//                // TODO: 实现图片OCR逻辑
//                // OcrProcessingWorker.enqueue(this@ClipboardService, clipContent, newClip.id)
//            }

            val sourceAppData = packageName?.let { clipDao.loadSourceApp(it) }
            val appColor: Int?
            val appIconPath: String?

            if (sourceAppData?.iconHash == iconHash) {
                logD(TAG) { "processClip 使用数据库中的应用数据" }
                appColor = sourceAppData?.primaryColor
                appIconPath = sourceAppData?.iconPath
            } else {
                logD(TAG) { "processClip 去提取应用图标的颜色和保存图标到本地" }
                // 提取图标里的颜色后续用来做边框的颜色
                appColor = appIcon?.extractUsableColor()
                appIconPath = saveAppIcon(packageName, appIcon)
            }

            val extractedLink = LinkUtils.extractFirstPreviewableUrl(contentText)
            val linkMeta = if (!extractedLink.isNullOrBlank()) {
                val history = clipDao.loadLinkPreview(extractedLink)
                if (!history?.imageUrl.isNullOrBlank()) {
                    logD(TAG) { "processClip 使用数据库中的链接数据 extractedLink=$extractedLink" }
                    // 避免重复解析链接
                    LinkMeta(history.title, history.description, history.imageUrl, history.siteName)
                } else {
                    logD(TAG) { "processClip 去解析链接 extractedLink=$extractedLink" }
                    LinkMetaParser.parse(extractedLink)
                }
            } else {
                null
            }

            val captureEntity = ClipCaptureEntity(
                content = clipContent,
                timestamp = System.currentTimeMillis(),
                sourcePackage = packageName ?: "",
                sourceAppName = appName ?: "unknown",
                sourceAppIconPath = appIconPath,
                sourcePrimaryColor = appColor,
                sourceAppIconHash = iconHash,
                link = extractedLink,
                linkTitle = linkMeta?.title,
                linkDescription = linkMeta?.description,
                linkImageUrl = linkMeta?.imageUrl,
                linkSiteName = linkMeta?.siteName,
            )

            logI(TAG) { "processClip: isLink=${!extractedLink.isNullOrBlank()} captureEntity=$captureEntity" }

            // 保存到数据库
            val clipId = clipDao.addNewClip(captureEntity)

            notificationHelper.notifyClipUpdate(
                title = "$appName ${appContext.getString(com.cla.clip.base.general.R.string.base_general_it_was_written_into_the_clipboard)}",
                content = "${appContext.getString(com.cla.clip.base.general.R.string.base_general_content)}${clipContent}",
                clipId = clipId
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 保存应用图标到内部存储，并返回保存路径
     */
    private fun saveAppIcon(packageName: String?, icon: Bitmap?): String? {
        if (packageName.isNullOrBlank()) {
            return null
        }

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
                "${appContext.packageName}.fileprovider",
                imageFile
            )

            fileUri.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    private fun startForeground() {
        val status = ShizukuUtils.checkStatus(appContext)
        logD(TAG) { "ensureForeground: Shizuku状态：$status" }
        val statusText = when (status) {
            is ShizukuStatus.Connected -> appContext.getString(com.cla.clip.base.general.R.string.base_general_service_is_running)
            is ShizukuStatus.Disconnect.NotInstalled -> appContext.getString(com.cla.clip.base.general.R.string.base_general_shizuku_not_install)
            is ShizukuStatus.Disconnect.ServiceNotAlive -> appContext.getString(com.cla.clip.base.general.R.string.base_general_shizuku_service_not_alive)
            is ShizukuStatus.Disconnect.VersionTooLow -> appContext.getString(com.cla.clip.base.general.R.string.base_general_shizuku_version_too_low)
            is ShizukuStatus.Disconnect.NotGranted -> appContext.getString(com.cla.clip.base.general.R.string.base_general_shizuku_not_granted)
        }

        notificationHelper.startForeground(
            this,
            appContext.getString(com.cla.clip.base.general.R.string.base_general_app_name),
            statusText
        )
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }
}