package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import com.cla.clip.feature.torrent.search.provider.builtin.torrentdownload.TorrentDownloadProvider
import com.cla.clip.feature.torrent.search.provider.builtin.torrentdownloads.TorrentDownloadsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.torrentz.TorrentzProvider
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 04E6 三站的公开请求、解析、安全、并发与失败契约。 */
class TwelfthBatchProviderTest {
    /** 六份合成 fixture 共用的合法 40 位 infoHash。 */
    private val hash = "0123456789abcdef0123456789abcdef01234567"
    /** 相对日期固定在 2024-01-04 UTC 零点，避免设备时间影响结果。 */
    private val now = 1704326400000L

    /** 三站描述符必须采用 manifest 的 NSFW/default-off 审计，而不是上游宽松值。 */
    @Test
    fun metadataMatchesLocalManifestAudit() {
        /** client 只满足构造依赖，不触发网络。 */
        val client = FakeTorrentSearchNetworkClient()
        /** downloadDescriptor 固定成人能力和两个审计 host 中的主站。 */
        val downloadDescriptor = TorrentDownloadProvider(client, { now }).descriptor
        assertEquals("torrentdownloadinfo", downloadDescriptor.id.value)
        assertEquals(ProviderSafetyLevel.NSFW, downloadDescriptor.safetyLevel)
        assertFalse(downloadDescriptor.enabledByDefault)
        assertTrue(Category.PORN in downloadDescriptor.supportedCategories)
        /** downloadsDescriptor 保留 Cloudflare 元数据但默认关闭。 */
        val downloadsDescriptor = TorrentDownloadsProvider(client).descriptor
        assertEquals("torrentdownloads", downloadsDescriptor.id.value)
        assertEquals(ProviderSafetyLevel.NSFW, downloadsDescriptor.safetyLevel)
        assertTrue(downloadsDescriptor.isCloudflareProtected)
        assertFalse(downloadsDescriptor.enabledByDefault)
        /** torrentzDescriptor 固定成人能力和默认关闭。 */
        val torrentzDescriptor = TorrentzProvider(client).descriptor
        assertEquals("torrentz", torrentzDescriptor.id.value)
        assertEquals(ProviderSafetyLevel.NSFW, torrentzDescriptor.safetyLevel)
        assertFalse(torrentzDescriptor.enabledByDefault)
        assertTrue(Category.PORN in torrentzDescriptor.supportedCategories)
    }

    /** 三站搜索必须结构化编码查询并保持各自分类协议。 */
    @Test
    fun searchUrlsEncodeQueryAndMapCategories() = runBlocking {
        /** downloadClient 返回空表并记录忽略分类的 URL。 */
        val downloadClient = FakeTorrentSearchNetworkClient(textHandler = { "<html></html>" })
        TorrentDownloadProvider(downloadClient, { now }).search("  fixture /?\u0000  ", Category.PORN)
        assertEquals(listOf("https://torrentdownload.info/search?q=fixture%20%2F%3F"), downloadClient.textUrls)
        /** downloadsClient 返回无列表容器页面并记录数字分类。 */
        val downloadsClient = FakeTorrentSearchNetworkClient(textHandler = { "<html></html>" })
        TorrentDownloadsProvider(downloadsClient).search("  fixture /?\u0000  ", Category.MUSIC)
        assertEquals(
            listOf("https://torrentdownloads.pro/search/?s_cat=5&search=fixture%20%2F%3F"),
            downloadsClient.textUrls,
        )
        /** torrentzClient 返回空结果并记录可选分类参数。 */
        val torrentzClient = FakeTorrentSearchNetworkClient(textHandler = { "<html></html>" })
        TorrentzProvider(torrentzClient).search("  fixture /?\u0000  ", Category.PORN)
        assertEquals(
            listOf("https://torrentz2.nz/search?q=fixture%20%2F%3F&category=10"),
            torrentzClient.textUrls,
        )
    }

