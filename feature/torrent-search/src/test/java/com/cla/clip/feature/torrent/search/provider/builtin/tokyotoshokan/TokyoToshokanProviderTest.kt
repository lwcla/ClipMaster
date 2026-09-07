package com.cla.clip.feature.torrent.search.provider.builtin.tokyotoshokan

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

/** TokyoToshokan 双行配对、日期、分类、详情身份和 URL 边界测试。 */
class TokyoToshokanProviderTest {
    /** descriptor 必须采用 manifest 的 NSFW/default-off 结论并暴露四项能力。 */
    @Test
    fun metadataAndCapabilitiesMatchManifest() {
        /** provider 不联网即可检查稳定元数据。 */
        val provider: SearchProvider = TokyoToshokanProvider(FakeTorrentSearchNetworkClient())

        assertEquals("tokyotoshokan", provider.descriptor.id.value)
        assertEquals(ProviderSafetyLevel.NSFW, provider.descriptor.safetyLevel)
        assertFalse(provider.descriptor.enabledByDefault)
        assertEquals(
            setOf(Category.ANIME, Category.BOOKS, Category.MUSIC, Category.PORN, Category.OTHER),
            provider.descriptor.supportedCategories,
        )
        assertTrue(provider is TorrentDetailsProvider)
        assertTrue(provider is LatestTorrentsProvider)
        assertTrue(provider is TopTorrentsProvider)
    }

    /** search 必须结构化编码查询并完整解析两组不重叠标题/元数据行。 */
    @Test
    fun searchEncodesQueryAndParsesTwoNonOverlappingRecords() = runBlocking {
        /** client 返回含两个记录对的中性 fixture。 */
        val client = FakeTorrentSearchNetworkClient(
            textHandler = { readProviderFixture("provider/tokyotoshokan/search_normal.html") },
        )
        /** items 是固定 DOM 顺序的两项结果。 */
        val items = TokyoToshokanProvider(client).search("  fi\u0000xture /?  ", Category.ANIME)
        /** request 是结构化后的搜索地址。 */
        val request = client.textUrls.single().toHttpUrl()

        assertEquals("/search.php", request.encodedPath)
        assertEquals("fixture /?", request.queryParameter("terms"))
        assertEquals("1", request.queryParameter("type"))
        assertEquals("true", request.queryParameter("searchName"))
        assertEquals(listOf("Sample Alpha", "Sample Beta"), items.map { it.name })
        assertEquals(listOf(INFO_HASH, SECOND_HASH), items.map { it.infoHash })
        assertEquals(listOf("1.5 GB", "512 MB"), items.map { it.size })
        assertEquals(listOf(1709214300000L, 1709219700000L), items.map { it.uploadEpochMillis })
        assertEquals(listOf(Category.ANIME, Category.BOOKS), items.map { it.category })
        assertEquals(listOf(12u, 0u), items.map { it.seeders })
        assertEquals(listOf(3u, 1u), items.map { it.peers })
        assertEquals("https://tokyotosho.info/files/alpha.torrent", items.first().fileDownloadLink)
        assertEquals("https://tokyotosho.info/details.php?id=2", items.last().descriptionPageUrl)
    }

    /** 十类 search/latest 输入必须使用独立手写矩阵，未知浏览分类不得插入 cat。 */
    @Test
    fun allCategoryRequestsMatchReviewedMatrix() = runBlocking {
        /** searchIds 是每个领域分类的固定 search type。 */
        val searchIds = mapOf(
            Category.ALL to "0", Category.ANIME to "1", Category.APPS to "0",
            Category.BOOKS to "3", Category.GAMES to "0", Category.MOVIES to "0",
            Category.MUSIC to "2", Category.PORN to "15", Category.SERIES to "0",
            Category.OTHER to "5",
        )
        /** latestIds 只为 ALL 之外的受支持映射提供 cat。 */
        val latestIds = mapOf(
            Category.ALL to null, Category.ANIME to "1", Category.APPS to null,
            Category.BOOKS to "3", Category.GAMES to null, Category.MOVIES to null,
            Category.MUSIC to "2", Category.PORN to "15", Category.SERIES to null,
            Category.OTHER to "5",
        )
        /** client 用空页记录全部构造请求。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { EMPTY_HTML })
        /** provider 复用同一实例以观察每次独立 URL。 */
        val provider = TokyoToshokanProvider(client)

        /** category 是当前十类输入。 */
        for (category in Category.entries) {
            provider.search("x", category)
            /** searchUrl 是本轮搜索请求。 */
            val searchUrl = client.textUrls.last().toHttpUrl()
            assertEquals(searchIds.getValue(category), searchUrl.queryParameter("type"))
            provider.getLatestTorrents(category)
            /** latestUrl 是本轮最新请求。 */
            val latestUrl = client.textUrls.last().toHttpUrl()
            assertEquals("/index.php", latestUrl.encodedPath)
            assertEquals(latestIds.getValue(category), latestUrl.queryParameter("cat"))
        }
    }

