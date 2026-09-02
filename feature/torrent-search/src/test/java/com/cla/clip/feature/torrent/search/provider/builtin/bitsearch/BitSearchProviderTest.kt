package com.cla.clip.feature.torrent.search.provider.builtin.bitsearch

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

/** BitSearch 五页并发、分类、详情、相对日期和本地 NSFW 审计契约测试。 */
class BitSearchProviderTest {
    /** 搜索必须请求 1..5 页并按页码稳定合并，同时描述符固定 NSFW/默认关闭。 */
    @Test
    fun searchBuildsFiveEncodedRequestsAndPreservesPageOrder() = runTest {
        /** 每个 URL 生成带页码名称的合成结果。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** url 提取当前页码并生成对应名称。 */
            textHandler = { url -> bitSearchPage("Page ${url.substringAfter("page=").substringBefore('&')}", "2 hours ago") },
        )
        /** 固定当前时间便于相对日期断言。 */
        val provider = BitSearchProvider(networkClient, currentTimeMillis = { 1709528767000L })

        /** 五页结果。 */
        val results = provider.search("A&B\n C\u0000", Category.ANIME)

        /** torrent 是当前页结果，用于验证稳定 flatten 顺序。 */
        assertEquals(listOf("Page 1", "Page 2", "Page 3", "Page 4", "Page 5"), results.map { torrent -> torrent.name })
        /** page 是预期 URL 的固定页码。 */
        assertEquals((1..5).map { page -> "https://bitsearch.to/search?q=A%26B%20C&page=$page&sortBy=seeders&category=4" }, networkClient.textUrls)
        assertEquals(1709521567000L, results.first().uploadEpochMillis)
        assertEquals(ProviderSafetyLevel.NSFW, provider.descriptor.safetyLevel)
        assertFalse(provider.descriptor.enabledByDefault)
        assertEquals(setOf(Category.ANIME, Category.APPS, Category.BOOKS, Category.GAMES, Category.MOVIES, Category.MUSIC, Category.PORN, Category.SERIES, Category.OTHER), provider.descriptor.supportedCategories)
    }

    /** ALL 不发送分类，latest/top 使用固定路径，其余分类使用稳定数字映射。 */
    @Test
    fun browseUrlsUseStableCategoryMapping() = runTest {
        /** 返回合法空容器。 */
        val networkClient = FakeTorrentSearchNetworkClient(textHandler = { "<html><body><main><div class=\"space-y-4\"></div></main></body></html>" })
        /** 待测 Provider。 */
        val provider = BitSearchProvider(networkClient)

        provider.getLatestTorrents(Category.ALL)
        provider.getLatestTorrents(Category.PORN)
        provider.getTopTorrents(Category.BOOKS)

        assertEquals(
            listOf(
                "https://bitsearch.to/latest",
                "https://bitsearch.to/latest?category=10",
                "https://bitsearch.to/trending?category=9",
            ),
            networkClient.textUrls,
        )
    }

    /** 结果字段、固定日期和未知类别回退必须稳定映射。 */
    @Test
    fun searchMapsFieldsAndUnknownCategoryToOther() = runTest {
        /** 每页返回同一条正常 fixture。 */
        val fixture = readProviderFixture("provider/bitsearch/search_normal.html")
        /** 返回正常 HTML 的网络 fake。 */
        val provider = BitSearchProvider(FakeTorrentSearchNetworkClient(textHandler = { fixture }))

        /** 第一页首条结果。 */
        val torrent = provider.search("fixture", Category.ALL).first()

        assertEquals("00112233445566778899aabbccddeeff00112233", torrent.infoHash)
        assertEquals("Synthetic BitSearch", torrent.name)
        assertEquals("900 MiB", torrent.size)
        assertEquals(22u, torrent.seeders)
        assertEquals(4u, torrent.peers)
        assertEquals(1709510400000L, torrent.uploadEpochMillis)
        assertEquals(Category.OTHER, torrent.category)
        assertEquals("https://bitsearch.to/torrent/fixture", torrent.descriptionPageUrl)
        assertEquals("https://bitsearch.to/download/torrent/fixture", torrent.fileDownloadLink)
    }

    /** 正常空容器返回空列表；缺 magnet 或非法 btih 条目逐条跳过。 */
    @Test
    fun searchAcceptsEmptyAndSkipsInvalidRows() = runTest {
        /** 一个缺 magnet、一个非法 magnet 的合成容器。 */
        val invalidRows = """
            <html><body><main><div class="space-y-4">
              <article data-torrent-result><h3 data-field="name">missing</h3></article>
              <article data-torrent-result><h3 data-field="name">invalid</h3><a data-field="magnet" href="magnet:?xt=urn:btih:nope">m</a></article>
            </div></main></body></html>
        """.trimIndent()
        /** 首页空、后续无效，最终都应为空。 */
        var calls = 0
        /** 记录调用次数并返回两类页面。 */
        val provider = BitSearchProvider(FakeTorrentSearchNetworkClient(textHandler = { calls += 1; if (calls == 1) "<html><body><main><div class=\"space-y-4\"></div></main></body></html>" else invalidRows }))

        assertTrue(provider.search("fixture", Category.ALL).isEmpty())
    }

    /** 缺结果顶层容器必须抛稳定解析异常。 */
    @Test
    fun searchRejectsMalformedTopLevelHtml() = runTest {
        /** 合成挑战页。 */
        val provider = BitSearchProvider(FakeTorrentSearchNetworkClient(textHandler = { "<html><body>challenge</body></html>" }))

        /** 顶层异常。 */
        val failure = runCatching { provider.search("fixture", Category.ALL) }.exceptionOrNull()
        assertTrue(failure is ProviderParseException)
    }

    /** 结果和详情中的跨 host 下载/详情链接必须在网络或产出前拒绝。 */
    @Test
    fun rejectsCrossHostUrls() = runTest {
        /** 恶意详情 URL 的结果页面。 */
        val page = bitSearchPage("Bad", "3/4/2024").replace("/torrent/fixture", "https://evil.invalid/torrent")
        /** 只允许搜索页面读取。 */
        val networkClient = FakeTorrentSearchNetworkClient(textHandler = { page })
        /** 待测 Provider。 */
        val provider = BitSearchProvider(networkClient)

        /** 页面解析恶意 URL 失败。 */
        val searchFailure = runCatching { provider.search("fixture", Category.ALL) }.exceptionOrNull()
        /** 直接详情恶意 host 失败。 */
        val detailsFailure = runCatching { provider.getDetails("https://evil.invalid/torrent") }.exceptionOrNull()

        assertTrue(searchFailure is HttpDeniedException)
        assertTrue(detailsFailure is HttpDeniedException)
        assertEquals(5, networkClient.textUrls.size)
    }

    /** 详情必须要求合法 btih，映射日期/分类/下载链接；非法日期只降级为空。 */
    @Test
    fun detailsMapsFixtureAndInvalidDateDegradesToNull() = runTest {
        /** 完整合成详情页。 */
        val normal = readProviderFixture("provider/bitsearch/details_normal.html")
        /** 正常详情 Provider。 */
        val normalProvider = BitSearchProvider(FakeTorrentSearchNetworkClient(textHandler = { normal }))
        /** 非法日期详情 Provider。 */
        val invalidDateProvider = BitSearchProvider(FakeTorrentSearchNetworkClient(textHandler = { normal.replace("3/4/2024", "invalid-date") }))

        /** 正常详情。 */
        val details = checkNotNull(normalProvider.getDetails("https://bitsearch.to/torrent/fixture"))
        /** 非法日期详情。 */
        val invalidDateDetails = checkNotNull(invalidDateProvider.getDetails("https://bitsearch.to/torrent/fixture"))

        assertEquals("00112233445566778899aabbccddeeff00112233", details.infoHash)
        assertEquals(Category.PORN, details.category)
        assertEquals(1709510400000L, details.uploadEpochMillis)
        assertEquals("https://bitsearch.to/download/torrent/fixture", details.fileDownloadLink)
        assertNull(invalidDateDetails.uploadEpochMillis)
    }

    /** 分页任一请求取消必须向上传播，不能伪造部分成功结果。 */
    @Test
    fun searchPropagatesPaginationCancellation() = runTest {
        /** 第二页主动取消，其余页返回空容器。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** url 在第二页触发取消，其余返回合法空页面。 */
            textHandler = { url -> if (url.contains("page=2")) throw CancellationException("fixture cancellation") else "<html><body><main><div class=\"space-y-4\"></div></main></body></html>" },
        )

        /** 取消信号。 */
        val failure = runCatching { BitSearchProvider(networkClient).search("fixture", Category.ALL) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }
}

/** 生成符合 BitSearch 合成选择器的单条结果页。 */
private fun bitSearchPage(name: String, date: String): String = """
    <html><body><main><div class="space-y-4">
      <article data-torrent-result>
        <h3 data-field="name"><a href="/torrent/fixture">$name</a></h3>
        <span data-field="category">Unknown</span><span data-field="size">900 MiB</span>
        <span data-field="date">$date</span><span data-field="seeders">22</span><span data-field="peers">4</span>
        <a data-field="file" href="/download/torrent/fixture">file</a>
        <a data-field="magnet" href="magnet:?xt=urn:btih:00112233445566778899aabbccddeeff00112233">magnet</a>
      </article>
    </div></main></body></html>
""".trimIndent()
