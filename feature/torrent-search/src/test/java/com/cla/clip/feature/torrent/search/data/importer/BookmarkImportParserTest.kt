package com.cla.clip.feature.torrent.search.data.importer

import com.cla.clip.feature.torrent.search.model.contract.Category
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 书签 JSON 导入的 10 MiB、逐项容错、稳定键去重与兼容格式测试。 */
class BookmarkImportParserTest {
    /** version 1 envelope 应保留合法项、跳过坏项并按规范化 infoHash 保留首项。 */
    @Test
    fun parseEnvelopeToleratesBadItemsAndDeduplicatesByInfoHash() {
        /** json 包含合法、坏 hash、重复和从 magnet 恢复 hash 的四个条目。 */
        val json = """
            {
              "version": 1,
              "bookmarks": [
                {"infoHash":"${HASH_A.uppercase()}","name":"First","providerId":"fixture","providerName":"Fixture","seeders":7,"category":"movies","magnetUri":"magnet:?xt=urn:btih:$HASH_A"},
                {"infoHash":"bad","name":"Broken","providerId":"fixture","providerName":"Fixture"},
                {"infoHash":"$HASH_A","name":"Duplicate","providerId":"fixture","providerName":"Fixture"},
                {"name":"Recovered","providerId":"fixture","providerName":"Fixture","magnetUri":"magnet:?xt=urn:btih:$HASH_B"}
              ]
            }
        """.trimIndent()

        /** result 是导入器逐项处理后的可观察汇总。 */
        val result = BookmarkImportParser.parse(ByteArrayInputStream(json.toByteArray()))

        assertTrue(result is BookmarkImportResult.Success)
        /** success 提供合法书签及低敏计数，不返回坏条目正文。 */
        val success = result as BookmarkImportResult.Success
        assertEquals(
            listOf(HASH_A, HASH_B),
            success.bookmarks.map { /** bookmark 是当前待提取身份的合法书签。 */ bookmark -> bookmark.infoHash },
        )
        assertEquals("First", success.bookmarks.first().name)
        assertEquals(7u, success.bookmarks.first().seeders)
        assertEquals(Category.MOVIES, success.bookmarks.first().category)
        assertEquals(1, success.skippedCount)
        assertEquals(1, success.duplicateCount)
    }

    /** 固定上游兼容的顶层数组必须可导入，单个非对象不阻断后续合法项。 */
    @Test
    fun parseAcceptsUpstreamArrayAndSkipsNonObjectItems() {
        /** json 使用顶层数组并混入数字，验证逐项容错而非整包失败。 */
        val json = """[7,{"id":9,"infoHash":"$HASH_A","name":"Array item","providerName":"Fixture","uploadDate":100,"category":"Movies"}]"""

        /** result 应是包含一个合法项的成功汇总。 */
        val result = BookmarkImportParser.parse(ByteArrayInputStream(json.toByteArray())) as BookmarkImportResult.Success

        assertEquals(HASH_A, result.bookmarks.single().infoHash)
        assertEquals("legacy_import", result.bookmarks.single().providerId.value)
        assertEquals(100L, result.bookmarks.single().uploadEpochMillis)
        assertEquals(Category.MOVIES, result.bookmarks.single().category)
        assertEquals(1, result.skippedCount)
        assertEquals(0, result.duplicateCount)
    }

    /** 显式 infoHash 与 magnet 身份不一致时必须跳过当前项，防止错误下载。 */
    @Test
    fun parseSkipsBookmarkWhoseMagnetDoesNotMatchInfoHash() {
        /** json 只包含身份矛盾的合成条目。 */
        val json = """[{"infoHash":"$HASH_A","name":"Mismatch","providerId":"fixture","providerName":"Fixture","magnetUri":"magnet:?xt=urn:btih:$HASH_B"}]"""

        /** result 仍是逐项成功，但没有可写入书签。 */
        val result = BookmarkImportParser.parse(ByteArrayInputStream(json.toByteArray())) as BookmarkImportResult.Success

        assertTrue(result.bookmarks.isEmpty())
        assertEquals(1, result.skippedCount)
    }

    /** 身份字段存在但类型错误时不得伪装成缺失，再从另一字段恢复并导入。 */
    @Test
    fun parseSkipsWrongTypesInOptionalIdentityFields() {
        /** json 分别用数字 magnet 和数字 infoHash 搭配另一合法身份字段。 */
        val json = """{"version":1,"bookmarks":[{"infoHash":"$HASH_A","magnetUri":7,"name":"Bad magnet type","providerId":"fixture","providerName":"Fixture"},{"infoHash":7,"magnetUri":"magnet:?xt=urn:btih:$HASH_B","name":"Bad hash type","providerId":"fixture","providerName":"Fixture"}]}"""

        /** result 应逐项跳过两个坏字段对象。 */
        val result = BookmarkImportParser.parse(ByteArrayInputStream(json.toByteArray())) as BookmarkImportResult.Success

        assertTrue(result.bookmarks.isEmpty())
        assertEquals(2, result.skippedCount)
    }

    /** 损坏 JSON、错误根类型和未知 envelope 版本必须整包失败并给出稳定分类。 */
    @Test
    fun parseClassifiesPackageLevelFailures() {
        /** cases 固定包级失败输入与预期 reasonCode。 */
        val cases = listOf(
            "" to BookmarkImportFailureReason.EMPTY,
            "{" to BookmarkImportFailureReason.MALFORMED_JSON,
            "{}" to BookmarkImportFailureReason.INVALID_ROOT,
            "{\"version\":\"1\",\"bookmarks\":[]}" to BookmarkImportFailureReason.INVALID_ROOT,
            "{\"version\":2,\"bookmarks\":[]}" to BookmarkImportFailureReason.UNSUPPORTED_VERSION,
        )

        /** case 是当前包级输入及预期失败分类。 */
        cases.forEach { case ->
            assertEquals(
                BookmarkImportResult.Failure(case.second),
                BookmarkImportParser.parse(ByteArrayInputStream(case.first.toByteArray())),
            )
        }
    }

