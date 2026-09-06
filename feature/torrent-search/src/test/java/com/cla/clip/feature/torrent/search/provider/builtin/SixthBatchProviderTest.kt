package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.builtin.knaben.KnabenProvider
import com.cla.clip.feature.torrent.search.provider.builtin.limetorrents.LimeTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.linuxtracker.LinuxTrackerProvider
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** 第六批 Provider 的固定请求、解析容错与 URL 安全契约。 */
class SixthBatchProviderTest {
    /** 合成 fixture 统一使用的合法 40 位 hash。 */
    private val hash = "0123456789abcdef0123456789abcdef01234567"
    /** 相对日期基准固定在 2024-01-04 UTC，避免真实时间使断言漂移。 */
    private val now = 1704326400000L

    /** Knaben 搜索必须提交精确结构化正文并映射完整字段。 */
    @Test
    fun knabenSearchBuildsExactJsonAndParsesResult() = runBlocking {
        /** bodies 只保存在测试内存，用于比较合成请求契约。 */
        val bodies = mutableListOf<String>()
        /** client 返回固定 JSON 并捕获请求正文。 */
        val client = FakeTorrentSearchNetworkClient(postJsonHandler = { _, body ->
            bodies += body
            readProviderFixture("provider/knaben/search_normal.json")
        })
        /** item 是唯一合法结果，字段应由 parser 统一映射。 */
        val item = KnabenProvider(client).search("  li\u0000nux  ", Category.MUSIC).single()
        /** expectedBody 固定全部字段和值，JSON 对象比较不依赖序列化字段顺序。 */
        val expectedBody = """{"query":"linux","size":300,"order_by":"seeders","order_direction":"desc","hide_unsafe":true,"hide_xxx":false,"categories":[1000000]}"""
        assertEquals(Json.parseToJsonElement(expectedBody), Json.parseToJsonElement(bodies.single()))
        assertEquals(listOf("https://api.knaben.org/v1"), client.postJsonUrls)
        assertEquals(hash, item.infoHash)
        assertEquals("1.00 GB", item.size)
        assertEquals(7u, item.seeders)
        assertEquals(3u, item.peers)
        assertEquals(1704153600000L, item.uploadEpochMillis)
        assertEquals(Category.MUSIC, item.category)
        assertEquals("https://knaben.org/detail/fixture", item.descriptionPageUrl)
    }

    /** Knaben latest/top 必须省略 query，并分别使用 date/seeders。 */
    @Test
    fun knabenLatestAndTopUseIndependentBodiesWithoutAllCategory() = runBlocking {
        /** bodies 按两次调用顺序捕获，正文不写生产日志。 */
        val bodies = mutableListOf<String>()
        /** client 每次返回合法空 hits。 */
        val client = FakeTorrentSearchNetworkClient(postJsonHandler = { _, body -> bodies += body; "{\"hits\":[]}" })
        /** provider 共用网络边界但每个入口独立构造正文。 */
        val provider = KnabenProvider(client)
        provider.getLatestTorrents(Category.ALL)
        provider.getTopTorrents(Category.ALL)
        assertEquals(2, client.postJsonUrls.size)
        assertFalse(bodies[0].contains("query"))
        assertFalse(bodies[0].contains("categories"))
        assertEquals("date", Json.parseToJsonElement(bodies[0]).let { it.jsonObject["order_by"]?.jsonPrimitive?.content })
        assertEquals("seeders", Json.parseToJsonElement(bodies[1]).let { it.jsonObject["order_by"]?.jsonPrimitive?.content })
    }

    /** Knaben 分类必须按最小有效完整数值区间映射，并区分音乐与其他。 */
    @Test
    fun knabenMapsSmallestValidCategoryAndNumericBoundaries() = runBlocking {
        /** categories 是需要逐项锁定的 JSON 数组内容与期望分类。 */
        val categories = listOf(
            "[10000000,1999999]" to Category.MUSIC,
            "[10000000,9999999]" to Category.BOOKS,
            "[10000000]" to Category.OTHER,
            "[null,\"bad\",11000000]" to Category.ALL,
        )
        /** pair 表示当前 categoryId fixture 与领域分类。 */
        for ((rawIds, expectedCategory) in categories) {
            /** body 只保留本断言需要的必填字段。 */
            val body = """{"hits":[{"title":"fixture","magnetUrl":"magnet:?xt=urn:btih:$hash","categoryId":$rawIds}]}"""
            /** item 必须保留合法 torrent 并单独降级分类。 */
            val item = KnabenProvider(FakeTorrentSearchNetworkClient(postJsonHandler = { _, _ -> body })).search("x", Category.ALL).single()
            assertEquals(expectedCategory, item.category)
        }
    }

