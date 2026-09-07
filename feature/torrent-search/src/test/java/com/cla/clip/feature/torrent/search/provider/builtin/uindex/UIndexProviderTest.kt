package com.cla.clip.feature.torrent.search.provider.builtin.uindex

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.LatestTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.provider.TopTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UIndex 精确容器、分类、注入时钟、括号日期和 URL 边界测试。 */
class UIndexProviderTest {
    /** descriptor 必须保留 Cloudflare 元数据并采用 manifest NSFW/default-off。 */
    @Test
    fun metadataAndCapabilitiesMatchManifest() {
        /** provider 不联网即可检查固定元数据。 */
        val provider: SearchProvider = UIndexProvider(FakeTorrentSearchNetworkClient())

        assertEquals("uindex", provider.descriptor.id.value)
        assertEquals(ProviderSafetyLevel.NSFW, provider.descriptor.safetyLevel)
        assertFalse(provider.descriptor.enabledByDefault)
        assertTrue(provider.descriptor.isCloudflareProtected)
        assertEquals("https://uindex.org/search.php?search=ubuntu", provider.descriptor.cloudflareSolverUrl)
        assertEquals(
            setOf(
                Category.ANIME,
                Category.APPS,
                Category.GAMES,
                Category.MOVIES,
                Category.MUSIC,
                Category.PORN,
                Category.SERIES,
                Category.OTHER,
            ),
            provider.descriptor.supportedCategories,
        )
        assertTrue(provider is TorrentDetailsProvider)
        assertTrue(provider is LatestTorrentsProvider)
        assertTrue(provider is TopTorrentsProvider)
    }

    /** 十类 search/latest/top 必须完整映射，BOOKS 与 ALL 都回退 c=0。 */
    @Test
    fun allCategoryRequestsMatchReviewedMatrix() = runBlocking {
        /** expectedIds 是固定上游的完整分类编号。 */
        val expectedIds = mapOf(
            Category.ALL to "0", Category.ANIME to "7", Category.APPS to "5",
            Category.BOOKS to "0", Category.GAMES to "3", Category.MOVIES to "1",
            Category.MUSIC to "4", Category.PORN to "6", Category.SERIES to "2",
            Category.OTHER to "8",
        )
        /** client 记录全部能力请求。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { EMPTY_HTML })
        /** provider 复用同一实例以验证没有 null 插值。 */
        val provider = UIndexProvider(client)

        /** category 是当前矩阵输入。 */
        for (category in Category.entries) {
            provider.search("x", category)
            /** searchUrl 是当前搜索请求。 */
            val searchUrl = client.textUrls.last().toHttpUrl()
            assertEquals("/search.php", searchUrl.encodedPath)
            assertEquals("x", searchUrl.queryParameter("search"))
            assertEquals(expectedIds.getValue(category), searchUrl.queryParameter("c"))
            provider.getLatestTorrents(category)
            /** latestUrl 是当前最新请求。 */
            val latestUrl = client.textUrls.last().toHttpUrl()
            assertEquals(expectedIds.getValue(category), latestUrl.queryParameter("c"))
            assertNull(latestUrl.queryParameter("search"))
            provider.getTopTorrents(category)
            /** topUrl 是当前热门请求。 */
            val topUrl = client.textUrls.last().toHttpUrl()
            assertEquals("/top.php", topUrl.encodedPath)
            assertEquals("24h", topUrl.queryParameter("t"))
            assertEquals(expectedIds.getValue(category), topUrl.queryParameter("c"))
            assertFalse(topUrl.toString().contains("null"))
        }
    }

    /** search 必须清洗编码查询，并按注入时钟解析人数与相对日期。 */
    @Test
    fun searchParsesNormalFixtureWithInjectedClock() = runBlocking {
        /** client 返回固定 UIndex 结果页。 */
        val client = FakeTorrentSearchNetworkClient(
            textHandler = { readProviderFixture("provider/uindex/search_normal.html") },
        )
        /** item 是唯一合法结果。 */
        val item = UIndexProvider(client, currentTimeMillis = { NOW })
            .search("  fi\u0000xture /?  ", Category.SERIES)
            .single()
        /** request 是结构化搜索地址。 */
        val request = client.textUrls.single().toHttpUrl()

        assertEquals("fixture /?", request.queryParameter("search"))
        assertEquals(INFO_HASH, item.infoHash)
        assertEquals(VALID_MAGNET, item.magnetUri)
        assertEquals(1_234u, item.seeders)
        assertEquals(5_678u, item.peers)
        assertEquals(1709214300000L, item.uploadEpochMillis)
        assertEquals(Category.SERIES, item.category)
        assertEquals("https://uindex.org/details.php?id=1", item.descriptionPageUrl)
    }

