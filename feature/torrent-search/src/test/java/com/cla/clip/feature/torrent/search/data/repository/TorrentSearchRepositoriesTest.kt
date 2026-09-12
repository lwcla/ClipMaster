package com.cla.clip.feature.torrent.search.data.repository

import com.cla.clip.feature.torrent.search.data.local.BookmarkedTorrentDao
import com.cla.clip.feature.torrent.search.data.local.BookmarkedTorrentEntity
import com.cla.clip.feature.torrent.search.data.local.TorrentSearchHistoryDao
import com.cla.clip.feature.torrent.search.data.local.TorrentSearchHistoryEntity
import com.cla.clip.feature.torrent.search.data.local.ViewedTorrentDao
import com.cla.clip.feature.torrent.search.data.local.ViewedTorrentEntity
import com.cla.clip.feature.torrent.search.data.model.BookmarkedTorrent
import com.cla.clip.feature.torrent.search.provider.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TorrentSearch 三类用户数据 Repository 的规范化、去重和边界测试。 */
class TorrentSearchRepositoriesTest {
    /** 书签必须按规范化 infoHash 替换，同名但不同 hash 仍允许共存。 */
    @Test
    fun bookmarkRepositoryUsesCanonicalInfoHashAsStableIdentity() = runTest {
        /** dao 用内存 Map 模拟 Room 主键替换，测试真实 Repository 规则而不是 mock 调用次数。 */
        val dao = FakeBookmarkedTorrentDao()
        /** repository 的时间来源固定，避免断言依赖真实时钟。 */
        val repository = BookmarkRepositoryImpl(dao = dao, nowMillis = { 100L })

        assertTrue(repository.save(bookmark(hash = HASH_A.uppercase(), name = "first")))
        assertTrue(repository.save(bookmark(hash = HASH_A, name = "updated")))
        assertTrue(repository.save(bookmark(hash = HASH_B, name = "updated")))

        /** storedBookmarks 是页面可消费的领域模型，不包含 Room 注解实体。 */
        val storedBookmarks = repository.observeBookmarks().first()
        assertEquals(2, storedBookmarks.size)
        /** item lambda 按稳定 hash 定位刷新后的书签。 */
        assertEquals("updated", storedBookmarks.first { item -> item.infoHash == HASH_A }.name)
        /** item lambda 提取稳定身份集合，验证同名不同 hash 共存。 */
        assertEquals(setOf(HASH_A, HASH_B), storedBookmarks.map { item -> item.infoHash }.toSet())
    }

    /** 详情书签状态必须使用精确 infoHash 查询，不依赖整表快照判断。 */
    @Test
    fun bookmarkRepositoryObservesOneCanonicalInfoHashExactly() = runTest {
        /** dao 使用内存主键语义提供精确存在状态。 */
        val dao = FakeBookmarkedTorrentDao()
        /** repository 负责把大写输入规范化成 DAO 稳定键。 */
        val repository = BookmarkRepositoryImpl(dao = dao, nowMillis = { 100L })

        assertFalse(repository.observeBookmarked(HASH_A.uppercase()).first())
        assertTrue(repository.save(bookmark(hash = HASH_A, name = "Synthetic")))
        assertTrue(repository.observeBookmarked(HASH_A.uppercase()).first())
    }

    /** 非法 hash 不得进入书签或已查看稳定键。 */
    @Test
    fun repositoriesRejectMalformedInfoHashes() = runTest {
        /** bookmarkDao 记录书签写入结果。 */
        val bookmarkDao = FakeBookmarkedTorrentDao()
        /** viewedDao 记录已查看写入结果。 */
        val viewedDao = FakeViewedTorrentDao()
        /** bookmarkRepository 使用固定时钟且不依赖 Android。 */
        val bookmarkRepository = BookmarkRepositoryImpl(bookmarkDao, nowMillis = { 100L })
        /** viewedRepository 使用另一个固定时钟验证拒绝分支不会写入。 */
        val viewedRepository = ViewedTorrentRepositoryImpl(viewedDao, nowMillis = { 200L })

        assertFalse(bookmarkRepository.save(bookmark(hash = "not-a-hash", name = "invalid")))
        assertFalse(viewedRepository.markViewed("1234"))
        assertTrue(bookmarkDao.observeAll().first().isEmpty())
        assertTrue(viewedDao.observeAll().first().isEmpty())
    }

