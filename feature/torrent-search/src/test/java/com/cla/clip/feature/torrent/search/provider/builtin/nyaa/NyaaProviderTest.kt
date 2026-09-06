package com.cla.clip.feature.torrent.search.provider.builtin.nyaa

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Nyaa 请求、共享解析接线、分类、URL 安全和取消契约测试。 */
class NyaaProviderTest {
    /** descriptor 必须保留安全、默认开启、完整分类和唯一备用 host。 */
    @Test
    fun descriptorMatchesReviewedMetadata() {
        /** 不联网的待测 Provider。 */
        val provider = NyaaProvider(FakeTorrentSearchNetworkClient())
        assertEquals("nyaasi", provider.descriptor.id.value)
        assertEquals(ProviderSafetyLevel.SAFE, provider.descriptor.safetyLevel)
        assertTrue(provider.descriptor.enabledByDefault)
        assertEquals(listOf("nyaa.iss.ink"), provider.alternateUrlDomains)
        assertEquals(setOf(Category.ANIME, Category.APPS, Category.BOOKS, Category.GAMES, Category.MUSIC, Category.SERIES), provider.descriptor.supportedCategories)
    }

    /** search/latest/top 必须分别编码查询并保持固定参数集合与顺序。 */
    @Test
    fun buildsIndependentEncodedRequestUrls() = runTest {
        /** 合法空结果表。 */
        val network = FakeTorrentSearchNetworkClient(textHandler = { "<table class='torrent-list'><tbody></tbody></table>" })
        /** 待测 Provider。 */
        val provider = NyaaProvider(network)
        provider.search("A&B\n C\u0000", Category.GAMES)
        provider.getLatestTorrents(Category.APPS)
        provider.getTopTorrents(Category.BOOKS)
        assertEquals(listOf("https://nyaa.si/?f=0&c=6_2&q=A%26B%20C", "https://nyaa.si/?c=6_1", "https://nyaa.si/?s=seeders&o=desc&c=3_0"), network.textUrls)
    }

    /** 每个完整分类 ID 都必须映射，评论链接不能被误作标题。 */
    @Test
    fun mapsAllCategoryIdsAndExcludesCommentLinks() = runTest {
        /** 各分类完整 ID，包含 6_2 游戏分支。 */
        val ids = linkedMapOf("1_4" to Category.ANIME, "2_2" to Category.MUSIC, "3_3" to Category.BOOKS, "4_4" to Category.SERIES, "5_2" to Category.OTHER, "6_1" to Category.APPS, "6_2" to Category.GAMES)
        /** 每行带 comments 链接在真实标题之前。 */
        val rows = ids.keys.mapIndexed { index, id -> nyaaRow(index, id) }.joinToString("")
        /** 返回多分类合成表。 */
        val provider = NyaaProvider(FakeTorrentSearchNetworkClient(textHandler = { "<table class='torrent-list'><tbody>$rows</tbody></table>" }))
        /** 解析后的有序结果。 */
        val results = provider.search("fixture", Category.ALL)
        assertEquals(ids.values.toList(), results.map { it.category })
        assertEquals(ids.keys.indices.map { "Synthetic $it" }, results.map { it.name })
    }

    /** 正常 fixture 必须保留可选同站链接；恶意可选链接只降级对应字段。 */
    @Test
    fun mapsNormalFixtureAndDropsUnsafeOptionalUrls() = runTest {
        /** 固定正常列表 fixture。 */
        val normal = NyaaProvider(FakeTorrentSearchNetworkClient(textHandler = { readProviderFixture("provider/nyaa/search_normal.html") })).search("fixture", Category.ALL).single()
        assertEquals("00112233445566778899aabbccddeeff00112233", normal.infoHash)
        assertEquals("Synthetic Nyaa", normal.name)
        assertEquals("https://nyaa.si/view/42", normal.descriptionPageUrl)
        assertEquals("https://nyaa.si/download/42.torrent", normal.fileDownloadLink)

        /** 将两个可选 URL 分别污染，必填 magnet 保持合法。 */
        val maliciousHtml = readProviderFixture("provider/nyaa/search_normal.html").replace("/view/42", "https://evil.invalid/view/42").replace("/download/42.torrent", "https://nyaa.si:444/download/42.torrent")
        /** 可选地址被清空但结果保留。 */
        val degraded = NyaaProvider(FakeTorrentSearchNetworkClient(textHandler = { maliciousHtml })).search("fixture", Category.ALL).single()
        assertNull(degraded.descriptionPageUrl)
        assertNull(degraded.fileDownloadLink)
    }

