package com.cla.clip.shizuku

import android.os.Build
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Shizuku 进程内专用 shell 命令执行器。
 *
 * 只服务当前 `content` 与 `am` 命令链路，统一处理 stdout/stderr 合并、stdin 写入、超时销毁和脱敏结果返回。
 *
 * @param processStarter shell 进程启动器，生产环境使用 `ProcessBuilder`，测试可替换为内存 fake。
 * @param timeoutProcessDestroyer 超时进程销毁器，测试可替换以断言销毁动作。
 */
internal class ShizukuShellCommandRunner(
    private val processStarter: ShellProcessStarter = ProcessBuilderShellProcessStarter(),
    private val timeoutProcessDestroyer: TimeoutProcessDestroyer = AndroidTimeoutProcessDestroyer(),
) : ShizukuShellCommandExecutor {

    /**
     * 执行不需要 stdin 的 shell 命令。
     *
     * @param args 命令及参数，调用方必须只传低敏小字段，不包含剪贴正文。
     * @param timeoutMillis 最长等待时间，单位毫秒。
     * @param timeoutOutput 超时时写入结果的低敏输出文本。
     */
    override fun run(
        args: List<String>,
        timeoutMillis: Long,
        timeoutOutput: String,
    ): ShizukuShellCommandResult {
        /** shell 进程句柄；启动失败由调用方外层异常处理或测试直接暴露。 */
        val process = processStarter.start(args)
        return waitForShellProcess(process, timeoutMillis, timeoutOutput)
    }

    /**
     * 执行需要通过 stdin 写入二进制或 JSON payload 的 shell 命令。
     *
     * @param args 命令及参数，payload 必须通过 `stdinBytes` 写入，不能拼入命令参数。
     * @param stdinBytes 待写入进程 stdin 的字节内容，日志只能记录长度。
     * @param timeoutMillis 最长等待时间，单位毫秒。
     * @param timeoutOutput 超时时写入结果的低敏输出文本。
     */
    override fun runWithStdin(
        args: List<String>,
        stdinBytes: ByteArray,
        timeoutMillis: Long,
        timeoutOutput: String,
    ): ShizukuShellCommandResult {
        /** shell 进程句柄；stdin 写入失败时会按命令失败返回。 */
        val process = processStarter.start(args)
        return runCatching {
            process.outputStream.use { outputStream: OutputStream ->
                outputStream.write(stdinBytes)
            }
        }.fold(
            onSuccess = {
                waitForShellProcess(process, timeoutMillis, timeoutOutput)
            },
            onFailure = { throwable ->
                process.destroy()
                ShizukuShellCommandResult(
                    exitCode = -1,
                    output = "stdin_write_failed:${throwable::class.java.simpleName.ifBlank { "Throwable" }}",
                    timedOut = false
                )
            }
        )
    }

    /**
     * 等待 shell 进程完成并读取输出。
     *
     * @param process 已启动的 shell 进程。
     * @param timeoutMillis 最长等待时间，单位毫秒。
     * @param timeoutOutput 超时时写入结果的低敏输出文本。
     */
    private fun waitForShellProcess(
        process: ShellProcess,
        timeoutMillis: Long,
        timeoutOutput: String,
    ): ShizukuShellCommandResult {
        /** 单命令等待线程；避免阻塞式 `Process.waitFor()` 让协程超时失效。 */
        val executor = Executors.newSingleThreadExecutor()
        /** 等待进程退出的 future；超时后会销毁进程并中断等待线程。 */
        val waitFuture = executor.submit<Int> { process.waitFor() }
        /** 命令退出码；为空表示命令超过等待上限。 */
        val exitCode = try {
            waitFuture.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } finally {
            executor.shutdownNow()
        }
        if (exitCode == null) {
            timeoutProcessDestroyer.destroy(process)
            waitFuture.cancel(true)
            return ShizukuShellCommandResult(exitCode = -1, output = timeoutOutput, timedOut = true)
        }

        /** 命令输出；生产进程已合并错误流，测试 fake 可直接返回合成文本。 */
        val output = process.inputText()
        return ShizukuShellCommandResult(exitCode = exitCode, output = output, timedOut = false)
    }

}