    /** Knaben 数字字段不接受字符串伪装，无符号溢出只降级可选字段。 */
    @Test
    fun knabenRejectsWrongNumericTypesAndUnsignedOverflow() = runBlocking {
        /** body 用字符串 categoryId 与越界人数验证严格 JSON 标量类型。 */
        val body = """{"hits":[{"title":"fixture","magnetUrl":"magnet:?xt=urn:btih:$hash","bytes":"1073741824","seeders":-1,"peers":4294967296,"categoryId":["1000000"]}]}"""
        /** POST 参数不参与断言，回调只返回合成响应。 */
        val item = KnabenProvider(FakeTorrentSearchNetworkClient(postJsonHandler = { _, _ -> body })).search("x", Category.ALL).single()
        assertNull(item.size)
        assertNull(item.seeders)
        assertNull(item.peers)
        assertEquals(Category.ALL, item.category)
    }

    /** Knaben 坏顶层应失败，坏条目和恶意可选详情只影响对应字段或条目。 */
    @Test
    fun knabenDistinguishesTopLevelFailureAndOptionalUnsafeDetails() = runBlocking {
        /** invalidBody 是结构合法但 hits 契约错误的顶层响应。 */
        for (invalidBody in listOf("{}", "{\"hits\":{}}", "[]")) {
            /** POST 两参数不参与断言，回调返回当前坏顶层。 */
            try { KnabenProvider(FakeTorrentSearchNetworkClient(postJsonHandler = { _, _ -> invalidBody })).search("x", Category.ALL); fail("hits 非数组必须失败") }
            /** failure 必须是无正文稳定异常。 */
            catch (failure: ProviderParseException) { assertNull(failure.message) }
        }
        /** maliciousDetails 覆盖外站、用户信息、明文和异常端口。 */
        val maliciousDetails = listOf("https://evil.example/item", "https://user@knaben.org/item", "http://knaben.org/item", "https://knaben.org:8443/item")
        /** details 是当前可选恶意值，合法 torrent 不应被丢弃。 */
        for (details in maliciousDetails) {
            /** body 同时放入一个坏条目，验证逐条容错。 */
            val body = """{"hits":[{"title":"bad"},{"title":"fixture","magnetUrl":"magnet:?xt=urn:btih:$hash","details":"$details"}]}"""
            /** POST 两参数不参与断言，回调返回当前恶意可选字段 fixture。 */
            assertNull(KnabenProvider(FakeTorrentSearchNetworkClient(postJsonHandler = { _, _ -> body })).search("x", Category.ALL).single().descriptionPageUrl)
        }
    }

    /** Lime 搜索必须结构化编码查询、离线提 hash 并重建 HTTPS 下载链接。 */
    @Test
    fun limeSearchEncodesPathAndRebuildsHttpsDownload() = runBlocking {
        /** client 只返回合成列表页。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { readProviderFixture("provider/limetorrents/search_normal.html") })
        /** item 保留上源字段，但不输出原始 HTTP marker 或 query。 */
        val item = LimeTorrentsProvider(client, { now }).search("  linux /?\u0000  ", Category.MUSIC).single()
        assertEquals("https://limetorrents.fun/search/music/linux%20%2F%3F/date/1/", client.textUrls.single())
        assertEquals(hash, item.infoHash)
        assertEquals("https://itorrents.net/torrent/$hash.torrent", item.fileDownloadLink)
        assertEquals("https://limetorrents.fun/fixture", item.descriptionPageUrl)
        assertEquals(Category.MUSIC, item.category)
        assertEquals(now - 2L * 24L * 60L * 60L * 1000L, item.uploadEpochMillis)
    }

