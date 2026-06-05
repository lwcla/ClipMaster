package com.cla.clip.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Provider 命令客户端测试，保护命令构造、参数转义和超时分层。 */
class ClipboardBridgeProviderCommandClientTest {

    @Test
    /** content extra 参数必须转义冒号和反斜杠，避免破坏 `key:type:value` 格式。 */
    fun contentCallArgsEscapesColonAndBackslash() {
        /** fake shell 执行器；本测试只检查参数构造，不实际执行。 */
        val executor = RecordingShellExecutor()
        /** 待测 Provider 命令客户端。 */
        val client = client(executor)

        /** 构造出的 content call 参数。 */
        val args = client.contentCallArgs(
            method = ClipboardBridgeContract.METHOD_COMMIT_CLIP,
            eventId = "event",
            clipPackageName = "com.demo:source",
            appName = "Demo\\Name:One",
            iconHash = "hash:value"
        )

        assertTrue(args.contains("${ClipboardBridgeContract.EXTRA_PACKAGE_NAME}:s:com.demo\\:source"))
        assertTrue(args.contains("${ClipboardBridgeContract.EXTRA_APP_NAME}:s:Demo\\\\Name\\:One"))
        assertTrue(args.contains("${ClipboardBridgeContract.EXTRA_ICON_HASH}:s:hash\\:value"))
    }

    @Test
    /** query_icon_state 必须使用图标命令超时，超时时只返回低敏 timeout 结果。 */
    fun queryIconStateUsesIconTimeout() {
        /** fake shell 执行器，模拟图标预判命令超时。 */
        val executor = RecordingShellExecutor(
            runResult = ShizukuShellCommandResult(exitCode = -1, output = "timeout", timedOut = true)
        )
        /** 待测 Provider 命令客户端。 */
        val client = client(executor, clipTimeout = 11L, iconTimeout = 7L)

        /** Provider 命令结果。 */
        val result = client.queryIconState("event", "pkg", "App", "hash")

        assertEquals(7L, executor.lastRunTimeout)
        assertEquals(-1, result.exitCode)
        assertEquals("timeout", result.output)
        assertTrue(executor.lastRunArgs.orEmpty().contains(ClipboardBridgeContract.METHOD_QUERY_ICON_STATE))
    }

