package com.cla.clip.master.ui.page.image

import com.cla.clip.base.general.repository.ImageCandidateData
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

/** 合成挂起的 HTTP 请求，验证弹层取消会真实停止网络调用而不只停止 UI 等待。 */
class ImagePreviewMetadataReaderTest {
    /** 在响应尚未返回时离开预览，不允许后台请求继续消耗连接。 */
    @Test fun dismissCancelsPendingNetworkCall() = runBlocking {
        // 无网络的待处理调用，只有取消状态可被测试观察。
        val call = PendingCall()
        // 工厂接收生产请求而不建立真实连接。
        val reader = ImagePreviewMetadataReader { Call.Factory { call } }
        // 立即启动到首个挂起点，避免依赖调度时间。
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            reader.read(ImageCandidateData("https://example.test/a.png", null, null, null, 0, null, null))
        }
        job.cancelAndJoin()
        assertTrue(call.isCanceled())
    }

    /** 最小 OkHttp 测试替身：响应始终挂起，用来检查真实取消桥接。 */
    private class PendingCall : Call by OkHttpClient().newCall(Request.Builder().url("https://example.test").build()) {
        /** 记录协程是否调用底层 cancel，而非仅停止等待。 */
        private var cancelled = false
        /** 合成地址不包含用户资料。 */
        override fun request(): Request = Request.Builder().url("https://example.test").build()
        /** 此路径必须使用异步请求，避免阻塞主线程。 */
        override fun execute(): Response = error("禁止同步网络读取")
        /** 故意不回调，模拟长时间没有响应的站点。 */
        override fun enqueue(responseCallback: Callback) = Unit
        /** 标记底层请求已经终止。 */
        override fun cancel() { cancelled = true }
        /** 测试调用一经构造便视为已安排。 */
        override fun isExecuted(): Boolean = true
        /** 向测试公开取消证据。 */
        override fun isCanceled(): Boolean = cancelled
        /** 不引入真实时间预算，页面调用方另外限制总耗时。 */
        override fun timeout(): Timeout = Timeout.NONE
        /** 克隆仍是独立未取消请求。 */
        override fun clone(): Call = PendingCall()
    }
}
