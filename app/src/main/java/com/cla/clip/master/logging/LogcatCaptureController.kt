package com.cla.clip.master.logging

import android.os.Process
import com.cla.clip.base.general.config.logging.LogSettingsRepository
import com.cla.clip.base.general.utils.ApplicationScope
import com.cla.clip.base.general.utils.setLogCaptureDiagnosticsEnabled
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** 瞬时录制状态仅用于 UI，稳定 code 不依赖混淆后的类型名或枚举 name。 */
internal data class LogCaptureState(
    /** stopped/preparing/recording/failed；不进入备份。 */
    val phase: String = "stopped",
    /** 固定故障分类，不能传入 stderr 或文件路径原文。 */
    val reason: String = "",
)

/** 主进程观察开关并管理一个原生 logcat，stdout 业务日志不经应用队列/转存。 */
@Singleton
internal class LogcatCaptureController @Inject constructor(
    /** 开关唯一已提交来源。 */
    private val settings: LogSettingsRepository,
    /** 固定路径与归属准备，不承担正文复制。 */
    private val files: LogDirectoryStore,
    /** 与页面分离的应用作用域，主进程存活期间响应持久化开关。 */
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** 防止 Application 或测试重复初始化两套观察。 */
    private val started = AtomicBoolean(false)
    /** 可见性由页面决定是否订阅，但采集状态独立于页面。 */
    private val mutableState = MutableStateFlow(LogCaptureState())
    /** UI 只观察，不拥有进程或切换状态。 */
    val state = mutableState.asStateFlow()

    /** 主进程启动调用一次，设置读取错误显式失败，不以关闭掩盖故障。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                settings.enabled.collectLatest { enabled ->
                    if (enabled) capture() else mutableState.value = LogCaptureState()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = LogCaptureState("failed", "log_settings_failed")
                logW("LogCapture") { "日志设置观察失败 reasonCode=log_settings_failed type=${failure.javaClass.simpleName}" }
            } finally { started.set(false) }
        }
    }

    /** collectLatest 先取消并等待旧会话释放，再响应新的已提交开关。 */
    private suspend fun capture() = coroutineScope {
        /** 只持有当前会话进程，失败/取消都在 finally 销毁。 */
        var process: java.lang.Process? = null
        try {
            mutableState.value = LogCaptureState("preparing")
            /** 固定目标必须先通过本安装归属校验。 */
            val target = files.prepare()
            /** 起点是本次许可，不无限回读启用前历史。 */
            val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())
            currentCoroutineContext().ensureActive()
            setLogCaptureDiagnosticsEnabled(true)
            /** 直接参数列表，不经过 shell，父应用 PID 防止采子进程自身日志。 */
            val active = ProcessBuilder(LogCapturePolicy.command(Process.myPid(), target.path, since)).start()
            process = active
            mutableState.value = LogCaptureState("recording")
            logI("LogCapture") { "日志采集开始 pid=${Process.myPid()} rotateKiB=10240 historyCount=20" }
            /** 等待与 stderr/取消释放由可测试会话负责，不能堵住开关切换。 */
            val result = LogcatProcessSession.await(active)
            currentCoroutineContext().ensureActive()
            /** 非预期退出与 stderr 故障都显式反馈，不无限重新创建采集进程。 */
            val reason = result.reason ?: "log_process_exited"
            mutableState.value = LogCaptureState("failed", reason)
            logW("LogCapture") { "日志采集结束 reasonCode=$reason exitCode=${result.exitCode}" }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.value = LogCaptureState("failed", "log_storage_failed")
            logW("LogCapture") { "日志保存启动失败 reasonCode=log_storage_failed type=${failure.javaClass.simpleName}" }
        } finally {
            // 停止记录只输出固定状态；结束瞬间不保证此条已落盘，不能充当完整审计证据。
            if (process != null) logI("LogCapture") { "日志采集停止 reasonCode=log_session_finished" }
            setLogCaptureDiagnosticsEnabled(false)
            process?.destroy()
            runCatching { process?.errorStream?.close() }
            runCatching { process?.inputStream?.close() }
            runCatching { process?.outputStream?.close() }
        }
    }
}
