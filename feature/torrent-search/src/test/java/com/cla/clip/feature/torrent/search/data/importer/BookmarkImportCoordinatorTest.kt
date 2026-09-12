package com.cla.clip.feature.torrent.search.data.importer

import com.cla.clip.feature.torrent.search.data.model.BookmarkedTorrent
import com.cla.clip.feature.torrent.search.data.repository.BookmarkRepository
import com.cla.clip.feature.torrent.search.provider.ProviderId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** 书签导入逐项持久化、重复/跳过/失败计数与取消传播测试。 */
class BookmarkImportCoordinatorTest {
    /** 已存在身份和单项写失败必须分别计数，后续合法项仍继续写入。 */
    @Test
    fun persistCountsDuplicatesSkipsAndItemWriteFailuresWhileContinuing() = runTest {
        /** repository 预置一条重复身份，并让第二条新记录写入失败。 */
        val repository = FakeBookmarkRepository(existing = mutableSetOf(HASH_A), failingHash = HASH_B)
        /** coordinator 执行真实逐项状态判断。 */
        val coordinator = BookmarkImportCoordinator(repository)
        /** parsed 模拟 05C 已完成语法、字段与文件内去重的结果。 */
        val parsed = BookmarkImportResult.Success(
            bookmarks = listOf(bookmark(HASH_A), bookmark(HASH_B), bookmark(HASH_C)),
            skippedCount = 2,
            duplicateCount = 1,
        )

        /** report 是不含业务内容的最终计数。 */
        val report = coordinator.persist(parsed)

        assertEquals(BookmarkPersistenceReport(1, 2, 2, 1), report)
        assertEquals(listOf(HASH_B, HASH_C), repository.savedHashes)
    }

    /** Repository 取消必须原样传播，不能误计为可继续的单项失败。 */
    @Test
    fun persistPropagatesCancellation() = runTest {
        /** repository 在保存第一项时抛出结构化取消。 */
        val repository = FakeBookmarkRepository(existing = mutableSetOf(), cancellingHash = HASH_A)
        /** coordinator 是被验证的真实导入协作者。 */
        val coordinator = BookmarkImportCoordinator(repository)
        try {
            coordinator.persist(BookmarkImportResult.Success(listOf(bookmark(HASH_A)), 0, 0))
            fail("预期导入取消向上传播")
        /** 捕获取消只用于证明同一个固定取消向上传播。 */
        } catch (cancellation: CancellationException) {
            assertEquals("synthetic_cancel", cancellation.message)
        }
    }

    /** 构造最小合法合成书签。 */
    private fun bookmark(hash: String): BookmarkedTorrent = BookmarkedTorrent(
        infoHash = hash,
        name = "Synthetic",
        providerId = ProviderId("fixture"),
        providerName = "Fixture",
    )

    /** 合成稳定身份集中定义。 */
    companion object {
        /** 已存在身份。 */
        private const val HASH_A = "0123456789012345678901234567890123456789"
        /** 写失败身份。 */
        private const val HASH_B = "abcdefabcdefabcdefabcdefabcdefabcdefabcd"
        /** 正常写入身份。 */
        private const val HASH_C = "1111111111111111111111111111111111111111"
    }
}

/** 导入测试专用内存书签 Repository。 */
private class FakeBookmarkRepository(
    /** 当前已存在的稳定身份。 */
    private val existing: MutableSet<String>,
    /** 返回 false 的合成写失败身份。 */
    private val failingHash: String? = null,
    /** 抛取消的合成身份。 */
    private val cancellingHash: String? = null,
) : BookmarkRepository {
    /** 按实际写入尝试顺序记录 hash。 */
    val savedHashes = mutableListOf<String>()

    /** 本测试不观察整表。 */
    override fun observeBookmarks(): Flow<List<BookmarkedTorrent>> = flowOf(emptyList())

    /** 精确返回当前稳定身份是否存在。 */
    override fun observeBookmarked(infoHash: String): Flow<Boolean> = flowOf(infoHash in existing)

    /** 模拟成功、固定失败或结构化取消。 */
    override suspend fun save(bookmark: BookmarkedTorrent): Boolean {
        savedHashes += bookmark.infoHash
        if (bookmark.infoHash == cancellingHash) throw CancellationException("synthetic_cancel")
        if (bookmark.infoHash == failingHash) return false
        existing += bookmark.infoHash
        return true
    }

    /** 本测试不删除书签。 */
    override suspend fun delete(infoHash: String): Boolean = false

    /** 本测试不清空书签。 */
    override suspend fun clear(): Int = 0
}
