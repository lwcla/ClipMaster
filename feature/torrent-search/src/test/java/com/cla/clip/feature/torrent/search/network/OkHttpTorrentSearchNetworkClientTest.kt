package com.cla.clip.feature.torrent.search.network

import com.cla.clip.feature.torrent.search.model.contract.ProviderFailureReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
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

    /** HTTPS 响应降级到 HTTP 时必须在目标 Call 创建前拒绝，明文服务不得收到请求。 */
    @Test
    fun getTextRejectsHttpsRedirectToHttpBeforeTargetRequest() = runBlocking {
        /** 初始 HTTPS fixture 复现 Provider 已通过首个地址校验后的共享网络调用。 */
        val fixture = HttpsNetworkFixture()
        /** 明文目标只在本机监听，用于证明安全边界不会实际访问降级地址。 */
        val cleartextTarget = MockWebServer().apply { start() }
        try {
            /** 明文目标响应可识别正文；旧实现自动跟随时会错误返回该内容。 */
            cleartextTarget.enqueue(MockResponse.Builder().body("unexpected-cleartext-target").build())
            /** 首个可信 HTTPS 响应尝试把请求降级到本地 HTTP 服务。 */
            fixture.server.enqueue(
                MockResponse.Builder().code(302).addHeader("Location", cleartextTarget.url("/target")).build(),
            )

            assertSuspendFailsWith<HttpDeniedException> { fixture.networkClient.getText(fixture.url("/start")) }
            assertEquals(0, cleartextTarget.requestCount)
        } finally {
            fixture.close()
            cleartextTarget.close()
        }
    }

    /** HTTPS 重定向更换 host 时必须在后续 Call 前拒绝，即使证书和端口仍可连接。 */
    @Test
    fun getTextRejectsCrossHostHttpsRedirectBeforeTargetRequest() = runBlocking {
        /** 同一 TLS 服务同时接受 localhost 与 127.0.0.1，隔离测试只观察来源策略。 */
        val fixture = HttpsNetworkFixture()
        fixture.use {
            /** 首个响应把 host 从 localhost 改为 127.0.0.1，保持 scheme 与有效端口不变。 */
            fixture.server.enqueue(
                MockResponse.Builder().code(302).addHeader(
                    "Location",
                    fixture.urlWithHost(path = "/cross-host", host = "127.0.0.1"),
                ).build(),
            )
            /** 第二个响应仅用于暴露旧实现确实自动访问了跨来源目标。 */
            fixture.server.enqueue(MockResponse.Builder().body("unexpected-cross-host-target").build())

            assertSuspendFailsWith<HttpDeniedException> { fixture.networkClient.getText(fixture.url("/start")) }
            assertEquals(1, fixture.server.requestCount)
        }
    }

    /** HTTPS 重定向保持 host 但更换有效 port 时必须拒绝，目标服务不得收到请求。 */
    @Test
    fun getTextRejectsCrossPortHttpsRedirectBeforeTargetRequest() = runBlocking {
        /** 初始 HTTPS fixture 提供固定 localhost 来源与共享测试证书。 */
        val fixture = HttpsNetworkFixture()
        fixture.use {
            /** 目标 HTTPS 服务复用相同证书但监听不同端口，隔离验证有效 port 策略。 */
            val differentPortTarget = fixture.newHttpsServer()
            differentPortTarget.use {
                differentPortTarget.enqueue(MockResponse.Builder().body("unexpected-cross-port-target").build())
                fixture.server.enqueue(
                    MockResponse.Builder().code(302).addHeader("Location", differentPortTarget.url("/target")).build(),
                )

                assertSuspendFailsWith<HttpDeniedException> { fixture.networkClient.getText(fixture.url("/start")) }
                assertEquals(1, fixture.server.requestCount)
                assertEquals(0, differentPortTarget.requestCount)
            }
        }
    }

    /** 同来源相对与绝对 HTTPS 跳转必须连续取得最终正文，且每一跳保持 GET。 */
    @Test
    fun getTextFollowsSameOriginRelativeAndAbsoluteRedirects() = runBlocking {
        /** 本地 HTTPS fixture 提供同 scheme、host 与有效 port 的三段响应。 */
        val fixture = HttpsNetworkFixture()
        fixture.use {
            fixture.server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/middle").build())
            fixture.server.enqueue(
                MockResponse.Builder().code(301).addHeader("Location", fixture.url("/final")).build(),
            )
            fixture.server.enqueue(MockResponse.Builder().body("final-body").build())

            assertEquals("final-body", fixture.networkClient.getText(fixture.url("/start")))
            /** 三次真实请求分别对应初始、相对跳转与绝对跳转地址。 */
            val requests = List(3) { fixture.server.takeRequest() }
            // request 是当前跳转阶段的真实请求，只提取方法用于固定 GET 语义。
            assertEquals(listOf("GET", "GET", "GET"), requests.map { request -> request.method })
            // request 是当前跳转阶段的真实请求，只提取无敏感参数的合成路径。
            assertEquals(listOf("/start", "/middle", "/final"), requests.map { request -> request.url.encodedPath })
        }
    }

    /** POST 的 301、302 与 303 必须转 GET，并移除原正文及内容相关 header。 */
    @Test
    fun postRedirects301302And303SwitchToGetWithoutBodyHeaders() = runBlocking {
        /** 三类会改变 POST 方法的标准重定向状态。 */
        val redirectCodes = listOf(301, 302, 303)
        // redirectCode 是当前独立验证的响应状态，失败时便于定位具体语义。
        redirectCodes.forEach { redirectCode ->
            /** 每个状态使用独立 TLS fixture，避免请求队列相互影响。 */
            val fixture = HttpsNetworkFixture()
            fixture.use {
                fixture.server.enqueue(MockResponse.Builder().code(redirectCode).addHeader("Location", "/final").build())
                fixture.server.enqueue(MockResponse.Builder().body("accepted").build())

                assertEquals("accepted", fixture.networkClient.postJson(fixture.url("/start"), "{\"fixture\":true}"))
                /** 初始请求用于确认测试确实从 POST 开始。 */
                val initialRequest = fixture.server.takeRequest()
                /** 后续请求锁定方法、正文与内容 header 的转换契约。 */
                val redirectedRequest = fixture.server.takeRequest()
                assertEquals("POST", initialRequest.method)
                assertEquals("GET", redirectedRequest.method)
                assertEquals(0L, redirectedRequest.bodySize)
                assertEquals(null, redirectedRequest.headers["Content-Type"])
                assertEquals(null, redirectedRequest.headers["Content-Length"])
                assertEquals(null, redirectedRequest.headers["Transfer-Encoding"])
            }
        }
    }

    /** POST 303 指回同一 URL 时允许以 GET 再请求一次，方法变化不能被误判为 URL 循环。 */
    @Test
    fun post303ToSameUrlSwitchesToGetAndReturnsFinalBody() = runBlocking {
        /** 本地 HTTPS fixture 用同一路径依次返回 303 与最终 200。 */
        val fixture = HttpsNetworkFixture()
        fixture.use {
            fixture.server.enqueue(MockResponse.Builder().code(303).addHeader("Location", "/same").build())
            fixture.server.enqueue(MockResponse.Builder().body("same-url-final-body").build())

            assertEquals(
                "same-url-final-body",
                fixture.networkClient.postJson(fixture.url("/same"), "{\"fixture\":true}"),
            )
            /** 两次真实请求分别锁定初始 POST 与同 URL 的后续 GET 身份。 */
            val requests = List(2) { fixture.server.takeRequest() }
            // request 是同一 URL 上的当前请求，只提取方法验证循环身份包含 HTTP method。
            assertEquals(listOf("POST", "GET"), requests.map { request -> request.method })
        }
    }

    /** POST 的 307 与 308 必须保持方法和固定合成正文，不能误降级成 GET。 */
    @Test
    fun postRedirects307And308PreserveMethodAndBody() = runBlocking {
        /** 两类要求保留请求语义的标准重定向状态。 */
        val redirectCodes = listOf(307, 308)
        // redirectCode 是当前独立验证的方法保留状态。
        redirectCodes.forEach { redirectCode ->
            /** 每个状态使用独立 TLS fixture，保证断言只对应一次跳转。 */
            val fixture = HttpsNetworkFixture()
            fixture.use {
                fixture.server.enqueue(MockResponse.Builder().code(redirectCode).addHeader("Location", "/final").build())
                fixture.server.enqueue(MockResponse.Builder().body("accepted").build())

                assertEquals("accepted", fixture.networkClient.postJson(fixture.url("/start"), "{\"fixture\":true}"))
                /** 初始请求只用于移出队列，后续断言聚焦重放语义。 */
                val initialRequest = fixture.server.takeRequest()
                /** 重定向请求必须保留 POST 与完全相同的固定 JSON。 */
                val redirectedRequest = fixture.server.takeRequest()
                assertEquals("POST", initialRequest.method)
                assertEquals("POST", redirectedRequest.method)
                assertEquals("{\"fixture\":true}", redirectedRequest.body?.utf8())
            }
        }
    }

    /** 同来源跳转最多跟随 20 次，第 21 个跳转响应必须稳定拒绝。 */
    @Test
    fun getTextRejectsRedirectBeyondTwentyFollowUps() = runBlocking {
        /** 本地 HTTPS fixture 提供 21 个不同路径的连续跳转。 */
        val fixture = HttpsNetworkFixture()
        fixture.use {
            // hopNumber 是从 1 开始的目标序号，避免循环检测提前终止上限测试。
            (1..21).forEach { hopNumber ->
                fixture.server.enqueue(
                    MockResponse.Builder().code(302).addHeader("Location", "/hop-$hopNumber").build(),
                )
            }

            assertSuspendFailsWith<HttpDeniedException> { fixture.networkClient.getText(fixture.url("/start")) }
            assertEquals(21, fixture.server.requestCount)
        }
    }

    /** 缺少 Location 的受支持重定向状态必须稳定拒绝，不能返回空正文。 */
    @Test
    fun getTextRejectsRedirectWithoutLocation() = runBlocking {
        /** 本地 HTTPS fixture 只返回不完整的 302。 */
        val fixture = HttpsNetworkFixture()
        fixture.use {
            fixture.server.enqueue(MockResponse.Builder().code(302).build())

            assertSuspendFailsWith<HttpDeniedException> { fixture.networkClient.getText(fixture.url("/missing-location")) }
            assertEquals(1, fixture.server.requestCount)
        }
    }

    /** 非法 Location 与带用户信息的 URL 必须在后续 Call 创建前拒绝。 */
    @Test
    fun getTextRejectsMalformedAndUserInfoRedirectLocations() = runBlocking {
        /** 同一 TLS fixture 让带用户信息 URL 保持相同 scheme、host 与有效 port。 */
        val fixture = HttpsNetworkFixture()
        fixture.use {
            /** 两类 Location 分别保护解析失败与凭据式 URL 边界。 */
            val locations = listOf("https://[invalid", fixture.urlWithUserInfo("/target"))
            // location 是当前独立验证的固定合成 Location，不含真实地址或真实凭据。
            locations.forEach { location ->
                fixture.server.enqueue(MockResponse.Builder().code(302).addHeader("Location", location).build())

                assertSuspendFailsWith<HttpDeniedException> { fixture.networkClient.getText(fixture.url("/start")) }
            }
            assertEquals(2, fixture.server.requestCount)
        }
    }

    /** 已访问 URL 再次出现时必须按循环拒绝，不能继续发出第三个请求。 */
    @Test
    fun getTextRejectsRedirectLoopBeforeRepeatedRequest() = runBlocking {
        /** 本地 HTTPS fixture 构造 start 到 middle 再回 start 的最小循环。 */
        val fixture = HttpsNetworkFixture()
        fixture.use {
            fixture.server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/middle").build())
            fixture.server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/start").build())

            assertSuspendFailsWith<HttpDeniedException> { fixture.networkClient.getText(fixture.url("/start")) }
            assertEquals(2, fixture.server.requestCount)
        }
    }

    /** redirectLocation 只返回首个 Location，不能消费目标响应或创建后续 Call。 */
    @Test
    fun redirectLocationDoesNotFollowReturnedLocation() = runBlocking {
        /** 本地 HTTPS fixture 提供首个跳转及一个不应被消费的目标响应。 */
        val fixture = HttpsNetworkFixture()
        fixture.use {
            fixture.server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/target").build())
            fixture.server.enqueue(MockResponse.Builder().body("must-remain-unread").build())

            assertEquals("/target", fixture.networkClient.redirectLocation(fixture.url("/start")))
            assertEquals(1, fixture.server.requestCount)
        }
    }

    /** 同来源跳转后的最终 Cloudflare 挑战仍必须经过共享状态分类。 */
    @Test
    fun getTextClassifiesFinalRedirectResponseAsCloudflareLocked() = runBlocking {
        /** 本地 HTTPS fixture 先跳转，再返回带 Cloudflare 特征头的挑战状态。 */
        val fixture = HttpsNetworkFixture()
        fixture.use {
            fixture.server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/challenge").build())
            fixture.server.enqueue(MockResponse.Builder().code(503).addHeader("CF-RAY", "fixture").build())

            /** 最终异常应保留低敏状态码，不能暴露 Location 或正文。 */
            val failure = assertSuspendFailsWith<CloudflareLockedException> {
                fixture.networkClient.getText(fixture.url("/start"))
            }
            assertEquals(503, failure.statusCode)
        }
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

    /** 首个 3xx 响应关闭时取消协程，不得跨越恢复边界创建下一跳 Call。 */
    @Test
    fun cancellationWhenRedirectResponseClosesDoesNotStartFollowUpCall() = runBlocking {
        /** 首个 Call 关闭同步点精确落在 executeInCallback 恢复与下一轮 ensureActive 之前。 */
        val redirectCallEnded = CompletableDeferred<Unit>()
        /** 延迟赋值的请求 Job 由 EventListener 在首个 Call 结束回调内取消。 */
        lateinit var requestJob: Job
        /** 生命周期监听器只使用 Call 结束事件，不读取 URL、Location、header 或正文。 */
        val listener = object : EventListener() {
            /** 响应已关闭且 Call 已结束时同步取消外层协程，锁定不得创建后续请求。 */
            override fun callEnd(call: Call) {
                requestJob.cancel()
                redirectCallEnded.complete(Unit)
            }
        }
        /** HTTPS fixture 注入真实 EventListener，生产网络接口保持不变。 */
        val fixture = HttpsNetworkFixture(listener)
        fixture.use {
            fixture.server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/target").build())
            fixture.server.enqueue(MockResponse.Builder().body("must-remain-unrequested").build())
            /** LAZY 启动保证监听器触发前 requestJob 已完成赋值。 */
            val request = async(start = CoroutineStart.LAZY) {
                fixture.networkClient.getText(fixture.url("/start"))
            }
            requestJob = request
            request.start()

            withTimeout(5_000L) { redirectCallEnded.await() }
            request.join()
            assertTrue(request.isCancelled)
            assertEquals(1, fixture.server.requestCount)
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
    /** 仅对本机 localhost 与 127.0.0.1 有效的短期测试证书。 */
    private val heldCertificate = HeldCertificate.Builder()
        .addSubjectAlternativeName("localhost")
        .addSubjectAlternativeName("127.0.0.1")
        .build()
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

    /** 生成仅替换 host 的本地 HTTPS 地址，用于隔离验证跨来源重定向拒绝。 */
    fun urlWithHost(
        /** 目标路径只使用固定合成值。 */
        path: String,
        /** 目标 host 必须被当前合成证书覆盖。 */
        host: String,
    ): String = server.url(path).newBuilder().host(host).build().toString()

    /** 生成同来源但带固定合成用户信息的地址，隔离验证凭据式 Location 拒绝。 */
    fun urlWithUserInfo(
        /** 目标路径只使用固定合成值。 */
        path: String,
    ): String = server.url(path).newBuilder().username("user").password("password").build().toString()

    /** 启动复用当前合成证书的额外 HTTPS 服务，用于隔离同 host 不同 port 行为。 */
    fun newHttpsServer(): MockWebServer = MockWebServer().apply {
        useHttps(serverCertificates.sslSocketFactory())
        start()
    }

    /** 关闭服务及其活动连接，保证测试资源不会泄漏。 */
    override fun close() {
        server.close()
    }
}