    /** 精确 10 MiB 合法 envelope 可解析，超过一个字节必须在 JSON 解析前拒绝。 */
    @Test
    fun parseEnforcesTenMebibyteLimit() {
        /** prefix 与 suffix 构成允许忽略 padding 字段的合法 envelope。 */
        val prefix = "{\"version\":1,\"bookmarks\":[],\"padding\":\""
        /** suffix 关闭 padding 字符串与 JSON 对象。 */
        val suffix = "\"}"
        /** paddingLength 使 UTF-8 ASCII 字节总数精确等于协议上限。 */
        val paddingLength = BookmarkImportParser.MAX_IMPORT_BYTES - prefix.length - suffix.length
        /** exactLimitJson 是精确上限且无用户数据的合成 JSON。 */
        val exactLimitJson = prefix + "x".repeat(paddingLength) + suffix
        /** overLimitJson 只增加一个 ASCII 字节。 */
        val overLimitJson = prefix + "x".repeat(paddingLength + 1) + suffix

        assertTrue(BookmarkImportParser.parse(ByteArrayInputStream(exactLimitJson.toByteArray())) is BookmarkImportResult.Success)
        assertEquals(
            BookmarkImportResult.Failure(BookmarkImportFailureReason.TOO_LARGE),
            BookmarkImportParser.parse(ByteArrayInputStream(overLimitJson.toByteArray())),
        )
    }

    /** JSON 结构深度和条目数必须各自有上限，避免小文件形成大量 DOM 节点。 */
    @Test
    fun parseRejectsExcessiveJsonDepthAndItemCount() {
        /** deepJson 在未知字段构造超过安全深度的嵌套数组。 */
        val deepJson = "{\"version\":1,\"bookmarks\":[],\"padding\":" +
            "[".repeat(BookmarkImportParser.MAX_JSON_DEPTH + 1) +
            "null" +
            "]".repeat(BookmarkImportParser.MAX_JSON_DEPTH + 1) +
            "}"
        /** excessiveItemsJson 使用 null 项避免生成用户数据，只验证固定条目上限。 */
        val excessiveItemsJson = "[" + "null,".repeat(BookmarkImportParser.MAX_IMPORT_ITEMS) + "null]"

        assertEquals(
            BookmarkImportResult.Failure(BookmarkImportFailureReason.JSON_TOO_DEEP),
            BookmarkImportParser.parse(ByteArrayInputStream(deepJson.toByteArray())),
        )
        assertEquals(
            BookmarkImportResult.Failure(BookmarkImportFailureReason.TOO_MANY_ITEMS),
            BookmarkImportParser.parse(ByteArrayInputStream(excessiveItemsJson.toByteArray())),
        )
    }

    /** 超大单项必须在创建 substring 和 DOM 前跳过，同时继续导入后续合法项。 */
    @Test
    fun parseSkipsOversizedSingleItemBeforeDomParsing() {
        /** oversizedItem 用未知字段模拟接近上限的恶意对象，不携带真实用户内容。 */
        val oversizedItem = "{\"padding\":\"" +
            "x".repeat(BookmarkImportParser.MAX_BOOKMARK_ITEM_CHARS + 1) +
            "\"}"
        /** validItem 证明跳过超大条目后扫描和逐项容错仍可继续。 */
        val validItem = "{\"infoHash\":\"$HASH_A\",\"name\":\"Valid\",\"providerName\":\"Fixture\"}"
        /** json 使用固定上游数组，使测试只关注单项资源边界。 */
        val json = "[$oversizedItem,$validItem]"

        /** result 应保留后续合法项并把超大单项计入跳过。 */
        val result = BookmarkImportParser.parse(ByteArrayInputStream(json.toByteArray())) as BookmarkImportResult.Success

        assertEquals(listOf(HASH_A), result.bookmarks.map { /** bookmark 是未超限的合法条目。 */ bookmark -> bookmark.infoHash })
        assertEquals(1, result.skippedCount)
    }

    /** envelope 字段名必须有小上限，避免未知巨型 key 被完整解码并长期保留。 */
    @Test
    fun parseRejectsOversizedEnvelopeKey() {
        /** oversizedKey 超过协议 key 上限但整包仍远低于 10 MiB。 */
        val oversizedKey = "x".repeat(BookmarkImportParser.MAX_ENVELOPE_KEY_CHARS + 1)
        /** json 把未知超长 key 放在必需字段前，验证扫描器立即停止。 */
        val json = "{\"$oversizedKey\":null,\"version\":1,\"bookmarks\":[]}"

        assertEquals(
            BookmarkImportResult.Failure(BookmarkImportFailureReason.ENVELOPE_KEY_TOO_LONG),
            BookmarkImportParser.parse(ByteArrayInputStream(json.toByteArray())),
        )
    }

    /** 合成稳定身份集中定义，避免测试使用真实 magnet 或用户数据。 */
    companion object {
        /** 第一条合成 SHA-1 infoHash。 */
        private const val HASH_A = "0123456789012345678901234567890123456789"
        /** 第二条合成 SHA-1 infoHash。 */
        private const val HASH_B = "abcdefabcdefabcdefabcdefabcdefabcdefabcd"
    }
}