    /** 三站 latest/top 必须保持固定路径与类别回退。 */
    @Test
    fun browseUrlsKeepProviderSpecificPaths() = runBlocking {
        /** downloadClient 验证浏览入口忽略分类。 */
        val downloadClient = FakeTorrentSearchNetworkClient(textHandler = { "<html></html>" })
        /** downloadProvider 连续调用两个独立浏览入口。 */
        val downloadProvider = TorrentDownloadProvider(downloadClient, { now })
        downloadProvider.getLatestTorrents(Category.BOOKS)
        downloadProvider.getTopTorrents(Category.PORN)
        assertEquals(
            listOf("https://torrentdownload.info/latest", "https://torrentdownload.info/top"),
            downloadClient.textUrls,
        )
        /** downloadsClient 验证分类页与 ALL 回退页。 */
        val downloadsClient = FakeTorrentSearchNetworkClient(textHandler = { "<html></html>" })
        /** downloadsProvider 复用同一无状态入口。 */
        val downloadsProvider = TorrentDownloadsProvider(downloadsClient)
        downloadsProvider.getLatestTorrents(Category.BOOKS)
        downloadsProvider.getTopTorrents(Category.ALL)
        assertEquals(
            listOf(
                "https://torrentdownloads.pro/view/today/Books.html",
                "https://torrentdownloads.pro/most-seeded",
            ),
            downloadsClient.textUrls,
        )
        /** torrentzClient 验证分类 query 与无 query 热门页。 */
        val torrentzClient = FakeTorrentSearchNetworkClient(textHandler = { "<html></html>" })
        /** torrentzProvider 连续执行分类最新和全部热门。 */
        val torrentzProvider = TorrentzProvider(torrentzClient)
        torrentzProvider.getLatestTorrents(Category.BOOKS)
        torrentzProvider.getTopTorrents(Category.ALL)
        assertEquals(
            listOf("https://torrentz2.nz/latest?category=9", "https://torrentz2.nz/trending"),
            torrentzClient.textUrls,
        )
    }

    /** TorrentDownload 列表必须从详情路径提取合法 hash 并映射相对日期。 */
    @Test
    fun torrentDownloadParsesListWithoutExtraDetailRequest() = runBlocking {
        /** client 只返回列表 fixture。 */
        val client = FakeTorrentSearchNetworkClient(
            textHandler = { readProviderFixture("provider/torrentdownload/search_normal.html") },
        )
        /** item 是唯一合法列表行。 */
        val item = TorrentDownloadProvider(client, { now }).search("x", Category.ALL).single()
        assertEquals(hash, item.infoHash)
        assertEquals("Fixture Download", item.name)
        assertEquals(1234u, item.seeders)
        assertEquals(56u, item.peers)
        assertEquals(now - 2L * 24L * 60L * 60L * 1000L, item.uploadEpochMillis)
        assertEquals(Category.MOVIES, item.category)
        assertEquals("https://torrentdownload.info/torrent/${hash.uppercase()}/fixture", item.descriptionPageUrl)
        assertEquals(1, client.textUrls.size)
    }

    /** TorrentDownload 详情必须映射严格日期、合法 magnet 与受限下载地址。 */
    @Test
    fun torrentDownloadParsesDetailsAndDegradesUnsafeFile() = runBlocking {
        /** normal 是固定详情页。 */
        val normal = readProviderFixture("provider/torrentdownload/details_normal.html")
        /** normalDetails 验证完整领域映射。 */
        val normalDetails = requireNotNull(
            TorrentDownloadProvider(FakeTorrentSearchNetworkClient(textHandler = { normal }), { now })
                .getDetails("https://torrentdownload.info/torrent/$hash/fixture"),
        )
        assertEquals(hash, normalDetails.infoHash)
        assertEquals("Fixture Download Details", normalDetails.name)
        assertEquals(1704153600000L, normalDetails.uploadEpochMillis)
        assertEquals(Category.MOVIES, normalDetails.category)
        assertEquals("https://itorrent.net/torrent/${hash.uppercase()}.torrent", normalDetails.fileDownloadLink)
        /** unsafeDetails 只把跨站可选文件降级，不丢合法详情。 */
        val unsafeDetails = requireNotNull(
            TorrentDownloadProvider(
                FakeTorrentSearchNetworkClient(textHandler = { normal.replace("https://itorrent.net/", "https://evil.example/") }),
                { now },
            ).getDetails("https://torrentdownload.info/torrent/$hash/fixture"),
        )
        assertNull(unsafeDetails.fileDownloadLink)
    }

