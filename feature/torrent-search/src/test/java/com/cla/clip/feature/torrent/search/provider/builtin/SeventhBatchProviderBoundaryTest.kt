package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.provider.builtin.megapeer.MegaPeerProvider
import com.cla.clip.feature.torrent.search.provider.builtin.megapeer.MegaPeerRussianDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.mikan.MikanProvider
import com.cla.clip.feature.torrent.search.provider.builtin.mypornclub.MyPornClubProvider
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第七批 Provider 尚未由主行为测试覆盖的输入与安全边界。 */
class SeventhBatchProviderBoundaryTest {
    /** MegaPeer 全部十二个俄文短月及五月属格必须映射到固定 UTC 日期。 */
    @Test
    fun megaPeerRussianDateParserCoversEveryReviewedMonthToken() {
        /** 月份 token 与 2024 年预期月份序号。 */
        val monthTokens = listOf(
            "Янв", "Фев", "Мар", "Апр", "Май", "Июн",
            "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек",
        )
        /** token 是当前接受的固定俄文短月。 */
        monthTokens.forEachIndexed { index, token ->
            /** 预期值由公共严格 UTC parser 的英语短月结果提供。 */
            val expectedEpochMillis = java.time.LocalDate.of(2024, index + 1, 1)
                .atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
            assertEquals(expectedEpochMillis, MegaPeerRussianDateParser.parseEpochMillis("1 $token 24"))
        }
        assertEquals(
            MegaPeerRussianDateParser.parseEpochMillis("1 Май 24"),
            MegaPeerRussianDateParser.parseEpochMillis("1 Мая 24"),
        )
    }

    /** MegaPeer 每个审计分类必须写入固定 cat，未知分类回退 0。 */
    @Test
    fun megaPeerSearchMapsEveryReviewedCategory() = runBlocking {
        /** 空列表避免分类 URL 测试启动详情补全。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            textHandler = { "<div id='index'><table><tbody></tbody></table></div>" },
        )
        /** 待测 Provider。 */
        val provider = MegaPeerProvider(networkClient)
        /** 分类与固定站点 cat 映射。 */
        val categoryIds = linkedMapOf(
            Category.ALL to "0",
            Category.APPS to "29",
            Category.BOOKS to "52",
            Category.GAMES to "28",
            Category.MOVIES to "80",
            Category.MUSIC to "94",
            Category.OTHER to "59",
            Category.SERIES to "6",
            Category.ANIME to "0",
            Category.PORN to "0",
        )

        /** category 是当前要编码的业务分类。 */
        categoryIds.keys.forEach { category -> provider.search("fixture", category) }

        assertEquals(
            categoryIds.values.toList(),
            /** url 是当前分类请求的结构化地址。 */
            networkClient.textUrls.map { url -> url.toHttpUrl().queryParameter("cat") },
        )
    }

    /** MegaPeer 可选 poster、截图和文件链接跨站时必须独立降级。 */
    @Test
    fun megaPeerDetailsDropsUnsafeOptionalMediaAndFileUrls() = runBlocking {
        /** 将正常 fixture 的三个可选链接替换为不同恶意来源。 */
        val maliciousHtml = readProviderFixture("provider/megapeer/details_normal.html")
            .replace("/poster.jpg", "https://evil.invalid/poster.jpg")
            .replace("/shot.jpg", "https://user@megapeer.vip/shot.jpg")
            .replace("/download/a.torrent", "https://megapeer.vip:444/download/a.torrent")
        /** 返回恶意可选字段页面的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(textHandler = { maliciousHtml })

        /** 必填 name/magnet 合法时详情必须保留。 */
        val details = requireNotNull(
            MegaPeerProvider(networkClient).getDetails("https://megapeer.vip/torrent/a"),
        )

        assertNull(details.posterUrl)
        assertTrue(details.screenshotUrls.isEmpty())
        assertNull(details.fileDownloadLink)
    }

