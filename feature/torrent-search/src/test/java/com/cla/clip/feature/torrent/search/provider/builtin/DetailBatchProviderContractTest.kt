package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.provider.LatestTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.TopTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.common.FrenchTorrentCategoryMapper
import com.cla.clip.feature.torrent.search.provider.builtin.oxtorrent.OxTorrentProvider
import com.cla.clip.feature.torrent.search.provider.builtin.torrent9.Torrent9Provider
import com.cla.clip.feature.torrent.search.provider.builtin.testing.DetailBatchSite
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.zeromagnet.ZeroMagnetProvider
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 04E4 三站描述符、请求路径和法语字段边界的离线契约测试。 */
class DetailBatchProviderContractTest {
    /** 描述符直接决定安全模式、默认启用和能力筛选，必须符合本地审计。 */
    @Test
    fun descriptorsMatchLocalSafetyAudit() {
        /** client 不执行网络，只用于构造三个真实 Provider。 */
        val client = FakeTorrentSearchNetworkClient()
        /** ox 是 SAFE 且默认关闭的六分类来源。 */
        val ox = OxTorrentProvider(client).descriptor
        /** nine 是 SAFE 且默认关闭的六分类来源。 */
        val nine = Torrent9Provider(client).descriptor
        /** zero 是本地重新审计为 NSFW 的成人来源。 */
        val zero = ZeroMagnetProvider(client).descriptor

        assertEquals("oxtorrent", ox.id.value)
        assertEquals("https://oxtorrent.co", ox.baseUrl)
        assertEquals(ProviderSafetyLevel.SAFE, ox.safetyLevel)
        assertFalse(ox.enabledByDefault)
        assertEquals("torrent9", nine.id.value)
        assertEquals("https://www6.torrent9.to", nine.baseUrl)
        assertEquals(ProviderSafetyLevel.SAFE, nine.safetyLevel)
        assertFalse(nine.enabledByDefault)
        assertEquals("0magnet", zero.id.value)
        assertEquals("https://9mag.net", zero.baseUrl)
        assertEquals(setOf(Category.PORN), zero.supportedCategories)
        assertEquals(ProviderSafetyLevel.NSFW, zero.safetyLevel)
        assertFalse(zero.enabledByDefault)
    }

    /** 两个法语来源必须完整保留固定六类正反向映射，未知值保守归入其他。 */
    @Test
    fun frenchCategoriesUseFixedBidirectionalSlugs() {
        /** forwardCases 是手写的宿主分类到固定路径 slug 契约。 */
        val forwardCases = listOf(
            Category.APPS to "logiciels",
            Category.BOOKS to "ebook",
            Category.GAMES to "jeux-pc",
            Category.MOVIES to "films",
            Category.MUSIC to "musique",
            Category.SERIES to "series",
        )
        /** category 和 slug 是当前独立正向映射样本。 */
        for ((category, slug) in forwardCases) {
            assertEquals(slug, FrenchTorrentCategoryMapper.toSlug(category))
            assertEquals(category, FrenchTorrentCategoryMapper.fromSlug(slug))
        }
        assertEquals(Category.GAMES, FrenchTorrentCategoryMapper.fromSlug("jeux-consoles"))
        assertNull(FrenchTorrentCategoryMapper.toSlug(Category.ALL))
        assertNull(FrenchTorrentCategoryMapper.toSlug(Category.PORN))
        assertEquals(Category.OTHER, FrenchTorrentCategoryMapper.fromSlug("unknown"))
    }

    /** 搜索词必须先去控制字符，再作为单一路径段或 query 值编码。 */
    @Test
    fun searchRequestsKeepQueryInsideItsProtocolField() = runBlocking {
        /** rawQuery 覆盖控制字符、Unicode、路径、查询和片段分隔符。 */
        val rawQuery = " \r中 /x?y#z& \n"
        /** oxClient 返回合法空列表，只记录实际请求。 */
        val oxClient = FakeTorrentSearchNetworkClient(textHandler = { "<table><tbody></tbody></table>" })
        /** nineClient 返回合法空列表，只记录实际请求。 */
        val nineClient = FakeTorrentSearchNetworkClient(textHandler = { "<table><tbody></tbody></table>" })
        /** zeroClient 返回合法空列表，只记录实际请求。 */
        val zeroClient = FakeTorrentSearchNetworkClient(textHandler = { "<table class='file-list'><tbody></tbody></table>" })

        OxTorrentProvider(oxClient).search(rawQuery, Category.APPS)
        Torrent9Provider(nineClient).search(rawQuery, Category.APPS)
        ZeroMagnetProvider(zeroClient).search(rawQuery, Category.PORN)

        /** oxUrl 的查询只能位于最后一个路径段。 */
        val oxUrl = oxClient.textUrls.single().toHttpUrl()
        /** nineUrl 必须把 `.html` 与搜索词放在同一段。 */
        val nineUrl = nineClient.textUrls.single().toHttpUrl()
        /** zeroUrl 必须只使用固定 q 参数。 */
        val zeroUrl = zeroClient.textUrls.single().toHttpUrl()
        assertEquals(listOf("recherche", "logiciels", "中 /x?y#z&"), oxUrl.pathSegments)
        assertNull(oxUrl.query)
        assertEquals(listOf("search_torrent", "logiciels", "中 /x?y#z&.html"), nineUrl.pathSegments)
        assertNull(nineUrl.query)
        assertEquals(listOf("search"), zeroUrl.pathSegments)
        assertEquals("中 /x?y#z&", zeroUrl.queryParameter("q"))
        assertEquals(setOf("q"), zeroUrl.queryParameterNames)
    }

