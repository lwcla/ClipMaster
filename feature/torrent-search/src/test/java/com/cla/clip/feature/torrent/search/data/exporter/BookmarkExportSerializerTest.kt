package com.cla.clip.feature.torrent.search.data.exporter

import com.cla.clip.feature.torrent.search.data.importer.BookmarkImportParser
import com.cla.clip.feature.torrent.search.data.importer.BookmarkImportResult
import com.cla.clip.feature.torrent.search.data.model.BookmarkedTorrent
import com.cla.clip.feature.torrent.search.provider.ProviderId
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 书签 v1 导出协议的稳定字段、往返与硬上限回归。 */
class BookmarkExportSerializerTest {
    /** 特殊字符必须由 JSON 正确转义，并能被同一 05C 解析器无损读回。 */
    @Test
    fun serializeUsesV1EnvelopeAndRoundTripsSpecialCharacters() {
        /** bookmark 使用中性特殊字符覆盖引号、反斜杠与非 ASCII 文本转义。 */
        val bookmark = bookmark(
            hash = HASH_A,
            name = "Synthetic \"quoted\" \\ 雪",
        )

        /** result 是受 10 MiB 上限保护的导出结果。 */
        val result = BookmarkExportSerializer.serialize(listOf(bookmark))

        assertTrue(result is BookmarkExportResult.Success)
        /** bytes 是成功结果中的固定 UTF-8 v1 JSON。 */
        val bytes = (result as BookmarkExportResult.Success).bytes
        /** json 只用于断言协议字段与紧凑输出，不进入生产日志。 */
        val json = bytes.toString(Charsets.UTF_8)
        assertTrue(json.startsWith("{\"version\":1,\"bookmarks\":["))
        /** parsed 复用生产导入解析器验证导出与导入协议兼容。 */
        val parsed = BookmarkImportParser.parse(ByteArrayInputStream(bytes))
        assertTrue(parsed is BookmarkImportResult.Success)
        assertEquals(bookmark.name, (parsed as BookmarkImportResult.Success).bookmarks.single().name)
    }

    /** 超过 10,000 条时必须整体失败，不能静默截断成貌似成功的文件。 */
    @Test
    fun serializeRejectsMoreThanTenThousandBookmarksWithoutTruncation() {
        /** bookmarks 刻意超过解析器条目上限一条。 */
        val bookmarks = (0..BookmarkImportParser.MAX_IMPORT_ITEMS).map { /** index 生成唯一合成 hash。 */ index ->
            bookmark(hash = index.toString(16).padStart(40, '0'), name = "Synthetic")
        }

        /** result 应保留固定失败分类而不包含部分 bytes。 */
        val result = BookmarkExportSerializer.serialize(bookmarks)

        assertEquals(BookmarkExportResult.Failure(BookmarkExportFailureReason.TOO_MANY_ITEMS), result)
    }

    /** 恰好 10,000 条小记录允许完整导出，条目边界不能误用大于等于。 */
    @Test
    fun serializeAcceptsExactlyTenThousandSmallBookmarks() {
        /** bookmarks 恰好命中协议条目上限且保持文档小于 10 MiB。 */
        val bookmarks = (0 until BookmarkImportParser.MAX_IMPORT_ITEMS).map { /** index 生成唯一合成 hash。 */ index ->
            bookmark(hash = index.toString(16).padStart(40, '0'), name = "S")
        }

        assertTrue(BookmarkExportSerializer.serialize(bookmarks) is BookmarkExportResult.Success)
    }

    /** 最终文档超过 10 MiB 时必须整体失败且不返回可写出的部分字节。 */
    @Test
    fun serializeRejectsDocumentOverTenMebibytesWithoutTruncation() {
        /** longUrl 每项保持在 05C 单字段上限内，但批次总量必然超过 10 MiB。 */
        val longUrl = "https://example.invalid/" + "x".repeat(8_100)
        /** bookmarks 使用不同稳定身份避免生成器去重假设。 */
        val bookmarks = (0 until 1_500).map { /** index 生成唯一合成书签。 */ index ->
            bookmark(hash = index.toString(16).padStart(40, '0'), name = "S").copy(descriptionPageUrl = longUrl)
        }

        assertEquals(BookmarkExportResult.Failure(BookmarkExportFailureReason.TOO_LARGE), BookmarkExportSerializer.serialize(bookmarks))
    }

    /** 构造字段完整但不含真实用户数据的书签。 */
    private fun bookmark(
        /** 合成稳定身份。 */
        hash: String,
        /** 合成展示名称。 */
        name: String,
    ): BookmarkedTorrent = BookmarkedTorrent(
        infoHash = hash,
        name = name,
        providerId = ProviderId("fixture"),
        providerName = "Fixture",
        magnetUri = "magnet:?xt=urn:btih:$hash",
        bookmarkedAtEpochMillis = 42L,
    )

    /** 合成稳定身份集中定义，避免测试包含真实 torrent。 */
    companion object {
        /** 第一条合成 SHA-1 infoHash。 */
        private const val HASH_A = "0123456789012345678901234567890123456789"
    }
}
