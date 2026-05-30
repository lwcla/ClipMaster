package com.cla.clip.shizuku

import java.io.File

/**
 * Shizuku 进程身份校验协作者。
 *
 * 只比较完整进程名，不拆解版本号或安装 pid；Provider 或本地进程名任一侧不确定时只跳过提交，不杀进程。
 *
 * @param processNameReader 当前 Shizuku 进程名读取器，测试可替换为固定值。
 * @param providerQuery Provider 身份查询命令，负责唤醒 app 并返回当前期望进程名。
 */
internal class ShizukuProcessIdentity(
    private val processNameReader: () -> String? = ::readCurrentProcessName,
    private val providerQuery: (eventId: String) -> ProviderCommandResult,
) {

    /**
     * 校验当前 Shizuku 进程是否仍是 app 期望的最新进程。
     *
     * @param eventId 当前剪贴事件 ID，用于 Provider 查询和日志串联。
     */
    fun verify(eventId: String): ShizukuProcessIdentityDecision {
        /** 当前 Shizuku 进程完整名称；为空表示本地读取失败，但仍要先触发 Provider 查询。 */
        val currentProcessName = processNameReader()?.trim()?.takeIf { it.isNotBlank() }
        /** Provider 查询结果；失败时身份不确定，不能误杀当前进程。 */
        val queryResult = runCatching { providerQuery(eventId) }.getOrElse {
            return ShizukuProcessIdentityDecision.Unknown(
                currentProcessName = currentProcessName,
                expectedProcessName = null,
                resultCode = null,
                reasonCode = ClipboardBridgeContract.REASON_PROVIDER_QUERY_FAILED,
                connectRequested = null,
                connectSkipReason = null
            )
        }
        return verifyQueryResult(queryResult, currentProcessName)
    }

    /**
     * 使用调用方已经完成的 Provider 查询结果校验 Shizuku 进程身份。
     *
     * @param queryResult Provider 身份查询命令结果，调用方可在查询前后插入 app 进程唤醒逻辑。
     */
    fun verifyQueryResult(queryResult: ProviderCommandResult): ShizukuProcessIdentityDecision {
        /** 当前 Shizuku 进程完整名称；为空时最终只会跳过提交，不会误杀当前进程。 */
        val currentProcessName = processNameReader()?.trim()?.takeIf { it.isNotBlank() }
        return verifyQueryResult(queryResult, currentProcessName)
    }

    /**
     * 根据当前进程名和 Provider 查询结果生成身份决策。
     *
     * @param queryResult Provider 身份查询命令结果。
     * @param currentProcessName 已读取并规整的当前 Shizuku 进程名。
     */
    private fun verifyQueryResult(
        queryResult: ProviderCommandResult,
        currentProcessName: String?,
    ): ShizukuProcessIdentityDecision {
        /** Provider resultCode；非 ok 时身份不确定。 */
        val resultCode = ClipboardBridgeCommandResultParser.parseResultCode(queryResult.output)
        /** Provider 返回的最新 Shizuku 完整进程名。 */
        val expectedProcessName = ClipboardBridgeCommandResultParser.parseShizukuProcessName(queryResult.output)
        /** Provider 是否已经发起 best-effort 连接请求。 */
        val connectRequested = ClipboardBridgeCommandResultParser.parseConnectRequested(queryResult.output)
        /** Provider 或连接器返回的连接跳过原因；为空表示没有明确跳过原因。 */
        val connectSkipReason = ClipboardBridgeCommandResultParser.parseConnectSkipReason(queryResult.output)
        /** Provider 返回的身份查询原因码；缺失时由本地规则补兜底。 */
        val providerReasonCode = ClipboardBridgeCommandResultParser.parseReasonCode(queryResult.output)

        if (!ClipboardBridgeCommandResultParser.isQueryShizukuProcessSuccessful(queryResult.exitCode, queryResult.output)) {
            /** 非成功身份查询的兜底原因，优先保留 Provider 明确 reasonCode。 */
            val reasonCode = providerReasonCode ?: ClipboardBridgeContract.REASON_PROVIDER_QUERY_FAILED
            return ShizukuProcessIdentityDecision.Unknown(
                currentProcessName = currentProcessName,
                expectedProcessName = expectedProcessName,
                resultCode = resultCode,
                reasonCode = reasonCode,
                connectRequested = connectRequested,
                connectSkipReason = connectSkipReason
            )
        }

        if (currentProcessName.isNullOrBlank()) {
            return ShizukuProcessIdentityDecision.Unknown(
                currentProcessName = currentProcessName,
                expectedProcessName = expectedProcessName,
                resultCode = resultCode,
                reasonCode = ClipboardBridgeContract.REASON_CURRENT_PROCESS_NAME_MISSING,
                connectRequested = connectRequested,
                connectSkipReason = connectSkipReason
            )
        }

        if (expectedProcessName.isNullOrBlank()) {
            return ShizukuProcessIdentityDecision.Unknown(
                currentProcessName = currentProcessName,
                expectedProcessName = expectedProcessName,
                resultCode = resultCode,
                reasonCode = ClipboardBridgeContract.REASON_MISSING_EXPECTED_PROCESS_NAME,
                connectRequested = connectRequested,
                connectSkipReason = connectSkipReason
            )
        }

        return if (currentProcessName == expectedProcessName) {
            ShizukuProcessIdentityDecision.Matched(
                currentProcessName = currentProcessName,
                expectedProcessName = expectedProcessName,
                resultCode = resultCode,
                connectRequested = connectRequested,
                connectSkipReason = connectSkipReason
            )
        } else {
            ShizukuProcessIdentityDecision.Mismatched(
                currentProcessName = currentProcessName,
                expectedProcessName = expectedProcessName,
                resultCode = resultCode,
                connectRequested = connectRequested,
                connectSkipReason = connectSkipReason
            )
        }
    }
}

