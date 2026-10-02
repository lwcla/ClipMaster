package com.cla.clip.master.ui.page.search

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 保护即时来源多选与有效查询的区别，尤其防止清空来源误重置时间或变成无结果。 */
class SourceAppSelectionReducerTest {
    /** 合成候选只含两个包名，避免测试依赖安装列表和用户真实来源。 */
    private val candidates = setOf("app.a", "app.b")

    /** 全选取消后应保持真实无勾选态，但查询不限制来源。 */
    @Test
    fun cancelSelectAllKeepsUncheckedStateAndQueriesAllSources() {
        /** 已全选时取消全选的真实归约结果。 */
        val result = SourceAppSelectionReducer.toggleAll(SourceAppSelection.All, candidates)
        assertEquals(SourceAppSelection.Explicit(emptySet()), result)
        assertEquals(emptySet<String>(), result.queryPackageNames)
        assertFalse(result.contains("app.a"))
        assertFalse(result.isAllSelected)
    }

    /** 无勾选态再次点击全选要恢复全部勾选，而不是保持空。 */
    @Test
    fun selectAllFromEmptyChecksEveryCandidate() {
        /** 空勾选转全选的结果，查询仍然是全部来源。 */
        val result = SourceAppSelectionReducer.toggleAll(SourceAppSelection.Explicit(emptySet()), candidates)
        assertEquals(SourceAppSelection.All, result)
        assertTrue(result.contains("app.a"))
        assertEquals(emptySet<String>(), result.queryPackageNames)
    }

    /** 部分勾选时全选会立即解除来源限制。 */
    @Test
    fun selectAllFromPartialRestoresAllMode() {
        assertEquals(
            SourceAppSelection.All,
            SourceAppSelectionReducer.toggleAll(SourceAppSelection.Explicit(setOf("app.a")), candidates),
        )
    }

    /** 全选中取消某项要展开当前候选后移除，不能从查询空集合开始计算。 */
    @Test
    fun deselectOneFromAllKeepsOtherCandidates() {
        assertEquals(
            SourceAppSelection.Explicit(setOf("app.b")),
            SourceAppSelectionReducer.toggleItem(SourceAppSelection.All, candidates, "app.a"),
        )
    }

    /** 取消最后一项不受限制，查询应成为全部来源。 */
    @Test
    fun deselectLastSourceProducesUncheckedAllQuery() {
        /** 只剩一项时取消该项的下一状态。 */
        val result = SourceAppSelectionReducer.toggleItem(
            SourceAppSelection.Explicit(setOf("app.a")), candidates, "app.a",
        )
        assertEquals(SourceAppSelection.Explicit(emptySet()), result)
        assertEquals(emptySet<String>(), result.queryPackageNames)
    }

    /** 从无勾选态选择某项必须成为单来源，不能先恢复全部再取消。 */
    @Test
    fun selectFromUncheckedStateLimitsQueryToChosenSource() {
        /** 无勾选状态直接添加一个来源。 */
        val result = SourceAppSelectionReducer.toggleItem(SourceAppSelection.Explicit(emptySet()), candidates, "app.a")
        assertEquals(setOf("app.a"), result.queryPackageNames)
        assertFalse(result.contains("app.b"))
    }

    /** 显式选齐当前候选后应归一为全部模式。 */
    @Test
    fun selectLastMissingSourceNormalizesToAll() {
        assertEquals(
            SourceAppSelection.All,
            SourceAppSelectionReducer.toggleItem(SourceAppSelection.Explicit(setOf("app.a")), candidates, "app.b"),
        )
    }

    /** 未知来源的空字符串是合法身份，不能当成没有选择。 */
    @Test
    fun unknownEmptyPackageRemainsAnExplicitQuery() {
        /** 含未知来源与一个普通来源的合成候选。 */
        val withUnknown = setOf("", "app.a")
        /** 只选择未知来源的结果。 */
        val result = SourceAppSelectionReducer.toggleItem(SourceAppSelection.Explicit(emptySet()), withUnknown, "")
        assertEquals(setOf(""), result.queryPackageNames)
        assertTrue(result.contains(""))
    }

    /** 取消未知来源最后一项后才解除来源限制。 */
    @Test
    fun deselectUnknownLastSourceQueriesAll() {
        assertEquals(
            SourceAppSelection.Explicit(emptySet()),
            SourceAppSelectionReducer.toggleItem(SourceAppSelection.Explicit(setOf("")), setOf("", "app.a"), ""),
        )
    }

