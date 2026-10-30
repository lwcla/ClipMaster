package com.cla.clip.master.preview

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

/** 在网络边界提供可控 Call，验证真实协程适配器的取消及读取上限。 */
class PreviewHtmlCallTest {
    /** 取消自动预览必须传到 OkHttp，而不只是丢弃结果。 */
    @Test fun cancellingCoroutineCancelsNetworkCall() = runBlocking {
        /** 无实际网络的等待中请求。 */
        val call = PendingCall()
        /** UNDISPATCHED 保证先注册网络回调，再由测试取消。 */
        val job = async(start = CoroutineStart.UNDISPATCHED) { call.awaitPreviewHtml(8) }
        job.cancelAndJoin()
        assertTrue(call.isCanceled())
    }

    /** HTML 读取上限避免自动预览为大页面分配完整响应体。 */
    @Test fun limitsResponseBodyBeforeReturningHtml() = runBlocking {
        /** 模拟一份超过限制的 ASCII 响应。 */
        val call = PendingCall()
        /** 适配器开始后手动投递合成响应。 */
        val result = async(start = CoroutineStart.UNDISPATCHED) { call.awaitPreviewHtml(4) }
        call.respond("abcdefgh")
        assertEquals("abcd", result.await())
    }

    /** 可控网络边界，不模拟被测适配逻辑。 */
    private class PendingCall : Call by OkHttpClient().newCall(Request.Builder().url("https://example.invalid/").build()) {
        /** 请求仅用于构造合法响应，不发送到站点。 */
        private val input = Request.Builder().url("https://example.invalid/").build()
        /** 测试驱动响应的注册回调。 */
        private lateinit var callback: Callback
        /** 被真实适配器取消时置位。 */
        private var cancelled = false
        /** 记录入队情况以满足 Call 契约。 */
        private var executed = false
        /** 暴露合成请求。 */ override fun request(): Request = input
        /** 被测入口应只用异步调用。 */ override fun execute(): Response = error("只能异步调用")
        /** 保存框架回调，不自行返回成功。 */ override fun enqueue(responseCallback: Callback) { executed = true; callback = responseCallback }
        /** 由协程取消处理器调用。 */ override fun cancel() { cancelled = true }
        /** 可观察的底层取消结果。 */ override fun isCanceled(): Boolean = cancelled
        /** 提供请求是否已入队。 */ override fun isExecuted(): Boolean = executed
        /** 假请求不需要计时器。 */ override fun timeout(): Timeout = Timeout.NONE
        /** 复制一个未执行请求。 */ override fun clone(): Call = PendingCall()
        /** 在等待中回调投递合成 HTTP 200 响应。 */
        fun respond(body: String) {
            callback.onResponse(this, Response.Builder().request(input).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build())
        }
    }
}
