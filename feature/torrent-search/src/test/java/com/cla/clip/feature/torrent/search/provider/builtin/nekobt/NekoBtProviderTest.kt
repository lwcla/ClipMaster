package com.cla.clip.feature.torrent.search.provider.builtin.nekobt

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** NekoBT 固定 HTML 接口、精确 DOM 与 URL 安全契约测试。 */
class NekoBtProviderTest {
    /** 本源保持 SAFE、默认关闭、ANIME 和唯一 host。 */
    @Test
    fun descriptorMatchesReviewedMetadata() {
        /** 不联网的 descriptor。 */
        val descriptor = NekoBtProvider(FakeTorrentSearchNetworkClient()).descriptor
        assertEquals("nekobt", descriptor.id.value)
        assertEquals(ProviderSafetyLevel.SAFE, descriptor.safetyLevel)
        assertFalse(descriptor.enabledByDefault)
        assertEquals(setOf(Category.ANIME), descriptor.supportedCategories)
    }

    /** 三种能力必须继续使用 HTML search 和带连字符的 sort-by。 */
    @Test
    fun buildsEncodedHtmlRequestUrls() = runTest {
        /** 合法空表。 */
        val network = FakeTorrentSearchNetworkClient(textHandler = { "<table class='table'><tbody></tbody></table>" })
        /** 待测 Provider。 */
        val provider = NekoBtProvider(network)
        provider.search("A&B\n C\u0000", Category.ANIME)
        provider.getLatestTorrents(Category.ANIME)
        provider.getTopTorrents(Category.ANIME)
        assertEquals(listOf("https://nekobt.to/search?query=A%26B%20C", "https://nekobt.to/search?sort-by=latest", "https://nekobt.to/search?sort-by=seeders"), network.textUrls)
    }

    /** 正常列表必须从第三列嵌套 span 取 ownText，并按固定列映射。 */
    @Test
    fun mapsNestedTitleAndFixedColumns() = runTest {
        /** 唯一合法列表项。 */
        val torrent = NekoBtProvider(FakeTorrentSearchNetworkClient(textHandler = { readProviderFixture("provider/nekobt/search_normal.html") })).search("fixture", Category.ALL).single()
        assertEquals("Synthetic Neko", torrent.name)
        assertEquals(Category.ANIME, torrent.category)
        assertEquals("00112233445566778899aabbccddeeff00112233", torrent.infoHash)
        assertEquals("700 MB", torrent.size)
        assertEquals(12u, torrent.seeders)
        assertEquals(3u, torrent.peers)
        assertEquals("https://nekobt.to/torrent/42", torrent.descriptionPageUrl)
    }

    /** 真实 HTML 没有固定 selector、空表、缺名称或坏 magnet 都必须返回空列表。 */
    @Test
    fun returnsEmptyForNoMatchAndInvalidRequiredRows() = runTest {
        /** 正常行作为必填污染基线。 */
        val normal = readProviderFixture("provider/nekobt/search_normal.html")
        /** html 是当前逐项容错输入。 */
        listOf("<main>changed</main>", "<table class='table'><tbody></tbody></table>", normal.replace("Synthetic Neko", ""), normal.replace("00112233445566778899AABBCCDDEEFF00112233", "bad")).forEach { html ->
            assertTrue(NekoBtProvider(FakeTorrentSearchNetworkClient(textHandler = { html })).search("fixture", Category.ALL).isEmpty())
        }
    }

    /** 详情按 data-tip 与 Banner 精确读取，缺必填返回 null。 */
    @Test
    fun mapsDataTipDetailsAndRejectsMissingRequiredFields() = runTest {
        /** 正常详情 fixture。 */
        val normal = NekoBtProvider(FakeTorrentSearchNetworkClient(textHandler = { readProviderFixture("provider/nekobt/details_normal.html") }))
        /** 非空正常详情。 */
        val details = checkNotNull(normal.getDetails("https://nekobt.to/torrent/42"))
        assertEquals("Synthetic Neko Detail", details.name)
        assertEquals("Fixture Uploader", details.uploader)
        assertEquals("https://nekobt.to/api/v1/torrents/42", details.fileDownloadLink)
        assertEquals("https://nekobt.to/images/banner.jpg", details.posterUrl)
        /** 删除标题后应稳定返回 null。 */
        val missing = readProviderFixture("provider/nekobt/details_normal.html").replace("Synthetic Neko Detail", "")
        assertNull(NekoBtProvider(FakeTorrentSearchNetworkClient(textHandler = { missing })).getDetails("https://nekobt.to/torrent/42"))
    }

    /** 详情输入和所有可选产出都必须遵守同站 HTTPS/443/no-userinfo。 */
    @Test
    fun enforcesExactUrlPolicy() = runTest {
        /** 输入恶意地址时不允许联网。 */
        val rejectingNetwork = FakeTorrentSearchNetworkClient(textHandler = { error("不应联网") })
        /** 待测 Provider。 */
        val provider = NekoBtProvider(rejectingNetwork)
        /** url 是当前非法详情地址。 */
        listOf("http://nekobt.to/torrent/42", "https://evil.invalid/torrent/42", "https://u@nekobt.to/torrent/42", "https://nekobt.to:444/torrent/42").forEach { url -> assertTrue(runCatching { provider.getDetails(url) }.exceptionOrNull() is HttpDeniedException) }
        assertTrue(rejectingNetwork.textUrls.isEmpty())
        /** 污染可选链接但保留名称与 magnet。 */
        val malicious = readProviderFixture("provider/nekobt/details_normal.html").replace("/api/v1/torrents/42", "https://evil.invalid/file").replace("/images/banner.jpg", "https://u@nekobt.to/banner.jpg")
        /** 合法详情必须保留，只清空恶意可选字段。 */
        val degraded = checkNotNull(NekoBtProvider(FakeTorrentSearchNetworkClient(textHandler = { malicious })).getDetails("https://nekobt.to/torrent/42"))
        assertNull(degraded.fileDownloadLink)
        assertNull(degraded.posterUrl)
    }
}
