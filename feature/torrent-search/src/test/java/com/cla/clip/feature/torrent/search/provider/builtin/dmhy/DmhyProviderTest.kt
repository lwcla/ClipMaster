package com.cla.clip.feature.torrent.search.provider.builtin.dmhy

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dmhy 分类 URL、列表 DOM、详情 HTML 与安全地址契约测试。 */
class DmhyProviderTest {
    /** 所有固定分类及不支持分类必须结构化编码成既定 sort_id。 */
    @Test
    fun searchBuildsEncodedUrlsForEveryCategory() = runTest {
        /** 合法空列表避免 URL 测试依赖结果映射。 */
        val emptyHtml = "<html><body><table id=\"topic_list\"><tbody></tbody></table></body></html>"
        /** 记录每次 URL 的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(textHandler = { emptyHtml })
        /** 待测 Provider。 */
        val provider = DmhyProvider(networkClient)
        /** 分类与固定 sort_id；站点不支持的分类回退 0。 */
        val categoryIds = linkedMapOf(
            Category.ALL to 0,
            Category.ANIME to 2,
            Category.BOOKS to 3,
            Category.GAMES to 9,
            Category.MUSIC to 4,
            Category.SERIES to 6,
            Category.OTHER to 1,
            Category.APPS to 0,
            Category.MOVIES to 0,
            Category.PORN to 0,
        )

        /** entry 是当前分类和预期 ID。 */
        categoryIds.forEach { (category, _) -> provider.search("A&B\n C\u0000", category) }

        /** entry 是当前分类和预期 URL 中 sort_id。 */
        val expectedUrls = categoryIds.map { (_, categoryId) ->
            "https://share.dmhy.org/topics/list?keyword=A%26B%20C&sort_id=$categoryId&team_id=0&order=date-desc"
        }
        assertEquals(expectedUrls, networkClient.textUrls)
        assertEquals("dmhy", provider.descriptor.id.value)
        assertEquals(
            setOf(Category.ANIME, Category.BOOKS, Category.GAMES, Category.MUSIC, Category.SERIES, Category.OTHER),
            provider.descriptor.supportedCategories,
        )
        assertEquals(ProviderSafetyLevel.SAFE, provider.descriptor.safetyLevel)
        assertFalse(provider.descriptor.enabledByDefault)
    }

    /** latest 与 top 必须各自发出独立同 URL 请求，不能通过一次调用复用结果。 */
    @Test
    fun latestAndTopIssueIndependentRequests() = runTest {
        /** 每次返回合法空 DOM。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            textHandler = { "<table id=\"topic_list\"><tbody></tbody></table>" },
        )
        /** 同一 Provider 连续调用两个能力。 */
        val provider = DmhyProvider(networkClient)

        assertTrue(provider.getLatestTorrents(Category.ANIME).isEmpty())
        assertTrue(provider.getTopTorrents(Category.ANIME).isEmpty())
        assertEquals(
            listOf(
                "https://share.dmhy.org/topics/list/sort_id/2",
                "https://share.dmhy.org/topics/list/sort_id/2",
            ),
            networkClient.textUrls,
        )
    }

    /** 正常列表必须保留 magnet，规范化 btih、大小、UTC 日期、类别与同站详情 URL。 */
    @Test
    fun searchMapsNormalFixture() = runTest {
        /** 返回固定正常列表 fixture。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            textHandler = { readProviderFixture("provider/dmhy/search_normal.html") },
        )

        /** 唯一合法列表结果。 */
        val torrent = DmhyProvider(networkClient).search("fixture", Category.ALL).single()

        assertEquals("00112233445566778899aabbccddeeff00112233", torrent.infoHash)
        assertEquals("Synthetic Dmhy", torrent.name)
        assertEquals("700 MB", torrent.size)
        assertEquals(12u, torrent.seeders)
        assertEquals(3u, torrent.peers)
        assertEquals(1_709_528_760_000L, torrent.uploadEpochMillis)
        assertEquals(Category.ANIME, torrent.category)
        assertEquals("https://share.dmhy.org/topics/view/42", torrent.descriptionPageUrl)
        assertEquals(
            "magnet:?xt=urn:btih:00112233445566778899AABBCCDDEEFF00112233&dn=fixture",
            torrent.magnetUri,
        )
    }

    /** 空 tbody 是合法空结果，缺少固定 table 才是顶层协议错误。 */
    @Test
    fun searchDistinguishesEmptyTableFromMalformedTopLevel() = runTest {
        /** 返回合法空 tbody 的 Provider。 */
        val emptyProvider = DmhyProvider(
            FakeTorrentSearchNetworkClient(textHandler = { "<table id=\"topic_list\"><tbody></tbody></table>" }),
        )
        assertTrue(emptyProvider.search("fixture", Category.ALL).isEmpty())

        /** 缺固定 table 的 Provider。 */
        val malformedProvider = DmhyProvider(
            FakeTorrentSearchNetworkClient(textHandler = { "<html><body><p>unexpected</p></body></html>" }),
        )
        /** 协议错误不能包含正文。 */
        val failure = runCatching { malformedProvider.search("fixture", Category.ALL) }.exceptionOrNull()
        assertTrue(failure is ProviderParseException)
        assertNull(failure?.message)
    }

