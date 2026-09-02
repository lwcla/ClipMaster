package com.cla.clip.feature.torrent.search.provider.builtin.blueroms

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BlueRoms Base64 两阶段搜索、详情资源与精确 host 安全契约测试。 */
class BlueRomsProviderTest {
    /** 正常结果按 DOM 顺序读取下载页，并映射固定 SAFE/GAMES/默认关闭描述符。 */
    @Test
    fun searchDecodesMagnetsInDomOrderAndMapsFields() = runTest {
        /** 合成搜索结果页。 */
        val searchFixture = readProviderFixture("provider/blueroms/search_normal.html")
        /** 两个手工固定的合法 Base64 magnet，避免用生产解码器生成期望。 */
        val encodedMagnets = mapOf(
            "/download/one" to "bWFnbmV0Oj94dD11cm46YnRpaDowMDExMjIzMzQ0NTU2Njc3ODg5OWFhYmJjY2RkZWVmZjAwMTEyMjMz",
            "/download/two" to "bWFnbmV0Oj94dD11cm46YnRpaDpmZmVlZGRjY2JiYWE5OTg4Nzc2NjU1NDQzMzIyMTEwMGZmZWVkZGNj",
        )
        /** 按 URL 返回结果页或对应下载页的 recording fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** url 用于选择固定合成响应，不访问互联网。 */
            textHandler = { url ->
                if (url.contains("/search?")) {
                    searchFixture
                } else {
                    /** entry 是当前下载页与 Base64 对照，只匹配 URL 尾部。 */
                    magnetPage(checkNotNull(encodedMagnets.entries.firstOrNull { entry -> url.endsWith(entry.key) }?.value))
                }
            },
        )
        /** 待测 Provider。 */
        val provider = BlueRomsProvider(networkClient)

        /** 搜索结果应保持 DOM 顺序。 */
        val results = provider.search("A&B\n C\u0000", Category.ALL)

