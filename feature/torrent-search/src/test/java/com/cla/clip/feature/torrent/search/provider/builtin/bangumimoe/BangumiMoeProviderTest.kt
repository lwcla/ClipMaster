package com.cla.clip.feature.torrent.search.provider.builtin.bangumimoe

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BangumiMoe JSON、payload、详情 ID 和 btih 一致性契约测试。 */
class BangumiMoeProviderTest {
    /** 搜索 payload 必须由 JSON 编码，正常字段映射且描述符固定 SAFE/ANIME/默认关闭。 */
    @Test
    fun searchBuildsEncodedPayloadAndMapsFixture() = runTest {
        /** 捕获 POST 正文用于协议断言。 */
        val payloads = mutableListOf<String>()
        /** 返回正常搜索 fixture 的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** 下划线参数为固定 URL，payload 是待捕获搜索正文。 */
            postJsonHandler = { _, payload -> payloads += payload; readProviderFixture("provider/bangumimoe/search_normal.json") },
        )
        /** 待测 Provider。 */
        val provider = BangumiMoeProvider(networkClient)

        /** 唯一合法结果。 */
        val torrent = provider.search("A\"B\n\u0000", Category.ANIME).single()

        assertEquals("{\"query\":\"A\\\"B\"}", payloads.single())
        assertEquals("00112233445566778899aabbccddeeff00112233", torrent.infoHash)
        assertEquals("Synthetic Bangumi", torrent.name)
        assertEquals("700 MiB", torrent.size)
        assertEquals(12u, torrent.seeders)
        assertEquals(3u, torrent.peers)
        assertEquals(1709528767123L, torrent.uploadEpochMillis)
        assertEquals("https://bangumi.moe/torrent/fixture-id", torrent.descriptionPageUrl)
        assertEquals(ProviderSafetyLevel.SAFE, provider.descriptor.safetyLevel)
        assertFalse(provider.descriptor.enabledByDefault)
    }

    /** latest 使用固定 GET 端点并接受 torrents 空数组。 */
    @Test
    fun latestUsesFixedEndpointAndAcceptsEmptyArray() = runTest {
        /** 返回合法空响应。 */
        val networkClient = FakeTorrentSearchNetworkClient(jsonHandler = { "{\"torrents\":[]}" })

        assertTrue(BangumiMoeProvider(networkClient).getLatestTorrents().isEmpty())
        assertEquals(listOf("https://bangumi.moe/api/torrent/latest"), networkClient.jsonUrls)
    }

    /** 缺字段、非法 hash 和 infohash/magnet 冲突条目必须逐条跳过。 */
    @Test
    fun searchSkipsMissingInvalidAndConflictingRows() = runTest {
        /** 四条中仅最后一条的 title/magnet/hash 相互合法。 */
        val json = """{"torrents":[{}, {"title":"bad","magnet":"magnet:?xt=urn:btih:nope"}, {"title":"conflict","magnet":"magnet:?xt=urn:btih:00112233445566778899aabbccddeeff00112233","infohash":"ffeeddccbbaa99887766554433221100ffeeddcc"}, {"title":"valid","magnet":"magnet:?xt=urn:btih:00112233445566778899aabbccddeeff00112233"}]}"""
        /** 返回混合 JSON。 */
        val networkClient = FakeTorrentSearchNetworkClient(postJsonHandler = { _, _ -> json })

        /** 仅合法条目名称。 */
        val names = BangumiMoeProvider(networkClient).search("fixture", Category.ALL).map { torrent -> torrent.name }
        assertEquals(listOf("valid"), names)
    }

    /** 顶层非对象、缺 torrents 或 torrents 类型错误必须抛稳定解析异常。 */
    @Test
    fun searchRejectsMalformedTopLevelJson() = runTest {
        /** 三个协议错误响应。 */
        val cases = listOf("[]", "{}", "{\"torrents\":{}}")

        /** json 是当前协议错误响应。 */
        cases.forEach { json ->
            /** 每轮独立 Provider，避免调用记录相互影响。 */
            val provider = BangumiMoeProvider(FakeTorrentSearchNetworkClient(postJsonHandler = { _, _ -> json }))
            /** 当前协议异常。 */
            val failure = runCatching { provider.search("fixture", Category.ALL) }.exceptionOrNull()
            assertTrue(failure is ProviderParseException)
        }
    }

    /** 详情 URL 只能是同 host 的精确 /torrent/{id} 且不能携带 query/fragment。 */
    @Test
    fun detailsRejectsPollutedOrCrossHostUrlsBeforeNetwork() = runTest {
        /** 不应被调用的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(postJsonHandler = { _, _ -> error("不应请求非法详情 ID") })
        /** 待测 Provider。 */
        val provider = BangumiMoeProvider(networkClient)
        /** 非法详情候选集合。 */
        val invalidUrls = listOf(
            "https://evil.invalid/torrent/id",
            "https://bangumi.moe/torrent/id/extra",
            "https://bangumi.moe/torrent/id?x=1",
            "https://bangumi.moe/torrent/id#fragment",
        )

        /** url 是当前恶意或语义污染地址。 */
        invalidUrls.forEach { url ->
            assertTrue(runCatching { provider.getDetails(url) }.exceptionOrNull() is HttpDeniedException)
        }
        assertTrue(networkClient.postJsonUrls.isEmpty())
    }

    /** 合法详情 ID 必须 JSON 编码并映射完整详情。 */
    @Test
    fun detailsPostsStructuredIdAndMapsFixture() = runTest {
        /** 捕获详情 POST payload。 */
        val payloads = mutableListOf<String>()
        /** 返回正常详情 fixture。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** 下划线参数为固定 URL，payload 是待捕获详情正文。 */
            postJsonHandler = { _, payload -> payloads += payload; readProviderFixture("provider/bangumimoe/details_normal.json") },
        )

        /** 非空详情。 */
        val details = checkNotNull(BangumiMoeProvider(networkClient).getDetails("https://bangumi.moe/torrent/id%20value"))

        assertEquals("{\"_id\":\"id value\"}", payloads.single())
        assertEquals("00112233445566778899aabbccddeeff00112233", details.infoHash)
        assertEquals(Category.ANIME, details.category)
    }

    /** 详情缺必填、非法 btih 或 hash 冲突返回 null，而不是抛出整页协议异常。 */
    @Test
    fun detailsReturnsNullForInvalidRequiredFields() = runTest {
        /** 依次提供缺标题、非法 magnet 和冲突 hash。 */
        val cases = listOf(
            "{\"magnet\":\"magnet:?xt=urn:btih:00112233445566778899aabbccddeeff00112233\"}",
            "{\"title\":\"bad\",\"magnet\":\"magnet:?xt=urn:btih:nope\"}",
            "{\"title\":\"bad\",\"magnet\":\"magnet:?xt=urn:btih:00112233445566778899aabbccddeeff00112233\",\"infohash\":\"ffeeddccbbaa99887766554433221100ffeeddcc\"}",
        )

        /** json 是当前非法详情响应。 */
        cases.forEach { json ->
            /** 当前独立 Provider。 */
            val provider = BangumiMoeProvider(FakeTorrentSearchNetworkClient(postJsonHandler = { _, _ -> json }))
            assertNull(provider.getDetails("https://bangumi.moe/torrent/id"))
        }
    }
}
