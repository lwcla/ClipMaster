package com.cla.clip.master.utils

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import com.cla.clip.base.general.repository.ClipDao
import com.cla.clip.base.general.utils.ApplicationScope
import com.cla.clip.base.general.utils.extractUsableColor
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/** 连接shizuku进程 */
@Singleton
class ShizukuConnector @Inject constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:ApplicationContext private val appContext: Context,
    private val clipDao: Lazy<ClipDao>,
    private val notificationHelper: Lazy<NotificationHelper>,
) {

    companion object {
        private const val TAG = "ShizukuConnector"
    }

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

                            scope.launch(Dispatchers.IO) {
                                val sourceAppData = packageName?.let { clipDao.get().loadSourceApp(it) }
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
                }
            } else {
                runCatching { shizukuService?.setCallback(null) }.getOrElse {
                    logE(TAG, it) { "callback 置空出错 1" }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val status = ShizukuUtils.checkStatus(appContext)
            logE(TAG) { "userServiceConnection: 断开连接 status=$status" }
            notificationHelper.get().notifyShizukuStatus(status)

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

    fun connect() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)

        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        runCatching {
            val result = Shizuku.peekUserService(userServiceArgs, userServiceConnection)
            if (result == -1) {
                logI(TAG) { "去绑定 shizuku 远程服务" }
                Shizuku.bindUserService(userServiceArgs, userServiceConnection)
            } else {
                logI(TAG) { "连接 shizuku 远程服务成功" }
            }
        }
    }
}