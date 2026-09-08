package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.provider.builtin.subsplease.SubsPleaseProvider
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import com.cla.clip.feature.torrent.search.provider.builtin.therarbag.TheRarBgProvider
import com.cla.clip.feature.torrent.search.provider.builtin.thirteenthirtysevenx.ThirteenThirtySevenXProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 04E5 三站的公开请求、解析、安全、并发与边界契约。 */
class EleventhBatchProviderTest {
    /** 六份合成 fixture 共用的合法 40 位 infoHash。 */
    private val hash = "0123456789abcdef0123456789abcdef01234567"
    /** 相对日期固定在 2024-01-04 UTC 零点，避免设备时间影响结果。 */
    private val now = 1704326400000L

    /** 三站描述符必须匹配 manifest 审计，而不是继续沿用上游宽松安全等级。 */
    @Test
    fun metadataMatchesLocalManifestAudit() {
        /** client 只满足构造依赖，不触发网络。 */
        val client = FakeTorrentSearchNetworkClient()
        /** subsDescriptor 固定动漫、安全和默认关闭。 */
        val subsDescriptor = SubsPleaseProvider(client).descriptor
        assertEquals("subsplease", subsDescriptor.id.value)
        assertEquals(setOf(Category.ANIME), subsDescriptor.supportedCategories)
        assertEquals(ProviderSafetyLevel.SAFE, subsDescriptor.safetyLevel)
        assertFalse(subsDescriptor.enabledByDefault)
        /** rarDescriptor 固定不安全等级和九类能力。 */
        val rarDescriptor = TheRarBgProvider(client).descriptor
        assertEquals("therarbag", rarDescriptor.id.value)
        assertEquals(ProviderSafetyLevel.UNSAFE, rarDescriptor.safetyLevel)
        assertFalse(rarDescriptor.enabledByDefault)
        /** thirteenDescriptor 必须按本地 manifest 视为 NSFW 且启用 Cloudflare 标记。 */
        val thirteenDescriptor = ThirteenThirtySevenXProvider(client, { now }).descriptor
        assertEquals("1337x", thirteenDescriptor.id.value)
        assertEquals(ProviderSafetyLevel.NSFW, thirteenDescriptor.safetyLevel)
        assertTrue(thirteenDescriptor.isCloudflareProtected)
        assertFalse(thirteenDescriptor.enabledByDefault)
    }

    /** SubsPlease 搜索必须结构化编码查询并映射合法下载项。 */
    @Test
    fun subsPleaseSearchEncodesQueryAndParsesResult() = runBlocking {
        /** client 返回固定对象顶层 JSON 并记录真实 URL。 */
        val client = FakeTorrentSearchNetworkClient(
            jsonHandler = { readProviderFixture("provider/subsplease/search_normal.json") },
        )
        /** item 是唯一合法分辨率下载项。 */
        val item = SubsPleaseProvider(client).search("  fixture /?\u0000  ", Category.ANIME).single()
        assertEquals("https://subsplease.org/api?f=search&tz=%24&s=fixture%20%2F%3F", client.jsonUrls.single())
        assertEquals(hash, item.infoHash)
        assertEquals("Fixture Show [1080p]", item.name)
        assertEquals("1.00 GB", item.size)
        assertEquals(1704153600000L, item.uploadEpochMillis)
        assertEquals(Category.ANIME, item.category)
        assertEquals("https://subsplease.org/shows/fixture?ep=01&res=1080", item.descriptionPageUrl)
    }

    /** SubsPlease latest 必须每次独立读取固定 API，不夹带调用方分类。 */
    @Test
    fun subsPleaseLatestUsesIndependentFixedRequest() = runBlocking {
        /** client 每次返回合法空对象。 */
        val client = FakeTorrentSearchNetworkClient(jsonHandler = { "{}" })
        /** provider 连续调用两次以证明没有缓存。 */
        val provider = SubsPleaseProvider(client)
        provider.getLatestTorrents(Category.ALL)
        provider.getLatestTorrents(Category.PORN)
        assertEquals(
            listOf(
                "https://subsplease.org/api/?f=latest&tz=%24",
                "https://subsplease.org/api/?f=latest&tz=%24",
            ),
            client.jsonUrls,
        )
    }