    /** selector 必须只使用首个 sr/top 精确类容器，近似类名不能命中。 */
    @Test
    fun resultsUseFirstExactSrOrTopContainer() = runBlocking {
        /** firstRow 与 secondRow 使用不同身份区分容器。 */
        val firstRow = row("First", INFO_HASH)
        /** secondRow 放在第二个匹配容器，不能被合并。 */
        val secondRow = row("Second", SECOND_HASH)
        /** html 前置近似类名，再放两个真实匹配容器。 */
        val html = """
            <table class="sr-table-copy"><tbody>$secondRow</tbody></table>
            <table class="top-table"><tbody>$firstRow</tbody></table>
            <table class="sr-table"><tbody>$secondRow</tbody></table>
        """.trimIndent()
        /** items 只能来自文档顺序中的首个真实匹配 top-table。 */
        val items = search(html)

        assertEquals(listOf("First"), items.map { it.name })
    }

    /** 空页、漂移、缺名称、坏 magnet 和 UInt 越界必须按字段契约处理。 */
    @Test
    fun malformedRowsUseRequiredAndOptionalFailureContracts() = runBlocking {
        /** badPages 覆盖无容器、近似容器、空名称和坏 hash。 */
        val badPages = listOf(
            EMPTY_HTML,
            "<table class='sr-table-copy'><tbody>${row("Near", INFO_HASH)}</tbody></table>",
            "<table class='sr-table'><tbody>${row("", INFO_HASH)}</tbody></table>",
            "<table class='sr-table'><tbody>${row("Bad", "bad")}</tbody></table>",
        )
        /** html 是当前必填坏页面。 */
        for (html in badPages) {
            assertTrue(search(html).isEmpty())
        }
        /** overflowHtml 保持合法身份但破坏人数与日期。 */
        val overflowHtml = readProviderFixture("provider/uindex/search_normal.html")
            .replace("1,234", "4,294,967,296")
            .replace("5,678", "-1")
            .replace("5 minutes ago", "unknown")
        /** item 的可选字段应独立为空。 */
        val item = search(overflowHtml).single()
        assertNull(item.seeders)
        assertNull(item.peers)
        assertNull(item.uploadEpochMillis)
    }

    /** 详情必须裁剪完整括号日期、映射 TV，并解析同站海报和不可信描述。 */
    @Test
    fun detailsParseBracketedDatesCategoryAndPoster() = runBlocking {
        /** details 是固定 UIndex 详情页解析结果。 */
        val details = requireNotNull(
            details(readProviderFixture("provider/uindex/details_normal.html")),
        )

        assertEquals(INFO_HASH, details.infoHash)
        assertEquals(VALID_MAGNET, details.magnetUri)
        assertEquals(1709214330000L, details.uploadEpochMillis)
        assertEquals(1709252100000L, details.lastCheckedEpochMillis)
        assertEquals(1_234u, details.seeders)
        assertEquals(5_678u, details.peers)
        assertEquals(Category.SERIES, details.category)
        assertEquals("https://uindex.org/images/sample.jpg", details.posterUrl)
        assertTrue(requireNotNull(details.description).contains("<strong>description</strong>"))
    }

    /** XXX、TV 与未知详情类别必须分别映射 PORN、SERIES 与 OTHER。 */
    @Test
    fun categoryMappingCoversReviewedFallbacks() = runBlocking {
        /** normalHtml 是详情结构基线。 */
        val normalHtml = readProviderFixture("provider/uindex/details_normal.html")
        /** expectedCategories 是页面文本到领域类别的独立期望。 */
        val expectedCategories = mapOf(
            "XXX" to Category.PORN,
            "TV" to Category.SERIES,
            "Unknown" to Category.OTHER,
        )

        /** rawCategory 是页面类别，expectedCategory 是领域期望。 */
        for ((rawCategory, expectedCategory) in expectedCategories) {
            /** parsed 是本轮详情。 */
            val parsed = requireNotNull(details(normalHtml.replace(">TV</a>", ">$rawCategory</a>")))
            assertEquals(expectedCategory, parsed.category)
        }
    }

    /** 坏括号、坏日期、坏人数和恶意海报只清空可选字段。 */
    @Test
    fun malformedOptionalDetailsFieldsDegradeIndependently() = runBlocking {
        /** html 同时破坏两类日期、人数和海报地址。 */
        val html = readProviderFixture("provider/uindex/details_normal.html")
            .replace(" (2024-02-29 13:45:30) ", "(2024-02-29 13:45:30")
            .replace("(2024-03-01 00:15:00)", "(2024-02-30 00:15:00)")
            .replace("1,234", "4,294,967,296")
            .replace("5,678", "-1")
            .replace("/images/sample.jpg", "https://user@uindex.org/image")
        /** details 保留名称和 magnet 身份。 */
        val details = requireNotNull(details(html))

        assertNull(details.uploadEpochMillis)
        assertNull(details.lastCheckedEpochMillis)
        assertNull(details.seeders)
        assertNull(details.peers)
        assertNull(details.posterUrl)
    }