/** Shizuku shell 命令执行接口，便于 Provider 客户端和唤醒命令 runner 使用 fake 测试。 */
internal interface ShizukuShellCommandExecutor {
    /**
     * 执行不需要 stdin 的 shell 命令。
     *
     * @param args 命令及参数，调用方负责保持参数低敏。
     * @param timeoutMillis 最长等待时间，单位毫秒。
     * @param timeoutOutput 超时时返回的低敏输出文本。
     */
    fun run(args: List<String>, timeoutMillis: Long, timeoutOutput: String = "timeout"): ShizukuShellCommandResult

    /**
     * 执行需要 stdin 的 shell 命令。
     *
     * @param args 命令及参数，调用方负责保持参数低敏。
     * @param stdinBytes 待写入 stdin 的字节内容。
     * @param timeoutMillis 最长等待时间，单位毫秒。
     * @param timeoutOutput 超时时返回的低敏输出文本。
     */
    fun runWithStdin(
        args: List<String>,
        stdinBytes: ByteArray,
        timeoutMillis: Long,
        timeoutOutput: String = "timeout",
    ): ShizukuShellCommandResult
}

/**
 * shell 命令执行结果。
 *
 * @param exitCode 命令退出码；超时或 stdin 写入失败时固定为 -1。
 * @param output stdout/stderr 合并后的输出文本；不得包含剪贴正文、HTML 原文或授权 URI。
 * @param timedOut true 表示命令超过调用方设置的等待上限并已触发销毁。
 */
internal data class ShizukuShellCommandResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
)

/**
 * shell 进程启动器。
 *
 * @param args 命令及参数，调用方负责保证参数不携带敏感正文。
 */
internal fun interface ShellProcessStarter {
    fun start(args: List<String>): ShellProcess
}

/** 可等待和销毁的 shell 进程抽象，便于 JVM 单元测试覆盖超时和 stdin 写入失败。 */
internal interface ShellProcess {
    /** 进程 stdin，用于 `content write` 传输 JSON 或 PNG。 */
    val outputStream: OutputStream

    /** 等待进程完成并返回退出码。 */
    fun waitFor(): Int

    /** 读取 stdout/stderr 合并输出。 */
    fun inputText(): String

    /** 常规销毁进程。 */
    fun destroy()

    /** 强制销毁进程，Android 8 及以上优先使用。 */
    fun destroyForcibly()
}

/** 生产环境 shell 进程启动器，统一开启错误流合并。 */
private class ProcessBuilderShellProcessStarter : ShellProcessStarter {
    override fun start(args: List<String>): ShellProcess {
        /** 系统 shell 进程；错误流合并后便于 Provider result parser 统一处理。 */
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        return ProcessBackedShellProcess(process)
    }
}

/** 真实 `Process` 的轻量适配器。 */
private class ProcessBackedShellProcess(
    /** Android Runtime 返回的真实进程对象。 */
    private val process: Process,
) : ShellProcess {
    override val outputStream: OutputStream
        get() = process.outputStream

    override fun waitFor(): Int {
        return process.waitFor()
    }

    override fun inputText(): String {
        return process.inputStream.bufferedReader().use { reader -> reader.readText() }
    }

    override fun destroy() {
        process.destroy()
    }

    override fun destroyForcibly() {
        // Android 8 以下没有强制销毁 API；即使调用方遗漏版本门禁，也只能退回常规销毁来避免 minSdk 24 崩溃。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process.destroyForcibly()
        } else {
            process.destroy()
        }
    }
}

/** 超时进程销毁器，按 Android 版本选择强制销毁能力。 */
internal fun interface TimeoutProcessDestroyer {
    fun destroy(process: ShellProcess)
}

/** Android 平台上的超时销毁实现。 */
private class AndroidTimeoutProcessDestroyer : TimeoutProcessDestroyer {
    override fun destroy(process: ShellProcess) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process.destroyForcibly()
        } else {
            process.destroy()
        }
    }
}