    /** Lime 的全部 search 分类和 browse 大小写路径必须固定。 */
    @Test
    fun limeMapsAllSearchAndBrowseCategories() = runBlocking {
        /** expectedSearch 固定每个领域分类的搜索 path 分类。 */
        val expectedSearch = linkedMapOf(
            Category.ALL to "all", Category.BOOKS to "all", Category.PORN to "all", Category.ANIME to "anime",
            Category.APPS to "applications", Category.GAMES to "games", Category.MOVIES to "movies",
            Category.MUSIC to "music", Category.SERIES to "tv", Category.OTHER to "other",
        )
        /** pair 是当前分类与固定路径段。 */
        for ((category, segment) in expectedSearch) {
            /** client 记录唯一 search 请求。 */
            val client = FakeTorrentSearchNetworkClient(textHandler = { "<table></table>" })
            LimeTorrentsProvider(client).search("x", category)
            assertEquals("https://limetorrents.fun/search/$segment/x/date/1/", client.textUrls.single())
        }
        /** expectedBrowse 只包含上游支持的七类与精确大小写。 */
        val expectedBrowse = linkedMapOf(Category.ANIME to "Anime", Category.APPS to "Applications", Category.GAMES to "Games", Category.MOVIES to "Movies", Category.MUSIC to "Music", Category.SERIES to "TV-shows", Category.OTHER to "Other")
        /** pair 是当前支持分类和 browse 路径段。 */
        for ((category, segment) in expectedBrowse) {
            /** client 记录 latest 与 top 两个独立请求。 */
            val client = FakeTorrentSearchNetworkClient(textHandler = { "<table></table>" })
            /** provider 不缓存两次响应。 */
            val provider = LimeTorrentsProvider(client)
            provider.getLatestTorrents(category)
            provider.getTopTorrents(category)
            assertEquals(listOf("https://limetorrents.fun/browse-torrents/$segment/", "https://limetorrents.fun/cat_top/16/$segment/"), client.textUrls)
        }
    }

    /** Lime latest/top 对不支持分类必须空返回且不联网。 */
    @Test
    fun limeUnsupportedBrowseCategoriesDoNotRequestNetwork() = runBlocking {
        /** client 用调用记录证明短路发生在网络前。 */
        val client = FakeTorrentSearchNetworkClient()
        /** provider 对 ALL、BOOKS、PORN 都执行两种浏览入口。 */
        val provider = LimeTorrentsProvider(client)
        /** category 是当前不支持浏览的领域分类。 */
        for (category in listOf(Category.ALL, Category.BOOKS, Category.PORN)) {
            assertTrue(provider.getLatestTorrents(category).isEmpty())
            assertTrue(provider.getTopTorrents(category).isEmpty())
        }
        assertTrue(client.textUrls.isEmpty())
    }

    /** Lime marker 必须拒绝伪 host、用户信息、异常端口和额外路径。 */
    @Test
    fun limeRejectsUnsafeTorrentMarkers() = runBlocking {
        /** markers 覆盖不能产出或访问的恶意输入。 */
        val markers = listOf(
            "https://itorrents.net.evil.example/torrent/$hash.torrent",
            "https://user@itorrents.net/torrent/$hash.torrent",
            "https://itorrents.net:8443/torrent/$hash.torrent",
            "https://itorrents.net/torrent/extra/$hash.torrent",
            "https://itorrents.net/torrent/bad.torrent",
        )
        /** marker 是当前替换进结果 fixture 的下载候选。 */
        for (marker in markers) {
            /** body 保留名称等其他字段，只替换原始 marker。 */
            val body = readProviderFixture("provider/limetorrents/search_normal.html").replace("http://itorrents.net/torrent/0123456789ABCDEF0123456789ABCDEF01234567.torrent?title=fixture", marker)
            assertTrue(LimeTorrentsProvider(FakeTorrentSearchNetworkClient(textHandler = { body })).search("x", Category.ALL).isEmpty())
        }
    }

