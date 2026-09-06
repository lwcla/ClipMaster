package com.cla.clip.feature.torrent.search.provider.builtin.sukebei

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Sukebei 独立请求、安全元数据及 Nyaa 族解析接线测试。 */
class SukebeiProviderTest {
    /** 本源必须固定 NSFW、默认关闭、单一 host 和 PORN 分类。 */
    @Test
    fun descriptorUsesReviewedNsfwMetadata() {
        /** 不联网的待测 Provider。 */
        val provider = SukebeiProvider(FakeTorrentSearchNetworkClient())
        /** 固定元数据由公开 descriptor 读取。 */
        val descriptor = provider.descriptor
        assertEquals("sukebeinyaa", descriptor.id.value)
        assertEquals(ProviderSafetyLevel.NSFW, descriptor.safetyLevel)
        assertFalse(descriptor.enabledByDefault)
        assertEquals(setOf(Category.PORN), descriptor.supportedCategories)
        assertTrue(provider.alternateUrlDomains.isEmpty())
    }

    /** search 有固定 f/c/q，latest 无参数，top 仅 s/o，且三次独立联网。 */
    @Test
    fun buildsSiteSpecificIndependentUrls() = runTest {
        /** 合法空结果表。 */
        val network = FakeTorrentSearchNetworkClient(textHandler = { "<table class='torrent-list'><tbody></tbody></table>" })
        /** 待测 Provider。 */
        val provider = SukebeiProvider(network)
        provider.search("A&B\n C\u0000", Category.ALL)
        provider.getLatestTorrents(Category.PORN)
        provider.getTopTorrents(Category.PORN)
        assertEquals(listOf("https://sukebei.nyaa.si/?f=0&c=0_0&q=A%26B%20C", "https://sukebei.nyaa.si/", "https://sukebei.nyaa.si/?s=seeders&o=desc"), network.textUrls)
    }

    /** 网页类别即使伪装为安全分类，结果与详情仍必须固定 PORN。 */
    @Test
    fun forcesPornCategoryAndFindsButtonsOutsideDifferentCard() = runTest {
        /** 列表与详情采用独立 fixture，确保不是只测共享 parser。 */
        val network = FakeTorrentSearchNetworkClient(textHandler = { url -> if (url.contains("/view/")) readProviderFixture("provider/sukebei/details_normal.html") else readProviderFixture("provider/sukebei/search_normal.html") })
        /** 待测 Provider。 */
        val provider = SukebeiProvider(network)
        assertEquals(Category.PORN, provider.search("fixture", Category.ALL).single().category)
        /** 动作按钮在 div.container > div.panel 外部。 */
        val details = checkNotNull(provider.getDetails("https://sukebei.nyaa.si/view/42"))
        assertEquals(Category.PORN, details.category)
        assertEquals("https://sukebei.nyaa.si/download/42.torrent", details.fileDownloadLink)
        assertTrue(details.magnetUri.startsWith("magnet:?xt=urn:btih:"))
    }

    /** 空/漂移 DOM、缺名称和非法 magnet 必须逐条容错，不得伪造结果。 */
    @Test
    fun returnsEmptyForEmptyMalformedAndInvalidRequiredRows() = runTest {
        /** 正常行作为必填字段污染基线。 */
        val row = readProviderFixture("provider/sukebei/search_normal.html")
        /** html 是当前独立容错输入。 */
        listOf("<table class='torrent-list'><tbody></tbody></table>", "<main>changed</main>", row.replace("Synthetic Sukebei", ""), row.replace("00112233445566778899AABBCCDDEEFF00112233", "bad")).forEach { html ->
            assertTrue(SukebeiProvider(FakeTorrentSearchNetworkClient(textHandler = { html })).search("fixture", Category.ALL).isEmpty())
        }
    }

    /** Nyaa 主站、子域、明文、userinfo、异常端口都不能扩大本源详情白名单。 */
    @Test
    fun detailsRejectsEveryNonExactOriginBeforeNetwork() = runTest {
        /** 任意回调都表示输入校验失败。 */
        val network = FakeTorrentSearchNetworkClient(textHandler = { error("不应联网") })
        /** 待测 Provider。 */
        val provider = SukebeiProvider(network)
        /** url 是当前恶意输入。 */
        listOf("https://nyaa.si/view/42", "https://sub.sukebei.nyaa.si/view/42", "http://sukebei.nyaa.si/view/42", "https://u@sukebei.nyaa.si/view/42", "https://sukebei.nyaa.si:444/view/42").forEach { url ->
            assertTrue(runCatching { provider.getDetails(url) }.exceptionOrNull() is HttpDeniedException)
        }
        assertTrue(network.textUrls.isEmpty())
    }

    /** 列表与详情的恶意可选链接只降级字段，详情缺名称或坏 magnet 返回 null。 */
    @Test
    fun dropsUnsafeOutputsAndRejectsMissingDetailsRequirements() = runTest {
        /** maliciousList 污染详情与下载地址但保留合法名称和 magnet。 */
        val maliciousList = readProviderFixture("provider/sukebei/search_normal.html")
            .replace("/view/42", "https://evil.invalid/view/42")
            .replace("/download/42.torrent", "https://u@sukebei.nyaa.si/download/42.torrent")
        /** listResult 必须保留条目并清空两个不可信可选字段。 */
        val listResult = SukebeiProvider(
            FakeTorrentSearchNetworkClient(textHandler = { maliciousList }),
        ).search("fixture", Category.ALL).single()
        assertNull(listResult.descriptionPageUrl)
        assertNull(listResult.fileDownloadLink)

        /** normalDetails 是必填污染的固定基线。 */
        val normalDetails = readProviderFixture("provider/sukebei/details_normal.html")
        /** badPages 分别删除名称与破坏 btih。 */
        val badPages = listOf(
            normalDetails.replace("Synthetic Sukebei Detail", ""),
            normalDetails.replace("00112233445566778899AABBCCDDEEFF00112233", "bad"),
        )
        /** html 是当前详情必填失败输入。 */
        badPages.forEach { html ->
            /** detailsProvider 只返回当前合成页面。 */
            val detailsProvider = SukebeiProvider(
                FakeTorrentSearchNetworkClient(textHandler = { html }),
            )
            assertNull(detailsProvider.getDetails("https://sukebei.nyaa.si/view/42"))
        }
    }
}
