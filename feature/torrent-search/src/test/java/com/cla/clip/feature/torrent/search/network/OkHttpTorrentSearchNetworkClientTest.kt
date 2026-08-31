package com.cla.clip.feature.torrent.search.network

import com.cla.clip.feature.torrent.search.model.contract.ProviderFailureReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** OkHttp 网络边界测试，只使用受信任的本地 HTTPS MockWebServer。 */
class OkHttpTorrentSearchNetworkClientTest {
    /** 共享 OkHttp 配置必须固定并发及连接/读取超时，不额外设置 callTimeout。 */
    @Test
    fun createClientUsesRequiredDispatcherAndTimeoutLimits() {
        /** 被测客户端仅检查配置，不执行请求。 */
        val client = OkHttpTorrentSearchNetworkClient.createOkHttpClient()

        assertEquals(64, client.dispatcher.maxRequests)
        assertEquals(5, client.dispatcher.maxRequestsPerHost)
        assertEquals(10_000L, client.connectTimeoutMillis.toLong())
        assertEquals(30_000L, client.readTimeoutMillis.toLong())
        assertEquals(0L, client.callTimeoutMillis.toLong())
    }

    /** 明文 HTTP 必须在创建 Call 前拒绝，生产类不得暴露测试绕过参数。 */
    @Test
    fun getTextRejectsHttpAndProductionConstructorHasNoBypass() = runBlocking {
        /** 普通客户端用于确认 scheme 策略由网络边界执行。 */
        val networkClient = OkHttpTorrentSearchNetworkClient(OkHttpClient())
        /** 生产构造参数名用于防止测试逃生开关重新进入发布代码。 */
        val constructorParameterNames = OkHttpTorrentSearchNetworkClient::class.java.declaredConstructors
            // constructor 是等待收集参数名的单个生产构造函数。
            .flatMap { constructor -> constructor.parameters.map { parameter -> parameter.name } }

        assertSuspendFailsWith<HttpDeniedException> { networkClient.getText("http://example.invalid/path") }
        // name 是等待检查明文测试旁路痕迹的单个构造参数名。
        assertFalse(constructorParameterNames.any { name -> name.contains("allowHttp", ignoreCase = true) })
    }

    /** 解压后恰好 5 MiB 可读取，超过一字节必须在 parser 前拒绝。 */
    @Test
    fun getTextEnforcesDecompressedFiveMiBLimit() = runBlocking {
        /** 本地 HTTPS fixture 提供可信证书与真实 OkHttp 调用。 */
        val fixture = HttpsNetworkFixture()
        fixture.use {
            /** 恰好上限的合成文本锁定包含式边界。 */
            val exactLimitText = "a".repeat(5 * 1024 * 1024)
            /** 超过一字节的合成文本必须被网络边界拒绝。 */
            val oversizedText = "b".repeat(5 * 1024 * 1024 + 1)
            fixture.server.enqueue(MockResponse.Builder().body(exactLimitText).build())
            fixture.server.enqueue(MockResponse.Builder().body(oversizedText).build())

            assertEquals(exactLimitText.length, fixture.networkClient.getText(fixture.url("/exact")).length)
            assertSuspendFailsWith<ResponseTooLargeException> { fixture.networkClient.getText(fixture.url("/oversized")) }
            Unit
        }
    }

    /** gzip 压缩体解压后超过 5 MiB 仍必须拒绝。 */
    @Test
    fun getTextRejectsGzipBodyThatExceedsLimitAfterDecompression() = runBlocking {
        /** 本地 HTTPS fixture 不访问任何真实 Provider。 */
        val fixture = HttpsNetworkFixture()
        fixture.use {
            /** 解压后超过上限一字节的重复文本。 */
            val oversizedText = "g".repeat(5 * 1024 * 1024 + 1)
            fixture.server.enqueue(
                MockResponse.Builder().addHeader("Content-Encoding", "gzip").body(gzipBody(oversizedText)).build(),
            )

            assertSuspendFailsWith<ResponseTooLargeException> { fixture.networkClient.getText(fixture.url("/gzip")) }
            Unit
        }
    }

