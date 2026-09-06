package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.builtin.eztv.EztvProvider
import com.cla.clip.feature.torrent.search.provider.builtin.filemood.FileMoodProvider
import com.cla.clip.feature.torrent.search.provider.builtin.internetarchive.InternetArchiveProvider
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

/** 第五批固定上源的合成协议回归；所有响应为本地构造，无实际网络。 */
class FifthBatchProviderTest {
    /** 稳定合成 hash，不对应用户资源。 */
    private val hash = "00112233445566778899aabbccddeeff00112233"
    /** 合成 magnet 保留原字段以检查 Eztv 不重建链接。 */
    private val magnet = "magnet:?xt=urn:btih:$hash&dn=fixture"

    /** Eztv 必須丢弃前两行并保留链接和做种信息。 */
    @Test
    fun eztvSearchDropsTwoRowsAndKeepsMagnet() = runBlocking {
        /** 三行均含合法数据，仅最后一行应成为结果。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { eztvSearch() })
        /** 单条结果可以暴露漏掉 drop(2) 或错误选择器。 */
        val item = EztvProvider(client).search("fixture", Category.ALL).single()
        assertEquals("episode", item.name)
        assertEquals(hash, item.infoHash)
        assertEquals(magnet, item.magnetUri)
        assertEquals(7u, item.seeders)
        assertEquals(0u, item.peers)
        assertEquals(Category.SERIES, item.category)
        assertEquals("https://eztvx.to/item", item.descriptionPageUrl)
        assertEquals("https://eztvx.to/item.torrent", item.fileDownloadLink)
    }

