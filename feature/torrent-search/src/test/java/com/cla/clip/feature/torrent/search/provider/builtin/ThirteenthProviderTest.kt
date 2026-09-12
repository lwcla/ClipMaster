package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import com.cla.clip.feature.torrent.search.provider.builtin.xxxclub.XXXClubProvider
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

/** 04E7 XXXClub 的公开请求、解析、安全与结构化并发契约。 */
class ThirteenthProviderTest {
    /** 合成 fixture 共用的合法 40 位 infoHash。 */
    private val hash = "0123456789abcdef0123456789abcdef01234567"

    /** 描述符必须采用 manifest 的 NSFW/default-off 审计并暴露完整能力。 */
    @Test
    fun metadataAndRequestUrlsMatchFixedSnapshot() = runBlocking {
        /** client 返回合法空页并记录四个公开入口的 URL。 */
        val client = FakeTorrentSearchNetworkClient(textHandler = { "<html></html>" })
        /** provider 是本批唯一固定来源。 */
        val provider = XXXClubProvider(client)
        assertEquals("xxxclub", provider.descriptor.id.value)
        assertEquals("XXXClub", provider.descriptor.displayName)
        assertEquals("https://xxxclub.to", provider.descriptor.baseUrl)
        assertEquals(ProviderSafetyLevel.NSFW, provider.descriptor.safetyLevel)
        assertFalse(provider.descriptor.enabledByDefault)
        assertEquals(setOf(Category.PORN), provider.descriptor.supportedCategories)

        provider.search("  fixture /?\u0000  ", Category.PORN)
        provider.getLatestTorrents(Category.ALL)
        provider.getTopTorrents(Category.BOOKS)
        assertEquals(
            listOf(
                "https://xxxclub.to/torrents/search/all/fixture%20%2F%3F",
                "https://xxxclub.to/torrents/browse/all",
                "https://xxxclub.to/torrents/top100",
            ),
            client.textUrls,
        )
    }

    /** 搜索必须通过同站详情补齐稳定身份并保留列表展示字段。 */
    @Test
    fun searchMergesListAndDetails() = runBlocking {
        /** client 按列表或详情地址返回中性 fixture。 */
        val client = FakeTorrentSearchNetworkClient(
            // url 区分列表与详情响应。
            textHandler = { url ->
                if (url.contains("/torrents/details/")) {
                    readProviderFixture("provider/xxxclub/details_normal.html")
                } else {
                    readProviderFixture("provider/xxxclub/search_normal.html")
                }
            },
        )
        /** item 是唯一合法合并结果。 */
        val item = XXXClubProvider(client).search("x", Category.ALL).single()
        assertEquals(hash, item.infoHash)
        assertEquals("Fixture XXXClub", item.name)
        assertEquals("1.25 GB", item.size)
        assertEquals(17u, item.seeders)
        assertEquals(6u, item.peers)
        assertEquals(1717359330000L, item.uploadEpochMillis)
        assertEquals(Category.PORN, item.category)
        assertEquals("https://xxxclub.to/torrents/details/fixture", item.descriptionPageUrl)
        assertEquals("magnet:?xt=urn:btih:${hash.uppercase()}", item.magnetUri)
        assertEquals("https://xxxclub.to/download/fixture.torrent", item.fileDownloadLink)
    }

    /** 详情必须严格解析字段，并让 Pending 与恶意可选媒体独立降级。 */
    @Test
    fun detailsParseStrictFieldsAndDegradeOptionalValues() = runBlocking {
        /** normal 是固定详情 fixture。 */
        val normal = readProviderFixture("provider/xxxclub/details_normal.html")
        /** degraded 把可选 last-checked、文件和海报替换为不可用值。 */
        val degraded = normal
            .replace("03 Jun 2024 21:16:31", "Pending")
            .replace("/download/fixture.torrent", "https://evil.example/a.torrent")
            .replace("/images/poster.jpg", "https://user@xxxclub.to/poster.jpg")
        /** details 保留必填详情并清空三个坏可选字段。 */
        val details = requireNotNull(
            XXXClubProvider(FakeTorrentSearchNetworkClient(textHandler = { degraded }))
                .getDetails("https://xxxclub.to/torrents/details/fixture"),
        )
        assertEquals(hash, details.infoHash)
        assertEquals("Fixture XXXClub Details", details.name)
        assertEquals(1717359330000L, details.uploadEpochMillis)
        assertEquals("fixture-uploader", details.uploader)
        assertEquals("<b>Neutral fixture description.</b>", details.description)
        assertNull(details.lastCheckedEpochMillis)
        assertNull(details.fileDownloadLink)
        assertNull(details.posterUrl)
    }

    /** 非法详情 authority 必须在网络前拒绝且异常不回显输入。 */
    @Test
    fun detailsRejectUnsafeAuthorityBeforeNetwork() = runBlocking {
        /** client 用于证明非法地址没有触发请求。 */
        val client = FakeTorrentSearchNetworkClient()
        try {
            XXXClubProvider(client).getDetails("https://xxxclub.to:8443/torrents/details/a")
            fail("非法 authority 必须拒绝")
        /** failure 只检查稳定低敏类型。 */
        } catch (failure: HttpDeniedException) {
            assertNull(failure.message)
        }
        assertTrue(client.textUrls.isEmpty())
    }