    /** 实际保存入口必须接受 512 字符名称并拒绝 513 字符及越界可选字段。 */
    @Test
    fun bookmarkRepositoryMatchesImportAndExportItemBoundaries() = runTest {
        /** dao 记录所有通过统一协议校验的真实实体。 */
        val dao = FakeBookmarkedTorrentDao()
        /** repository 使用固定合法时间戳。 */
        val repository = BookmarkRepositoryImpl(dao, nowMillis = { 100L })

        assertTrue(repository.save(bookmark(HASH_A, "n".repeat(512))))
        assertFalse(repository.save(bookmark(HASH_B, "n".repeat(513))))
        assertFalse(repository.save(bookmark(HASH_B, "valid").copy(size = "s".repeat(129))))
        assertFalse(repository.save(bookmark(HASH_B, "valid").copy(uploadEpochMillis = -1L)))
        assertFalse(repository.save(bookmark(HASH_B, "valid").copy(descriptionPageUrl = "file:///private")))
        assertFalse(repository.save(bookmark(HASH_B, "valid").copy(magnetUri = "magnet:?xt=urn:btih:$HASH_A")))
        assertEquals(1, repository.observeBookmarks().first().size)
    }

    /** 搜索历史必须复用入口清洗、按小写稳定键去重，并拒绝超限文本。 */
    @Test
    fun historyRepositorySanitizesDeduplicatesAndRejectsOverLimitQueries() = runTest {
        /** dao 用规范化查询 Map 模拟唯一索引与最近时间排序。 */
        val dao = FakeTorrentSearchHistoryDao()
        /** timestamps 为两次合法写入提供确定性更新时间。 */
        val timestamps = ArrayDeque(listOf(10L, 20L))
        /** repository 读取固定时间队列，非法第三次写入不得消耗时间。 */
        val repository = TorrentSearchHistoryRepositoryImpl(dao = dao, nowMillis = { timestamps.removeFirst() })

        assertTrue(repository.saveQuery("  Linux\nISO  "))
        assertTrue(repository.saveQuery("linuxiso"))
        assertFalse(repository.saveQuery("x".repeat(513)))

        /** histories 应只有一个由第二次合法输入刷新的稳定记录。 */
        val histories = repository.observeHistory().first()
        assertEquals(1, histories.size)
        assertEquals("linuxiso", histories.single().query)
        assertEquals("linuxiso", histories.single().normalizedQuery)
        assertEquals(20L, histories.single().updatedAtEpochMillis)
    }

    /** 重复查看同一 hash 应刷新时间而不是新增记录。 */
    @Test
    fun viewedRepositoryRefreshesExistingStableIdentity() = runTest {
        /** dao 用 infoHash 主键 Map 模拟 Room REPLACE。 */
        val dao = FakeViewedTorrentDao()
        /** timestamps 固定两次查看时间。 */
        val timestamps = ArrayDeque(listOf(30L, 40L))
        /** repository 负责 hash 规范化与时间读取。 */
        val repository = ViewedTorrentRepositoryImpl(dao = dao, nowMillis = { timestamps.removeFirst() })

        assertTrue(repository.markViewed(HASH_A.uppercase()))
        assertTrue(repository.markViewed(HASH_A))

        /** viewed 只保留一条并使用最近查看时间。 */
        val viewed = repository.observeViewed().first()
        assertEquals(1, viewed.size)
        assertEquals(HASH_A, viewed.single().infoHash)
        assertEquals(40L, viewed.single().viewedAtEpochMillis)
    }

    /** 创建最小书签领域模型，显式 hash/name 之外字段使用中性合成值。 */
    private fun bookmark(hash: String, name: String): BookmarkedTorrent {
        return BookmarkedTorrent(
            infoHash = hash,
            name = name,
            providerId = ProviderId("fixture"),
            providerName = "Fixture",
        )
    }

    /** 测试稳定身份集中定义，避免各用例生成不一致 fixture。 */
    companion object {
        /** 第一条合成 SHA-1 infoHash。 */
        private const val HASH_A = "0123456789012345678901234567890123456789"
        /** 第二条合成 SHA-1 infoHash，允许同名书签共存。 */
        private const val HASH_B = "abcdefabcdefabcdefabcdefabcdefabcdefabcd"
    }
}

/** 内存书签 DAO，通过 Map 主键语义模拟 Room REPLACE 和时间倒序。 */
private class FakeBookmarkedTorrentDao : BookmarkedTorrentDao {
    /** rows 保存规范化 infoHash 到实体的当前快照。 */
    private val rows = linkedMapOf<String, BookmarkedTorrentEntity>()
    /** state 向 Repository 提供可重复收集的实体流。 */
    private val state = MutableStateFlow<List<BookmarkedTorrentEntity>>(emptyList())

    /** 观察按书签时间倒序的全部记录。 */
    override fun observeAll(): Flow<List<BookmarkedTorrentEntity>> = state