    /** Mikan 无结果、结构漂移、缺名称和非法 magnet 均应稳定返回空列表。 */
    @Test
    fun mikanSearchHandlesEmptyMalformedAndMissingRequiredRows() = runBlocking {
        /** 四类页面覆盖真实空行、selector 漂移及两个必填字段失败。 */
        val pages = listOf(
            "<table><tbody></tbody></table>",
            "<html><body><div class='changed-layout'>fixture</div></body></html>",
            mikanRow(name = "", magnet = validMagnet),
            mikanRow(name = "Fixture", magnet = "magnet:?xt=urn:btih:bad"),
        )

        /** html 是当前独立解析边界输入。 */
        pages.forEach { html ->
            /** 当前页面使用独立 Provider，避免 fake 调用记录互相影响。 */
            val provider = MikanProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))
            assertTrue(provider.search("fixture", Category.ANIME).isEmpty())
        }
    }

    /** Mikan 恶意详情与下载产出只清空可选字段，不丢弃合法 magnet 结果。 */
    @Test
    fun mikanSearchDropsUnsafeOptionalOutputUrls() = runBlocking {
        /** 合法必填字段搭配跨站详情与非 Download 文件地址。 */
        val html = mikanRow(
            name = "Fixture",
            magnet = validMagnet,
            detailsUrl = "https://evil.invalid/Home/Episode/a",
            downloadUrl = "https://mikanani.me/files/a.torrent",
        )
        /** 解析当前恶意可选字段页面。 */
        val item = MikanProvider(
            FakeTorrentSearchNetworkClient(textHandler = { html }),
        ).search("fixture", Category.ANIME).single()

        assertNull(item.descriptionPageUrl)
        assertNull(item.fileDownloadLink)
    }

    /** Mikan 详情缺标题或坏 magnet 返回 null，恶意下载链接独立降级。 */
    @Test
    fun mikanDetailsHandlesRequiredAndUnsafeOptionalFields() = runBlocking {
        /** 缺标题与坏 magnet 的两个详情页面。 */
        val missingRequiredPages = listOf(
            "<p class='episode-title'></p><a href='$validMagnet'>m</a>",
            "<p class='episode-title'>Fixture</p><a href='magnet:?xt=urn:btih:bad'>m</a>",
        )
        /** html 是当前必填失败详情。 */
        missingRequiredPages.forEach { html ->
            /** 当前页面使用独立 Provider。 */
            val provider = MikanProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))
            assertNull(provider.getDetails("https://mikanani.me/Home/Episode/a"))
        }
        /** 正常必填搭配 userinfo 下载地址。 */
        val unsafeFileHtml = "<p class='episode-title'>Fixture</p><a href='$validMagnet'>m</a><a href='https://user@mikanani.me/Download/a.torrent'>d</a>"
        /** 合法详情必须保留。 */
        val details = requireNotNull(
            MikanProvider(FakeTorrentSearchNetworkClient(textHandler = { unsafeFileHtml }))
                .getDetails("https://mikanani.me/Home/Episode/a"),
        )
        assertNull(details.fileDownloadLink)
    }

    /** MyPornClub 无结果、结构漂移和缺 draft 必填字段不得请求详情。 */
    @Test
    fun myPornClubSearchHandlesEmptyMalformedAndMissingDraftWithoutDetailsIo() = runBlocking {
        /** 四类列表页分别覆盖无结果、selector 漂移、空名称和跨站详情。 */
        val pages = listOf(
            "<div class='torrents_list'></div>",
            "<div class='changed-layout'>fixture</div>",
            myPornClubRow(name = "", detailsUrl = "/torrent/a"),
            myPornClubRow(name = "Fixture", detailsUrl = "https://evil.invalid/torrent/a"),
        )
        /** html 是当前独立列表边界输入。 */
        pages.forEach { html ->
            /** 仅允许一次列表请求。 */
            val networkClient = FakeTorrentSearchNetworkClient(textHandler = { html })
            assertTrue(MyPornClubProvider(networkClient).search("fixture", Category.PORN).isEmpty())
            assertEquals(1, networkClient.textUrls.size)
        }
    }

    /** MyPornClub 详情缺 hash/名称或 magnet 冲突返回 null，恶意文件链接只降级。 */
    @Test
    fun myPornClubDetailsHandlesRequiredAndUnsafeOptionalFields() = runBlocking {
        /** 缺 hash 与空名称的两个必填失败页面。 */
        val missingRequiredPages = listOf(
            "<div class='torrent_text'>Fixture #meta</div><div class='torrent_info_div'><div>[hash_info]: bad</div></div>",
            "<div class='torrent_text'> #meta</div><div class='torrent_info_div'><div>[hash_info]: $hash</div></div>",
        )
        /** html 是当前必填失败详情。 */
        missingRequiredPages.forEach { html ->
            /** 当前页面使用独立 Provider。 */
            val provider = MyPornClubProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))
            assertNull(provider.getDetails("https://myporn.club/torrent/a"))
        }
        /** 固定正常详情仅污染可选文件地址。 */
        val unsafeFileHtml = readProviderFixture("provider/mypornclub/details_normal.html")
            .replace("/download/a.torrent", "https://myporn.club:444/download/a.torrent")
        /** 合法 hash/name 仍应返回详情。 */
        val details = requireNotNull(
            MyPornClubProvider(FakeTorrentSearchNetworkClient(textHandler = { unsafeFileHtml }))
                .getDetails("https://myporn.club/torrent/a"),
        )
        assertNull(details.fileDownloadLink)
    }

    /** 测试使用的固定合法 40 位 btih。 */
    private val hash = "0123456789abcdef0123456789abcdef01234567"

    /** 测试使用的固定合法 magnet。 */
    private val validMagnet = "magnet:?xt=urn:btih:$hash"

    /** 构造 Mikan 固定列结构的单行页面。 */
    private fun mikanRow(
        /** 标题必填候选。 */
        name: String,
        /** magnet 必填候选。 */
        magnet: String,
        /** 可选详情地址。 */
        detailsUrl: String = "/Home/Episode/a",
        /** 可选下载地址。 */
        downloadUrl: String = "/Download/a.torrent",
    ): String = """
        <table><tbody><tr class="js-search-results-row">
          <td>x</td><td><a href="$detailsUrl">$name</a><a data-clipboard-text="$magnet">m</a></td>
          <td>1GB</td><td>2024/01/02 03:04</td><td><a href="$downloadUrl">d</a></td>
        </tr></tbody></table>
    """.trimIndent()

    /** 构造 MyPornClub 固定列表结构的单行页面。 */
    private fun myPornClubRow(
        /** 标题必填候选。 */
        name: String,
        /** 详情地址必填候选。 */
        detailsUrl: String,
    ): String = """
        <div class="torrents_list"><div class="torrent_element">
          <div class="torrent_element_text_div"><a>x</a><a href="$detailsUrl"><span class="torrent_element_text_span">$name</span></a></div>
          <div class="torrent_element_info"><span>x</span><span class="teiv">2 days ago</span><span>x</span><span class="teiv">2 gb</span></div>
        </div></div>
    """.trimIndent()
}
