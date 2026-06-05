package com.cla.clip.shizuku

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Shizuku shell 命令执行器测试，保护超时、stdin 写入和销毁契约。 */
class ShizukuShellCommandRunnerTest {

    @Test
    /** 命令正常结束时应返回 exitCode 与合并输出，并保留启动参数。 */
    fun runReturnsExitCodeAndOutput() {
        /** fake 进程，用于模拟 content call 正常输出。 */
        val process = FakeShellProcess(exitCode = 0, outputText = "Result: Bundle[{resultCode=ok}]")
        /** fake 启动器，用于记录命令参数。 */
        val starter = RecordingProcessStarter(process)
        /** 待测 shell 命令执行器。 */
        val runner = ShizukuShellCommandRunner(processStarter = starter, timeoutProcessDestroyer = RecordingDestroyer())

        /** 命令执行结果。 */
        val result = runner.run(listOf("content", "call"), timeoutMillis = 100L, timeoutOutput = "timeout")

        assertEquals(listOf("content", "call"), starter.startedArgs)
        assertEquals(0, result.exitCode)
        assertEquals("Result: Bundle[{resultCode=ok}]", result.output)
        assertFalse(result.timedOut)
    }

    @Test
    /** stdin payload 必须写入进程输出流，避免敏感正文拼到命令参数中。 */
    fun runWithStdinWritesPayloadBytes() {
        /** fake 进程，用于捕获 stdin 字节。 */
        val process = FakeShellProcess(exitCode = 0, outputText = "")
        /** 待测 shell 命令执行器。 */
        val runner = ShizukuShellCommandRunner(
            processStarter = RecordingProcessStarter(process),
            timeoutProcessDestroyer = RecordingDestroyer()
        )

        /** 命令执行结果。 */
        val result = runner.runWithStdin(
            args = listOf("content", "write"),
            stdinBytes = "payload".toByteArray(),
            timeoutMillis = 100L,
            timeoutOutput = "timeout"
        )

        assertEquals(0, result.exitCode)
        assertArrayEquals("payload".toByteArray(), process.writtenBytes())
    }

    @Test
    /** stdin 写入失败时应销毁进程并返回低敏失败摘要。 */
    fun runWithStdinDestroysProcessWhenWriteFails() {
        /** fake 进程，用于模拟 stdin 写入抛出 IOException。 */
        val process = FakeShellProcess(exitCode = 0, outputStream = ThrowingOutputStream())
        /** 待测 shell 命令执行器。 */
        val runner = ShizukuShellCommandRunner(
            processStarter = RecordingProcessStarter(process),
            timeoutProcessDestroyer = RecordingDestroyer()
        )

        /** 命令执行结果。 */
        val result = runner.runWithStdin(
            args = listOf("content", "write"),
            stdinBytes = byteArrayOf(1, 2),
            timeoutMillis = 100L,
            timeoutOutput = "timeout"
        )

        assertEquals(-1, result.exitCode)
        assertTrue(result.output.startsWith("stdin_write_failed"))
        assertTrue(process.destroyCalled)
        assertFalse(result.timedOut)
    }

    @Test
    /** 命令等待超时时应调用销毁器，并返回 timeout 低敏输出。 */
    fun runDestroysProcessWhenTimedOut() {
        /** fake 进程，用长等待模拟系统服务卡住。 */
        val process = FakeShellProcess(exitCode = 0, waitMillis = 5_000L)
        /** fake 销毁器，用于确认超时分支执行。 */
        val destroyer = RecordingDestroyer()
        /** 待测 shell 命令执行器。 */
        val runner = ShizukuShellCommandRunner(
            processStarter = RecordingProcessStarter(process),
            timeoutProcessDestroyer = destroyer
        )

        /** 命令执行结果。 */
        val result = runner.run(listOf("content", "call"), timeoutMillis = 1L, timeoutOutput = "timeout")

        assertEquals(-1, result.exitCode)
        assertEquals("timeout", result.output)
        assertTrue(result.timedOut)
        assertEquals(process, destroyer.destroyedProcess)
    }

    /** 记录启动参数的 fake 进程启动器。 */
    private class RecordingProcessStarter(
        /** 每次启动返回的 fake 进程。 */
        private val process: FakeShellProcess,
    ) : ShellProcessStarter {
        /** 最近一次启动参数。 */
        var startedArgs: List<String>? = null

        override fun start(args: List<String>): ShellProcess {
            startedArgs = args
            return process
        }
    }

    /** 记录超时销毁目标的 fake 销毁器。 */
    private class RecordingDestroyer : TimeoutProcessDestroyer {
        /** 被销毁的进程；为空表示未触发超时销毁。 */
        var destroyedProcess: ShellProcess? = null

        override fun destroy(process: ShellProcess) {
            destroyedProcess = process
            process.destroy()
        }
    }

    /** 测试用 shell 进程。 */
    private class FakeShellProcess(
        /** 等待完成后返回的退出码。 */
        private val exitCode: Int,
        /** 命令输出文本。 */
        private val outputText: String = "",
        /** waitFor 阻塞时长，单位毫秒。 */
        private val waitMillis: Long = 0L,
        /** stdin 输出流。 */
        override val outputStream: OutputStream = ByteArrayOutputStream(),
    ) : ShellProcess {
        /** 是否调用常规 destroy。 */
        var destroyCalled: Boolean = false

        override fun waitFor(): Int {
            if (waitMillis > 0L) {
                Thread.sleep(waitMillis)
            }
            return exitCode
        }

        override fun inputText(): String {
            return outputText
        }

        override fun destroy() {
            destroyCalled = true
        }

        override fun destroyForcibly() {
            destroyCalled = true
        }

        /** 返回已写入 stdin 的字节。 */
        fun writtenBytes(): ByteArray {
            return (outputStream as ByteArrayOutputStream).toByteArray()
        }
    }

    /** 写入时抛异常的输出流。 */
    private class ThrowingOutputStream : OutputStream() {
        override fun write(b: Int) {
            error("stdin failed")
        }
    }
}