    /** Cloudflare 404 即使带特征头也必须归类普通 HTTP 拒绝。 */
    @Test
    fun getTextClassifiesCloudflareHeaderOn404AsHttpDenied() = runBlocking {
        /** 本地 HTTPS fixture 固定响应状态与头。 */
        val fixture = HttpsNetworkFixture()
        fixture.use {
            fixture.server.enqueue(
                MockResponse.Builder().code(404).addHeader("Server", "cloudflare").addHeader("CF-RAY", "fixture").build(),
            )

            /** 捕获的稳定异常只读取状态码，不读取响应 message 或正文。 */
            val failure = assertSuspendFailsWith<HttpDeniedException> { fixture.networkClient.getText(fixture.url("/missing")) }
            assertEquals(404, failure.statusCode)
        }
    }

    /** Cloudflare 只在挑战状态与特征头同时存在时分类锁定。 */
    @Test
    fun getTextClassifiesChallengeStatusWithCloudflareHeaderAsLocked() = runBlocking {
        /** 本地 HTTPS fixture 固定挑战响应。 */
        val fixture = HttpsNetworkFixture()
        fixture.use {
            fixture.server.enqueue(MockResponse.Builder().code(429).addHeader("Server", "cloudflare").build())

            /** 挑战异常必须保留低敏状态码。 */
            val failure = assertSuspendFailsWith<CloudflareLockedException> {
                fixture.networkClient.getText(fixture.url("/challenge"))
            }
            assertEquals(429, failure.statusCode)
        }
    }

    /** redirectLocation 必须复用 HTTP/Cloudflare 分类，不能绕过状态校验。 */
    @Test
    fun redirectLocationUsesSharedStatusValidation() = runBlocking {
        /** 本地 HTTPS fixture 依次返回挑战和合法重定向。 */
        val fixture = HttpsNetworkFixture()
        fixture.use {
            fixture.server.enqueue(MockResponse.Builder().code(503).addHeader("CF-RAY", "fixture").build())
            fixture.server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://target.invalid/file").build())

            assertSuspendFailsWith<CloudflareLockedException> { fixture.networkClient.redirectLocation(fixture.url("/locked")) }
            assertEquals("https://target.invalid/file", fixture.networkClient.redirectLocation(fixture.url("/redirect")))
        }
    }

    /** 响应头到达后取消仍必须打断慢 body、关闭响应且触发底层 Call.cancel。 */
    @Test
    fun cancellationAfterHeadersCancelsCallAndDoesNotBlockCaller() = runBlocking {
        /** 响应头到达同步点证明取消发生在 body 读取阶段。 */
        val headersReceived = CompletableDeferred<Unit>()
        /** 底层取消同步点直接观察真实 Call 生命周期。 */
        val callCancelled = CompletableDeferred<Unit>()
        /** EventListener 只观察生命周期，不读取 URL、header 或正文。 */
        val listener = object : EventListener() {
            /** 响应头结束时释放测试，让取消精确落在慢 body 阶段。 */
            override fun responseHeadersEnd(call: Call, response: okhttp3.Response) {
                headersReceived.complete(Unit)
            }

            /** OkHttp 确认取消时释放底层生命周期断言。 */
            override fun canceled(call: Call) {
                callCancelled.complete(Unit)
            }
        }
        /** HTTPS fixture 注入真实 EventListener。 */
        val fixture = HttpsNetworkFixture(listener)
        fixture.use {
            fixture.server.enqueue(
                MockResponse.Builder().body("slow-body").bodyDelay(30L, TimeUnit.SECONDS).build(),
            )
            /** 异步请求挂起时当前调用线程仍能继续处理取消。 */
            val request = async { fixture.networkClient.getText(fixture.url("/slow")) }

            withTimeout(5_000L) { headersReceived.await() }
            assertTrue(request.isActive)
            request.cancelAndJoin()
            withTimeout(5_000L) { callCancelled.await() }
            assertTrue(request.isCancelled)
        }
    }