/** Shizuku 进程身份校验结果。 */
internal sealed class ShizukuProcessIdentityDecision {

    /** 当前 Shizuku 进程完整名称；为空表示本地读取失败。 */
    abstract val currentProcessName: String?

    /** Provider 返回的最新 Shizuku 完整进程名；为空表示 app 侧身份不确定。 */
    abstract val expectedProcessName: String?

    /** Provider 返回的结果码；为空表示命令没有可解析结果。 */
    abstract val resultCode: String?

    /** 本次身份判断原因码，用于日志和跳过/自杀分支诊断。 */
    abstract val reasonCode: String

    /** Provider 是否已请求 app 侧尝试绑定最新 Shizuku 进程。 */
    abstract val connectRequested: Boolean?

    /** Provider 或连接器返回的跳过 bind 原因；为空表示没有明确跳过原因。 */
    abstract val connectSkipReason: String?

    /** 当前进程名和期望进程名完全一致，可以继续提交剪贴 payload。 */
    data class Matched(
        /** 当前 Shizuku 进程完整名称。 */
        override val currentProcessName: String,
        /** Provider 返回的最新 Shizuku 完整进程名。 */
        override val expectedProcessName: String,
        /** Provider 返回的结果码，正常应为 ok。 */
        override val resultCode: String?,
        /** Provider 是否已请求 app 侧尝试绑定最新 Shizuku 进程。 */
        override val connectRequested: Boolean?,
        /** Provider 或连接器返回的跳过 bind 原因。 */
        override val connectSkipReason: String?,
    ) : ShizukuProcessIdentityDecision() {
        /** 身份匹配原因码，固定用于日志聚合。 */
        override val reasonCode: String = ClipboardBridgeContract.REASON_PROCESS_MATCHED
    }

    /** 当前进程名和期望进程名明确不一致，应通过 destroy() 退出旧进程。 */
    data class Mismatched(
        /** 当前 Shizuku 进程完整名称。 */
        override val currentProcessName: String,
        /** Provider 返回的最新 Shizuku 完整进程名。 */
        override val expectedProcessName: String,
        /** Provider 返回的结果码，正常应为 ok。 */
        override val resultCode: String?,
        /** Provider 是否已请求 app 侧尝试绑定最新 Shizuku 进程。 */
        override val connectRequested: Boolean?,
        /** Provider 或连接器返回的跳过 bind 原因。 */
        override val connectSkipReason: String?,
    ) : ShizukuProcessIdentityDecision() {
        /** 身份不匹配原因码，固定用于日志聚合。 */
        override val reasonCode: String = ClipboardBridgeContract.REASON_PROCESS_MISMATCHED
    }

    /** 身份不确定，只跳过本次提交，不杀死当前进程。 */
    data class Unknown(
        /** 当前 Shizuku 进程完整名称；为空表示本地读取失败。 */
        override val currentProcessName: String?,
        /** Provider 返回的最新 Shizuku 完整进程名；为空表示 app 侧没有可用期望值。 */
        override val expectedProcessName: String?,
        /** Provider 返回的结果码；为空表示命令没有可解析结果。 */
        override val resultCode: String?,
        /** 身份不确定原因码。 */
        override val reasonCode: String,
        /** Provider 是否已请求 app 侧尝试绑定最新 Shizuku 进程。 */
        override val connectRequested: Boolean?,
        /** Provider 或连接器返回的跳过 bind 原因。 */
        override val connectSkipReason: String?,
    ) : ShizukuProcessIdentityDecision()
}

/** 读取 `/proc/self/cmdline` 中的完整进程名，失败时返回 null 并交给调用方按身份不确定处理。 */
private fun readCurrentProcessName(): String? {
    return runCatching {
        /** cmdline 原始字节；Linux 以 NUL 结尾，可能包含后续空字节。 */
        val cmdlineBytes = File("/proc/self/cmdline").readBytes()
        /** 首个 NUL 字节位置；不存在时使用完整字节数组。 */
        val endIndex = cmdlineBytes.indexOf(0.toByte()).takeIf { it >= 0 } ?: cmdlineBytes.size
        /** 当前进程名文本；为空时交给身份协作者跳过提交。 */
        val processName = cmdlineBytes.copyOfRange(0, endIndex).toString(Charsets.UTF_8).trim()
        processName.takeIf { it.isNotBlank() }
    }.getOrNull()
}