    /** 候选缺失的旧包名不能因选择另一项被丢弃或归一为全部。 */
    @Test
    fun selectionPreservesMissingCandidates() {
        assertEquals(
            setOf("app.missing", "app.a", "app.b"),
            SourceAppSelectionReducer.toggleItem(
                SourceAppSelection.Explicit(setOf("app.missing", "app.a")), candidates, "app.b",
            ).queryPackageNames,
        )
    }

    /** 列表没有候选时全选入口禁用，归约也应保持已有筛选。 */
    @Test
    fun emptyCandidatesDoNotClearAppliedSelection() {
        /** 候选暂时消失但仍应保留的来源选择。 */
        val applied = SourceAppSelection.Explicit(setOf("app.missing"))
        assertEquals(applied, SourceAppSelectionReducer.toggleAll(applied, emptySet()))
    }

    /** 已消失行的迟到点击不得添加一个菜单中已经不存在的包名。 */
    @Test
    fun staleRowEventDoesNotChangeSelection() {
        assertEquals(
            SourceAppSelection.All,
            SourceAppSelectionReducer.toggleItem(SourceAppSelection.All, candidates, "app.missing"),
        )
    }

    /** 只有一个候选也允许取消全部，保持真实无勾选。 */
    @Test
    fun deselectOnlyCandidateDoesNotRestoreCheckedState() {
        assertEquals(
            SourceAppSelection.Explicit(emptySet()),
            SourceAppSelectionReducer.toggleItem(SourceAppSelection.All, setOf("app.a"), "app.a"),
        )
    }

    /** 连续操作每次读取上一结果，保护不丢失前次勾选的事件语义。 */
    @Test
    fun consecutiveSelectionsKeepBothSources() {
        /** 三项候选可观察两项选择，而不被全选归一掩盖。 */
        val threeCandidates = candidates + "app.c"
        /** 第一次选中结果作为第二次事件输入。 */
        val first = SourceAppSelectionReducer.toggleItem(SourceAppSelection.Explicit(emptySet()), threeCandidates, "app.a")
        assertEquals(setOf("app.a", "app.b"), SourceAppSelectionReducer.toggleItem(first, threeCandidates, "app.b").queryPackageNames)
    }

    /** 来源清空必须保留非默认时间、关键词和范围外的状态。 */
    @Test
    fun clearingSourcePreservesSelectedTimeAndKeyword() {
        /** 非默认时间及合成关键词用于抓住错误的整个状态重置。 */
        val applied = SearchFilterState(query = "fixture", timeFilter = SearchTimeFilter.LAST_7_DAYS,
            sourceAppSelection = SourceAppSelection.Explicit(setOf("app.a")))
        /** 页面来源事件只复制来源字段，时间不能被 reducer 改写。 */
        val next = applied.copy(sourceAppSelection = SourceAppSelectionReducer.toggleItem(applied.sourceAppSelection, candidates, "app.a"))
        assertEquals(SearchTimeFilter.LAST_7_DAYS, next.timeFilter)
        assertEquals("fixture", next.query)
        assertEquals(emptySet<String>(), next.sourceAppPackages)
    }

    /** 有效查询去重要忽略全选与无勾选的等价切换，仍响应时间及部分来源变化。 */
    @Test
    fun queryFlowSkipsEquivalentAllAndUncheckedSelections() = runBlocking {
        /** 同一查询的全选基线，使用非默认时间以保护时间隔离。 */
        val all = SearchFilterState(query = "fixture", timeFilter = SearchTimeFilter.LAST_7_DAYS)
        /** 手工构造全选、清空、部分选择、时间改变的序列。 */
        val results = flowOf(all, all.copy(sourceAppSelection = SourceAppSelection.Explicit(emptySet())), all,
            all.copy(sourceAppSelection = SourceAppSelection.Explicit(setOf("app.a"))),
            all.copy(timeFilter = SearchTimeFilter.TODAY)).distinctSearchQueryConditions().toList()
        assertEquals(3, results.size)
        assertEquals(emptySet<String>(), results[0].sourceAppPackages)
        assertEquals(setOf("app.a"), results[1].sourceAppPackages)
        assertEquals(SearchTimeFilter.TODAY, results[2].timeFilter)
    }
}
