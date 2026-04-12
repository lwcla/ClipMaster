package com.cla.clip.master.service

import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import com.cla.clip.base.general.utils.hasOverlayPermission
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.toColorString
import com.cla.clip.base.general.utils.toast
import com.cla.clip.master.utils.LinkMeta
import com.cla.clip.master.utils.LinkMetaParser
import com.cla.clip.master.utils.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 剪贴板监听服务
 * Process: com.cla.clip.master, PID: 17785 (Fix with AI)
 *          java.lang.RuntimeException: Unable to create service com.cla.clip.master.service.ClipboardService: android.app.ForegroundServiceStartNotAllowedException: Time limit already exhausted for foreground service type dataSync
 *          	at android.app.ActivityThread.handleCreateService(ActivityThread.java:5774)
 *          	at android.app.ActivityThread.-$$Nest$mhandleCreateService(Unknown Source:0)
 *          	at android.app.ActivityThread$H.handleMessage(ActivityThread.java:2859)
 *          	at android.os.Handler.dispatchMessage(Handler.java:114)
 *          	at android.os.Looper.loopOnce(Looper.java:267)
 *          	at android.os.Looper.loop(Looper.java:360)
 *          	at android.app.ActivityThread.main(ActivityThread.java:10054)
 *          	at java.lang.reflect.Method.invoke(Native Method)
 *          	at com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:616)
 *          	at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:1115)
 *          Caused by: android.app.ForegroundServiceStartNotAllowedException: Time limit already exhausted for foreground service type dataSync
 *          	at android.app.ForegroundServiceStartNotAllowedException$1.createFromParcel(ForegroundServiceStartNotAllowedException.java:54)
 *          	at android.app.ForegroundServiceStartNotAllowedException$1.createFromParcel(ForegroundServiceStartNotAllowedException.java:50)
 *          	at android.os.Parcel.readParcelableInternal(Parcel.java:5236)
 *          	at android.os.Parcel.readParcelable(Parcel.java:5218)
 *          	at android.os.Parcel.createExceptionOrNull(Parcel.java:3356)
 *          	at android.os.Parcel.createException(Parcel.java:3345)
 *          	at android.os.Parcel.readException(Parcel.java:3328)
 *          	at android.os.Parcel.readException(Parcel.java:3270)
 *          	at android.app.IActivityManager$Stub$Proxy.setServiceForeground(IActivityManager.java:7704)
 *          	at android.app.Service.startForeground(Service.java:776)
 *          	at com.cla.clip.master.service.ClipboardService.g(r8-map-id-6409f4ceac89fcc67f67cd9b9509ef8bf36a79e71fb1bb316ff30535e75becf9:268)
 *          	at com.cla.clip.master.service.ClipboardService.onCreate(r8-map-id-6409f4ceac89fcc67f67cd9b9509ef8bf36a79e71fb1bb316ff30535e75becf9:24)
 *          	at android.app.ActivityThread.handleCreateService(ActivityThread.java:5761)
 *          	... 9 more
 *
 * ForegroundServiceStartNotAllowedException: Time limit already exhausted for foreground service type dataSync
 * 意思是：
 * 你在启动 ClipboardService 时调用了 startForeground(...)，但系统判断你这个前台服务类型 dataSync 的可用时长已经用完了，所以直接抛异常并让服务创建失败。
 * 从你项目看，ClipboardService 在 AndroidManifest.xml 里声明了：
 * app/src/main/AndroidManifest.xml 第 53 行：android:foregroundServiceType="dataSync"
 * 并且在 ClipboardService 里会多次调用 startForeground()（onCreate、onStartCommand、连接回调等）。核心问题不是“调用次数”，而是这个服务本身是长期驻留监听场景，和 dataSync 类型不匹配，容易把该类型时间预算耗尽。
 *
 * 不能长期保持一个前台服务存活，应该在任务结束之后 stopForeground/stopSelf，避免长期占用预算。
 *
 */
@AndroidEntryPoint
class ClipboardService : Service() {

    companion object {
        private const val TAG = "ClipboardService"

        private const val PACKAGE_NAME_KEY = "package_name_key"
        private const val APP_NAME_KEY = "app_name_key"
        private const val APP_ICON_PATH_KEY = "app_icon_path_key"
        private const val ICON_COLOR_KEY = "icon_color_key"
        private const val ICON_HASH_KEY = "icon_hash_key"

        private const val READ_CLIP_KEY = "read_clip_key"

        fun start(
            context: Context,
            packageName: String?,
            appName: String?,
            iconPath: String?,
            iconColor: Int?,
            iconHash: String?,
        ) {
            logI(TAG) { "start" }
            val serviceIntent = Intent(context, ClipboardService::class.java)

            serviceIntent.putExtra(PACKAGE_NAME_KEY, packageName)
            serviceIntent.putExtra(APP_NAME_KEY, appName)
            serviceIntent.putExtra(APP_ICON_PATH_KEY, iconPath)
            serviceIntent.putExtra(ICON_COLOR_KEY, iconColor)
            serviceIntent.putExtra(ICON_HASH_KEY, iconHash)
            serviceIntent.putExtra(READ_CLIP_KEY, true)

            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // 尝试启动前台服务，如果失败，就启动普通服务
                    // 这里只是为了传递数据给service,shizuku进程会在传数据之前先用命令启动前台服务
                    context.startForegroundService(serviceIntent)
                    return
                }
            }.getOrElse {
                logE(TAG, it) { "start: 启动前台服务失败，器启动普通服务" }
            }


