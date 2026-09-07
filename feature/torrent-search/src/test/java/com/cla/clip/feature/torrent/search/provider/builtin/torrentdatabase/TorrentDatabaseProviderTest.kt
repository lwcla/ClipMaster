package com.cla.clip.feature.torrent.search.provider.builtin.torrentdatabase

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.LatestTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.provider.TopTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TorrentDatabase marker、tracker、BOOKS 路径、偏移日期和 URL 边界测试。 */
class TorrentDatabaseProviderTest {
    /** descriptor 必须保留 Cloudflare 元数据并采用 manifest NSFW/default-off。 */
    @Test
    fun metadataAndCapabilitiesMatchManifest() {
        /** provider 不联网即可检查固定元数据。 */
        val provider: SearchProvider = TorrentDatabaseProvider(FakeTorrentSearchNetworkClient())

        assertEquals("torrentdatabase", provider.descriptor.id.value)
        assertEquals(ProviderSafetyLevel.NSFW, provider.descriptor.safetyLevel)
        assertFalse(provider.descriptor.enabledByDefault)
        assertTrue(provider.descriptor.isCloudflareProtected)
        assertEquals(
            setOf(
                Category.APPS,
                Category.BOOKS,
                Category.GAMES,
                Category.MOVIES,
                Category.MUSIC,
                Category.PORN,
                Category.SERIES,
            ),
            provider.descriptor.supportedCategories,
        )
        assertTrue(provider is TorrentDetailsProvider)
        assertTrue(provider is LatestTorrentsProvider)
        assertTrue(provider is TopTorrentsProvider)
    }

    /** 十类 search/latest/top 必须锁定 e-books 与 ebooks 的差异及未知 top 无请求。 */
    @Test
    fun allCategoryRequestsMatchReviewedMatrix() = runBlocking {
        /** searchCategories 是搜索 query 的固定分类值。 */
        val searchCategories = mapOf(
            Category.ALL to null, Category.ANIME to null, Category.APPS to "software",
            Category.BOOKS to "e-books", Category.GAMES to "games", Category.MOVIES to "movies",
            Category.MUSIC to "music", Category.PORN to "porn", Category.SERIES to "tv",
            Category.OTHER to null,
        )
        /** latestPaths 是最新能力的固定路径。 */
        val latestPaths = mapOf(
            Category.ALL to "/newest", Category.ANIME to "/newest",
            Category.APPS to "/newest_software", Category.BOOKS to "/newest_ebooks",
            Category.GAMES to "/newest_games", Category.MOVIES to "/newest_movies",
            Category.MUSIC to "/newest_music", Category.PORN to "/newest_porn",
            Category.SERIES to "/newest_tv", Category.OTHER to "/newest",
        )
        /** topPaths 为 null 的输入必须直接空返回且不联网。 */
        val topPaths = mapOf(
            Category.ALL to "/newest", Category.ANIME to null,
            Category.APPS to "/top_seeded_software", Category.BOOKS to "/top_seeded_ebooks",
            Category.GAMES to "/top_seeded_games", Category.MOVIES to "/top_seeded_movies",
            Category.MUSIC to "/top_seeded_music", Category.PORN to "/top_seeded_porn",
            Category.SERIES to "/top_seeded_tv", Category.OTHER to null,
        )
        /** client 记录全部请求。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { EMPTY_HTML })
        /** provider 复用同一实例验证独立调用。 */
        val provider = TorrentDatabaseProvider(client)

        /** category 是当前矩阵输入。 */
        for (category in Category.entries) {
            provider.search("x", category)
            /** searchUrl 是当前搜索请求。 */
            val searchUrl = client.textUrls.last().toHttpUrl()
            assertEquals("/newest", searchUrl.encodedPath)
            assertEquals("x", searchUrl.queryParameter("q"))
            assertEquals(searchCategories.getValue(category), searchUrl.queryParameter("category"))
            provider.getLatestTorrents(category)
            assertEquals(latestPaths.getValue(category), client.textUrls.last().toHttpUrl().encodedPath)
            /** requestsBeforeTop 用于证明不支持分类不联网。 */
            val requestsBeforeTop = client.textUrls.size
            /** topResults 对未知分类必须为空。 */
            val topResults = provider.getTopTorrents(category)
            /** expectedTopPath 是手写矩阵期望。 */
            val expectedTopPath = topPaths.getValue(category)
            if (expectedTopPath == null) {
                assertTrue(topResults.isEmpty())
                assertEquals(requestsBeforeTop, client.textUrls.size)
            } else {
                assertEquals(requestsBeforeTop + 1, client.textUrls.size)
                assertEquals(expectedTopPath, client.textUrls.last().toHttpUrl().encodedPath)
            }
        }
    }

