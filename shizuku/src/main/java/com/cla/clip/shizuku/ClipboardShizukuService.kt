package com.cla.clip.shizuku

import android.app.AppOpsManager
import android.app.AppOpsManagerHidden
import android.content.Context
import android.os.SystemClock
import androidx.annotation.Keep
import com.cla.clip.base.hidden.api.HiddenApiExemptions
import com.cla.clip.base.general.utils.exceptionHandler
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logW
import dev.rikka.tools.refine.Refine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 运行在 Shizuku 进程中的剪贴板 AppOps 桥接服务。
 *
 * 该类只负责 Binder 生命周期、AppOps 注册、剪贴事件主流程编排和关键诊断；Provider 命令、图标同步、app 唤醒和来源解析交给具名协作者。
 */
class ClipboardShizukuService @Keep constructor(private val context: Context) : IClipboardShizukuService.Stub() {

    companion object {
        /** Shizuku 服务日志标签，用于串联隐藏 API、Provider 通道、app 唤醒和身份校验日志。 */
        const val TAG = "ClipboardShizukuService"
    }

    /** AppOpsManager 隐藏 API 入口，仅用于监听剪贴板写入 op。 */
    private val appOpsManager by lazy { context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager }

    /** Shizuku 进程使用的 PackageManager，用来解析来源应用名称和图标。 */
    private val packageManager by lazy { context.packageManager }

    /** 当前应用包名，既用于过滤自身事件，也用于 Provider authority 和 app 唤醒命令。 */
    private val packageName by lazy { context.packageName }

    /** Shizuku 进程内剪贴板读取适配器，只负责 shell 身份选择和 payload 映射。 */
    private val clipboardReader = ShizukuClipboardReader()

    /** Provider 命令客户端，统一封装 content call/write、参数转义和命令超时。 */
    private val providerCommandClient by lazy { ClipboardBridgeProviderCommandClient(packageName) }

    /** Shizuku 进程身份校验协作者，用于覆盖安装后识别并退出旧进程。 */
    private val processIdentity = ShizukuProcessIdentity(providerQuery = ::callProviderQueryShizukuProcess)

    /** 来源应用解析器，解析失败时回退 Unknown/空图标，不阻断剪贴 payload。 */
    private val sourceAppResolver by lazy { ShizukuSourceAppResolver(packageManager) }

    /** app 主进程唤醒命令执行器，只负责 NoDisplay Activity 唤醒入口。 */
    private val appWakeCommandRunner by lazy { ShizukuAppWakeCommandRunner(packageName) }

    /** Shizuku 进程内协程作用域，使用 SupervisorJob 避免单次回调失败终止整个监听服务。 */
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    /** 主进程注册的 AIDL 回调；Provider 正式链路只使用它做无副作用 ping 探活。 */
    private var callFlow = MutableStateFlow<ShizukuCallback?>(null)

    /** Provider 提交前的 app 主进程探活与唤醒协作者，只负责可达性，不判断身份或写入剪贴数据。 */
    private val appProcessReadiness by lazy {
        ShizukuAppProcessReadiness(
            callbackFlow = callFlow,
            pingCallback = { callback ->
                callback.pingAppProcess()
            },
            wakeAppProcess = {
                appWakeCommandRunner.wakeAppProcess()
            },
            clockMillis = {
                SystemClock.elapsedRealtime()
            }
        )
    }

    /** Provider 图标同步协调器，图标失败不影响剪贴内容入库。 */
    private val iconSyncCoordinator by lazy {
        ClipboardBridgeIconSyncCoordinator(
            serviceScope = serviceScope,
            providerCommandClient = providerCommandClient
        )
    }

    /** 监听启动状态，防止重复注册 AppOps listener。 */
    private var isRunning = AtomicBoolean(false)

    /** 当前注册到 AppOps 的监听器实例，destroy 或重新 start 前必须移除。 */
    private var opNotedListener: AppOpsManagerHidden.OnOpNotedListener? = null

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

