package com.cla.clip.feature.torrent.search.provider.builtin.anilibria

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.builtin.testing.readProviderFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AniLibria 独立 URL/JSON parser 契约测试，不借助 fake network 自洽。 */
class AniLibriaJsonParserTest {
    /** 固定 Provider 元数据只用于领域映射，不参与 JSON 结构推导。 */
    private val descriptor = ProviderDescriptor(
        id = ProviderId("anilibria"),
        displayName = "AniLibria",
        baseUrl = "https://www.anilibria.top",
        supportedCategories = setOf(Category.ANIME),
        safetyLevel = ProviderSafetyLevel.SAFE,
        enabledByDefault = false,
    )
    /** 直接待测的 JSON parser，默认调度器由生产实现提供。 */
    private val parser = AniLibriaJsonParser(descriptor)

    /** release 顶层数组必须提取合法数字 ID、跳过缺字段并固定最多 15 项。 */
    @Test
    fun parseReleaseIdsHandlesNormalEmptyAndMissingFields() = runTest {
        /** 16 个合法 ID、一个缺 ID 和一个字符串 ID 的独立合成输入。 */
        val mixedJson = (1..16).joinToString(prefix = "[", postfix = ",{}, {\"id\":\"17\"}]") { id ->
            """{"id":$id}"""
        }

        assertEquals((1L..15L).toList(), parser.parseReleaseIds(mixedJson))
        assertTrue(parser.parseReleaseIds("[]").isEmpty())
    }

    /** torrent 数组必须直接映射正常 fixture，并跳过缺少必填 hash 的单条。 */
    @Test
    fun parseReleaseTorrentsMapsNormalFixtureAndSkipsMissingEntry() = runTest {
        /** 固定正常 torrent fixture，期望值手工推导。 */
        val normalFixture = readProviderFixture("provider/anilibria/release_torrents_normal.json")
        /** 首条缺 hash、第二条合法的独立逐条容错输入。 */
        val mixedFixture = """[{"label":"broken"},{"hash":"00112233445566778899aabbccddeeff10203040","label":"valid"}]"""

        /** 正常 fixture 映射出的唯一 torrent。 */
        val normalTorrent = parser.parseReleaseTorrents(normalFixture).single()

        assertEquals("0123456789abcdef0123456789abcdef01234567", normalTorrent.infoHash)
        assertEquals("Synthetic AniLibria Episode", normalTorrent.name)
        assertEquals("1.00 MB", normalTorrent.size)
        assertEquals(1709528767000L, normalTorrent.uploadEpochMillis)
        /** torrent 是混合输入保留的合法领域条目，只投影名称验证逐条跳过。 */
        assertEquals(listOf("valid"), parser.parseReleaseTorrents(mixedFixture).map { torrent -> torrent.name })
        assertTrue(parser.parseReleaseTorrents("[]").isEmpty())
    }

    /** release 与 torrent 顶层不是数组时都必须抛稳定低敏解析异常。 */
    @Test
    fun parserRejectsMalformedTopLevelStructures() = runTest {
        /** release 顶层对象产生的稳定异常。 */
        val releaseFailure = runCatching { parser.parseReleaseIds("{\"unexpected\":true}") }.exceptionOrNull()
        /** torrent 顶层对象产生的稳定异常。 */
        val torrentFailure = runCatching { parser.parseReleaseTorrents("{\"unexpected\":true}") }.exceptionOrNull()

        assertTrue(releaseFailure is ProviderParseException)
        assertTrue(torrentFailure is ProviderParseException)
    }

    /** URL builder 必须独立编码查询、release ID 与 alias 路径边界。 */
    @Test
    fun urlBuilderEncodesSearchAndPathInputsExactly() {
        assertEquals(
            "https://anilibria.top/api/v1/app/search/releases?query=A%26B%20C",
            AniLibriaUrlBuilder.buildSearchUrl("A&B\n C"),
        )
        assertEquals(
            "https://anilibria.top/api/v1/anime/torrents/release/101",
            AniLibriaUrlBuilder.buildReleaseTorrentsUrl(101L),
        )
        assertEquals(
            "https://www.anilibria.top/anime/releases/release/a%2Fb",
            AniLibriaUrlBuilder.buildReleaseDetailsUrl("a/b"),
        )
    }
}
