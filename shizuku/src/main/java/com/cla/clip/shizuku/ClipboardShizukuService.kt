package com.cla.clip.shizuku

import android.app.AppOpsManager
import android.app.AppOpsManagerHidden
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.Keep
import com.cla.clip.base.general.utils.exceptionHandler
import com.cla.clip.base.general.utils.iconBitmap
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logW
import com.cla.clip.base.general.utils.toStableHash
import com.cla.clip.base.general.utils.toByteArray
import dev.rikka.tools.refine.Refine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 运行在 Shizuku 进程中的剪贴板 AppOps 桥接服务。
 *
 * 它通过隐藏 API 监听其他应用写剪贴板事件，采集来源应用信息后回调主进程；必要时会用 shell 命令预拉起主进程前台服务，
 * 以绕过部分系统对后台启动服务的限制。
 */
class ClipboardShizukuService @Keep constructor(private val context: Context) : IClipboardShizukuService.Stub() {

    companion object {
        /** Shizuku 服务日志标签，用于排查隐藏 API、回调重连和 shell 启动命令。 */
        const val TAG = "ClipboardShizukuService"

        /** Provider 通道实验开关；false 时恢复旧 AIDL callback 路径。 */
        private const val USE_PROVIDER_BRIDGE = true

        /** Provider 图标写入和提交命令超时时间，避免 Shizuku 图标任务长期挂起。 */
        private const val PROVIDER_ICON_COMMAND_TIMEOUT_MS = 3_000L
    }

    /** AppOpsManager 隐藏 API 入口，用于监听剪贴板写入 op 和授予悬浮窗模式。 */
    private val appOpsManager by lazy { context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager }

    /** Shizuku 进程使用的 PackageManager，用来解析来源应用名称和图标。 */
    private val packageManager by lazy { context.packageManager }

    /** 当前应用包名，既用于过滤自身事件，也用于 shell 命令启动主进程服务。 */
    private val packageName by lazy { context.packageName }

    /** Shizuku 进程内协程作用域，使用 SupervisorJob 避免单次回调失败终止整个监听服务。 */
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    /** 主进程注册的 AIDL 回调；为空表示主进程尚未连接或已经死亡。 */
    private var callFlow = MutableStateFlow<ShizukuCallback?>(null)

    /** 监听启动状态，防止重复注册 AppOps listener。 */
    private var isRunning = AtomicBoolean(false)

    /** 当前注册到 AppOps 的监听器实例，destroy 或重新 start 前必须移除。 */
    private var opNotedListener: AppOpsManagerHidden.OnOpNotedListener? = null

    /** 最近一次剪贴板事件处理任务，用于防抖；新事件到来会取消旧任务。 */
    private var job: Job? = null

    /** 正在执行的图标同步 key 集合，避免同一来源图标重复上传。 */
    private val inflightIconSyncKeys = ConcurrentHashMap.newKeySet<String>()

    /** AIDL 健康检查入口；主进程用它确认 Shizuku 进程是否仍持有 callback。 */
    override fun isAlive(): Boolean {
        return callFlow.value != null
    }

    /** 主动退出 Shizuku 服务，通常用于用户关闭或重连前清理旧进程。 */
    override fun exit() {
        logD(TAG) { "exit" }
        destroy()
    }

    /** 销毁监听并杀死当前 Shizuku 进程，避免旧进程继续监听剪贴板事件。 */
    override fun destroy() {
        logD(TAG) { "destroy" }
        isRunning.set(false)
        callFlow.update { null }
        removeListener()

        // 这里可能是应用被卸载了，在debug时杀死自己的进程
        val pid = android.os.Process.myPid()
        logD(TAG) { "停止监听剪贴板事件，杀死进程 pid=$pid" }
        android.os.Process.killProcess(pid)
    }

    /**
     * 启动剪贴板 AppOps 监听。
     *
     * 会先确保悬浮窗权限被授予，再注册隐藏 API 监听器；Android P+ 需要先添加 HiddenApiBypass 豁免。
     */
    override fun start() {
        logD(TAG) { "start" }
        if (isRunning.get()) {
            logD(TAG) { "Service already running, skip" }
            return
        }
        isRunning.set(true)

        removeListener()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/app")
        }

