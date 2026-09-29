package com.cla.clip.master.provider

import com.cla.clip.base.general.config.installation.InstallationIdentityRepository
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.master.BuildConfig
import com.cla.clip.master.utils.ShizukuConnectRequestResult
import com.cla.clip.master.utils.ShizukuConnector
import com.cla.clip.shizuku.ClipboardBridgeContract
import com.cla.clip.shizuku.ShizukuProcessName
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Provider 侧 Shizuku 进程身份查询协调器。
 *
 * 它负责刷新 app 侧最新期望进程名、异步触发最新 Shizuku 绑定，并把低敏身份结果返回给旧或当前 Shizuku 进程。
 */
@Singleton
class ClipboardBridgeShizukuProcessCoordinator @Inject constructor(
    /** Provider 身份查询触发连接请求的薄封装。 */
    private val connectRequester: ShizukuConnectRequester,
    /** 宿主唯一安装身份仓库；冷启动查询与连接器复用已提交的 DataStore 身份。 */
    private val identityRepository: InstallationIdentityRepository,
) {
    companion object {
        /** 日志标签，用于观察 Provider 身份查询是否频繁触发 bind。 */
        private const val TAG = "ClipboardBridgeShizukuProcess"
    }

    /** 身份查询纯流程处理器；首次 Binder 请求才懒取仓库，不在 Provider.onCreate 读盘。 */
    private val queryHandler: ClipboardBridgeShizukuProcessQueryHandler by lazy {
        ClipboardBridgeShizukuProcessQueryHandler(
            installIdProvider = identityRepository::getOrCreateInstallId,
            processNameWriter = identityRepository::setExpectedProcessName,
            connectRequester = connectRequester::request,
            applicationId = BuildConfig.APPLICATION_ID,
            version = ShizukuConnector.VERSION
        )
    }

    /**
     * 查询当前期望 Shizuku 完整进程名并 best-effort 触发最新绑定。
     *
     * @param request Provider 请求参数，主要使用 eventId 串联 Shizuku 侧日志。
     */
    suspend fun query(request: ClipboardBridgeRequest): ClipboardBridgeResult {
        /** Provider 身份查询结果；持久化进程名完成后才异步提交连接请求。 */
        val result = queryHandler.query(eventId = request.eventId)
        logD(TAG) {
            "Provider 查询 Shizuku 进程 eventId=${request.eventId} ready=${!result.shizukuProcessName.isNullOrBlank()} " +
                "connectRequested=${result.connectRequested} connectSkipReason=${result.connectSkipReason}"
        }
        if (result.resultCode == ClipboardBridgeContract.CODE_SHIZUKU_PROCESS_MISSING) {
            logE(TAG) {
                "Provider 查询 Shizuku 进程失败 eventId=${request.eventId} reasonCode=${result.reasonCode}"
            }
        }
        return result
    }
}

/**
 * Provider 身份查询纯流程处理器。
 *
 * @param installIdProvider 安装级 ID 挂起读取器，生产在 DataStore 事务内生成并提交。
 * @param processNameWriter 最新完整进程名挂起写入器，返回后表示已持久化。
 * @param connectRequester 连接请求函数，只提交 best-effort 异步连接，不等待 bind 完成。
 * @param applicationId 当前宿主应用 id。
 * @param version 当前 Shizuku 用户服务版本。
 */
