package com.cla.clip.master.logging

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference

/** 只保留退出状态与固定 stderr 分类，不把命令原文带到 UI/公共日志。 */
internal data class LogcatProcessExit(
    /** 子进程真实退出码，不代表此前所有轮转成功。 */ val exitCode: Int,
    /** 监测到的固定失败分类；正常退出时由控制器补意外退出原因。 */ val reason: String?,
)

/** 可取消的原生会话等待；不读取 stdout 或转存任何业务日志。 */
internal object LogcatProcessSession {
    /** 先销毁并关闭阻塞流，再等待 stderr 任务取消完成，避免旧会话阻塞开关切换。 */
    suspend fun await(process: Process): LogcatProcessExit = coroutineScope {
        /** 原生 rename 失败可能不主动退出，第一条报错决定终止原因。 */
        val reason = AtomicReference<String?>(null)
        /** 仅监控 stderr；正文文件仍由 logcat 自行写入和轮转。 */
        val errors = launch(Dispatchers.IO) {
            process.errorStream.bufferedReader().use { reader ->
                /** 原文仅在分类期间短暂使用，不能返回或打印。 */
                var line = reader.readLine()
                while (line != null) {
                    if (line.isNotBlank()) {
                        reason.set(LogCapturePolicy.stderrReason(line))
                        process.destroy()
                        break
                    }
                    line = reader.readLine()
                }
            }
        }
        try {
            /** 等待可以被协程取消打断，关闭开关不等到日志自然结束。 */
            val exitCode = runInterruptible(Dispatchers.IO) { process.waitFor() }
            errors.join()
            currentCoroutineContext().ensureActive()
            LogcatProcessExit(exitCode, reason.get())
        } finally {
            process.destroy()
            // close 在 cancelAndJoin 之前解除不可中断的 readLine，不持有 BufferedReader 的读取锁。
            runCatching { process.errorStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            withContext(NonCancellable) { errors.cancelAndJoin() }
        }
    }
}