    /** 详情名称与合法 magnet 必填，列表非法可选详情地址不丢合法结果。 */
    @Test
    fun requiredIdentityAndOptionalUrlsUseSeparateFailureContracts() = runBlocking {
        /** normalDetails 是详情基线。 */
        val normalDetails = readProviderFixture("provider/uindex/details_normal.html")
        assertNull(details(normalDetails.replace("Sample Alpha", "")))
        assertNull(details(normalDetails.replace(INFO_HASH, "bad")))
        /** unsafeUrls 覆盖可选列表详情的全部 authority 风险。 */
        val unsafeUrls = listOf(
            "https://evil.invalid/a",
            "http://uindex.org/a",
            "https://user@uindex.org/a",
            "https://uindex.org:444/a",
        )
        /** unsafeUrl 是当前污染的详情候选。 */
        for (unsafeUrl in unsafeUrls) {
            /** item 仍保留合法名称和 magnet。 */
            val item = search(
                "<table class='sr-table'><tbody>${row("Alpha", INFO_HASH, unsafeUrl)}</tbody></table>",
            ).single()
            assertNull(item.descriptionPageUrl)
        }
    }

    /** 非法详情 authority 必须在网络前拒绝，显式 443 允许。 */
    @Test
    fun validatesDetailsAuthorityBeforeNetwork() = runBlocking {
        /** client 用于证明拒绝分支没有网络。 */
        val client = FakeTorrentSearchNetworkClient(
            textHandler = { readProviderFixture("provider/uindex/details_normal.html") },
        )
        /** provider 是详情入口。 */
        val provider = UIndexProvider(client)
        /** url 是当前恶意输入。 */
        for (url in UNSAFE_URLS) {
            /** failure 是低敏拒绝。 */
            val failure = runCatching { provider.getDetails(url) }.exceptionOrNull()
            assertTrue(failure is HttpDeniedException)
        }
        assertTrue(client.textUrls.isEmpty())
        requireNotNull(provider.getDetails("https://uindex.org:443/details.php?id=1"))
        assertEquals(1, client.textUrls.size)
    }

    /** 通过公开 Provider 搜索入口解析给定 HTML。 */
    private suspend fun search(
        /** 当前合成结果页。 */
        html: String,
    ) = UIndexProvider(
        networkClient = FakeTorrentSearchNetworkClient(textHandler = { html }),
        currentTimeMillis = { NOW },
    ).search("x", Category.ALL)

    /** 通过公开 Provider 详情入口解析给定 HTML。 */
    private suspend fun details(
        /** 当前合成详情页。 */
        html: String,
    ) = UIndexProvider(
        networkClient = FakeTorrentSearchNetworkClient(textHandler = { html }),
        currentTimeMillis = { NOW },
    ).getDetails("https://uindex.org/details.php?id=1")

    /** 构造 UIndex 固定 sr 类名单行。 */
    private fun row(
        /** 结果名称。 */
        name: String,
        /** magnet 中的 hash 候选。 */
        infoHash: String,
        /** 详情链接候选。 */
        detailsHref: String = "/details.php?id=1",
    ): String = """
        <tr>
          <td class="sr-col-name">
            <a class="sr-torrent-link" href="$detailsHref">$name</a>
            <a class="sr-magnet" href="magnet:?xt=urn:btih:$infoHash">Magnet</a>
          </td>
          <td class="sr-col-size">1 GB</td>
          <td class="sr-col-seeders"><span class="sr-seed">1</span></td>
          <td class="sr-col-leechers"><span class="sr-leech">2</span></td>
          <td class="sr-col-uploaded">5 minutes ago</td>
          <td class="sr-col-cat"><a class="sr-cat-badge">TV</a></td>
        </tr>
    """.trimIndent()

    /** 固定测试协议常量。 */
    private companion object {
        /** 注入时钟固定在列表日期五分钟之后。 */
        private const val NOW = 1709214600000L
        /** 中性 40 位 hash。 */
        private const val INFO_HASH = "0123456789abcdef0123456789abcdef01234567"
        /** 第二个中性 40 位 hash。 */
        private const val SECOND_HASH = "89abcdef0123456789abcdef0123456789abcdef"
        /** 中性合法 magnet。 */
        private const val VALID_MAGNET = "magnet:?xt=urn:btih:$INFO_HASH"
        /** UIndex 无匹配容器的合法空页。 */
        private const val EMPTY_HTML = "<div class='empty-results'></div>"
        /** 输入详情地址的全部 authority 风险。 */
        private val UNSAFE_URLS = listOf(
            "https://evil.invalid/a",
            "http://uindex.org/a",
            "https://user@uindex.org/a",
            "https://uindex.org:444/a",
        )
    }
}