    /** 查询作为一个路径片段，控制字符清洗后不产生新路径或参数。 */
    @Test
    fun eztvEncodesQueryAsOneSegment() = runBlocking {
        /** 无行 HTML 是上源合法零结果。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { "<table><tr><td>header</td></tr></table>" })
        assertTrue(EztvProvider(client).search(" \r中 /x?y#z& \n", Category.ALL).isEmpty())
        assertEquals(listOf("search", "中 /x?y#z&"), client.textUrls.single().toHttpUrl().pathSegments)
        assertNull(client.textUrls.single().toHttpUrl().query)
    }

    /** Eztv 缺名称或非法 magnet 只跳过单条。 */
    @Test
    fun eztvSkipsMissingRequiredFields() = runBlocking {
        /** 空名称仍保留上源结构，防止把坏字段误判结构失败。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { eztvSearch().replace("episode", "").replace(hash, "bad") })
        assertTrue(EztvProvider(client).search("fixture", Category.ALL).isEmpty())
    }

    /** 可选链接不能扩大 host 或降级 HTTP。 */
    @Test
    fun eztvDropsMaliciousOptionalLinks() = runBlocking {
        /** 仅替换可选 href，不影响必填 magnet。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = {
            eztvSearch().replace("href='/item'", "href='https://sub.eztvx.to/item'")
                .replace("href='/item.torrent'", "href='http://eztvx.to/item.torrent'")
        })
        /** 可选坏字段降级后合法结果仍保留。 */
        val item = EztvProvider(client).search("fixture", Category.ALL).single()
        assertNull(item.descriptionPageUrl)
        assertNull(item.fileDownloadLink)
    }

    /** 详情必须按标签的 TextNode 获取大小和序数日期。 */
    @Test
    fun eztvDetailsReadsTextNodesAndOrdinalDate() = runBlocking {
        /** 固定嵌套表格与上游 selector 一致。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { eztvDetails() })
        /** 对象为空会直接使回归失败。 */
        val item = requireNotNull(EztvProvider(client).getDetails("https://eztvx.to/item"))
        assertEquals("episode", item.name)
        assertEquals("2 GB", item.size)
        assertEquals(1704153600000L, item.uploadEpochMillis)
        assertEquals(magnet, item.magnetUri)
        assertEquals(11u, item.seeders)
        assertEquals(4u, item.peers)
        assertEquals("https://eztvx.to/poster.png", item.posterUrl)
        assertEquals("https://eztvx.to/item.torrent", item.fileDownloadLink)
        assertTrue(item.description.orEmpty().contains("<b>fixture</b>"))
    }

    /** 日期污染和可选恶意海报只能降级字段。 */
    @Test
    fun eztvDetailsDropsInvalidOptionals() = runBlocking {
        /** 无效日期不能通过宽松 parser 进入排序字段。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = {
            eztvDetails().replace("2nd Jan 2024", "99th Jan 2024")
                .replace("src='/poster.png'", "src='https://other.invalid/poster.png'")
        })
        /** 合法必填字段维持详情。 */
        val item = requireNotNull(EztvProvider(client).getDetails("https://eztvx.to/item"))
        assertNull(item.uploadEpochMillis)
        assertNull(item.posterUrl)
    }

    /** 无上源列表结构本来为空，详情无必填也返回 null。 */
    @Test
    fun eztvEmptyAndMalformedHtmlRemainEmpty() = runBlocking {
        /** 合成无匹配 selector 的合法 HTML。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { "<html><p>empty</p></html>" })
        assertTrue(EztvProvider(client).search("fixture", Category.ALL).isEmpty())
        assertNull(EztvProvider(client).getDetails("https://eztvx.to/item"))
    }

    /** 各站外部详情一律在网络前检查精确 HTTPS host。 */
    @Test
    fun allProvidersRejectForeignDetailsBeforeNetwork() = runBlocking {
        /** 记录所有潜在网络调用，恶意输入不应到达。 */
        val client = FakeTorrentSearchNetworkClient()
        /** 每站的主域与子域/明文对照。 */
        val providers = listOf(EztvProvider(client), FileMoodProvider(client), InternetArchiveProvider(client))
        /** provider 是当前待验证的读取入口。 */
        for (provider in providers) {
            /** url 覆盖跨域和明文输入。 */
            for (url in listOf("https://other.invalid/item", provider.descriptor.baseUrl.replace("https:", "http:") + "/item", provider.descriptor.baseUrl.replace("https://", "https://sub.") + "/item")) {
                try { provider.getDetails(url); fail("恶意详情必须在网络前拒绝") }
                /** failure 只检查低敏异常类型及空消息。 */
                catch (failure: HttpDeniedException) { assertNull(failure.message) }
            }
        }
        assertTrue(client.textUrls.isEmpty())
        assertTrue(client.jsonUrls.isEmpty())
    }

    /** FileMood 搜索保留标题条件、hash 和节点数。 */
    @Test
    fun fileMoodSearchKeepsTitleConstraintAndMetadata() = runBlocking {
        /** 合成结果按 td.dn-* 固定结构提供。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { fileMoodSearch("7/3") })
        /** 编码后的 q 不允许通过 & 注入其他参数。 */
        val item = FileMoodProvider(client).search(" \r中 &x=1\n ", Category.ALL).single()
        assertEquals(hash, item.infoHash)
        assertEquals("fixture", item.name)
        assertEquals(7u, item.seeders)
        assertEquals(3u, item.peers)
        assertEquals(Category.OTHER, item.category)
        assertEquals("中 &x=1 in:title", client.textUrls.single().toHttpUrl().queryParameter("q"))
        assertEquals(setOf("q"), client.textUrls.single().toHttpUrl().queryParameterNames)
    }

    /** 上游两元素解构会在一个字段时崩溃，安全下标必须覆盖零/一/多字段。 */
    @Test
    fun fileMoodHandlesNonstandardPeerFieldCounts() = runBlocking {
        /** 状态文本和独立推导期望，不能由被测 parser 生成。 */
        val cases = listOf("" to (null to null), "9" to (9u to null), "2/3/4" to (2u to 3u), "bad/8" to (null to 8u))
        /** status 是上游文本，expected 是独立手写的可选计数。 */
        for ((status, expected) in cases) {
            /** 每次只解析一个合成候选。 */
            val client = FakeTorrentSearchNetworkClient(textHandler = { fileMoodSearch(status) })
            /** 名称/hash 合法时节点数异常不能丢失结果。 */
            val item = FileMoodProvider(client).search("fixture", Category.OTHER).single()
            assertEquals(expected.first, item.seeders)
            assertEquals(expected.second, item.peers)
        }
    }

    /** hash 非法、详情缺失或跨站都不能产出可操作结果。 */
    @Test
    fun fileMoodSkipsBadHashAndUnsafeDetails() = runBlocking {
        /** fixture 覆盖三个必填错误，均不应让整个搜索异常。 */
        val fixtures = listOf(fileMoodSearch("1/2").replace(hash, "bad"), fileMoodSearch("1/2").replace("/fixture-", "https://other.invalid/fixture-"), fileMoodSearch("1/2").replace("fixture</td>", "</td>"))
        /** html 是当前合成坏候选。 */
        for (html in fixtures) {
            assertTrue(FileMoodProvider(FakeTorrentSearchNetworkClient(textHandler = { html })).search("fixture", Category.ALL).isEmpty())
        }
    }

    /** 详情固定 hash/tracker 与日期首空白契约。 */
    @Test
    fun fileMoodDetailsNormalizesHashAndDate() = runBlocking {
        /** 大写 hash 用于验证公共规范化。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { fileMoodDetails() })
        /** 详情字段按上源第三张 table 定位。 */
        val item = requireNotNull(FileMoodProvider(client).getDetails("https://filemood.com/item.html"))
        assertEquals(hash, item.infoHash)
        assertEquals("2 GB", item.size)
        assertEquals(1704153600000L, item.lastCheckedEpochMillis)
        assertTrue(item.magnetUri.startsWith("magnet:?xt=urn:btih:$hash&tr=udp%3A%2F%2Ftracker.opentrackr.org"))
        assertEquals(20, item.magnetUri.split("&tr=").size - 1)
    }

    /** 搜索无行与详情缺必填各有稳定空契约。 */
    @Test
    fun fileMoodEmptyAndMissingDetailsRemainEmpty() = runBlocking {
        /** 空表不要求新增站点哨兵。 */
        val provider = FileMoodProvider(FakeTorrentSearchNetworkClient(textHandler = { "<table><tbody></tbody></table>" }))
        assertTrue(provider.search("fixture", Category.ALL).isEmpty())
        assertNull(provider.getDetails("https://filemood.com/item.html"))
        assertNull(FileMoodProvider(FakeTorrentSearchNetworkClient(textHandler = { fileMoodDetails().replace(hash.uppercase(), "bad") })).getDetails("https://filemood.com/item.html"))
    }

    /** IA 搜索需保留固定参数，并按 Long 大小、hash 和分类映射。 */
    @Test
    fun archiveSearchEncodesParametersAndMapsMetadata() = runBlocking {
        /** response.docs 固定对象数组。 */
        val client = FakeTorrentSearchNetworkClient(jsonHandler = { archiveSearch() })
        /** q 包含注入样式字符，HttpUrl 必须将其保留为值。 */
        val item = InternetArchiveProvider(client).search(" \n中 &x=1 ", Category.BOOKS).single()
        assertEquals("fixture", item.name)
        assertEquals(hash, item.infoHash)
        assertEquals("1.00 GB", item.size)
        assertEquals(Category.BOOKS, item.category)
        assertEquals(1704153600000L, item.uploadEpochMillis)
        assertEquals("https://archive.org/details/fixture", item.descriptionPageUrl)
        /** URL 解码后应精确还原协议值。 */
        val url = client.jsonUrls.single().toHttpUrl()
        assertEquals("title:中 &x=1 AND mediatype:(texts)", url.queryParameter("q"))
        assertEquals("title,item_size,publicdate,mediatype,identifier,btih", url.queryParameter("fl[]"))
        assertEquals("100", url.queryParameter("rows"))
        assertEquals("1", url.queryParameter("page"))
        assertEquals("json", url.queryParameter("output"))
        assertEquals(5, url.queryParameterNames.size)
    }

    /** ALL 无过滤，其他分类按固定上源回退 other。 */
    @Test
    fun archiveCategoryQueriesFollowUpstreamMapping() = runBlocking {
        /** 每个期望 q 独立手写。 */
        val cases = listOf(Category.ALL to "title:fixture", Category.APPS to "title:fixture AND mediatype:(software)", Category.MOVIES to "title:fixture AND mediatype:(movies)", Category.ANIME to "title:fixture AND mediatype:(other)")
        /** category 是调用分类，query 是期望的固定上源表达式。 */
        for ((category, query) in cases) {
            /** 空 docs 为合法零结果。 */
            val client = FakeTorrentSearchNetworkClient(jsonHandler = { """{"response":{"docs":[]}}""" })
            assertTrue(InternetArchiveProvider(client).search("fixture", category).isEmpty())
            assertEquals(query, client.jsonUrls.single().toHttpUrl().queryParameter("q"))
        }
    }

    /** JSON 语法、顶层与 docs 类型错误不能伪装零结果。 */
    @Test
    fun archiveRejectsMalformedTopLevel() = runBlocking {
        /** body 覆盖独立结构错误。 */
        for (body in listOf("not-json", "[]", "{}", """{"response":{"docs":{}}}""")) {
            try { InternetArchiveProvider(FakeTorrentSearchNetworkClient(jsonHandler = { body })).search("fixture", Category.ALL); fail("结构异常必须分类为解析失败") }
            /** failure 禁止携带原始响应。 */
            catch (failure: ProviderParseException) { assertNull(failure.message) }
        }
    }

    /** 缺字段和非对象只跳过单条，后续有效候选仍可见。 */
    @Test
    fun archiveSkipsMalformedRowsWithoutLosingLaterValidRows() = runBlocking {
        /** 在正常数组前插入坏对象与非对象。 */
        val body = archiveSearch().replace("\"docs\":[", "\"docs\":[null,12,{},")
        assertEquals(1, InternetArchiveProvider(FakeTorrentSearchNetworkClient(jsonHandler = { body })).search("fixture", Category.ALL).size)
        /** required 是搜索协议必填字段名。 */
        for (required in listOf("title", "item_size", "mediatype", "identifier", "btih")) {
            /** 改名模拟缺字段，避免关联修改其他数据。 */
            val missing = archiveSearch().replace("\"$required\":", "\"absent\":")
            assertTrue(InternetArchiveProvider(FakeTorrentSearchNetworkClient(jsonHandler = { missing })).search("fixture", Category.ALL).isEmpty())
        }
    }

    /** identifier 不能改变 API 路由；编码形式也需在网络前拒绝。 */
    @Test
    fun archiveRejectsUnsafeDetailRoutes() = runBlocking {
        /** client 应一直没有元数据请求。 */
        val client = FakeTorrentSearchNetworkClient()
        /** path 覆盖其他 API、额外路径、空段、编码斜杠、query/fragment。 */
        for (path in listOf("/metadata/fixture", "/details/fixture/extra", "/details/", "/details/%2Fmetadata", "/details/%5Cbad", "/details/%00bad", "/details/fixture?q=x", "/details/fixture#x", "/details/../fixture", "/details/" + "a".repeat(513))) {
            try { InternetArchiveProvider(client).getDetails("https://archive.org$path"); fail("非法标识路径必须拒绝") }
            /** failure 表达路径策略拒绝，不携带地址。 */
            catch (failure: HttpDeniedException) { assertNull(failure.message) }
        }
        assertTrue(client.jsonUrls.isEmpty())
    }

    /** 搜索响应中的恶意 identifier 不能产出链接。 */
    @Test
    fun archiveSkipsUnsafeIdentifiers() = runBlocking {
        /** id 是合成外部路径候选。 */
        for (id in listOf("", ".", "..", "../metadata", "x/y", "x\\\\y", "x\\n", "a".repeat(513))) {
            /** 仅替换 identifier，不污染名称或 hash。 */
            val body = archiveSearch().replace("\"identifier\":\"fixture\"", "\"identifier\":\"$id\"")
            assertTrue(InternetArchiveProvider(FakeTorrentSearchNetworkClient(jsonHandler = { body })).search("fixture", Category.ALL).isEmpty())
        }
    }

    /** 分类必须读 mediatype，修复上游 metadata.metadata 笔误。 */
    @Test
    fun archiveDetailsUsesMediaTypeAndSkipsInvalidFirstTorrent() = runBlocking {
        /** 首个有 btih 的文件非法，后续合法条目必须继续被发现。 */
        val client = FakeTorrentSearchNetworkClient(jsonHandler = { archiveDetails() })
        /** 请求标识固定为 fixture，与响应匹配。 */
        val item = requireNotNull(InternetArchiveProvider(client).getDetails("https://archive.org/details/fixture"))
        assertEquals(Category.BOOKS, item.category)
        assertEquals(hash, item.infoHash)
        assertEquals(1704153600000L, item.uploadEpochMillis)
        assertEquals(1700000000000L, item.lastCheckedEpochMillis)
        assertEquals("https://archive.org/download/fixture/a%20%23%3F.torrent", item.fileDownloadLink)
        assertEquals("https://archive.org/metadata/fixture", client.jsonUrls.single())
        assertEquals("uploader", item.uploader)
        assertEquals("<b>fixture</b>", item.description)
    }

    /** 响应标识不匹配或文件必填坏值不应生成错误详情。 */
    @Test
    fun archiveDetailsRequiresMatchingIdentityAndValidFile() = runBlocking {
        /** body 是相互独立的必填错误。 */
        for (body in listOf(archiveDetails().replace("\"identifier\":\"fixture\"", "\"identifier\":\"other\""), archiveDetails().replace(hash.uppercase(), "bad"), archiveDetails().replace("a #?.torrent", ".."), archiveDetails().replace("\"title\":\"fixture\"", "\"title\":\"\""))) {
            assertNull(InternetArchiveProvider(FakeTorrentSearchNetworkClient(jsonHandler = { body })).getDetails("https://archive.org/details/fixture"))
        }
    }

    /** 可选时间溢出只降级，未知分类仍是 OTHER。 */
    @Test
    fun archiveDetailsHandlesOverflowAndUnknownCategory() = runBlocking {
        /** 超大秒值可被 Long 表示但转换毫秒必溢出。 */
        val body = archiveDetails().replace("1700000000", "9223372036854775807").replace("\"mediatype\":\"texts\"", "\"mediatype\":\"unknown\"")
        /** 合法必填字段保持详情。 */
        val item = requireNotNull(InternetArchiveProvider(FakeTorrentSearchNetworkClient(jsonHandler = { body })).getDetails("https://archive.org/details/fixture"))
        assertNull(item.lastCheckedEpochMillis)
        assertEquals(Category.OTHER, item.category)
        assertNull(requireNotNull(InternetArchiveProvider(FakeTorrentSearchNetworkClient(jsonHandler = { archiveDetails().replace("\"mediatype\":", "\"absent\":") })).getDetails("https://archive.org/details/fixture")).category)
    }

    /** 描述符直接影响安全模式和默认来源启用选择。 */
    @Test
    fun metadataMatchesLocalSafetyAudit() {
        /** 单例 fake 不执行网络。 */
        val client = FakeTorrentSearchNetworkClient()
        assertEquals("eztvx", EztvProvider(client).descriptor.id.value)
        assertTrue(EztvProvider(client).descriptor.enabledByDefault)
        assertTrue(EztvProvider(client).descriptor.isCloudflareProtected)
        assertEquals("https://eztvx.to/home", EztvProvider(client).descriptor.cloudflareSolverUrl)
        assertEquals(ProviderSafetyLevel.SAFE, FileMoodProvider(client).descriptor.safetyLevel)
        assertFalse(FileMoodProvider(client).descriptor.enabledByDefault)
        assertEquals(ProviderSafetyLevel.NSFW, InternetArchiveProvider(client).descriptor.safetyLevel)
        assertFalse(InternetArchiveProvider(client).descriptor.enabledByDefault)
    }

    /** 搜索固定样式 Cookie 显式提供，详情不继承它或用户登录态。 */
    @Test
    fun eztvOnlySearchUsesFixedLayoutCookie() = runBlocking {
        /** 回调只记录合成 header 对象，不写入日志。 */
        val headersSeen = mutableListOf<Map<String, String>>()
        /** 请求差异是 Provider 可观察契约，fake 不自行生成 header。 */
        val client = FakeTorrentSearchNetworkClient(
            textHandler = { headersSeen += emptyMap<String, String>(); eztvDetails() },
            /** headers 是 Provider 实际传入的固定样式配置。 */
            textWithHeadersHandler = { _, headers -> headersSeen += headers; eztvSearch() },
        )
        EztvProvider(client).search("fixture", Category.SERIES)
        EztvProvider(client).getDetails("https://eztvx.to/item")
        assertEquals(listOf(mapOf("Cookie" to "layout=def_wlinks"), emptyMap<String, String>()), headersSeen)
    }

    /** 大小值在两位展示边界处不应被 Float 舍入推进到下一单位。 */
    @Test
    fun archiveKeepsLongSizeBeforeFormatting() = runBlocking {
        /** 一 GiB 以下一字节，Float 会错误舍入成整 GiB。 */
        val body = archiveSearch().replace("1073741824", "1073741823")
        /** 正确 Long/Double 路径保持 MB 单位直到格式化结束。 */
        val item = InternetArchiveProvider(FakeTorrentSearchNetworkClient(jsonHandler = { body })).search("fixture", Category.ALL).single()
        assertEquals("1024.00 MB", item.size)
    }

    /** 搜索的错误标量类型不能通过 JSON primitive 强转进入领域字段。 */
    @Test
    fun archiveSkipsWrongScalarTypesAndInvalidHash() = runBlocking {
        /** body 覆盖名称类型、大小溢出、hash 格式和日期可选降级。 */
        for (body in listOf(archiveSearch().replace("\"title\":\"fixture\"", "\"title\":12"), archiveSearch().replace("1073741824", "9223372036854775808"), archiveSearch().replace(hash.uppercase(), "bad"))) {
            assertTrue(InternetArchiveProvider(FakeTorrentSearchNetworkClient(jsonHandler = { body })).search("fixture", Category.ALL).isEmpty())
        }
        /** 日期为可选字段，非法时不丢合法资源。 */
        val item = InternetArchiveProvider(FakeTorrentSearchNetworkClient(jsonHandler = { archiveSearch().replace("2024-01-02T00:00:00Z", "bad") })).search("fixture", Category.ALL).single()
        assertNull(item.uploadEpochMillis)
    }

    /** 详情顶层语法失败分类明确，缺 metadata/files 为无详情。 */
    @Test
    fun archiveDetailsDistinguishesMalformedTopLevelFromMissingFields() = runBlocking {
        /** body 是无可用详情但合法的对象。 */
        for (body in listOf("{}", "{\"metadata\":{}}", archiveDetails().replace("\"files\":", "\"absent\":"))) {
            assertNull(InternetArchiveProvider(FakeTorrentSearchNetworkClient(jsonHandler = { body })).getDetails("https://archive.org/details/fixture"))
        }
        try { InternetArchiveProvider(FakeTorrentSearchNetworkClient(jsonHandler = { "[]" })).getDetails("https://archive.org/details/fixture"); fail("详情非对象必须解析失败") }
        /** failure 为稳定协议异常，不包含正文。 */
        catch (failure: ProviderParseException) { assertNull(failure.message) }
    }

    /** hash 必须出自 .html 文件名，不能从 query/fragment 或无后缀路径伪造。 */
    @Test
    fun fileMoodRejectsHashOutsideHtmlFilename() = runBlocking {
        /** link 是独立构造的伪详情地址。 */
        for (link in listOf("/fixture-$hash", "/fixture.html?x=-$hash", "/fixture.html#-$hash", "/fixture-$hash.html?x=1", "/fixture-$hash.html#x")) {
            /** 仅替换 href，其他必填字段保持合法。 */
            val body = fileMoodSearch("1/2").replace("/fixture-$hash.html", link)
            assertTrue(FileMoodProvider(FakeTorrentSearchNetworkClient(textHandler = { body })).search("fixture", Category.ALL).isEmpty())
        }
    }

    /** 归一化前的额外路径与非默认端口均不可改变元数据请求来源。 */
    @Test
    fun archiveRejectsNormalizedExtraSegmentsAndAlternatePort() = runBlocking {
        /** client 记录所有违规输入可能触发的请求。 */
        val client = FakeTorrentSearchNetworkClient(jsonHandler = { archiveDetails() })
        /** url 覆盖会被 HttpUrl 静默规范化的路径，以及另一个服务端口。 */
        for (url in listOf("https://archive.org/details/x/../fixture", "https://archive.org/details/x/%2E%2E/fixture", "https://archive.org:8443/details/fixture")) {
            try { InternetArchiveProvider(client).getDetails(url); fail("非精确详情路径必须拒绝") }
            /** failure 只检查策略类型，不读取输入。 */
            catch (failure: HttpDeniedException) { assertNull(failure.message) }
        }
        assertTrue(client.jsonUrls.isEmpty())
    }

    /** Eztv 详情非法必填字段不应构造磁力身份或展示空标题。 */
    @Test
    fun eztvDetailsRequiresNonblankNameAndValidMagnet() = runBlocking {
        /** body 分别覆盖缺名称、非法 hash 与缺 magnet。 */
        for (body in listOf(eztvDetails().replace("<span>episode</span>", "<span></span>"), eztvDetails().replace(hash, "bad"), eztvDetails().replace("title='Magnet Link'", "title='absent'"))) {
            assertNull(EztvProvider(FakeTorrentSearchNetworkClient(textHandler = { body })).getDetails("https://eztvx.to/item"))
        }
    }

    /** IA 合法带保留字符标识需单段编码，并保持请求响应匹配。 */
    @Test
    fun archiveEncodesValidIdentifierAsSinglePathSegment() = runBlocking {
        /** 标识中的查询与片段字符只能是路径数据。 */
        val id = "fixture #?&"
        /** 同一标识返回搜索及详情，避免测试 fake 放宽身份匹配。 */
        val client = FakeTorrentSearchNetworkClient(jsonHandler = { archiveSearch().replace("\"identifier\":\"fixture\"", "\"identifier\":\"$id\"") })
        /** 手写预期编码保护 URL 构建，而非调用被测 helper 生成期望。 */
        val item = InternetArchiveProvider(client).search("fixture", Category.ALL).single()
        assertEquals("https://archive.org/details/fixture%20%23%3F&", item.descriptionPageUrl)
        /** 详情网络回调捕获真实 metadata 路径。 */
        val detailsClient = FakeTorrentSearchNetworkClient(jsonHandler = { archiveDetails().replace("\"identifier\":\"fixture\"", "\"identifier\":\"$id\"") })
        assertNotNull(InternetArchiveProvider(detailsClient).getDetails(requireNotNull(item.descriptionPageUrl)))
        assertEquals(listOf("metadata", id), detailsClient.jsonUrls.single().toHttpUrl().pathSegments)
        assertNull(detailsClient.jsonUrls.single().toHttpUrl().query)
    }

    /** 最后一个 table 的前三行均合法，明确证明必须 drop(2)。 */
    private fun eztvSearch(): String = "<table><tr><td>layout</td></tr></table><table>" +
        "<tr><td></td><td><a class='epinfo' href='/item'>episode</a></td><td><a class='magnet' href='$magnet'>m</a><a href='/item.torrent'>t</a></td><td>2 GB</td><td></td><td>7</td></tr>".repeat(3) + "</table>"

    /** 按固定上源嵌套层级构造标题、左列海报与第三列信息。 */
    private fun eztvDetails(): String = """
        <div id='header_holder'><table><tr><td><h1><span>episode</span></h1></td></tr><tr><td>
        <table class='episode_columns_holder'><tr>
        <td class='episode_left_column'><table><tr><td></td></tr><tr><td><a><img src='/poster.png'></a></td></tr></table></td>
        <td class='episode_middle_column'><table></table><table><tr><td></td></tr><tr><td><div><table><tr><td></td><td><span class='stat_red'>11</span><span class='stat_green'>4</span></td></tr></table></div></td></tr></table></td>
        <td><table><tr><td></td></tr><tr><td><table><tr><td><b>Filesize:</b> 2 GB <b>Released:</b> 2nd Jan 2024 </td></tr></table></td></tr></table></td>
        </tr></table></td></tr></table></div>
        <a title='Magnet Link' href='$magnet'>m</a><a title='Download Torrent' href='/item.torrent'>t</a><div class='desc_big'><b>fixture</b></div>
    """.trimIndent()

    /** status 是需验证的零/一/多字段状态文本。 */
    private fun fileMoodSearch(
        /** 合成做种和节点字段，用于验证非标准分隔数量。 */
        status: String,
    ): String = """
        <table><tr><td class='dn-title'>fixture</td><td class='dn-size'>2 GB</td><td class='dn-status'>$status</td><td class='dn-btn'><div><a class='btn-success' href='/fixture-$hash.html'>d</a></div></td></tr></table>
    """.trimIndent()

    /** 第三个 table 固定字段行与日期尾随时间，用于首空白回归。 */
    private fun fileMoodDetails(): String = """
        <div class='well'><table><tr><td><h1><b>fixture</b></h1></td></tr></table><div></div><table>
        <tr><td></td><td></td></tr><tr><td></td><td><p><b>2 GB</b></p></td></tr>
        <tr><td></td><td></td></tr><tr><td></td><td><p><b>2024-01-02 10:20:30</b></p></td></tr>
        <tr><td></td><td><p>${hash.uppercase()}</p></td></tr></table></div>
    """.trimIndent()

    /** 合成 IA 搜索结果只包含固定上源所读取字段。 */
    private fun archiveSearch(): String = """{"response":{"docs":[{"title":"fixture","item_size":1073741824,"publicdate":"2024-01-02T00:00:00Z","mediatype":"texts","identifier":"fixture","btih":"${hash.uppercase()}"}]}}"""

    /** 错误首文件与合法第二文件用于防止 first-with-key 提前结束。 */
    private fun archiveDetails(): String = """{"item_last_updated":1700000000,"item_size":1073741824,"metadata":{"title":"fixture","identifier":"fixture","publicdate":"2024-01-02 00:00:00","mediatype":"texts","metadata":"movies","uploader":"uploader","description":"<b>fixture</b>"},"files":[null,{"btih":"bad","name":"bad.torrent"},{"btih":"${hash.uppercase()}","name":"a #?.torrent"}]}"""
}
