package com.cla.clip.feature.torrent.search.provider.builtin.bt4g

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

/** BT4G 两阶段 hash、Cloudflare、分类 URL 与外域 marker 安全契约测试。 */
class Bt4gProviderTest {
    /** 正常搜索按 DOM 顺序请求详情，只离线提取 marker hash 并映射字段。 */
    @Test
    fun searchReadsHashesInOrderWithoutRequestingMarkerHost() = runTest {
        /** 两条 BT4G 结果页 fixture。 */
        val searchFixture = readProviderFixture("provider/bt4g/search_normal.html")
        /** 注入相对日期计算的固定时刻。 */
        val now = 1_700_000_000_000L
        /** 按详情地址返回不同 marker hash。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** url 区分搜索页与两个同站详情页。 */
            textHandler = { url ->
                if (url.contains("/search?")) searchFixture else markerPage(if (url.endsWith("/one")) "00112233445566778899AABBCCDDEEFF00112233" else "FFEEDDCCBBAA99887766554433221100FFEEDDCC")
            },
        )
        /** 待测 Provider。 */
        val provider = Bt4gProvider(networkClient, currentTimeMillis = { now })

        /** 搜索结果应保持 DOM 顺序。 */
        val results = provider.search("A&B\n C\u0000", Category.MOVIES)