    /** search 必须清洗编码查询并从精确 marker 路径构造含 21 个 tracker 的 magnet。 */
    @Test
    fun searchParsesMarkerAndAppendsTrackerExactlyOnce() = runBlocking {
        /** client 返回固定 TorrentDatabase 列表。 */
        val client = FakeTorrentSearchNetworkClient(
            textHandler = { readProviderFixture("provider/torrentdatabase/search_normal.html") },
        )
        /** item 是唯一合法结果。 */
        val item = TorrentDatabaseProvider(client)
            .search("  fi\u0000xture /?  ", Category.BOOKS)
            .single()
        /** request 是结构化搜索地址。 */
        val request = client.textUrls.single().toHttpUrl()
        /** trackerValues 是逐项只解码一次后的 tracker 列表。 */
        val trackerValues = decodedTrackerValues(requireNotNull(item.magnetUri))

        assertEquals("fixture /?", request.queryParameter("q"))
        assertEquals("e-books", request.queryParameter("category"))
        assertEquals(INFO_HASH, item.infoHash)
        assertEquals(Category.BOOKS, item.category)
        assertEquals(1709214330000L, item.uploadEpochMillis)
        assertEquals("https://developify.ca/torrent/1", item.descriptionPageUrl)
        assertEquals(21, trackerValues.size)
        assertEquals("https://developify.ca/announce", trackerValues.last())
        assertFalse(requireNotNull(item.magnetUri).contains("%25"))
    }

    /** marker 必须是同站 HTTPS 精确三段路径，query 不能提供或替换 hash。 */
    @Test
    fun markerRejectsUnsafeAuthorityAndNonExactPaths() = runBlocking {
        /** invalidMarkers 覆盖伪 host、端口、userinfo、HTTP、额外路径和 query 冒充。 */
        val invalidMarkers = listOf(
            "https://evil.invalid/track/magnet/$INFO_HASH",
            "https://developify.ca:444/track/magnet/$INFO_HASH",
            "https://user@developify.ca/track/magnet/$INFO_HASH",
            "http://developify.ca/track/magnet/$INFO_HASH",
            "/track/magnet/$INFO_HASH/extra",
            "/track/magnet/bad?hash=$INFO_HASH",
            "/other/track/magnet/$INFO_HASH",
        )

        /** marker 是当前不可信第二 anchor 地址。 */
        for (marker in invalidMarkers) {
            assertTrue(search(row(marker = marker)).isEmpty())
        }
        /** validQuery 证明 query 可以存在但 hash 只来自固定路径段。 */
        val validQuery = search(row(marker = "/track/magnet/$INFO_HASH?source=list")).single()
        assertEquals(INFO_HASH, validQuery.infoHash)
    }

    /** 空页、selector 漂移、空名称与坏 hash 都必须跳过，人数坏值只降级。 */
    @Test
    fun malformedRowsUseRequiredAndOptionalFailureContracts() = runBlocking {
        /** badPages 覆盖合法空、漂移、缺名和非法 hash。 */
        val badPages = listOf(
            EMPTY_HTML,
            "<div class='changed-layout'>x</div>",
            row(name = ""),
            row(marker = "/track/magnet/bad"),
        )
        /** html 是当前独立坏页面。 */
        for (html in badPages) {
            assertTrue(search(html).isEmpty())
        }
        /** optionalHtml 破坏日期和两个人数字段。 */
        val optionalHtml = readProviderFixture("provider/torrentdatabase/search_normal.html")
            .replace("2024-02-29 13:45:30", "2024-02-30 25:61:00")
            .replace("<span>12</span>", "<span>4294967296</span>")
            .replace("<span>3</span>", "<span>-1</span>")
        /** item 保留合法 marker 身份。 */
        val item = search(optionalHtml).single()
        assertNull(item.uploadEpochMillis)
        assertNull(item.seeders)
        assertNull(item.peers)
    }

