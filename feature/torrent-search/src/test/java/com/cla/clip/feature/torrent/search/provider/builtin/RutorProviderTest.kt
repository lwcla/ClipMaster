package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.LatestTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.provider.TopTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.rutor.RutorProvider
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rutor 请求、DOM、类别、日期、人数与 URL 边界测试。 */
class RutorProviderTest {
    /** manifest 元数据与全部四项能力必须稳定。 */
    @Test
    fun metadataAndCapabilitiesMatchManifest() {
        /** provider 是不联网即可检查的固定实现。 */
        val provider: SearchProvider = RutorProvider(FakeTorrentSearchNetworkClient())
        assertEquals("rutorinfo", provider.descriptor.id.value)
        assertEquals(ProviderSafetyLevel.SAFE, provider.descriptor.safetyLevel)
        assertFalse(provider.descriptor.enabledByDefault)
        /** category 是当前待过滤的稳定分类。 */
        val expectedCategories = Category.entries.filterNot { category -> category == Category.PORN }.toSet()
        assertEquals(expectedCategories, provider.descriptor.supportedCategories)
        assertTrue(provider is TorrentDetailsProvider)
        assertTrue(provider is LatestTorrentsProvider)
        assertTrue(provider is TopTorrentsProvider)
    }

    /** search 清洗并编码 path，且严格读取 file/magnet/name anchor 顺序。 */
    @Test
    fun searchBuildsReviewedPathAndParsesAnchorOrder() = runBlocking {
        /** client 返回固定正常列表。 */
        val client = FakeTorrentSearchNetworkClient(
            textHandler = { readProviderFixture("provider/rutor/search_normal.html") },
        )
        /** item 是唯一合法结果。 */
        val item = RutorProvider(client).search("  fi\u0000xture /?  ", Category.ANIME).single()
        /** request 是结构化构造后的 URL。 */
        val request = client.textUrls.single().toHttpUrl()
        assertEquals("/search/0/10/010/2/fixture%20%2F%3F", request.encodedPath)
        assertEquals(INFO_HASH, item.infoHash)
        assertEquals(VALID_MAGNET, item.magnetUri)
        assertEquals("https://rutor.info/download/a.torrent", item.fileDownloadLink)
        assertEquals("https://rutor.info/torrent/a", item.descriptionPageUrl)
        assertEquals(Category.ANIME, item.category)
        assertEquals(1704067200000L, item.uploadEpochMillis)
        assertEquals(2u, item.seeders)
        assertEquals(3u, item.peers)
    }

    /** 全部分类映射以及 latest/top 固定路径必须准确。 */
    @Test
    fun mapsEveryCategoryAndKeepsLatestTopPaths() = runBlocking {
        /** client 对全部能力返回合法空页。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { EMPTY_LIST_HTML })
        /** provider 复用同一记录 fake。 */
        val provider = RutorProvider(client)
        /** mappings 是审计固定类别段，PORN 作为未知能力回退 0。 */
        val mappings = linkedMapOf(
            Category.ALL to "0",
            Category.ANIME to "10",
            Category.APPS to "9",
            Category.BOOKS to "11",
            Category.GAMES to "8",
            Category.MOVIES to "1",
            Category.MUSIC to "2",
            Category.OTHER to "3",
            Category.SERIES to "4",
            Category.PORN to "0",
        )
        /** category 是当前待验证分类。 */
        mappings.keys.forEach { category ->
            provider.search("x", category)
        }
        assertEquals(
            mappings.values.toList(),
            /** requestUrl 是当前分类请求地址。 */
            client.textUrls.map { requestUrl -> requestUrl.toHttpUrl().pathSegments[2] },
        )
        provider.getLatestTorrents(Category.BOOKS)
        provider.getTopTorrents(Category.MOVIES)
        assertEquals(
            "/browse/0/11/0/0",
            client.textUrls[client.textUrls.lastIndex - 1].toHttpUrl().encodedPath,
        )
        assertEquals("/top", client.textUrls.last().toHttpUrl().encodedPath)
    }

    /** ALL 结果不声明类别，其他调用范围原样保留。 */
    @Test
    fun listCategoryReflectsSearchScope() = runBlocking {
        /** client 每次返回同一正常行。 */
        val client = FakeTorrentSearchNetworkClient(
            textHandler = { readProviderFixture("provider/rutor/search_normal.html") },
        )
        /** provider 是待测入口。 */
        val provider = RutorProvider(client)
        assertNull(provider.search("x", Category.ALL).single().category)
        assertEquals(Category.MOVIES, provider.search("x", Category.MOVIES).single().category)
    }

