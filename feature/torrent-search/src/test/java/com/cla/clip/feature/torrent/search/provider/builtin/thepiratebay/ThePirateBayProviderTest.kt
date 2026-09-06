package com.cla.clip.feature.torrent.search.provider.builtin.thepiratebay

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

/** ThePirateBay 分类路由、严格 token、JSON 容错、详情身份与取消测试。 */
class ThePirateBayProviderTest {
    /** descriptor 必须保留 Unsafe、默认关闭、完整八类和上源备用页面 host。 */
    @Test
    fun descriptorMatchesReviewedMetadata() {
        /** 不联网的待测 Provider。 */
        val provider = ThePirateBayProvider(FakeTorrentSearchNetworkClient())
        assertEquals("thepiratebay", provider.descriptor.id.value)
        assertEquals(ProviderSafetyLevel.UNSAFE, provider.descriptor.safetyLevel)
        assertFalse(provider.descriptor.enabledByDefault)
        assertEquals(setOf(Category.APPS, Category.BOOKS, Category.GAMES, Category.MOVIES, Category.MUSIC, Category.PORN, Category.SERIES, Category.OTHER), provider.descriptor.supportedCategories)
        assertEquals(listOf("knaben.xyz"), provider.alternateUrlDomains)
    }

    /** search、latest、top 必须固定 API host，并完整保留上游类别路由。 */
    @Test
    fun buildsEncodedRequestsForEveryCategory() = runTest {
        /** API 合法空数组。 */
        val network = FakeTorrentSearchNetworkClient(jsonHandler = { "[]" })
        /** 待测 Provider。 */
        val provider = ThePirateBayProvider(network)
        provider.search("A&B\n C", Category.BOOKS)
        provider.getLatestTorrents(Category.ALL)
        provider.getLatestTorrents(Category.SERIES)
        provider.getTopTorrents(Category.ALL)
        provider.getTopTorrents(Category.PORN)
        assertEquals("A&B C", network.jsonUrls[0].toHttpUrl().queryParameter("q"))
        assertEquals("601", network.jsonUrls[0].toHttpUrl().queryParameter("cat"))
        assertEquals("https://apibay.org/precompiled/data_top100_recent.json", network.jsonUrls[1])
        assertEquals("category:200", network.jsonUrls[2].toHttpUrl().queryParameter("q"))
        assertEquals("https://apibay.org/precompiled/data_top100_48h.json", network.jsonUrls[3])
        assertEquals("https://apibay.org/precompiled/data_top100_48h_500.json", network.jsonUrls[4])
        /** 全十类的上游请求 fallback 必须稳定。 */
        val expected = mapOf(Category.ALL to 0, Category.ANIME to 0, Category.APPS to 300, Category.BOOKS to 601, Category.GAMES to 400, Category.MOVIES to 200, Category.MUSIC to 101, Category.PORN to 500, Category.SERIES to 200, Category.OTHER to 600)
        /** 当前分类与预期 API cat。 */
        expected.forEach { (category, id) -> provider.search("fixture", category)
        assertEquals(id.toString(), network.jsonUrls.last().toHttpUrl().queryParameter("cat")) }
    }

    /** 坏条目不能截断后续合法项，分类映射覆盖所有边界且数字不截断。 */
    @Test
    fun mapsCategoriesAndKeepsValidItemAfterBadEntries() = runTest {
        /** 混合 fixture 包含 sentinel、非对象、坏数值和完整类别边界。 */
        val results = ThePirateBayProvider(FakeTorrentSearchNetworkClient(jsonHandler = { readProviderFixture("provider/thepiratebay/search_mixed.json") })).search("fixture", Category.ALL)
        assertEquals(listOf(Category.APPS, Category.BOOKS, Category.GAMES, Category.MOVIES, Category.MUSIC, Category.PORN, Category.SERIES, Category.OTHER), results.map { it.category })
        assertTrue(results.all { it.descriptionPageUrl?.startsWith("https://thepiratebay.org/description.php?id=") == true })
    }

    /** 搜索结果的 Long 大小、UInt 边界和 epoch 溢出必须逐字段安全映射。 */
    @Test
    fun mapsSearchLongSizeAndDegradesNumericBoundaries() = runTest {
        /** boundaryJson 包含 UInt 最大值、最大值加一、Long 大小、epoch 溢出及负数/非法文本。 */
        val boundaryJson = """
            [
              {"name":"Synthetic Boundary","id":"1","info_hash":"00112233445566778899aabbccddeeff00112233","size":"9007199254740993","seeders":"4294967295","leechers":"4294967296","added":"9223372036854775807","category":"201"},
              {"name":"Synthetic Invalid","id":"2","info_hash":"112233445566778899aabbccddeeff0011223344","size":"invalid","seeders":"-1","leechers":"invalid","added":"invalid","category":"201"}
            ]
        """.trimIndent()
        /** results 通过公开 search 入口映射两个合法身份候选。 */
        val results = ThePirateBayProvider(
            FakeTorrentSearchNetworkClient(jsonHandler = { boundaryJson }),
        ).search("fixture", Category.MOVIES)
        /** boundary 是保留 Long 大小且人数/时间按范围降级的首项。 */
        val boundary = results[0]
        assertEquals("8.00 PB", boundary.size)
        assertEquals(UInt.MAX_VALUE, boundary.seeders)
        assertNull(boundary.peers)
        assertNull(boundary.uploadEpochMillis)
        /** invalid 是必填身份合法、全部可选数字非法的次项。 */
        val invalid = results[1]
        assertNull(invalid.size)
        assertNull(invalid.seeders)
        assertNull(invalid.peers)
        assertNull(invalid.uploadEpochMillis)
    }

