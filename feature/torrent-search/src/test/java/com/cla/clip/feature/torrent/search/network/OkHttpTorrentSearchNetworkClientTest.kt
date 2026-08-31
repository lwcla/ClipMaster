package com.cla.clip.feature.torrent.search.network

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** OkHttp 网络边界测试，仅使用本地 MockWebServer，绝不访问真实 Provider。 */
class OkHttpTorrentSearchNetworkClientTest {
    /** 共享 OkHttp 配置必须固定全局与单主机并发以及连接、读取超时。 */
    @Test
    fun createClientUsesRequiredDispatcherAndTimeoutLimits() {
        /** 被测客户端配置，不执行任何请求。 */
        val client = OkHttpTorrentSearchNetworkClient.createOkHttpClient()

        assertEquals(64, client.dispatcher.maxRequests)
        assertEquals(5, client.dispatcher.maxRequestsPerHost)
        assertEquals(10_000L, client.connectTimeoutMillis.toLong())
        assertEquals(30_000L, client.readTimeoutMillis.toLong())
        assertEquals(0L, client.callTimeoutMillis.toLong())
    }

    /** 明文 HTTP 必须在进入网络层前被稳定拒绝。 */
    @Test
    fun getTextRejectsHttpBeforeCreatingNetworkRequest() = runTest {
        /** 注入普通 OkHttp 客户端，地址策略应由被测网络边界独立执行。 */
        val networkClient = OkHttpTorrentSearchNetworkClient(OkHttpClient())

        assertSuspendFailsWith<HttpDeniedException> { networkClient.getText("http://example.invalid/path") }
    }

    /** 解压后恰好 5 MiB 的 HTTPS 响应可读取，超过一字节必须在 parser 前拒绝。 */
    @Test
    fun getTextEnforcesDecompressedFiveMiBLimit() = runTest {
        /** 本地 HTTPS 服务器只提供合成字节，不触发外部网络。 */
        val server = MockWebServer()
        server.start()
        try {
            /** 恰好上限的合成文本，用来锁定边界可读行为。 */
            val exactLimitText = "a".repeat(5 * 1024 * 1024)
            server.enqueue(MockResponse.Builder().body(exactLimitText).build())
            /** 超过一字节的合成文本，必须不交给 Provider parser。 */
            val oversizedText = "b".repeat(5 * 1024 * 1024 + 1)
            server.enqueue(MockResponse.Builder().body(oversizedText).build())
            /** 被测客户端仅在当前模块测试构造中允许回环 HTTP fixture，正式构造仍严格 HTTPS-only。 */
            val networkClient = OkHttpTorrentSearchNetworkClient(OkHttpClient(), allowHttpForLocalTests = true)
            /** 本地回环 URL 仅用于传输边界，不包含用户数据或真实 Provider 地址。 */
            val url = server.url("/content").toString()

            assertEquals(exactLimitText.length, networkClient.getText(url).length)
            assertSuspendFailsWith<ResponseTooLargeException> { networkClient.getText(url) }
        } finally {
            server.close()
        }
    }

    /** gzip 压缩体即使很小，解压后超过 5 MiB 仍必须拒绝，防止压缩炸弹绕过 parser 前边界。 */
    @Test
    fun getTextRejectsGzipBodyThatExceedsLimitAfterDecompression() = runTest {
        /** 本地服务只返回合成 gzip 数据，不访问真实 Provider。 */
        val server = MockWebServer()
        server.start()
        try {
            /** 解压后超过上限一字节的重复文本，压缩体本身远小于 5 MiB。 */
            val oversizedText = "g".repeat(5 * 1024 * 1024 + 1)
            /** gzip 压缩后的响应体，用于证明限制依据解压后 source 而非 Content-Length。 */
            val compressedBody = gzipBody(oversizedText)
            server.enqueue(
                MockResponse.Builder()
                    .addHeader("Content-Encoding", "gzip")
                    .body(compressedBody)
                    .build(),
            )
            /** 测试专用回环客户端；生产构造仍拒绝所有 HTTP 地址。 */
            val networkClient = OkHttpTorrentSearchNetworkClient(OkHttpClient(), allowHttpForLocalTests = true)
            /** 本机回环地址不含真实域名、查询或用户数据。 */
            val url = server.url("/gzip").toString()

            assertSuspendFailsWith<ResponseTooLargeException> { networkClient.getText(url) }
        } finally {
            server.close()
        }
    }