    /** 详情严格读取 header、完整日期、类别 path 和媒体清理。 */
    @Test
    fun detailsParseFullDatesCategoryPathAndCleanMedia() = runBlocking {
        /** provider 返回固定详情。 */
        val provider = RutorProvider(
            FakeTorrentSearchNetworkClient(
                textHandler = { readProviderFixture("provider/rutor/details_normal.html") },
            ),
        )
        /** details 使用真实 page URL 作为相对链接 base。 */
        val details = requireNotNull(provider.getDetails("https://rutor.info/torrent/a"))
        assertEquals(INFO_HASH, details.infoHash)
        assertEquals(1704164645000L, details.uploadEpochMillis)
        assertEquals(1704251045000L, details.lastCheckedEpochMillis)
        assertEquals(Category.MOVIES, details.category)
        assertEquals(4u, details.seeders)
        assertEquals(5u, details.peers)
        assertEquals("https://rutor.info/poster.jpg", details.posterUrl)
        assertEquals(listOf("https://rutor.info/shot.jpg"), details.screenshotUrls)
        assertFalse(requireNotNull(details.description).contains("hidewrap"))
        assertFalse(requireNotNull(details.description).contains("poster.jpg"))
    }

    /** 详情只把首个直属图片作为海报，前面的嵌套正文图必须保留。 */
    @Test
    fun detailsSelectDirectPosterAfterNestedBodyImage() = runBlocking {
        /** html 在直属海报前加入嵌套正文图片。 */
        val html = readProviderFixture("provider/rutor/details_normal.html")
            .replace(
                "<img src=\"/poster.jpg\"><p>neutral body</p>",
                "<div><img src=\"/nested-before.jpg\"></div><img src=\"/poster.jpg\"><p>neutral body</p>",
            )
        /** details 通过公开 Provider 入口解析。 */
        val details = requireNotNull(
            RutorProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))
                .getDetails("https://rutor.info/torrent/a"),
        )
        assertEquals("https://rutor.info/poster.jpg", details.posterUrl)
        assertTrue(requireNotNull(details.description).contains("nested-before.jpg"))
        assertFalse(requireNotNull(details.description).contains("poster.jpg"))
    }

    /** 没有直属海报时不把嵌套正文图片误认或移除。 */
    @Test
    fun detailsKeepNestedBodyImageWhenDirectPosterIsMissing() = runBlocking {
        /** html 移除直属海报并保留一张嵌套正文图片。 */
        val html = readProviderFixture("provider/rutor/details_normal.html")
            .replace(
                "<img src=\"/poster.jpg\"><p>neutral body</p>",
                "<div><img src=\"/nested-only.jpg\"></div><p>neutral body</p>",
            )
        /** details 通过公开 Provider 入口解析。 */
        val details = requireNotNull(
            RutorProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))
                .getDetails("https://rutor.info/torrent/a"),
        )
        assertNull(details.posterUrl)
        assertTrue(requireNotNull(details.description).contains("nested-only.jpg"))
    }

    /** 类别必须从 HttpUrl path segment 读取，query 中斜线不能污染映射。 */
    @Test
    fun detailsMapEveryReviewedRussianCategoryPath() = runBlocking {
        /** mappings 固定俄文路径与未知回退。 */
        val mappings = linkedMapOf(
            "kino" to Category.MOVIES,
            "nashe_kino" to Category.MOVIES,
            "multiki" to Category.MOVIES,
            "seriali" to Category.SERIES,
            "nashi_seriali" to Category.SERIES,
            "tv" to Category.SERIES,
            "anime" to Category.ANIME,
            "audio" to Category.MUSIC,
            "games" to Category.GAMES,
            "soft" to Category.APPS,
            "knigi" to Category.BOOKS,
            "unknown" to Category.OTHER,
        )
        /** path 与 expected 是当前类别路径和领域值。 */
        mappings.forEach { (path, expected) ->
            /** html 只替换类别 href，并用 query 反证 substring 解析。 */
            val html = readProviderFixture("provider/rutor/details_normal.html")
                .replace("/category/kino", "/category/$path?from=/wrong")
            /** details 是当前映射结果。 */
            val details = requireNotNull(
                RutorProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))
                    .getDetails("https://rutor.info/torrent/a"),
            )
            assertEquals(expected, details.category)
        }
    }

    /** UInt 最大值可保留，负数、溢出和坏日期仅降级对应字段。 */
    @Test
    fun optionalDateAndPeerBoundariesDoNotDropValidRows() = runBlocking {
        /** html 将正常数值替换为边界和坏值。 */
        val html = readProviderFixture("provider/rutor/search_normal.html")
            .replace("1 Янв 24", "31 Фев 24")
            .replace("<span>2</span><span>x</span><span>3</span>", "<span>4294967295</span><span>x</span><span>4294967296</span>")
        /** item 的身份字段仍应保留。 */
        val item = RutorProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))
            .search("x", Category.ALL)
            .single()
        assertEquals(UInt.MAX_VALUE, item.seeders)
        assertNull(item.peers)
        assertNull(item.uploadEpochMillis)
    }

    /** Rutor 公开列表入口完整消费十二个俄文短月。 */
    @Test
    fun searchParsesEveryReviewedRussianMonth() = runBlocking {
        /** tokens 是固定上源的十二个俄文短月。 */
        val tokens = listOf(
            "Янв", "Фев", "Мар", "Апр", "Май", "Июн",
            "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек",
        )
        /** token 是当前月份，index 是零基月份。 */
        tokens.forEachIndexed { index, token ->
            /** html 将正常列表日期替换为当前月份。 */
            val html = readProviderFixture("provider/rutor/search_normal.html")
                .replace("1 Янв 24", "1 $token 24")
            /** item 是公开入口解析结果。 */
            val item = RutorProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))
                .search("x", Category.ALL)
                .single()
            /** expected 是独立计算的 UTC 月初。 */
            val expected = LocalDate.of(2024, index + 1, 1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
            assertEquals(expected, item.uploadEpochMillis)
        }
    }

    /** Rutor 详情日期必须完整匹配四位年，坏值不丢详情。 */
    @Test
    fun detailsMalformedFullDatesDegradeIndependently() = runBlocking {
        /** html 同时破坏上传时间和最后检查时间宽度。 */
        val html = readProviderFixture("provider/rutor/details_normal.html")
            .replace("02-01-2024 3:04:05", "02-01-24 3:04:05")
            .replace("03-01-2024 3:04:05", "03-01-24 3:04:05")
        /** details 仍保留合法名称与 magnet。 */
        val details = requireNotNull(
            RutorProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))
                .getDetails("https://rutor.info/torrent/a"),
        )
        assertNull(details.uploadEpochMillis)
        assertNull(details.lastCheckedEpochMillis)
    }

    /** 合法空页、selector 漂移、缺名称和坏 magnet 均跳过。 */
    @Test
    fun handlesEmptyMalformedAndMissingRequiredRows() = runBlocking {
        /** pages 覆盖真实空形状和三类坏输入。 */
        val pages = listOf(
            EMPTY_LIST_HTML,
            "<div class='changed-layout'>x</div>",
            rutorRow("", VALID_MAGNET),
            rutorRow("Fixture", "magnet:?xt=urn:btih:bad"),
        )
        /** html 是当前独立页面。 */
        pages.forEach { html ->
            assertTrue(
                RutorProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))
                    .search("x", Category.ALL)
                    .isEmpty(),
            )
        }
    }

    /** 可选 URL 分别拒绝跨站、userinfo、异常端口与 HTTP，同时保留合法条目。 */
    @Test
    fun dropsEveryUnsafeOptionalOutputUrl() = runBlocking {
        /** pollutedUrls 覆盖 authority 与 scheme 边界。 */
        val pollutedUrls = listOf(
            "https://evil.invalid/a",
            "https://user@rutor.info/a",
            "https://rutor.info:8443/a",
            "http://rutor.info/a",
        )
        /** unsafeUrl 是当前同时污染 file/details 的候选。 */
        pollutedUrls.forEach { unsafeUrl ->
            /** item 仍由合法名称与 magnet 构成。 */
            val item = RutorProvider(
                FakeTorrentSearchNetworkClient(
                    textHandler = { rutorRow("Fixture", VALID_MAGNET, unsafeUrl, unsafeUrl) },
                ),
            ).search("x", Category.ALL).single()
            assertNull(item.fileDownloadLink)
            assertNull(item.descriptionPageUrl)
        }
    }

    /** 非法详情输入在网络前拒绝，显式 443 仍可正常请求。 */
    @Test
    fun validatesDetailsAuthorityBeforeNetwork() = runBlocking {
        /** client 用于证明拒绝分支无请求。 */
        val client = FakeTorrentSearchNetworkClient(
            textHandler = { readProviderFixture("provider/rutor/details_normal.html") },
        )
        /** provider 是详情入口。 */
        val provider = RutorProvider(client)
        /** url 是当前恶意详情地址。 */
        for (url in listOf(
            "https://evil.invalid/a",
            "http://rutor.info/a",
            "https://user@rutor.info/a",
            "https://rutor.info:8443/a",
        )) {
            /** failure 应是无正文的低敏拒绝。 */
            val failure = runCatching { provider.getDetails(url) }.exceptionOrNull()
            assertTrue(failure is HttpDeniedException)
        }
        assertTrue(client.textUrls.isEmpty())
        requireNotNull(provider.getDetails("https://rutor.info:443/torrent/a"))
        assertEquals(1, client.textUrls.size)
    }

    /** 多行列表保持原顺序，并允许单条坏可选字段而不影响后续条目。 */
    @Test
    fun parsesMultipleRowsInSourceOrder() = runBlocking {
        /** secondRow 是第二条独立合法结果。 */
        val secondRow = """
            <tr><td>2 Фев 24</td><td><a href="/download/b.torrent">file</a><a href="magnet:?xt=urn:btih:$SECOND_HASH">magnet</a><a href="/torrent/b">Fixture Two</a></td><td>2 GB</td><td><span>8</span><span>x</span><span>9</span></td></tr>
        """.trimIndent()
        /** html 在固定正常页后追加第二行。 */
        val html = readProviderFixture("provider/rutor/search_normal.html")
            .replace("</tbody>", "$secondRow</tbody>")
        /** items 必须完整保持两行顺序。 */
        val items = RutorProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))
            .search("x", Category.ALL)
        /** item 是当前结果名称来源。 */
        assertEquals(listOf("Fixture Alpha", "Fixture Two"), items.map { item -> item.name })
        /** item 是当前结果 hash 来源。 */
        assertEquals(listOf(INFO_HASH, SECOND_HASH), items.map { item -> item.infoHash })
    }

    /** 详情缺名称或坏 magnet 时返回 null，不构造无身份详情。 */
    @Test
    fun detailsRequireNameAndValidMagnet() = runBlocking {
        /** normalHtml 是详情结构基线。 */
        val normalHtml = readProviderFixture("provider/rutor/details_normal.html")
        /** badPages 分别移除名称和破坏 magnet。 */
        val badPages = listOf(
            normalHtml.replace("Fixture Alpha", ""),
            normalHtml.replace(INFO_HASH, "bad"),
        )
        /** html 是当前坏详情页。 */
        badPages.forEach { html ->
            assertNull(
                RutorProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))
                    .getDetails("https://rutor.info/torrent/a"),
            )
        }
    }

    /** 详情 file、poster 和 screenshot 非法时分别降级，描述主体仍保留。 */
    @Test
    fun detailsDropUnsafeMediaAndFileUrls() = runBlocking {
        /** html 同时污染三类选填产出地址。 */
        val html = readProviderFixture("provider/rutor/details_normal.html")
            .replace("/download/a.torrent", "https://evil.invalid/a")
            .replace("/poster.jpg", "https://user@rutor.info/a")
            .replace("/shot.jpg", "https://rutor.info:8443/a")
        /** details 仍保留合法名称和 magnet 身份。 */
        val details = requireNotNull(
            RutorProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))
                .getDetails("https://rutor.info/torrent/a"),
        )
        assertNull(details.fileDownloadLink)
        assertNull(details.posterUrl)
        assertTrue(details.screenshotUrls.isEmpty())
        assertTrue(requireNotNull(details.description).contains("neutral body"))
    }

    /** 构造固定 Rutor anchor 顺序。 */
    private fun rutorRow(
        /** 结果名称。 */
        name: String,
        /** 结果 magnet。 */
        magnet: String,
        /** 可选文件地址。 */
        file: String = "/download/a.torrent",
        /** 可选详情地址。 */
        details: String = "/torrent/a",
    ): String = """
        <div id="index"><table><tbody><tr><th>header</th></tr><tr>
          <td>1 Янв 24</td><td><a href="$file">file</a><a href="$magnet">magnet</a><a href="$details">$name</a></td>
          <td>1 GB</td><td><span>2</span><span>x</span><span>3</span></td>
        </tr></tbody></table></div>
    """.trimIndent()

    /** 固定测试协议常量。 */
    private companion object {
        /** 中性 40 位 hash。 */
        private const val INFO_HASH = "0123456789abcdef0123456789abcdef01234567"
        /** 中性合法 magnet。 */
        private const val VALID_MAGNET = "magnet:?xt=urn:btih:$INFO_HASH"
        /** 第二条中性 40 位 hash。 */
        private const val SECOND_HASH = "89abcdef0123456789abcdef0123456789abcdef"
        /** 固定列表合法空结构。 */
        private const val EMPTY_LIST_HTML = "<div id='index'><table><tbody><tr><th>header</th></tr></tbody></table></div>"
    }
}