    /** SubsPlease 详情必须校验 ep/res，并通过 show API 精确选择对应下载。 */
    @Test
    fun subsPleaseDetailsLoadsMatchingEpisodeAndResolution() = runBlocking {
        /** client 分别返回静态详情页和 show JSON。 */
        val client = FakeTorrentSearchNetworkClient(
            textHandler = { readProviderFixture("provider/subsplease/details_normal.html") },
            jsonHandler = { readProviderFixture("provider/subsplease/show_normal.json") },
        )
        /** details 是经两段解析得到的完整领域详情。 */
        val details = requireNotNull(
            SubsPleaseProvider(client).getDetails("https://subsplease.org/shows/fixture?ep=01&res=1080"),
        )
        assertEquals(listOf("https://subsplease.org/shows/fixture?ep=01&res=1080"), client.textUrls)
        assertEquals(listOf("https://subsplease.org/api/?f=show&tz=%24&sid=42"), client.jsonUrls)
        assertEquals(hash, details.infoHash)
        assertEquals("Fixture Show - 01 [1080p]", details.name)
        assertEquals("1.00 GB", details.size)
        assertEquals(1704153600000L, details.uploadEpochMillis)
        assertEquals("https://subsplease.org/torrents/fixture.torrent", details.fileDownloadLink)
        assertEquals("https://subsplease.org/images/poster.jpg", details.posterUrl)
        assertEquals("Neutral fixture description.", details.description)
    }

    /** SubsPlease 非法 authority 或缺失唯一参数必须在网络前拒绝。 */
    @Test
    fun subsPleaseRejectsUnsafeOrAmbiguousDetailsBeforeNetwork() = runBlocking {
        /** client 的调用记录证明拒绝边界发生在网络前。 */
        val client = FakeTorrentSearchNetworkClient()
        /** urls 覆盖跨站、userinfo、端口、缺参数和重复参数。 */
        val urls = listOf(
            "https://evil.example/shows/x?ep=1&res=1080",
            "https://user@subsplease.org/shows/x?ep=1&res=1080",
            "https://subsplease.org:8443/shows/x?ep=1&res=1080",
            "https://subsplease.org/shows/x?ep=1",
            "https://subsplease.org/shows/x?ep=1&ep=2&res=1080",
        )
        /** url 是当前不可信详情候选。 */
        for (url in urls) {
            try {
                SubsPleaseProvider(client).getDetails(url)
                fail("非法详情参数必须拒绝")
            /** failure 只验证低敏策略类型。 */
            } catch (failure: HttpDeniedException) {
                assertNull(failure.message)
            }
        }
        assertTrue(client.textUrls.isEmpty())
        assertTrue(client.jsonUrls.isEmpty())
    }

    /** SubsPlease 坏必填字段逐项跳过，可选跨站媒体只独立降级。 */
    @Test
    fun subsPleaseSkipsBadRequiredFieldsAndDegradesUnsafeMedia() = runBlocking {
        /** search 是固定正常列表响应。 */
        val search = readProviderFixture("provider/subsplease/search_normal.json")
        /** body 分别破坏 show 名、episode、resolution 与 magnet。 */
        val invalidBodies = listOf(
            search.replace("Fixture Show", ""),
            search.replace("\"episode\": \"01\"", "\"episode\": null"),
            search.replace("\"res\": \"1080\"", "\"res\": null"),
            search.replace(hash.uppercase(), "bad"),
        )
        /** body 是当前只含坏条目的响应。 */
        for (body in invalidBodies) {
            assertTrue(SubsPleaseProvider(FakeTorrentSearchNetworkClient(jsonHandler = { body })).search("x", Category.ALL).isEmpty())
        }
        /** unsafeMedia 只污染可选 torrent 和 poster，合法详情身份仍需保留。 */
        val unsafeShow = readProviderFixture("provider/subsplease/show_normal.json")
            .replace("https://subsplease.org/torrents/fixture.torrent", "https://evil.example/file.torrent")
        /** client 返回跨站海报及下载候选。 */
        val client = FakeTorrentSearchNetworkClient(
            textHandler = { readProviderFixture("provider/subsplease/details_normal.html").replace("/images/poster.jpg", "https://evil.example/poster.jpg") },
            jsonHandler = { unsafeShow },
        )
        /** details 不能因可选媒体污染丢失合法 magnet。 */
        val details = requireNotNull(SubsPleaseProvider(client).getDetails("https://subsplease.org/shows/x?ep=01&res=1080"))
        assertNull(details.fileDownloadLink)
        assertNull(details.posterUrl)
    }

