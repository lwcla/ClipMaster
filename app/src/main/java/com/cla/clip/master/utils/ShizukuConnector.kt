package com.cla.clip.master.utils

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import com.cla.clip.base.general.R
import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.base.general.utils.ApplicationScope
import com.cla.clip.base.general.utils.extractUsableColor
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import com.cla.clip.base.general.utils.saveIcon
import com.cla.clip.master.BuildConfig
import com.cla.clip.master.service.ClipboardService
import com.cla.clip.shizuku.ClipboardShizukuService
import com.cla.clip.shizuku.IClipboardShizukuService
import com.cla.clip.shizuku.ShizukuCallback
import com.cla.clip.shizuku.ShizukuUtils
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/** 连接shizuku进程 */
@Singleton
class ShizukuConnector @Inject constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:ApplicationContext private val appContext: Context,
    private val clipRepository: Lazy<ClipRepository>,
    private val notificationHelper: Lazy<NotificationHelper>,
) {

    companion object {
        private const val TAG = "ShizukuConnector"

        /**
         * shizuku的版本号，这个不要跟app的版本
         * 否则只是更新了app，但shizuku服务没有发生变化的情况下，也会重启shizuku进程
         */
        private const val VERSION = 2
    }

    private var shizukuService: IClipboardShizukuService? = null

    private val userServiceArgs by lazy {
        logD(TAG) { "args init: debug=${BuildConfig.DEBUG} version_code=${VERSION} pid=${AppSetting.pid}" }
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ClipboardShizukuService::class.java.name))
            .daemon(true) // 守护进程，确保服务在后台持续运行
            .processNameSuffix("shizuku_${VERSION}_${AppSetting.pid}")
            .debuggable(BuildConfig.DEBUG)
            .version(VERSION)
            .tag(AppSetting.pid) // tag和version决定是否需要替换shizuku进程，并且退出旧进程
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            logI(TAG) { "userServiceConnection : 已经连接 pingBinder=${binder?.pingBinder()}" }
            if (binder != null && binder.pingBinder()) {
                notificationHelper.get().cancelShizukuStatus()
                val service = IClipboardShizukuService.Stub.asInterface(binder)
                shizukuService = service
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

                        scope.launch(Dispatchers.IO) {
                            val sourceAppData = packageName?.let { clipRepository.get().loadSourceApp(it) }
                            val appColor: Int?
                            val appIconPath: String?

                            if (sourceAppData?.iconHash == iconHash) {
                                logD(TAG) { "onOpNoted 使用数据库中的应用数据" }
                                appColor = sourceAppData?.primaryColor
                                appIconPath = sourceAppData?.iconPath
                            } else {
                                logD(TAG) { "onOpNoted 去提取应用图标的颜色和保存图标到本地" }
                                // 提取图标里的颜色后续用来做边框的颜色
                                appColor = appIcon?.extractUsableColor()
                                appIconPath = appContext.saveIcon(packageName, appIcon)
                            }

                            withContext(Dispatchers.Main) {
                                // CoroutineExceptionHandler--> Coroutine exception (Show original) (Fix with AI)
                                // android.app.ForegroundServiceStartNotAllowedException: startForegroundService() not allowed due to mAllowStartForeground false: service com.cla.clip.master/.service.ClipboardService
                                ClipboardService.start(
                                    appContext,
                                    packageName,
                                    appName,
                                    appIconPath,
                                    appColor,
                                    iconHash
                                )
                            }
                        }
                    }
                })
            } else {
                val status = ShizukuUtils.checkStatus(appContext)
                logE(TAG) { "userServiceConnection: 绑定服务出错 status=$status" }
                notificationHelper.get().notifyShizukuStatus(status, appContext.getString(R.string.base_general_shizuku_not_connect))

                runCatching { shizukuService?.setCallback(null) }.getOrElse {
                    logE(TAG, it) { "callback 置空出错 1" }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val status = ShizukuUtils.checkStatus(appContext)
            logE(TAG) { "onServiceDisconnected: 断开连接 status=$status" }
            notificationHelper.get().notifyShizukuStatus(status, appContext.getString(R.string.base_general_shizuku_not_connect))

            runCatching { shizukuService?.setCallback(null) }.getOrElse {
                logE(TAG, it) { "callback 置空出错 2" }
            }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        val status = ShizukuUtils.checkStatus(appContext)
        logD(TAG) { "binderReceivedListener: shizuku状态=$status" }
        notificationHelper.get().notifyShizukuStatus(status)
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        val status = ShizukuUtils.checkStatus(appContext)
        logD(TAG) { "binderDeadListener: shizuku状态=${status}" }

        // todo 这里还要看一下，关闭shizuku服务时，这里没有回调，也就没有发送通知
        notificationHelper.get().notifyShizukuStatus(status)
    }

    /** 连接服务时，添加一把锁，避免短时间内重复绑定服务 */
    private val connectMutex = Mutex()

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    fun connect(delayTime: Long = 0) {
        scope.launch {
            connectMutex.withLock {
                if (delayTime > 0) {
                    delay(delayTime)
                }

                if (!ShizukuUtils.isConnected(appContext)) {
                    logW(TAG) { "connect: shizuku未连接，不需要在这里就绑定shizuku服务" }
                    return@launch
                }

                val isAlive = runCatching { shizukuService?.isAlive }.getOrElse {
                    logE(TAG, it) { "connect: 连接已经断开" }
                    null
                }

                logI(TAG) { "connect: isAlive=$isAlive" }
                if (isAlive == true) {
                    logD(TAG) { "connect: shizuku服务连接中，不需要重复绑定" }
                    return@launch
                }

                runCatching {
                    logI(TAG) { "connect: 去绑定shizuku进程" }
                    // 这里不能用peekUserService，它会导致，即使userServiceArgs已经设置了不同的版本号，但不会创建新的shizuku进程，导致绑定的服务一直是旧的服务，无法更新到新的版本
                    Shizuku.bindUserService(userServiceArgs, userServiceConnection)
//                    val result = Shizuku.peekUserService(userServiceArgs, userServiceConnection)
//                    if (result == -1) {
//                        logI(TAG) { "去绑定 shizuku 远程服务" }
//                        Shizuku.bindUserService(userServiceArgs, userServiceConnection)
//                    } else {
//                        logI(TAG) { "连接 shizuku 远程服务成功" }
//                    }
                    // 加个延迟避免短时间内重复绑定服务
                    delay(3000)
                }.getOrElse {
                    logE(TAG, it) { "connect: shizuku远程服务连接失败" }
                }
            }
        }
    }
}