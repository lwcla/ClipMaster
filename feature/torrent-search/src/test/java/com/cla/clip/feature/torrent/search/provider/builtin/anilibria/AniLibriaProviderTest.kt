package com.cla.clip.feature.torrent.search.provider.builtin.anilibria

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AniLibria 固定快照搜索、并发上限与 JSON 失败契约测试。 */
class AniLibriaProviderTest {
    /** 正常 fixture 必须精确映射字段、UTC 时间、字节大小与 release alias。 */
    @Test
    fun searchMapsNormalFixtureExactly() = runTest {
        /** 搜索 release 合成数组。 */
        val searchFixture = readProviderFixture("provider/anilibria/search_normal.json")
        /** release torrent 合成数组。 */
        val torrentsFixture = readProviderFixture("provider/anilibria/release_torrents_normal.json")
        /** 按路径返回两阶段响应的可控网络边界。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** url 只用于区分搜索与 release 端点，不输出到诊断。 */
            jsonHandler = { url -> if (url.contains("/app/search/releases")) searchFixture else torrentsFixture },
        )
        /** 仅构造注入共享网络客户端的待测 Provider。 */
        val provider = AniLibriaProvider(networkClient)

        /** 单个合成 release 的搜索结果。 */
        val results = provider.search("fixture", Category.ANIME)
        /** 唯一合法 torrent，所有期望值都与生产解析独立手工推导。 */
        val torrent = results.single()

        assertEquals("anilibria", provider.descriptor.id.value)
        assertEquals(ProviderSafetyLevel.SAFE, provider.descriptor.safetyLevel)
        assertEquals(false, provider.descriptor.enabledByDefault)
        assertEquals("0123456789abcdef0123456789abcdef01234567", torrent.infoHash)
        assertEquals("Synthetic AniLibria Episode", torrent.name)
        assertEquals("1.00 MB", torrent.size)
        assertEquals(12u, torrent.seeders)
        assertEquals(3u, torrent.peers)
        assertEquals(1709528767000L, torrent.uploadEpochMillis)
        assertEquals(Category.ANIME, torrent.category)
        assertEquals("https://www.anilibria.top/anime/releases/release/synthetic-release", torrent.descriptionPageUrl)
        assertEquals("magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567", torrent.magnetUri)
    }

    /** 合法空数组必须返回空结果，不得误报解析失败。 */
    @Test
    fun searchReturnsEmptyListForEmptyReleaseArray() = runTest {
        /** 只返回合法空 JSON 数组的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(jsonHandler = { "[]" })
        /** 待测 AniLibria Provider。 */
        val provider = AniLibriaProvider(networkClient)

        assertTrue(provider.search("fixture", Category.ALL).isEmpty())
        assertEquals(1, networkClient.jsonUrls.size)
    }

    /** 单条缺少 hash 时跳过该条，同批合法 torrent 仍保留。 */
    @Test
    fun searchSkipsEntryMissingRequiredHashAndKeepsValidEntry() = runTest {
        /** 搜索阶段的单 release 合成 JSON。 */
        val searchFixture = """[{"id":101}]"""
        /** 首条缺 hash、第二条合法的最小 torrent 合成 JSON。 */
        val torrentFixture = """[{"label":"broken"},{"hash":"0123456789abcdef0123456789abcdef01234567","label":"valid"}]"""
        /** 按是否为搜索端点返回合成响应的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** url 只用于选择当前合成 JSON，测试不记录正文。 */
            jsonHandler = { url -> if (url.contains("/app/search/releases")) searchFixture else torrentFixture },
        )

        /** 经逐条容错后的搜索结果。 */
        val results = AniLibriaProvider(networkClient).search("fixture", Category.ANIME)

        /** torrent 是逐条容错后保留的合法结果，只取名称检查跳过行为。 */
        assertEquals(listOf("valid"), results.map { torrent -> torrent.name })
    }

    /** 顶层 JSON 不是数组时必须统一映射为 ProviderParseException。 */
    @Test
    fun searchRejectsMalformedTopLevelJson() = runTest {
        /** 返回合法 JSON 对象但错误顶层类型的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(jsonHandler = { """{"unexpected":true}""" })
        /** 待测 AniLibria Provider。 */
        val provider = AniLibriaProvider(networkClient)

        /** 顶层 JSON 类型异常的稳定失败对象。 */
        val failure = runCatching { provider.search("fixture", Category.ANIME) }.exceptionOrNull()
        assertTrue(failure is ProviderParseException)
    }

    /** 查询必须编码保留 & 的数据语义，且删除控制字符以防止请求注入。 */
    @Test
    fun searchBuildsEncodedControlCharacterFreeQueryUrl() = runTest {
        /** 只返回空 release 列表的网络 fake，便于检查唯一请求。 */
        val networkClient = FakeTorrentSearchNetworkClient(jsonHandler = { "[]" })

        AniLibriaProvider(networkClient).search("A&B\n C\u0000", Category.ANIME)

        assertEquals("https://anilibria.top/api/v1/app/search/releases?query=A%26B%20C", networkClient.jsonUrls.single())
    }

    /** 最多只请求 15 个 release，且至少两个 release 请求能在前一个完成前进入 fake。 */
    @Test
    fun searchCapsReleaseRequestsAtFifteenAndStartsThemConcurrently() = runTest {
        /** 二个 release 请求都进入后解锁的并发门。 */
        val concurrentGate = CompletableDeferred<Unit>()
        /** 当前尚未返回的 release 请求数。 */
        var activeReleaseRequests = 0
        /** 观测到的最大同时 release 请求数。 */
        var maxActiveReleaseRequests = 0
        /** 16 个 release 用于证明第 16 个不会被请求。 */
        val searchFixture = (1..16).joinToString(prefix = "[", postfix = "]") { id -> """{"id":$id}""" }
        /** 可观测 release 请求重叠的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** url 用于区分首个搜索请求和后续 release 请求。 */
            jsonHandler = { url ->
                if (url.contains("/app/search/releases")) {
                    searchFixture
                } else {
                    activeReleaseRequests += 1
                    maxActiveReleaseRequests = maxOf(maxActiveReleaseRequests, activeReleaseRequests)
                    if (activeReleaseRequests >= 2) concurrentGate.complete(Unit)
                    concurrentGate.await()
                    activeReleaseRequests -= 1
                    "[]"
                }
            },
        )
        /** 在测试调度器上启动的搜索任务。 */
        val searchTask = async { AniLibriaProvider(networkClient).search("fixture", Category.ANIME) }

        searchTask.await()

        assertEquals(16, networkClient.jsonUrls.size)
        assertTrue(maxActiveReleaseRequests >= 2)
        /** url 是已记录的合成请求，第 16 个 release 必须不存在。 */
        assertTrue(networkClient.jsonUrls.none { url -> url.endsWith("/16") })
    }
}
