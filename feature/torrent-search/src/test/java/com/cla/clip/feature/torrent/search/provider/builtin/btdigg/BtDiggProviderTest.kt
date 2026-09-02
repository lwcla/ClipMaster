package com.cla.clip.feature.torrent.search.provider.builtin.btdigg

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BTDigg magnet 保留、相对日期、详情与精确 host 安全契约测试。 */
class BtDiggProviderTest {
    /** 正常搜索必须保留合法 magnet 并映射固定 SAFE/OTHER/默认关闭描述符。 */
    @Test
    fun searchPreservesValidatedMagnetAndMapsRelativeDate() = runTest {
        /** 正常搜索 fixture。 */
        val fixture = readProviderFixture("provider/btdigg/search_normal.html")
        /** 固定相对日期基准。 */
        val now = 1_700_000_000_000L
        /** 仅返回 fixture 的 recording fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(textHandler = { fixture })
        /** 待测 Provider。 */
        val provider = BtDiggProvider(networkClient, currentTimeMillis = { now })

        /** 唯一合法结果。 */
        val torrent = provider.search("A&B\n C\u0000", Category.ALL).single()

        assertEquals("btdigg", provider.descriptor.id.value)
        assertEquals(ProviderSafetyLevel.SAFE, provider.descriptor.safetyLevel)
        assertFalse(provider.descriptor.enabledByDefault)
        assertEquals(setOf(Category.OTHER), provider.descriptor.supportedCategories)
        assertEquals("00112233445566778899aabbccddeeff00112233", torrent.infoHash)
        assertEquals("magnet:?xt=urn:btih:00112233445566778899AABBCCDDEEFF00112233", torrent.magnetUri)
        assertEquals("Synthetic BTDigg Result", torrent.name)
        assertEquals("900 MB", torrent.size)
        assertEquals(now - 2L * 60L * 60L * 1000L, torrent.uploadEpochMillis)
        assertEquals(Category.OTHER, torrent.category)
        assertEquals("https://btdig.com/search/result/one", torrent.descriptionPageUrl)
        assertEquals(listOf("https://btdig.com/search?q=A%26B%20C"), networkClient.textUrls)
    }

    /** 合法空容器返回空，缺字段或非法 magnet 的单条只跳过。 */
    @Test
    fun searchHandlesEmptyAndInvalidRows() = runTest {
        /** 一条非法 magnet 和一条合法记录。 */
        val mixedPage = """
            <html><body><main id="search-results">
              <div class="one_result"><div><div class="torrent_name"><a href="/bad">Bad</a></div><div class="torrent_magnet"><div class="fa-magnet"><a href="https://btdig.com/not-magnet">x</a></div></div></div></div>
              <div class="one_result"><div><div class="torrent_name"><a href="/valid">Valid</a></div><div class="torrent_magnet"><div class="fa-magnet"><a href="magnet:?xt=urn:btih:00112233445566778899aabbccddeeff00112233">x</a></div></div></div></div>
            </main></body></html>
        """.trimIndent()
        /** 首次返回空容器，随后返回混合页面。 */
        var callCount = 0
        /** 合成网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** 调用次数选择固定页面。 */
            textHandler = {
                callCount += 1
                if (callCount == 1) "<html><body><main id=\"search-results\"></main></body></html>" else mixedPage
            },
        )
        /** 待测 Provider。 */
        val provider = BtDiggProvider(networkClient)