    /** TorrentDownloads 列表必须并发补详情并优先采用详情字段。 */
    @Test
    fun torrentDownloadsMergesListAndDetails() = runBlocking {
        /** client 按列表或详情 URL 返回合成页面。 */
        val client = FakeTorrentSearchNetworkClient(
            // url 区分列表与逐条详情请求。
            textHandler = { url ->
                if (url.contains("/torrent/")) readProviderFixture("provider/torrentdownloads/details_normal.html")
                else readProviderFixture("provider/torrentdownloads/search_normal.html")
            },
        )
        /** item 使用详情名称、大小、统计、日期和磁力。 */
        val item = TorrentDownloadsProvider(client).search("x", Category.ALL).single()
        assertEquals(hash, item.infoHash)
        assertEquals("Fixture Downloads Details", item.name)
        assertEquals("800 MB", item.size)
        assertEquals(9u, item.seeders)
        assertEquals(4u, item.peers)
        assertEquals(1704164645000L, item.uploadEpochMillis)
        assertEquals(Category.MOVIES, item.category)
        assertEquals("https://torrentdownloads.pro/torrent/fixture", item.descriptionPageUrl)
        assertEquals("magnet:?xt=urn:btih:${hash.uppercase()}", item.magnetUri)
    }

    /** Torrentz 列表必须通过详情补齐完整字段与同站下载地址。 */
    @Test
    fun torrentzMergesDetailsAndMapsCategory() = runBlocking {
        /** client 按 URL 类型返回列表或详情 fixture。 */
        val client = FakeTorrentSearchNetworkClient(
            // url 区分列表与逐条详情请求。
            textHandler = { url ->
                if (url.contains("/torrent/")) readProviderFixture("provider/torrentz/details_normal.html")
                else readProviderFixture("provider/torrentz/search_normal.html")
            },
        )
        /** item 完全来源于详情解析与列表详情 URL。 */
        val item = TorrentzProvider(client).search("x", Category.ALL).single()
        assertEquals(hash, item.infoHash)
        assertEquals("Fixture Torrentz Details", item.name)
        assertEquals("900 MB", item.size)
        assertEquals(11u, item.seeders)
        assertEquals(5u, item.peers)
        assertEquals(1704153600000L, item.uploadEpochMillis)
        assertEquals(Category.MOVIES, item.category)
        assertEquals("https://torrentz2.nz/dowload/torrent/fixture.torrent", item.fileDownloadLink)
    }

    /** 三站外部详情 authority 必须在网络前拒绝且异常不回显输入。 */
    @Test
    fun detailsRejectUnsafeAuthoritiesBeforeNetwork() = runBlocking {
        /** clients 分别证明三个入口都未发起网络。 */
        val clients = List(3) { FakeTorrentSearchNetworkClient() }
        /** checks 是三站对跨站 URL 的详情调用。 */
        val checks: List<suspend () -> Unit> = listOf(
            /** TorrentDownload 非法详情调用。 */
            { TorrentDownloadProvider(clients[0], { now }).getDetails("https://evil.example/a") },
            /** TorrentDownloads 非法详情调用。 */
            { TorrentDownloadsProvider(clients[1]).getDetails("https://user@torrentdownloads.pro/a") },
            /** Torrentz 非法详情调用。 */
            { TorrentzProvider(clients[2]).getDetails("https://torrentz2.nz:8443/a") },
        )
        /** check 是当前不可信地址调用。 */
        for (check in checks) {
            try {
                check()
                fail("非法 authority 必须拒绝")
            /** failure 只验证稳定低敏类型。 */
            } catch (failure: HttpDeniedException) {
                assertNull(failure.message)
            }
        }
        /** client 是当前站点的调用记录。 */
        clients.forEach { client -> assertTrue(client.textUrls.isEmpty()) }
    }

