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
import com.cla.clip.shizuku.ShizukuProcessName
import com.cla.clip.shizuku.ShizukuProcessNames
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

/**
 * 连接shizuku进程
 * adb shell ps | grep com.cla.clip.master
 * adb shell ps | findstr "com.cla.clip.master"
 */
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
        const val VERSION = 17

        /**
         * 判断当前连接是否可以跳过重新 bind。
         *
         * @param isAlive 当前 binder 是否仍能响应 isAlive。
         * @param boundProcessName 当前 binder 最近确认绑定的完整进程名。
         * @param expectedProcessName app 当前期望的最新完整进程名。
         */
        internal fun shouldSkipBind(
            isAlive: Boolean?,
            boundProcessName: String?,
            expectedProcessName: String,
        ): Boolean {
            return isAlive == true && boundProcessName == expectedProcessName
        }
    }

    /** 当前已缓存的 UserService 参数；进程名刷新后会重建，避免 lazy 持有旧 suffix。 */
    private var cachedUserServiceArgs: Shizuku.UserServiceArgs? = null

    /** 最近一次 UserService 参数对应的进程名；用于判断缓存是否仍可复用。 */
    private var cachedUserServiceProcessName: String? = null

    private var shizukuService: IClipboardShizukuService? = null

    /** 最近一次确认连接成功的完整 Shizuku 进程名；用于避免旧 binder 活着时误跳过新进程绑定。 */
    private var boundProcessName: String? = null

    /** 最近一次发起绑定请求的完整 Shizuku 进程名；用于日志区分连接中和已连接状态。 */
    private var bindingProcessName: String? = null

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            logI(TAG) { "userServiceConnection : 已经连接 pingBinder=${binder?.pingBinder()}" }
            if (binder != null && binder.pingBinder()) {
                notificationHelper.get().cancelShizukuStatus()
                val service = IClipboardShizukuService.Stub.asInterface(binder)
                shizukuService = service
                boundProcessName = bindingProcessName ?: refreshExpectedShizukuProcessName().fullName
                bindingProcessName = null
                service.start()
                service.setCallback(object : ShizukuCallback.Stub() {
                    /** Shizuku 进程探测 app 主进程是否仍可达；该方法必须无业务副作用，避免探活触发入库或服务启动。 */
                    override fun pingAppProcess(): Boolean {
                        return true
                    }

                    /** 接收旧 AIDL 链路投递的剪贴来源信息；Provider 直读模式不应通过该方法保存同一条剪贴内容。 */
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
                bindingProcessName = null

                runCatching { shizukuService?.setCallback(null) }.getOrElse {
                    logE(TAG, it) { "callback 置空出错 1" }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val status = ShizukuUtils.checkStatus(appContext)
            logE(TAG) { "onServiceDisconnected: 断开连接 status=$status" }
            notificationHelper.get().notifyShizukuStatus(status, appContext.getString(R.string.base_general_shizuku_not_connect))
            boundProcessName = null
            bindingProcessName = null

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

    /**
     * 刷新当前期望的 Shizuku 进程名。
     *
     * 该方法必须早于任何连接 early return 调用，确保旧 Shizuku 进程通过 Provider 查询时总能拿到最新期望值。
     */
    fun refreshExpectedShizukuProcessName(): ShizukuProcessNames {
        /** 当前安装级 ID；开发阶段无效旧值会由 AppSetting 直接重建为纯数字。 */
        val installId = AppSetting.pid
        /** 同源构造出的 Shizuku 进程名集合。 */
        val names = ShizukuProcessName.buildNames(
            applicationId = BuildConfig.APPLICATION_ID,
            version = VERSION,
            installId = installId
        )
        AppSetting.shizukuSuffix = names.fullName
        return names
    }

    /**
     * 请求连接最新 Shizuku 服务。
     *
     * Provider 身份查询会调用该方法触发异步修复；返回值只表示是否已提交连接请求，不等待真实 bind 完成。
     */
    fun requestConnect(reasonCode: String): ShizukuConnectRequestResult {
        /** 最新期望进程名；必须在任何 early return 之前刷新。 */
        val expectedNames = refreshExpectedShizukuProcessName()
        connect(expectedNames = expectedNames, reasonCode = reasonCode)
        return ShizukuConnectRequestResult(
            requested = true,
            expectedProcessName = expectedNames.fullName,
            reasonCode = reasonCode
        )
    }

    /**
     * 获取当前或新建 UserService 参数。
     *
     * @param expectedNames 当前期望进程名集合；参数缓存必须和它完全一致。
     */
    private fun userServiceArgs(expectedNames: ShizukuProcessNames): Shizuku.UserServiceArgs {
        /** 已缓存且仍匹配当前进程名的参数，可直接复用避免重复构造。 */
        val cachedArgs = cachedUserServiceArgs
        if (cachedArgs != null && cachedUserServiceProcessName == expectedNames.fullName) {
            return cachedArgs
        }

        logD(TAG) {
            "args init: debug=${BuildConfig.DEBUG} version_code=$VERSION pid=${AppSetting.pid} " +
                "processName=${expectedNames.fullName}"
        }
        /** 最新 UserService 参数；processNameSuffix 使用后缀，tag 保持安装级 pid。 */
        val newArgs = Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ClipboardShizukuService::class.java.name))
            .daemon(true) // 守护进程，确保服务在后台持续运行
            .processNameSuffix(expectedNames.suffix)
            .debuggable(BuildConfig.DEBUG)
            .version(VERSION)
            .tag(AppSetting.pid) // tag 和 version 决定是否需要替换 Shizuku 进程，并且退出旧进程
        cachedUserServiceArgs = newArgs
        cachedUserServiceProcessName = expectedNames.fullName
        return newArgs
    }

    /**
     * 连接当前期望的 Shizuku 服务。
     *
     * @param delayTime 延迟连接时间；即使延迟执行，也必须在入口同步刷新完整进程名。
     */
    fun connect(delayTime: Long = 0) {
        /** 最新期望进程名；必须在 delay、状态判断和 early return 前刷新。 */
        val expectedNames = refreshExpectedShizukuProcessName()
        connect(expectedNames = expectedNames, delayTime = delayTime, reasonCode = "manual_connect")
    }

    /**
     * 按指定期望进程名异步连接 Shizuku 服务。
     *
     * @param expectedNames 当前期望的 Shizuku 进程名集合。
     * @param delayTime 延迟连接时间，用于页面恢复等场景的轻量防抖。
     * @param reasonCode 触发连接的原因，用于诊断是否由 Provider 身份查询频繁触发。
     */
    private fun connect(
        expectedNames: ShizukuProcessNames,
        delayTime: Long = 0,
        reasonCode: String,
    ) {
        scope.launch {
            connectMutex.withLock {
                if (delayTime > 0) {
                    delay(delayTime)
                }

                if (!ShizukuUtils.isConnected(appContext)) {
                    logW(TAG) {
                        "connect: shizuku未连接，不需要在这里就绑定shizuku服务 reasonCode=$reasonCode " +
                            "processName=${expectedNames.fullName}"
                    }
                    return@launch
                }

                /** 当前 binder 是否仍能响应；异常时按断开处理，继续尝试绑定最新进程。 */
                val isAlive = runCatching { shizukuService?.isAlive }.getOrElse {
                    logE(TAG, it) { "connect: 连接已经断开" }
                    null
                }

                logI(TAG) {
                    "connect: isAlive=$isAlive reasonCode=$reasonCode boundProcessName=$boundProcessName " +
                        "bindingProcessName=$bindingProcessName expectedProcessName=${expectedNames.fullName}"
                }
                if (shouldSkipBind(isAlive = isAlive, boundProcessName = boundProcessName, expectedProcessName = expectedNames.fullName)) {
                    logD(TAG) { "connect: shizuku服务连接中，不需要重复绑定 reasonCode=alive_same_process" }
                    return@launch
                }

                runCatching {
                    bindingProcessName = expectedNames.fullName
                    logI(TAG) { "connect: 去绑定shizuku进程 reasonCode=$reasonCode processName=${expectedNames.fullName}" }
                    // 这里不能用peekUserService，它会导致，即使userServiceArgs已经设置了不同的版本号，但不会创建新的shizuku进程，导致绑定的服务一直是旧的服务，无法更新到新的版本
                    Shizuku.bindUserService(userServiceArgs(expectedNames), userServiceConnection)
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
                    bindingProcessName = null
                    logE(TAG, it) { "connect: shizuku远程服务连接失败" }
                }
            }
        }
    }
}

/**
 * Shizuku 连接请求结果。
 *
 * @property requested 是否已向应用协程提交连接请求。
 * @property expectedProcessName 本次请求对应的完整 Shizuku 进程名。
 * @property reasonCode 请求原因，用于 Provider 身份查询日志。
 */
data class ShizukuConnectRequestResult(
    val requested: Boolean,
    val expectedProcessName: String,
    val reasonCode: String,
)
