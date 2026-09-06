package com.cla.clip.feature.torrent.search.provider.builtin.ext

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ext 两阶段 magnet、HMAC、URL、字段上限、详情描述与安全元数据契约测试。 */
class ExtProviderTest {
    /** 搜索应先解析只读脚本 token，再按固定字段计算 HMAC 并保留返回 magnet。 */
    @Test
    fun searchPostsExpectedHmacFieldsAndMapsNormalFixture() = runTest {
        /** 捕获表单字段，所有值都是固定合成数据。 */
        val submittedFields = mutableListOf<Map<String, String>>()
        /** 返回正常搜索页和合法 magnet JSON 的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            textHandler = { readProviderFixture("provider/ext/search_normal.html") },
            /** fields 用于断言请求协议，不写入运行时日志。 */
            formHandler = { _, fields ->
                submittedFields += fields
                """{"url":"magnet:?xt=urn:btih:00112233445566778899AABBCCDDEEFF00112233&dn=fixture"}"""
            },
        )
        /** 注入固定秒级时刻的 Provider。 */
        val provider = ExtProvider(networkClient, currentTimeMillis = { 1_700_000_000_000L })

        /** 唯一合法结果。 */
        val torrent = provider.search("A&B\n C\u0000", Category.MOVIES).single()

        assertEquals(
            listOf("https://ext.to/browse/?cat=1&q=A%26B%20C"),
            networkClient.textUrls,
        )
        assertEquals(listOf("https://ext.to/ajax/getSearchMagnet.php"), networkClient.formUrls)
        assertEquals(
            mapOf(
                "torrent_id" to "42",
                "hash" to "",
                "name" to "",
                "timestamp" to "1700000000",
                "hmac" to "7c3bc34cc0eb80ed4c158585554e24b176f5a057d616a86e1386ae1935457a9c",
                "sessid" to "session-fixture",
            ),
            submittedFields.single(),
        )
        assertEquals("00112233445566778899aabbccddeeff00112233", torrent.infoHash)
        assertEquals("Synthetic Ext", torrent.name)
        assertEquals("700 MB", torrent.size)
        assertEquals(12u, torrent.seeders)
        assertEquals(3u, torrent.peers)
        assertEquals(1_709_510_400_000L, torrent.uploadEpochMillis)
        assertEquals(Category.MOVIES, torrent.category)
        assertEquals("https://ext.to/torrent/synthetic-42", torrent.descriptionPageUrl)
        assertEquals(
            "magnet:?xt=urn:btih:00112233445566778899AABBCCDDEEFF00112233&dn=fixture",
            torrent.magnetUri,
        )
    }

    /** HMAC 纯函数必须匹配独立计算的固定 SHA-256 小写向量。 */
    @Test
    fun hmacMatchesIndependentFixedVector() {
        assertEquals(
            "7c3bc34cc0eb80ed4c158585554e24b176f5a057d616a86e1386ae1935457a9c",
            ExtHmacSha256.compute("42", 1_700_000_000L, "page-token"),
        )
    }

    /** descriptor 必须采用本地 NSFW 审计，并固定 Cloudflare、分类与默认关闭元数据。 */
    @Test
    fun descriptorUsesReviewedNsfwAndCloudflareMetadata() {
        /** 无需发起请求的 Provider。 */
        val descriptor = ExtProvider(FakeTorrentSearchNetworkClient()).descriptor

        assertEquals("extdotto", descriptor.id.value)
        assertEquals("https://ext.to", descriptor.baseUrl)
        assertEquals("https://ext.to/browse/?q=ubuntu", descriptor.cloudflareSolverUrl)
        assertEquals(ProviderSafetyLevel.NSFW, descriptor.safetyLevel)
        assertFalse(descriptor.enabledByDefault)
        assertTrue(descriptor.isCloudflareProtected)
        assertEquals(
            setOf(
                Category.ANIME,
                Category.APPS,
                Category.BOOKS,
                Category.GAMES,
                Category.MOVIES,
                Category.MUSIC,
                Category.OTHER,
                Category.PORN,
                Category.SERIES,
            ),
            descriptor.supportedCategories,
        )
    }

    /** search 的 ALL 与九类 cat 必须保持 cat 在 q 前并由 HttpUrl 编码。 */
    @Test
    fun searchBuildsCategoryUrlsInFixedOrder() = runTest {
        /** 合法空表无需 token，也不会触发表单请求。 */
        val emptyHtml = "<table class=\"search-table\"><tbody></tbody></table>"
        /** 记录搜索 URL 的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(textHandler = { emptyHtml })
        /** 待测 Provider。 */
        val provider = ExtProvider(networkClient)
        /** 分类与固定站点 ID。 */
        val categoryIds = linkedMapOf(
            Category.ALL to null,
            Category.ANIME to 7,
            Category.APPS to 5,
            Category.BOOKS to 6,
            Category.GAMES to 4,
            Category.MOVIES to 1,
            Category.MUSIC to 3,
            Category.OTHER to 8,
            Category.PORN to 10,
            Category.SERIES to 2,
        )

        /** category 是当前待构造 URL 的分类。 */
        categoryIds.keys.forEach { category -> provider.search("x y", category) }

        /** categoryId 是当前 URL 的可选 cat。 */
        val expectedUrls = categoryIds.values.map { categoryId ->
            if (categoryId == null) {
                "https://ext.to/browse/?q=x%20y"
            } else {
                "https://ext.to/browse/?cat=$categoryId&q=x%20y"
            }
        }
        assertEquals(expectedUrls, networkClient.textUrls)
    }

    /** latest/top 固定排序参数，非 ALL 只在末尾附加 cat。 */
    @Test
    fun latestAndTopBuildFixedUrls() = runTest {
        /** 合法空表响应。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            textHandler = { "<table class=\"search-table\"><tbody></tbody></table>" },
        )
        /** 待测 Provider。 */
        val provider = ExtProvider(networkClient)

        assertTrue(provider.getLatestTorrents(Category.GAMES).isEmpty())
        assertTrue(provider.getTopTorrents(Category.ALL).isEmpty())
        assertEquals(
            listOf(
                "https://ext.to/browse/?sort=age&order=desc&age=4&cat=4",
                "https://ext.to/browse/?sort=seeds&order=desc&age=4",
            ),
            networkClient.textUrls,
        )
    }

    /** 固定结果表存在时允许空；缺表属于顶层协议异常且不回显正文。 */
    @Test
    fun searchDistinguishesEmptyTableFromMalformedTopLevel() = runTest {
        /** 合法空结果 Provider。 */
        val emptyProvider = ExtProvider(
            FakeTorrentSearchNetworkClient(textHandler = { "<table class=\"search-table\"><tbody></tbody></table>" }),
        )
        assertTrue(emptyProvider.search("fixture", Category.ALL).isEmpty())

        /** 缺固定结果表的 Provider。 */
        val malformedProvider = ExtProvider(
            FakeTorrentSearchNetworkClient(textHandler = { "<html><body>unexpected</body></html>" }),
        )
        /** 顶层异常不得携带 HTML。 */
        val failure = runCatching { malformedProvider.search("fixture", Category.ALL) }.exceptionOrNull()
        assertTrue(failure is ProviderParseException)
        assertNull(failure?.message)
    }

    /** token/session 缺失或超限时合法行不能放大请求体，也不得调用 magnet 端点。 */
    @Test
    fun searchSkipsRowsWhenTokensAreMissingOrTooLong() = runTest {
        /** 单条结构合法的固定结果行。 */
        val row = extSearchRow("42", "Valid")
        /** 覆盖缺 token、page token 超限和 session 超限。 */
        val pages = listOf(
            "<table class=\"search-table\"><tbody>$row</tbody></table>",
            "<meta name=\"csrf-token\" content=\"session\"><script>window.searchPageToken = '${"p".repeat(1025)}';</script><table class=\"search-table\"><tbody>$row</tbody></table>",
            "<meta name=\"csrf-token\" content=\"${"s".repeat(1025)}\"><script>window.searchPageToken = 'page';</script><table class=\"search-table\"><tbody>$row</tbody></table>",
        )

        /** html 是当前缺失或超限 token 页面。 */
        pages.forEach { html ->
            /** 表单回调不应执行。 */
            val networkClient = FakeTorrentSearchNetworkClient(
                /** 当前 token 边界页面由文本回调直接返回。 */
                textHandler = { html },
                /** 任意表单调用都说明超限字段未被拦截。 */
                formHandler = { _, _ -> error("不应提交超限字段") },
            )
            assertTrue(ExtProvider(networkClient).search("fixture", Category.ALL).isEmpty())
            assertTrue(networkClient.formUrls.isEmpty())
        }
    }

    /** 单条缺字段、ID 超限、跨 host 详情或非法 magnet JSON 必须跳过而不污染其他行。 */
    @Test
    fun searchSkipsInvalidRowsAndMagnetResponses() = runTest {
        /** 固定 token 包裹四种坏行。 */
        val html = """
            <meta name="csrf-token" content="session"><script>window.searchPageToken = 'page';</script>
            <table class="search-table"><tbody>
              ${extSearchRow("", "missing-id")}
              ${extSearchRow("${"i".repeat(257)}", "long-id")}
              ${extSearchRow("43", "cross-host", "https://evil.invalid/torrent/43")}
              ${extSearchRow("44", "bad-magnet")}
            </tbody></table>
        """.trimIndent()
        /** 仅会为最后一条结构合法 draft 请求表单，但响应 url 非 magnet。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** 混合坏行页面由文本回调直接返回。 */
            textHandler = { html },
            /** 唯一可请求行返回非 magnet 地址。 */
            formHandler = { _, _ -> """{"url":"https://ext.to/not-a-magnet"}""" },
        )

        assertTrue(ExtProvider(networkClient).search("fixture", Category.ALL).isEmpty())
        assertEquals(1, networkClient.formUrls.size)
    }

    /** 两阶段表单必须按 DOM 顺序执行，取消应原样传播且不得请求后续行。 */
    @Test
    fun searchPreservesOrderAndStopsAfterCancellation() = runTest {
        /** 三条合法 draft 的合成页面。 */
        val html = """
            <meta name="csrf-token" content="session"><script>window.searchPageToken = 'page';</script>
            <table class="search-table"><tbody>${extSearchRow("1", "One")}${extSearchRow("2", "Two")}${extSearchRow("3", "Three")}</tbody></table>
        """.trimIndent()
        /** 实际尝试提交的 torrent_id 顺序。 */
        val submittedIds = mutableListOf<String>()
        /** 第二条抛取消，第三条不应执行。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** 三条有序 draft 页面由文本回调直接返回。 */
            textHandler = { html },
            /** fields 是当前顺序提交的合成表单。 */
            formHandler = { _, fields ->
                /** 当前表单必有受限 torrent_id。 */
                val torrentId = checkNotNull(fields["torrent_id"])
                submittedIds += torrentId
                if (torrentId == "2") throw CancellationException()
                """{"url":"magnet:?xt=urn:btih:00112233445566778899aabbccddeeff00112233"}"""
            },
        )

        /** 取消必须向调用方传播。 */
        val failure = runCatching { ExtProvider(networkClient).search("fixture", Category.ALL) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(listOf("1", "2"), submittedIds)
    }

    /** 详情 magnet 按钮位于 card 外但仍在 document 时必须沿用固定上游全局选择语义。 */
    @Test
    fun detailsFindsMagnetButtonOutsideDetailsCard() = runTest {
        /** 固定 fixture 中待移动的唯一 magnet 按钮。 */
        val magnetButton = "<a class=\"detail-magnet-link download-btn-magnet\" data-id=\"42\">Magnet</a>"
        /** 删除 card 内按钮并将同一按钮移到 body 末尾。 */
        val movedButtonHtml = readProviderFixture("provider/ext/details_normal.html")
            .replace(magnetButton, "")
            .replace("</body>", "$magnetButton</body>")
        /** 捕获详情表单提交的受限 torrent_id。 */
        val submittedIds = mutableListOf<String>()
        /** 返回移动按钮页面和固定合法 magnet 的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** 文本回调只返回当前合成详情页面。 */
            textHandler = { movedButtonHtml },
            /** fields 是当前详情 magnet 表单，仅提取合成 ID 做断言。 */
            formHandler = { _, fields ->
                /** torrentId 是生产代码提交的受限页面 ID。 */
                val torrentId = checkNotNull(fields["torrent_id"])
                submittedIds += torrentId
                """{"url":"magnet:?xt=urn:btih:00112233445566778899aabbccddeeff00112233"}"""
            },
            /** 非影视描述保持合法空对象，避免测试依赖描述字段。 */
            jsonWithHeadersHandler = { _, _ -> "{}" },
        )

        /** card 外按钮仍应产出完整详情。 */
        val details = checkNotNull(ExtProvider(networkClient).getDetails("https://ext.to/torrent/42"))

        assertEquals("00112233445566778899aabbccddeeff00112233", details.infoHash)
        assertEquals(listOf("42"), submittedIds)
    }

    /** 详情地址必须先做 HTTPS 精确 host 校验，恶意输入不能联网。 */
    @Test
    fun detailsRejectsUnsafeUrlsBeforeNetwork() = runTest {
        /** 网络回调若执行即表示详情白名单失效。 */
        val networkClient = FakeTorrentSearchNetworkClient(textHandler = { error("不应请求非法详情地址") })
        /** 待测 Provider。 */
        val provider = ExtProvider(networkClient)
        /** 明文和跨 host 输入。 */
        val invalidUrls = listOf("http://ext.to/torrent/42", "https://evil.invalid/torrent/42")

        /** url 是当前恶意详情地址。 */
        invalidUrls.forEach { url ->
            assertTrue(runCatching { provider.getDetails(url) }.exceptionOrNull() is HttpDeniedException)
        }
        assertTrue(networkClient.textUrls.isEmpty())
    }

    /** 非影视详情必须编码描述参数、发送固定 header，并映射描述、海报和相对日期。 */
    @Test
    fun detailsFetchesNonMediaDescriptionWithFixedHeader() = runTest {
        /** 捕获带 header 的 JSON 请求。 */
        val jsonCalls = mutableListOf<Pair<String, Map<String, String>>>()
        /** 返回正常详情、合法 magnet 与不可信描述字符串。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** 正常详情 fixture 由文本回调读取。 */
            textHandler = { readProviderFixture("provider/ext/details_normal.html") },
            /** 详情表单返回固定合法 magnet。 */
            formHandler = { _, _ -> """{"url":"magnet:?xt=urn:btih:00112233445566778899aabbccddeeff00112233"}""" },
            /** headers 是生产固定 AJAX header，URL 只含合成 ID/slug。 */
            jsonWithHeadersHandler = { url, headers ->
                jsonCalls += url to headers
                """{"data":"<p>Untrusted description</p>"}"""
            },
        )
        /** 注入相对日期基准时间。 */
        val provider = ExtProvider(networkClient, currentTimeMillis = { 1_700_000_000_000L })

        /** 完整非影视详情。 */
        val details = checkNotNull(provider.getDetails("https://ext.to/torrent/synthetic-42"))

        assertEquals("00112233445566778899aabbccddeeff00112233", details.infoHash)
        assertEquals("Synthetic Ext Detail", details.name)
        assertEquals("1 GB", details.size)
        assertEquals(Category.OTHER, details.category)
        assertEquals(1_709_510_400_000L, details.uploadEpochMillis)
        assertEquals(1_700_000_000_000L - 2L * 60L * 60L * 1000L, details.lastCheckedEpochMillis)
        assertEquals("<p>Untrusted description</p>", details.description)
        assertEquals("https://ext.to/images/poster.jpg", details.posterUrl)
        assertEquals(
            listOf(
                "https://ext.to/ajax/torrentDescription.php?id=42&code=slug%20value" to
                    mapOf("X-Requested-With" to "XMLHttpRequest"),
            ),
            jsonCalls,
        )
    }

    /** movies/series 直接采用 DOM plot，不能额外请求描述 JSON。 */
    @Test
    fun detailsUsesDomPlotForMoviesAndSeries() = runTest {
        /** 基础详情 fixture 包含两个 plot 分支。 */
        val baseHtml = readProviderFixture("provider/ext/details_normal.html")
        /** 分类 path 与对应预期 plot 文本。 */
        val cases = listOf("movies" to "Movie plot", "tv" to "Series plot")

        /** entry 是当前影视分类与预期 DOM 描述。 */
        cases.forEach { (categoryPath, expectedDescription) ->
            /** 将唯一 Other 分类链接替换为当前影视类别。 */
            val html = baseHtml.replace("/other/", "/$categoryPath/")
            /** JSON handler 不允许执行。 */
            val networkClient = FakeTorrentSearchNetworkClient(
                /** 当前影视分类详情页由文本回调返回。 */
                textHandler = { html },
                /** 影视详情仍需固定合法 magnet。 */
                formHandler = { _, _ -> """{"url":"magnet:?xt=urn:btih:00112233445566778899aabbccddeeff00112233"}""" },
                /** 任意 JSON 描述请求都表示影视分支越界。 */
                jsonWithHeadersHandler = { _, _ -> error("影视详情不应请求描述接口") },
            )

            /** 当前影视详情。 */
            val details = checkNotNull(ExtProvider(networkClient).getDetails("https://ext.to/torrent/42"))
            assertEquals(expectedDescription, details.description)
        }
    }

    /** 缺顶层详情卡抛解析异常；ID/token/session/slug 超限按缺字段且不得放大后续请求。 */
    @Test
    fun detailsEnforcesTopLevelAndFieldLengthBoundaries() = runTest {
        /** 缺详情卡的 Provider。 */
        val malformedProvider = ExtProvider(
            FakeTorrentSearchNetworkClient(textHandler = { "<html><body>unexpected</body></html>" }),
        )
        /** 顶层失败不得携带 HTML。 */
        val failure = runCatching { malformedProvider.getDetails("https://ext.to/torrent/42") }.exceptionOrNull()
        assertTrue(failure is ProviderParseException)
        assertNull(failure?.message)

        /** 将合法 ID 替换为超限值，确保不会提交表单。 */
        val oversizedHtml = readProviderFixture("provider/ext/details_normal.html")
            .replace("data-id=\"42\"", "data-id=\"${"i".repeat(257)}\"")
        /** 表单回调执行即表示长度限制失效。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** 超限 ID 详情页由文本回调直接返回。 */
            textHandler = { oversizedHtml },
            /** 任意详情表单调用都表示长度边界失效。 */
            formHandler = { _, _ -> error("不应提交超限详情字段") },
        )
        assertNull(ExtProvider(networkClient).getDetails("https://ext.to/torrent/42"))
        assertTrue(networkClient.formUrls.isEmpty())
    }

    /** 海报只允许 ext.to 精确 HTTPS，默认占位图与跨 host 图片均返回 null。 */
    @Test
    fun detailsFiltersPlaceholderAndCrossHostPosterUrls() = runTest {
        /** 正常详情 fixture 用于替换海报源。 */
        val baseHtml = readProviderFixture("provider/ext/details_normal.html")
        /** 两类不允许进入模型的海报地址。 */
        val posterCandidates = listOf("/images/no-torrent-image.png", "https://evil.invalid/poster.jpg")

        /** posterCandidate 是当前待过滤地址。 */
        posterCandidates.forEach { posterCandidate ->
            /** 替换唯一正常海报地址。 */
            val html = baseHtml.replace("/images/poster.jpg", posterCandidate)
            /** 返回合法 magnet 且描述可为空的网络 fake。 */
            val networkClient = FakeTorrentSearchNetworkClient(
                /** 当前海报候选详情页由文本回调返回。 */
                textHandler = { html },
                /** 海报过滤不影响固定合法 magnet。 */
                formHandler = { _, _ -> """{"url":"magnet:?xt=urn:btih:00112233445566778899aabbccddeeff00112233"}""" },
                /** 可选描述返回合法空对象。 */
                jsonWithHeadersHandler = { _, _ -> "{}" },
            )
            /** 当前详情仍应成功，但海报必须被清空。 */
            val details = checkNotNull(ExtProvider(networkClient).getDetails("https://ext.to/torrent/42"))
            assertNull(details.posterUrl)
        }
    }
}

/** 构造与上游固定选择器一致的单条最小结果 DOM。 */
private fun extSearchRow(
    /** torrentId 用于测试必填和 1..256 长度边界。 */
    torrentId: String,
    /** 可见名称用于验证 DOM 顺序。 */
    name: String,
    /** 详情地址默认使用同站相对链接，也可注入跨 host 恶意地址。 */
    detailsUrl: String = "/torrent/$torrentId",
): String = """
    <tr>
      <td><div><a class="torrent-title-link" href="$detailsUrl">$name</a><div class="related-posted"><a>in</a><a href="/other/">Other</a></div></div><div><a class="search-magnet-btn" data-id="$torrentId">M</a></div></td>
      <td><div><span>Size</span><span>1 GB</span></div></td><td></td><td><div><span>Date</span><span title="04 March 2024">Date</span></div></td>
      <td><div><span>Seeds</span><span>1</span></div></td><td><div><span>Peers</span><span>2</span></div></td>
    </tr>
""".trimIndent()