        assertTrue(provider.search("empty", Category.OTHER).isEmpty())
        /** torrent 是混合页面唯一合法记录。 */
        assertEquals(listOf("Valid"), provider.search("mixed", Category.OTHER).map { torrent -> torrent.name })
    }

    /** 缺顶层容器抛低敏异常，恶意详情 host 在发起网络前拒绝。 */
    @Test
    fun rejectsMalformedTopLevelAndUnlistedDetailsHost() = runTest {
        /** 不含顶层结果容器的挑战页。 */
        val networkClient = FakeTorrentSearchNetworkClient(textHandler = { "<html><body>challenge</body></html>" })
        /** 待测 Provider。 */
        val provider = BtDiggProvider(networkClient)

        /** 顶层结构失败。 */
        val malformedFailure = runCatching { provider.search("fixture", Category.OTHER) }.exceptionOrNull()
        /** 直接恶意详情 host 拒绝。 */
        val detailsFailure = runCatching { provider.getDetails("https://evil.invalid/item") }.exceptionOrNull()

        assertTrue(malformedFailure is ProviderParseException)
        assertNull(malformedFailure?.message)
        assertTrue(detailsFailure is HttpDeniedException)
        assertEquals(1, networkClient.textUrls.size)
    }

    /** 结果中的恶意详情链接必须拒绝，不能产出未校验 URL。 */
    @Test
    fun searchRejectsUnlistedDetailsLink() = runTest {
        /** 正常 fixture 中只替换详情 host。 */
        val maliciousFixture = readProviderFixture("provider/btdigg/search_normal.html")
            .replace("/search/result/one", "https://evil.invalid/result")
        /** 仅返回恶意 fixture 的 fake。 */
        val provider = BtDiggProvider(FakeTorrentSearchNetworkClient(textHandler = { maliciousFixture }))

        /** 跨 host 结果的稳定拒绝。 */
        val failure = runCatching { provider.search("fixture", Category.OTHER) }.exceptionOrNull()
        assertTrue(failure is HttpDeniedException)
    }

    /** 详情必须校验 btih、保留 magnet，并用注入时钟解析 Age。 */
    @Test
    fun detailsMapsValidatedMagnetAndRelativeAge() = runTest {
        /** 完整详情 fixture。 */
        val fixture = readProviderFixture("provider/btdigg/details_normal.html")
        /** 固定相对日期基准。 */
        val now = 1_700_000_000_000L
        /** 待测 Provider。 */
        val provider = BtDiggProvider(FakeTorrentSearchNetworkClient(textHandler = { fixture }), currentTimeMillis = { now })

        /** 非空详情结果。 */
        val details = checkNotNull(provider.getDetails("https://btdig.com/search/result/one"))

        assertEquals("ffeeddccbbaa99887766554433221100ffeeddcc", details.infoHash)
        assertEquals("magnet:?xt=urn:btih:FFEEDDCCBBAA99887766554433221100FFEEDDCC", details.magnetUri)
        assertEquals("Synthetic BTDigg Detail", details.name)
        assertEquals("1.2 GB", details.size)
        assertEquals(now - 3L * 60L * 60L * 1000L, details.uploadEpochMillis)
        assertEquals(Category.OTHER, details.category)
    }

    /** 详情缺必填名称或含非法 btih 时返回 null，非法日期只降级为空。 */
    @Test
    fun detailsHandlesMissingRequiredFieldsAndInvalidDate() = runTest {
        /** 三种详情响应按调用次数提供。 */
        var callCount = 0
        /** 合成详情 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** 调用次数选择缺名称、非法 magnet 或非法日期。 */
            textHandler = {
                callCount += 1
                when (callCount) {
                    1 -> "<html><body><main id=\"torrent-details\"><a href=\"magnet:?xt=urn:btih:00112233445566778899aabbccddeeff00112233\">m</a></main></body></html>"
                    2 -> "<html><body><main id=\"torrent-details\"><table><tr><td>Name:</td><td>Name</td></tr></table><a href=\"magnet:?xt=urn:btih:bad\">m</a></main></body></html>"
                    else -> "<html><body><main id=\"torrent-details\"><table><tr><td>Name:</td><td>Name</td></tr><tr><td>Age:</td><td>unknown</td></tr></table><a href=\"magnet:?xt=urn:btih:00112233445566778899aabbccddeeff00112233\">m</a></main></body></html>"
                }
            },
        )
        /** 待测 Provider。 */
        val provider = BtDiggProvider(networkClient)

        assertNull(provider.getDetails("https://btdig.com/one"))
        assertNull(provider.getDetails("https://btdig.com/two"))
        assertNull(checkNotNull(provider.getDetails("https://btdig.com/three")).uploadEpochMillis)
    }
}