    /** 空表、无匹配结构与缺名称或坏 magnet 都按 HTML 来源的逐行容错返回空。 */
    @Test
    fun returnsEmptyForEmptyMalformedAndMissingRequiredRows() = runTest {
        /** 各页面分别覆盖空、结构漂移、缺名称和非法 magnet。 */
        val pages = listOf(
            "<table class='torrent-list'><tbody></tbody></table>",
            "<html><body><div>changed layout</div></body></html>",
            "<table class='torrent-list'><tbody>${nyaaRow(1, "1_0").replace("Synthetic 1", "")}</tbody></table>",
            "<table class='torrent-list'><tbody>${nyaaRow(1, "1_0").replace("00112233445566778899aabbccddeeff00112233", "bad")}</tbody></table>",
        )
        /** html 是当前独立容错输入。 */
        pages.forEach { html -> assertTrue(NyaaProvider(FakeTorrentSearchNetworkClient(textHandler = { html })).search("fixture", Category.ALL).isEmpty()) }
    }

    /** 备用 host 详情允许，子域、userinfo、明文和异常端口必须在联网前拒绝。 */
    @Test
    fun detailsUsesExactHostPolicyBeforeNetwork() = runTest {
        /** 允许备用 host 后返回缺必填的中性页面。 */
        val network = FakeTorrentSearchNetworkClient(textHandler = { "<div class='container'><div></div></div>" })
        /** 待测 Provider。 */
        val provider = NyaaProvider(network)
        assertNull(provider.getDetails("https://nyaa.iss.ink/view/42"))
        /** 每个地址都不能进入网络 fake。 */
        val invalid = listOf("http://nyaa.si/view/42", "https://user@nyaa.si/view/42", "https://nyaa.si:444/view/42", "https://sub.nyaa.si/view/42", "https://sukebei.nyaa.si/view/42")
        /** url 是当前恶意输入。 */
        invalid.forEach { url -> assertTrue(runCatching { provider.getDetails(url) }.exceptionOrNull() is HttpDeniedException) }
        assertEquals(listOf("https://nyaa.iss.ink/view/42"), network.textUrls)
    }

    /** 详情第二分类 6_2 优先，并能从 card 外找到 magnet 与下载按钮。 */
    @Test
    fun detailsPrefersGameSubcategoryAndFindsButtonsOutsideCard() = runTest {
        /** fixture 刻意将动作链接放在信息 card 之后。 */
        val provider = NyaaProvider(FakeTorrentSearchNetworkClient(textHandler = { readProviderFixture("provider/nyaa/details_normal.html") }))
        /** 完整详情结果。 */
        val details = checkNotNull(provider.getDetails("https://nyaa.si/view/42"))
        assertEquals(Category.GAMES, details.category)
        assertEquals("https://nyaa.si/download/42.torrent", details.fileDownloadLink)
        assertTrue(details.magnetUri.startsWith("magnet:?xt=urn:btih:"))
        assertEquals("<p>Untrusted <strong>HTML</strong></p>", details.description)
    }

    /** 超大 epoch、非法计数和坏行只降级或跳过，取消必须原样传播。 */
    @Test
    fun handlesOverflowInvalidCountsAndCancellation() = runTest {
        /** 正常 fixture 被污染为溢出 epoch 与非法计数。 */
        val degradedHtml = readProviderFixture("provider/nyaa/search_normal.html").replace("1709510400", Long.MAX_VALUE.toString()).replace(">12<", ">-1<").replace(">3<", ">nope<")
        /** 合法必填仍生成结果。 */
        val degraded = NyaaProvider(FakeTorrentSearchNetworkClient(textHandler = { degradedHtml })).search("fixture", Category.ALL).single()
        assertNull(degraded.uploadEpochMillis)
        assertNull(degraded.seeders)
        assertNull(degraded.peers)
        /** 网络取消不得被转为空列表。 */
        val cancelled = NyaaProvider(FakeTorrentSearchNetworkClient(textHandler = { throw CancellationException() }))
        assertTrue(runCatching { cancelled.search("fixture", Category.ALL) }.exceptionOrNull() is CancellationException)
    }
}

/** 构造一条 Nyaa 族真实列顺序的中性结果行。 */
private fun nyaaRow(index: Int, categoryId: String): String = """<tr><td><a href='/?c=$categoryId'>C</a></td><td><a class='comments' href='/view/$index#comments'>9</a><a href='/view/$index'>Synthetic $index</a></td><td><a href='/download/$index.torrent'>D</a><a href='magnet:?xt=urn:btih:00112233445566778899aabbccddeeff00112233'>M</a></td><td>1 GiB</td><td data-timestamp='1709510400'></td><td>12</td><td>3</td></tr>"""