    /** Provider 总超时在响应头后慢 body 阶段仍必须及时取消 Call，不能阻塞调用方调度器。 */
    @Test
    fun providerTimeoutDuringSlowBodyCancelsCallAfterHeaders() = runBlocking {
        /** 响应头同步点证明超时发生时 Call 已进入 body 消费阶段。 */
        val headersReceived = CompletableDeferred<Unit>()
        /** 底层取消同步点证明 withTimeout 不只是停止外层等待。 */
        val callCancelled = CompletableDeferred<Unit>()
        /** 生命周期监听器只记录阶段，不读取任何请求或响应内容。 */
        val listener = object : EventListener() {
            /** 记录响应头已完整到达。 */
            override fun responseHeadersEnd(call: Call, response: okhttp3.Response) {
                headersReceived.complete(Unit)
            }

            /** 记录底层 Call.cancel 已执行。 */
            override fun canceled(call: Call) {
                callCancelled.complete(Unit)
            }
        }
        /** HTTPS fixture 模拟响应头立即到达、body 长时间阻塞。 */
        val fixture = HttpsNetworkFixture(listener)
        fixture.use {
            fixture.server.enqueue(MockResponse.Builder().body("slow-body").bodyDelay(30L, TimeUnit.SECONDS).build())

            /** 200 ms 代表可注入测试超时；生产协调器使用同一 withTimeout 路径的 45 秒默认。 */
            assertSuspendFailsWith<TimeoutCancellationException> {
                withTimeout(200L) { fixture.networkClient.getText(fixture.url("/provider-timeout")) }
            }
            withTimeout(5_000L) { headersReceived.await() }
            withTimeout(5_000L) { callCancelled.await() }
        }
    }

    /** SocketTimeoutException 必须映射稳定 timeout，而不是笼统 network。 */
    @Test
    fun socketTimeoutMapsToStableTimeoutReason() {
        /** 合成超时异常不包含地址或用户输入。 */
        val failure = SocketTimeoutException()
        assertEquals(ProviderFailureReason.TIMEOUT, failure.toProviderFailureReason())
    }

    /** 断言挂起调用抛出精确类型，并返回异常供状态码等低敏字段继续验证。 */
    private suspend inline fun <reified T : Throwable> assertSuspendFailsWith(
        /** 需要执行的挂起网络调用。 */
        block: suspend () -> Unit,
    ): T {
        try {
            block()
            fail("预期抛出 ${T::class.java.simpleName}")
        // failure 是被测挂起调用的实际异常，只校验类型而不读取 message。
        } catch (failure: Throwable) {
            assertTrue("预期 ${T::class.java.simpleName}，实际为 ${failure::class.java.simpleName}", failure is T)
            @Suppress("UNCHECKED_CAST")
            return failure as T
        }
        error("JUnit fail 应已终止当前测试")
    }

    /** 把合成文本压缩为 gzip Buffer，验证限制依据解压后大小。 */
    private fun gzipBody(text: String): Buffer {
        /** 保存 gzip 字节的内存缓冲。 */
        val compressedBody = Buffer()
        /** 关闭 sink 会写完 trailer，Buffer 中字节仍可供响应读取。 */
        GzipSink(compressedBody).buffer().use { gzipSink -> gzipSink.writeUtf8(text) }
        return compressedBody
    }
}

/** 为单测建立只信任本地自签证书的 HTTPS 服务与客户端。 */
private class HttpsNetworkFixture(
    /** 可选生命周期监听器，用于取消与响应头测试。 */
    eventListener: EventListener? = null,
) : AutoCloseable {
    /** 仅对 localhost 有效的短期测试证书。 */
    private val heldCertificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    /** 服务端证书链，只供当前 MockWebServer。 */
    private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(heldCertificate).build()
    /** 客户端只信任本测试证书，不修改系统或生产信任策略。 */
    private val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(heldCertificate.certificate).build()
    /** 启用 TLS 的本地 MockWebServer。 */
    val server = MockWebServer().apply {
        useHttps(serverCertificates.sslSocketFactory())
        start()
    }
    /** 真实网络边界，生产构造没有 HTTP 绕过参数。 */
    val networkClient: OkHttpTorrentSearchNetworkClient

    /** 构造信任本地证书的 OkHttp 客户端，不发起请求。 */
    init {
        /** 客户端构造器只设置测试证书和可选生命周期监听。 */
        val clientBuilder = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        if (eventListener != null) clientBuilder.eventListener(eventListener)
        networkClient = OkHttpTorrentSearchNetworkClient(clientBuilder.build())
    }

    /** 生成当前本地 HTTPS 服务地址，不包含查询或真实域名。 */
    fun url(path: String): String = server.url(path).toString()

    /** 关闭服务及其活动连接，保证测试资源不会泄漏。 */
    override fun close() {
        server.close()
    }
}
