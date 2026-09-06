package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.LatestTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.provider.TopTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import com.cla.clip.feature.torrent.search.provider.builtin.xxxtracker.XxxTrackerProvider
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** XXXTracker 独立 selector、日期、人数、描述和 NSFW 边界测试。 */
class XxxTrackerProviderTest {
    /** manifest 元数据和全部四项能力必须稳定。 */
    @Test
    fun metadataAndCapabilitiesMatchManifest() {
        /** provider 是不联网即可检查的固定实现。 */
        val provider: SearchProvider = XxxTrackerProvider(FakeTorrentSearchNetworkClient())
        assertEquals("xxxtracker", provider.descriptor.id.value)
        assertEquals(ProviderSafetyLevel.NSFW, provider.descriptor.safetyLevel)
        assertFalse(provider.descriptor.enabledByDefault)
        assertEquals(setOf(Category.PORN), provider.descriptor.supportedCategories)
        assertTrue(provider is TorrentDetailsProvider)
        assertTrue(provider is LatestTorrentsProvider)
        assertTrue(provider is TopTorrentsProvider)
    }

    /** search 结构化编码 query，并读取 magnet/file/name 独立 anchor 顺序。 */
    @Test
    fun searchEncodesQueryAndParsesOwnAnchorOrder() = runBlocking {
        /** client 返回固定正常列表。 */
        val client = FakeTorrentSearchNetworkClient(
            textHandler = { readProviderFixture("provider/xxxtracker/search_normal.html") },
        )
        /** item 是唯一合法结果。 */
        val item = XxxTrackerProvider(client).search("  fi\u0000xture /?  ", Category.PORN).single()
        /** request 是结构化 b.php 请求。 */
        val request = client.textUrls.single().toHttpUrl()
        assertEquals("/b.php", request.encodedPath)
        assertEquals("fixture /?", request.queryParameter("search"))
        assertEquals(INFO_HASH, item.infoHash)
        assertEquals(VALID_MAGNET, item.magnetUri)
        assertEquals("https://xxxtor.com/file/a.torrent", item.fileDownloadLink)
        assertEquals("https://xxxtor.com/details/a", item.descriptionPageUrl)
        assertEquals(Category.PORN, item.category)
        assertEquals(1714521600000L, item.uploadEpochMillis)
        assertEquals(6u, item.seeders)
        assertEquals(7u, item.peers)
    }

    /** latest/top 每次独立访问固定路径且忽略调用分类。 */
    @Test
    fun latestAndTopUseReviewedPaths() = runBlocking {
        /** client 返回真实空表。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { EMPTY_LIST_HTML })
        /** provider 是入口。 */
        val provider = XxxTrackerProvider(client)
        provider.getLatestTorrents(Category.ALL)
        provider.getTopTorrents(Category.MOVIES)
        assertEquals(
            listOf("/b.php", "/top"),
            /** requestUrl 是当前能力请求地址。 */
            client.textUrls.map { requestUrl -> requestUrl.toHttpUrl().encodedPath },
        )
    }