    /** 顶层非数组稳定失败，合法 sentinel 与空数组为空。 */
    @Test
    fun enforcesStrictTopLevelArray() = runTest {
        /** 合法空与上游 sentinel 都不得失败。 */
        listOf("[]", "[{\"name\":\"No results returned\"}]").forEach { json -> assertTrue(ThePirateBayProvider(FakeTorrentSearchNetworkClient(jsonHandler = { json })).search("fixture", Category.ALL).isEmpty()) }
        /** 非数组和坏 JSON 均为低敏解析失败。 */
        listOf("{}", "{").forEach { json -> assertTrue(runCatching { ThePirateBayProvider(FakeTorrentSearchNetworkClient(jsonHandler = { json })).search("fixture", Category.ALL) }.exceptionOrNull() is ProviderParseException) }
    }

    /** details token 只接受精确页面、唯一数字 id、默认 443 且无 userinfo/query 污染。 */
    @Test
    fun rejectsMalformedDetailsTokensBeforeNetwork() = runTest {
        /** 返回缺必填详情，合法 token 可验证 API 请求身份。 */
        val network = FakeTorrentSearchNetworkClient(jsonHandler = { "{}" })
        /** 待测 Provider。 */
        val provider = ThePirateBayProvider(network)
        assertNull(provider.getDetails("https://thepiratebay.org/description.php?id=42"))
        assertEquals("https://apibay.org/t.php?id=42", network.jsonUrls.single())
        /** 每个恶意 token 都不能进入网络。 */
        val invalid = listOf("http://thepiratebay.org/description.php?id=42", "https://user@thepiratebay.org/description.php?id=42", "https://thepiratebay.org:444/description.php?id=42", "https://sub.thepiratebay.org/description.php?id=42", "https://knaben.xyz/thepiratebay/description.php?id=42", "https://thepiratebay.org/other?id=42", "https://thepiratebay.org/description.php?id=-1", "https://thepiratebay.org/description.php?id=999999999999999999999", "https://thepiratebay.org/description.php?id=42&id=43", "https://thepiratebay.org/description.php?id=42&x=1", "https://thepiratebay.org/description.php?id=42#x")
        /** url 是当前恶意 token。 */
        invalid.forEach { url -> assertTrue(runCatching { provider.getDetails(url) }.exceptionOrNull() is HttpDeniedException) }
        assertEquals(1, network.jsonUrls.size)
    }

    /** 详情响应 id/hash 必须匹配请求，合法响应生成公共 magnet，坏可选数值只降级。 */
    @Test
    fun validatesDetailsIdentityAndMapsSafeFields() = runTest {
        /** 正常详情 fixture。 */
        val normal = ThePirateBayProvider(FakeTorrentSearchNetworkClient(jsonHandler = { readProviderFixture("provider/thepiratebay/details_normal.json") })).getDetails("https://thepiratebay.org/description.php?id=42")
        assertNotNull(normal)
        assertEquals("00112233445566778899aabbccddeeff00112233", normal!!.infoHash)
        assertEquals("8.00 PB", normal.size)
        assertEquals(12u, normal.seeders)
        assertEquals(3u, normal.peers)
        assertEquals(1_704_067_200_000L, normal.uploadEpochMillis)
        assertTrue(normal.magnetUri.startsWith("magnet:?xt=urn:btih:"))
        assertEquals("<p>Untrusted</p>", normal.description)
        /** 冲突 id 与坏 hash 均拒绝整个详情。 */
        listOf("43", "bad").forEach { replacement ->
            /** 响应只污染身份字段。 */
            val json = readProviderFixture("provider/thepiratebay/details_normal.json").replace(if (replacement == "43") "\"id\":\"42\"" else "00112233445566778899aabbccddeeff00112233", if (replacement == "43") "\"id\":\"43\"" else replacement)
            assertNull(ThePirateBayProvider(FakeTorrentSearchNetworkClient(jsonHandler = { json })).getDetails("https://thepiratebay.org/description.php?id=42"))
        }
    }