    /** top 必须每次重新执行 latest 形状请求，不能共享缓存或结果。 */
    @Test
    fun topAlwaysMakesAnIndependentLatestShapedRequest() = runBlocking {
        /** client 记录两个独立热门调用。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { EMPTY_HTML })
        /** provider 是热门入口。 */
        val provider = TokyoToshokanProvider(client)

        provider.getTopTorrents(Category.BOOKS)
        provider.getTopTorrents(Category.BOOKS)

        assertEquals(2, client.textUrls.size)
        assertTrue(client.textUrls.all { it.toHttpUrl().queryParameter("cat") == "3" })
    }

    /** 奇数尾、广告与相邻标题必须按 desc-top 起始和紧邻 desc-bot 规则解析。 */
    @Test
    fun pairingHandlesOddTailAdvertisementsAndAdjacentTitles() = runBlocking {
        /** tail 是缺元数据的奇数尾标题。 */
        val tail = search(listing(titleRow("Tail", INFO_HASH))).single()
        assertNull(tail.size)
        assertNull(tail.uploadEpochMillis)
        /** advertised 在标题后插入广告和孤立元数据。 */
        val advertised = search(
            listing(
                titleRow("Advertised", INFO_HASH),
                "<tr class='advert'><td>Ad</td></tr>",
                metadataRow("Size: 9GB", "Date: 2024-02-29 13:45 UTC"),
            ),
        ).single()
        assertNull(advertised.size)
        /** adjacent 包含两个连续标题，只有第二个消费紧邻元数据。 */
        val adjacent = search(
            listing(
                titleRow("Alpha", INFO_HASH),
                titleRow("Beta", SECOND_HASH),
                metadataRow("Size: 2GB", "Date: 2024-02-29 13:45 UTC"),
            ),
        )
        assertEquals(listOf("Alpha", "Beta"), adjacent.map { it.name })
        assertNull(adjacent.first().size)
        assertEquals("2 GB", adjacent.last().size)
    }

    /** 元数据缺 Size 或 Date 时只降级对应字段，不能数组解构越界。 */
    @Test
    fun missingSingleMetadataFieldDegradesIndependently() = runBlocking {
        /** items 分别提供仅日期和仅大小。 */
        val items = search(
            listing(
                titleRow("Date only", INFO_HASH),
                metadataRow("Date: 2024-02-29 13:45 UTC"),
                titleRow("Size only", SECOND_HASH),
                metadataRow("Size: 4GB"),
            ),
        )

        assertNull(items.first().size)
        assertEquals(1709214300000L, items.first().uploadEpochMillis)
        assertEquals("4 GB", items.last().size)
        assertNull(items.last().uploadEpochMillis)
    }

    /** 空页、坏身份、伪 cat 以及恶意可选 URL 必须分别降级。 */
    @Test
    fun malformedRowsUseRequiredAndOptionalFailureContracts() = runBlocking {
        /** badPages 覆盖真实空、selector 漂移、缺名和坏 hash。 */
        val badPages = listOf(
            EMPTY_HTML,
            "<div class='changed-layout'>x</div>",
            listing(titleRow("", INFO_HASH)),
            listing(titleRow("Bad", "bad")),
        )
        /** html 是当前独立坏页面。 */
        for (html in badPages) {
            assertTrue(search(html).isEmpty())
        }
        /** exactCategory 不能从路径或无关 query 冒充 cat。 */
        val exactCategory = search(
            listing(titleRow("Alpha", INFO_HASH, categoryHref = "/cat/1?other=3")),
        ).single()
        assertNull(exactCategory.category)
        /** unsafeUrls 覆盖输出 URL 的 scheme、host、userinfo 和异常端口。 */
        val unsafeUrls = listOf(
            "https://evil.invalid/a",
            "http://tokyotosho.info/a",
            "https://user@tokyotosho.info/a",
            "https://tokyotosho.info:444/a",
        )
        /** unsafeUrl 是本轮同时污染文件与详情的候选。 */
        for (unsafeUrl in unsafeUrls) {
            /** item 仍保留合法名称和 magnet。 */
            val item = search(
                listing(
                    titleRow(
                        name = "Alpha",
                        infoHash = INFO_HASH,
                        fileHref = unsafeUrl,
                        detailsHref = unsafeUrl,
                    ),
                ),
            ).single()
            assertNull(item.fileDownloadLink)
            assertNull(item.descriptionPageUrl)
        }
    }

