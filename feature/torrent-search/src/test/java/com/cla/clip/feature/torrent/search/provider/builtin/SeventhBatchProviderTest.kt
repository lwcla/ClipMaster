package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.builtin.megapeer.MegaPeerProvider
import com.cla.clip.feature.torrent.search.provider.builtin.megapeer.MegaPeerRussianDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.mikan.MikanProvider
import com.cla.clip.feature.torrent.search.provider.builtin.mypornclub.MyPornClubProvider
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

/** 第七批 Provider 的固定请求、解析、日期和 URL 安全契约。 */
class SeventhBatchProviderTest {
    /** 合成 fixture 的合法 40 位 hash。 */
        private val hash = "0123456789abcdef0123456789abcdef01234567"
    /** 相对日期使用的固定 UTC 基准。 */
        private val now = 1704326400000L

    /** 三站元数据必须服从本地安全审计并默认关闭。 */
    @Test
    fun metadataMatchesLocalAudit() {
        /** client 不执行网络，只满足构造依赖。 */
        val client = FakeTorrentSearchNetworkClient()
        assertEquals(ProviderSafetyLevel.SAFE, MegaPeerProvider(client).descriptor.safetyLevel)
        assertEquals(ProviderSafetyLevel.SAFE, MikanProvider(client).descriptor.safetyLevel)
        assertEquals(ProviderSafetyLevel.NSFW, MyPornClubProvider(client).descriptor.safetyLevel)
        assertFalse(MegaPeerProvider(client).descriptor.enabledByDefault)
        assertFalse(MikanProvider(client).descriptor.enabledByDefault)
        assertFalse(MyPornClubProvider(client).descriptor.enabledByDefault)
    }

    /** MegaPeer 结构化编码全部固定参数并映射正常列表。 */
    @Test
    fun megaPeerSearchBuildsParametersAndParsesFixture() = runBlocking {
        /** client 根据列表或详情地址返回对应合成页。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { url -> if (url.contains("/torrent/")) readProviderFixture("provider/megapeer/details_normal.html") else readProviderFixture("provider/megapeer/search_normal.html") })
        /** item 是唯一完成详情补全的结果。 */
        val item = MegaPeerProvider(client).search("  fi\u0000xture /? ", Category.MOVIES).single()
        /** request 是首个列表 URL。 */
        val request = client.textUrls.first().toHttpUrl()
        assertEquals("fixture /?", request.queryParameter("search"))
        assertEquals("", request.queryParameter("age"))
        assertEquals("80", request.queryParameter("cat"))
        assertEquals("0", request.queryParameter("stype"))
        assertEquals("0", request.queryParameter("sort"))
        assertEquals("0", request.queryParameter("ascdesc"))
        assertEquals(hash, item.infoHash)
        assertNull(item.magnetUri)
        assertEquals(1709164800000L, item.uploadEpochMillis)
    }

