package com.cla.clip.base.general.dao

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cla.clip.base.general.entity.ClipVisibilityScope
import com.cla.clip.base.general.search.ClipSearchTarget
import com.cla.clip.base.general.search.buildClipFieldSearchQuery
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 在真实 Room/SQLite 上验证字段隔离、来源交集和分页排序，而非只断言 SQL 字串。 */
@RunWith(AndroidJUnit4::class)
class ClipSearchTargetDaoTest {
    /** 每个测试独立内存库，禁止依赖用户剪贴数据。 */
    private lateinit var database: AppDatabase

    /** 初始化合成正文、标题、折叠及回收站记录。 */
    @Before fun setUp() = runBlocking {
        /** 测试进程 Context 只供 Room 创建内存数据库。 */
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        database.linkPreviewDao().upsert(LinkPreviewData("https://example.test/2", "标题会议", null, null, null))
        database.clipDao().upsertClipsForBackup(listOf(
            clip(1, "正文会议", "a"), clip(2, "链接记录", "b", link = "https://example.test/2"),
            clip(3, "正文会议", "b", folded = true), clip(4, "正文会议", "a", deleted = 100),
            clip(5, "符号%_\\", "a"), clip(6, "符号AB", "a"),
        ))
    }

    /** 每轮释放内存库与失效观察器。 */
    @After fun tearDown() { database.close() }

    /** 正文目标不能被标题、综合索引、折叠或已删除记录污染。 */
    @Test fun contentDoesNotMatchTitleOrOtherVisibility() = runBlocking {
        assertEquals(listOf(1L), ids(ClipSearchTarget.Content, "会议"))
    }

    /** 标题目标只使用真实预览标题，正文与无标题记录都不命中。 */
    @Test fun titleDoesNotMatchContentOrFallback() = runBlocking {
        assertEquals(listOf(2L), ids(ClipSearchTarget.LinkTitle, "会议"))
    }

    /** 来源名已解析为空集合时必须返回零条，不能走现有空集合即全部语义。 */
    @Test fun noSourceNameMatchStaysEmpty() = runBlocking {
        assertEquals(emptyList<Long>(), ids(ClipSearchTarget.SourceName, "会议", matched = emptySet()))
        assertEquals(emptyList<Long>(), ids(ClipSearchTarget.SourceName, "会议", selected = setOf("b"), matched = setOf("a")))
    }

    /** 名称匹配后仍要应用用户选择与删除/折叠边界，排序以 id 唯一兜底。 */
    @Test fun sourceIntersectionKeepsStableVisibleOrder() = runBlocking {
        assertEquals(listOf(6L, 5L, 1L), ids(ClipSearchTarget.SourceName, "应用", selected = setOf("a"), matched = setOf("a", "b")))
    }

    /** 大集合使用单个编码参数后仍精确匹配，不能因子串前缀误命中另一个来源。 */
    @Test fun manySourceNamesUseBoundedParametersWithoutPrefixMatches() = runBlocking {
        /** 超出内联参数阈值，但只有 a 是本库已记录来源；ab 不能让 b 误命中。 */
        val matches = (1..1_200).map { "fixture.$it" }.toSet() + setOf("a", "ab")
        assertEquals(listOf(6L, 5L, 1L), ids(ClipSearchTarget.SourceName, "应用", matched = matches))
    }

    /** 搜索符号时不把百分号和下划线解释为通配符。 */
    @Test fun symbolsAreMatchedLiterally() = runBlocking {
        assertEquals(listOf(5L), ids(ClipSearchTarget.Content, "%_\\"))
    }

    /** 折叠搜索使用 foldedAt 时间轴，不能套用原剪贴时间。 */
    @Test fun foldedSearchUsesFoldedTimeWindow() = runBlocking {
        assertEquals(listOf(3L), ids(ClipSearchTarget.Content, "会议", scope = ClipVisibilityScope.FoldedOnly, start = 8_000, end = 10_000))
        assertEquals(emptyList<Long>(), ids(ClipSearchTarget.Content, "会议", scope = ClipVisibilityScope.FoldedOnly, start = 1_000, end = 2_000))
    }

    /** 同一时间下置顶优先，然后按目标字段精确命中优先并以主键稳定排序。 */
    @Test fun exactFieldMatchAndPinningPrecedeTimeTieBreaker() = runBlocking {
        database.clipDao().upsertClipsForBackup(listOf(clip(7, "会议", "a"), clip(8, "会议补充", "a").copy(pinnedTime = 20)))
        assertEquals(listOf(8L, 7L, 1L), ids(ClipSearchTarget.Content, "会议"))
    }

    /** 原有空关键词路径仍显示没有标题的记录，与所选目标无关。 */
    @Test fun existingEmptyQueryFiltersKeepUntitledRecords() = runBlocking {
        /** 原有空关键词查询不增加标题非空限制。 */
        val result = database.clipDao().searchClipsByFilters(null, null, 0, listOf("__all_source_apps__"), false, false)
            .load(PagingSource.LoadParams.Refresh(null, 20, false)) as PagingSource.LoadResult.Page
        assertEquals(listOf(6L, 5L, 2L, 1L), result.data.map { it.clip.id })
    }

    /** 调用生产查询构造器与 DAO，读取实际分页首屏，错误直接抛出使测试失败。 */
    private suspend fun ids(
        /** 当前目标字段。 */ target: ClipSearchTarget,
        /** 合成关键词。 */ keyword: String,
        /** 用户选择来源。 */ selected: Set<String> = emptySet(),
        /** 名称匹配得到的来源，null 表示不使用该维度。 */ matched: Set<String>? = null,
        /** 普通/折叠记录隔离。 */ scope: ClipVisibilityScope = ClipVisibilityScope.VisibleOnly,
        /** 左闭时间边界。 */ start: Long? = null,
        /** 右开时间边界。 */ end: Long? = null,
    ): List<Long> {
        /** 实际执行的参数化 SQL，全部用户文本通过绑定参数进入 SQLite。 */
        val query = buildClipFieldSearchQuery(target, keyword, start, end, selected, matched, scope)
        /** 执行真实 Room 分页查询，不模拟命中结果。 */
        val loaded = database.clipDao().searchClipsByFields(query).load(PagingSource.LoadParams.Refresh(null, 20, false))
        return when (loaded) {
            is PagingSource.LoadResult.Page -> loaded.data.map { it.clip.id }
            is PagingSource.LoadResult.Error -> throw loaded.throwable
            is PagingSource.LoadResult.Invalid -> error("合成查询意外失效")
        }
    }

    /** 最小合成剪贴，综合索引故意全部包含“会议”以捕获错误复用综合字段。 */
    private fun clip(
        /** 稳定测试主键。 */ id: Long,
        /** 用于字段隔离的正文。 */ content: String,
        /** 合成来源身份。 */ source: String,
        /** 可选关联预览。 */ link: String? = null,
        /** 普通/折叠状态。 */ folded: Boolean = false,
        /** 非零表示回收站。 */ deleted: Long = 0,
    ) = ClipData(id, content, 1_000, isFolded = folded, foldedAt = if (folded) 9_000 else 0,
        deletedAt = deleted, link = link, sourceAppPackage = source, searchText = "综合会议")
}
