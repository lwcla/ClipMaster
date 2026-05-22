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
        /** 剪贴板服务日志标签，用于排查跨进程启动、悬浮窗读取和前台服务生命周期。 */
        private const val TAG = "ClipboardService"

        /** Intent extra：来源应用包名，来自 Shizuku AppOps 回调。 */
        private const val PACKAGE_NAME_KEY = "package_name_key"
        /** Intent extra：来源应用名，已在 Shizuku 进程通过 PackageManager 解析。 */
        private const val APP_NAME_KEY = "app_name_key"
        /** Intent extra：来源应用图标缓存路径，可能为空。 */
        private const val APP_ICON_PATH_KEY = "app_icon_path_key"
        /** Intent extra：来源应用图标主色，-1 表示未知或未提取。 */
        private const val ICON_COLOR_KEY = "icon_color_key"
        /** Intent extra：来源应用图标哈希，用于避免重复保存图标。 */
        private const val ICON_HASH_KEY = "icon_hash_key"

        /** Intent extra：是否携带真实读取任务；Shizuku 仅预拉起服务时为 false。 */
        private const val READ_CLIP_KEY = "read_clip_key"

        /**
         * 启动剪贴板读取服务并传递来源应用信息。
         *
         * Android 8+ 优先用前台服务启动，失败时回退普通服务；这里的目的只是把 Shizuku 进程采集到的来源信息传回主进程。
         */
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
    /** 剪贴板数据仓库，当前服务通过 ClipHelper 间接处理数据，保留注入用于后续直接写入扩展。 */
    lateinit var clipRepository: Lazy<ClipRepository>

    @Inject
    @ApplicationScope
    /** 应用级协程作用域，用于服务内后台读取和 Toast 展示，生命周期长于单次 Service 启动。 */
    lateinit var scope: Lazy<CoroutineScope>

    @Inject
    @ApplicationContext
    /** 应用 Context，避免 Service 实例销毁后仍被后台协程错误持有。 */
    lateinit var appContext: Lazy<Context>

    @Inject
    /** 通知助手，用于把服务提升为前台服务并展示剪贴板读取通知。 */
    lateinit var notificationHelper: Lazy<NotificationHelper>

    @Inject
    /** 剪贴板读取和入库助手，负责把系统 ClipData 转换成业务记录。 */
    lateinit var clipHelper: Lazy<ClipHelper>

    /** WindowManager 用于添加 1x1 透明悬浮 View，触发系统允许当前进程读取剪贴板。 */
    private val windowManager by lazy { getSystemService(WindowManager::class.java) as WindowManager }

    /** 当前正在处理的读取任务数量，确保多个 startCommand 并发时最后一个任务结束后才停服务。 */
    private val activeTasks = AtomicInteger(0)

    @Volatile
    /** 最近一次 onStartCommand 的 startId，stopSelfResult 需要用它避免旧请求误停新任务。 */
    private var latestStartId: Int = 0

    @Volatile
    /** 无 payload 预拉起服务后的延迟停止任务，真实读取任务到达时会取消。 */
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

    /**
     * 执行一次真实剪贴板读取。
     *
     * 直接添加一个 1x1 透明 View 触发系统剪贴板读取窗口，避免 Settings.canDrawOverlays 在跨 Shizuku 场景下误判；
     * 读取成功后交给 ClipHelper 入库，添加 View 失败时按真实异常兜底提示。
     * 无论成功失败都会尝试移除 View 并减少 activeTasks，避免悬浮窗泄漏或前台服务常驻。
     */
    private fun magic(
        packageName: String?,
        appName: String?,
        iconPath: String?,
        iconColor: Int?,
        iconHash: String?,
    ) {
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
            if (it is SecurityException || it is WindowManager.BadTokenException) {
                scope.get().launch { appContext.get().toast(R.string.base_general_without_the_floating_window_permission) }
            }
            // 确保移除 view，避免泄漏
            runCatching {
                view?.let { windowManager.removeView(it) }
            }.onFailure { tr ->
                logE(TAG, tr) { "移除悬浮View出错" }
            }
        }

        killSelf(decrement = true)
    }

    /**
     * 尝试停止服务。
     *
     * decrement 为 true 表示当前真实任务结束，需要减少 activeTasks；只有剩余任务为 0 时才移除前台通知并停止最新 startId。
     */
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

    /**
     * 为无 payload 的预拉起服务安排延迟停止。
     *
     * Shizuku 进程会先用 shell 命令拉起服务，再通过 AIDL 传真实数据；立即停止可能导致后续数据投递失败，因此保留短暂等待窗口。
     */
    private fun scheduleNoPayloadStop() {
        pendingNoPayloadStopJob?.cancel()
        pendingNoPayloadStopJob = scope.get().launch(Dispatchers.Main) {
            delay(3000)
            logD(TAG) { "scheduleNoPayloadStop : 延迟时间到，准备去停止服务" }
            killSelf(decrement = false)
        }
    }

    /** 将服务提升为前台服务，满足 Android 8+ 对 startForegroundService 的时限要求。 */
    private fun startForeground() {
        notificationHelper.get().readClipForeground(this)
    }

    /** 本服务只通过 startService/startForegroundService 工作，不提供绑定接口。 */
    override fun onBind(intent: Intent): IBinder? {
        return null
    }
}
