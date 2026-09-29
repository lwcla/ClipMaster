package com.cla.clip.master.provider

import com.cla.clip.master.utils.ShizukuConnectRequestResult
import com.cla.clip.shizuku.ClipboardBridgeContract
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Provider 身份查询纯流程测试，保护 expectedProcessName 刷新和连接请求返回契约。 */
class ClipboardBridgeShizukuProcessQueryHandlerTest {

    @Test
    /** 合法查询必须先写入最新完整进程名，再返回 ok、进程名和连接请求状态。 */
    fun queryWritesExpectedProcessNameAndReturnsOkResult() = runBlocking {
        /** 已提交到安装身份仓库的完整进程名替身。 */
        var writtenProcessName: String? = null
        /** 连接请求收到的原因码。 */
        var requestedReasonCode: String? = null
        /** 待测 Provider 身份查询处理器。 */
        val handler = ClipboardBridgeShizukuProcessQueryHandler(
            installIdProvider = { "001234" },
            processNameWriter = { processName -> writtenProcessName = processName },
            connectRequester = { reasonCode ->
                requestedReasonCode = reasonCode
                ShizukuConnectRequestResult(
                    requested = true,
                    expectedProcessName = writtenProcessName.orEmpty(),
                    reasonCode = reasonCode
                )
            },
            applicationId = "com.cla.clip.master",
            version = 10
        )

        /** Provider 身份查询结果。 */
        val result = handler.query(eventId = "event-1")

        assertEquals("com.cla.clip.master:shizuku_10_001234", writtenProcessName)
        assertEquals("com.cla.clip.master:shizuku_10_001234", result.shizukuProcessName)
        assertEquals(ClipboardBridgeContract.CODE_OK, result.resultCode)
        assertEquals(true, result.connectRequested)
        assertNull(result.connectSkipReason)
        assertEquals(ClipboardBridgeContract.REASON_IDENTITY_QUERY, result.reasonCode)
        assertEquals(ClipboardBridgeContract.REASON_IDENTITY_QUERY, requestedReasonCode)
    }

    @Test
    /** 连接请求异常时仍返回 expectedProcessName，让 Shizuku 侧可以完成身份比较。 */
    fun queryKeepsExpectedProcessNameWhenConnectRequestFails() = runBlocking {
        /** 已提交到安装身份仓库的完整进程名替身。 */
        var writtenProcessName: String? = null
        /** 待测 Provider 身份查询处理器。 */
        val handler = ClipboardBridgeShizukuProcessQueryHandler(
            installIdProvider = { "001234" },
            processNameWriter = { processName -> writtenProcessName = processName },
            connectRequester = { error("connect failed") },
            applicationId = "com.cla.clip.master",
            version = 10
        )

        /** Provider 身份查询结果。 */
        val result = handler.query(eventId = "event-2")

        assertEquals("com.cla.clip.master:shizuku_10_001234", writtenProcessName)
        assertEquals("com.cla.clip.master:shizuku_10_001234", result.shizukuProcessName)
        assertEquals(ClipboardBridgeContract.CODE_OK, result.resultCode)
        assertEquals(false, result.connectRequested)
        assertEquals(ClipboardBridgeContract.REASON_CONNECT_REQUEST_FAILED, result.connectSkipReason)
        assertEquals(ClipboardBridgeContract.REASON_IDENTITY_QUERY, result.reasonCode)
    }

    @Test
    /** 身份持久化失败时不得排队连接，也不得向旧进程返回未提交的期望进程名。 */
    fun queryDoesNotConnectWhenExpectedProcessNameCommitFails() = runBlocking {
        /** 连接请求次数；持久化失败时必须保持零。 */
        var connectRequests = 0
        /** 测试处理器；用合成身份制造持久化失败。 */
        val handler = ClipboardBridgeShizukuProcessQueryHandler(
            installIdProvider = { "001234" },
            processNameWriter = { error("store failed") },
            connectRequester = {
                connectRequests += 1
                ShizukuConnectRequestResult(true, "unexpected", it)
            },
            applicationId = "com.cla.clip.master",
            version = 10
        )

        /** 查询异常；外层 Binder 桥负责映射为可重试错误。 */
        val failure = runCatching { handler.query("event-failed") }.exceptionOrNull()
        assertEquals("store failed", failure?.message)
        assertEquals(0, connectRequests)
    }

    @Test
    /** 进程名提交未完成前不得排队绑定，也不得向旧 Shizuku 进程返回成功身份。 */
    fun queryWaitsForExpectedProcessNameCommitBeforeConnect() = runBlocking {
        /** 假存储已进入写入阶段的信号；让测试观察提交前状态。 */
        val writeStarted = CompletableDeferred<Unit>()
        /** 假存储提交完成的闸门；完成后查询才允许返回。 */
        val writeCommitted = CompletableDeferred<Unit>()
        /** 连接请求次数；提交完成前必须保持零。 */
        var connectRequests = 0
        /** 测试处理器；使用挂起写入模拟冷启动 DataStore 提交。 */
        val handler = ClipboardBridgeShizukuProcessQueryHandler(
            installIdProvider = { "001234" },
            processNameWriter = {
                writeStarted.complete(Unit)
                writeCommitted.await()
            },
            connectRequester = { reasonCode ->
                connectRequests += 1
                ShizukuConnectRequestResult(true, "", reasonCode)
            },
            applicationId = "com.cla.clip.master",
            version = 10
        )

        /** 正在等待持久化完成的身份查询。 */
        val pendingQuery = async { handler.query("event-pending") }
        writeStarted.await()
        assertEquals(0, connectRequests)
        assertEquals(false, pendingQuery.isCompleted)
        writeCommitted.complete(Unit)
        /** 持久化完成后的响应；必须携带已提交的完整进程名。 */
        val result = pendingQuery.await()
        assertEquals(1, connectRequests)
        assertEquals("com.cla.clip.master:shizuku_10_001234", result.shizukuProcessName)
    }
}
