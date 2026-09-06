package com.cla.clip.feature.torrent.search.provider.builtin.yts

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

/** Yts 逐电影展开、严格 token、媒体 URL 降级、身份与取消契约测试。 */
class YtsProviderTest {
    /** descriptor 必须匹配 manifest 的稳定 ID、安全等级和电影分类。 */
    @Test
    fun descriptorMatchesReviewedMetadata() {
        /** 不联网的待测 Provider。 */
        val provider = YtsProvider(FakeTorrentSearchNetworkClient())
        assertEquals("ytsmx", provider.descriptor.id.value)
        assertEquals(ProviderSafetyLevel.SAFE, provider.descriptor.safetyLevel)
        assertTrue(provider.descriptor.enabledByDefault)
        assertEquals(setOf(Category.MOVIES), provider.descriptor.supportedCategories)
    }

    /** search 编码 query 并保留 limit，latest/top 每次发起独立 API 请求。 */
    @Test
    fun buildsSearchAndIndependentLatestTopRequests() = runTest {
        /** 合法零电影响应。 */
        val network = FakeTorrentSearchNetworkClient(jsonHandler = { readProviderFixture("provider/yts/search_empty.json") })
        /** 待测 Provider。 */
        val provider = YtsProvider(network)
        provider.search(" A&B\n C ", Category.ALL)
        provider.getLatestTorrents(Category.MOVIES)
        provider.getTopTorrents(Category.MOVIES)
        /** 搜索请求使用结构化参数。 */
        val search = network.jsonUrls[0].toHttpUrl()
        assertEquals("movies-api.accel.li", search.host)
        assertEquals("/api/v2/list_movies.json", search.encodedPath)
        assertEquals("A&B C", search.queryParameter("query_term"))
        assertEquals("50", search.queryParameter("limit"))
        assertEquals(listOf("https://movies-api.accel.li/api/v2/list_movies.json", "https://movies-api.accel.li/api/v2/list_movies.json"), network.jsonUrls.drop(1))
    }

    /** 多电影多 torrent 按输入顺序展开，坏 torrent 与坏电影不截断后续合法项。 */
    @Test
    fun expandsEveryMovieTorrentAndKeepsSurvivorsAfterBadItems() = runTest {
        /** 混合 fixture 含两个合法电影、三个合法 torrent 和多个坏项。 */
        val results = YtsProvider(FakeTorrentSearchNetworkClient(jsonHandler = { readProviderFixture("provider/yts/search_mixed.json") })).search("fixture", Category.MOVIES)
        assertEquals(listOf("Synthetic One [720p] [bluray] [x264]", "Synthetic One [1080p] [web] [x265]", "Synthetic Two [2160p] [-] [-]"), results.map { it.name })
        assertEquals(listOf("00112233445566778899aabbccddeeff00112233", "112233445566778899aabbccddeeff0011223344", "2233445566778899aabbccddeeff001122334455"), results.map { it.infoHash })
        assertTrue(results.all { it.descriptionPageUrl?.startsWith("https://yts.bz/movies/") == true })
    }

    /** movie_count=0 且省略 movies 是合法空；其他缺结构或错误类型必须稳定失败。 */
    @Test
    fun distinguishesDocumentedEmptyFromMalformedProtocol() = runTest {
        /** 固定合法空响应。 */
        assertTrue(YtsProvider(FakeTorrentSearchNetworkClient(jsonHandler = { readProviderFixture("provider/yts/search_empty.json") })).search("fixture", Category.MOVIES).isEmpty())
        /** data 缺失、movie_count 非零但缺 movies、movies 非数组和坏 JSON 都失败。 */
        listOf("{}", "{\"data\":{\"movie_count\":1}}", "{\"data\":{\"movie_count\":1,\"movies\":{}}}", "{").forEach { json -> assertTrue(runCatching { YtsProvider(FakeTorrentSearchNetworkClient(jsonHandler = { json })).search("fixture", Category.MOVIES) }.exceptionOrNull() is ProviderParseException) }
    }