        /** torrent 是当前顺序结果，用于提取名称。 */
        assertEquals(listOf("Synthetic Game One - Console One", "Synthetic Game Two - Console Two"), results.map { torrent -> torrent.name })
        /** torrent 是当前顺序结果，用于提取规范 hash。 */
        assertEquals(listOf("00112233445566778899aabbccddeeff00112233", "ffeeddccbbaa99887766554433221100ffeeddcc"), results.map { torrent -> torrent.infoHash })
        assertEquals("700 MB", results.first().size)
        assertEquals(Category.GAMES, results.first().category)
        assertEquals("magnet:?xt=urn:btih:00112233445566778899aabbccddeeff00112233", results.first().magnetUri)
        assertEquals(ProviderSafetyLevel.SAFE, provider.descriptor.safetyLevel)
        assertFalse(provider.descriptor.enabledByDefault)
        assertEquals(
            listOf(
                "https://www.blueroms.ws/search?g=0&p=0&q=A%26B%20C",
                "https://www.blueroms.ws/download/one",
                "https://www.blueroms.ws/download/two",
            ),
            networkClient.textUrls,
        )
    }

    /** 合法空容器返回空；缺字段、非法 Base64 和解码后非 magnet 都仅跳过单条。 */
    @Test
    fun searchHandlesEmptyMissingAndInvalidEncodedMagnets() = runTest {
        /** 空结果页、混合结果页和下载响应按调用轮次返回。 */
        var pageCallCount = 0
        /** 一条缺标题、两条无效下载值和一条合法记录。 */
        val mixedPage = """
            <html><body><div class="row"><div class="col-xs-12">
              <div class="card"><div class="card-footer"><a href="/download/missing">x</a></div></div>
              <div class="card"><h4 class="card-title"><a href="/game/bad">Bad Base64</a></h4><div class="card-footer"><a href="/download/bad">x</a></div></div>
              <div class="card"><h4 class="card-title"><a href="/game/http">Not Magnet</a></h4><div class="card-footer"><a href="/download/http">x</a></div></div>
              <div class="card"><h4 class="card-title"><a href="/game/valid">Valid</a></h4><div class="card-footer"><a href="/download/valid">x</a></div></div>
            </div></div></body></html>
        """.trimIndent()
        /** 返回合法空页、混合页和三类 Base64 下载页。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** url 区分搜索轮次和下载页类型。 */
            textHandler = { url ->
                when {
                    url.contains("/search?") -> {
                        pageCallCount += 1
                        if (pageCallCount == 1) "<html><body><div class=\"row\"><div class=\"col-xs-12\"></div></div></body></html>" else mixedPage
                    }
                    url.endsWith("/bad") -> magnetPage("%%%")
                    url.endsWith("/http") -> magnetPage("aHR0cHM6Ly93d3cuYmx1ZXJvbXMud3Mvbm90LW1hZ25ldA==")
                    else -> magnetPage("bWFnbmV0Oj94dD11cm46YnRpaDowMDExMjIzMzQ0NTU2Njc3ODg5OWFhYmJjY2RkZWVmZjAwMTEyMjMz")
                }
            },
        )
        /** 待测 Provider。 */
        val provider = BlueRomsProvider(networkClient)

        assertTrue(provider.search("empty", Category.GAMES).isEmpty())
        /** torrent 是混合页面唯一合法结果。 */
        assertEquals(listOf("Valid"), provider.search("mixed", Category.GAMES).map { torrent -> torrent.name })
    }

    /** 缺顶层容器必须抛无正文的稳定解析异常。 */
    @Test
    fun searchRejectsMalformedTopLevelHtml() = runTest {
        /** 不含结果容器的挑战页。 */
        val provider = BlueRomsProvider(FakeTorrentSearchNetworkClient(textHandler = { "<html><body>challenge</body></html>" }))

        /** 稳定解析失败对象。 */
        val failure = runCatching { provider.search("fixture", Category.ALL) }.exceptionOrNull()
        assertTrue(failure is ProviderParseException)
        assertNull(failure?.message)
    }

    /** 取消首个下载页请求后不得访问后续下载页。 */
    @Test
    fun cancellationStopsBeforeNextDownloadRequest() = runTest {
        /** 两条结果的正常 fixture。 */
        val searchFixture = readProviderFixture("provider/blueroms/search_normal.html")
        /** 搜索页后首个下载页直接取消。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** url 只允许结果页成功。 */
            textHandler = { url -> if (url.contains("/search?")) searchFixture else throw CancellationException("fixture cancellation") },
        )

        /** 取消必须原样传播。 */
        val failure = runCatching { BlueRomsProvider(networkClient).search("fixture", Category.GAMES) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(listOf("https://www.blueroms.ws/search?g=0&p=0&q=fixture", "https://www.blueroms.ws/download/one"), networkClient.textUrls)
    }

    /** 详情按顺序保留可信海报/截图，并通过第二阶段下载页取得 magnet。 */
    @Test
    fun detailsMapsTrustedPosterScreenshotsAndMagnet() = runTest {
        /** 完整详情页 fixture。 */
        val detailsFixture = readProviderFixture("provider/blueroms/details_normal.html")
        /** 按阶段返回详情页和 Base64 下载页。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** url 用于区分详情与下载阶段。 */
            textHandler = { url -> if (url.endsWith("/game/detail")) detailsFixture else magnetPage("bWFnbmV0Oj94dD11cm46YnRpaDowMDExMjIzMzQ0NTU2Njc3ODg5OWFhYmJjY2RkZWVmZjAwMTEyMjMz") },
        )

        /** 非空详情结果。 */
        val details = checkNotNull(BlueRomsProvider(networkClient).getDetails("https://www.blueroms.ws/game/detail"))

        assertEquals("Synthetic Game Detail - Console Detail", details.name)
        assertEquals("2 GB", details.size)
        assertEquals("https://www.blueroms.ws/static/game/poster.jpg", details.posterUrl)
        assertEquals(
            listOf("https://www.blueroms.ws/static/game/screen-1.jpg", "https://www.blueroms.ws/static/game/screen-2.jpg"),
            details.screenshotUrls,
        )
        assertEquals(listOf("https://www.blueroms.ws/game/detail", "https://www.blueroms.ws/download/detail"), networkClient.textUrls)
    }

    /** 详情下载页的非法 Base64 返回 null，跨 host 截图在下载请求前拒绝。 */
    @Test
    fun detailsRejectsInvalidDownloadPayloadAndUnlistedPoster() = runTest {
        /** 正常详情 fixture 用于验证下载页失败契约。 */
        val detailsFixture = readProviderFixture("provider/blueroms/details_normal.html")
        /** 详情页后返回非法 Base64。 */
        val invalidPayloadClient = FakeTorrentSearchNetworkClient(
            /** url 区分详情与下载阶段。 */
            textHandler = { url -> if (url.endsWith("/game/detail")) detailsFixture else magnetPage("%%not-base64%%") },
        )
        /** 截图指向未列入 manifest 的 host。 */
        val maliciousResourcePage = "<html><body><main><h3 class=\"custom-title\">Bad Screenshot</h3><div class=\"panel\"><div class=\"panel-heading\"><h3>Screenshots</h3></div><img class=\"img-thumbnail\" src=\"https://evil.invalid/screen.jpg\"></div><a href=\"/download/detail\">x</a></main></body></html>"
        /** 只应读取详情页，不能继续访问下载页。 */
        val maliciousResourceClient = FakeTorrentSearchNetworkClient(textHandler = { maliciousResourcePage })

        assertNull(BlueRomsProvider(invalidPayloadClient).getDetails("https://www.blueroms.ws/game/detail"))
        /** 跨 host 截图的稳定拒绝结果。 */
        val resourceFailure = runCatching {
            BlueRomsProvider(maliciousResourceClient).getDetails("https://www.blueroms.ws/game/resource")
        }.exceptionOrNull()
        assertTrue(resourceFailure is HttpDeniedException)
        assertEquals(listOf("https://www.blueroms.ws/game/resource"), maliciousResourceClient.textUrls)
    }

    /** 搜索、详情与资源中的恶意 host 必须在对应网络或模型输出前拒绝。 */
    @Test
    fun rejectsUnlistedHostsBeforeNetworkOrResourceOutput() = runTest {
        /** 结果页携带恶意下载地址。 */
        val maliciousSearch = "<html><body><div class=\"row\"><div class=\"col-xs-12\"><div class=\"card\"><h4 class=\"card-title\"><a href=\"/game/one\">One</a></h4><div class=\"card-footer\"><a href=\"https://evil.invalid/download\">x</a></div></div></div></div></body></html>"
        /** 只允许首个搜索请求的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(textHandler = { maliciousSearch })
        /** 待测 Provider。 */
        val provider = BlueRomsProvider(networkClient)

        /** 恶意下载 host 的拒绝结果。 */
        val searchFailure = runCatching { provider.search("fixture", Category.GAMES) }.exceptionOrNull()
        /** 恶意直接详情 host 的拒绝结果。 */
        val detailsFailure = runCatching { provider.getDetails("https://evil.invalid/game") }.exceptionOrNull()

        assertTrue(searchFailure is HttpDeniedException)
        assertTrue(detailsFailure is HttpDeniedException)
        assertEquals(1, networkClient.textUrls.size)
    }
}

/** 生成只含固定 Base64 属性的最小下载页面。 */
private fun magnetPage(encodedMagnet: String): String = "<html><body><button id=\"magnet-button\" data-link=\"$encodedMagnet\"></button></body></html>"
