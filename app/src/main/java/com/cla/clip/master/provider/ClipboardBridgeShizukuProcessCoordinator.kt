package com.cla.clip.master.provider

import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.master.BuildConfig
import com.cla.clip.master.utils.ShizukuConnectRequestResult
import com.cla.clip.master.utils.ShizukuConnector
import com.cla.clip.shizuku.ClipboardBridgeContract
import com.cla.clip.shizuku.ShizukuProcessName
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provider 侧 Shizuku 进程身份查询协调器。
 *
 * 它负责刷新 app 侧最新期望进程名、异步触发最新 Shizuku 绑定，并把低敏身份结果返回给旧或当前 Shizuku 进程。
 */
@Singleton
class ClipboardBridgeShizukuProcessCoordinator @Inject constructor(
    /** Provider 身份查询触发连接请求的薄封装。 */
    private val connectRequester: ShizukuConnectRequester,
) {
    companion object {
        /** 日志标签，用于观察 Provider 身份查询是否频繁触发 bind。 */
        private const val TAG = "ClipboardBridgeShizukuProcess"
    }

    /** 身份查询纯流程处理器；懒创建避免 Provider 冷启动时过早读取 AppSetting。 */
    private val queryHandler: ClipboardBridgeShizukuProcessQueryHandler by lazy {
        ClipboardBridgeShizukuProcessQueryHandler(
            installIdProvider = { AppSetting.pid },
            processNameWriter = { processName -> AppSetting.shizukuSuffix = processName },
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
    fun query(request: ClipboardBridgeRequest): ClipboardBridgeResult {
        /** Provider 身份查询结果；内部已经刷新 expectedProcessName 并提交 best-effort 连接请求。 */
        val result = queryHandler.query(eventId = request.eventId)
        logD(TAG) {
            "Provider 查询 Shizuku 进程 eventId=${request.eventId} expectedProcessName=${result.shizukuProcessName} " +
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
 * @param installIdProvider 安装级 ID 读取器，生产读取 AppSetting，测试可传固定值。
 * @param processNameWriter 最新完整进程名写入器，生产写回 AppSetting。
 * @param connectRequester 连接请求函数，只提交 best-effort 异步连接，不等待 bind 完成。
 * @param applicationId 当前宿主应用 id。
 * @param version 当前 Shizuku 用户服务版本。
 */
internal class ClipboardBridgeShizukuProcessQueryHandler(
    private val installIdProvider: () -> String,
    private val processNameWriter: (String) -> Unit,
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
    fun query(eventId: String): ClipboardBridgeResult {
        /** 当前安装级 ID；生产路径由 AppSetting 保证是固定长度纯数字。 */
        val installId = installIdProvider()
        /** 当前期望进程名集合；Provider 入口同样刷新，覆盖旧进程冷启动 app 的窗口。 */
        val processNames = ShizukuProcessName.buildNames(
            applicationId = applicationId,
            version = version,
            installId = installId
        )
        processNameWriter(processNames.fullName)

        if (processNames.fullName.isBlank()) {
            return ClipboardBridgeResult.of(
                resultCode = ClipboardBridgeContract.CODE_SHIZUKU_PROCESS_MISSING,
                connectRequested = false,
                connectSkipReason = ClipboardBridgeContract.REASON_MISSING_EXPECTED_PROCESS_NAME,
                reasonCode = ClipboardBridgeContract.REASON_MISSING_EXPECTED_PROCESS_NAME
            )
        }

        /** 异步连接请求结果；失败时仍返回当前期望名，让 Shizuku 侧可以完成身份比较。 */
        val connectResult = runCatching {
            connectRequester(ClipboardBridgeContract.REASON_IDENTITY_QUERY)
        }.getOrElse { throwable ->
            logE(TAG, throwable) {
                "Provider 请求连接最新 Shizuku 失败 eventId=$eventId expectedProcessName=${processNames.fullName}"
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
