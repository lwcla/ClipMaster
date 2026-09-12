package com.cla.clip.feature.torrent.search.data.exporter

import com.cla.clip.feature.torrent.search.data.importer.BookmarkImportParser
import com.cla.clip.feature.torrent.search.data.importer.BookmarkImportResult
import com.cla.clip.feature.torrent.search.data.model.BookmarkedTorrent
import com.cla.clip.feature.torrent.search.provider.ProviderId
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 书签 v1 导出协议的稳定字段、往返与硬上限回归。 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkExportSerializerTest {
    /** 特殊字符必须由 JSON 正确转义，并能被同一 05C 解析器无损读回。 */
    @Test
    fun serializeUsesV1EnvelopeAndRoundTripsSpecialCharacters() = runTest {
        /** bookmark 使用中性特殊字符覆盖引号、反斜杠与非 ASCII 文本转义。 */
        val bookmark = bookmark(
            hash = HASH_A,
            name = "Synthetic \"quoted\" \\ 雪",
        )

        /** result 是受 10 MiB 上限保护的导出结果。 */
        val result = BookmarkExportSerializer().serialize(listOf(bookmark))

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
    fun serializeRejectsMoreThanTenThousandBookmarksWithoutTruncation() = runTest {
        /** bookmarks 刻意超过解析器条目上限一条。 */
        val bookmarks = (0..BookmarkImportParser.MAX_IMPORT_ITEMS).map { /** index 生成唯一合成 hash。 */ index ->
            bookmark(hash = index.toString(16).padStart(40, '0'), name = "Synthetic")
        }

        /** result 应保留固定失败分类而不包含部分 bytes。 */
        val result = BookmarkExportSerializer().serialize(bookmarks)

        assertEquals(BookmarkExportResult.Failure(BookmarkExportFailureReason.TOO_MANY_ITEMS), result)
    }

    /** 恰好 10,000 条小记录允许完整导出，条目边界不能误用大于等于。 */
    @Test
    fun serializeAcceptsExactlyTenThousandSmallBookmarks() = runTest {
        /** bookmarks 恰好命中协议条目上限且保持文档小于 10 MiB。 */
        val bookmarks = (0 until BookmarkImportParser.MAX_IMPORT_ITEMS).map { /** index 生成唯一合成 hash。 */ index ->
            bookmark(hash = index.toString(16).padStart(40, '0'), name = "S")
        }

        assertTrue(BookmarkExportSerializer().serialize(bookmarks) is BookmarkExportResult.Success)
    }

    /** 最终文档超过 10 MiB 时必须整体失败且不返回可写出的部分字节。 */
    @Test
    fun serializeRejectsDocumentOverTenMebibytesWithoutTruncation() = runTest {
        /** longUrl 每项保持在 05C 单字段上限内，但批次总量必然超过 10 MiB。 */
        val longUrl = "https://example.invalid/" + "x".repeat(8_100)
        /** bookmarks 使用不同稳定身份避免生成器去重假设。 */
        val bookmarks = (0 until 1_500).map { /** index 生成唯一合成书签。 */ index ->
            bookmark(hash = index.toString(16).padStart(40, '0'), name = "S").copy(descriptionPageUrl = longUrl)
        }

        assertEquals(BookmarkExportResult.Failure(BookmarkExportFailureReason.TOO_LARGE), BookmarkExportSerializer().serialize(bookmarks))
    }

    /** 编码必须切到调用方可替换的后台 dispatcher，而不是立即占用当前 Main。 */
    @Test
    fun serializeRunsOnTheInjectedDispatcher() = runTest {
        /** encodingDispatcher 与测试调用协程共享 scheduler 但需显式调度。 */
        val encodingDispatcher = StandardTestDispatcher(testScheduler)
        /** serializer 使用受测试控制的后台执行器。 */
        val serializer = BookmarkExportSerializer(encodingDispatcher)
        /** result 是尚未执行后台队列的编码任务。 */
        val result = async { serializer.serialize(listOf(bookmark(HASH_A, "Synthetic"))) }

        assertFalse(result.isCompleted)
        runCurrent()
        assertTrue(result.await() is BookmarkExportResult.Success)
    }

    /** 逐项编码在取消后不得继续遍历剩余快照。 */
    @Test
    fun serializeChecksCancellationBetweenItems() = runTest {
        /** executor 提供可与测试线程并行取消的单一后台线程。 */
        val executor = Executors.newSingleThreadExecutor()
        /** encodingDispatcher 是 serializer 的真实可替换后台边界。 */
        val encodingDispatcher = executor.asCoroutineDispatcher()
        try {
            /** bookmarks 在第二项访问时阻塞，稳定暴露逐项取消点。 */
            val bookmarks = BlockingBookmarkList(bookmark(HASH_A, "Synthetic"))
            /** result 执行后台逐项编码。 */
            val result = async { BookmarkExportSerializer(encodingDispatcher).serialize(bookmarks) }
            runCurrent()
            assertTrue(bookmarks.secondItemReached.await(2, TimeUnit.SECONDS))

            result.cancel()
            bookmarks.releaseSecondItem.countDown()
            result.cancelAndJoin()

            assertEquals(2, bookmarks.accessCount)
        } finally {
            encodingDispatcher.close()
            executor.shutdownNow()
        }
    }

    /** 名称 512 字符可往返，513 字符必须在生成任何成功文档前拒绝。 */
    @Test
    fun serializeMatchesParserNameBoundaries() = runTest {
        /** serializer 直接验证与 05C 相同的单项边界。 */
        val serializer = BookmarkExportSerializer(StandardTestDispatcher(testScheduler))
        /** accepted 是恰好命中名称上限的完整结果。 */
        val accepted = async { serializer.serialize(listOf(bookmark(HASH_A, "n".repeat(512)))) }
        runCurrent()
        assertTrue(accepted.await() is BookmarkExportResult.Success)
        /** rejected 是超过名称上限一字符的固定失败。 */
        val rejected = async { serializer.serialize(listOf(bookmark(HASH_A, "n".repeat(513)))) }
        runCurrent()
        assertEquals(BookmarkExportResult.Failure(BookmarkExportFailureReason.INVALID_ITEM), rejected.await())
    }

    /** 所有可选字段在各自合法边界都必须由导出文件完整恢复。 */
    @Test
    fun serializeRoundTripsEveryOptionalFieldAtItsBoundary() = runTest {
        /** bookmark 覆盖 UInt、时间、分类、128 字符 size 与三个合法链接字段。 */
        val bookmark = bookmark(HASH_A, "n".repeat(512)).copy(
            size = "s".repeat(128),
            seeders = UInt.MAX_VALUE,
            peers = UInt.MAX_VALUE,
            uploadEpochMillis = Long.MAX_VALUE,
            category = com.cla.clip.feature.torrent.search.model.contract.Category.ANIME,
            descriptionPageUrl = "https://example.invalid/details",
            magnetUri = "magnet:?xt=urn:btih:$HASH_A",
            fileDownloadLink = "https://example.invalid/file.torrent",
            bookmarkedAtEpochMillis = Long.MAX_VALUE,
        )
        /** result 是完整可恢复的稳定 v1 文档。 */
        val result = BookmarkExportSerializer(StandardTestDispatcher(testScheduler)).serialize(bookmark.let(::listOf))
        /** parsed 使用实际生产解析器读回所有字段。 */
        val parsed = BookmarkImportParser.parse(ByteArrayInputStream((result as BookmarkExportResult.Success).bytes)) as BookmarkImportResult.Success

        assertEquals(bookmark, parsed.bookmarks.single())
    }

    /** 规范化后超过单字段上限的 Unicode URL 必须拒绝，不能返回 parser 会跳过的 Success。 */
    @Test
    fun serializeRejectsUnicodeUrlWhoseNormalizedFormExceedsParserLimit() = runTest {
        /** expandingUrl 原始长度允许，但百分号编码后的最终字符串超过 8192。 */
        val expandingUrl = "https://example.invalid/" + "雪".repeat(1_000)
        /** candidate 复现旧实现会先报告 Success、随后 parser skipped 的坏记录。 */
        val candidate = bookmark(HASH_A, "Synthetic").copy(descriptionPageUrl = expandingUrl)

        assertEquals(
            BookmarkExportResult.Failure(BookmarkExportFailureReason.INVALID_ITEM),
            BookmarkExportSerializer().serialize(listOf(candidate)),
        )
    }

    /** 普通 Unicode v1 URL 仍应导出成功并由 parser 以零 skipped 完整恢复。 */
    @Test
    fun serializeRoundTripsOrdinaryUnicodeUrlsWithoutSkippedItems() = runTest {
        /** candidate 的两个 URL 会被 OkHttp 规范化为仍在上限内的百分号表示。 */
        val candidate = bookmark(HASH_A, "Synthetic").copy(
            descriptionPageUrl = "https://example.invalid/雪",
            fileDownloadLink = "https://example.invalid/文件/雪",
        )
        /** serialized 必须是 parser 可完整恢复的成功结果。 */
        val serialized = BookmarkExportSerializer().serialize(listOf(candidate)) as BookmarkExportResult.Success
        /** parsed 通过真实 05C parser 验证没有单项被静默跳过。 */
        val parsed = BookmarkImportParser.parse(ByteArrayInputStream(serialized.bytes)) as BookmarkImportResult.Success

        assertEquals(0, parsed.skippedCount)
        assertEquals(1, parsed.bookmarks.size)
        assertEquals("https://example.invalid/%E9%9B%AA", parsed.bookmarks.single().descriptionPageUrl)
        assertEquals("https://example.invalid/%E6%96%87%E4%BB%B6/%E9%9B%AA", parsed.bookmarks.single().fileDownloadLink)
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

/** 在第二项访问处提供跨线程取消窗口的合成列表。 */
private class BlockingBookmarkList(
    /** 所有位置复用的合法合成书签。 */
    private val bookmark: BookmarkedTorrent,
) : AbstractList<BookmarkedTorrent>() {
    /** secondItemReached 标记后台编码已完成首项。 */
    val secondItemReached = CountDownLatch(1)
    /** releaseSecondItem 允许测试在线程外取消后恢复列表读取。 */
    val releaseSecondItem = CountDownLatch(1)
    /** accessCount 记录取消前实际访问的条目数量。 */
    @Volatile var accessCount = 0
    /** 固定三项可观察取消是否阻止第三项。 */
    override val size: Int = 3

    /** 第二项等待测试发出取消，其他项立即返回。 */
    override fun get(index: Int): BookmarkedTorrent {
        accessCount += 1
        if (index == 1) {
            secondItemReached.countDown()
            releaseSecondItem.await()
        }
        return bookmark
    }
}
