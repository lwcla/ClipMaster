package com.cla.clip.base.general.dao

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cla.clip.base.general.dao.data.ClipDetail
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** ClipDao 分页排序稳定性测试。 */
@RunWith(AndroidJUnit4::class)
class ClipDaoPagingOrderTest {

    /** 内存 Room 数据库；每个测试都重新创建，避免排序样本互相污染。 */
    private lateinit var database: AppDatabase
    /** 被测 ClipDao。 */
    private lateinit var dao: ClipDao

    @Before
    fun setUp() {
        /** AndroidTest 进程里的应用级 Context。 */
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.clipDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    /** 普通列表在时间完全相同的情况下，应使用主键倒序作为最终稳定兜底。 */
    fun loadVisibleClipsUsesIdAsFinalTieBreaker() = runBlocking {
        dao.upsertClipsForBackup(
            listOf(
                clip(id = 41, content = "same-time-41", timestamp = 1_000),
                clip(id = 57, content = "same-time-57", timestamp = 1_000),
                clip(id = 62, content = "same-time-62", timestamp = 1_000),
            )
        )

        /** 刷新第一页后返回的主键顺序。 */
        val ids = dao.loadClipsByFoldState(isFolded = false).refreshIds()

        assertEquals(listOf(62L, 57L, 41L), ids)
    }

    @Test
    /** 折叠列表在 foldedAt 和 timestamp 相同的情况下，也应回退到主键倒序。 */
    fun loadFoldedClipsUsesIdAsFinalTieBreaker() = runBlocking {
        dao.upsertClipsForBackup(
            listOf(
                clip(id = 41, content = "folded-41", timestamp = 1_000, isFolded = true, foldedAt = 2_000),
                clip(id = 57, content = "folded-57", timestamp = 1_000, isFolded = true, foldedAt = 2_000),
                clip(id = 62, content = "folded-62", timestamp = 1_000, isFolded = true, foldedAt = 2_000),
            )
        )

        val ids = dao.loadClipsByFoldState(isFolded = true).refreshIds()

        assertEquals(listOf(62L, 57L, 41L), ids)
    }

    @Test
    /** 回收站列表在 deletedAt 和 timestamp 相同的情况下，也要用主键倒序稳定分页边界。 */
    fun loadRecycleBinClipsUsesIdAsFinalTieBreaker() = runBlocking {
        dao.upsertClipsForBackup(
            listOf(
                clip(id = 41, content = "deleted-41", timestamp = 1_000, deletedAt = 3_000),
                clip(id = 57, content = "deleted-57", timestamp = 1_000, deletedAt = 3_000),
                clip(id = 62, content = "deleted-62", timestamp = 1_000, deletedAt = 3_000),
            )
        )

        val ids = dao.loadRecycleBinClips().refreshIds()

        assertEquals(listOf(62L, 57L, 41L), ids)
    }

    @Test
    /** 无关键词筛选查询也必须沿用主键倒序作为最终兜底。 */
    fun searchFiltersUseIdAsFinalTieBreaker() = runBlocking {
        dao.upsertClipsForBackup(
            listOf(
                clip(id = 41, content = "filter-41", timestamp = 1_000),
                clip(id = 57, content = "filter-57", timestamp = 1_000),
                clip(id = 62, content = "filter-62", timestamp = 1_000),
            )
        )

        val ids = dao.searchClipsByFilters(
            startTime = null,
            endTime = null,
            sourceAppPackageCount = 0,
            sourceAppPackages = listOf("__all_source_apps__"),
            isFolded = false,
            timeFilterUsesFoldedAt = false,
        ).refreshIds()

        assertEquals(listOf(62L, 57L, 41L), ids)
    }

    @Test
    /** LIKE 兜底搜索在命中质量和时间完全一致时也要按主键倒序稳定返回。 */
    fun likeSearchUsesIdAsFinalTieBreaker() = runBlocking {
        dao.upsertClipsForBackup(
            listOf(
                clip(id = 41, content = "repeat keyword 41", timestamp = 1_000),
                clip(id = 57, content = "repeat keyword 57", timestamp = 1_000),
                clip(id = 62, content = "repeat keyword 62", timestamp = 1_000),
            )
        )

        val ids = dao.searchClipsByLike(
            keyword = "keyword",
            startTime = null,
            endTime = null,
            sourceAppPackageCount = 0,
            sourceAppPackages = listOf("__all_source_apps__"),
            isFolded = false,
            timeFilterUsesFoldedAt = false,
        ).refreshIds()

        assertEquals(listOf(62L, 57L, 41L), ids)
    }

    @Test
    /** FTS 关键词搜索在同分同时间场景下也必须使用主键倒序兜底。 */
    fun keywordSearchUsesIdAsFinalTieBreaker() = runBlocking {
        dao.upsertClipsForBackup(
            listOf(
                clip(id = 41, content = "keyword 41", timestamp = 1_000),
                clip(id = 57, content = "keyword 57", timestamp = 1_000),
                clip(id = 62, content = "keyword 62", timestamp = 1_000),
            )
        )

        val ids = dao.searchClipsByKeyword(
            query = "keyword*",
            exactQuery = "keyword",
            queryWord = "keyword",
            likeKeyword = "keyword",
            startTime = null,
            endTime = null,
            sourceAppPackageCount = 0,
            sourceAppPackages = listOf("__all_source_apps__"),
            isFolded = false,
            timeFilterUsesFoldedAt = false,
        ).refreshIds()

        assertEquals(listOf(62L, 57L, 41L), ids)
    }

    @Test
    /** 批量折叠只应更新正常未折叠记录，并为同一批次写入相同 foldedAt，避免回收站或已折叠数据被误改。 */
    fun foldVisibleClipsOnlyUpdatesVisibleUnfoldedRows() = runBlocking {
        dao.upsertClipsForBackup(
            listOf(
                clip(id = 11, content = "visible-11", timestamp = 1_000),
                clip(id = 12, content = "visible-12", timestamp = 1_100),
                clip(id = 13, content = "already-folded", timestamp = 1_200, isFolded = true, foldedAt = 8_000),
                clip(id = 14, content = "deleted-visible", timestamp = 1_300, deletedAt = 9_000),
            )
        )

        /** 本批次折叠时间戳，所有被折叠记录必须写入同一个值来保证折叠列表排序稳定。 */
        val foldedAt = 12_345L
        /** 本批次实际折叠数量；已折叠、回收站、无效 id 和重复 id 都不应计入。 */
        val updatedCount = dao.foldVisibleClips(
            ids = listOf(11L, 12L, 12L, 13L, 14L, -1L),
            foldedAt = foldedAt,
        )
        /** 按 id 回读的剪贴记录，方便逐条断言折叠状态和边界记录是否被保留。 */
        val clipsById = dao.loadClipsByIdsForBackup(listOf(11L, 12L, 13L, 14L)).associateBy { it.id }

        assertEquals(2, updatedCount)
        assertEquals(true, clipsById.getValue(11L).isFolded)
        assertEquals(foldedAt, clipsById.getValue(11L).foldedAt)
        assertEquals(true, clipsById.getValue(12L).isFolded)
        assertEquals(foldedAt, clipsById.getValue(12L).foldedAt)
        assertEquals(true, clipsById.getValue(13L).isFolded)
        assertEquals(8_000L, clipsById.getValue(13L).foldedAt)
        assertEquals(false, clipsById.getValue(14L).isFolded)
        assertEquals(0L, clipsById.getValue(14L).foldedAt)
    }

    /** 构造最小剪贴记录样本；只设置与排序相关的字段，避免无关字段干扰断言。 */
    private fun clip(
        id: Long,
        content: String,
        timestamp: Long,
        isFolded: Boolean = false,
        foldedAt: Long = 0,
        deletedAt: Long = 0,
    ): ClipData {
        return ClipData(
            id = id,
            content = content,
            timestamp = timestamp,
            isFolded = isFolded,
            foldedAt = foldedAt,
            deletedAt = deletedAt,
            link = null,
            sourceAppPackage = null,
            searchText = content,
        )
    }
}

/** 触发 PagingSource 首次刷新并提取返回记录 id，便于排序断言。 */
private suspend fun PagingSource<Int, ClipDetail>.refreshIds(): List<Long> {
    return when (
        val result = load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 20,
                placeholdersEnabled = false,
            )
        )
    ) {
        is PagingSource.LoadResult.Page -> result.data.map { it.clip.id }
        is PagingSource.LoadResult.Error -> throw result.throwable
        is PagingSource.LoadResult.Invalid -> error("PagingSource invalidated during test")
    }
}