    /** 可选详情地址非法时只清空字段，合法相对地址按列表页解析。 */
    @Test
    fun optionalDetailsUrlUsesReviewedAuthority() = runBlocking {
        /** unsafeUrls 覆盖 scheme、host、userinfo 和异常端口。 */
        val unsafeUrls = listOf(
            "https://evil.invalid/a",
            "http://developify.ca/a",
            "https://user@developify.ca/a",
            "https://developify.ca:444/a",
        )
        /** unsafeUrl 是当前污染的第一 anchor。 */
        for (unsafeUrl in unsafeUrls) {
            /** item 仍保留 marker 身份。 */
            val item = search(row(detailsHref = unsafeUrl)).single()
            assertNull(item.descriptionPageUrl)
        }
        /** valid 是相对详情地址解析结果。 */
        val valid = search(row(detailsHref = "/torrent/2")).single()
        assertEquals("https://developify.ca/torrent/2", valid.descriptionPageUrl)
    }

    /** ALL top 每次必须重新请求 newest，不能共享 latest 结果。 */
    @Test
    fun allTopAlwaysMakesAnIndependentNewestRequest() = runBlocking {
        /** client 记录两个独立 top 调用。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { EMPTY_HTML })
        /** provider 是热门入口。 */
        val provider = TorrentDatabaseProvider(client)

        provider.getTopTorrents(Category.ALL)
        provider.getTopTorrents(Category.ALL)

        assertEquals(2, client.textUrls.size)
        assertTrue(client.textUrls.all { it.toHttpUrl().encodedPath == "/newest" })
    }

    /** 详情必须解析 +09:00 偏移、无偏移 lastChecked 与 AudioBooks 类别。 */
    @Test
    fun detailsParseOffsetDateAndBooksCategory() = runBlocking {
        /** details 是固定详情 fixture 解析结果。 */
        val details = requireNotNull(
            details(readProviderFixture("provider/torrentdatabase/details_normal.html")),
        )

        assertEquals(INFO_HASH, details.infoHash)
        assertEquals(VALID_MAGNET, details.magnetUri)
        assertEquals(1709181930000L, details.uploadEpochMillis)
        assertEquals(1709252100000L, details.lastCheckedEpochMillis)
        assertEquals("1.5 GB", details.size)
        assertEquals(Category.BOOKS, details.category)
        assertNull(details.fileDownloadLink)
        assertTrue(requireNotNull(details.description).contains("<strong>description</strong>"))
    }

    /** 上传时间无偏移时回退严格 UTC，坏偏移和坏 lastChecked 只清空时间。 */
    @Test
    fun detailsDateFallbackAndMalformedValuesAreStable() = runBlocking {
        /** normalHtml 是详情结构基线。 */
        val normalHtml = readProviderFixture("provider/torrentdatabase/details_normal.html")
        /** utcDetails 用无偏移 UTC 时间覆盖回退路径。 */
        val utcDetails = requireNotNull(
            details(normalHtml.replace("2024-02-29 13:45:30+09:00", "2024-02-29 13:45:30")),
        )
        assertEquals(1709214330000L, utcDetails.uploadEpochMillis)
        /** malformed 同时破坏偏移上传时间和 lastChecked。 */
        val malformed = requireNotNull(
            details(
                normalHtml
                    .replace("2024-02-29 13:45:30+09:00", "2024-02-29 13:45:30+0900")
                    .replace("2024-03-01 00:15:00", "2024-02-30 00:15:00"),
            ),
        )
        assertNull(malformed.uploadEpochMillis)
        assertNull(malformed.lastCheckedEpochMillis)
    }

    /** E-Books 与 AudioBooks 映射 BOOKS，未知类别回退 OTHER。 */
    @Test
    fun detailsCategoryMappingCoversBooksAndFallback() = runBlocking {
        /** normalHtml 是详情结构基线。 */
        val normalHtml = readProviderFixture("provider/torrentdatabase/details_normal.html")
        /** expectedCategories 是页面类别与领域期望。 */
        val expectedCategories = mapOf(
            "E-Books" to Category.BOOKS,
            "AudioBooks" to Category.BOOKS,
            "Unknown" to Category.OTHER,
        )
        /** rawCategory 与 expectedCategory 是本轮映射输入输出。 */
        for ((rawCategory, expectedCategory) in expectedCategories) {
            /** parsed 是当前详情。 */
            val parsed = requireNotNull(
                details(normalHtml.replace(">AudioBooks</span>", ">$rawCategory</span>")),
            )
            assertEquals(expectedCategory, parsed.category)
        }
    }