    /** 详情必须使用 24 小时和显式时区，且规范化 hash 与同站 torrent 文件。 */
    @Test
    fun detailsParseTwentyFourHourTimezoneAndNormalizedIdentity() = runBlocking {
        /** details 来自固定 28 项详情 fixture。 */
        val details = requireNotNull(details(readProviderFixture("provider/tokyotoshokan/details_normal.html")))

        assertEquals(INFO_HASH, details.infoHash)
        assertEquals(VALID_MAGNET, details.magnetUri)
        assertEquals(1709214300000L, details.uploadEpochMillis)
        assertEquals("1.5 GB", details.size)
        assertEquals(Category.ANIME, details.category)
        assertEquals("https://tokyotosho.info/files/alpha.torrent", details.fileDownloadLink)
        assertEquals("Sample Uploader", details.uploader)
    }

    /** 详情日期的 00:xx 小时必须按当天午夜后的合法时间解析。 */
    @Test
    fun detailsParseMidnightHourWithoutDroppingValidDetails() = runBlocking {
        /** normalHtml 是用于保持详情身份和可选字段完整的固定 fixture。 */
        val normalHtml = readProviderFixture("provider/tokyotoshokan/details_normal.html")
        /** midnightHtml 仅将 13:45 替换为 00:15，以覆盖 24 小时制的午夜边界。 */
        val midnightHtml = normalHtml.replace("2024-02-29 13:45 UTC", "2024-02-29 00:15 UTC")
        /** details 保留合法详情，且日期应独立落在当天 00:15 UTC。 */
        val details = requireNotNull(details(midnightHtml))

        assertEquals(INFO_HASH, details.infoHash)
        assertEquals(VALID_MAGNET, details.magnetUri)
        assertEquals(1709165700000L, details.uploadEpochMillis)
        assertEquals("1.5 GB", details.size)
        assertEquals(Category.ANIME, details.category)
    }

    /** 缺 magnet 必须生成，hash 冲突、缺名称或坏 hash 必须拒绝详情。 */
    @Test
    fun detailsEnforceIdentityAndMagnetConsistency() = runBlocking {
        /** normalHtml 是详情结构基线。 */
        val normalHtml = readProviderFixture("provider/tokyotoshokan/details_normal.html")
        /** withoutMagnet 删除唯一 magnet anchor。 */
        val withoutMagnet = normalHtml.replace("<a href=\"$VALID_MAGNET\">Magnet</a>", "")
        /** generated 是公共 builder 生成的可用 magnet。 */
        val generated = requireNotNull(details(withoutMagnet))
        assertTrue(generated.magnetUri.startsWith("$VALID_MAGNET&tr="))
        /** conflictingHtml 让页面 magnet 指向另一个合法 hash。 */
        val conflictingHtml = normalHtml.replace(VALID_MAGNET, "magnet:?xt=urn:btih:$SECOND_HASH")
        assertNull(details(conflictingHtml))
        assertNull(details(normalHtml.replace(">Sample Alpha</a>", "></a>")))
        assertNull(details(normalHtml.replace("0123456789ABCDEF0123456789ABCDEF01234567", "bad")))
    }

