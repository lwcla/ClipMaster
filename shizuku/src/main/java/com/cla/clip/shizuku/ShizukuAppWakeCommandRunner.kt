package com.cla.clip.shizuku

import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logW

/**
 * Shizuku 进程唤醒 app 主进程的 shell 命令执行器。
 *
 * 只使用 NoDisplay Activity 拉起主进程；剪贴读取已经改为 Shizuku 进程直读，不再依赖 app 前台服务。
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
     * 只启动 NoDisplay Activity，不携带剪贴正文或来源应用信息。
     */
    fun wakeAppProcess(): AppWakeCommandResult {
        return startWakeActivityCommand()
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
        /** NoDisplay Activity 唤醒命令等待上限，避免厂商 ROM 卡住 am 命令拖住剪贴事件。 */
        private const val DEFAULT_APP_WAKE_COMMAND_TIMEOUT_MS = 2_000L

        /** 主进程 NoDisplay 唤醒 Activity 完整类名；shell `am start` 通过它冷启动 app 主进程。 */
        private const val SHIZUKU_WAKE_ACTIVITY_CLASS_NAME = "com.cla.clip.master.wake.ShizukuWakeActivity"
    }
}