    /** 未知俄文月、负人数和 UInt 溢出仅降级字段，不丢合法条目。 */
    @Test
    fun optionalDateAndPeerBoundariesDegradeIndependently() = runBlocking {
        /** html 同时覆盖三种可选坏字段。 */
        val html = readProviderFixture("provider/xxxtracker/search_normal.html")
            .replace("1 Мая 24", "1 Неиз 24")
            .replace("<span>6</span><span>7</span>", "<span>4294967295</span><span>4294967296</span>")
        /** item 的身份仍合法。 */
        val item = XxxTrackerProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))
            .search("x", Category.PORN)
            .single()
        assertNull(item.uploadEpochMillis)
        assertEquals(UInt.MAX_VALUE, item.seeders)
        assertNull(item.peers)
    }

    /** XXXTracker 公开列表入口完整消费十二个俄文短月。 */
    @Test
    fun searchParsesEveryReviewedRussianMonth() = runBlocking {
        /** tokens 是固定上源的十二个俄文短月。 */
        val tokens = listOf(
            "Янв", "Фев", "Мар", "Апр", "Май", "Июн",
            "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек",
        )
        /** token 是当前月份，index 是零基月份。 */
        tokens.forEachIndexed { index, token ->
            /** html 将五月属格替换为当前月份。 */
            val html = readProviderFixture("provider/xxxtracker/search_normal.html")
                .replace("1 Мая 24", "1 $token 24")
            /** item 是公开入口解析结果。 */
            val item = XxxTrackerProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))
                .search("x", Category.PORN)
                .single()
            /** expected 是独立计算的 UTC 月初。 */
            val expected = LocalDate.of(2024, index + 1, 1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
            assertEquals(expected, item.uploadEpochMillis)
        }
    }

    /** 详情保留 nth-last-child、四位年和只删除前三个直属元素的语义。 */
    @Test
    fun detailsKeepNestedBodyWhileRemovingDirectChildren() = runBlocking {
        /** details 来自固定合成页。 */
        val details = requireNotNull(
            XxxTrackerProvider(
                FakeTorrentSearchNetworkClient(
                    textHandler = { readProviderFixture("provider/xxxtracker/details_normal.html") },
                ),
            ).getDetails("https://xxxtor.com/details/a"),
        )
        assertEquals(INFO_HASH, details.infoHash)
        assertEquals(1704153600000L, details.uploadEpochMillis)
        assertEquals(6u, details.seeders)
        assertEquals(7u, details.peers)
        assertEquals("https://xxxtor.com/poster.jpg", details.posterUrl)
        assertFalse(requireNotNull(details.description).contains("remove-one"))
        assertFalse(requireNotNull(details.description).contains("remove-two"))
        assertFalse(requireNotNull(details.description).contains("remove-three"))
        assertTrue(requireNotNull(details.description).contains("nested-body"))
    }

    /** 海报可以位于其他直属元素之后，清理仍只删除最前面的三个直属元素。 */
    @Test
    fun detailsSelectDirectPosterAfterAnotherDirectElement() = runBlocking {
        /** html 在直属海报前加入一个直属占位元素。 */
        val html = readProviderFixture("provider/xxxtracker/details_normal.html")
            .replace(
                "<img id=\"remove-one\"",
                "<span id=\"before-poster\">prefix</span><img id=\"remove-one\"",
            )
        /** details 通过公开 Provider 入口解析。 */
        val details = requireNotNull(
            XxxTrackerProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))
                .getDetails("https://xxxtor.com/details/a"),
        )
        assertEquals("https://xxxtor.com/poster.jpg", details.posterUrl)
        assertFalse(requireNotNull(details.description).contains("before-poster"))
        assertFalse(requireNotNull(details.description).contains("remove-one"))
        assertFalse(requireNotNull(details.description).contains("remove-two"))
        assertTrue(requireNotNull(details.description).contains("remove-three"))
    }

    /** 详情两位年和人数坏值只降级对应字段。 */
    @Test
    fun malformedDetailsDateAndPeerCountsDoNotDropDetails() = runBlocking {
        /** html 破坏固定日期、做种和下载人数行。 */
        val html = readProviderFixture("provider/xxxtracker/details_normal.html")
            .replace("02-01-2024", "02-01-24")
            .replace("><td>6</td>", "><td>-1</td>")
            .replace("><td>7</td>", "><td>4294967296</td>")
        /** details 仍保留必填名称与 magnet。 */
        val details = requireNotNull(
            XxxTrackerProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))
                .getDetails("https://xxxtor.com/details/a"),
        )
        assertNull(details.uploadEpochMillis)
        assertNull(details.seeders)
        assertNull(details.peers)
    }

    /** 合法空页、selector 漂移、缺名称和坏 magnet 均跳过。 */
    @Test
    fun handlesEmptyMalformedAndMissingRequiredRows() = runBlocking {
        /** pages 覆盖合法空和三类坏行。 */
        val pages = listOf(
            EMPTY_LIST_HTML,
            "<div class='changed-layout'>x</div>",
            trackerRow("", VALID_MAGNET),
            trackerRow("Fixture", "magnet:?xt=urn:btih:bad"),
        )
        /** html 是当前独立页面。 */
        pages.forEach { html ->
            assertTrue(
                XxxTrackerProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))
                    .search("x", Category.PORN)
                    .isEmpty(),
            )
        }
    }

    /** 可选 file/details URL 覆盖 scheme、host、userinfo 和端口后只降级字段。 */
    @Test
    fun dropsEveryUnsafeOptionalOutputUrl() = runBlocking {
        /** unsafeUrls 是全部 authority 风险候选。 */
        val unsafeUrls = listOf(
            "https://evil.invalid/a",
            "https://user@xxxtor.com/a",
            "https://xxxtor.com:444/a",
            "http://xxxtor.com/a",
        )
        /** unsafeUrl 是当前污染两个选填字段的候选。 */
        unsafeUrls.forEach { unsafeUrl ->
            /** item 仍保留合法身份。 */
            val item = XxxTrackerProvider(
                FakeTorrentSearchNetworkClient(
                    textHandler = { trackerRow("Fixture", VALID_MAGNET, unsafeUrl, unsafeUrl) },
                ),
            ).search("x", Category.PORN).single()
            assertNull(item.fileDownloadLink)
            assertNull(item.descriptionPageUrl)
        }
    }

    /** 详情可选 poster/file 地址使用真实 base 并分别降级恶意候选。 */
    @Test
    fun detailsResolveRelativeUrlsAndDropUnsafeCandidates() = runBlocking {
        /** validDetails 验证相对地址按页面解析。 */
        val validDetails = requireNotNull(
            XxxTrackerProvider(
                FakeTorrentSearchNetworkClient(
                    textHandler = { readProviderFixture("provider/xxxtracker/details_normal.html") },
                ),
            ).getDetails("https://xxxtor.com/details/a"),
        )
        assertEquals("https://xxxtor.com/file/a.torrent", validDetails.fileDownloadLink)
        assertEquals("https://xxxtor.com/poster.jpg", validDetails.posterUrl)
        /** pollutedHtml 同时污染两个详情选填地址。 */
        val pollutedHtml = readProviderFixture("provider/xxxtracker/details_normal.html")
            .replace("/file/a.torrent", "https://evil.invalid/a")
            .replace("/poster.jpg", "https://user@xxxtor.com/a")
        /** pollutedDetails 仍保留必填详情。 */
        val pollutedDetails = requireNotNull(
            XxxTrackerProvider(FakeTorrentSearchNetworkClient(textHandler = { pollutedHtml }))
                .getDetails("https://xxxtor.com/details/a"),
        )
        assertNull(pollutedDetails.fileDownloadLink)
        assertNull(pollutedDetails.posterUrl)
    }

    /** 非法详情输入在网络前拒绝，显式 443 允许。 */
    @Test
    fun validatesDetailsAuthorityBeforeNetwork() = runBlocking {
        /** client 用于证明拒绝分支无网络。 */
        val client = FakeTorrentSearchNetworkClient(
            textHandler = { readProviderFixture("provider/xxxtracker/details_normal.html") },
        )
        /** provider 是详情入口。 */
        val provider = XxxTrackerProvider(client)
        /** url 是当前恶意详情地址。 */
        for (url in listOf(
            "https://evil.invalid/a",
            "http://xxxtor.com/a",
            "https://user@xxxtor.com/a",
            "https://xxxtor.com:444/a",
        )) {
            /** failure 应为低敏拒绝。 */
            val failure = runCatching { provider.getDetails(url) }.exceptionOrNull()
            assertTrue(failure is HttpDeniedException)
        }
        assertTrue(client.textUrls.isEmpty())
        requireNotNull(provider.getDetails("https://xxxtor.com:443/details/a"))
        assertEquals(1, client.textUrls.size)
    }

    /** 多行列表保持原顺序，证明遍历不会只解析首个正常 fixture。 */
    @Test
    fun parsesMultipleRowsInSourceOrder() = runBlocking {
        /** secondRow 是第二条独立合法结果。 */
        val secondRow = """
            <tr><td>2 Фев 24</td><td><a href="magnet:?xt=urn:btih:$SECOND_HASH">magnet</a><a href="/file/b.torrent">file</a><a href="/details/b">Fixture Two</a></td><td>4 GB</td><td><span>8</span><span>9</span></td></tr>
        """.trimIndent()
        /** html 在固定正常页后追加第二行。 */
        val html = readProviderFixture("provider/xxxtracker/search_normal.html")
            .replace("</tbody>", "$secondRow</tbody>")
        /** items 必须完整保持两行顺序。 */
        val items = XxxTrackerProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))
            .search("x", Category.PORN)
        /** item 是当前结果名称来源。 */
        assertEquals(listOf("Fixture Beta", "Fixture Two"), items.map { item -> item.name })
        /** item 是当前结果 hash 来源。 */
        assertEquals(listOf(INFO_HASH, SECOND_HASH), items.map { item -> item.infoHash })
    }

    /** 详情缺名称或坏 magnet 时返回 null。 */
    @Test
    fun detailsRequireNameAndValidMagnet() = runBlocking {
        /** normalHtml 是详情结构基线。 */
        val normalHtml = readProviderFixture("provider/xxxtracker/details_normal.html")
        /** badPages 分别移除名称和破坏 magnet。 */
        val badPages = listOf(
            normalHtml.replace("Fixture Beta", ""),
            normalHtml.replace(INFO_HASH, "bad"),
        )
        /** html 是当前坏详情页。 */
        badPages.forEach { html ->
            assertNull(
                XxxTrackerProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))
                    .getDetails("https://xxxtor.com/details/a"),
            )
        }
    }

    /** 构造 XXXTracker 固定 anchor 顺序。 */
    private fun trackerRow(
        /** 结果名称。 */
        name: String,
        /** 结果 magnet。 */
        magnet: String,
        /** 可选文件地址。 */
        file: String = "/file/a.torrent",
        /** 可选详情地址。 */
        details: String = "/details/a",
    ): String = """
        <table><tbody><tr><th>header</th></tr><tr>
          <td>1 Мая 24</td><td><a href="$magnet">magnet</a><a href="$file">file</a><a href="$details">$name</a></td>
          <td>3 GB</td><td><span>6</span><span>7</span></td>
        </tr></tbody></table>
    """.trimIndent()

    /** 固定测试协议常量。 */
    private companion object {
        /** 中性 40 位 hash。 */
        private const val INFO_HASH = "0123456789abcdef0123456789abcdef01234567"
        /** 中性合法 magnet。 */
        private const val VALID_MAGNET = "magnet:?xt=urn:btih:$INFO_HASH"
        /** 第二条中性 40 位 hash。 */
        private const val SECOND_HASH = "89abcdef0123456789abcdef0123456789abcdef"
        /** 固定合法空列表。 */
        private const val EMPTY_LIST_HTML = "<table><tbody><tr><th>header</th></tr></tbody></table>"
    }
}