    @Test
    /** commit_clip 必须使用剪贴命令超时，并保持 Provider 输出原样交给 parser。 */
    fun commitClipUsesClipTimeout() {
        /** fake shell 执行器，模拟 commit_clip 成功输出。 */
        val executor = RecordingShellExecutor(
            runResult = ShizukuShellCommandResult(
                exitCode = 0,
                output = "Result: Bundle[{resultCode=ok, clipStatus=saved}]",
                timedOut = false
            )
        )
        /** 待测 Provider 命令客户端。 */
        val client = client(executor, clipTimeout = 13L, iconTimeout = 7L)

        /** Provider 命令结果。 */
        val result = client.commitClip("event", "pkg", "App", "hash")

        assertEquals(13L, executor.lastRunTimeout)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("clipStatus=saved"))
        assertTrue(executor.lastRunArgs.orEmpty().contains(ClipboardBridgeContract.METHOD_COMMIT_CLIP))
    }

    @Test
    /** writeClipPayload 应通过 stdin 写入 payload，并在命令成功时返回 true。 */
    fun writeClipPayloadWritesStdinAndReturnsTrue() {
        /** fake shell 执行器，模拟 content write 成功。 */
        val executor = RecordingShellExecutor(
            stdinResult = ShizukuShellCommandResult(exitCode = 0, output = "", timedOut = false)
        )
        /** 待测 Provider 命令客户端。 */
        val client = client(executor, clipTimeout = 13L, iconTimeout = 7L)

        /** 写入结果。 */
        val ok = client.writeClipPayload("event", """{"version":1}""")

        assertTrue(ok)
        assertEquals(13L, executor.lastStdinTimeout)
        assertEquals("""{"version":1}""", executor.lastStdinBytes?.toString(Charsets.UTF_8))
        assertTrue(executor.lastStdinArgs.orEmpty().contains(ClipboardBridgeContract.clipUri("com.cla.clip.master", "event")))
    }

    @Test
    /** writeClipPayload 遇到命令 Error 输出时不能继续 commit_clip。 */
    fun writeClipPayloadRejectsCommandError() {
        /** fake shell 执行器，模拟 content write 输出 Error。 */
        val executor = RecordingShellExecutor(
            stdinResult = ShizukuShellCommandResult(exitCode = 0, output = "Error while writing", timedOut = false)
        )
        /** 待测 Provider 命令客户端。 */
        val client = client(executor)

        /** 写入结果。 */
        val ok = client.writeClipPayload("event", """{"version":1}""")

        assertFalse(ok)
    }

    @Test
    /** commit_icon 必须使用图标命令超时。 */
    fun commitIconUsesIconTimeout() {
        /** fake shell 执行器，模拟 commit_icon 输出。 */
        val executor = RecordingShellExecutor(
            runResult = ShizukuShellCommandResult(exitCode = 0, output = "Result: Bundle[{resultCode=ok}]", timedOut = false)
        )
        /** 待测 Provider 命令客户端。 */
        val client = client(executor, clipTimeout = 13L, iconTimeout = 7L)

        /** Provider 命令结果。 */
        client.commitIcon("event", "pkg", "App", "hash")

        assertEquals(7L, executor.lastRunTimeout)
        assertTrue(executor.lastRunArgs.orEmpty().contains(ClipboardBridgeContract.METHOD_COMMIT_ICON))
    }

    /**
     * 构造待测 Provider 命令客户端。
     *
     * @param executor fake shell 执行器。
     * @param clipTimeout 剪贴命令超时时间。
     * @param iconTimeout 图标命令超时时间。
     */
    private fun client(
        executor: RecordingShellExecutor,
        clipTimeout: Long = 11L,
        iconTimeout: Long = 7L,
    ): ClipboardBridgeProviderCommandClient {
        return ClipboardBridgeProviderCommandClient(
            packageName = "com.cla.clip.master",
            shellCommandRunner = executor,
            clipCommandTimeoutMillis = clipTimeout,
            iconCommandTimeoutMillis = iconTimeout
        )
    }

    /** 记录调用参数的 fake shell 执行器。 */
    private class RecordingShellExecutor(
        /** 普通命令返回结果。 */
        private val runResult: ShizukuShellCommandResult = ShizukuShellCommandResult(0, "", false),
        /** stdin 命令返回结果。 */
        private val stdinResult: ShizukuShellCommandResult = ShizukuShellCommandResult(0, "", false),
    ) : ShizukuShellCommandExecutor {
        /** 最近一次普通命令参数。 */
        var lastRunArgs: List<String>? = null
        /** 最近一次普通命令超时时间。 */
        var lastRunTimeout: Long? = null
        /** 最近一次 stdin 命令参数。 */
        var lastStdinArgs: List<String>? = null
        /** 最近一次 stdin 命令字节。 */
        var lastStdinBytes: ByteArray? = null
        /** 最近一次 stdin 命令超时时间。 */
        var lastStdinTimeout: Long? = null

        override fun run(args: List<String>, timeoutMillis: Long, timeoutOutput: String): ShizukuShellCommandResult {
            lastRunArgs = args
            lastRunTimeout = timeoutMillis
            return runResult
        }

        override fun runWithStdin(
            args: List<String>,
            stdinBytes: ByteArray,
            timeoutMillis: Long,
            timeoutOutput: String,
        ): ShizukuShellCommandResult {
            lastStdinArgs = args
            lastStdinBytes = stdinBytes
            lastStdinTimeout = timeoutMillis
            return stdinResult
        }
    }
}