    /** 精确观察指定稳定 hash 是否存在。 */
    override fun observeExistsByInfoHash(infoHash: String): Flow<Boolean> {
        return state.map { /** entities 是每次主键变化后的完整 fake 快照。 */ entities ->
            entities.any { /** entity 是待与精确稳定键比较的行。 */ entity -> entity.infoHash == infoHash }
        }
    }

    /** 插入或替换同 hash 书签。 */
    override suspend fun upsert(entity: BookmarkedTorrentEntity) {
        rows[entity.infoHash] = entity
        /** item lambda 模拟 Room 的最近书签排序。 */
        state.value = rows.values.sortedByDescending { item -> item.bookmarkedAtEpochMillis }
    }

    /** 按稳定 hash 删除书签。 */
    override suspend fun deleteByInfoHash(infoHash: String): Int {
        /** removed 表示主键是否存在。 */
        val removed = rows.remove(infoHash) != null
        state.value = rows.values.toList()
        return if (removed) 1 else 0
    }

    /** 清空全部书签并返回删除数量。 */
    override suspend fun clear(): Int {
        /** count 是清理前行数。 */
        val count = rows.size
        rows.clear()
        state.value = emptyList()
        return count
    }
}

/** 内存搜索历史 DAO，模拟 normalized_query 唯一索引和更新时间倒序。 */
private class FakeTorrentSearchHistoryDao : TorrentSearchHistoryDao {
    /** rows 保存规范化查询到实体的当前快照。 */
    private val rows = linkedMapOf<String, TorrentSearchHistoryEntity>()
    /** state 向 Repository 提供最近历史流。 */
    private val state = MutableStateFlow<List<TorrentSearchHistoryEntity>>(emptyList())

    /** 观察最近历史；fake 数据量小于固定上限。 */
    override fun observeRecent(limit: Int): Flow<List<TorrentSearchHistoryEntity>> = state

    /** 插入或替换同规范化查询，并刷新倒序快照。 */
    override suspend fun upsert(entity: TorrentSearchHistoryEntity) {
        rows[entity.normalizedQuery] = entity
        /** item lambda 模拟 Room 的最近历史排序。 */
        state.value = rows.values.sortedByDescending { item -> item.updatedAtEpochMillis }
    }

    /** 删除指定规范化查询。 */
    override suspend fun deleteByNormalizedQuery(normalizedQuery: String): Int {
        /** removed 表示稳定键是否存在。 */
        val removed = rows.remove(normalizedQuery) != null
        state.value = rows.values.toList()
        return if (removed) 1 else 0
    }

    /** 清空历史并返回删除数量。 */
    override suspend fun clear(): Int {
        /** count 是清理前行数。 */
        val count = rows.size
        rows.clear()
        state.value = emptyList()
        return count
    }

    /** 裁剪最近数量；fake 使用与生产相同的时间倒序语义。 */
    override suspend fun trimToLimit(limit: Int): Int {
        /** retained 保留时间最新的固定数量。 */
        val retained = rows.values.sortedByDescending { item -> item.updatedAtEpochMillis }.take(limit)
        /** removedCount 是本次被裁剪的旧记录数。 */
        val removedCount = rows.size - retained.size
        rows.clear()
        /** item lambda 按稳定查询键重建裁剪后的 Map。 */
        retained.forEach { item -> rows[item.normalizedQuery] = item }
        state.value = retained
        return removedCount
    }
}

/** 内存已查看 DAO，通过 infoHash 主键语义模拟重复查看刷新。 */
private class FakeViewedTorrentDao : ViewedTorrentDao {
    /** rows 保存稳定 infoHash 到最近查看实体。 */
    private val rows = linkedMapOf<String, ViewedTorrentEntity>()
    /** state 向 Repository 提供时间倒序实体流。 */
    private val state = MutableStateFlow<List<ViewedTorrentEntity>>(emptyList())

    /** 观察按查看时间倒序的全部记录。 */
    override fun observeAll(): Flow<List<ViewedTorrentEntity>> = state

    /** 插入或替换同 hash 的最近查看时间。 */
    override suspend fun upsert(entity: ViewedTorrentEntity) {
        rows[entity.infoHash] = entity
        /** item lambda 模拟 Room 的最近查看排序。 */
        state.value = rows.values.sortedByDescending { item -> item.viewedAtEpochMillis }
    }

    /** 清空已查看记录并返回删除数量。 */
    override suspend fun clear(): Int {
        /** count 是清理前行数。 */
        val count = rows.size
        rows.clear()
        state.value = emptyList()
        return count
    }
}
