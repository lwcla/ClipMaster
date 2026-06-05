package com.cla.clip.shizuku

import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logW

/**
 * Shizuku 进程唤醒 app 主进程的 shell 命令执行器。
 *
 * 优先使用前台服务唤醒；命令失败、超时或输出被 parser 判定失败时，再使用 NoDisplay Activity fallback。
 *
 * @param packageName 当前应用包名，用于构造 manifest component。
 * @param shellCommandRunner shell 命令执行器。
 * @param wakeCommandTimeoutMillis 单次 `am` 命令等待上限。
 */
internal class ShizukuAppWakeCommandRunner(
    private val packageName: String,
    private val shellCommandRunner: ShizukuShellCommandExecutor = ShizukuShellCommandRunner(),
    private val wakeCommandTimeoutMillis: Long = DEFAULT_APP_WAKE_COMMAND_TIMEOUT_MS,
) {

    /**
     * 执行 app 主进程唤醒命令。
     *
     * 前台服务被系统接受时直接返回；否则尝试 NoDisplay Activity。
     */
    fun wakeAppProcess(): AppWakeCommandResult {
        /** 前台服务唤醒结果；它是常规路径，成功时不再打开 NoDisplay Activity。 */
        val foregroundResult = startForegroundServiceCommand()
        /** 前台服务命令是否已经被系统接受；集中 parser 兼容 ROM 退出码差异。 */
        val foregroundAccepted = !foregroundResult.timedOut &&
            ClipboardBridgeCommandResultParser.isStartForegroundServiceSuccessful(
                foregroundResult.exitCode,
                foregroundResult.output
            )
        if (foregroundAccepted) {
            return foregroundResult
        }

        logW(ClipboardShizukuService.TAG) {
            "start-foreground-service 唤醒失败，尝试 NoDisplay WakeActivity fallback " +
                "exit=${foregroundResult.exitCode} timedOut=${foregroundResult.timedOut}"
        }
        return startWakeActivityCommand()
    }

    /**
     * 执行前台服务唤醒命令并返回完整命令结果。
     *
     * 命令不带 `--user`，避免多用户设备上引入新的权限差异。
     */
    internal fun startForegroundServiceCommand(): AppWakeCommandResult {
        /** 前台服务 component；类名固定为主进程 manifest 声明的完整类名。 */
        val serviceComponent = "$packageName/$CLIPBOARD_SERVICE_CLASS_NAME"
        /** `am start-foreground-service` 命令参数；不传真实剪贴数据。 */
        val args = listOf(
            "am",
            "start-foreground-service",
            "-n",
            serviceComponent
        )
        /** 前台服务唤醒命令执行结果。 */
        val result = shellCommandRunner.run(args, wakeCommandTimeoutMillis)
        if (result.timedOut) {
            logW(ClipboardShizukuService.TAG) { "start-foreground-service 超时 timeoutMs=$wakeCommandTimeoutMillis" }
        }
        /** 前台服务命令是否被系统接受；集中 parser 兼容 ROM 退出码差异。 */
        val ok = ClipboardBridgeCommandResultParser.isStartForegroundServiceSuccessful(result.exitCode, result.output)
        logD(ClipboardShizukuService.TAG) { "start-foreground-service exit=${result.exitCode} ok=$ok output=${result.output}" }
        return AppWakeCommandResult(
            wakeMode = AppWakeMode.FOREGROUND_SERVICE,
            exitCode = result.exitCode,
            output = result.output,
            timedOut = result.timedOut
        )
    }

    /**
     * 执行 NoDisplay WakeActivity 唤醒命令并返回完整命令结果。
     *
     * 该入口只拉起 app 主进程和 Shizuku callback，不传真实剪贴数据。
     */
    internal fun startWakeActivityCommand(): AppWakeCommandResult {
        /** NoDisplay 唤醒 Activity component；类名固定为 app manifest 声明的完整类名。 */
        val activityComponent = "$packageName/$SHIZUKU_WAKE_ACTIVITY_CLASS_NAME"
        /** `am start` 命令参数；只打开 NoDisplay Activity，不携带剪贴板正文或来源应用信息。 */
        val args = listOf(
            "am",
            "start",
            "--activity-no-animation",
            "-n",
            activityComponent
        )
        /** NoDisplay Activity 唤醒命令执行结果。 */
        val result = shellCommandRunner.run(args, wakeCommandTimeoutMillis)
        if (result.timedOut) {
            logW(ClipboardShizukuService.TAG) { "wake-activity 超时 timeoutMs=$wakeCommandTimeoutMillis" }
        }
        /** NoDisplay Activity 命令是否被系统接受；集中 parser 判断 Error/Exception 和 Starting 输出。 */
        val ok = ClipboardBridgeCommandResultParser.isAppWakeCommandSuccessful(result.exitCode, result.output)
        logD(ClipboardShizukuService.TAG) { "wake-activity exit=${result.exitCode} ok=$ok output=${result.output}" }
        return AppWakeCommandResult(
            wakeMode = AppWakeMode.ACTIVITY_NO_DISPLAY,
            exitCode = result.exitCode,
            output = result.output,
            timedOut = result.timedOut
        )
    }

    companion object {
        /** 前台服务唤醒命令等待上限，避免厂商 ROM 卡住 am 命令拖住剪贴事件。 */
        private const val DEFAULT_APP_WAKE_COMMAND_TIMEOUT_MS = 2_000L

        /** 主进程剪贴板服务完整类名；shell `am` 唤醒使用完整 component，避免 ROM 对相对类名解析不一致。 */
        private const val CLIPBOARD_SERVICE_CLASS_NAME = "com.cla.clip.master.service.ClipboardService"

        /** 主进程 NoDisplay 唤醒 Activity 完整类名；前台服务命令失败时作为冷启动 fallback。 */
        private const val SHIZUKU_WAKE_ACTIVITY_CLASS_NAME = "com.cla.clip.master.wake.ShizukuWakeActivity"
    }
}
