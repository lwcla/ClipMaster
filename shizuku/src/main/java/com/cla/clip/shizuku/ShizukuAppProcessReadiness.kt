package com.cla.clip.shizuku

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shizuku 进程提交 Provider 前的 app 主进程探活与唤醒协作者。
 *
 * 该类只负责 callback 可达性、app 唤醒、等待 callback 回来和短冷却，不判断 Shizuku 身份，也不写入剪贴数据。
 *
 * @param callbackFlow 当前 app 主进程注册的 callback 状态。
 * @param pingCallback 无副作用 callback 探活函数，返回 true 表示 app 主进程可达。
 * @param wakeAppProcess 使用 shell 命令拉起 app 主进程的函数，可由前台服务或 NoDisplay Activity 实现。
 * @param clockMillis 单调时间来源，用于耗时和 cooldown，测试可替换为假时钟。
 * @param pingDispatcher 执行 Binder ping 的调度器，避免阻塞调用方协程。
 * @param pingTimeoutMillis 单次 callback 探活最长等待时间。
 * @param callbackWaitMillis 唤醒后等待新 callback 的最长时间，必须小于 ClipboardService 无 payload 自停时间。
 * @param wakeCooldownMillis 唤醒失败后的短冷却，避免连续复制反复拉起 app。
 */
