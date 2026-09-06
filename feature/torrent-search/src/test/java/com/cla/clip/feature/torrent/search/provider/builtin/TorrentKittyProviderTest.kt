package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import com.cla.clip.feature.torrent.search.provider.builtin.torrentkitty.TorrentKittyProvider
import java.util.Locale
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TorrentKitty hash-only 列表、详情 base、日期与 URL 边界测试。 */
class TorrentKittyProviderTest {
    /** descriptor 只支持 search/details 与 OTHER 输入分类。 */
    @Test
    fun metadataAndCapabilitiesMatchManifest() {
        /** provider 是不联网即可检查的固定实现。 */
        val provider: SearchProvider = TorrentKittyProvider(FakeTorrentSearchNetworkClient())
        assertEquals("torrentkitty", provider.descriptor.id.value)
        assertEquals(ProviderSafetyLevel.SAFE, provider.descriptor.safetyLevel)
        assertFalse(provider.descriptor.enabledByDefault)
        assertEquals(setOf(Category.OTHER), provider.descriptor.supportedCategories)
        assertTrue(provider is TorrentDetailsProvider)
    }

    /** 搜索编码 path，列表只输出 hash 且不虚构 magnet/category。 */
    @Test
    fun searchEncodesPathAndPreservesHashOnlyContract() = runBlocking {
        /** client 返回固定正常列表。 */
        val client = FakeTorrentSearchNetworkClient(
            textHandler = { readProviderFixture("provider/torrentkitty/search_normal.html") },
        )
        /** item 是唯一合法列表条目。 */
        val item = TorrentKittyProvider(client).search("  fi\u0000xture /?  ", Category.OTHER).single()
        assertEquals("/search/fixture%20%2F%3F", client.textUrls.single().toHttpUrl().encodedPath)
        assertEquals(INFO_HASH, item.infoHash)
        assertNull(item.magnetUri)
        assertNull(item.category)
        assertEquals("1.5 GB", item.size)
        assertEquals(1704153600000L, item.uploadEpochMillis)
        assertEquals("https://torrentkitty.tv/information/a", item.descriptionPageUrl)
        assertEquals("https://torrentkitty.tv/torrent/a.torrent", item.fileDownloadLink)
    }

    /** action 第一详情、第二 hash、第三文件的顺序不能复用其他站 parser。 */
    @Test
    fun listActionOrderRemainsSiteSpecific() = runBlocking {
        /** item 从真实顺序 fixture 读取。 */
        val item = TorrentKittyProvider(
            FakeTorrentSearchNetworkClient(
                textHandler = { readProviderFixture("provider/torrentkitty/search_normal.html") },
            ),
        ).search("x", Category.OTHER).single()
        assertEquals(INFO_HASH, item.infoHash)
        assertEquals("https://torrentkitty.tv/information/a", item.descriptionPageUrl)
        assertEquals("https://torrentkitty.tv/torrent/a.torrent", item.fileDownloadLink)
        assertNull(item.magnetUri)
    }

    /** 详情使用真实 page base，并从 p.action 第二/第一 anchor 读取 magnet/file。 */
    @Test
    fun detailsResolveRelativeFileAgainstValidatedPage() = runBlocking {
        /** details 使用固定合成详情页。 */
        val details = requireNotNull(
            TorrentKittyProvider(
                FakeTorrentSearchNetworkClient(
                    textHandler = { readProviderFixture("provider/torrentkitty/details_normal.html") },
                ),
            ).getDetails("https://torrentkitty.tv/information/a"),
        )
        assertEquals(INFO_HASH, details.infoHash)
        assertEquals(VALID_MAGNET, details.magnetUri)
        assertEquals("https://torrentkitty.tv/torrent/a.torrent", details.fileDownloadLink)
        assertEquals("2 GB", details.size)
        assertEquals(1704153600000L, details.uploadEpochMillis)
    }

    /** 严格 yyyy-MM-dd 坏值只降级日期，不丢列表或详情。 */
    @Test
    fun malformedDatesDegradeWithoutDroppingResults() = runBlocking {
        /** listHtml 使用不存在日期。 */
        val listHtml = readProviderFixture("provider/torrentkitty/search_normal.html")
            .replace("2024-01-02", "2024-02-30")
        /** listItem 仍有合法名称和 hash。 */
        val listItem = TorrentKittyProvider(
            FakeTorrentSearchNetworkClient(textHandler = { listHtml }),
        ).search("x", Category.OTHER).single()
        assertNull(listItem.uploadEpochMillis)
        /** detailsHtml 使用错误年份宽度。 */
        val detailsHtml = readProviderFixture("provider/torrentkitty/details_normal.html")
            .replace("2024-01-02", "24-1-2")
        /** details 仍有合法名称和 magnet。 */
        val details = requireNotNull(
            TorrentKittyProvider(FakeTorrentSearchNetworkClient(textHandler = { detailsHtml }))
                .getDetails("https://torrentkitty.tv/information/a"),
        )
        assertNull(details.uploadEpochMillis)
    }

