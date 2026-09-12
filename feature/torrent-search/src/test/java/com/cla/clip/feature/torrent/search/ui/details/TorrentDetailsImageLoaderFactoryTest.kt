package com.cla.clip.feature.torrent.search.ui.details

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 详情图片专用网络客户端的自动重定向逃逸回归。 */
class TorrentDetailsImageLoaderFactoryTest {
    /** 专用客户端必须同时关闭普通与跨协议自动重定向。 */
    @Test
    fun networkClientDisablesAllAutomaticRedirects() {
        /** client 是图片加载器实际复用的专用 OkHttpClient。 */
        val client = TorrentDetailsImageLoaderFactory.createNetworkClient()

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    /** HTTPS 图片端点返回跨主机 HTTP 地址时不得发出第二跳请求。 */
    @Test
    fun networkClientDoesNotFollowCrossHostHttpsDowngradeRedirect() {
        /** heldCertificate 只信任本机合成 HTTPS 来源。 */
        val heldCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        /** serverCertificates 为合成来源提供 TLS 服务端证书。 */
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(heldCertificate).build()
        /** clientCertificates 只信任当前合成证书。 */
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(heldCertificate.certificate).build()
        /** redirectTarget 是不得收到请求的跨主机明文目标。 */
        val redirectTarget = MockWebServer().apply { start() }
        /** httpsOrigin 是已允许初始图片地址的合成 HTTPS 来源。 */
        val httpsOrigin = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory())
            start()
        }
        try {
            redirectTarget.enqueue(MockResponse.Builder().body("unexpected-target").build())
            /** escapedUrl 同时改变 host 与协议，代表不得自动追随的白名单外第二跳。 */
            val escapedUrl = redirectTarget.url("/escaped-image").newBuilder().host("127.0.0.1").build()
            httpsOrigin.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .addHeader("Location", escapedUrl)
                    .build(),
            )
            /** trustedBuilder 只为本测试注入合成证书信任。 */
            val trustedBuilder = OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            /** client 在保留测试 TLS 配置后应用生产禁重定向边界。 */
            val client = TorrentDetailsImageLoaderFactory.createNetworkClient(trustedBuilder)
            /** request 指向初始可信 HTTPS 图片地址。 */
            val request = Request.Builder().url(httpsOrigin.url("/image")).build()

            client.newCall(request).execute().use { /** response 必须停留在第一跳 302。 */ response ->
                assertEquals(302, response.code)
            }
            assertEquals(1, httpsOrigin.requestCount)
            assertEquals(0, redirectTarget.requestCount)
        } finally {
            httpsOrigin.close()
            redirectTarget.close()
        }
    }
}