        // 先去授予悬浮窗权限，之后添加监听，否则剪贴板回调之后，发现还没有悬浮窗权限，就没办法读取剪贴板数据
        // 开启悬浮窗权限
        Refine.unsafeCast<AppOpsManagerHidden>(appOpsManager)
            .setMode(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                packageManager.getPackageUid(packageName, 0),
                packageName,
                AppOpsManager.MODE_ALLOWED
            )

        // DO NOT convert it to lambda due to R8 will break it down
        opNotedListener = ClipboardListener(packageName, this)

        // 监听剪贴板事件
        Refine.unsafeCast<AppOpsManagerHidden>(appOpsManager).startWatchingNoted(intArrayOf(30), opNotedListener)
    }

    /** 注册主进程回调；主进程重连时会覆盖旧 callback，死亡时由发送失败路径清空。 */
    override fun setCallback(shizukuCallback: ShizukuCallback?) {
        logD(TAG) { "setCallback : 设置callback shizukuCallback=$shizukuCallback" }
        callFlow.update { shizukuCallback }
    }

    /**
     * 处理剪贴板写入事件。
     *
     * 先确保主进程具备悬浮窗权限，再防抖 100ms 后解析来源应用名、图标和图标哈希，最后分叉为“快速读取剪贴板”和“独立图标同步预判”两条协程。
     */
    fun handleOpNoted(clipPackageName: String?) {
        // 开启悬浮窗权限
        Refine.unsafeCast<AppOpsManagerHidden>(appOpsManager)
            .setMode(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                packageManager.getPackageUid(packageName, 0),
                packageName,
                AppOpsManager.MODE_ALLOWED
            )

        job?.cancel()
        job = serviceScope.launch {
            delay(100) // 防抖
            val packageInfo = clipPackageName?.let { packageManager.getPackageInfo(it, 0) }
            val name = packageInfo?.applicationInfo?.loadLabel(packageManager)?.toString().takeUnless { it.isNullOrBlank() } ?: "Unknown"
            // 获取图标 Drawable
            // Android 的 Bitmap 类实现了 Parcelable，并且针对 Binder 传输做了特殊优化（会将大图片数据放在 Ashmem 匿名共享内存中，而不是 Binder 缓冲区，只传递文件描述符）

            val bitmap = packageInfo?.applicationInfo?.loadIcon(packageManager).iconBitmap()
            val iconHash = bitmap?.toStableHash()

            logD(TAG) { "OnOpNotedListener packageName=${clipPackageName} name=$name bitmap=${bitmap?.width} x ${bitmap?.height} iconHash=$iconHash" }
            insert(clipPackageName, name, bitmap, iconHash)
        }
    }

    /** 移除当前 AppOps 监听器；隐藏 API 失败只记录日志，避免 destroy 流程被中断。 */
    private fun removeListener() {
        opNotedListener?.let { listener ->
            runCatching {
                Refine.unsafeCast<AppOpsManagerHidden>(appOpsManager).stopWatchingNoted(listener)
            }.getOrElse {
                logE(TAG, it) { "停止监听剪贴板事件失败" }
            }
        }
        opNotedListener = null
    }

    /**
     * 将来源应用信息投递给主进程。
     *
     * 先尝试已有 callback；失败后分别尝试前台服务和普通服务唤醒主进程并等待 callback 重连。
     * 所有等待都有超时，避免 Shizuku 进程因主进程不可用而永久挂起。
     */
    private suspend fun insert(clipPackageName: String?, appName: String, bitmap: Bitmap?, iconHash: String?) {
        if (USE_PROVIDER_BRIDGE) {
            val providerOk = sendByContentProvider(clipPackageName, appName, bitmap, iconHash)
            logD(TAG) { "Provider 通道发送完成 ok=$providerOk packageName=$clipPackageName appName=$appName iconHash=$iconHash" }
            return
        }

        // android.app.ForegroundServiceStartNotAllowedException: startForegroundService() not allowed due to mAllowStartForeground false: service com.cla.clip.master/.service.ClipboardService
        // 在aidl中去启动前台服务被拒绝了，所以在这里先用命令启动一次前台服务
        val shellOk = startForegroundService()
        logD(TAG) { "先启动一次前台服务: $shellOk" }

        // 1) fast path: 先试一次
        val cb = callFlow.value
        if (cb != null) {
            try {
                cb.onOpNoted(clipPackageName, appName, bitmap, iconHash)
                logD(TAG) { "第一次发送剪贴板信息 成功" }
                return
            } catch (e: android.os.DeadObjectException) {
                callFlow.update { current -> if (current === cb) null else current }
                logE(TAG) { "第一次发送失败 DeadObjectException" }
            } catch (e: android.os.RemoteException) {
                callFlow.update { current -> if (current === cb) null else current }
                logE(TAG) { "第一次发送失败 RemoteException" }
            } catch (tr: Throwable) {
                logE(TAG, tr) { "第一次发送失败" }
            }
        }

        // 2) 发送失败，可能是 callback 进程被系统杀死了，尝试启动前台服务唤醒它（部分 Android 12+ 设备可能对 start-foreground-service 有额外限制，但对 startservice 没有）
        val okCmd = startForegroundService()
        logD(TAG) { "callBack已经失活，尝试启动前台服务 okCmd=${okCmd}" }
        if (okCmd) {
            // 3) 等待 callback 重连（务必加超时，防止永久挂起）
            val rebound = withTimeoutOrNull(2_500) {
                callFlow.filterNotNull().first()
            }

            logD(TAG) { "等待前台服务重连，结果 rebound=${rebound != null}" }
            if (rebound != null) {
                currentCoroutineContext().ensureActive()
                // 4) 再投递一次
                try {
                    rebound.onOpNoted(clipPackageName, appName, bitmap, iconHash)
                    logD(TAG) { "第二次发送剪贴板信息 成功" }
                    return
                } catch (e: android.os.DeadObjectException) {
                    callFlow.update { current -> if (current === rebound) null else current }
                    logE(TAG, e) { "重连后回调仍断开" }
                } catch (e: android.os.RemoteException) {
                    callFlow.update { current -> if (current === rebound) null else current }
                    logE(TAG, e) { "重连后回调失败" }
                } catch (tr: Throwable) {
                    logE(TAG, tr) { "重连后回调失败" }
                }
            }
        }

        logE(TAG) { "callBack已经失活，前台服务启动失败或者启动超时，启动普通服务" }
        // 5) 兼容方案：启动普通服务（部分 Android 12+ 设备可能对 start-foreground-service 有额外限制，但对 startservice 没有）
        val okCompat = startService()
        logD(TAG) { "启动普通服务 okCompat=${okCompat}" }

        val rebound = withTimeoutOrNull(5_000) {
            callFlow.filterNotNull().first()
        }

        logD(TAG) { "等待普通服务重连，结果 rebound=${rebound != null}" }
        if (rebound != null) {
            currentCoroutineContext().ensureActive()
            try {
                rebound.onOpNoted(clipPackageName, appName, bitmap, iconHash)
                logD(TAG) { "第三次发送剪贴板信息 成功" }
            } catch (e: android.os.DeadObjectException) {
                callFlow.update { current -> if (current === rebound) null else current }
                logE(TAG, e) { "兼容方案重连后回调仍断开" }
            } catch (e: android.os.RemoteException) {
                callFlow.update { current -> if (current === rebound) null else current }
                logE(TAG, e) { "兼容方案重连后回调失败" }
            }
        }
    }

    /**
     * 通过主进程 ContentProvider 发送来源信息并触发剪贴板读取。
     *
     * Provider 模式下不自动 fallback AIDL，避免掩盖本次冷启动验证通道的真实可靠性。
     */
    private suspend fun sendByContentProvider(
        clipPackageName: String?,
        appName: String,
        bitmap: Bitmap?,
        iconHash: String?,
    ): Boolean {
        /** 本次来源事件的追踪 ID；读剪贴链路和图标链路共享同一个 eventId 便于日志串联。 */
        val eventId = UUID.randomUUID().toString()

        /** read_clip 读取协程；只负责尽快触发主进程冷启动和剪贴板入库。 */
        val readJob = serviceScope.async {
            val callResult = callProviderReadClip(
                eventId = eventId,
                clipPackageName = clipPackageName,
                appName = appName,
                iconHash = iconHash
            )
            val providerSaved = ClipboardBridgeCommandResultParser.isReadClipSuccessful(callResult.exitCode, callResult.output)
            if (providerSaved) {
                logD(TAG) {
                    "Provider 通道保存成功 eventId=$eventId packageName=$clipPackageName appName=$appName hasIcon=${bitmap != null}"
                }
            } else {
                logW(TAG) {
                    "Provider 通道未确认成功 eventId=$eventId packageName=$clipPackageName appName=$appName " +
                        "exit=${callResult.exitCode} resultCode=${ClipboardBridgeCommandResultParser.parseResultCode(callResult.output)}"
                }
            }
            providerSaved
        }

        /** 图标链路协程；独立预判当前来源图标是否需要同步，不等待 read_clip 完成。 */
        launchProviderIconQuery(eventId, clipPackageName, appName, bitmap, iconHash)

        /** 返回值仍以 read_clip 是否最终成功入库为准。 */
        return readJob.await()
    }

    /**
     * 启动 Provider 图标独立预判任务。
     *
     * @param eventId 当前剪贴事件 ID。
     * @param clipPackageName 来源应用包名。
     * @param appName 来源应用名称。
     * @param bitmap Shizuku 侧解析出的来源图标。
     * @param iconHash 来源图标 Bitmap.toStableHash()。
     */
    private fun launchProviderIconQuery(
        eventId: String,
        clipPackageName: String?,
        appName: String?,
        bitmap: Bitmap?,
        iconHash: String?,
    ) {
        if (clipPackageName.isNullOrBlank() || bitmap == null || iconHash.isNullOrBlank()) {
            logD(TAG) { "Provider 图标预判跳过 eventId=$eventId packageName=$clipPackageName reasonCode=missing_icon_args" }
            return
        }

        /** 同一来源图标的内存去重 key，避免多个 AppOps 回调重复上传同一份图标。 */
        val iconSyncKey = buildIconSyncKey(clipPackageName, iconHash)
        if (!inflightIconSyncKeys.add(iconSyncKey)) {
            logD(TAG) { "Provider 图标预判跳过重复任务 eventId=$eventId iconSyncKey=$iconSyncKey" }
            return
        }

        serviceScope.launch {
            try {
                runProviderIconQuery(eventId, clipPackageName, appName, bitmap, iconHash, iconSyncKey)
            } finally {
                inflightIconSyncKeys.remove(iconSyncKey)
            }
        }
    }

    /**
     * 执行 Provider 图标独立预判与补图链路。
     *
     * @param eventId 当前剪贴事件 ID。
     * @param clipPackageName 来源应用包名。
     * @param appName 来源应用名称。
     * @param bitmap Shizuku 侧解析出的来源图标。
     * @param iconHash 来源图标 Bitmap.toStableHash()。
     */
    private suspend fun runProviderIconQuery(
        eventId: String,
        clipPackageName: String,
        appName: String?,
        bitmap: Bitmap,
        iconHash: String,
        iconSyncKey: String,
    ) {
        /** query_icon_state 命令结果；只在 Provider 明确要求同步时才继续上传 PNG。 */
        val queryResult = callProviderQueryIconState(eventId, clipPackageName, appName, iconHash)
        val queryOk = ClipboardBridgeCommandResultParser.isQueryIconStateSuccessful(queryResult.exitCode, queryResult.output)
        if (!queryOk) {
            logW(TAG) {
                "Provider 图标预判失败 eventId=$eventId iconSyncKey=$iconSyncKey exit=${queryResult.exitCode} " +
                    "resultCode=${ClipboardBridgeCommandResultParser.parseResultCode(queryResult.output)} " +
                    "reasonCode=${ClipboardBridgeCommandResultParser.parseIconDecisionReason(queryResult.output)}"
            }
            return
        }

        /** Provider 返回的图标同步布尔决策。 */
        val shouldSyncIcon = ClipboardBridgeCommandResultParser.parseShouldSyncIcon(queryResult.output)
        /** Provider 返回的图标同步原因。 */
        val reasonCode = ClipboardBridgeCommandResultParser.parseIconDecisionReason(queryResult.output)
        if (shouldSyncIcon != true) {
            logD(TAG) {
                "Provider 图标预判命中无需同步 eventId=$eventId iconSyncKey=$iconSyncKey reasonCode=$reasonCode"
            }
            return
        }

        /** content write 写图标是否成功；失败时等待下一次事件自然重试。 */
        val iconWriteOk = writeIconToProvider(eventId, bitmap)
        if (!iconWriteOk) {
            logW(TAG) { "Provider 图标写入失败 eventId=$eventId iconSyncKey=$iconSyncKey reasonCode=$reasonCode" }
            return
        }

        /** Provider commit_icon 命令结果；只有 ok + saved/reused 才算图标补齐成功。 */
        val commitResult = callProviderCommitIcon(eventId, clipPackageName, appName, iconHash)
        val commitOk = ClipboardBridgeCommandResultParser.isCommitIconSuccessful(commitResult.exitCode, commitResult.output)
        if (commitOk) {
            logD(TAG) { "Provider 图标补全成功 eventId=$eventId iconSyncKey=$iconSyncKey iconHash=$iconHash" }
        } else {
            logW(TAG) {
                "Provider 图标补全失败 eventId=$eventId iconSyncKey=$iconSyncKey exit=${commitResult.exitCode} " +
                    "resultCode=${ClipboardBridgeCommandResultParser.parseResultCode(commitResult.output)} " +
                    "iconStatus=${ClipboardBridgeCommandResultParser.parseIconStatus(commitResult.output)}"
            }
        }
    }

    /**
     * 调用 Provider 预判当前来源图标是否需要同步。
     *
     * @param eventId 当前剪贴事件 ID。
     * @param clipPackageName 来源应用包名。
     * @param appName 来源应用名称。
     * @param iconHash 来源图标 Bitmap.toStableHash()。
     */
    private fun callProviderQueryIconState(
        eventId: String,
        clipPackageName: String?,
        appName: String?,
        iconHash: String?,
    ): ProviderCommandResult {
        /** content call 命令参数，只传递图标预判所需的小字段。 */
        val args = mutableListOf(
            "content",
            "call",
            "--uri",
            ClipboardBridgeContract.callUri(packageName),
            "--method",
            ClipboardBridgeContract.METHOD_QUERY_ICON_STATE,
            "--extra",
            "${ClipboardBridgeContract.EXTRA_EVENT_ID}:s:${eventId}"
        )

        clipPackageName?.takeIf { it.isNotBlank() }?.let { sourcePackage ->
            args += "--extra"
            args += "${ClipboardBridgeContract.EXTRA_PACKAGE_NAME}:s:${escapeContentArg(sourcePackage)}"
        }
        appName?.takeIf { it.isNotBlank() }?.let { sourceAppName ->
            args += "--extra"
            args += "${ClipboardBridgeContract.EXTRA_APP_NAME}:s:${escapeContentArg(sourceAppName)}"
        }
        iconHash?.takeIf { it.isNotBlank() }?.let { sourceIconHash ->
            args += "--extra"
            args += "${ClipboardBridgeContract.EXTRA_ICON_HASH}:s:${escapeContentArg(sourceIconHash)}"
        }

        /** content call 命令进程；错误流合并后统一解析输出。 */
        val process = ProcessBuilder(args).redirectErrorStream(true).start()

        /** content call 命令退出码。 */
        val exitCode = process.waitFor()

        /** content call 命令输出，包含 Provider 返回的 Bundle 文本。 */
        val output = process.inputStream.bufferedReader().use { it.readText() }
        logD(TAG) { "Provider query_icon_state eventId=$eventId exit=$exitCode output=$output" }
        return ProviderCommandResult(exitCode = exitCode, output = output)
    }

    /**
     * 构造来源图标同步的内存去重 key。
     *
     * @param packageName 来源应用包名。
     * @param iconHash 来源图标 hash。
     */
    private fun buildIconSyncKey(packageName: String, iconHash: String): String {
        return "$packageName#$iconHash"
    }

    /**
     * 将来源应用图标通过 `content write` 写入主进程 Provider。
     *
     * @param eventId 当前剪贴事件 ID。
     * @param bitmap Shizuku 侧 PackageManager 解析得到的小尺寸图标。
     */
    private fun writeIconToProvider(eventId: String, bitmap: Bitmap): Boolean {
        /** 图标 PNG 字节；当前图标已缩小到小尺寸，适合通过 stdin 流式写入。 */
        val iconBytes = bitmap.toByteArray()

        /** content write 命令进程；stdin 用于传递 PNG，避免 Base64 放入命令参数。 */
        val process = ProcessBuilder(
            "content",
            "write",
            "--uri",
            ClipboardBridgeContract.iconUri(packageName, eventId)
        ).redirectErrorStream(true).start()

        runCatching {
            process.outputStream.use { outputStream: OutputStream ->
                outputStream.write(iconBytes)
            }
        }.onFailure { throwable ->
            logE(TAG, throwable) { "Provider 图标写入 stdin 失败 eventId=$eventId size=${iconBytes.size}" }
            process.destroy()
            return false
        }

        /** content write 命令退出码；超时会销毁进程并按失败处理。 */
        val exitCode = waitForProcess(process, PROVIDER_ICON_COMMAND_TIMEOUT_MS) ?: run {
            logW(TAG) { "Provider 图标写入超时 eventId=$eventId size=${iconBytes.size}" }
            destroyTimedOutProcess(process)
            return false
        }

        /** content write 命令输出；只记录长度和错误摘要，不包含图标内容。 */
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val ok = exitCode == 0 && !output.contains("Error", ignoreCase = true)
        logD(TAG) { "Provider 图标写入 eventId=$eventId size=${iconBytes.size} exit=$exitCode ok=$ok output=$output" }
        return ok
    }

    /**
     * 调用 Provider 读取剪贴板并保存。
     *
     * @param eventId 当前剪贴事件 ID。
     * @param clipPackageName 来源应用包名。
     * @param appName 来源应用名称。
     * @param iconHash 来源图标 hash。
     */
    private fun callProviderReadClip(
        eventId: String,
        clipPackageName: String?,
        appName: String?,
        iconHash: String?,
    ): ProviderCommandResult {
        /** content call 命令参数，只传递命令行安全的小字段。 */
        val args = mutableListOf(
            "content",
            "call",
            "--uri",
            ClipboardBridgeContract.callUri(packageName),
            "--method",
            ClipboardBridgeContract.METHOD_READ_CLIP,
            "--extra",
            "${ClipboardBridgeContract.EXTRA_EVENT_ID}:s:${eventId}"
        )

        clipPackageName?.takeIf { it.isNotBlank() }?.let { sourcePackage ->
            args += "--extra"
            args += "${ClipboardBridgeContract.EXTRA_PACKAGE_NAME}:s:${escapeContentArg(sourcePackage)}"
        }
        appName?.takeIf { it.isNotBlank() }?.let { sourceAppName ->
            args += "--extra"
            args += "${ClipboardBridgeContract.EXTRA_APP_NAME}:s:${escapeContentArg(sourceAppName)}"
        }
        iconHash?.takeIf { it.isNotBlank() }?.let { sourceIconHash ->
            args += "--extra"
            args += "${ClipboardBridgeContract.EXTRA_ICON_HASH}:s:${escapeContentArg(sourceIconHash)}"
        }

        /** content call 命令进程；错误流合并后统一解析输出。 */
        val process = ProcessBuilder(args).redirectErrorStream(true).start()

        /** content call 命令退出码。 */
        val exitCode = process.waitFor()

        /** content call 命令输出，包含 Provider 返回的 Bundle 文本。 */
        val output = process.inputStream.bufferedReader().use { it.readText() }
        logD(TAG) { "Provider read_clip eventId=$eventId exit=$exitCode output=$output" }
        return ProviderCommandResult(exitCode = exitCode, output = output)
    }

    /**
     * 调用 Provider 提交已异步写入的图标。
     *
     * @param eventId 当前剪贴事件 ID。
     * @param clipPackageName 来源应用包名。
     * @param appName 来源应用名称。
     * @param iconHash 来源图标 Bitmap.toStableHash()。
     */
    private fun callProviderCommitIcon(
        eventId: String,
        clipPackageName: String?,
        appName: String?,
        iconHash: String?,
    ): ProviderCommandResult {
        /** content call 命令参数，只传递提交图标所需的小字段。 */
        val args = mutableListOf(
            "content",
            "call",
            "--uri",
            ClipboardBridgeContract.callUri(packageName),
            "--method",
            ClipboardBridgeContract.METHOD_COMMIT_ICON,
            "--extra",
            "${ClipboardBridgeContract.EXTRA_EVENT_ID}:s:${eventId}"
        )

        clipPackageName?.takeIf { it.isNotBlank() }?.let { sourcePackage ->
            args += "--extra"
            args += "${ClipboardBridgeContract.EXTRA_PACKAGE_NAME}:s:${escapeContentArg(sourcePackage)}"
        }
        appName?.takeIf { it.isNotBlank() }?.let { sourceAppName ->
            args += "--extra"
            args += "${ClipboardBridgeContract.EXTRA_APP_NAME}:s:${escapeContentArg(sourceAppName)}"
        }
        iconHash?.takeIf { it.isNotBlank() }?.let { sourceIconHash ->
            args += "--extra"
            args += "${ClipboardBridgeContract.EXTRA_ICON_HASH}:s:${escapeContentArg(sourceIconHash)}"
        }

        /** content call 命令进程；错误流合并后统一解析输出。 */
        val process = ProcessBuilder(args).redirectErrorStream(true).start()

        /** content call 命令退出码；超时会销毁进程并按失败处理。 */
        val exitCode = waitForProcess(process, PROVIDER_ICON_COMMAND_TIMEOUT_MS) ?: run {
            destroyTimedOutProcess(process)
            logW(TAG) { "Provider commit_icon 超时 eventId=$eventId packageName=$clipPackageName" }
            return ProviderCommandResult(exitCode = -1, output = "timeout")
        }

        /** content call 命令输出，包含 Provider 返回的 Bundle 文本。 */
        val output = process.inputStream.bufferedReader().use { it.readText() }
        logD(TAG) { "Provider commit_icon eventId=$eventId exit=$exitCode output=$output" }
        return ProviderCommandResult(exitCode = exitCode, output = output)
    }

    /**
     * 等待 shell 命令结束并处理超时。
     *
     * @param process 正在执行的 shell 命令进程。
     * @param timeoutMillis 最长等待时间，单位毫秒。
     */
    private fun waitForProcess(process: Process, timeoutMillis: Long): Int? {
        return runBlocking {
            withTimeoutOrNull(timeoutMillis) {
                process.waitFor()
            }
        }
    }

    /**
     * 销毁已经超时的 shell 命令进程。
     *
     * @param process 已经超过等待时间的 content/am 命令进程；Android 8 以下没有 destroyForcibly，只能退回 destroy。
     */
    private fun destroyTimedOutProcess(process: Process) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process.destroyForcibly()
        } else {
            process.destroy()
        }
    }

    /**
     * 转义 Android `content` 命令 extra 参数中的冒号和反斜杠。
     *
     * `content` 命令使用 `key:type:value` 格式，未转义冒号会破坏参数解析。
     */
    private fun escapeContentArg(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace(":", "\\:")
    }

    /**
     * 通过 shell 命令启动主进程前台服务。
     *
     * Shizuku 进程不直接调用 Context.startForegroundService，避免后台启动限制；返回 false 表示命令失败或输出包含 Error。
     */
    private fun startForegroundService(): Boolean {
        val process = ProcessBuilder(
            "am",
            "start-foreground-service",
//            "--user", "0", // 这个参数在某些设备上可能会导致权限问题，暂时先不加了，等后续有需要再说
            "-n", "${packageName}/.service.ClipboardService"
        ).redirectErrorStream(true).start()

        val exitCode = process.waitFor()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        logD(TAG) { "start-foreground-service  exit=$exitCode  output=$output" }

        return (exitCode == 0) && !output.contains("Error:", ignoreCase = true)
    }

    /**
     * 通过 shell 命令启动主进程普通服务。
     *
     * 作为前台服务启动失败后的兼容方案；Android 8+ 或定制 ROM 仍可能因后台限制拒绝。
     */
    private fun startService(): Boolean {
        // 命令执行失败，可能是 Android 12+ 的限制导致的，尝试使用 startservice 作为兼容方案
        val process = ProcessBuilder(
            "am",
            "startservice",
//            "--user", "0", // 这个参数在某些设备上可能会导致权限问题，暂时先不加了，等后续有需要再说
            "-n", "${packageName}/.service.ClipboardService"
        ).redirectErrorStream(true).start()

        val exitCode = process.waitFor()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        logD(TAG) { "startservice  exit=$exitCode  output=$output" }

        //startservice exit=255 output=Starting service: Intent { cmp=com.cla.clip.master/.service.ClipboardService }
        //Error: app is in background uid null

        //Error: app is in background uid null 的意思是：系统判定目标应用当前在后台，不允许用 startservice 启动普通 Service。
        //这是 Android 8.0+ 的后台启动限制（你这里是从 Shizuku/shell 侧触发，更容易被 ROM 策略拦截）。

        return (exitCode == 0) && !output.contains("Error:", ignoreCase = true)
    }
}

/**
 * Shizuku 执行 Provider 命令后的结果。
 *
 * @param exitCode 命令进程退出码。
 * @param output 标准输出和错误输出合并后的文本。
 */
private data class ProviderCommandResult(
    val exitCode: Int,
    val output: String,
)