            runCatching {
                context.startService(serviceIntent)
            }.getOrElse {
                logE(TAG, it) { "start: 普通服务启动失败" }
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

    private var killJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // 服务创建时，立即尝试提升为前台服务
        logI(TAG) { "onCreate: " }
        notificationHelper.createChannels()
        startForeground(
            title = appContext.getString(R.string.base_general_app_name),
            content = appContext.getString(R.string.base_general_the_clipboard_is_being_read),
            clipId = null
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 关键：每次调用 startForegroundService 后，必须再次调用 startForeground，
        // 否则在 API 26+ 设备上可能会因为“未能在规定时间内进入前台”而崩溃。

        // 从shizuku进程拉起service时，没有传递数据，这个时候readClip没有值，不要去读剪贴板数据
        val readClip = intent?.getBooleanExtra(READ_CLIP_KEY, false) ?: false
        val sourcePackageName = intent?.getStringExtra(PACKAGE_NAME_KEY)
        val sourceAppName = intent?.getStringExtra(APP_NAME_KEY)
        val sourceAppIconPath = intent?.getStringExtra(APP_ICON_PATH_KEY)
        val sourceAppIconColor = intent?.getIntExtra(ICON_COLOR_KEY, -1)
        val sourceAppIconHash = intent?.getStringExtra(ICON_HASH_KEY)

        if (intent != null && readClip) {
            val killComplete = killJob?.isCompleted
            killJob?.cancel()

            startForeground(
                title = appContext.getString(R.string.base_general_app_name),
                content = appContext.getString(R.string.base_general_the_clipboard_is_being_read),
                clipId = null
            )

            logD(TAG) {
                """
            onStartCommand: 
            killComplete=$killComplete
            sourcePackageName=$sourcePackageName
            sourceAppName=$sourceAppName
            sourceAppIconPath=$sourceAppIconPath
            sourceAppIconHash=$sourceAppIconHash
            sourceAppIconColor=$sourceAppIconColor-${sourceAppIconColor?.toColorString()}
        """.trimIndent()
            }

            scope.launch(Dispatchers.Main) {
                magic(sourcePackageName, sourceAppName, sourceAppIconPath, sourceAppIconColor, sourceAppIconHash)
            }
        } else {
            killSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        logI(TAG) { "onDestroy : " }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        logI(TAG) { "onTaskRemoved: " }
        // 不要在这里做清除监听的操作，因为从任务栈移除时，进程并不一定被杀死，前台服务可能还在运行中
        //removeListener()
        super.onTaskRemoved(rootIntent)
    }

    private suspend fun magic(
        packageName: String?,
        appName: String?,
        iconPath: String?,
        iconColor: Int?,
        iconHash: String?,
    ) = withContext(Dispatchers.Main.immediate) {
        // 检查当前有没有悬浮窗权限
        if (!appContext.hasOverlayPermission()) {
            appContext.toast(R.string.base_general_without_the_floating_window_permission)
            logE(TAG) { "没有悬浮窗权限，无法读取剪贴板内容" }
            return@withContext
        }

        var view: View? = null
        runCatching {
            // 通过添加一个不可见的 View 来触发系统读取剪贴板内容，从而获取最新的剪贴板数据
            // todo 状态栏展开时不可用，不能解决，可以在我的页面给用户提示
            view = View(appContext)
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

            // 读取剪贴板内容
            val clipData = clipboardManager.primaryClip
            val clip = clipData?.let { runCatching { it.getItemAt(0) }.getOrNull() }

            windowManager.removeView(view)

            if (clip != null) {
                withContext(Dispatchers.IO) {
                    processClip(clip, packageName, appName, iconPath, iconColor, iconHash)
                }
            }
        }.getOrElse {
            logE(TAG, it) { "读取剪贴板内容出错" }
            // 确保移除 view，避免泄漏
            runCatching {
                view?.let { windowManager.removeView(it) }
            }.getOrElse { tr ->
                logE(TAG, tr) { "移除悬浮View出错" }
            }
        }

        killSelf()
    }

    /** 处理新的剪贴板内容 */
    private suspend fun processClip(
        item: android.content.ClipData.Item,
        packageName: String?,
        appName: String?,
        iconPath: String?,
        iconColor: Int?,
        iconHash: String?,
    ) {
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
            sourceAppIconPath = iconPath,
            sourcePrimaryColor = iconColor?.takeIf { it > 0 },
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

        startForeground(
            title = "$appName ${appContext.getString(R.string.base_general_it_was_written_into_the_clipboard)}",
            content = "${appContext.getString(R.string.base_general_content)}${clipContent}",
            clipId = clipId
        )
    }

    private fun killSelf() {
        killJob?.cancel()
        killJob = scope.launch(Dispatchers.Main) {
            delay(5000)
            logI(TAG) { "processClip: 关闭前台服务" }
            stopForeground(STOP_FOREGROUND_DETACH) // 前台服务降级为普通服务，但通知依旧保留
            stopSelf()
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

    private fun startForeground(title: String, content: String, clipId: Long?) {
        notificationHelper.readClipForeground(this, title, content, clipId)
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }
}