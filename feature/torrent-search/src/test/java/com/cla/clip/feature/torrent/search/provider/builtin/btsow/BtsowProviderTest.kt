package com.cla.clip.feature.torrent.search.provider.builtin.btsow

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

/** Btsow JSON、请求 payload、详情路径与时间溢出契约测试。 */
class BtsowProviderTest {
    /** 搜索必须清洗并 JSON 编码查询，同时映射固定大小、hash、名称和描述符。 */
    @Test
    fun searchBuildsPayloadAndMapsNormalFixture() = runTest {
        /** 捕获的 JSON payload 只使用合成查询。 */
        val payloads = mutableListOf<String>()
        /** 返回固定搜索 fixture 的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** payload 是当前搜索正文，URL 由 fake 单独记录。 */
            postJsonHandler = { _, payload ->
                payloads += payload
                readProviderFixture("provider/btsow/search_normal.json")
            },
        )
        /** 待测 Btsow Provider。 */
        val provider = BtsowProvider(networkClient)

        /** 唯一合法结果，查询中的控制字符应在编码前删除。 */
        val torrent = provider.search("A\"B\n\u0000", Category.OTHER).single()

        assertEquals("[{\"search\":\"A\\\"B\"},30,1]", payloads.single())
        assertEquals(listOf("https://btsow.live/bts/data/api/search"), networkClient.postJsonUrls)
        assertEquals("00112233445566778899aabbccddeeff00112233", torrent.infoHash)
        assertEquals("Synthetic Btsow", torrent.name)
        assertEquals("1.00 MB", torrent.size)
        assertEquals("https://btsow.live/magnet/detail/00112233445566778899aabbccddeeff00112233", torrent.descriptionPageUrl)
        assertEquals("btsow", provider.descriptor.id.value)
        assertEquals(setOf(Category.OTHER), provider.descriptor.supportedCategories)
        assertEquals(ProviderSafetyLevel.SAFE, provider.descriptor.safetyLevel)
        assertFalse(provider.descriptor.enabledByDefault)
    }

    /** data 空数组是合法空结果，不能与顶层协议错误混淆。 */
    @Test
    fun searchAcceptsEmptyDataArray() = runTest {
        /** 返回合法空对象的 Provider。 */
        val provider = BtsowProvider(
            /** 空结果 fixture 与请求参数无关。 */
            FakeTorrentSearchNetworkClient(postJsonHandler = { _, _ -> "{\"data\":[]}" }),
        )

        assertTrue(provider.search("fixture", Category.ALL).isEmpty())
    }

    /** 单条缺 name、非法 hash 或错误类型必须跳过，合法条目仍保留。 */
    @Test
    fun searchSkipsRowsWithInvalidRequiredFields() = runTest {
        /** 合成混合响应仅最后一条满足名称与 hash 契约。 */
        val responseJson = """{"data":[{"hash":"00112233445566778899aabbccddeeff00112233"},{"hash":"bad","name":"bad"},{"hash":4,"name":"bad"},{"hash":"ffeeddccbbaa99887766554433221100ffeeddcc","name":"valid","size":-1}]}"""
        /** 返回混合响应的 Provider。 */
        val provider = BtsowProvider(
            /** 请求参数不参与混合响应 fixture 的选择。 */
            FakeTorrentSearchNetworkClient(postJsonHandler = { _, _ -> responseJson }),
        )

        /** 仅合法条目名称。 */
        val names = provider.search("fixture", Category.ALL).map { torrent -> torrent.name }
        assertEquals(listOf("valid"), names)
    }

    /** 顶层非对象、缺 data、data 非数组或非法 JSON 必须抛无正文稳定异常。 */
    @Test
    fun searchRejectsMalformedTopLevelWithoutLeakingBody() = runTest {
        /** 四种顶层协议错误合成值。 */
        val cases = listOf("[]", "{}", "{\"data\":{}}", "not-json")

        /** responseJson 是当前独立错误响应。 */
        cases.forEach { responseJson ->
            /** 每轮独立 Provider，避免请求记录相互污染。 */
            val provider = BtsowProvider(
                /** 当前错误响应不依赖 URL 或提交正文。 */
                FakeTorrentSearchNetworkClient(postJsonHandler = { _, _ -> responseJson }),
            )
            /** 解析失败只能暴露类型，不能携带上游正文。 */
            val failure = runCatching { provider.search("fixture", Category.ALL) }.exceptionOrNull()
            assertTrue(failure is ProviderParseException)
            assertNull(failure?.message)
        }
    }

    /** 详情输入只接受同 host 的精确 hash 路径，污染地址不得触发网络。 */
    @Test
    fun detailsRejectsPollutedOrCrossHostUrlsBeforeNetwork() = runTest {
        /** 网络回调若执行即暴露详情 URL 校验遗漏。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** 任意请求均令测试失败，参数无需展开或记录。 */
            postJsonHandler = { _, _ -> error("不应请求非法详情地址") },
        )
        /** 待测 Provider。 */
        val provider = BtsowProvider(networkClient)
        /** 覆盖跨 host、额外段、查询、fragment 与非法 hash。 */
        val invalidUrls = listOf(
            "https://evil.invalid/magnet/detail/00112233445566778899aabbccddeeff00112233",
            "https://btsow.live/magnet/detail/00112233445566778899aabbccddeeff00112233/extra",
            "https://btsow.live/magnet/detail/00112233445566778899aabbccddeeff00112233?x=1",
            "https://btsow.live/magnet/detail/00112233445566778899aabbccddeeff00112233#fragment",
            "https://btsow.live/magnet/detail/not-a-hash",
        )

        /** url 是当前被拒绝的恶意或语义污染地址。 */
        invalidUrls.forEach { url ->
            assertTrue(runCatching { provider.getDetails(url) }.exceptionOrNull() is HttpDeniedException)
        }
        assertTrue(networkClient.postJsonUrls.isEmpty())
    }

    /** 合法详情必须提交规范 hash，并映射秒级时间与公共 tracker magnet。 */
    @Test
    fun detailsPostsNormalizedHashAndMapsNormalFixture() = runTest {
        /** 捕获详情 JSON payload。 */
        val payloads = mutableListOf<String>()
        /** 返回固定详情 fixture 的网络 fake。 */
        val networkClient = FakeTorrentSearchNetworkClient(
            /** payload 是当前详情请求正文。 */
            postJsonHandler = { _, payload ->
                payloads += payload
                readProviderFixture("provider/btsow/details_normal.json")
            },
        )

        /** 合法详情对象。 */
        val details = checkNotNull(
            BtsowProvider(networkClient).getDetails(
                "https://btsow.live/magnet/detail/00112233445566778899AABBCCDDEEFF00112233",
            ),
        )

        assertEquals("[\"00112233445566778899aabbccddeeff00112233\"]", payloads.single())
        assertEquals(listOf("https://btsow.live/bts/data/api/magnet"), networkClient.postJsonUrls)
        assertEquals("00112233445566778899aabbccddeeff00112233", details.infoHash)
        assertEquals("Synthetic Btsow Detail", details.name)
        assertEquals("1.00 GB", details.size)
        assertEquals(1_709_528_760_000L, details.uploadEpochMillis)
        assertEquals(1_709_532_360_000L, details.lastCheckedEpochMillis)
        assertTrue(details.magnetUri.startsWith("magnet:?xt=urn:btih:00112233445566778899aabbccddeeff00112233&tr="))
    }

    /** 详情 hash 冲突或缺 name 返回 null，epoch 溢出只降级对应时间字段。 */
    @Test
    fun detailsRejectsHashConflictAndDegradesOverflowingEpoch() = runTest {
        /** 与请求 hash 冲突的详情响应。 */
        val conflictingProvider = BtsowProvider(
            FakeTorrentSearchNetworkClient(
                /** 固定返回冲突 hash，忽略请求参数以模拟错误上源。 */
                postJsonHandler = { _, _ -> """{"data":{"hash":"ffeeddccbbaa99887766554433221100ffeeddcc","name":"conflict"}}""" },
            ),
        )
        /** 请求使用的固定合法详情 URL。 */
        val detailsUrl = "https://btsow.live/magnet/detail/00112233445566778899aabbccddeeff00112233"
        assertNull(conflictingProvider.getDetails(detailsUrl))

        /** 极端秒值不得乘法溢出到错误时间。 */
        val overflowProvider = BtsowProvider(
            FakeTorrentSearchNetworkClient(
                /** 固定返回超限秒值，网络地址与 payload 不影响该边界。 */
                postJsonHandler = { _, _ -> """{"data":{"hash":"00112233445566778899aabbccddeeff00112233","name":"overflow","date":9223372036854775807,"lastUpdateTime":-9223372036854775808}}""" },
            ),
        )
        /** 溢出时间不影响其他必填字段。 */
        val details = checkNotNull(overflowProvider.getDetails(detailsUrl))
        assertNull(details.uploadEpochMillis)
        assertNull(details.lastCheckedEpochMillis)
    }

    /** 详情 data 缺失或类型错误属于顶层结构异常，且异常不能回显正文。 */
    @Test
    fun detailsRejectsMalformedTopLevelData() = runTest {
        /** 两种详情顶层 data 协议错误。 */
        val cases = listOf("{}", "{\"data\":[]}")
        /** 固定合法详情地址。 */
        val detailsUrl = "https://btsow.live/magnet/detail/00112233445566778899aabbccddeeff00112233"

        /** responseJson 是当前独立错误响应。 */
        cases.forEach { responseJson ->
            /** 每轮独立 Provider。 */
            val provider = BtsowProvider(
                /** 固定顶层错误响应与本轮详情参数无关。 */
                FakeTorrentSearchNetworkClient(postJsonHandler = { _, _ -> responseJson }),
            )
            /** 顶层错误统一为无正文解析异常。 */
            val failure = runCatching { provider.getDetails(detailsUrl) }.exceptionOrNull()
            assertTrue(failure is ProviderParseException)
            assertNull(failure?.message)
        }
    }
}
