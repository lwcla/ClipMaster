package com.cla.clip.feature.torrent.search.provider.builtin.animetosho

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AnimeTosho 搜索、详情、日期与 host 白名单的合成契约测试。 */
class AnimeToshoProviderTest {
    /** 正常搜索 fixture 必须映射 seeds/peers、Today、magnet/hash 和 torrent 链接。 */
    @Test
    fun searchMapsNormalFixtureExactly() = runTest {
        /** AnimeTosho 搜索页合成 HTML。 */
        val fixture = readProviderFixture("provider/animetosho/search_normal.html")
        /** 固定当前时间的网络 fake，避免 Today 断言受真实日期影响。 */
        val networkClient = FakeTorrentSearchNetworkClient(textHandler = { fixture })
        /** 当前时间固定为 2026-09-01 UTC 中午。 */
        val provider = AnimeToshoProvider(networkClient, currentTimeMillis = { 1788264000000L })

        /** 唯一合法搜索结果。 */
        val torrent = provider.search("fixture", Category.ANIME).single()

        assertEquals("animetosho", provider.descriptor.id.value)
        assertEquals(ProviderSafetyLevel.SAFE, provider.descriptor.safetyLevel)
        assertEquals(true, provider.descriptor.enabledByDefault)
        assertEquals("89abcdef0123456789abcdef0123456789abcdef", torrent.infoHash)
        assertEquals("Synthetic Anime Result", torrent.name)
        assertEquals("1.5 GiB", torrent.size)
        assertEquals(12u, torrent.seeders)
        assertEquals(3u, torrent.peers)
        assertEquals(1788220800000L, torrent.uploadEpochMillis)
        assertEquals(Category.ANIME, torrent.category)
        assertEquals("https://animetosho.org/view/101", torrent.descriptionPageUrl)
        assertEquals("https://animetosho.org/storage/torrent/101.torrent", torrent.fileDownloadLink)
    }

    /** Yesterday 与固定 d/M/yyyy 必须使用 UTC 且不依赖设备时区。 */
    @Test
    fun searchParsesYesterdayAndFixedDateAgainstInjectedClock() = runTest {
        /** 两条只在日期和 hash 上不同的最小合成结果。 */
        val fixture = """
            <html><body><main id="content">
              <div class="home_list_entry"><div class="link"><a href="/view/1">Yesterday item</a></div><div class="size">1 MiB</div><div class="date" title="Date/time submitted: Yesterday 10:00"></div><div class="links"><a href="magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567">m</a></div></div>
              <div class="home_list_entry"><div class="link"><a href="/view/2">Fixed item</a></div><div class="size">2 MiB</div><div class="date" title="Date/time submitted: 6/5/2024 10:00"></div><div class="links"><a href="magnet:?xt=urn:btih:89abcdef0123456789abcdef0123456789abcdef">m</a></div></div>
            </main></body></html>
        """.trimIndent()
        /** 返回日期边界 fixture 的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(textHandler = { fixture })
        /** 注入 2026-09-01 UTC 的固定基准时间。 */
        val provider = AnimeToshoProvider(networkClient, currentTimeMillis = { 1788264000000L })

        /** 按 DOM 顺序返回的两条结果。 */
        val results = provider.search("fixture", Category.ANIME)

        assertEquals(1788134400000L, results[0].uploadEpochMillis)
        assertEquals(1714953600000L, results[1].uploadEpochMillis)
    }

    /** 详情 fixture 必须映射 name/hash/size/date/screenshots 及统一 Provider 元数据。 */
    @Test
    fun detailsMapsNormalFixtureExactly() = runTest {
        /** AnimeTosho 详情页合成 HTML。 */
        val fixture = readProviderFixture("provider/animetosho/details_normal.html")
        /** 返回详情 fixture 的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(textHandler = { fixture })
        /** 待测 AnimeTosho Provider。 */
        val provider = AnimeToshoProvider(networkClient, currentTimeMillis = { 1788264000000L })

        /** 非空的详情解析结果。 */
        val details = checkNotNull(provider.getDetails("https://animetosho.org/view/101"))

        assertEquals("Synthetic Anime Detail", details.name)
        assertEquals("89abcdef0123456789abcdef0123456789abcdef", details.infoHash)
        assertEquals("2 GiB", details.size)
        assertEquals(20u, details.seeders)
        assertEquals(4u, details.peers)
        assertEquals(1714998600000L, details.uploadEpochMillis)
        assertEquals(
            listOf("https://animetosho.org/screens/101-a.jpg", "https://animetosho.org/screens/101-b.jpg"),
            details.screenshotUrls,
        )
    }

