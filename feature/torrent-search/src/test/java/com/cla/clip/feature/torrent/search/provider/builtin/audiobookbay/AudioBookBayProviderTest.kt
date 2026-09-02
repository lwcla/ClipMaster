package com.cla.clip.feature.torrent.search.provider.builtin.audiobookbay

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
import org.junit.Assert.assertTrue
import org.junit.Test

/** AudioBookBay 两阶段搜索、详情字段和精确 host 安全契约测试。 */
class AudioBookBayProviderTest {
    /** 正常结果按 DOM 顺序读取详情 hash，并固定 SAFE/BOOKS/默认关闭描述符。 */
    @Test
    fun searchReadsHashesInDomOrderAndMapsFields() = runTest {
        /** 合成搜索结果页。 */
        val searchFixture = readProviderFixture("provider/audiobookbay/search_normal.html")
        /** 合成详情 hash 页。 */
        val hashFixture = readProviderFixture("provider/audiobookbay/hash_normal.html")
        /** 按地址返回搜索或详情内容的 recording fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** url 区分搜索页与两个详情 hash 页面。 */
            textHandler = { url -> if (url.contains("?s=")) searchFixture else hashFixture.replace("HASH_TOKEN", hashFor(url)) },
        )
        /** 待测 Provider。 */
        val provider = AudioBookBayProvider(networkClient)

        /** 两条结果必须保持页面顺序。 */
        val results = provider.search("A&B\n C\u0000", Category.BOOKS)

