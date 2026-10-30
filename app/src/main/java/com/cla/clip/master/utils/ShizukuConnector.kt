package com.cla.clip.master.utils

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.cla.clip.base.general.R
import com.cla.clip.base.general.config.installation.InstallationIdentityRepository
import com.cla.clip.base.general.utils.ApplicationScope
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import com.cla.clip.master.BuildConfig
import com.cla.clip.master.onboarding.FirstLaunchInitializer
import com.cla.clip.shizuku.ClipboardShizukuService
import com.cla.clip.shizuku.IClipboardShizukuService
import com.cla.clip.shizuku.ShizukuCallback
import com.cla.clip.shizuku.ShizukuProcessName
import com.cla.clip.shizuku.ShizukuProcessNames
import com.cla.clip.shizuku.ShizukuUtils
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * 连接 Shizuku UserService。
 *
 * adb shell ps | grep com.cla.clip.master
 * adb shell ps | findstr "com.cla.clip.master"
 */
@Singleton
class ShizukuConnector @Inject internal constructor(
    /** 应用级协程作用域；用于异步发起 bind，避免阻塞 UI。 */
    @param:ApplicationScope private val scope: CoroutineScope,
    /** 应用 Context；用于 Shizuku 状态检查和通知文案。 */
    @param:ApplicationContext private val appContext: Context,
    /** Shizuku 状态通知协作者；懒加载避免连接器初始化时过早创建通知依赖。 */
    private val notificationHelper: Lazy<NotificationHelper>,
    /** 宿主安装身份仓库；应用协程先等 ID 和期望进程名持久化，再执行绑定。 */
    private val identityRepository: InstallationIdentityRepository,
    /** 首次选择之前禁止任何自动重连入口绑定后台服务；不保存第二套后台开关。 */
    private val firstLaunchInitializer: FirstLaunchInitializer,
) {

    companion object {
        /** 连接器日志标签。 */
        private const val TAG = "ShizukuConnector"

        /**
         * Shizuku UserService 行为版本；停用传图后升级，确保覆盖安装不复用仍生成图标的旧进程。
         *
         * 这个版本号不跟随 app 版本，只在跨进程协议或服务行为需要强制替换时递增。
         */
        // 覆盖安装后重建旧传图服务，使来源图标生成与上传实际停止。
        const val VERSION = 29

        /** UserService bind 等待超时；只用于连接器内部确认本次主动 bind 是否有回调。 */
        private const val USER_SERVICE_BIND_TIMEOUT_MS = 3_000L

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

        /**
         * 构造当前 UserService tag。
         *
         * Shizuku SDK 本地连接缓存按 tag 复用，tag 必须包含协议版本，避免升级后继续拿到旧 AIDL binder。
         */
        internal fun buildUserServiceTag(installId: String, version: Int): String {
            return "${installId}_v$version"
        }
    }

    /** 当前已缓存的 UserService 参数；进程名刷新后会重建，避免 lazy 持有旧 suffix。 */
    private var cachedUserServiceArgs: Shizuku.UserServiceArgs? = null

    /** 最近一次 UserService 参数对应的进程名；用于判断缓存是否仍可复用。 */
    private var cachedUserServiceProcessName: String? = null

    /** 当前可用的 Shizuku AIDL 服务；连接断开时必须清空，避免继续调用旧 binder。 */
    private var shizukuService: IClipboardShizukuService? = null

    /** 最近一次确认连接成功的完整 Shizuku 进程名；用于避免旧 binder 活着时误跳过新进程绑定。 */
    private var boundProcessName: String? = null

    /** 最近一次发起绑定请求的完整 Shizuku 进程名；用于日志区分连接中和已连接状态。 */
    private var bindingProcessName: String? = null

    /** 当前正在等待 onServiceConnected 的 bind 信号；只承接连接器内部主动 bind 的一次结果。 */
    private var pendingUserServiceBind: CompletableDeferred<IClipboardShizukuService?>? = null

    /** 同一次连接使用的已提交身份和进程名，防止构造 SDK 参数时重复读取或生成身份。 */
    private data class PreparedProcess(
        /** DataStore 已提交的安装 ID，仅用于 SDK tag，不进入日志。 */
        val installId: String,
        /** 与安装 ID 对应、已经提交的 Shizuku 进程名集合。 */
        val names: ShizukuProcessNames,
    )

    /** Shizuku UserService 连接回调；负责启动监听并注册无业务副作用 ping callback。 */
    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            logI(TAG) { "userServiceConnection : 已经连接 pingBinder=${binder?.pingBinder()}" }
            if (binder != null && binder.pingBinder()) {
                notificationHelper.get().cancelShizukuStatus()
                /** 新回调拿到的 AIDL 代理；版本通过 UserService tag 和 process suffix 隔离。 */
                val service = IClipboardShizukuService.Stub.asInterface(binder)
                /** 本次绑定预期进程名；SDK 回调不携带远端真实进程名，只能使用本次请求上下文记录。 */
                val connectedProcessName = bindingProcessName ?: cachedUserServiceProcessName
                shizukuService = service
                boundProcessName = connectedProcessName
                bindingProcessName = null
                runCatching {
                    service.start()
                    service.setCallback(object : ShizukuCallback.Stub() {
                        /** Shizuku 进程探测 app 主进程是否仍可达；该方法必须无业务副作用。 */
                        override fun pingAppProcess(): Boolean {
                            return true
                        }
                    })
                }.onSuccess {
                    pendingUserServiceBind?.complete(service)
                }.getOrElse { error ->
                    shizukuService = null
                    boundProcessName = null
                    pendingUserServiceBind?.complete(null)
                    logE(TAG, error) { "userServiceConnection: 启动或设置 callback 失败" }
                }
            } else {
                /** 当前 Shizuku 状态；用于通知用户连接失败原因。 */
                val status = ShizukuUtils.checkStatus(appContext)
                logE(TAG) { "userServiceConnection: 绑定服务出错 status=$status" }
                notificationHelper.get().notifyShizukuStatus(status, appContext.getString(R.string.base_general_shizuku_not_connect))
                clearCurrentService()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            /** 当前 Shizuku 状态；用于通知用户断开原因。 */
            val status = ShizukuUtils.checkStatus(appContext)
            logE(TAG) { "onServiceDisconnected: 断开连接 status=$status" }
            notificationHelper.get().notifyShizukuStatus(status, appContext.getString(R.string.base_general_shizuku_not_connect))
            clearCurrentService()
        }
    }

    /** Shizuku binder 首次可用监听；用于刷新状态通知。 */
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        /** 当前 Shizuku 状态；收到 binder 后通常可以消除未连接提示。 */
        val status = ShizukuUtils.checkStatus(appContext)
        logD(TAG) { "binderReceivedListener: shizuku状态=$status" }
        notificationHelper.get().notifyShizukuStatus(status)
    }

    /** Shizuku binder 死亡监听；用于刷新状态通知。 */
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        /** 当前 Shizuku 状态；死亡后给用户同步状态。 */
        val status = ShizukuUtils.checkStatus(appContext)
        logD(TAG) { "binderDeadListener: shizuku状态=$status" }
        notificationHelper.get().notifyShizukuStatus(status)
    }

    /** 连接服务时使用的互斥锁，避免短时间内重复绑定服务。 */
    private val connectMutex = Mutex()

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    /**
     * 在应用协程中准备当前期望的 Shizuku 进程名。
     *
     * 该方法必须早于延迟和任何连接 early return 调用；Provider 查询独立等待同一仓库提交。
     */
    private suspend fun prepareExpectedShizukuProcess(): PreparedProcess {
        /** DataStore 事务完成后的安装级 ID；缺失时仓库原子生成且并发只保留一份。 */
        val installId = identityRepository.getOrCreateInstallId()
        /** 同源构造出的 Shizuku 进程名集合。 */
        val names = ShizukuProcessName.buildNames(
            applicationId = BuildConfig.APPLICATION_ID,
            version = VERSION,
            installId = installId
        )
        identityRepository.setExpectedProcessName(names.fullName)
        return PreparedProcess(installId = installId, names = names)
    }

    /**
     * 请求连接最新 Shizuku 服务。
     *
     * Provider 身份查询会调用该方法触发异步修复；返回值只表示是否已排队，不等待身份读取或真实 bind 完成。
     */
    fun requestConnect(reasonCode: String): ShizukuConnectRequestResult {
        connect(reasonCode = reasonCode)
        return ShizukuConnectRequestResult(
            requested = true,
            expectedProcessName = "",
            reasonCode = reasonCode
        )
    }

    /**
     * 连接当前期望的 Shizuku 服务。
     *
     * @param delayTime 延迟连接时间；应用协程会先完成身份提交，再执行延迟和绑定。
     */
    fun connect(delayTime: Long = 0) {
        connect(delayTime = delayTime, reasonCode = "manual_connect")
    }

    /**
     * 在应用协程中先提交身份，再按期望进程名连接 Shizuku 服务。
     *
     * @param delayTime 延迟连接时间，用于页面恢复等场景的轻量防抖。
     * @param reasonCode 触发连接的原因，用于诊断是否由 Provider 身份查询频繁触发。
     */
    private fun connect(
        delayTime: Long = 0,
        reasonCode: String,
    ) {
        scope.launch {
            connectMutex.withLock {
                /** 已提交的安装身份和进程名；存储错误不得按默认身份继续绑定。 */
                val preparedProcess = try {
                    // 统一覆盖启动、首页及 Provider 修复入口；稍后选择完成后仍需真实 Shizuku 授权。
                    if (!firstLaunchInitializer.initialize().firstLaunchCompleted) return@withLock
                    prepareExpectedShizukuProcess()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    logE(TAG) { "连接前准备安装身份失败 reasonCode=$reasonCode exceptionType=${exception::class.java.simpleName}" }
                    return@withLock
                }
                if (delayTime > 0) {
                    delay(delayTime)
                }
                bindUserServiceLocked(preparedProcess = preparedProcess, reasonCode = reasonCode)
                Unit
            }
        }
    }

    /**
     * 在持有连接锁时绑定最新 Shizuku UserService。
     *
     * @param preparedProcess 已提交的安装身份与当前期望进程名。
     * @param reasonCode 触发绑定的低敏原因码。
     */
    private suspend fun bindUserServiceLocked(
        preparedProcess: PreparedProcess,
        reasonCode: String,
    ): IClipboardShizukuService? {
        /** 本次已提交的完整进程名；所有早退和 SDK 参数都使用同一份身份。 */
        val expectedNames = preparedProcess.names
        if (!ShizukuUtils.isConnected(appContext)) {
            logW(TAG) {
                "connect: shizuku未连接，不需要在这里就绑定shizuku服务 reasonCode=$reasonCode"
            }
            return null
        }

        /** 当前 binder 是否仍能响应；异常时按断开处理，继续尝试绑定最新进程。 */
        val isAlive = runCatching { shizukuService?.isAlive }.getOrElse { error ->
            logE(TAG, error) { "connect: 连接已经断开" }
            null
        }

        logI(TAG) {
            "connect: isAlive=$isAlive reasonCode=$reasonCode boundSame=${boundProcessName == expectedNames.fullName} " +
                "bindingSame=${bindingProcessName == expectedNames.fullName}"
        }
        if (shouldSkipBind(isAlive = isAlive, boundProcessName = boundProcessName, expectedProcessName = expectedNames.fullName)) {
            logD(TAG) { "connect: shizuku服务连接中，不需要重复绑定 reasonCode=alive_same_process" }
            return shizukuService
        }

        /** 本次 bind 的完成信号；onServiceConnected/onServiceDisconnected 会完成它。 */
        val bindSignal = CompletableDeferred<IClipboardShizukuService?>()
        pendingUserServiceBind = bindSignal
        return runCatching {
            bindingProcessName = expectedNames.fullName
            logI(TAG) { "connect: 去绑定shizuku进程 reasonCode=$reasonCode" }
            Shizuku.bindUserService(userServiceArgs(preparedProcess), userServiceConnection)
            /** 等待主线程 ServiceConnection 回调；超时后返回 null，下一次连接入口可继续重试。 */
            val connectedService = withTimeoutOrNull(USER_SERVICE_BIND_TIMEOUT_MS) {
                bindSignal.await()
            }
            if (connectedService == null) {
                logW(TAG) { "connect: bind 等待超时 reasonCode=$reasonCode" }
            }
            connectedService
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            bindingProcessName = null
            logE(TAG, error) { "connect: shizuku远程服务连接失败" }
            null
        }.also {
            if (pendingUserServiceBind === bindSignal) {
                pendingUserServiceBind = null
            }
        }
    }

    /**
     * 获取当前或新建 UserService 参数。
     *
     * @param preparedProcess 已提交身份和期望进程名；参数缓存必须和它完全一致。
     */
    private fun userServiceArgs(preparedProcess: PreparedProcess): Shizuku.UserServiceArgs {
        /** 本次期望进程名；只使用应用协程已提交的身份快照。 */
        val expectedNames = preparedProcess.names
        /** 已缓存且仍匹配当前进程名的参数，可直接复用避免重复构造。 */
        val cachedArgs = cachedUserServiceArgs
        if (cachedArgs != null && cachedUserServiceProcessName == expectedNames.fullName) {
            return cachedArgs
        }

        logD(TAG) {
            "args init: debug=${BuildConfig.DEBUG} version_code=$VERSION"
        }
        /** 当前 UserService tag；带协议版本，避免 Shizuku SDK 按旧安装级 tag 复用旧 connection。 */
        val serviceTag = buildUserServiceTag(installId = preparedProcess.installId, version = VERSION)
        /** 最新 UserService 参数；processNameSuffix 使用后缀，tag 保持安装级身份。 */
        val newArgs = Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ClipboardShizukuService::class.java.name))
            .daemon(true)
            .processNameSuffix(expectedNames.suffix)
            .debuggable(BuildConfig.DEBUG)
            .version(VERSION)
            .tag(serviceTag)
        cachedUserServiceArgs = newArgs
        cachedUserServiceProcessName = expectedNames.fullName
        return newArgs
    }

    /** 清空当前服务代理和等待信号，并 best-effort 解除旧 callback。 */
    private fun clearCurrentService() {
        /** 断开前的服务代理；清空字段后再尝试 best-effort 解除 callback。 */
        val previousService = shizukuService
        shizukuService = null
        boundProcessName = null
        bindingProcessName = null
        pendingUserServiceBind?.complete(null)
        pendingUserServiceBind = null
        runCatching { previousService?.setCallback(null) }.getOrElse { error ->
            logE(TAG, error) { "callback 置空出错" }
        }
    }
}

/**
 * Shizuku 连接请求结果。
 *
 * @property requested 是否已向应用协程提交连接请求。
 * @property expectedProcessName 已准备好的完整进程名；请求刚排队时为空，Provider 自身从已提交查询结果返回进程名。
 * @property reasonCode 请求原因，用于 Provider 身份查询日志。
 */
data class ShizukuConnectRequestResult(
    val requested: Boolean,
    val expectedProcessName: String,
    val reasonCode: String,
)
