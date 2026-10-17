package com.cla.clip.base.general.backup.webdav

import java.io.IOException
import java.net.SocketTimeoutException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** 合成 Call 只触发观察事件，不连接域名，测试时钟无需等待。 */
class WebDavRequestEventListenerTest {
    /** 超时保留最后阶段和单调耗时，日志不得包含域名、头或异常文本。 */
    @Test fun timeoutReportsLastPhaseWithoutSensitiveData() {
        /** 可控单调时钟的毫秒值。 */
        var now = 10L
        /** 捕获低敏输出，不使用 Android Log。 */
        val logs = mutableListOf<String>()
        /** URL 和凭据占位只存在于内存请求。 */
        val call = OkHttpClient().newCall(Request.Builder().url("https://private.invalid/secret")
            .header("Authorization", "private-token").build())
        /** 请求身份与 operation 使用受控标签。 */
        val listener = WebDavRequestEventListener(WebDavRequestTrace("upload_file", "round-1"), { now }, logs::add)
        listener.callStart(call)
        listener.dnsStart(call, "private.invalid")
        now = 70L
        listener.callFailed(call, SocketTimeoutException("private-password"))
        assertTrue(logs.last().contains("event=failed"))
        assertTrue(logs.last().contains("phase=dns"))
        assertTrue(logs.last().contains("phaseElapsedMs=60"))
        assertTrue(logs.last().contains("taskId=round-1"))
        assertFalse(logs.joinToString().contains("private"))
        assertFalse(logs.joinToString().contains("secret"))
    }

    /** 复用连接可直接写入；两个交错 Call 的阶段和字节统计不能相互覆盖。 */
    @Test fun interleavedCallsKeepIndependentPhasesAndIds() {
        /** 两个请求共用时钟而不共用状态。 */
        var now = 0L
        /** 分开收集每个 Call 的输出便于验证关联。 */
        val firstLogs = mutableListOf<String>()
        val secondLogs = mutableListOf<String>()
        /** 合成请求不执行网络。 */
        val call = OkHttpClient().newCall(Request.Builder().url("https://example.invalid").build())
        /** 两个 listener 必须各自持有 callId 和当前阶段。 */
        val first = WebDavRequestEventListener(WebDavRequestTrace("upload_file", "A"), { now }, firstLogs::add)
        val second = WebDavRequestEventListener(WebDavRequestTrace("upload_file", "B"), { now }, secondLogs::add)
        first.callStart(call)
        second.callStart(call)
        first.requestBodyStart(call)
        second.dnsStart(call, "unused.invalid")
        now = 20L
        first.requestBodyEnd(call, 123L)
        now = 30L
        second.callFailed(call, IOException("private"))
        first.callEnd(call)
        assertTrue(firstLogs.last().contains("phase=waiting_response"))
        assertTrue(firstLogs.last().contains("requestBytes=123"))
        assertTrue(secondLogs.last().contains("phase=dns"))
        assertFalse(firstLogs.any { it.contains("phase=dns") })
        assertNotEquals(firstLogs.first().substringAfter("callId=").substringBefore(' '),
            secondLogs.first().substringAfter("callId=").substringBefore(' '))
    }

    /** 取消及晚到 IO 只产生一次取消终态，日志接收器异常也不得影响请求。 */
    @Test fun cancellationAndBrokenLoggerDoNotInventFailure() {
        /** 无外部内容的合成请求。 */
        val call = OkHttpClient().newCall(Request.Builder().url("https://example.invalid").build())
        /** 观察取消后的全部输出。 */
        val logs = mutableListOf<String>()
        /** 不可信关联值不能成为输出敏感内容的通道。 */
        val listener = WebDavRequestEventListener(WebDavRequestTrace("private-url", "https://private.invalid"), { 0L }, logs::add)
        listener.callStart(call)
        call.cancel()
        listener.canceled(call)
        listener.callFailed(call, IOException("private"))
        listener.requestBodyStart(call)
        assertEquals(1, logs.count { it.contains("event=cancelled") })
        assertFalse(logs.any { it.contains("event=failed") || it.contains("private") })
        WebDavRequestEventListener(WebDavRequestTrace("upload_file", "A"), { 0L }, { error("logger") }).callStart(call)
    }
}