        /** torrent 是当前顺序结果，用于提取名称。 */
        assertEquals(listOf("Synthetic Book One", "Synthetic Book Two"), results.map { torrent -> torrent.name })
        /** torrent 是当前顺序结果，用于提取规范 hash。 */
        assertEquals(listOf("00112233445566778899aabbccddeeff00112233", "ffeeddccbbaa99887766554433221100ffeeddcc"), results.map { torrent -> torrent.infoHash })
        assertEquals("1.5 GB", results.first().size)
        assertEquals(1709510400000L, results.first().uploadEpochMillis)
        assertEquals(Category.BOOKS, results.first().category)
        assertEquals(ProviderSafetyLevel.SAFE, provider.descriptor.safetyLevel)
        assertFalse(provider.descriptor.enabledByDefault)
        assertEquals(
            listOf(
                "https://audiobookbay.lu/?s=A%26B%20C",
                "https://audiobookbay.lu/book/one",
                "https://audiobookbay.lu/book/two",
            ),
            networkClient.textUrls,
        )
    }

    /** latest 使用根地址；合法空容器返回空列表而非协议异常。 */
    @Test
    fun latestUsesRootUrlAndAcceptsEmptyContainer() = runTest {
        /** 带固定顶层容器的空页面。 */
        val networkClient = FakeTorrentSearchNetworkClient(textHandler = { "<html><body><main id=\"primary\"></main></body></html>" })
        /** 待测 Provider。 */
        val provider = AudioBookBayProvider(networkClient)

        assertTrue(provider.getLatestTorrents().isEmpty())
        assertEquals(listOf("https://audiobookbay.lu/"), networkClient.textUrls)
    }

    /** 单条缺字段或详情缺 hash 时只跳过该条，不能阻断后续结果。 */
    @Test
    fun searchSkipsMissingRowsAndMissingHashes() = runTest {
        /** 一条缺标题、一条无 hash 和一条合法记录的合成页面。 */
        val page = """
            <html><body><main id="primary">
              <div class="post"><div class="postTitle"><h2></h2></div></div>
              <div class="post"><div class="postTitle"><h2><a href="/book/nohash">No Hash</a></h2></div><div class="postContent"><p>x</p><p>x</p><p>File Size: 1 GB</p></div></div>
              <div class="post"><div class="postTitle"><h2><a href="/book/valid">Valid</a></h2></div><div class="postContent"><p>x</p><p>x</p><p>File Size: 2 GB</p></div></div>
            </main></body></html>
        """.trimIndent()
        /** nohash 返回无 hash 页面，valid 返回合法 hash。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** url 区分结果页、无 hash 与合法 hash 详情页。 */
            textHandler = { url -> if (url.contains("?s=")) page else if (url.endsWith("/valid")) hashPage("00112233445566778899aabbccddeeff00112233") else hashPage("") },
        )

        /** 唯一合法结果名称。 */
        val names = AudioBookBayProvider(networkClient).search("fixture", Category.ALL).map { torrent -> torrent.name }

        assertEquals(listOf("Valid"), names)
    }

    /** 缺顶层容器必须抛低敏解析异常。 */
    @Test
    fun searchRejectsMalformedTopLevelHtml() = runTest {
        /** 不含 primary 的合成挑战页。 */
        val provider = AudioBookBayProvider(FakeTorrentSearchNetworkClient(textHandler = { "<html><body>challenge</body></html>" }))

        /** 顶层结构异常。 */
        val failure = runCatching { provider.search("fixture", Category.ALL) }.exceptionOrNull()
        assertTrue(failure is ProviderParseException)
    }

    /** 详情和搜索页提取的跨 host 地址必须在网络前拒绝。 */
    @Test
    fun rejectsUnlistedDetailsHostsBeforeNetwork() = runTest {
        /** 搜索页携带恶意详情地址。 */
        val page = "<html><body><main id=\"primary\"><div class=\"post\"><div class=\"postTitle\"><h2><a href=\"https://evil.invalid/book\">Bad</a></h2></div></div></main></body></html>"
        /** 仅首个页面请求允许发生。 */
        val networkClient = FakeTorrentSearchNetworkClient(textHandler = { page })
        /** 待测 Provider。 */
        val provider = AudioBookBayProvider(networkClient)

        /** 搜索恶意链接失败。 */
        val searchFailure = runCatching { provider.search("fixture", Category.ALL) }.exceptionOrNull()
        /** 直接恶意详情失败。 */
        val detailsFailure = runCatching { provider.getDetails("https://evil.invalid/book") }.exceptionOrNull()

        assertTrue(searchFailure is HttpDeniedException)
        assertTrue(detailsFailure is HttpDeniedException)
        assertEquals(1, networkClient.textUrls.size)
    }

    /** 取消详情 hash 阶段后不能访问 DOM 中后续详情页。 */
    @Test
    fun cancellationStopsBeforeNextDetailsRequest() = runTest {
        /** 两条结果页面用于验证顺序取消。 */
        val page = readProviderFixture("provider/audiobookbay/search_normal.html")
        /** 第一个详情请求即取消。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** url 在搜索页后进入首个详情时触发取消。 */
            textHandler = { url -> if (url.contains("?s=")) page else throw CancellationException("fixture cancellation") },
        )

        /** 取消必须原样传播。 */
        val failure = runCatching { AudioBookBayProvider(networkClient).search("fixture", Category.ALL) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(listOf("https://audiobookbay.lu/?s=fixture", "https://audiobookbay.lu/book/one"), networkClient.textUrls)
    }

    /** 详情必须映射替代大小、RFC1123 日期、描述、上传者、海报和 canonical magnet。 */
    @Test
    fun detailsMapsAlternateSizeAndTrustedPoster() = runTest {
        /** 合成完整详情页。 */
        val fixture = readProviderFixture("provider/audiobookbay/details_normal.html")
        /** 仅返回合成详情的网络 fake。 */
        val provider = AudioBookBayProvider(FakeTorrentSearchNetworkClient(textHandler = { fixture }))

        /** 非空详情结果。 */
        val details = checkNotNull(provider.getDetails("https://audiobookbay.lu/book/one"))

        assertEquals("00112233445566778899aabbccddeeff00112233", details.infoHash)
        assertEquals("2.5 GB", details.size)
        assertEquals(1709528767000L, details.uploadEpochMillis)
        assertEquals("Synthetic Uploader", details.uploader)
        assertEquals("<p>Synthetic description</p>", details.description)
        assertEquals("https://audiobookbay.lu/images/one.jpg", details.posterUrl)
        assertTrue(details.magnetUri.startsWith("magnet:?xt=urn:btih:00112233445566778899aabbccddeeff00112233"))
    }
}

/** 按详情地址返回两个固定合法 hash。 */
private fun hashFor(url: String): String = if (url.endsWith("/one")) {
    "00112233445566778899aabbccddeeff00112233"
} else {
    "ffeeddccbbaa99887766554433221100ffeeddcc"
}

/** 生成只含 Info Hash 表格的最小详情页面。 */
private fun hashPage(hash: String): String = "<html><body><table><tr><td>Info Hash:</td><td>$hash</td></tr></table></body></html>"
