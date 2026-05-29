package com.cla.clip.master.provider

import com.cla.clip.master.utils.ShizukuConnectRequestResult
import com.cla.clip.shizuku.ClipboardBridgeContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Provider 身份查询纯流程测试，保护 expectedProcessName 刷新和连接请求返回契约。 */
class ClipboardBridgeShizukuProcessQueryHandlerTest {

    @Test
    /** 合法查询必须先写入最新完整进程名，再返回 ok、进程名和连接请求状态。 */
    fun queryWritesExpectedProcessNameAndReturnsOkResult() {
        /** 写入到 AppSetting 的完整进程名替身。 */
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
    fun queryKeepsExpectedProcessNameWhenConnectRequestFails() {
        /** 写入到 AppSetting 的完整进程名替身。 */
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
}