    /** 坏可选详情字段独立降级，非法详情 authority 在网络前拒绝。 */
    @Test
    fun detailsDropBadOptionalFieldsAndRejectUnsafeAuthority() = runBlocking {
        /** normalHtml 是详情结构基线。 */
        val normalHtml = readProviderFixture("provider/tokyotoshokan/details_normal.html")
        /** optionalHtml 同时破坏日期、人数、MIME 和文件 host。 */
        val optionalHtml = normalHtml
            .replace("2024-02-29 13:45 UTC", "2024-02-30 25:61 UTC")
            .replace("<li>12</li>", "<li>-1</li>")
            .replace("<li>3</li>", "<li>4294967296</li>")
            .replace("type=\"application/x-bittorrent\"", "type=\"text/html\"")
            .replace("/files/alpha.torrent", "https://evil.invalid/file")
        /** parsed 仍保留合法身份。 */
        val parsed = requireNotNull(details(optionalHtml))
        assertNull(parsed.uploadEpochMillis)
        assertNull(parsed.seeders)
        assertNull(parsed.peers)
        assertNull(parsed.fileDownloadLink)
        /** client 证明拒绝输入不会联网。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { normalHtml })
        /** provider 是详情校验入口。 */
        val provider = TokyoToshokanProvider(client)
        /** url 是当前恶意详情地址。 */
        for (url in UNSAFE_URLS) {
            /** failure 是无正文的安全拒绝。 */
            val failure = runCatching { provider.getDetails(url) }.exceptionOrNull()
            assertTrue(failure is HttpDeniedException)
        }
        assertTrue(client.textUrls.isEmpty())
        requireNotNull(provider.getDetails("https://tokyotosho.info:443/details.php?id=1"))
        assertEquals(1, client.textUrls.size)
    }

    /** 通过公开 Provider 搜索入口解析给定 HTML。 */
    private suspend fun search(
        /** 当前合成列表。 */
        html: String,
    ) = TokyoToshokanProvider(
        FakeTorrentSearchNetworkClient(textHandler = { html }),
    ).search("x", Category.ALL)

    /** 通过公开 Provider 详情入口解析给定 HTML。 */
    private suspend fun details(
        /** 当前合成详情页。 */
        html: String,
    ) = TokyoToshokanProvider(
        FakeTorrentSearchNetworkClient(textHandler = { html }),
    ).getDetails("https://tokyotosho.info/details.php?id=1")

    /** 构造含表头的 TokyoToshokan listing。 */
    private fun listing(
        /** 表头后的全部行。 */
        vararg rows: String,
    ): String = """
        <table class="listing"><tbody>
        <tr><th>Listing</th></tr>
        ${rows.joinToString(separator = "\n")}
        </tbody></table>
    """.trimIndent()

    /** 构造一个合法或按参数污染的标题行。 */
    private fun titleRow(
        /** 结果名称。 */
        name: String,
        /** magnet 中的 hash 候选。 */
        infoHash: String,
        /** 类别链接候选。 */
        categoryHref: String = "/?cat=1",
        /** 名称 anchor 同时承载的文件候选。 */
        fileHref: String = "/files/a.torrent",
        /** 可选详情地址。 */
        detailsHref: String = "/details.php?id=1",
    ): String = """
        <tr>
          <td><a href="$categoryHref">Category</a></td>
          <td class="desc-top"><a href="magnet:?xt=urn:btih:$infoHash">Magnet</a><a href="$fileHref">$name</a></td>
          <td class="web"><a href="$detailsHref">Details</a></td>
        </tr>
    """.trimIndent()

    /** 构造可缺少 Size 或 Date 的元数据行。 */
    private fun metadataRow(
        /** 分隔后的第一个字段。 */
        firstField: String,
        /** 分隔后的可选第二字段。 */
        secondField: String? = null,
    ): String {
        /** suffix 保持字段顺序但不制造空标签。 */
        val suffix = listOfNotNull(firstField, secondField).joinToString(separator = " | ")
        return """
            <tr>
              <td class="desc-bot">Metadata | $suffix</td>
              <td class="stats"><span>12</span><span>3</span></td>
            </tr>
        """.trimIndent()
    }

    /** 固定测试协议常量。 */
    private companion object {
        /** 中性 40 位 hash。 */
        private const val INFO_HASH = "0123456789abcdef0123456789abcdef01234567"
        /** 第二个中性 40 位 hash。 */
        private const val SECOND_HASH = "89abcdef0123456789abcdef0123456789abcdef"
        /** 中性合法 magnet。 */
        private const val VALID_MAGNET = "magnet:?xt=urn:btih:$INFO_HASH"
        /** TokyoToshokan 真实空列表形状。 */
        private const val EMPTY_HTML = "<table class='listing'><tbody><tr><th>Listing</th></tr></tbody></table>"
        /** 输入详情地址的全部 authority 风险。 */
        private val UNSAFE_URLS = listOf(
            "https://evil.invalid/a",
            "http://tokyotosho.info/a",
            "https://user@tokyotosho.info/a",
            "https://tokyotosho.info:444/a",
        )
    }
}