    /** 页面 token builder/parser 必须可逆，并拒绝重复、多余、伪 hash、错 host/path/端口。 */
    @Test
    fun validatesExactDetailsTokenBeforeNetwork() = runTest {
        /** 正常详情 fixture 允许检查合法 token 的 API 请求。 */
        val network = FakeTorrentSearchNetworkClient(jsonHandler = { readProviderFixture("provider/yts/details_normal.json") })
        /** 待测 Provider。 */
        val provider = YtsProvider(network)
        assertNotNull(provider.getDetails("https://yts.bz/movies/synthetic-one?movieid=7&infohash=00112233445566778899aabbccddeeff00112233"))
        assertEquals("7", network.jsonUrls.single().toHttpUrl().queryParameter("movie_id"))
        assertEquals("true", network.jsonUrls.single().toHttpUrl().queryParameter("with_images"))
        /** 每个恶意 token 都必须在联网前拒绝。 */
        val invalid = listOf("http://yts.bz/movies/a?movieid=7&infohash=00112233445566778899aabbccddeeff00112233", "https://user@yts.bz/movies/a?movieid=7&infohash=00112233445566778899aabbccddeeff00112233", "https://yts.bz:444/movies/a?movieid=7&infohash=00112233445566778899aabbccddeeff00112233", "https://sub.yts.bz/movies/a?movieid=7&infohash=00112233445566778899aabbccddeeff00112233", "https://yts.bz/other/a?movieid=7&infohash=00112233445566778899aabbccddeeff00112233", "https://yts.bz/movies/a?movieid=0&infohash=00112233445566778899aabbccddeeff00112233", "https://yts.bz/movies/a?movieid=7&movieid=8&infohash=00112233445566778899aabbccddeeff00112233", "https://yts.bz/movies/a?movieid=7&infohash=bad", "https://yts.bz/movies/a?movieid=7&infohash=00112233445566778899aabbccddeeff00112233&x=1", "https://yts.bz/movies/a?movieid=7&infohash=00112233445566778899aabbccddeeff00112233#x")
        /** token 是当前恶意输入。 */
        invalid.forEach { token -> assertTrue(runCatching { provider.getDetails(token) }.exceptionOrNull() is HttpDeniedException) }
        assertEquals(1, network.jsonUrls.size)
    }

    /** 详情 movie id 与 torrent hash 必须匹配；非法可选媒体只丢字段并保留详情。 */
    @Test
    fun checksDetailsIdentityAndDropsUnsafeMedia() = runTest {
        /** 污染所有可选媒体 URL，但保留详情必填身份。 */
        val malicious = readProviderFixture("provider/yts/details_normal.json").replace("https://yts.bz/assets/poster.jpg", "https://evil.invalid/poster.jpg").replace("https://yts.bz/assets/shot.jpg", "https://user@yts.bz/assets/shot.jpg").replace("https://yts.bz/torrent/fixture.torrent", "https://yts.bz:444/torrent/fixture.torrent")
        /** 合法详情应保留，媒体字段安全降级。 */
        val details = checkNotNull(YtsProvider(FakeTorrentSearchNetworkClient(jsonHandler = { malicious })).getDetails("https://yts.bz/movies/synthetic-one?movieid=7&infohash=00112233445566778899aabbccddeeff00112233"))
        assertNull(details.posterUrl)
        assertTrue(details.screenshotUrls.isEmpty())
        assertNull(details.fileDownloadLink)
        assertEquals("<p>Untrusted</p>", details.description)
        /** 响应 movie id 冲突或找不到目标 hash 时为空。 */
        listOf(readProviderFixture("provider/yts/details_normal.json").replace("\"id\":7", "\"id\":8"), readProviderFixture("provider/yts/details_normal.json").replace("00112233445566778899aabbccddeeff00112233", "112233445566778899aabbccddeeff0011223344")).forEach { json -> assertNull(YtsProvider(FakeTorrentSearchNetworkClient(jsonHandler = { json })).getDetails("https://yts.bz/movies/synthetic-one?movieid=7&infohash=00112233445566778899aabbccddeeff00112233")) }
    }

    /** Long 大小和 epoch 溢出按字段降级，网络取消原样传播。 */
    @Test
    fun handlesNumericBoundariesAndCancellation() = runTest {
        /** 边界 fixture 保留合法必填身份。 */
        val item = YtsProvider(FakeTorrentSearchNetworkClient(jsonHandler = { readProviderFixture("provider/yts/search_boundaries.json") })).search("fixture", Category.MOVIES).single()
        assertEquals("8.00 PB", item.size)
        assertNull(item.seeders)
        assertNull(item.peers)
        assertNull(item.uploadEpochMillis)
        /** 取消不能转成空结果。 */
        val cancelled = YtsProvider(FakeTorrentSearchNetworkClient(jsonHandler = { throw CancellationException() }))
        assertTrue(runCatching { cancelled.search("fixture", Category.MOVIES) }.exceptionOrNull() is CancellationException)
    }
}
