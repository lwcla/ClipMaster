package com.cla.clip.feature.torrent.search.provider.builtin.anirena

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AniRena 搜索/浏览/详情、NSFW 审计与磁力重定向安全契约测试。 */
class AniRenaProviderTest {
    /** 正常 fixture 必须映射分类、秒级时间、跳转 magnet 和本地 NSFW 默认关闭结论。 */
    @Test
    fun searchMapsNormalFixtureAndUsesReviewedSafetyMetadata() = runTest {
        /** AniRena 搜索页合成 HTML。 */
        val fixture = readProviderFixture("provider/anirena/search_normal.html")
        /** 搜索页与磁力跳转的可控网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            textHandler = { fixture },
            redirectHandler = { "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567" },
        )
        /** 待测 AniRena Provider。 */
        val provider = AniRenaProvider(networkClient)

        /** 唯一合法搜索结果。 */
        val torrent = provider.search("fixture", Category.ANIME).single()

        assertEquals("anirena", provider.descriptor.id.value)
        assertEquals(ProviderSafetyLevel.NSFW, provider.descriptor.safetyLevel)
        assertFalse(provider.descriptor.enabledByDefault)
        assertEquals("0123456789abcdef0123456789abcdef01234567", torrent.infoHash)
        assertEquals("Synthetic AniRena Result", torrent.name)
        assertEquals("700 MiB", torrent.size)
        assertEquals(18u, torrent.seeders)
        assertEquals(5u, torrent.peers)
        assertEquals(1714998600000L, torrent.uploadEpochMillis)
        assertEquals(Category.ANIME, torrent.category)
        assertEquals("https://anirena.com/view/202", torrent.descriptionPageUrl)
        assertEquals("https://anirena.com/download/202.torrent", torrent.fileDownloadLink)
        assertEquals(listOf("https://anirena.com/redirect/202"), networkClient.redirectUrls)
    }

    /** 搜索、latest 和 top 必须统一使用分类映射与 HttpUrl 查询编码。 */
    @Test
    fun searchLatestAndTopBuildEncodedCategoryUrls() = runTest {
        /** 只返回合法空表格以观测 URL 的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            textHandler = { "<html><body><table class=\"tl-table\"><tbody></tbody></table></body></html>" },
        )
        /** 待测 AniRena Provider。 */
        val provider = AniRenaProvider(networkClient)

        provider.search("A&B\n C\u0000", Category.PORN)
        provider.getLatestTorrents(Category.BOOKS)
        provider.getTopTorrents(Category.SERIES)

        assertEquals(
            listOf(
                "https://anirena.com/?q=A%26B%20C&page=1&cat=hentai",
                "https://anirena.com/?page=1&cat=manga",
                "https://anirena.com/?page=1&cat=live",
            ),
            networkClient.textUrls,
        )
    }

    /** 详情 fixture 必须映射描述、海报、上传者、UTC 时间并通过跳转取得 magnet。 */
    @Test
    fun detailsMapsNormalFixtureExactly() = runTest {
        /** AniRena 详情页合成 HTML。 */
        val fixture = readProviderFixture("provider/anirena/details_normal.html")
        /** 详情页与磁力跳转的可控网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            textHandler = { fixture },
            redirectHandler = { "magnet:?xt=urn:btih:FEDCBA9876543210FEDCBA9876543210FEDCBA98" },
        )
        /** 待测 AniRena Provider。 */
        val provider = AniRenaProvider(networkClient)

        /** 非空的 AniRena 详情结果。 */
        val details = checkNotNull(provider.getDetails("https://anirena.com/view/202"))

        assertEquals("fedcba9876543210fedcba9876543210fedcba98", details.infoHash)
        assertEquals("Synthetic AniRena Detail", details.name)
        assertEquals("3.2 GiB", details.size)
        assertEquals(9u, details.seeders)
        assertEquals(2u, details.peers)
        assertEquals(1714998600000L, details.uploadEpochMillis)
        assertEquals(Category.PORN, details.category)
        assertEquals("Synthetic Uploader", details.uploader)
        assertEquals("Synthetic description", details.description)
        assertEquals("https://anirena.com/posters/202.jpg", details.posterUrl)
        assertEquals("https://anirena.com/download/detail-202.torrent", details.fileDownloadLink)
    }