    /** 详情补全来源必须并发启动，并在逆序完成后保持来源顺序。 */
    @Test
    fun detailProvidersRunConcurrentlyAndPreserveSourceOrder() = runBlocking {
        /** torrentzList 含两个来源顺序固定的详情草稿。 */
        val torrentzList = readProviderFixture("provider/torrentz/search_normal.html")
            .replace("</div>", "<dl><dt><a href='/torrent/second'>Second Torrentz</a></dt></dl></div>")
        /** firstStarted 与 secondStarted 证明两个详情请求已同时启动。 */
        val firstStarted = CompletableDeferred<Unit>()
        /** secondStarted 控制第一项等待第二项启动。 */
        val secondStarted = CompletableDeferred<Unit>()
        /** releaseFirst 让第二项先完成后再释放第一项。 */
        val releaseFirst = CompletableDeferred<Unit>()
        /** client 根据详情地址协调逆序完成。 */
        val client = FakeTorrentSearchNetworkClient(
            // url 决定返回列表、第一详情或第二详情。
            textHandler = { url ->
                when {
                    url.contains("/torrent/fixture") -> {
                        firstStarted.complete(Unit)
                        withTimeout(1_000L) { secondStarted.await() }
                        releaseFirst.await()
                        readProviderFixture("provider/torrentz/details_normal.html")
                    }
                    url.contains("/torrent/second") -> {
                        secondStarted.complete(Unit)
                        withTimeout(1_000L) { firstStarted.await() }
                        releaseFirst.complete(Unit)
                        readProviderFixture("provider/torrentz/details_normal.html")
                            .replace("Fixture Torrentz Details", "Second Torrentz Details")
                            .replace(hash.uppercase(), "1123456789ABCDEF0123456789ABCDEF01234567")
                    }
                    else -> torrentzList
                }
            },
        )
        /** names 必须按列表来源顺序而不是网络完成顺序输出。 */
        val names = TorrentzProvider(client).search("x", Category.ALL).map { item -> item.name }
        assertEquals(listOf("Fixture Torrentz Details", "Second Torrentz Details"), names)
    }

    /** 空详情只跳过当前草稿，真实传输失败必须向上游传播。 */
    @Test
    fun detailProvidersSkipEmptyDetailsAndPropagateTransportFailure() = runBlocking {
        /** list 含一个会返回空详情结构的 Torrentz 草稿。 */
        val list = readProviderFixture("provider/torrentz/search_normal.html")
        /** emptyClient 返回合法列表和无必填字段详情。 */
        val emptyClient = FakeTorrentSearchNetworkClient(
            // url 区分列表与空详情。
            textHandler = { url -> if (url.contains("/torrent/")) "<html></html>" else list },
        )
        assertTrue(TorrentzProvider(emptyClient).search("x", Category.ALL).isEmpty())
        /** failingClient 在详情阶段抛出真实传输错误。 */
        val failingClient = FakeTorrentSearchNetworkClient(
            // url 只允许列表成功，详情必须失败。
            textHandler = { url -> if (url.contains("/torrent/")) throw IOException() else list },
        )
        try {
            TorrentzProvider(failingClient).search("x", Category.ALL)
            fail("详情传输失败必须传播")
        /** failure 必须保留网络异常类型供协调器分类。 */
        } catch (_: IOException) {
            // 预期分支无需检查异常正文，避免建立不稳定或敏感契约。
        }
    }
}