    /** TheRarBg 搜索、最新和热门必须固定路径语义与分类映射。 */
    @Test
    fun theRarBgBuildsSearchLatestAndTopPaths() = runBlocking {
        /** client 返回合法空列表并记录三个独立请求。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { "<table><tbody></tbody></table>" })
        /** provider 不复用响应缓存。 */
        val provider = TheRarBgProvider(client)
        provider.search("  fixture /?\u0000  ", Category.MUSIC)
        provider.getLatestTorrents(Category.ALL)
        provider.getTopTorrents(Category.PORN)
        assertEquals(
            listOf(
                "https://therarbg.com/get-posts/keywords:fixture%20%2F%3F:category:Music",
                "https://therarbg.com/get-posts/time:2D",
                "https://therarbg.com/get-posts/time:2D:order:-se:category:XXX",
            ),
            client.textUrls,
        )
    }

    /** TheRarBg 列表通过详情补全磁力，同时保留列表自己的名称与统计。 */
    @Test
    fun theRarBgParsesListAndDetailsFields() = runBlocking {
        /** client 按 URL 类型返回列表或详情 fixture。 */
        val client = FakeTorrentSearchNetworkClient(
            // url 区分列表请求与逐条详情请求。
            textHandler = { url ->
                if (url.contains("post-detail")) readProviderFixture("provider/therarbag/details_normal.html")
                else readProviderFixture("provider/therarbag/search_normal.html")
            },
        )
        /** item 保留来源行展示字段和详情身份。 */
        val item = TheRarBgProvider(client).search("x", Category.ALL).single()
        assertEquals(hash, item.infoHash)
        assertEquals("Fixture Rar", item.name)
        assertEquals("1.00 GB", item.size)
        assertEquals(7u, item.seeders)
        assertEquals(3u, item.peers)
        assertEquals(1704153600000L, item.uploadEpochMillis)
        assertEquals(Category.MOVIES, item.category)
        /** details 验证站点标签映射和 a.m./p.m. 归一化。 */
        val details = requireNotNull(TheRarBgProvider(client).getDetails("https://therarbg.com/post-detail/fixture"))
        assertEquals("Fixture Rar Details", details.name)
        assertEquals(1704200400000L, details.uploadEpochMillis)
        assertEquals("Neutral fixture description.", details.description)
    }

    /** TheRarBg 详情输入 authority 必须在请求前精确校验。 */
    @Test
    fun theRarBgRejectsUnsafeDetailsBeforeNetwork() = runBlocking {
        /** client 不应收到任何非法输入。 */
        val client = FakeTorrentSearchNetworkClient()
        /** url 覆盖跨站、明文、userinfo 与异常端口。 */
        for (url in listOf("https://evil.example/a", "http://therarbg.com/a", "https://user@therarbg.com/a", "https://therarbg.com:8443/a")) {
            try {
                TheRarBgProvider(client).getDetails(url)
                fail("非法详情地址必须拒绝")
            /** failure 不得包含输入。 */
            } catch (failure: HttpDeniedException) {
                assertNull(failure.message)
            }
        }
        assertTrue(client.textUrls.isEmpty())
    }

    /** 1337x 全部/类别/不支持分类与热门路径必须可预测且不崩溃。 */
    @Test
    fun thirteenBuildsSearchCategoryFallbackAndTopPaths() = runBlocking {
        /** client 返回空列表以隔离 URL 断言。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { "<table class='table-list'><tbody></tbody></table>" })
        /** provider 的时间源不参与空列表。 */
        val provider = ThirteenThirtySevenXProvider(client, { now })
        provider.search("  fixture /?\u0000  ", Category.ALL)
        provider.search("x", Category.PORN)
        provider.search("x", Category.BOOKS)
        provider.getTopTorrents(Category.MUSIC)
        assertEquals(
            listOf(
                "https://1337x.to/search/fixture%20%2F%3F/1/",
                "https://1337x.to/category-search/x/XXX/1/",
                "https://1337x.to/search/x/1/",
                "https://1337x.to/top-100",
            ),
            client.textUrls,
        )
    }