internal class ShizukuAppProcessReadiness<T : Any>(
    private val callbackFlow: MutableStateFlow<T?>,
    private val pingCallback: suspend (T) -> Boolean,
    private val wakeAppProcess: () -> AppWakeCommandResult,
    private val clockMillis: () -> Long,
    private val pingDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pingTimeoutMillis: Long = DEFAULT_PING_TIMEOUT_MS,
    private val callbackWaitMillis: Long = DEFAULT_CALLBACK_WAIT_MS,
    private val wakeCooldownMillis: Long = DEFAULT_WAKE_COOLDOWN_MS,
) {
    companion object {
        /** callback 探活超时；超过该值按 callback 不可信处理。 */
        private const val DEFAULT_PING_TIMEOUT_MS = 300L

        /** 唤醒后等待 callback 重连的时间；必须小于 ClipboardService 的 3000ms 无 payload 自停延迟。 */
        private const val DEFAULT_CALLBACK_WAIT_MS = 2_500L

        /** 唤醒失败后的进程内短冷却，避免高频复制反复拉起 app。 */
        private const val DEFAULT_WAKE_COOLDOWN_MS = 3_000L
    }

    /** 唤醒互斥锁；并发剪贴事件各自保留快照，但共享同一轮 wake + wait callback。 */
    private val wakeMutex = Mutex()

    /** 最近一次唤醒失败的时间；为空表示当前没有 cooldown 限制。 */
    private var lastWakeFailedAtMillis: Long? = null

    /**
     * 确保 Provider 身份查询前 app 主进程尽量处于可达状态。
     *
     * 返回值只表示 callback/唤醒层状态，调用方仍必须继续执行 Provider 身份查询。
     */
    suspend fun ensureReady(): ShizukuAppProcessReadinessResult {
        /** 当前 callback 快照；为空表示 app 主进程尚未注册或已被清理。 */
        val callback = callbackFlow.value
        if (callback != null) {
            /** 首次探活结果；成功时不启动 app 唤醒命令。 */
            val pingResult = ping(callback)
            if (pingResult.ok) {
                lastWakeFailedAtMillis = null
                return ShizukuAppProcessReadinessResult.Ready(
                    appPingResult = pingResult.reasonCode,
                    appWakeElapsedMs = 0L,
                    reasonCode = "callback_ready"
                )
            }
            clearCallback(callback)
            return wakeAfterUnavailableCallback(pingResult.reasonCode)
        }
        return wakeAfterUnavailableCallback("callback_missing")
    }

    /**
     * 在 callback 不可用时执行共享唤醒流程。
     *
     * @param unavailableReason callback 不可信的原因，用于返回值和日志聚合。
     */
    private suspend fun wakeAfterUnavailableCallback(unavailableReason: String): ShizukuAppProcessReadinessResult {
        return wakeMutex.withLock {
            /** 进入互斥区后重新读取 callback，复用其他并发事件可能已经拉起的主进程。 */
            val reboundBeforeWake = callbackFlow.value
            if (reboundBeforeWake != null) {
                /** 共享唤醒后的二次探活结果；成功说明本事件无需再发起 app 唤醒命令。 */
                val reboundPing = ping(reboundBeforeWake)
                if (reboundPing.ok) {
                    lastWakeFailedAtMillis = null
                    return@withLock ShizukuAppProcessReadinessResult.Ready(
                        appPingResult = reboundPing.reasonCode,
                        appWakeElapsedMs = 0L,
                        reasonCode = "callback_ready_after_shared_wake"
                    )
                }
                clearCallback(reboundBeforeWake)
            }

            /** 当前单调时间；用于判断唤醒失败后的短冷却窗口。 */
            val nowMillis = clockMillis()
            /** 最近失败时间；在 cooldown 内不再重复拉起 app。 */
            val failedAtMillis = lastWakeFailedAtMillis
            if (failedAtMillis != null && nowMillis - failedAtMillis < wakeCooldownMillis) {
                return@withLock ShizukuAppProcessReadinessResult.CooldownSkipped(
                    appPingResult = unavailableReason,
                    appWakeElapsedMs = 0L,
                    reasonCode = "wake_cooldown_skipped"
                )
            }

            /** 唤醒命令开始时间；只记录耗时，不记录剪贴板内容。 */
            val wakeStartMillis = clockMillis()
            /** app 唤醒命令结果；由调用方封装真实 shell 执行、Activity fallback 和 2000ms 超时。 */
            val wakeCommandResult = wakeAppProcess()
            /** 唤醒命令耗时；假时钟回退时兜底为 0，避免日志出现负数。 */
            val wakeElapsedMillis = (clockMillis() - wakeStartMillis).coerceAtLeast(0L)
            /** 唤醒命令是否被系统接受；前台服务和 NoDisplay Activity 输出解析集中在 ClipboardBridgeCommandResultParser。 */
            val wakeCommandAccepted = !wakeCommandResult.timedOut &&
                ClipboardBridgeCommandResultParser.isAppWakeCommandSuccessful(
                    wakeCommandResult.exitCode,
                    wakeCommandResult.output
                )
            if (!wakeCommandAccepted) {
                lastWakeFailedAtMillis = clockMillis()
                return@withLock ShizukuAppProcessReadinessResult.WakeFailed(
                    appPingResult = unavailableReason,
                    appWakeMode = wakeCommandResult.wakeMode,
                    appWakeResult = false,
                    callbackRebound = false,
                    appWakeElapsedMs = wakeElapsedMillis,
                    reasonCode = if (wakeCommandResult.timedOut) "wake_command_timeout" else "wake_command_failed"
                )
            }

            /** 唤醒后重新注册的 callback；为空表示唤醒入口未在等待窗口内带回 app 主进程连接。 */
            val reboundCallback = withTimeoutOrNull(callbackWaitMillis) {
                callbackFlow.filterNotNull().first()
            }
            if (reboundCallback == null) {
                lastWakeFailedAtMillis = clockMillis()
                return@withLock ShizukuAppProcessReadinessResult.WakeFailed(
                    appPingResult = unavailableReason,
                    appWakeMode = wakeCommandResult.wakeMode,
                    appWakeResult = true,
                    callbackRebound = false,
                    appWakeElapsedMs = wakeElapsedMillis,
                    reasonCode = callbackWaitTimeoutReason(wakeCommandResult.wakeMode)
                )
            }

            /** 唤醒后的确认探活结果；避免重新拿到旧的坏 callback。 */
            val reboundPing = ping(reboundCallback)
            if (!reboundPing.ok) {
                clearCallback(reboundCallback)
                lastWakeFailedAtMillis = clockMillis()
                return@withLock ShizukuAppProcessReadinessResult.WakeFailed(
                    appPingResult = reboundPing.reasonCode,
                    appWakeMode = wakeCommandResult.wakeMode,
                    appWakeResult = true,
                    callbackRebound = true,
                    appWakeElapsedMs = wakeElapsedMillis,
                    reasonCode = "rebound_ping_failed"
                )
            }

            lastWakeFailedAtMillis = null
            ShizukuAppProcessReadinessResult.WakeSucceeded(
                appPingResult = reboundPing.reasonCode,
                appWakeMode = wakeCommandResult.wakeMode,
                appWakeElapsedMs = wakeElapsedMillis,
                reasonCode = "wake_succeeded"
            )
        }
    }

    /**
     * 根据唤醒入口生成 callback 等待超时原因码。
     *
     * @param wakeMode 已被系统接受的唤醒入口，用于区分 NoDisplay Activity 启动成功但 callback 未回流的场景。
     */
    private fun callbackWaitTimeoutReason(wakeMode: AppWakeMode): String {
        return if (wakeMode == AppWakeMode.ACTIVITY_NO_DISPLAY) {
            "wake_activity_started_callback_timeout"
        } else {
            "callback_wait_timeout"
        }
    }

    /**
     * 执行一次 callback 探活。
     *
     * @param callback 待探测的 callback 实例。
     */
    private suspend fun ping(callback: T): AppProcessPingResult {
        /** 探活开始时间；用于后续需要扩展 ping 耗时日志时保持同一时间源。 */
        val pingStartMillis = clockMillis()
        return try {
            /** ping 返回值；超时返回 null，false 也按 callback 不可信处理。 */
            val pingOk = withTimeoutOrNull(pingTimeoutMillis) {
                withContext(pingDispatcher) {
                    pingCallback(callback)
                }
            }
            /** 探活耗时；当前只参与调试扩展，不作为业务判断条件。 */
            val pingElapsedMillis = (clockMillis() - pingStartMillis).coerceAtLeast(0L)
            when (pingOk) {
                true -> AppProcessPingResult(ok = true, reasonCode = "ping_ok", elapsedMillis = pingElapsedMillis)
                false -> AppProcessPingResult(ok = false, reasonCode = "ping_false", elapsedMillis = pingElapsedMillis)
                null -> AppProcessPingResult(ok = false, reasonCode = "ping_timeout", elapsedMillis = pingElapsedMillis)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            /** 异常类型名；不记录 message，避免厂商异常里夹带敏感上下文。 */
            val exceptionName = throwable::class.java.simpleName.ifBlank { "Throwable" }
            AppProcessPingResult(
                ok = false,
                reasonCode = "ping_exception_$exceptionName",
                elapsedMillis = (clockMillis() - pingStartMillis).coerceAtLeast(0L)
            )
        }
    }

    /**
     * 仅当当前 callback 仍是失败实例时清空，避免覆盖并发事件刚注册的新 callback。
     *
     * @param failedCallback 已确认不可用的旧 callback。
     */
    private fun clearCallback(failedCallback: T) {
        callbackFlow.update { currentCallback ->
            if (currentCallback === failedCallback) null else currentCallback
        }
    }
}

/** app 主进程 callback 探活结果。 */
private data class AppProcessPingResult(
    /** true 表示 callback 已成功响应 ping。 */
    val ok: Boolean,
    /** 探活结果原因码，用于低敏日志聚合。 */
    val reasonCode: String,
    /** 探活耗时，单位毫秒；当前主要供后续诊断扩展。 */
    val elapsedMillis: Long,
)

/** app 主进程唤醒命令模式。 */
internal enum class AppWakeMode {
    /** 未执行 app 唤醒命令，通常表示 callback 已可达或 cooldown 跳过。 */
    NONE,

    /** 通过 `am start-foreground-service` 唤醒主进程。 */
    FOREGROUND_SERVICE,

    /** 通过 `am start --activity-no-animation` 启动 NoDisplay Activity 唤醒主进程。 */
    ACTIVITY_NO_DISPLAY,
}

/** app 主进程唤醒命令结果。 */
internal data class AppWakeCommandResult(
    /** 本次命令采用的唤醒入口，用于日志聚合和区分 callback 未回流原因。 */
    val wakeMode: AppWakeMode,
    /** `am` 唤醒命令退出码；超时时固定为 -1。 */
    val exitCode: Int,
    /** `am` 唤醒命令标准输出和错误输出合并文本；超时时使用低敏 timeout 字符串。 */
    val output: String,
    /** true 表示命令超过调用方设置的等待上限。 */
    val timedOut: Boolean,
)

/** app 主进程探活与唤醒策略结果。 */
internal sealed class ShizukuAppProcessReadinessResult {
    /** callback 探活结果原因码。 */
    abstract val appPingResult: String

    /** 是否请求过 app 唤醒。 */
    abstract val appWakeRequested: Boolean

    /** app 唤醒入口；未请求唤醒时固定为 NONE。 */
    abstract val appWakeMode: AppWakeMode

    /** app 唤醒命令是否被系统接受；未请求唤醒时为空。 */
    abstract val appWakeResult: Boolean?

    /** 唤醒后 callback 是否在等待窗口内回来；未请求唤醒时为空。 */
    abstract val callbackRebound: Boolean?

    /** 是否因 cooldown 跳过本次唤醒。 */
    abstract val wakeCooldownSkipped: Boolean

    /** app 唤醒命令耗时，单位毫秒。 */
    abstract val appWakeElapsedMs: Long

    /** 是否允许 Provider 缺失竞态再触发一次唤醒。 */
    abstract val canRetryWakeAfterProviderMissing: Boolean

    /** 当前探活结果是否足以继续常规 Provider 查询。 */
    abstract val readyForProviderQuery: Boolean

    /** 结果原因码，用于日志和单元测试断言。 */
    abstract val reasonCode: String

    /** callback 已经可用，不需要拉起 app。 */
    data class Ready(
        /** callback 探活结果原因码。 */
        override val appPingResult: String,
        /** app 唤醒命令耗时，固定为 0。 */
        override val appWakeElapsedMs: Long,
        /** 结果原因码。 */
        override val reasonCode: String,
    ) : ShizukuAppProcessReadinessResult() {
        /** 未请求 app 唤醒。 */
        override val appWakeRequested: Boolean = false
        /** 未请求 app 唤醒，因此模式固定为 NONE。 */
        override val appWakeMode: AppWakeMode = AppWakeMode.NONE
        /** 未请求 app 唤醒，因此没有唤醒结果。 */
        override val appWakeResult: Boolean? = null
        /** 未请求 app 唤醒，因此没有 callback 回流结果。 */
        override val callbackRebound: Boolean? = null
        /** 当前没有 cooldown 跳过。 */
        override val wakeCooldownSkipped: Boolean = false
        /** Provider 缺失可能是 ping 后 app 被杀，可以再触发一次唤醒。 */
        override val canRetryWakeAfterProviderMissing: Boolean = true
        /** callback 已可达，可以继续 Provider 查询。 */
        override val readyForProviderQuery: Boolean = true
    }

    /** app 唤醒成功并确认 callback 已恢复。 */
    data class WakeSucceeded(
        /** 唤醒后 callback 探活结果原因码。 */
        override val appPingResult: String,
        /** 本次成功唤醒采用的入口模式。 */
        override val appWakeMode: AppWakeMode,
        /** app 唤醒命令耗时。 */
        override val appWakeElapsedMs: Long,
        /** 结果原因码。 */
        override val reasonCode: String,
    ) : ShizukuAppProcessReadinessResult() {
        /** 已请求 app 唤醒。 */
        override val appWakeRequested: Boolean = true
        /** app 唤醒命令被系统接受。 */
        override val appWakeResult: Boolean? = true
        /** callback 已在等待窗口内回来。 */
        override val callbackRebound: Boolean? = true
        /** 当前没有 cooldown 跳过。 */
        override val wakeCooldownSkipped: Boolean = false
        /** 本事件已经执行过唤醒，不允许 Provider 缺失时再拉起一次。 */
        override val canRetryWakeAfterProviderMissing: Boolean = false
        /** callback 已可达，可以继续 Provider 查询。 */
        override val readyForProviderQuery: Boolean = true
    }

    /** app 唤醒失败或唤醒后 callback 未恢复。 */
    data class WakeFailed(
        /** 唤醒前或唤醒后 callback 探活失败原因码。 */
        override val appPingResult: String,
        /** 本次失败唤醒采用的入口模式。 */
        override val appWakeMode: AppWakeMode,
        /** app 唤醒命令是否被系统接受。 */
        override val appWakeResult: Boolean?,
        /** callback 是否在等待窗口内回来。 */
        override val callbackRebound: Boolean?,
        /** app 唤醒命令耗时。 */
        override val appWakeElapsedMs: Long,
        /** 结果原因码。 */
        override val reasonCode: String,
    ) : ShizukuAppProcessReadinessResult() {
        /** 已请求 app 唤醒。 */
        override val appWakeRequested: Boolean = true
        /** 当前没有 cooldown 跳过。 */
        override val wakeCooldownSkipped: Boolean = false
        /** 本事件已经执行过唤醒，不允许 Provider 缺失时再拉起一次。 */
        override val canRetryWakeAfterProviderMissing: Boolean = false
        /** callback 未确认可达，Provider 查询仅作为兼容兜底。 */
        override val readyForProviderQuery: Boolean = false
    }

    /** 因唤醒失败 cooldown 跳过本次 app 唤醒。 */
    data class CooldownSkipped(
        /** callback 不可信原因码。 */
        override val appPingResult: String,
        /** app 唤醒命令耗时，固定为 0。 */
        override val appWakeElapsedMs: Long,
        /** 结果原因码。 */
        override val reasonCode: String,
    ) : ShizukuAppProcessReadinessResult() {
        /** cooldown 内未请求 app 唤醒。 */
        override val appWakeRequested: Boolean = false
        /** cooldown 内未请求 app 唤醒，因此模式固定为 NONE。 */
        override val appWakeMode: AppWakeMode = AppWakeMode.NONE
        /** 未请求 app 唤醒，因此没有唤醒结果。 */
        override val appWakeResult: Boolean? = null
        /** 未请求 app 唤醒，因此没有 callback 回流结果。 */
        override val callbackRebound: Boolean? = null
        /** 本次由 cooldown 主动跳过唤醒。 */
        override val wakeCooldownSkipped: Boolean = true
        /** cooldown 表示近期已经失败过，不允许 Provider 缺失时再次拉起。 */
        override val canRetryWakeAfterProviderMissing: Boolean = false
        /** callback 未确认可达，Provider 查询仅作为兼容兜底。 */
        override val readyForProviderQuery: Boolean = false
    }

    /** callback 探活失败且未执行唤醒的保留结果，用于后续扩展新的跳过策略。 */
    data class PingFailed(
        /** callback 探活失败原因码。 */
        override val appPingResult: String,
        /** 结果原因码。 */
        override val reasonCode: String,
    ) : ShizukuAppProcessReadinessResult() {
        /** 未请求 app 唤醒。 */
        override val appWakeRequested: Boolean = false
        /** 未请求 app 唤醒，因此模式固定为 NONE。 */
        override val appWakeMode: AppWakeMode = AppWakeMode.NONE
        /** 未请求 app 唤醒，因此没有唤醒结果。 */
        override val appWakeResult: Boolean? = null
        /** 未请求 app 唤醒，因此没有 callback 回流结果。 */
        override val callbackRebound: Boolean? = null
        /** 当前没有 cooldown 跳过。 */
        override val wakeCooldownSkipped: Boolean = false
        /** 没有确认 callback 可达，不允许 Provider 缺失时追加唤醒。 */
        override val canRetryWakeAfterProviderMissing: Boolean = false
        /** app 唤醒命令未执行，耗时固定为 0。 */
        override val appWakeElapsedMs: Long = 0L
        /** callback 未确认可达，Provider 查询仅作为兼容兜底。 */
        override val readyForProviderQuery: Boolean = false
    }
}
