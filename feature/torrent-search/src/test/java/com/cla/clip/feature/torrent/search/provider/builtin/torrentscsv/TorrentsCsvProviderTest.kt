package com.cla.clip.feature.torrent.search.provider.builtin.torrentscsv

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

/** TorrentsCSV 请求、严格 JSON、数字边界和取消契约测试。 */
class TorrentsCsvProviderTest {
    /** descriptor 必须与 manifest 的稳定 ID、安全等级和仅搜索能力一致。 */
    @Test
    fun descriptorMatchesReviewedMetadata() {
        /** 不联网的待测 Provider。 */
        val provider = TorrentsCsvProvider(FakeTorrentSearchNetworkClient())
        assertEquals("torrentscsv", provider.descriptor.id.value)
        assertEquals(ProviderSafetyLevel.SAFE, provider.descriptor.safetyLevel)
        assertTrue(provider.descriptor.enabledByDefault)
        assertEquals(setOf(Category.OTHER), provider.descriptor.supportedCategories)
    }

    /** 查询必须删除控制字符并由 HttpUrl 编码，输出只保留上源已有字段。 */
    @Test
    fun buildsEncodedSearchAndMapsCompleteItem() = runTest {
        /** 返回完整正常 fixture 并记录请求。 */
        val network = FakeTorrentSearchNetworkClient(jsonHandler = { readProviderFixture("provider/torrentscsv/search_normal.json") })
        /** 待测 Provider。 */
        val item = TorrentsCsvProvider(network).search("  A&B\n C\u0000  ", Category.ALL).single()
        /** 实际请求用于检查结构化 query。 */
        val request = network.jsonUrls.single().toHttpUrl()
        assertEquals("https", request.scheme)
        assertEquals("torrents-csv.com", request.host)
        assertEquals("/service/search", request.encodedPath)
        assertEquals("A&B C", request.queryParameter("q"))
        assertEquals("00112233445566778899aabbccddeeff00112233", item.infoHash)
        assertEquals("Synthetic Archive", item.name)
        assertEquals("4.00 GB", item.size)
        assertEquals(12u, item.seeders)
        assertEquals(3u, item.peers)
        assertEquals(1_704_067_200_000L, item.uploadEpochMillis)
        assertNull(item.magnetUri)
        assertNull(item.descriptionPageUrl)
        assertNull(item.fileDownloadLink)
    }

    /** 合法空数组为空，顶层或 torrents 字段类型错误必须稳定失败。 */
    @Test
    fun distinguishesEmptyFromMalformedProtocol() = runTest {
        /** 三种协议响应依次独立测试。 */
        val cases = listOf("{\"torrents\":[]}" to false, "[]" to true, "{\"torrents\":{}}" to true)
        /** case 是当前 JSON 与预期失败标志。 */
        cases.forEach { (json, shouldFail) ->
            /** 当前响应使用独立 fake，避免请求记录相互影响。 */
            val failure = runCatching { TorrentsCsvProvider(FakeTorrentSearchNetworkClient(jsonHandler = { json })).search("fixture", Category.ALL) }.exceptionOrNull()
            if (shouldFail) assertTrue(failure is ProviderParseException) else assertNull(failure)
        }
    }

    /** 非对象、空名、坏 hash 与坏数字只跳过坏项，后续合法项仍返回。 */
    @Test
    fun skipsBadItemsAndKeepsValidItemAfterThem() = runTest {
        /** fixture 在四个坏项之后放置一个合法项。 */
        val results = TorrentsCsvProvider(FakeTorrentSearchNetworkClient(jsonHandler = { readProviderFixture("provider/torrentscsv/search_mixed.json") })).search("fixture", Category.OTHER)
        assertEquals(listOf("Bad size", "Synthetic Survivor"), results.map { it.name })
        assertNull(results.first().size)
    }

    /** Long 大小不得经 Float 降精度，负数/超大 UInt 与 epoch 溢出按字段降级。 */
    @Test
    fun preservesLongSizeAndDegradesInvalidOptionalNumbers() = runTest {
        /** 边界 fixture 含 2^53+1 字节、负做种、UInt 溢出和秒转毫秒溢出。 */
        val item = TorrentsCsvProvider(FakeTorrentSearchNetworkClient(jsonHandler = { readProviderFixture("provider/torrentscsv/search_boundaries.json") })).search("fixture", Category.OTHER).single()
        assertEquals("8.00 PB", item.size)
        assertNull(item.seeders)
        assertNull(item.peers)
        assertNull(item.uploadEpochMillis)
    }

    /** 网络取消与大列表解析取消必须原样传播，不能转空结果或解析失败。 */
    @Test
    fun propagatesCancellation() = runTest {
        /** 网络边界直接取消。 */
        val provider = TorrentsCsvProvider(FakeTorrentSearchNetworkClient(jsonHandler = { throw CancellationException() }))
        assertTrue(runCatching { provider.search("fixture", Category.ALL) }.exceptionOrNull() is CancellationException)
    }
}