    /** 详情可选数字接受 UInt 最大值，并让越界、负数、非法文本与 epoch 溢出独立降级。 */
    @Test
    fun mapsDetailsLongSizeAndDegradesNumericBoundaries() = runTest {
        /** normalJson 提供固定 Long 大小和可替换的数字字符串字段。 */
        val normalJson = readProviderFixture("provider/thepiratebay/details_normal.json")
        /** boundaryJson 将人数与时间替换到协议边界。 */
        val boundaryJson = normalJson
            .replace("\"seeders\":\"12\"", "\"seeders\":\"4294967295\"")
            .replace("\"leechers\":\"3\"", "\"leechers\":\"4294967296\"")
            .replace("\"added\":\"1704067200\"", "\"added\":\"9223372036854775807\"")
        /** boundaryDetails 通过公开详情入口验证边界降级。 */
        val boundaryDetails = checkNotNull(
            ThePirateBayProvider(
                FakeTorrentSearchNetworkClient(jsonHandler = { boundaryJson }),
            ).getDetails("https://thepiratebay.org/description.php?id=42"),
        )
        assertEquals("8.00 PB", boundaryDetails.size)
        assertEquals(UInt.MAX_VALUE, boundaryDetails.seeders)
        assertNull(boundaryDetails.peers)
        assertNull(boundaryDetails.uploadEpochMillis)

        /** invalidJson 将四个可选数字分别替换成负数或非法文本。 */
        val invalidJson = normalJson
            .replace("\"size\":\"9007199254740993\"", "\"size\":\"invalid\"")
            .replace("\"seeders\":\"12\"", "\"seeders\":\"-1\"")
            .replace("\"leechers\":\"3\"", "\"leechers\":\"invalid\"")
            .replace("\"added\":\"1704067200\"", "\"added\":\"invalid\"")
        /** invalidDetails 保留合法身份，同时丢弃所有坏可选数字。 */
        val invalidDetails = checkNotNull(
            ThePirateBayProvider(
                FakeTorrentSearchNetworkClient(jsonHandler = { invalidJson }),
            ).getDetails("https://thepiratebay.org/description.php?id=42"),
        )
        assertNull(invalidDetails.size)
        assertNull(invalidDetails.seeders)
        assertNull(invalidDetails.peers)
        assertNull(invalidDetails.uploadEpochMillis)
    }

    /** 详情 id 一旦存在就必须是匹配请求的规范正整数字符串，错误 JSON 类型不能冒充缺字段。 */
    @Test
    fun rejectsEveryPresentInvalidDetailsIdTypeAndKeepsMissingIdCompatibility() = runTest {
        /** normalJson 提供除 id 外均合法的详情身份与字段。 */
        val normalJson = readProviderFixture("provider/thepiratebay/details_normal.json")
        /** invalidIdValues 覆盖匹配/冲突数字、null、Boolean、对象、数组和非规范字符串。 */
        val invalidIdValues = listOf(
            "42",
            "43",
            "null",
            "true",
            "false",
            "{}",
            "[]",
            "\"\"",
            "\" \"",
            "\"-0\"",
            "\"0\"",
            "\"-1\"",
            "\"042\"",
            "\"9223372036854775808\"",
            "\"43\"",
        )
        /** rejectedDetails 通过公开入口逐一执行网络回调与真实 parser。 */
        val rejectedDetails = invalidIdValues.map {
            /** invalidIdValue 是当前替换进响应的完整 JSON 值。 */
            invalidIdValue ->
            /** responseJson 只改变 id 类型或值，其他必填身份保持合法。 */
            val responseJson = normalJson.replace(
                oldValue = "\"id\":\"42\"",
                newValue = "\"id\":$invalidIdValue",
            )
            ThePirateBayProvider(
                FakeTorrentSearchNetworkClient(jsonHandler = { responseJson }),
            ).getDetails("https://thepiratebay.org/description.php?id=42")
        }
        assertTrue(rejectedDetails.all {
            /** details 是当前错误 id 响应的公开入口结果。 */
            details -> details == null
        })

        /** missingIdJson 模拟固定源兼容场景：响应完全没有 id 键。 */
        val missingIdJson = normalJson.replace("\"id\":\"42\",", "")
        /** missingIdDetails 必须仍由名称与 hash 身份生成详情。 */
        val missingIdDetails = ThePirateBayProvider(
            FakeTorrentSearchNetworkClient(jsonHandler = { missingIdJson }),
        ).getDetails("https://thepiratebay.org/description.php?id=42")
        assertNotNull(missingIdDetails)
    }

    /** 网络取消必须原样传播。 */
    @Test
    fun propagatesCancellation() = runTest {
        /** 网络回调模拟父协程取消。 */
        val provider = ThePirateBayProvider(FakeTorrentSearchNetworkClient(jsonHandler = { throw CancellationException() }))
        assertTrue(runCatching { provider.search("fixture", Category.ALL) }.exceptionOrNull() is CancellationException)
    }
}