    /** Lime 详情要求名称、hash、合法 magnet 且身份一致，缺分隔符只降级日期分类。 */
    @Test
    fun limeDetailsRequiresMatchingHashAndHandlesMissingSeparator() = runBlocking {
        /** normal 是固定详情形状。 */
        val normal = readProviderFixture("provider/limetorrents/details_normal.html")
        /** details 由站点 host 网络读取并映射可选字段。 */
        val details = requireNotNull(LimeTorrentsProvider(FakeTorrentSearchNetworkClient(textHandler = { normal }), { now }).getDetails("https://www.limetorrents.lol/item"))
        assertEquals(hash, details.infoHash)
        assertEquals("https://itorrents.net/torrent/$hash.torrent", details.fileDownloadLink)
        assertEquals(Category.MUSIC, details.category)
        /** mismatch 只改 magnet hash，必须拒绝详情。 */
        val mismatch = normal.replace(hash, "1123456789abcdef0123456789abcdef01234567")
        assertNull(LimeTorrentsProvider(FakeTorrentSearchNetworkClient(textHandler = { mismatch })).getDetails("https://limetorrents.fun/item"))
        /** unsafeMarker 保留详情必填字段，只让可选下载链接跨站。 */
        val unsafeMarker = normal.replace("http://itorrents.net/torrent/0123456789ABCDEF0123456789ABCDEF01234567.torrent?title=fixture", "https://evil.example/torrent/$hash.torrent")
        assertNull(requireNotNull(LimeTorrentsProvider(FakeTorrentSearchNetworkClient(textHandler = { unsafeMarker })).getDetails("https://limetorrents.fun/item")).fileDownloadLink)
        /** noSeparator 移除分类分隔符，日期仍应独立解析且不越界。 */
        val noSeparator = normal.replace("2 days ago in Music.", "2 days ago")
        /** degraded 保留详情但日期和分类为空。 */
        val degraded = requireNotNull(LimeTorrentsProvider(FakeTorrentSearchNetworkClient(textHandler = { noSeparator }), { now }).getDetails("https://limetorrents.fun/item"))
        assertEquals(now - 2L * 24L * 60L * 60L * 1000L, degraded.uploadEpochMillis)
        assertNull(degraded.category)
        /** minutesText 证明分隔符不能误命中 minutes 单词内部的 in。 */
        val minutesText = normal.replace("2 days ago in Music.", "5 minutes ago in Music.")
        /** minutesDetails 同时保留正确相对日期与分类。 */
        val minutesDetails = requireNotNull(LimeTorrentsProvider(FakeTorrentSearchNetworkClient(textHandler = { minutesText }), { now }).getDetails("https://limetorrents.fun/item"))
        assertEquals(now - 5L * 60L * 1000L, minutesDetails.uploadEpochMillis)
        assertEquals(Category.MUSIC, minutesDetails.category)
    }

    /** Lime 详情请求必须在网络前拒绝跨站、用户信息、明文和异常端口。 */
    @Test
    fun limeRejectsUnsafeDetailsBeforeNetwork() = runBlocking {
        /** client 调用列表应在所有拒绝后保持空。 */
        val client = FakeTorrentSearchNetworkClient()
        /** url 是当前不可信详情地址。 */
        for (url in listOf("https://evil.example/item", "https://user@limetorrents.fun/item", "http://limetorrents.fun/item", "https://limetorrents.fun:8443/item")) {
            try { LimeTorrentsProvider(client).getDetails(url); fail("非法详情地址必须拒绝") }
            /** failure 不携带输入正文。 */
            catch (failure: HttpDeniedException) { assertNull(failure.message) }
        }
        assertTrue(client.textUrls.isEmpty())
    }

    /** LinuxTracker 搜索必须编码 query、避免嵌套表重复并映射 UTC 日期。 */
    @Test
    fun linuxSearchEncodesQueryAndAvoidsNestedDuplicates() = runBlocking {
        /** client 返回包含嵌套表的固定上游形状。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { readProviderFixture("provider/linuxtracker/search_normal.html") })
        /** items 必须只有外层结果行。 */
        val items = LinuxTrackerProvider(client).search("  linux /?\u0000  ", Category.APPS)
        assertEquals("https://linuxtracker.org/index.php?page=torrents&search=linux%20%2F%3F&category=0&active=0", client.textUrls.single())
        assertEquals(1, items.size)
        assertEquals(hash, items.single().infoHash)
        assertEquals(1704153600000L, items.single().uploadEpochMillis)
        assertEquals("https://linuxtracker.org/index.php?page=downloadcheck&id=1", items.single().fileDownloadLink)
        assertEquals("https://linuxtracker.org/index.php?page=torrent-details&id=1", items.single().descriptionPageUrl)
    }

    /** LinuxTracker 空/缺必填与恶意可选 URL 必须逐条安全处理。 */
    @Test
    fun linuxHandlesEmptyMissingAndUnsafeOptionalUrls() = runBlocking {
        assertTrue(LinuxTrackerProvider(FakeTorrentSearchNetworkClient(textHandler = { "<table class='lista' width='100%'><tbody></tbody></table>" })).search("x", Category.APPS).isEmpty())
        /** missingName 保留 magnet 但缺标题，应跳过条目。 */
        val missingName = readProviderFixture("provider/linuxtracker/search_normal.html").replace(" title=\"fixture\"", "")
        assertTrue(LinuxTrackerProvider(FakeTorrentSearchNetworkClient(textHandler = { missingName })).search("x", Category.APPS).isEmpty())
        /** unsafeLinks 把可选下载地址替换为异常端口，固定详情 anchor 仍负责选择外层行。 */
        val unsafeLinks = readProviderFixture("provider/linuxtracker/search_normal.html")
            .replace("index.php?page=downloadcheck&id=1", "https://linuxtracker.org:8443/file")
        /** item 只降级可选 URL。 */
        val item = LinuxTrackerProvider(FakeTorrentSearchNetworkClient(textHandler = { unsafeLinks })).search("x", Category.APPS).single()
        assertEquals("https://linuxtracker.org/index.php?page=torrent-details&id=1", item.descriptionPageUrl)
        assertNull(item.fileDownloadLink)
    }