    /** 大小大写固定使用 Locale.ROOT，不受土耳其默认 locale 影响。 */
    @Test
    fun sizeUppercaseIsLocaleIndependent() = runBlocking {
        /** previousLocale 保存测试前全局 locale。 */
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            /** listHtml 使用含 i 的单位证明固定 locale。 */
            val listHtml = readProviderFixture("provider/torrentkitty/search_normal.html")
                .replace("1.5 gb", "1.5 mib")
            /** listItem 是列表格式化结果。 */
            val listItem = TorrentKittyProvider(
                FakeTorrentSearchNetworkClient(textHandler = { listHtml }),
            ).search("x", Category.OTHER).single()
            assertEquals("1.5 MIB", listItem.size)
            /** detailsHtml 使用相同单位覆盖详情 parser。 */
            val detailsHtml = readProviderFixture("provider/torrentkitty/details_normal.html")
                .replace("2 gb", "2 mib")
            /** details 是详情格式化结果。 */
            val details = requireNotNull(
                TorrentKittyProvider(FakeTorrentSearchNetworkClient(textHandler = { detailsHtml }))
                    .getDetails("https://torrentkitty.tv/information/a"),
            )
            assertEquals("2 MIB", details.size)
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    /** 合法空页、selector 漂移、缺名称和坏 hash 均返回空。 */
    @Test
    fun handlesEmptyMalformedAndMissingRequiredRows() = runBlocking {
        /** pages 覆盖合法空和三类坏条目。 */
        val pages = listOf(
            EMPTY_LIST_HTML,
            "<div class='changed-layout'>x</div>",
            kittyRow("", INFO_HASH),
            kittyRow("Fixture", "bad"),
        )
        /** html 是当前独立页面。 */
        pages.forEach { html ->
            assertTrue(
                TorrentKittyProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))
                    .search("x", Category.OTHER)
                    .isEmpty(),
            )
        }
    }

    /** 详情名称/hash 缺失返回 null，列表不把 OTHER 输入写入输出。 */
    @Test
    fun detailsRequireNameAndHashWhileListCategoryStaysNull() = runBlocking {
        /** provider 返回由 url 区分的三份页面。 */
        val provider = TorrentKittyProvider(
            FakeTorrentSearchNetworkClient(
                /** requestUrl 决定返回列表或两类坏详情。 */
                textHandler = { requestUrl ->
                    when {
                        requestUrl.endsWith("/missing-name") -> readProviderFixture("provider/torrentkitty/details_normal.html")
                            .replace("Fixture Gamma", "")
                        requestUrl.endsWith("/bad-hash") -> readProviderFixture("provider/torrentkitty/details_normal.html")
                            .replace(INFO_HASH, "bad")
                        else -> readProviderFixture("provider/torrentkitty/search_normal.html")
                    }
                },
            ),
        )
        assertNull(provider.getDetails("https://torrentkitty.tv/missing-name"))
        assertNull(provider.getDetails("https://torrentkitty.tv/bad-hash"))
        assertNull(provider.search("x", Category.ALL).single().category)
    }

    /** 可选链接拒绝 scheme、host、userinfo 和端口，合法 hash 条目仍保留。 */
    @Test
    fun dropsEveryUnsafeOptionalOutputUrl() = runBlocking {
        /** unsafeUrls 是全部 authority 风险候选。 */
        val unsafeUrls = listOf(
            "https://evil.invalid/a",
            "https://user@torrentkitty.tv/a",
            "https://torrentkitty.tv:444/a",
            "http://torrentkitty.tv/a",
        )
        /** unsafeUrl 是当前污染详情和文件字段的候选。 */
        unsafeUrls.forEach { unsafeUrl ->
            /** item 仍保留合法名称和 hash。 */
            val item = TorrentKittyProvider(
                FakeTorrentSearchNetworkClient(
                    textHandler = { kittyRow("Fixture", INFO_HASH, unsafeUrl, unsafeUrl) },
                ),
            ).search("x", Category.OTHER).single()
            assertNull(item.descriptionPageUrl)
            assertNull(item.fileDownloadLink)
        }
    }

    /** 非法详情输入在网络前拒绝，显式 443 允许。 */
    @Test
    fun validatesDetailsAuthorityBeforeNetwork() = runBlocking {
        /** client 用于证明拒绝分支无网络。 */
        val client = FakeTorrentSearchNetworkClient(
            textHandler = { readProviderFixture("provider/torrentkitty/details_normal.html") },
        )
        /** provider 是详情入口。 */
        val provider = TorrentKittyProvider(client)
        /** url 是当前恶意详情地址。 */
        for (url in listOf(
            "https://evil.invalid/a",
            "http://torrentkitty.tv/a",
            "https://user@torrentkitty.tv/a",
            "https://torrentkitty.tv:444/a",
        )) {
            /** failure 应为低敏拒绝。 */
            val failure = runCatching { provider.getDetails(url) }.exceptionOrNull()
            assertTrue(failure is HttpDeniedException)
        }
        assertTrue(client.textUrls.isEmpty())
        requireNotNull(provider.getDetails("https://torrentkitty.tv:443/information/a"))
        assertEquals(1, client.textUrls.size)
    }

    /** 多行列表保持原顺序且每行只从第二 action anchor 取 hash。 */
    @Test
    fun parsesMultipleRowsInSourceOrder() = runBlocking {
        /** secondRow 是第二条独立合法结果。 */
        val secondRow = """
            <tr><td class="name">Fixture Two</td><td class="size">2 gb</td><td class="date">2024-02-02</td><td class="action"><a href="/information/b">details</a><a href="magnet:?xt=urn:btih:$SECOND_HASH">magnet</a><a href="/torrent/b.torrent">file</a></td></tr>
        """.trimIndent()
        /** html 在固定正常页后追加第二行。 */
        val html = readProviderFixture("provider/torrentkitty/search_normal.html")
            .replace("</tbody>", "$secondRow</tbody>")
        /** items 必须完整保持两行顺序。 */
        val items = TorrentKittyProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))
            .search("x", Category.OTHER)
        /** item 是当前结果名称来源。 */
        assertEquals(listOf("Fixture Gamma", "Fixture Two"), items.map { item -> item.name })
        /** item 是当前结果 hash 来源。 */
        assertEquals(listOf(INFO_HASH, SECOND_HASH), items.map { item -> item.infoHash })
    }

    /** 详情 torrent 文件 URL 非法时只清空文件字段。 */
    @Test
    fun detailsDropUnsafeFileUrlWithoutDroppingIdentity() = runBlocking {
        /** html 把相对文件替换为跨站候选。 */
        val html = readProviderFixture("provider/torrentkitty/details_normal.html")
            .replace("/torrent/a.torrent", "https://evil.invalid/a")
        /** details 仍由合法名称与 magnet 构成。 */
        val details = requireNotNull(
            TorrentKittyProvider(FakeTorrentSearchNetworkClient(textHandler = { html }))
                .getDetails("https://torrentkitty.tv/information/a"),
        )
        assertEquals(INFO_HASH, details.infoHash)
        assertNull(details.fileDownloadLink)
    }

    /** 构造固定 TorrentKitty action 顺序。 */
    private fun kittyRow(
        /** 结果名称。 */
        name: String,
        /** 裸 hash 候选。 */
        infoHash: String,
        /** 可选详情地址。 */
        details: String = "/information/a",
        /** 可选文件地址。 */
        file: String = "/torrent/a.torrent",
    ): String = """
        <table id="archiveResult"><tbody><tr><th>header</th></tr><tr>
          <td class="name">$name</td><td class="size">1 gb</td><td class="date">2024-01-02</td>
          <td class="action"><a href="$details">details</a><a href="magnet:?xt=urn:btih:$infoHash">magnet</a><a href="$file">file</a></td>
        </tr></tbody></table>
    """.trimIndent()

    /** 固定测试协议常量。 */
    private companion object {
        /** 中性 40 位 hash。 */
        private const val INFO_HASH = "0123456789abcdef0123456789abcdef01234567"
        /** 中性合法 magnet。 */
        private const val VALID_MAGNET = "magnet:?xt=urn:btih:$INFO_HASH"
        /** 第二条中性 40 位 hash。 */
        private const val SECOND_HASH = "89abcdef0123456789abcdef0123456789abcdef"
        /** 固定合法空列表。 */
        private const val EMPTY_LIST_HTML = "<table id='archiveResult'><tbody><tr><th>header</th></tr></tbody></table>"
    }
}