    /** ALL 和不支持类别执行无类别搜索，固定六类才增加来源 slug。 */
    @Test
    fun frenchSearchCategoryPathsMatchFixedSnapshot() = runBlocking {
        /** cases 手写全部十类期望的可选 slug，避免复用生产 mapper 生成断言。 */
        val cases = listOf(
            Category.ALL to null,
            Category.ANIME to null,
            Category.APPS to "logiciels",
            Category.BOOKS to "ebook",
            Category.GAMES to "jeux-pc",
            Category.MOVIES to "films",
            Category.MUSIC to "musique",
            Category.PORN to null,
            Category.SERIES to "series",
            Category.OTHER to null,
        )
        /** category 和 slug 是当前独立请求路径样本。 */
        for ((category, slug) in cases) {
            /** oxClient 只记录当前类别的列表请求。 */
            val oxClient = FakeTorrentSearchNetworkClient(textHandler = { "" })
            /** nineClient 只记录当前类别的列表请求。 */
            val nineClient = FakeTorrentSearchNetworkClient(textHandler = { "" })
            OxTorrentProvider(oxClient).search("fixture", category)
            Torrent9Provider(nineClient).search("fixture", category)
            /** oxExpected 是手写固定前缀、可选 slug 与查询段。 */
            val oxExpected = listOfNotNull("recherche", slug, "fixture")
            /** nineExpected 是手写固定前缀、可选 slug 与带后缀查询段。 */
            val nineExpected = listOfNotNull("search_torrent", slug, "fixture.html")
            assertEquals(oxExpected, oxClient.textUrls.single().toHttpUrl().pathSegments)
            assertEquals(nineExpected, nineClient.textUrls.single().toHttpUrl().pathSegments)
        }
    }

    /** 最新页只支持固定六类且不支持类别不得联网，热门页每次独立请求并忽略类别。 */
    @Test
    fun latestAndTopRequestsRespectBrowsingCapabilities() = runBlocking {
        /** oxClient 的空响应让测试只观察浏览路径。 */
        val oxClient = FakeTorrentSearchNetworkClient(textHandler = { "" })
        /** nineClient 的空响应让测试只观察浏览路径。 */
        val nineClient = FakeTorrentSearchNetworkClient(textHandler = { "" })
        /** ox 同时具备最新和热门能力。 */
        val ox = OxTorrentProvider(oxClient)
        /** nine 同时具备最新和热门能力。 */
        val nine = Torrent9Provider(nineClient)

        assertTrue((ox as LatestTorrentsProvider).getLatestTorrents(Category.ALL).isEmpty())
        assertTrue((nine as LatestTorrentsProvider).getLatestTorrents(Category.PORN).isEmpty())
        assertTrue(oxClient.textUrls.isEmpty())
        assertTrue(nineClient.textUrls.isEmpty())
        ox.getLatestTorrents(Category.BOOKS)
        nine.getLatestTorrents(Category.BOOKS)
        (ox as TopTorrentsProvider).getTopTorrents(Category.MOVIES)
        ox.getTopTorrents(Category.PORN)
        (nine as TopTorrentsProvider).getTopTorrents(Category.MOVIES)
        nine.getTopTorrents(Category.PORN)

        assertEquals(
            listOf("https://oxtorrent.co/torrents/ebook", "https://oxtorrent.co/top", "https://oxtorrent.co/top"),
            oxClient.textUrls,
        )
        assertEquals(
            listOf("https://www6.torrent9.to/torrents_ebook.html", "https://www6.torrent9.to/top_torrent.html", "https://www6.torrent9.to/top_torrent.html"),
            nineClient.textUrls,
        )
    }

    /** Torrent9 只规范合法法语 o/标准 B 单位，空值和未知单位保持缺失。 */
    @Test
    fun torrent9SizeParsingPreservesMagnitudeWithoutBlindSuffixRemoval() = runBlocking {
        /** cases 手写输入与期望，防止复用被测格式化逻辑。 */
        val cases = listOf(
            "1.5Go" to "1.5 GB",
            "1,5Go" to "1.5 GB",
            "1.5 GB" to "1.5 GB",
            "512Mo" to "512 MB",
            "1To" to "1 TB",
            "" to null,
            "1.5XB" to null,
        )
        /** rawSize 和 expected 是当前独立大小协议样本。 */
        for ((rawSize, expected) in cases) {
            /** html 只替换中性 fixture 的大小值。 */
            val html = DetailBatchSite.NINE.detailsHtml().replace("1.5Go", rawSize)
            /** details 仍必须保留合法名称和磁力身份。 */
            val details = requireNotNull(
                Torrent9Provider(FakeTorrentSearchNetworkClient(textHandler = { html }))
                    .getDetails(DetailBatchSite.NINE.url()),
            )
            assertEquals(expected, details.size)
        }
    }
}