internal class ClipboardBridgeShizukuProcessQueryHandler(
    private val installIdProvider: suspend () -> String,
    private val processNameWriter: suspend (String) -> Unit,
    private val connectRequester: (String) -> ShizukuConnectRequestResult,
    private val applicationId: String,
    private val version: Int,
) {
    companion object {
        /** 日志标签，用于纯处理器记录连接请求失败。 */
        private const val TAG = "ClipboardBridgeShizukuProcess"
    }

    /**
     * 查询并返回当前期望 Shizuku 完整进程名。
     *
     * @param eventId 当前剪贴事件 ID，用于诊断连接请求失败。
     */
    suspend fun query(eventId: String): ClipboardBridgeResult {
        /** 当前已提交的安装级 ID；生产仓库会在同一 DataStore 事务内生成缺失身份。 */
        val installId = installIdProvider()
        /** 当前期望进程名集合；Provider 入口同样刷新，覆盖旧进程冷启动 app 的窗口。 */
        val processNames = ShizukuProcessName.buildNames(
            applicationId = applicationId,
            version = version,
            installId = installId
        )
        if (processNames.fullName.isBlank()) {
            return ClipboardBridgeResult.of(
                resultCode = ClipboardBridgeContract.CODE_SHIZUKU_PROCESS_MISSING,
                connectRequested = false,
                connectSkipReason = ClipboardBridgeContract.REASON_MISSING_EXPECTED_PROCESS_NAME,
                reasonCode = ClipboardBridgeContract.REASON_MISSING_EXPECTED_PROCESS_NAME
            )
        }

        // 先等待期望进程名持久化，再把可信身份返回给旧进程或提交异步 bind 请求。
        processNameWriter(processNames.fullName)

        /** 异步连接请求结果；失败时仍返回当前期望名，让 Shizuku 侧可以完成身份比较。 */
        val connectResult = runCatching {
            connectRequester(ClipboardBridgeContract.REASON_IDENTITY_QUERY)
        }.getOrElse { throwable ->
            logE(TAG) {
                "Provider 请求连接最新 Shizuku 失败 eventId=$eventId exceptionType=${throwable::class.java.simpleName}"
            }
            null
        }

        /** 是否成功提交连接请求；该值不表示真实 bind 已完成。 */
        val connectRequested = connectResult?.requested == true
        /** 连接请求跳过或失败原因；用于观察频繁触发 bind 的情况。 */
        val connectSkipReason = if (connectRequested) {
            null
        } else {
            ClipboardBridgeContract.REASON_CONNECT_REQUEST_FAILED
        }

        return ClipboardBridgeResult.of(
            resultCode = ClipboardBridgeContract.CODE_OK,
            shizukuProcessName = processNames.fullName,
            connectRequested = connectRequested,
            connectSkipReason = connectSkipReason,
            reasonCode = ClipboardBridgeContract.REASON_IDENTITY_QUERY
        )
    }
}

/**
 * 同步 Binder 边界的局部协程桥；仅用于安装身份查询，不把阻塞读取扩散到普通仓库调用方。
 * 在非主线程最多等待三秒，真实 DataStore 工作调度到 IO；失败按既有协议返回可重试结果。
 */
internal object ClipboardBridgeShizukuIdentityQueryBridge {
    /** Binder 查询等待上限；冷启动超出上限时由 Shizuku 侧稍后重试。 */
    private const val DEFAULT_TIMEOUT_MS = 3_000L

    /**
     * 在 Binder 线程等待挂起查询，并把超时、主线程误调用和存储异常映射为协议结果。
     * @param isMainThread 当前调用是否来自进程主线程，主线程绝不阻塞等待 DataStore。
     * @param timeoutMs 本次 Binder 等待上限；测试可传更小值验证超时边界。
     * @param query 已通过调用方权限及参数检查的身份查询。
     */
    fun query(
        isMainThread: Boolean,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        query: suspend () -> ClipboardBridgeResult,
    ): ClipboardBridgeResult {
        if (isMainThread) {
            return ClipboardBridgeResult.of(
                resultCode = ClipboardBridgeContract.CODE_TIMEOUT,
                reasonCode = "main_thread_query_rejected"
            )
        }
        return try {
            runBlocking(Dispatchers.IO) { withTimeout(timeoutMs) { query() } }
        } catch (exception: TimeoutCancellationException) {
            ClipboardBridgeResult.of(
                resultCode = ClipboardBridgeContract.CODE_TIMEOUT,
                reasonCode = "identity_query_timeout"
            )
        } catch (exception: Exception) {
            ClipboardBridgeResult.of(
                resultCode = ClipboardBridgeContract.CODE_SHIZUKU_PROCESS_MISSING,
                reasonCode = "identity_store_failed"
            )
        }
    }
}