        /** torrent 是当前结果，用于提取名称。 */
        assertEquals(listOf("Synthetic BT4G One", "Synthetic BT4G Two"), results.map { torrent -> torrent.name })
        /** torrent 是当前结果，用于提取规范 hash。 */
        assertEquals(listOf("00112233445566778899aabbccddeeff00112233", "ffeeddccbbaa99887766554433221100ffeeddcc"), results.map { torrent -> torrent.infoHash })
        assertEquals("700 MB", results.first().size)
        assertEquals(12u, results.first().seeders)
        assertEquals(3u, results.first().peers)
        assertEquals(1709510400000L, results.first().uploadEpochMillis)
        assertEquals(now - 2L * 60L * 60L * 1000L, results.last().uploadEpochMillis)
        assertEquals(Category.MOVIES, results.first().category)
        assertNull(results.first().magnetUri)
        assertEquals(
            listOf(
                "https://bt4gprx.com/search?q=A%26B%20C&category=movie&orderby=seeders&p=1",
                "https://bt4gprx.com/torrent/one",
                "https://bt4gprx.com/torrent/two",
            ),
            networkClient.textUrls,
        )
    }

    /** descriptor 必须固定 Cloudflare 元数据、分类能力、SAFE 与默认关闭。 */
    @Test
    fun descriptorExposesReviewedCloudflareAndSafetyMetadata() {
        /** 不会访问的网络 fake。 */
        val provider = Bt4gProvider(FakeTorrentSearchNetworkClient())

        assertEquals("bt4g", provider.descriptor.id.value)
        assertEquals(ProviderSafetyLevel.SAFE, provider.descriptor.safetyLevel)
        assertFalse(provider.descriptor.enabledByDefault)
        assertTrue(provider.descriptor.isCloudflareProtected)
        assertEquals("https://bt4gprx.com/search?q=ubuntu", provider.descriptor.cloudflareSolverUrl)
        assertEquals(setOf(Category.APPS, Category.BOOKS, Category.MOVIES, Category.MUSIC, Category.OTHER), provider.descriptor.supportedCategories)
    }

    /** 分类回退、latest 与 top 必须使用固定 URL，不把未支持分类透传。 */
    @Test
    fun searchLatestAndTopBuildFixedUrls() = runTest {
        /** 合法空结果容器。 */
        val networkClient = FakeTorrentSearchNetworkClient(textHandler = { "<html><body><main><div class=\"notion-list\"></div></main></body></html>" })
        /** 待测 Provider。 */
        val provider = Bt4gProvider(networkClient)

        provider.search("fixture", Category.PORN)
        provider.search("fixture", Category.BOOKS)
        provider.getLatestTorrents(Category.MUSIC)
        provider.getTopTorrents(Category.APPS)

        assertEquals(
            listOf(
                "https://bt4gprx.com/search?q=fixture&category=all&orderby=seeders&p=1",
                "https://bt4gprx.com/search?q=fixture&category=doc&orderby=seeders&p=1",
                "https://bt4gprx.com/new",
                "https://bt4gprx.com/week",
            ),
            networkClient.textUrls,
        )
    }

    /** 缺顶层结果容器抛低敏异常，恶意 marker 作为缺失 hash 跳过。 */
    @Test
    fun searchRejectsMalformedTopLevelAndSkipsMaliciousMarkers() = runTest {
        /** 单条合法外形的结果页。 */
        val searchFixture = readProviderFixture("provider/bt4g/search_normal.html").replaceAfter("</div></main>", "</body></html>")
        /** 首次返回挑战页，随后返回一条结果及恶意 marker。 */
        var callCount = 0
        /** 所有响应均为合成内容。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** 调用序号区分顶层异常、结果页与详情页。 */
            textHandler = {
                callCount += 1
                when (callCount) {
                    1 -> "<html><body>challenge</body></html>"
                    2 -> searchFixture
                    else -> "<html><body><main><a href=\"//downloadtorrentfile.com.evil/hash/00112233445566778899aabbccddeeff00112233\">bad</a></main></body></html>"
                }
            },
        )
        /** 待测 Provider。 */
        val provider = Bt4gProvider(networkClient)

        /** 顶层协议失败。 */
        val malformedFailure = runCatching { provider.search("bad", Category.ALL) }.exceptionOrNull()
        assertTrue(malformedFailure is ProviderParseException)
        assertNull(malformedFailure?.message)
        assertTrue(provider.search("marker", Category.ALL).isEmpty())
        /** 只能出现 bt4gprx.com 请求，marker 外域绝不能进入 fake。 */
        assertTrue(networkClient.textUrls.all { url -> url.startsWith("https://bt4gprx.com/") })
    }

    /** 缺名称的结果只跳过，合法后续记录仍按同站详情 marker 产出。 */
    @Test
    fun searchSkipsMissingRowsAndRetainsValidResult() = runTest {
        /** 一条缺标题和一条合法结果的最小页面。 */
        val page = """
            <html><body><main><div class="notion-list">
              <div class="notion-list-item"><div class="notion-list-item-title"></div></div>
              <div class="notion-list-item"><div class="notion-list-item-title"><a href="/torrent/valid">Valid</a></div></div>
            </div></main></body></html>
        """.trimIndent()
        /** 搜索页后只返回合法 marker。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** url 区分搜索和详情阶段。 */
            textHandler = { url -> if (url.contains("/search?")) page else markerPage("00112233445566778899aabbccddeeff00112233") },
        )

        /** torrent 是唯一合法结果。 */
        assertEquals(listOf("Valid"), Bt4gProvider(networkClient).search("fixture", Category.ALL).map { torrent -> torrent.name })
        assertEquals(2, networkClient.textUrls.size)
    }

    /** 取消首个详情请求后不得访问后续详情页。 */
    @Test
    fun cancellationStopsBeforeNextDetailsRequest() = runTest {
        /** 两条搜索结果 fixture。 */
        val searchFixture = readProviderFixture("provider/bt4g/search_normal.html")
        /** 搜索页后首个详情请求触发取消。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** url 只允许搜索页返回。 */
            textHandler = { url -> if (url.contains("/search?")) searchFixture else throw CancellationException("fixture cancellation") },
        )

        /** 取消必须原样传播。 */
        val failure = runCatching { Bt4gProvider(networkClient).search("fixture", Category.ALL) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(listOf("https://bt4gprx.com/search?q=fixture&category=all&orderby=seeders&p=1", "https://bt4gprx.com/torrent/one"), networkClient.textUrls)
    }

    /** 详情映射固定 hash/magnet、统计、类别、日期与相对 lastChecked。 */
    @Test
    fun detailsMapsFieldsAndCanonicalMagnet() = runTest {
        /** 完整详情 fixture。 */
        val detailsFixture = readProviderFixture("provider/bt4g/details_normal.html")
        /** 固定相对时间基准。 */
        val now = 1_700_000_000_000L
        /** 仅返回详情 fixture 的网络 fake。 */
        val provider = Bt4gProvider(FakeTorrentSearchNetworkClient(textHandler = { detailsFixture }), currentTimeMillis = { now })

        /** 非空详情结果。 */
        val details = checkNotNull(provider.getDetails("https://bt4gprx.com/torrent/one"))

        assertEquals("00112233445566778899aabbccddeeff00112233", details.infoHash)
        assertTrue(details.magnetUri.startsWith("magnet:?xt=urn:btih:00112233445566778899aabbccddeeff00112233"))
        assertEquals("2 GB", details.size)
        assertEquals(21u, details.seeders)
        assertEquals(4u, details.peers)
        assertEquals(1709510400000L, details.uploadEpochMillis)
        assertEquals(Category.APPS, details.category)
        assertEquals(now - 3L * 60L * 60L * 1000L, details.lastCheckedEpochMillis)
    }

    /** 详情缺名称或 marker 使用绝对外域 URL 时按缺必填字段返回 null且不联网。 */
    @Test
    fun detailsRejectsMissingNameAndNonSchemeRelativeMarker() = runTest {
        /** 首次页面缺名称，第二次页面用不允许的绝对 marker。 */
        var callCount = 0
        /** 两种详情内容都只通过同站网络读取。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** 调用次数选择详情边界。 */
            textHandler = {
                callCount += 1
                if (callCount == 1) {
                    markerPage("00112233445566778899aabbccddeeff00112233")
                } else {
                    "<html><body><main><h1 class=\"notion-detail-title\">Name</h1><a href=\"https://downloadtorrentfile.com/hash/00112233445566778899aabbccddeeff00112233\">bad</a></main></body></html>"
                }
            },
        )
        /** 待测 Provider。 */
        val provider = Bt4gProvider(networkClient)

        assertNull(provider.getDetails("https://bt4gprx.com/torrent/missing-name"))
        assertNull(provider.getDetails("https://bt4gprx.com/torrent/absolute-marker"))
        /** url 是实际请求地址，必须始终属于 BT4G 唯一 manifest host。 */
        assertTrue(networkClient.textUrls.all { url -> url.startsWith("https://bt4gprx.com/") })
    }

    /** 详情和结果中的跨 host 地址必须在网络前拒绝。 */
    @Test
    fun rejectsUnlistedDetailsHostsBeforeNetwork() = runTest {
        /** 搜索页含恶意详情地址。 */
        val page = "<html><body><main><div class=\"notion-list\"><div class=\"notion-list-item\"><div class=\"notion-list-item-title\"><a href=\"https://evil.invalid/torrent\">Bad</a></div></div></div></main></body></html>"
        /** 只允许搜索页请求。 */
        val networkClient = FakeTorrentSearchNetworkClient(textHandler = { page })
        /** 待测 Provider。 */
        val provider = Bt4gProvider(networkClient)

        /** 结果恶意 host 拒绝。 */
        val searchFailure = runCatching { provider.search("fixture", Category.ALL) }.exceptionOrNull()
        /** 直接恶意详情 host 拒绝。 */
        val detailsFailure = runCatching { provider.getDetails("https://evil.invalid/torrent") }.exceptionOrNull()

        assertTrue(searchFailure is HttpDeniedException)
        assertTrue(detailsFailure is HttpDeniedException)
        assertEquals(1, networkClient.textUrls.size)
    }
}

/** 构造只含精确外域 hash marker 的最小 BT4G 详情页。 */
private fun markerPage(hash: String): String = "<html><body><main><a href=\"//downloadtorrentfile.com/hash/$hash?source=fixture\">marker</a></main></body></html>"
