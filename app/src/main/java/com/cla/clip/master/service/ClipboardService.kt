package com.cla.clip.master.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import com.cla.clip.base.general.R
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.base.general.utils.ApplicationScope
import com.cla.clip.base.general.utils.hasOverlayPermission
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.toColorString
import com.cla.clip.base.general.utils.toast
import com.cla.clip.master.utils.ClipHelper
import com.cla.clip.master.utils.NotificationHelper
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

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
    lateinit var clipRepository: Lazy<ClipRepository>

    @Inject
    @ApplicationScope
    lateinit var scope: Lazy<CoroutineScope>

    @Inject
    @ApplicationContext
    lateinit var appContext: Lazy<Context>

    @Inject
    lateinit var notificationHelper: Lazy<NotificationHelper>

    @Inject
    lateinit var clipHelper: Lazy<ClipHelper>

    private val windowManager by lazy { getSystemService(WindowManager::class.java) as WindowManager }

    private val activeTasks = AtomicInteger(0)

    @Volatile
    private var latestStartId: Int = 0

    @Volatile
    private var pendingNoPayloadStopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // 服务创建时，立即尝试提升为前台服务
        logI(TAG) { "onCreate: " }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        // 关键：每次调用 startForegroundService 后，必须再次调用 startForeground，
        // 否则在 API 26+ 设备上可能会因为“未能在规定时间内进入前台”而崩溃。
        startForeground()

        // 从shizuku进程拉起service时，没有传递数据，这个时候readClip没有值，不要去读剪贴板数据
        val readClip = intent?.getBooleanExtra(READ_CLIP_KEY, false) ?: false
        val sourcePackageName = intent?.getStringExtra(PACKAGE_NAME_KEY)
        val sourceAppName = intent?.getStringExtra(APP_NAME_KEY)
        val sourceAppIconPath = intent?.getStringExtra(APP_ICON_PATH_KEY)
        val sourceAppIconColor = intent?.getIntExtra(ICON_COLOR_KEY, -1)
        val sourceAppIconHash = intent?.getStringExtra(ICON_HASH_KEY)

        if (intent != null && readClip) {
            // 收到真正任务后，取消无数据拉起的延迟关闭逻辑
            pendingNoPayloadStopJob?.cancel()
            pendingNoPayloadStopJob = null

            activeTasks.incrementAndGet()

            logD(TAG) {
                """
            onStartCommand: 
            startId=$startId
            activeTasks=${activeTasks.get()}
            sourcePackageName=$sourcePackageName
            sourceAppName=$sourceAppName
            sourceAppIconPath=$sourceAppIconPath
            sourceAppIconHash=$sourceAppIconHash
            sourceAppIconColor=$sourceAppIconColor / ${sourceAppIconColor?.toColorString()}
        """.trimIndent()
            }

            magic(sourcePackageName, sourceAppName, sourceAppIconPath, sourceAppIconColor, sourceAppIconHash)
        } else {
            logD(TAG) { "onStartCommand : 延迟一点时间去结束服务" }
            // 这里应该是shizuku进程拉起的服务，没有传递数据过来，不需要读剪贴板
            // shizuku进程用aidl联系ShizukuConnector把数据传递过来，但是不能在ShizukuConnector直接启动前台服务
            // 所以会在shizuku进程中通过命令拉起服务，这时不能立即停止服务，否则shizuku进程传递数据过来时服务已经被杀死了，无法处理数据
            scheduleNoPayloadStop()
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

    private fun magic(
        packageName: String?,
        appName: String?,
        iconPath: String?,
        iconColor: Int?,
        iconHash: String?,
    ) {
        // 检查当前有没有悬浮窗权限
        if (!appContext.get().hasOverlayPermission()) {
            scope.get().launch { appContext.get().toast(R.string.base_general_without_the_floating_window_permission) }
            logE(TAG) { "没有悬浮窗权限，无法读取剪贴板内容" }
            killSelf(decrement = true)
            return
        }

        var view: View? = null
        runCatching {
            // 通过添加一个不可见的 View 来触发系统读取剪贴板内容，从而获取最新的剪贴板数据
            // todo 状态栏展开时不可用，不能解决，可以在我的页面给用户提示
            view = View(appContext.get())
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
            val clipData = clipHelper.get().clipboardManager.primaryClip
            val clip = clipData?.let { runCatching { it.getItemAt(0) }.getOrNull() }

            windowManager.removeView(view)

            if (clip != null) {
                scope.get().launch(Dispatchers.IO) {
                    clipHelper.get().processClip(clip, packageName, appName, iconPath, iconColor, iconHash)
                }
            }
        }.onFailure {
            logE(TAG, it) { "读取剪贴板内容出错" }
            // 确保移除 view，避免泄漏
            runCatching {
                view?.let { windowManager.removeView(it) }
            }.onFailure { tr ->
                logE(TAG, tr) { "移除悬浮View出错" }
            }
        }

        killSelf(decrement = true)
    }

    private fun killSelf(decrement: Boolean) {
        val left = if (decrement) activeTasks.decrementAndGet() else activeTasks.get()
        logI(TAG) { "停止服务 : left=$left" }
        if (left == 0) {
            // 没有任务了，才降级并尝试停服务
            stopForeground(STOP_FOREGROUND_REMOVE)
            // 用最新的 startId 来停，避免旧请求误停
            stopSelfResult(latestStartId)
        }
    }

    private fun scheduleNoPayloadStop() {
        pendingNoPayloadStopJob?.cancel()
        pendingNoPayloadStopJob = scope.get().launch(Dispatchers.Main) {
            delay(3000)
            logD(TAG) { "scheduleNoPayloadStop : 延迟时间到，准备去停止服务" }
            killSelf(decrement = false)
        }
    }

    private fun startForeground() {
        notificationHelper.get().readClipForeground(this)
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }
}