        /** 当前 Shizuku 进程 pid；只用于退出日志和 killProcess，不跨进程传输。 */
        val pid = android.os.Process.myPid()
        logD(TAG) { "停止监听剪贴板事件，杀死进程 pid=$pid" }
        android.os.Process.killProcess(pid)
    }

    /**
     * 启动剪贴板 AppOps 监听。
     *
     * 只负责添加隐藏 API 豁免并注册监听器；正式 Provider 直读链路不再写入或依赖悬浮窗 AppOps。
     */
    override fun start() {
        logD(TAG) { "start" }
        if (isRunning.get()) {
            logD(TAG) { "Service already running, skip" }
            return
        }
        isRunning.set(true)

        removeListener()
        HiddenApiExemptions.addIfNeeded("Landroid/app")

        // DO NOT convert it to lambda due to R8 will break it down
        opNotedListener = ClipboardListener(packageName, this)

        // 监听剪贴板事件
        Refine.unsafeCast<AppOpsManagerHidden>(appOpsManager).startWatchingNoted(intArrayOf(30), opNotedListener)
    }

    /** 注册主进程 callback；Provider 正式链路只保留 pingAppProcess 探活用途。 */
    override fun setCallback(shizukuCallback: ShizukuCallback?) {
        logD(TAG) { "setCallback : 设置callback shizukuCallback=$shizukuCallback" }
        callFlow.update { shizukuCallback }
    }

    /**
     * 处理剪贴板写入事件。
     *
     * 直接进入 Provider 直读链路；AIDL callback 只保留 `pingAppProcess()` 探活，不再承载剪贴保存回调。
     */
    fun handleOpNoted(clipPackageName: String?) {
        serviceScope.launch {
            handleProviderBridgeOpNoted(clipPackageName)
        }
    }

    /**
     * 处理 Provider 直读链路的剪贴事件。
     *
     * @param clipPackageName AppOps 回调给出的来源应用包名，可能为空。
     */
    private suspend fun handleProviderBridgeOpNoted(clipPackageName: String?) {
        /** 本次来源事件的追踪 ID；剪贴 payload 链路和图标链路共享它来串联日志。 */
        val eventId = UUID.randomUUID().toString()
        /** Shizuku 收到回调后立即记录的捕获时间，用于避免并发提交顺序影响列表排序。 */
        val capturedAtMillis = System.currentTimeMillis()
        /** 系统剪贴板快照；必须尽可能靠近回调入口读取，避免后续复制覆盖当前值。 */
        val clipData = clipboardReader.readPrimaryClip()
        /** 正式传给 Provider 的 payload；为空表示系统剪贴板读取失败、为空或不支持当前类型。 */
        val clipPayload = clipboardReader.toPayload(
            clipData = clipData,
            eventId = eventId,
            capturedAtMillis = capturedAtMillis
        )
        logShizukuClipboardReadResult(eventId, clipData, clipPayload)

        /** app 主进程探活和必要唤醒结果；必须发生在读取剪贴板快照之后，避免等待期间快照被新内容覆盖。 */
        val readinessResult = appProcessReadiness.ensureReady()
        logAppProcessReadiness(eventId, readinessResult)

        /** 进程身份判断结果；必须在剪贴板读取之后、payload 和图标写入 Provider 之前执行。 */
        val identityResult = verifyShizukuProcessIdentityAfterReadiness(eventId, readinessResult)
        /** 身份判断的最终决策；只有 Matched 才允许继续提交 payload 和图标。 */
        val identityDecision = identityResult.decision
        logShizukuProcessIdentityDecision(
            eventId = eventId,
            decision = identityDecision,
            providerQueryElapsedMs = identityResult.providerQueryElapsedMs,
            wakeFailedProviderFallback = identityResult.wakeFailedProviderFallback,
            providerIdentityAfterWake = identityResult.providerIdentityAfterWake
        )
        when (identityDecision) {
            is ShizukuProcessIdentityDecision.Matched -> Unit
            is ShizukuProcessIdentityDecision.Mismatched -> {
                logW(TAG) {
                    "Shizuku 旧进程确认退出 eventId=$eventId " +
                        "currentProcessName=${identityDecision.currentProcessName} " +
                        "expectedProcessName=${identityDecision.expectedProcessName} " +
                        "resultCode=${identityDecision.resultCode} reasonCode=${identityDecision.reasonCode} " +
                        "connectRequested=${identityDecision.connectRequested} " +
                        "connectSkipReason=${identityDecision.connectSkipReason}"
                }
                destroy()
                return
            }
            is ShizukuProcessIdentityDecision.Unknown -> {
                logW(TAG) {
                    "Shizuku 身份不确定，跳过本次提交 eventId=$eventId " +
                        "currentProcessName=${identityDecision.currentProcessName} " +
                        "expectedProcessName=${identityDecision.expectedProcessName} " +
                        "resultCode=${identityDecision.resultCode} reasonCode=${identityDecision.reasonCode} " +
                        "connectRequested=${identityDecision.connectRequested} " +
                        "connectSkipReason=${identityDecision.connectSkipReason}"
                }
                return
            }
        }

        /** 来源应用展示信息；解析失败时回退 Unknown/空图标，不能阻断剪贴 payload。 */
        val sourceAppInfo = sourceAppResolver.resolve(clipPackageName)
        logD(TAG) {
            "OnOpNotedListener eventId=$eventId packageName=${sourceAppInfo.packageName} name=${sourceAppInfo.appName} " +
                "bitmap=${sourceAppInfo.bitmap?.width} x ${sourceAppInfo.bitmap?.height} iconHash=${sourceAppInfo.iconHash}"
        }
        submitProviderBridgeEvent(sourceAppInfo, eventId, clipPayload)
    }

    /**
     * 输出 Shizuku 侧剪贴板直读结果。
     *
     * @param eventId 当前剪贴事件 ID，用于串联 Provider payload 与日志。
     * @param clipData Shizuku 进程通过系统 `IClipboard` 读到的剪贴板；日志只允许输出结构信息。
     * @param payload 即将写入 Provider 的 payload；为空表示直读失败、剪贴板为空或不支持当前类型。
     */
    private fun logShizukuClipboardReadResult(
        eventId: String,
        clipData: android.content.ClipData?,
        payload: ClipboardBridgeClipPayload?,
    ) {
        /** 首个剪贴板 item；只用于统计结构，不输出具体内容。 */
        val firstItem = clipData?.takeIf { data -> data.itemCount > 0 }?.getItemAt(0)
        /** 首个 item 的普通文本长度；为空表示没有文本或读取失败。 */
        val textLength = firstItem?.text?.length
        /** 首个 item 的 HTML 文本长度；为空表示没有 HTML 文本。 */
        val htmlLength = firstItem?.htmlText?.length
        /** 首个 item 是否携带 URI；只记录布尔值，避免泄露授权 URI。 */
        val hasUri = firstItem?.uri != null
        /** 首个 item 是否携带 Intent；只记录布尔值，避免泄露 Intent 内容。 */
        val hasIntent = firstItem?.intent != null

        logD(TAG) {
            "Shizuku 进程直读剪贴板 eventId=$eventId clipNull=${clipData == null} " +
                "payloadNull=${payload == null} itemCount=${clipData?.itemCount ?: 0} mimeTypes=${payload?.mimeTypes.orEmpty()} " +
                "textLength=$textLength htmlLength=$htmlLength hasUri=$hasUri hasIntent=$hasIntent"
        }
    }

    /**
     * 输出 Provider 提交前 app 主进程探活与唤醒结果。
     *
     * @param eventId 当前剪贴事件 ID。
     * @param result app 主进程 callback 探活和唤醒策略结果。
     */
    private fun logAppProcessReadiness(
        eventId: String,
        result: ShizukuAppProcessReadinessResult,
    ) {
        logD(TAG) {
            "Shizuku app 进程探活 eventId=$eventId " +
                "appPingResult=${result.appPingResult} appWakeRequested=${result.appWakeRequested} " +
                "appWakeMode=${result.appWakeMode} appWakeResult=${result.appWakeResult} callbackRebound=${result.callbackRebound} " +
                "wakeCooldownSkipped=${result.wakeCooldownSkipped} appWakeElapsedMs=${result.appWakeElapsedMs} " +
                "readyForProviderQuery=${result.readyForProviderQuery} reasonCode=${result.reasonCode}"
        }
    }

    /**
     * 在 app 进程探活后执行 Shizuku 身份查询，并处理 Provider 缺失竞态兜底。
     *
     * @param eventId 当前剪贴事件 ID。
     * @param readinessResult 身份查询前的 app 进程探活结果。
     */
    private suspend fun verifyShizukuProcessIdentityAfterReadiness(
        eventId: String,
        readinessResult: ShizukuAppProcessReadinessResult,
    ): ShizukuProcessIdentityVerification {
        /** 首次身份查询结果和耗时；无论探活是否成功都执行，作为 Provider 兼容兜底。 */
        var queryWithElapsed = queryShizukuProcessWithElapsed(eventId)
        /** 本次身份查询是否已经在 Provider 缺失后补做过唤醒。 */
        var providerIdentityAfterWake = readinessResult.appWakeRequested
        /** 唤醒失败或 cooldown 后仍尝试 Provider 查询时记录 fallback 标记。 */
        val wakeFailedProviderFallback = !readinessResult.readyForProviderQuery
        /** 当前 Provider authority，用于识别本应用 Provider 冷启动缺失。 */
        val providerAuthority = ClipboardBridgeContract.authority(packageName)

        if (
            readinessResult.canRetryWakeAfterProviderMissing &&
            ClipboardBridgeCommandResultParser.isProviderMissingForColdStart(queryWithElapsed.result.output, providerAuthority)
        ) {
            /** Provider 查询发现 app 可能在 ping 后又被杀，补做一次同样的唤醒流程。 */
            val retryReadinessResult = appProcessReadiness.ensureReady()
            providerIdentityAfterWake = providerIdentityAfterWake || retryReadinessResult.appWakeRequested
            logAppProcessReadiness(eventId, retryReadinessResult)
            queryWithElapsed = queryShizukuProcessWithElapsed(eventId)
        }

        /** 身份判断结果；只消费最终一次 Provider 查询输出。 */
        val decision = processIdentity.verifyQueryResult(queryWithElapsed.result)
        return ShizukuProcessIdentityVerification(
            decision = decision,
            providerQueryElapsedMs = queryWithElapsed.elapsedMs,
            wakeFailedProviderFallback = wakeFailedProviderFallback,
            providerIdentityAfterWake = providerIdentityAfterWake
        )
    }

    /**
     * 调用 Provider 身份查询并记录命令耗时。
     *
     * @param eventId 当前剪贴事件 ID。
     */
    private fun queryShizukuProcessWithElapsed(eventId: String): TimedProviderCommandResult {
        /** Provider 查询开始时间，使用单调时钟避免系统时间跳变影响耗时。 */
        val queryStartMillis = SystemClock.elapsedRealtime()
        /** Provider 身份查询结果；输出只包含低敏身份字段或命令错误摘要。 */
        val queryResult = callProviderQueryShizukuProcess(eventId)
        /** Provider 查询耗时，单位毫秒；假如时钟异常回退则兜底为 0。 */
        val queryElapsedMillis = (SystemClock.elapsedRealtime() - queryStartMillis).coerceAtLeast(0L)
        return TimedProviderCommandResult(result = queryResult, elapsedMs = queryElapsedMillis)
    }

    /**
     * 输出 Shizuku 进程身份判断结果。
     *
     * @param eventId 当前剪贴事件 ID。
     * @param decision 当前进程与 Provider 期望进程名的完整字符串比较结果。
     * @param providerQueryElapsedMs Provider 身份查询命令耗时，单位毫秒。
     * @param wakeFailedProviderFallback 是否在 app 唤醒失败后继续执行 Provider 兼容查询。
     * @param providerIdentityAfterWake 本次身份查询前是否执行过 app 唤醒命令。
     */
    private fun logShizukuProcessIdentityDecision(
        eventId: String,
        decision: ShizukuProcessIdentityDecision,
        providerQueryElapsedMs: Long,
        wakeFailedProviderFallback: Boolean,
        providerIdentityAfterWake: Boolean,
    ) {
        /** 当前进程和期望进程是否已经完整匹配；不确定时固定为 false。 */
        val matched = decision is ShizukuProcessIdentityDecision.Matched
        logD(TAG) {
            "Shizuku 进程身份校验 eventId=$eventId " +
                "currentProcessName=${decision.currentProcessName} " +
                "expectedProcessName=${decision.expectedProcessName} matched=$matched " +
                "resultCode=${decision.resultCode} reasonCode=${decision.reasonCode} " +
                "connectRequested=${decision.connectRequested} " +
                "connectSkipReason=${decision.connectSkipReason} " +
                "wakeFailedProviderFallback=$wakeFailedProviderFallback " +
                "providerIdentityAfterWake=$providerIdentityAfterWake " +
                "providerQueryElapsedMs=$providerQueryElapsedMs"
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
     * 提交 Provider 正式链路事件。
     *
     * 剪贴 payload 与图标链路使用同一 eventId 但失败互不影响；返回值只以 payload 是否被 Provider 确认处理为准。
     *
     * @param sourceAppInfo 来源应用展示信息。
     * @param eventId 当前剪贴事件 ID。
     * @param clipPayload Shizuku 直读剪贴板后构造的 payload；为空时只记录失败，不进入旧读取链路。
     */
    private suspend fun submitProviderBridgeEvent(
        sourceAppInfo: ShizukuSourceAppInfo,
        eventId: String,
        clipPayload: ClipboardBridgeClipPayload?,
    ): Boolean = supervisorScope {
        /** clipPayloadJob 只负责剪贴 payload 写入和提交，不能等待图标链路完成。 */
        val clipPayloadJob = async {
            runProviderClipPayload(
                eventId = eventId,
                clipPackageName = sourceAppInfo.packageName,
                appName = sourceAppInfo.appName,
                iconHash = sourceAppInfo.iconHash,
                clipPayload = clipPayload
            )
        }

        iconSyncCoordinator.launch(
            eventId = eventId,
            clipPackageName = sourceAppInfo.packageName,
            appName = sourceAppInfo.appName,
            bitmap = sourceAppInfo.bitmap,
            iconHash = sourceAppInfo.iconHash
        )

        /** Provider 直读链路处理结果；返回 false 表示 payload 未被确认处理。 */
        val providerOk = clipPayloadJob.await()
        logD(TAG) {
            "Provider 通道发送完成 ok=$providerOk packageName=${sourceAppInfo.packageName} " +
                "appName=${sourceAppInfo.appName} iconHash=${sourceAppInfo.iconHash}"
        }
        providerOk
    }

    /**
     * 执行 Provider 剪贴 payload 写入与提交。
     *
     * @param eventId 当前剪贴事件 ID。
     * @param clipPackageName 来源应用包名。
     * @param appName 来源应用名称。
     * @param iconHash 来源图标 hash，提交剪贴时只用于复用已有来源图标。
     * @param clipPayload Shizuku 直读剪贴板后构造的 payload；为空时不再尝试旧读取入口。
     */
    private fun runProviderClipPayload(
        eventId: String,
        clipPackageName: String?,
        appName: String?,
        iconHash: String?,
        clipPayload: ClipboardBridgeClipPayload?,
    ): Boolean {
        if (clipPayload == null) {
            logW(TAG) { "Provider 剪贴 payload 为空 eventId=$eventId packageName=$clipPackageName" }
            return false
        }

        /** payload JSON 字符串；只通过 stdin 写入 Provider，禁止拼接进 shell 参数。 */
        val payloadJson = clipPayload.toJsonString()
        /** payload 写入是否成功；只有 content write exit=0 才允许 commit_clip。 */
        val payloadWriteOk = providerCommandClient.writeClipPayload(eventId, payloadJson)
        if (!payloadWriteOk) {
            logW(TAG) { "Provider 剪贴 payload 写入失败 eventId=$eventId packageName=$clipPackageName" }
            return false
        }

        /** Provider commit_clip 命令结果；输出只包含脱敏 Bundle 字段。 */
        val commitResult = providerCommandClient.commitClip(eventId, clipPackageName, appName, iconHash)
        /** commit_clip 是否完成处理；重复/空内容也属于已按既有语义处理完成。 */
        val commitOk = ClipboardBridgeCommandResultParser.isCommitClipSuccessful(commitResult.exitCode, commitResult.output)
        if (commitOk) {
            logD(TAG) {
                "Provider 剪贴 payload 处理完成 eventId=$eventId packageName=$clipPackageName " +
                    "clipStatus=${ClipboardBridgeCommandResultParser.parseClipStatus(commitResult.output)} " +
                    "clipCommitted=${ClipboardBridgeCommandResultParser.parseClipCommitted(commitResult.output)}"
            }
        } else {
            logW(TAG) {
                "Provider 剪贴 payload 未确认成功 eventId=$eventId packageName=$clipPackageName " +
                    "exit=${commitResult.exitCode} resultCode=${ClipboardBridgeCommandResultParser.parseResultCode(commitResult.output)} " +
                    "clipStatus=${ClipboardBridgeCommandResultParser.parseClipStatus(commitResult.output)}"
            }
        }
        return commitOk
    }

    /**
     * 调用 Provider 查询当前 app 期望的最新 Shizuku 完整进程名。
     *
     * @param eventId 当前剪贴事件 ID，用于串联身份查询和剪贴提交日志。
     */
    private fun callProviderQueryShizukuProcess(eventId: String): ProviderCommandResult {
        return providerCommandClient.queryShizukuProcess(eventId)
    }
}

/**
 * 带耗时的 Provider 命令结果。
 *
 * @param result Provider 命令退出码和输出。
 * @param elapsedMs Provider 命令耗时，单位毫秒。
 */
private data class TimedProviderCommandResult(
    val result: ProviderCommandResult,
    val elapsedMs: Long,
)

/**
 * Shizuku 身份查询的最终结果和诊断字段。
 *
 * @param decision 当前 Shizuku 进程身份判断结果。
 * @param providerQueryElapsedMs 最终一次 Provider 身份查询耗时，单位毫秒。
 * @param wakeFailedProviderFallback 是否在 app 进程未确认可达时仍执行 Provider 兼容查询。
 * @param providerIdentityAfterWake 最终身份查询前是否执行过 app 唤醒命令。
 */
private data class ShizukuProcessIdentityVerification(
    val decision: ShizukuProcessIdentityDecision,
    val providerQueryElapsedMs: Long,
    val wakeFailedProviderFallback: Boolean,
    val providerIdentityAfterWake: Boolean,
)