    /** 详情名称与合法 magnet 必填，坏可选人数和日期不应丢详情。 */
    @Test
    fun detailsRequiredAndOptionalFieldsHaveSeparateFailures() = runBlocking {
        /** normalHtml 是详情结构基线。 */
        val normalHtml = readProviderFixture("provider/torrentdatabase/details_normal.html")
        assertNull(details(normalHtml.replace("Sample Alpha", "")))
        assertNull(details(normalHtml.replace(INFO_HASH, "bad")))
        /** optionalHtml 只污染可选人数。 */
        val optionalHtml = normalHtml
            .replace(">12</strong>", ">4294967296</strong>")
            .replace(">3</strong>", ">-1</strong>")
        /** parsed 保留合法详情身份。 */
        val parsed = requireNotNull(details(optionalHtml))
        assertNull(parsed.seeders)
        assertNull(parsed.peers)
    }

    /** 非法详情 authority 必须在网络前拒绝，显式 443 允许。 */
    @Test
    fun validatesDetailsAuthorityBeforeNetwork() = runBlocking {
        /** client 用于证明拒绝分支没有网络。 */
        val client = FakeTorrentSearchNetworkClient(
            textHandler = { readProviderFixture("provider/torrentdatabase/details_normal.html") },
        )
        /** provider 是详情入口。 */
        val provider = TorrentDatabaseProvider(client)
        /** url 是当前恶意输入。 */
        for (url in UNSAFE_URLS) {
            /** failure 是低敏拒绝。 */
            val failure = runCatching { provider.getDetails(url) }.exceptionOrNull()
            assertTrue(failure is HttpDeniedException)
        }
        assertTrue(client.textUrls.isEmpty())
        requireNotNull(provider.getDetails("https://developify.ca:443/torrent/1"))
        assertEquals(1, client.textUrls.size)
    }

    /** 通过公开 Provider 搜索入口解析给定 HTML。 */
    private suspend fun search(
        /** 当前合成结果页。 */
        html: String,
    ) = TorrentDatabaseProvider(
        FakeTorrentSearchNetworkClient(textHandler = { html }),
    ).search("x", Category.ALL)

    /** 通过公开 Provider 详情入口解析给定 HTML。 */
    private suspend fun details(
        /** 当前合成详情页。 */
        html: String,
    ) = TorrentDatabaseProvider(
        FakeTorrentSearchNetworkClient(textHandler = { html }),
    ).getDetails("https://developify.ca/torrent/1")

    /** 构造一项 TorrentDatabase 固定表格结果。 */
    private fun row(
        /** 结果名称。 */
        name: String = "Alpha",
        /** 第二 anchor 的 marker 候选。 */
        marker: String = "/track/magnet/$INFO_HASH",
        /** 第一 anchor 的可选详情候选。 */
        detailsHref: String = "/torrent/1",
    ): String = """
        <table class="torrent-table"><tbody><tr>
          <td><a href="$detailsHref">Details</a><a href="$marker">$name</a></td>
          <td><span class="category-bubble">E-Books</span></td>
          <td class="size-cell">1 GB</td>
          <td class="date-cell">2024-02-29 13:45:30</td>
          <td><div><span>1</span><span>2</span></div></td>
        </tr></tbody></table>
    """.trimIndent()

    /** 从 opaque magnet 原始查询中逐项解码 tracker 值。 */
    private fun decodedTrackerValues(
        /** 待检查的完整 magnet。 */
        magnetUri: String,
    ): List<String> {
        /** rawQuery 是问号后的原始查询文本。 */
        val rawQuery = checkNotNull(URI(magnetUri).rawSchemeSpecificPart).removePrefix("?")
        return rawQuery.split('&').mapNotNull {
            /** parameter 是当前原始查询参数。 */
            parameter ->
            /** parts 将参数名和值最多拆为两段。 */
            val parts = parameter.split('=', limit = 2)
            if (parts.size == 2 && parts[0] == "tr") {
                URLDecoder.decode(parts[1], Charsets.UTF_8.name())
            } else {
                null
            }
        }
    }

    /** 固定测试协议常量。 */
    private companion object {
        /** 中性 40 位 hash。 */
        private const val INFO_HASH = "0123456789abcdef0123456789abcdef01234567"
        /** 中性合法 magnet。 */
        private const val VALID_MAGNET = "magnet:?xt=urn:btih:$INFO_HASH"
        /** TorrentDatabase 无条目的合法空页。 */
        private const val EMPTY_HTML = "<table class='torrent-table'><tbody></tbody></table>"
        /** 输入详情地址的全部 authority 风险。 */
        private val UNSAFE_URLS = listOf(
            "https://evil.invalid/a",
            "http://developify.ca/a",
            "https://user@developify.ca/a",
            "https://developify.ca:444/a",
        )
    }
}
