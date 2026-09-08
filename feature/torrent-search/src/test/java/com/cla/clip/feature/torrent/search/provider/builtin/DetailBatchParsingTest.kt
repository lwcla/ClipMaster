package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.builtin.testing.DetailBatchSite
import com.cla.clip.feature.torrent.search.provider.builtin.testing.DetailBatchSite.Companion.HASH
import com.cla.clip.feature.torrent.search.provider.builtin.testing.DetailBatchSite.Companion.MAGNET
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 04E4 三站独立 fixture 的公开字段、安全与降级契约。 */
@RunWith(Parameterized::class)
class DetailBatchParsingTest(
    /** 当前站点参数，不改变其他测试的全局状态。 */
    private val site: DetailBatchSite,
) {
    /** 正常详情经过真实 parser，保持身份、时间、类别和媒体边界。 */
    @Test
    fun detailsPreserveIdentityAndOptionalMetadata() = runBlocking {
        /** client 仅返回本站详情结构。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { site.detailsHtml() })
        /** details 必须存在，不能以可空断言掩盖丢条目。 */
        val details = requireNotNull(site.provider(client).getDetails(site.url()))
        assertEquals(HASH, details.infoHash)
        assertEquals(MAGNET, details.magnetUri)
        assertEquals("Sample Alpha", details.name)
        assertEquals("1.5 GB", details.size)
        assertEquals(if (site == DetailBatchSite.ZERO) 1709214330000L else 1709164800000L, details.uploadEpochMillis)
        assertEquals(if (site == DetailBatchSite.ZERO) Category.PORN else Category.GAMES, details.category)
        assertNull(details.fileDownloadLink)
        if (site != DetailBatchSite.ZERO) {
            assertEquals(12u, details.seeders)
            assertEquals(3u, details.peers)
            assertEquals("https://${site.host}/images/sample.jpg", details.posterUrl)
            assertTrue(requireNotNull(details.description).contains("<strong>description</strong>"))
        }
    }

    /** 列表必须携带详情中的合法磁力，保护 0Magnet 上游遗漏的回归。 */
    @Test
    fun resultsPreserveMagnetAndSourceOrder() = runBlocking {
        /** client 为两条草稿分别返回不同身份的详情。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = {
            /** url 只在测试中区分固定列表与详情路径。 */
            url -> when (url) {
                site.url() -> site.detailsHtml()
                site.url("sample-beta") -> site.betaHtml()
                else -> site.listHtml()
            }
        })
        /** results 来自真实列表、详情和合并链路。 */
        val results = site.provider(client).search("fixture", Category.ALL)
        assertEquals(listOf("Sample Alpha", "Sample Beta"), results.map { it.name })
        assertEquals(MAGNET, results.first().magnetUri)
        assertEquals(site.url(), results.first().descriptionPageUrl)
        assertEquals(HASH, results.first().infoHash)
        assertNull(results.first().fileDownloadLink)
        assertEquals(3, client.textUrls.size)
    }

    /** 空页面、selector 漂移和无有效 href 均为空，不能触发详情。 */
    @Test
    fun emptyAndMalformedListsDoNotFetchDetails() = runBlocking {
        /** documents 分别模拟空、错误结构和没有链接的真实容器。 */
        val documents = listOf("", "<div>changed</div>", "<table class='file-list'><tbody><tr><td><a>Missing</a></td></tr></tbody></table>")
        /** document 是当前单独验证的坏列表。 */
        for (document in documents) {
            /** client 不允许坏草稿触发第二次请求。 */
            val client = FakeTorrentSearchNetworkClient(textHandler = { document })
            assertTrue(site.provider(client).search("fixture", Category.ALL).isEmpty())
            assertEquals(1, client.textUrls.size)
        }
    }

    /** 名称或磁力缺失、错误身份必须拒绝详情，而不是抛出正文异常。 */
    @Test
    fun invalidRequiredDetailsAreDropped() = runBlocking {
        /** normal 是本站详情基线。 */
        val normal = site.detailsHtml()
        /** documents 覆盖无 DOM、空标题、坏 hash、缺磁力。 */
        val documents = listOf("", "<p>changed</p>", normal.replace("Sample Alpha", ""), normal.replace(HASH, "invalid"), normal.replace(MAGNET, ""))
        /** document 是当前独立坏详情。 */
        for (document in documents) {
            assertNull(site.provider(FakeTorrentSearchNetworkClient(textHandler = { document })).getDetails(site.url()))
        }
    }

    /** 一个坏详情只丢该条，后面的有效详情继续保留。 */
    @Test
    fun malformedDetailDoesNotDiscardValidSibling() = runBlocking {
        /** client 让 Alpha 结构失效而 Beta 合法。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = {
            /** url 是当前中性请求。 */
            url -> when (url) {
                site.url() -> "<p>missing</p>"
                site.url("sample-beta") -> site.betaHtml()
                else -> site.listHtml()
            }
        })
        assertEquals(listOf("Sample Beta"), site.provider(client).search("fixture", Category.ALL).map { it.name })
    }

    /** 坏可选日期与人数不应损坏合法身份。 */
    @Test
    fun invalidOptionalNumbersAndDateBecomeNull() = runBlocking {
        /** malformed 只破坏可选元数据，保留名称和磁力。 */
        val malformed = site.detailsHtml()
            .replace("29/02/2024", "31/02/2024")
            .replace("2024-02-29 13:45:30", "2024-02-30 25:61:00")
            .replace(">12<", ">-1<")
            .replace(">3<", ">4294967296<")
        /** details 必须保留合法身份。 */
        val details = requireNotNull(site.provider(FakeTorrentSearchNetworkClient(textHandler = { malformed })).getDetails(site.url()))
        assertNull(details.uploadEpochMillis)
        assertNull(details.seeders)
        assertNull(details.peers)
    }

    /** 恶意 authority 在详情入口及列表草稿阶段均拒绝，且不产生对应请求。 */
    @Test
    fun unsafeAuthoritiesAreRejectedBeforeNetwork() = runBlocking {
        /** unsafeUrls 包含协议、伪主机、用户名、密码、编码用户名及非标准端口。 */
        val unsafeUrls = listOf("http://${site.host}/a", "https://${site.host}.evil.invalid/a", "https://user@${site.host}/a", "https://:pass@${site.host}/a", "https://%75ser@${site.host}/a", "https://${site.host}:8443/a")
        /** unsafe 是当前独立安全边界样本。 */
        for (unsafe in unsafeUrls) {
            /** detailClient 证明拒绝发生在网络之前。 */
            val detailClient = FakeTorrentSearchNetworkClient()
            try {
                site.provider(detailClient).getDetails(unsafe)
                fail("恶意详情地址必须拒绝")
            // 下划线参数表示预期的安全拒绝，不读取异常详情。
            } catch (_: HttpDeniedException) {
                // 仅接受明确的安全拒绝，不接受其他异常伪装成功。
            }
            assertTrue(detailClient.textUrls.isEmpty())
            /** listHtml 只替换第一条地址，第二条仍可正常补详情。 */
            val listHtml = site.listHtml().replace("/details/sample-alpha", unsafe)
            /** listClient 不应收到任何 unsafe 请求。 */
            val listClient = FakeTorrentSearchNetworkClient(textHandler = {
                /** url 是列表或剩余合法 Beta 详情。 */
                url -> if (url == site.url("sample-beta")) site.betaHtml() else listHtml
            })
            assertEquals(listOf("Sample Beta"), site.provider(listClient).search("fixture", Category.ALL).map { it.name })
            assertEquals(2, listClient.textUrls.size)
            assertFalse(listClient.textUrls.contains(unsafe))
        }
    }

    /** 非法可选海报只置空，不把合法详情丢弃。 */
    @Test
    fun optionalPosterCannotEscapeSource() = runBlocking {
        /** unsafePosters 独立覆盖精确主机、协议和凭据边界；Zero 没有海报仍须保持空。 */
        val unsafePosters = listOf("https://evil.invalid/p.jpg", "http://${site.host}/p.jpg", "https://user@${site.host}/p.jpg", "https://${site.host}:8443/p.jpg")
        /** poster 是当前待拒绝的媒体候选。 */
        for (poster in unsafePosters) {
            /** html 保持其他元数据有效。 */
            val html = site.detailsHtml().replace("/images/sample.jpg", poster)
            assertNull(requireNotNull(site.provider(FakeTorrentSearchNetworkClient(textHandler = { html })).getDetails(site.url())).posterUrl)
        }
    }

    /** JUnit 将每个来源作为独立参数运行，避免共享状态。 */
    companion object {
        /** 固定三站参数，与生产注册表无耦合。 */
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun sites(): Collection<Array<Any>> = DetailBatchSite.parameters()
    }
}