    /** 普通 403 若没有 Cloudflare 特征必须归类 HTTP 拒绝，避免把站点权限错误误报为挑战锁定。 */
    @Test
    fun getTextClassifiesPlainForbiddenResponseAsHttpDenied() = runTest {
        /** 本地服务返回无 Cloudflare 头的最小 403 响应。 */
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().code(403).body("denied").build())
            /** 测试专用回环客户端，正式请求仍固定 HTTPS-only。 */
            val networkClient = OkHttpTorrentSearchNetworkClient(OkHttpClient(), allowHttpForLocalTests = true)
            /** 本机回环地址只用于合成状态码。 */
            val url = server.url("/forbidden").toString()

            try {
                networkClient.getText(url)
                fail("预期普通 403 抛出 HttpDeniedException")
            } catch (failure: HttpDeniedException) {
                assertEquals(403, failure.statusCode)
            }
        } finally {
            server.close()
        }
    }

    /** 外层协程取消必须取消底层 OkHttp Call，避免页面离开后连接继续占用资源。 */
    @Test
    fun getTextCancelsUnderlyingCallWhenCoroutineIsCancelled() = runTest {
        /** 本地服务端延迟响应，保证测试有机会在响应完成前取消收集任务。 */
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().body("late").bodyDelay(30, TimeUnit.SECONDS).build())
            /** 底层 OkHttp 取消事件同步点，直接证明 Call.cancel 被调用而非只检查外层 Job。 */
            val callCancelled = kotlinx.coroutines.CompletableDeferred<Unit>()
            /** 真实 OkHttp EventListener 只观察取消生命周期，不读取 URL、header 或正文。 */
            val eventListener = object : EventListener() {
                /** OkHttp 确认底层 Call 被取消时完成测试同步点。 */
                override fun canceled(call: Call) {
                    callCancelled.complete(Unit)
                }
            }
            /** 注入取消监听器的真实 OkHttp 客户端，避免通过 mock 断言自身调用。 */
            val okHttpClient = OkHttpClient.Builder().eventListener(eventListener).build()
            /** 使用仅测试可见的回环 HTTP fixture 开关，正式调用无法传入该开关。 */
            val networkClient = OkHttpTorrentSearchNetworkClient(okHttpClient, allowHttpForLocalTests = true)
            /** 测试专用回环 URL，仅用于本机响应，不包含用户数据。 */
            val url = server.url("/slow").toString()
            /** 启动会挂起的请求，外层取消应桥接到底层 Call.cancel()。 */
            val requestJob = async { networkClient.getText(url) }

            delay(10)
            requestJob.cancelAndJoin()
            assertTrue(requestJob.isCancelled)
            assertTrue(callCancelled.isCompleted)
        } finally {
            server.close()
        }
    }

    /** Cloudflare 特征头即使搭配非 403/503 状态，也必须映射为稳定锁定分类。 */
    @Test
    fun getTextClassifiesCloudflareHeaderAsLocked() = runTest {
        /** 本地服务仅返回最小挑战特征头，不包含真实站点地址、正文或用户查询。 */
        val server = MockWebServer()
        server.start()
        try {
            /** 429 用于证明分类不能只依赖原先的两个固定状态码。 */
            val cloudflareResponse = MockResponse.Builder()
                .code(429)
                .addHeader("Server", "cloudflare")
                .body("challenge")
                .build()
            server.enqueue(cloudflareResponse)
            /** 测试构造只允许本机回环 HTTP，生产构造仍会在请求前拒绝 HTTP。 */
            val networkClient = OkHttpTorrentSearchNetworkClient(OkHttpClient(), allowHttpForLocalTests = true)
            /** 回环请求地址只服务于固定合成响应。 */
            val url = server.url("/challenge").toString()

            assertSuspendFailsWith<CloudflareLockedException> { networkClient.getText(url) }
        } finally {
            server.close()
        }
    }

    /** 断言挂起网络调用抛出精确类型，避免 JUnit 非挂起断言掩盖协程边界。 */
    private suspend inline fun <reified T : Throwable> assertSuspendFailsWith(
        /** 需要执行的挂起网络调用。 */
        block: suspend () -> Unit,
    ) {
        try {
            block()
            fail("预期抛出 ${T::class.java.simpleName}")
        } catch (failure: Throwable) {
            assertTrue("预期 ${T::class.java.simpleName}，实际为 ${failure::class.java.simpleName}", failure is T)
        }
    }

    /** 把合成文本压缩为 gzip Buffer，测试响应读取基于解压后大小而非压缩体大小。 */
    private fun gzipBody(text: String): Buffer {
        /** 保存 gzip 字节的内存缓冲，不涉及文件或真实响应。 */
        val compressedBody = Buffer()
        /** gzip sink 在 close 时写完 trailer；关闭 Buffer 本身不会丢弃已写字节。 */
        GzipSink(compressedBody).buffer().use { gzipSink -> gzipSink.writeUtf8(text) }
        return compressedBody
    }
}