    /** 合法空容器返回空结果，缺必填字段的单条不影响后续合法条目。 */
    @Test
    fun searchHandlesEmptyAndMissingRequiredEntries() = runTest {
        /** 包含一条缺 magnet 与一条合法结果的合成 HTML。 */
        val mixedFixture = """
            <html><body><main id="content">
              <div class="home_list_entry"><div class="link"><a href="/view/broken">broken</a></div><div class="size">1 MiB</div><div class="date" title="Date/time submitted: Today"></div><div class="links"></div></div>
              <div class="home_list_entry"><div class="link"><a href="/view/valid">valid</a></div><div class="size">1 MiB</div><div class="date" title="Date/time submitted: Today"></div><div class="links"><a href="magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567">m</a></div></div>
            </main></body></html>
        """.trimIndent()
        /** 首次返回空容器、第二次返回混合条目的调用计数。 */
        var callCount = 0
        /** 根据调用次数切换 fixture 的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            textHandler = {
                callCount += 1
                if (callCount == 1) "<html><body><main id=\"content\"></main></body></html>" else mixedFixture
            },
        )
        /** 待测 AnimeTosho Provider。 */
        val provider = AnimeToshoProvider(networkClient, currentTimeMillis = { 1788264000000L })

        assertTrue(provider.search("empty", Category.ANIME).isEmpty())
        /** torrent 是混合 fixture 中逐条容错后保留的合法条目。 */
        assertEquals(listOf("valid"), provider.search("mixed", Category.ANIME).map { torrent -> torrent.name })
    }

    /** 缺失站点顶层容器的 HTML 必须映射为 ProviderParseException。 */
    @Test
    fun searchRejectsMalformedTopLevelHtml() = runTest {
        /** 不包含 AnimeTosho 页面容器的合成挑战页。 */
        val networkClient = FakeTorrentSearchNetworkClient(textHandler = { "<html><body>challenge</body></html>" })
        /** 待测 AnimeTosho Provider。 */
        val provider = AnimeToshoProvider(networkClient, currentTimeMillis = { 1788264000000L })

        /** 搜索失败的稳定异常类型。 */
        val failure = runCatching { provider.search("fixture", Category.ANIME) }.exceptionOrNull()
        assertTrue(failure is ProviderParseException)
    }

    /** 查询使用 HttpUrl 精确编码并在进入网络前删除控制字符。 */
    @Test
    fun searchBuildsEncodedControlCharacterFreeUrl() = runTest {
        /** 返回合法空容器以仅观测请求 URL 的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(textHandler = { "<html><body><main id=\"content\"></main></body></html>" })

        AnimeToshoProvider(networkClient, currentTimeMillis = { 1788264000000L })
            .search("A&B\r\n C", Category.ANIME)

        assertEquals("https://animetosho.org/search?q=A%26B%20C", networkClient.textUrls.single())
    }

    /** 恶意详情 host 必须在 getText 之前拒绝，异常不回显地址。 */
    @Test
    fun detailsRejectsUnlistedHostBeforeNetwork() = runTest {
        /** 如果被调用就会暴露白名单失效的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(textHandler = { error("不应发起网络请求") })
        /** 待测 AnimeTosho Provider。 */
        val provider = AnimeToshoProvider(networkClient, currentTimeMillis = { 1788264000000L })

        /** 请求前 host 拒绝的稳定网络失败类型。 */
        val failure = runCatching { provider.getDetails("https://evil.invalid/view/101") }.exceptionOrNull()

        assertTrue(failure is HttpDeniedException)
        assertTrue(networkClient.textUrls.isEmpty())
    }
}