    /** 1337x 列表和详情必须映射身份、相对日期与受限媒体。 */
    @Test
    fun thirteenParsesListAndDetailsFields() = runBlocking {
        /** client 按详情路径返回对应 fixture。 */
        val client = FakeTorrentSearchNetworkClient(
            // url 区分列表请求与逐条详情请求。
            textHandler = { url ->
                if (url.contains("/torrent/")) readProviderFixture("provider/thirteen/details_normal.html")
                else readProviderFixture("provider/thirteen/search_normal.html")
            },
        )
        /** provider 使用固定时间解析列表和详情相对日期。 */
        val provider = ThirteenThirtySevenXProvider(client, { now })
        /** item 合并列表字段与详情 magnet。 */
        val item = provider.search("x", Category.ALL).single()
        assertEquals(hash, item.infoHash)
        assertEquals("Fixture 1337", item.name)
        assertEquals("1024 MB", item.size)
        assertEquals(1717358400000L, item.uploadEpochMillis)
        assertEquals(Category.MOVIES, item.category)
        assertEquals("https://itorrents.org/torrent/$hash.torrent", item.fileDownloadLink)
        /** details 的相对日期都基于注入 now。 */
        val details = requireNotNull(provider.getDetails("https://1337x.to/torrent/fixture"))
        assertEquals(now - 24L * 60L * 60L * 1000L, details.uploadEpochMillis)
        assertEquals(now - 2L * 24L * 60L * 60L * 1000L, details.lastCheckedEpochMillis)
        assertEquals("https://1337x.to/images/poster.jpg", details.posterUrl)
        assertTrue(requireNotNull(details.description).contains("<b>"))
    }

    /** 1337x 三种列表日期都必须严格解析，坏值独立降级。 */
    @Test
    fun thirteenParsesThreeListDateShapesStrictly() = runBlocking {
        /** normal 是固定列表形状。 */
        val normal = readProviderFixture("provider/thirteen/search_normal.html")
        /** cases 手写上游三种日期与 UTC 预期。 */
        val cases = listOf(
            "12:45am" to 1704329100000L,
            "8pm Jun. 2nd" to 1717358400000L,
            "May. 27th '26" to 1779840000000L,
            "bad" to null,
        )
        /** pair 是当前日期文本与预期时间。 */
        for ((rawDate, expected) in cases) {
            /** body 只替换日期，不改变其他字段。 */
            val body = normal.replace("8pm Jun. 2nd", rawDate)
            /** client 返回列表和固定合法详情。 */
            val client = FakeTorrentSearchNetworkClient(
                // url 区分列表请求与逐条详情请求。
                textHandler = { url -> if (url.contains("/torrent/")) readProviderFixture("provider/thirteen/details_normal.html") else body },
            )
            assertEquals(expected, ThirteenThirtySevenXProvider(client, { now }).search("x", Category.ALL).single().uploadEpochMillis)
        }
    }

    /** 1337x 必须拒绝不可信详情输入并降级跨站或 hash 冲突媒体。 */
    @Test
    fun thirteenEnforcesDetailsAndMediaUrlPolicies() = runBlocking {
        /** deniedClient 的调用记录用于证明网络前拒绝。 */
        val deniedClient = FakeTorrentSearchNetworkClient()
        /** url 是当前不可信详情地址。 */
        for (url in listOf("https://evil.example/a", "http://1337x.to/a", "https://user@1337x.to/a", "https://1337x.to:8443/a")) {
            try {
                ThirteenThirtySevenXProvider(deniedClient, { now }).getDetails(url)
                fail("非法详情地址必须拒绝")
            /** failure 只验证稳定策略类型。 */
            } catch (failure: HttpDeniedException) {
                assertNull(failure.message)
            }
        }
        assertTrue(deniedClient.textUrls.isEmpty())
        /** unsafeHtml 同时污染下载 host 与海报 host。 */
        val unsafeHtml = readProviderFixture("provider/thirteen/details_normal.html")
            .replace("https://itorrents.org/torrent/${hash.uppercase()}.torrent", "https://evil.example/torrent/$hash.torrent")
            .replace("/images/poster.jpg", "https://evil.example/poster.jpg")
        /** details 仍保留合法 magnet，但两个可选媒体均为空。 */
        val details = requireNotNull(
            ThirteenThirtySevenXProvider(FakeTorrentSearchNetworkClient(textHandler = { unsafeHtml }), { now })
                .getDetails("https://1337x.to/torrent/x"),
        )
        assertNull(details.fileDownloadLink)
        assertNull(details.posterUrl)
    }

