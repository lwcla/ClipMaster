package com.cla.clip.shizuku

import android.graphics.Bitmap
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logW
import com.cla.clip.base.general.utils.toByteArray

/**
 * Shizuku 进程调用主进程 ClipboardBridgeProvider 的命令客户端。
 *
 * 负责构造 `content call/write` 命令、传输 JSON/PNG stdin、执行超时保护和返回低敏命令结果。
 *
 * @param packageName 当前应用包名，用于构造 Provider authority。
 * @param shellCommandRunner shell 命令执行器，测试可替换为 fake。
 * @param clipCommandTimeoutMillis 剪贴 payload 和身份查询命令超时时间。
 * @param iconCommandTimeoutMillis 图标查询、写入和提交命令超时时间。
 */
internal class ClipboardBridgeProviderCommandClient(
    private val packageName: String,
    private val shellCommandRunner: ShizukuShellCommandExecutor = ShizukuShellCommandRunner(),
    private val clipCommandTimeoutMillis: Long = DEFAULT_PROVIDER_CLIP_COMMAND_TIMEOUT_MS,
    private val iconCommandTimeoutMillis: Long = DEFAULT_PROVIDER_ICON_COMMAND_TIMEOUT_MS,
) {

    /**
     * 查询主进程当前期望的 Shizuku 完整进程名。
     *
     * @param eventId 当前剪贴事件 ID，用于串联身份查询日志。
     */
    fun queryShizukuProcess(eventId: String): ProviderCommandResult {
        /** 身份查询命令参数；只传 eventId，不携带剪贴正文。 */
        val args = contentCallArgs(
            method = ClipboardBridgeContract.METHOD_QUERY_SHIZUKU_PROCESS,
            eventId = eventId
        )
        /** 命令执行结果；超时按身份不确定处理。 */
        val result = shellCommandRunner.run(args, clipCommandTimeoutMillis)
        if (result.timedOut) {
            logW(ClipboardShizukuService.TAG) { "Provider query_shizuku_process 超时 eventId=$eventId" }
        }
        logD(ClipboardShizukuService.TAG) {
            "Provider query_shizuku_process eventId=$eventId exit=${result.exitCode} output=${result.output}"
        }
        return result.toProviderCommandResult()
    }

    /**
     * 查询当前来源图标是否需要同步。
     *
     * @param eventId 当前剪贴事件 ID。
     * @param clipPackageName 来源应用包名。
     * @param appName 来源应用名称。
     * @param iconHash 来源图标 hash。
     */
    fun queryIconState(
        eventId: String,
        clipPackageName: String?,
        appName: String?,
        iconHash: String?,
    ): ProviderCommandResult {
        /** 图标预判命令参数；只传低敏小字段，避免图标内容进入命令行。 */
        val args = contentCallArgs(
            method = ClipboardBridgeContract.METHOD_QUERY_ICON_STATE,
            eventId = eventId,
            clipPackageName = clipPackageName,
            appName = appName,
            iconHash = iconHash
        )
        /** 命令执行结果；超时只影响图标链路，不影响剪贴 payload。 */
        val result = shellCommandRunner.run(args, iconCommandTimeoutMillis)
        if (result.timedOut) {
            logW(ClipboardShizukuService.TAG) {
                "Provider query_icon_state 超时 eventId=$eventId packageName=$clipPackageName"
            }
        }
        logD(ClipboardShizukuService.TAG) {
            "Provider query_icon_state eventId=$eventId exit=${result.exitCode} output=${result.output}"
        }
        return result.toProviderCommandResult()
    }

    /**
     * 写入剪贴 payload JSON。
     *
     * @param eventId 当前剪贴事件 ID。
     * @param payloadJson 已序列化的 payload JSON；日志中只能记录字节长度，不能输出正文。
     */
    fun writeClipPayload(eventId: String, payloadJson: String): Boolean {
        /** payload UTF-8 字节；不设置低于系统剪贴板能力的自定义上限。 */
        val payloadBytes = payloadJson.toByteArray(Charsets.UTF_8)
        /** content write 执行结果；stdin 写入失败或命令超时都按失败处理。 */
        val result = shellCommandRunner.runWithStdin(
            args = listOf(
                "content",
                "write",
                "--uri",
                ClipboardBridgeContract.clipUri(packageName, eventId)
            ),
            stdinBytes = payloadBytes,
            timeoutMillis = clipCommandTimeoutMillis
        )
        /** payload 写入是否成功；只有命令成功才允许后续 commit_clip 消费 eventId 临时文件。 */
        val ok = result.exitCode == 0 && !result.output.contains("Error", ignoreCase = true)
        if (result.timedOut) {
            logW(ClipboardShizukuService.TAG) {
                "Provider 剪贴 payload 写入超时 eventId=$eventId size=${payloadBytes.size}"
            }
        }
        logD(ClipboardShizukuService.TAG) {
            "Provider 剪贴 payload 写入 eventId=$eventId size=${payloadBytes.size} exit=${result.exitCode} ok=$ok output=${result.output}"
        }
        return ok
    }

    /**
     * 提交已写入的剪贴 payload。
     *
     * @param eventId 当前剪贴事件 ID。
     * @param clipPackageName 来源应用包名。
     * @param appName 来源应用名称。
     * @param iconHash 来源图标 hash。
     */
    fun commitClip(
        eventId: String,
        clipPackageName: String?,
        appName: String?,
        iconHash: String?,
    ): ProviderCommandResult {
        /** commit_clip 命令参数；正文已经通过 content write 写入临时文件。 */
        val args = contentCallArgs(
            method = ClipboardBridgeContract.METHOD_COMMIT_CLIP,
            eventId = eventId,
            clipPackageName = clipPackageName,
            appName = appName,
            iconHash = iconHash
        )
        /** 命令执行结果；超时按 payload 未确认处理。 */
        val result = shellCommandRunner.run(args, clipCommandTimeoutMillis)
        if (result.timedOut) {
            logW(ClipboardShizukuService.TAG) { "Provider commit_clip 超时 eventId=$eventId packageName=$clipPackageName" }
        }
        logD(ClipboardShizukuService.TAG) {
            "Provider commit_clip eventId=$eventId exit=${result.exitCode} output=${result.output}"
        }
        return result.toProviderCommandResult()
    }

    /**
     * 写入来源应用图标 PNG。
     *
     * @param eventId 当前剪贴事件 ID。
     * @param bitmap Shizuku 侧 PackageManager 解析得到的小尺寸图标。
     */
    fun writeIcon(eventId: String, bitmap: Bitmap): Boolean {
        /** 图标 PNG 字节；当前图标较小，适合通过 stdin 流式写入。 */
        val iconBytes = bitmap.toByteArray()
        /** content write 执行结果；图标内容不进入命令行参数。 */
        val result = shellCommandRunner.runWithStdin(
            args = listOf(
                "content",
                "write",
                "--uri",
                ClipboardBridgeContract.iconUri(packageName, eventId)
            ),
            stdinBytes = iconBytes,
            timeoutMillis = iconCommandTimeoutMillis
        )
        /** 图标写入是否成功；失败时等待下一次事件自然重试。 */
        val ok = result.exitCode == 0 && !result.output.contains("Error", ignoreCase = true)
        if (result.timedOut) {
            logW(ClipboardShizukuService.TAG) { "Provider 图标写入超时 eventId=$eventId size=${iconBytes.size}" }
        }
        logD(ClipboardShizukuService.TAG) {
            "Provider 图标写入 eventId=$eventId size=${iconBytes.size} exit=${result.exitCode} ok=$ok output=${result.output}"
        }
        return ok
    }

    /**
     * 提交已写入的来源图标。
     *
     * @param eventId 当前剪贴事件 ID。
     * @param clipPackageName 来源应用包名。
     * @param appName 来源应用名称。
     * @param iconHash 来源图标 hash。
     */
    fun commitIcon(
        eventId: String,
        clipPackageName: String?,
        appName: String?,
        iconHash: String?,
    ): ProviderCommandResult {
        /** commit_icon 命令参数；只传图标元信息，不传 PNG 内容。 */
        val args = contentCallArgs(
            method = ClipboardBridgeContract.METHOD_COMMIT_ICON,
            eventId = eventId,
            clipPackageName = clipPackageName,
            appName = appName,
            iconHash = iconHash
        )
        /** 命令执行结果；超时按图标补全失败处理。 */
        val result = shellCommandRunner.run(args, iconCommandTimeoutMillis)
        if (result.timedOut) {
            logW(ClipboardShizukuService.TAG) { "Provider commit_icon 超时 eventId=$eventId packageName=$clipPackageName" }
        }
        logD(ClipboardShizukuService.TAG) {
            "Provider commit_icon eventId=$eventId exit=${result.exitCode} output=${result.output}"
        }
        return result.toProviderCommandResult()
    }

    /**
     * 构造 `content call` 命令参数。
     *
     * @param method Provider method 名称。
     * @param eventId 当前事件 ID。
     * @param clipPackageName 来源应用包名。
     * @param appName 来源应用名称。
     * @param iconHash 来源图标 hash。
     */
    internal fun contentCallArgs(
        method: String,
        eventId: String,
        clipPackageName: String? = null,
        appName: String? = null,
        iconHash: String? = null,
    ): List<String> {
        /** content call 命令参数，只承载 Provider 协议小字段。 */
        val args = mutableListOf(
            "content",
            "call",
            "--uri",
            ClipboardBridgeContract.callUri(packageName),
            "--method",
            method,
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

        return args
    }

    /**
     * 转义 Android `content` 命令 extra 参数中的冒号和反斜杠。
     *
     * @param value 待作为 `key:type:value` 中 value 部分的小字段。
     */
    internal fun escapeContentArg(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace(":", "\\:")
    }

    /** shell 命令结果转 Provider 命令结果，只暴露 Provider parser 需要的字段。 */
    private fun ShizukuShellCommandResult.toProviderCommandResult(): ProviderCommandResult {
        return ProviderCommandResult(exitCode = exitCode, output = output)
    }

    companion object {
        /** Provider 图标写入和提交命令默认超时时间，避免 Shizuku 图标任务长期挂起。 */
        private const val DEFAULT_PROVIDER_ICON_COMMAND_TIMEOUT_MS = 3_000L

        /** Provider 剪贴 payload 写入和提交命令默认超时时间，避免 Shizuku 读取任务长期挂起。 */
        private const val DEFAULT_PROVIDER_CLIP_COMMAND_TIMEOUT_MS = 3_000L
    }
}

/**
 * Shizuku 执行 Provider 命令后的结果。
 *
 * @param exitCode 命令进程退出码。
 * @param output 标准输出和错误输出合并后的文本。
 */
internal data class ProviderCommandResult(
    val exitCode: Int,
    val output: String,
)