    /** 缺必填或非法 magnet 的行必须跳过，跨 host 详情不能进入模型。 */
    @Test
    fun searchSkipsInvalidRowsAndDropsCrossHostDetailsUrl() = runTest {
        /** 合成表包含缺名称、非法 magnet 与仅详情地址恶意的三行。 */
        val html = """
            <table id="topic_list"><tbody>
              <tr><td></td><td></td><td class="title"></td><td><a href="magnet:?xt=urn:btih:00112233445566778899aabbccddeeff00112233">M</a></td></tr>
              <tr><td></td><td></td><td class="title"><a href="/topics/view/bad">Bad</a></td><td><a href="magnet:?xt=urn:btih:nope">M</a></td></tr>
              <tr><td></td><td></td><td class="title"><a href="https://evil.invalid/topics/view/42">Valid</a></td><td><a href="magnet:?xt=urn:btih:00112233445566778899aabbccddeeff00112233">M</a></td></tr>
            </tbody></table>
        """.trimIndent()
        /** 返回混合 DOM 的 Provider。 */
        val provider = DmhyProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))

        /** 仅第三行业务必填合法，恶意详情地址必须被丢弃。 */
        val torrent = provider.search("fixture", Category.ALL).single()
        assertEquals("Valid", torrent.name)
        assertNull(torrent.descriptionPageUrl)
    }

    /** 详情输入必须在联网前拒绝非 HTTPS 或跨 host 地址。 */
    @Test
    fun detailsRejectsUnsafeUrlsBeforeNetwork() = runTest {
        /** 网络回调执行即表示白名单边界失效。 */
        val networkClient = FakeTorrentSearchNetworkClient(textHandler = { error("不应请求非法详情地址") })
        /** 待测 Provider。 */
        val provider = DmhyProvider(networkClient)
        /** 明文与跨 host 两类恶意输入。 */
        val invalidUrls = listOf(
            "http://share.dmhy.org/topics/view/42",
            "https://evil.invalid/topics/view/42",
        )

        /** url 是当前被拒绝详情地址。 */
        invalidUrls.forEach { url ->
            assertTrue(runCatching { provider.getDetails(url) }.exceptionOrNull() is HttpDeniedException)
        }
        assertTrue(networkClient.textUrls.isEmpty())
    }

    /** 正常详情必须保留 magnet 与不可信 HTML，并删除描述固定前两个子节点。 */
    @Test
    fun detailsMapsNormalFixtureAndTrimsDescriptionPrefix() = runTest {
        /** 返回固定详情 fixture。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            textHandler = { readProviderFixture("provider/dmhy/details_normal.html") },
        )

        /** 非空详情结果。 */
        val details = checkNotNull(DmhyProvider(networkClient).getDetails("https://share.dmhy.org/topics/view/42"))

        assertEquals("00112233445566778899aabbccddeeff00112233", details.infoHash)
        assertEquals("Synthetic Dmhy Detail", details.name)
        assertEquals("1 GB", details.size)
        assertEquals(1_709_510_400_000L, details.uploadEpochMillis)
        assertEquals(Category.SERIES, details.category)
        assertEquals("<p>Visible <strong>HTML</strong></p>", details.description)
        assertEquals(
            "magnet:?xt=urn:btih:00112233445566778899AABBCCDDEEFF00112233&dn=fixture",
            details.magnetUri,
        )
    }

    /** 详情缺名称或非法 magnet 返回 null，非法日期只降级日期且恶意类别链接不扩权。 */
    @Test
    fun detailsHandlesInvalidRequiredDateAndCategoryFields() = runTest {
        /** 缺名称但包含合法 magnet 的详情 HTML。 */
        val missingNameHtml = """<div class="topic-title"></div><div id="resource-tabs"><div id="tabs-1"><p></p><p><a id="a_magnet" href="magnet:?xt=urn:btih:00112233445566778899aabbccddeeff00112233">M</a></p></div></div>"""
        /** 缺名称应返回 null。 */
        val missingNameProvider = DmhyProvider(FakeTorrentSearchNetworkClient(textHandler = { missingNameHtml }))
        assertNull(missingNameProvider.getDetails("https://share.dmhy.org/topics/view/42"))

        /** 必填合法但日期污染、类别跨 host 的详情 DOM。 */
        val degradedHtml = """
            <div class="topic-title"><h3>Degraded</h3><div class="info"><ul>
              <li><span><a href="https://evil.invalid/topics/list/sort_id/2">Anime</a></span></li>
              <li><span>not-a-date trailing</span></li><li></li><li></li><li></li><li><span>2GB</span></li>
            </ul></div></div>
            <div id="resource-tabs"><div id="tabs-1"><p></p><p><a id="a_magnet" href="magnet:?xt=urn:btih:00112233445566778899aabbccddeeff00112233">M</a></p></div></div>
        """.trimIndent()
        /** 返回降级字段 DOM 的 Provider。 */
        val degradedProvider = DmhyProvider(FakeTorrentSearchNetworkClient(textHandler = { degradedHtml }))
        /** 业务必填仍合法的详情。 */
        val details = checkNotNull(degradedProvider.getDetails("https://share.dmhy.org/topics/view/42"))
        assertNull(details.uploadEpochMillis)
        assertEquals(Category.OTHER, details.category)
    }
}