    /** LinuxTracker 详情应解析字段，人数缺逗号或空值时保持详情。 */
    @Test
    fun linuxDetailsHandlesPeerBoundariesAndUntrustedDescription() = runBlocking {
        /** normal 是固定详情 fixture。 */
        val normal = readProviderFixture("provider/linuxtracker/details_normal.html")
        /** details 映射完整字段且原样保留不可信 HTML。 */
        val details = requireNotNull(LinuxTrackerProvider(FakeTorrentSearchNetworkClient(textHandler = { normal })).getDetails("https://linuxtracker.org/index.php?page=torrent-details&id=1"))
        assertEquals(7u, details.seeders)
        assertEquals(3u, details.peers)
        assertEquals(1704153600000L, details.uploadEpochMillis)
        assertTrue(requireNotNull(details.description).contains("<script>"))
        /** badStats 覆盖缺逗号和空字段，不能触发越界。 */
        for (badStats in listOf("seeds: 7", "")) {
            /** body 仅替换人数文本。 */
            val body = normal.replace("seeds: 7, leechers: 3", badStats)
            /** degraded 仍有名称与 magnet。 */
            val degraded = requireNotNull(LinuxTrackerProvider(FakeTorrentSearchNetworkClient(textHandler = { body })).getDetails("https://linuxtracker.org/item"))
            assertNull(degraded.seeders)
            assertNull(degraded.peers)
        }
    }

    /** LinuxTracker latest/top 必须逐次新请求，不共享结果或缓存。 */
    @Test
    fun linuxLatestAndTopAlwaysRequestIndependently() = runBlocking {
        /** client 返回合法空列表并记录每次调用。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { "<table class='lista' width='100%'><tbody></tbody></table>" })
        /** provider 连续执行最新和热门。 */
        val provider = LinuxTrackerProvider(client)
        provider.getLatestTorrents(Category.ALL)
        provider.getTopTorrents(Category.ALL)
        assertEquals(listOf("https://linuxtracker.org/index.php?page=torrents&search=&category=0&active=0", "https://linuxtracker.org/index.php?page=torrents&search=&category=0&active=0"), client.textUrls)
    }

    /** LinuxTracker 详情跨站、用户信息、明文和异常端口均在网络前拒绝。 */
    @Test
    fun linuxRejectsUnsafeDetailsBeforeNetwork() = runBlocking {
        /** client 的调用列表用于证明未接触网络。 */
        val client = FakeTorrentSearchNetworkClient()
        /** url 是当前不可信详情候选。 */
        for (url in listOf("https://evil.example/item", "https://user@linuxtracker.org/item", "http://linuxtracker.org/item", "https://linuxtracker.org:8443/item")) {
            try { LinuxTrackerProvider(client).getDetails(url); fail("非法详情地址必须拒绝") }
            /** failure 是无正文策略异常。 */ catch (failure: HttpDeniedException) { assertNull(failure.message) }
        }
        assertTrue(client.textUrls.isEmpty())
    }

    /** 三站描述符必须与本地安全审计和默认关闭策略一致。 */
    @Test
    fun metadataMatchesLocalAudit() {
        /** client 不执行任何网络。 */
        val client = FakeTorrentSearchNetworkClient()
        assertEquals(ProviderSafetyLevel.NSFW, KnabenProvider(client).descriptor.safetyLevel)
        assertEquals(ProviderSafetyLevel.UNSAFE, LimeTorrentsProvider(client).descriptor.safetyLevel)
        assertEquals(ProviderSafetyLevel.SAFE, LinuxTrackerProvider(client).descriptor.safetyLevel)
        assertFalse(KnabenProvider(client).descriptor.enabledByDefault)
        assertFalse(LimeTorrentsProvider(client).descriptor.enabledByDefault)
        assertFalse(LinuxTrackerProvider(client).descriptor.enabledByDefault)
        assertEquals(Category.entries.toSet() - Category.ALL, KnabenProvider(client).descriptor.supportedCategories)
    }
}