    /** 两个逐条详情站点必须并发启动并在逆序完成后保持来源顺序。 */
    @Test
    fun detailBatchProvidersRunConcurrentlyAndPreserveSourceOrder() = runBlocking {
        /** rarList 是两条详情地址互异的列表。 */
        val rarList = duplicateResultFixture(
            readProviderFixture("provider/therarbag/search_normal.html"),
            "/post-detail/fixture",
            "/post-detail/second",
            "Fixture Rar",
            "Second Rar",
        )
        assertConcurrentOrder(
            listHtml = rarList,
            firstMarker = "/post-detail/fixture",
            secondMarker = "/post-detail/second",
            firstDetails = readProviderFixture("provider/therarbag/details_normal.html"),
            secondDetails = readProviderFixture("provider/therarbag/details_normal.html").replace(hash.uppercase(), "1123456789ABCDEF0123456789ABCDEF01234567"),
            providerFactory = ::TheRarBgProvider,
            expectedNames = listOf("Fixture Rar", "Second Rar"),
        )
        /** thirteenList 是两条 1337x 详情草稿。 */
        val thirteenList = duplicateResultFixture(
            readProviderFixture("provider/thirteen/search_normal.html"),
            "/torrent/fixture",
            "/torrent/second",
            "Fixture 1337",
            "Second 1337",
        )
        assertConcurrentOrder(
            listHtml = thirteenList,
            firstMarker = "/torrent/fixture",
            secondMarker = "/torrent/second",
            firstDetails = readProviderFixture("provider/thirteen/details_normal.html"),
            secondDetails = readProviderFixture("provider/thirteen/details_normal.html").replace(hash.uppercase(), "1123456789ABCDEF0123456789ABCDEF01234567"),
            // client 用于构造带固定时钟的真实 1337x Provider。
            providerFactory = { client -> ThirteenThirtySevenXProvider(client, { now }) },
            expectedNames = listOf("Fixture 1337", "Second 1337"),
        )
    }

    /** 复制单行 fixture 并替换身份，生成顺序明确的两条列表。 */
    private fun duplicateResultFixture(
        /** 原始单行 HTML。 */
        html: String,
        /** 第一条详情 marker。 */
        firstMarker: String,
        /** 第二条详情 marker。 */
        secondMarker: String,
        /** 第一条展示名。 */
        firstName: String,
        /** 第二条展示名。 */
        secondName: String,
    ): String {
        /** secondRow 是只替换详情地址和展示名的第二行。 */
        val secondRow = html.substringAfter("<tbody>").substringBefore("</tbody>")
            .replace(firstMarker, secondMarker)
            .replace(firstName, secondName)
        return html.replace("</tbody>", "$secondRow</tbody>")
    }

    /** 验证 N+1 详情在第一项阻塞时仍能启动第二项，并按源顺序合并。 */
    private suspend fun assertConcurrentOrder(
        /** 两条列表 HTML。 */
        listHtml: String,
        /** 第一条详情 URL 标识。 */
        firstMarker: String,
        /** 第二条详情 URL 标识。 */
        secondMarker: String,
        /** 第一条详情响应。 */
        firstDetails: String,
        /** 第二条详情响应。 */
        secondDetails: String,
        /** 构造真实 Provider 的工厂。 */
        providerFactory: (FakeTorrentSearchNetworkClient) -> SearchProvider,
        /** 来源顺序下的预期名称。 */
        expectedNames: List<String>,
    ) = coroutineScope {
        /** firstStarted 表示第一条详情请求已经进入 fake。 */
        val firstStarted = CompletableDeferred<Unit>()
        /** secondStarted 表示第二条详情请求已经进入 fake。 */
        val secondStarted = CompletableDeferred<Unit>()
        /** releaseFirst 控制第一条晚于第二条返回。 */
        val releaseFirst = CompletableDeferred<Unit>()
        /** releaseSecond 控制第二条先返回。 */
        val releaseSecond = CompletableDeferred<Unit>()
        /** client 根据 URL 在内存中模拟列表和两个详情响应。 */
        val client = FakeTorrentSearchNetworkClient(
            // url 决定当前是列表、第一条详情或第二条详情请求。
            textHandler = { url ->
                when {
                    url.contains(firstMarker) -> {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                        firstDetails
                    }
                    url.contains(secondMarker) -> {
                        secondStarted.complete(Unit)
                        releaseSecond.await()
                        secondDetails
                    }
                    else -> listHtml
                }
            },
        )
        /** result 在子任务执行真实搜索。 */
        val result = async { providerFactory(client).search("x", Category.ALL) }
        /** 超时保护能把串行退化明确报告为测试失败。 */
        withTimeout(2_000L) {
            firstStarted.await()
            secondStarted.await()
        }
        releaseSecond.complete(Unit)
        releaseFirst.complete(Unit)
        // item 是按最终输出顺序读取名称的真实结果。
        assertEquals(expectedNames, result.await().map { item -> item.name })
    }
}