    /** 合法空表格返回空列表，缺必填字段的单条不阻断后续合法条目。 */
    @Test
    fun searchHandlesEmptyAndMissingRequiredRows() = runTest {
        /** 一条缺名称与一条合法行的合成 AniRena 表格。 */
        val mixedFixture = """
            <html><body><table class="tl-table"><tbody>
              <tr><td class="col-actions"><div class="tl-actions"><a href="/redirect/broken">m</a></div></td></tr>
              <tr data-created-ts="1"><td class="col-name"><div class="tl-name-wrap"><a class="tl-torrent-name" href="/view/valid">valid</a></div></td><td class="col-actions"><div class="tl-actions"><a href="/redirect/valid">m</a></div></td></tr>
            </tbody></table></body></html>
        """.trimIndent()
        /** 网络调用次数，用于先返回空表格再返回混合表格。 */
        var callCount = 0
        /** 提供两组页面及统一合法 magnet 的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            textHandler = {
                callCount += 1
                if (callCount == 1) "<html><body><table class=\"tl-table\"><tbody></tbody></table></body></html>" else mixedFixture
            },
            redirectHandler = { "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567" },
        )
        /** 待测 AniRena Provider。 */
        val provider = AniRenaProvider(networkClient)

        assertTrue(provider.search("empty", Category.ALL).isEmpty())
        /** torrent 是混合表格中保留的合法行，只取名称检查跳过行为。 */
        assertEquals(listOf("valid"), provider.search("mixed", Category.ALL).map { torrent -> torrent.name })
    }

    /** 缺失 AniRena 顶层表格时必须抛出稳定 ProviderParseException。 */
    @Test
    fun searchRejectsMalformedTopLevelHtml() = runTest {
        /** 不包含预期表格的合成挑战页。 */
        val networkClient = FakeTorrentSearchNetworkClient(textHandler = { "<html><body>challenge</body></html>" })
        /** 待测 AniRena Provider。 */
        val provider = AniRenaProvider(networkClient)

        /** 顶层结构异常的稳定失败对象。 */
        val failure = runCatching { provider.search("fixture", Category.ALL) }.exceptionOrNull()
        assertTrue(failure is ProviderParseException)
    }

    /** 详情页与结果内的恶意重定向 host 都必须在对应网络请求前拒绝。 */
    @Test
    fun rejectsUnlistedDetailsAndRedirectHostsBeforeTheirNetworkCalls() = runTest {
        /** 包含恶意磁力跳转地址的合成结果表格。 */
        val maliciousFixture = """
            <html><body><table class="tl-table"><tbody><tr><td class="col-name"><div class="tl-name-wrap"><a class="tl-torrent-name" href="/view/valid">valid</a></div></td><td class="col-actions"><div class="tl-actions"><a href="https://evil.invalid/redirect">m</a></div></td></tr></tbody></table></body></html>
        """.trimIndent()
        /** 只允许页面读取、不应进入 redirect fake 的网络边界。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            textHandler = { maliciousFixture },
            redirectHandler = { error("不应请求恶意跳转 host") },
        )
        /** 待测 AniRena Provider。 */
        val provider = AniRenaProvider(networkClient)

        /** 恶意详情 host 的请求前拒绝结果。 */
        val detailsFailure = runCatching { provider.getDetails("https://evil.invalid/view/1") }.exceptionOrNull()
        /** 恶意跳转 host 的请求前拒绝结果。 */
        val redirectFailure = runCatching { provider.search("fixture", Category.ALL) }.exceptionOrNull()

        assertTrue(detailsFailure is HttpDeniedException)
        assertTrue(redirectFailure is HttpDeniedException)
        assertEquals(1, networkClient.textUrls.size)
        assertTrue(networkClient.redirectUrls.isEmpty())
    }

    /** 重定向 Location 不是 magnet scheme 时必须拒绝，不能生成可见 torrent。 */
    @Test
    fun rejectsNonMagnetRedirectLocation() = runTest {
        /** AniRena 搜索页合成 HTML。 */
        val fixture = readProviderFixture("provider/anirena/search_normal.html")
        /** 故意返回 HTTPS 而非 magnet Location 的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            textHandler = { fixture },
            redirectHandler = { "https://anirena.com/not-a-magnet" },
        )

        /** 非 magnet Location 的稳定拒绝结果。 */
        val failure = runCatching { AniRenaProvider(networkClient).search("fixture", Category.ALL) }.exceptionOrNull()

        assertTrue(failure is HttpDeniedException)
    }
}