    /** 详情缺少名称或合法 magnet 时只返回空，不构造不稳定身份。 */
    @Test
    fun detailsRequireNameAndValidMagnet() = runBlocking {
        /** normal 是用于单点变异的固定详情页。 */
        val normal = readProviderFixture("provider/xxxclub/details_normal.html")
        /** validDetails 证明测试先固定正常入口，而不是让空实现误过坏输入断言。 */
        val validDetails = XXXClubProvider(FakeTorrentSearchNetworkClient(textHandler = { normal }))
            .getDetails("https://xxxclub.to/torrents/details/fixture")
        assertEquals(hash, requireNotNull(validDetails).infoHash)
        /** candidates 分别破坏名称、scheme 与 infoHash。 */
        val candidates = listOf(
            normal.replace("Fixture XXXClub Details", ""),
            normal.replace("magnet:?", "https://example.invalid/?"),
            normal.replace(hash.uppercase(), "not-a-hash"),
        )
        /** candidate 是当前坏详情响应。 */
        for (candidate in candidates) {
            /** details 对坏必填值必须稳定返回 null。 */
            val details = XXXClubProvider(FakeTorrentSearchNetworkClient(textHandler = { candidate }))
                .getDetails("https://xxxclub.to/torrents/details/fixture")
            assertNull(details)
        }
    }

    /** 多详情请求必须并发启动，并在逆序完成后保持来源顺序。 */
    @Test
    fun detailsRunConcurrentlyAndPreserveSourceOrder() = runBlocking {
        /** list 含两个来源顺序固定的列表项。 */
        val list = readProviderFixture("provider/xxxclub/search_normal.html")
            .replace(
                "</ul>",
                "<li><span>2</span><span><a href='/torrents/details/second'>Second XXXClub</a></span>" +
                    "<span class='siz'>2 GB</span><span class='see'>2</span><span class='lee'>1</span>" +
                    "<span class='adde'>03 Jun 2024 20:15:30</span></li></ul>",
            )
        /** firstStarted 与 secondStarted 证明两个详情请求都获得启动机会。 */
        val firstStarted = CompletableDeferred<Unit>()
        /** secondStarted 控制第一项等待第二项进入。 */
        val secondStarted = CompletableDeferred<Unit>()
        /** releaseFirst 让第二项先完成后再释放第一项。 */
        val releaseFirst = CompletableDeferred<Unit>()
        /** client 根据详情地址协调逆序完成。 */
        val client = FakeTorrentSearchNetworkClient(
            // url 决定返回列表、第一详情或第二详情。
            textHandler = { url ->
                when {
                    url.endsWith("/details/fixture") -> {
                        firstStarted.complete(Unit)
                        withTimeout(1_000L) { secondStarted.await() }
                        releaseFirst.await()
                        readProviderFixture("provider/xxxclub/details_normal.html")
                    }
                    url.endsWith("/details/second") -> {
                        secondStarted.complete(Unit)
                        withTimeout(1_000L) { firstStarted.await() }
                        releaseFirst.complete(Unit)
                        readProviderFixture("provider/xxxclub/details_normal.html")
                            .replace(hash.uppercase(), "1123456789ABCDEF0123456789ABCDEF01234567")
                    }
                    else -> list
                }
            },
        )
        /** names 必须按列表来源顺序而不是网络完成顺序输出。 */
        val names = XXXClubProvider(client).search("x", Category.ALL).map { item -> item.name }
        assertEquals(listOf("Fixture XXXClub", "Second XXXClub"), names)
    }

    /** 空详情只跳过当前草稿，真实传输失败必须向协调器传播。 */
    @Test
    fun emptyDetailsAreSkippedAndTransportFailurePropagates() = runBlocking {
        /** list 是单条合法列表响应。 */
        val list = readProviderFixture("provider/xxxclub/search_normal.html")
        /** emptyClient 让详情缺少必填字段。 */
        val emptyClient = FakeTorrentSearchNetworkClient(
            // url 区分列表与空详情。
            textHandler = { url -> if (url.contains("/details/")) "<html></html>" else list },
        )
        assertTrue(XXXClubProvider(emptyClient).search("x", Category.ALL).isEmpty())
        /** failingClient 在详情阶段抛出真实传输异常。 */
        val failingClient = FakeTorrentSearchNetworkClient(
            // url 只允许列表成功，详情必须失败。
            textHandler = { url -> if (url.contains("/details/")) throw IOException() else list },
        )
        try {
            XXXClubProvider(failingClient).search("x", Category.ALL)
            fail("详情传输失败必须传播")
        /** failure 只固定异常类型，不读取可能敏感的 message。 */
        } catch (_: IOException) {
            // 预期分支无需附加断言。
        }
    }
}