    /** MegaPeer 缺 draft 必填字段时不得启动详情请求。 */
    @Test
    fun megaPeerMissingDraftDoesNotRequestDetails() = runBlocking {
        /** body 同时覆盖空名称与跨站详情。 */
        val body = "<div id='index'><table><tbody><tr class='table_fon'><td>x</td><td><a>x</a><a href='/torrent/a'></a></td></tr><tr class='table_fon'><td>x</td><td><a>x</a><a href='https://evil.example/a'>x</a></td></tr></tbody></table></div>"
        /** client 只应收到列表请求。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { body })
        assertTrue(MegaPeerProvider(client).search("x", Category.ALL).isEmpty())
        assertEquals(1, client.textUrls.size)
    }

    /** MegaPeer 详情提取媒体后必须从描述移除重复块。 */
    @Test
    fun megaPeerDetailsExtractsMediaAndCleansDescription() = runBlocking {
        /** details 来自固定合成详情页。 */
        val details = requireNotNull(MegaPeerProvider(FakeTorrentSearchNetworkClient(textHandler = { readProviderFixture("provider/megapeer/details_normal.html") })).getDetails("https://megapeer.vip/torrent/a"))
        assertEquals("1 GB", details.size)
        assertEquals(Category.MOVIES, details.category)
        assertEquals("https://megapeer.vip/poster.jpg", details.posterUrl)
        assertEquals(listOf("https://megapeer.vip/shot.jpg"), details.screenshotUrls)
        assertFalse(requireNotNull(details.description).contains("sp-wrap"))
        assertFalse(requireNotNull(details.description).contains("<img"))
    }

    /** MegaPeer 俄文月份和固定世纪必须覆盖 00/69/99、闰日与坏输入。 */
    @Test
    fun megaPeerRussianDatesUseStableTwentyFirstCentury() {
        assertEquals(951782400000L, MegaPeerRussianDateParser.parseEpochMillis("29 Фев 00"))
        assertEquals(3134592000000L, MegaPeerRussianDateParser.parseEpochMillis("1 Май 69"))
        assertEquals(4102358400000L, MegaPeerRussianDateParser.parseEpochMillis("31 Дек 99"))
        assertEquals(3981312000000L, MegaPeerRussianDateParser.parseEpochMillis("29 Фев 96"))
        assertNull(MegaPeerRussianDateParser.parseEpochMillis("29 Фев 99"))
        assertNull(MegaPeerRussianDateParser.parseEpochMillis("1 Неиз 24"))
    }

    /** Mikan 搜索编码查询并映射 magnet、大小、UTC 日期和同站链接。 */
    @Test
    fun mikanSearchEncodesAndParsesFixture() = runBlocking {
        /** client 返回固定列表。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { readProviderFixture("provider/mikan/search_normal.html") })
        /** item 是唯一动漫结果。 */
        val item = MikanProvider(client).search(" 动画 /?\u0000 ", Category.ANIME).single()
        assertEquals("动画 /?", client.textUrls.single().toHttpUrl().queryParameter("searchstr"))
        assertEquals(hash, item.infoHash)
        assertEquals("1.5 GB", item.size)
        assertEquals(1704164640000L, item.uploadEpochMillis)
        assertEquals(Category.ANIME, item.category)
    }

    /** Mikan 详情完整 trim 中文字段并保留不可信 HTML。 */
    @Test
    fun mikanDetailsTrimsLabelsAndPreservesHtml() = runBlocking {
        /** details 由合成页解析。 */
        val details = requireNotNull(MikanProvider(FakeTorrentSearchNetworkClient(textHandler = { readProviderFixture("provider/mikan/details_normal.html") })).getDetails("https://mikanani.me/Home/Episode/a"))
        assertEquals("2 GB", details.size)
        assertEquals(1704164640000L, details.uploadEpochMillis)
        assertTrue(requireNotNull(details.description).contains("<script>"))
    }

    /** MyPornClub 只替换字面 percent20，普通空格保持 HttpUrl 编码。 */
    @Test
    fun myPornClubPreservesLiteralPercent20SearchRule() = runBlocking {
        /** client 记录两个合法空列表请求。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { "<div class='torrents_list'></div>" })
        /** provider 复用同一网络边界。 */
        val provider = MyPornClubProvider(client, { now })
        provider.search(" alpha%20beta /? ", Category.PORN)
        provider.search("alpha beta", Category.PORN)
        assertEquals("/s/alpha-beta%20%2F%3F/seeders", client.textUrls[0].toHttpUrl().encodedPath)
        assertEquals("/s/alpha%20beta/seeders", client.textUrls[1].toHttpUrl().encodedPath)
    }

    /** MyPornClub 无页面 magnet 时使用公共固定 tracker 构造。 */
    @Test
    fun myPornClubCompletesListAndGeneratesMagnet() = runBlocking {
        /** client 按路径返回列表或详情。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { url -> if (url.contains("/torrent/")) readProviderFixture("provider/mypornclub/details_normal.html") else readProviderFixture("provider/mypornclub/search_normal.html") })
        /** item 合并列表与详情字段。 */
        val item = MyPornClubProvider(client, { now }).search("x", Category.PORN).single()
        assertEquals(hash, item.infoHash)
        assertEquals(20, requireNotNull(item.magnetUri).split("&tr=").size - 1)
        assertEquals(now - 172800000L, item.uploadEpochMillis)
        assertEquals("2 gb", item.size)
    }

    /** MyPornClub 页面 magnet 与 hash 冲突时详情无效。 */
    @Test
    fun myPornClubRejectsConflictingMagnet() = runBlocking {
        /** body 注入另一合法 hash 的 magnet。 */
        val body = readProviderFixture("provider/mypornclub/details_normal.html") + "<a class='md_btn' href='magnet:?xt=urn:btih:1123456789abcdef0123456789abcdef01234567'>m</a>"
        assertNull(MyPornClubProvider(FakeTorrentSearchNetworkClient(textHandler = { body }), { now }).getDetails("https://myporn.club/torrent/a"))
    }

    /** 三站详情入口拒绝跨站、HTTP、用户信息和异常端口且不联网。 */
    @Test
    fun detailsRejectUnsafeUrlsBeforeNetwork() = runBlocking {
        /** megaClient 用调用记录证明网络前拒绝。 */
        val megaClient = FakeTorrentSearchNetworkClient()
        /** mikanClient 用调用记录证明网络前拒绝。 */
        val mikanClient = FakeTorrentSearchNetworkClient()
        /** clubClient 用调用记录证明网络前拒绝。 */
        val clubClient = FakeTorrentSearchNetworkClient()
        /** url 是当前 MegaPeer 恶意详情候选。 */
        assertUnsafeDetails("megapeer.vip") { url -> MegaPeerProvider(megaClient).getDetails(url) }
        /** url 是当前 Mikan 恶意详情候选。 */
        assertUnsafeDetails("mikanani.me") { url -> MikanProvider(mikanClient).getDetails(url) }
        /** url 是当前 MyPornClub 恶意详情候选。 */
        assertUnsafeDetails("myporn.club") { url -> MyPornClubProvider(clubClient).getDetails(url) }
        assertTrue(megaClient.textUrls.isEmpty())
        assertTrue(mikanClient.textUrls.isEmpty())
        assertTrue(clubClient.textUrls.isEmpty())
    }

    /** MyPornClub latest/top 每次发起独立固定请求。 */
    @Test
    fun myPornClubLatestAndTopAreIndependent() = runBlocking {
        /** client 返回合法空列表并记录调用。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { "<div class='torrents_list'></div>" })
        /** provider 连续执行两种浏览。 */
        val provider = MyPornClubProvider(client, { now })
        provider.getLatestTorrents(Category.PORN)
        provider.getTopTorrents(Category.PORN)
        assertEquals(listOf("https://myporn.club/ts/latest/alltime", "https://myporn.club/ts/hits/alltime"), client.textUrls)
    }

    /** 对一个站点详情入口断言四类恶意 URL 都被稳定拒绝。 */
    private suspend fun assertUnsafeDetails(
        /** 当前站点精确 host。 */ host: String,
        /** 当前详情入口。 */ call: suspend (String) -> Any?,
    ) {
        /** url 是当前恶意输入。 */
        for (url in listOf("https://evil.example/a", "http://$host/a", "https://user@$host/a", "https://$host:8443/a")) {
            try {
                call(url)
                fail("非法 URL 必须拒绝")
            /** failure 是无正文的 URL 策略拒绝。 */
            } catch (failure: HttpDeniedException) {
                assertNull(failure.message)
            }
        }
    }
}
