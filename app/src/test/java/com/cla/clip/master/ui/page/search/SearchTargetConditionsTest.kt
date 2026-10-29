package com.cla.clip.master.ui.page.search

import com.cla.clip.base.general.search.ClipSearchTarget
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** 搜索字段必须参与 Pager 身份，重复选择不能触发重复查询。 */
class SearchTargetConditionsTest {
    /** 旧实现会忽略 target，导致三个合法查询错误合并为一个。 */
    @Test fun changingTargetCreatesNewQueryButDuplicateSelectionDoesNot() = runBlocking {
        /** 时间、来源和关键词保持固定，只改变目标字段。 */
        val initial = SearchFilterState("会议", SearchTimeFilter.TODAY, SourceAppSelection.Explicit(setOf("a")))
        /** 人工给出预期的三种独立查询。 */
        val queries = flowOf(initial, initial.copy(target = ClipSearchTarget.Content),
            initial.copy(target = ClipSearchTarget.Content), initial.copy(target = ClipSearchTarget.LinkTitle))
            .distinctSearchQueryConditions().toList()
        assertEquals(listOf(ClipSearchTarget.All, ClipSearchTarget.Content, ClipSearchTarget.LinkTitle), queries.map { it.target })
        assertEquals(listOf("会议", "会议", "会议"), queries.map { it.query })
        assertEquals(listOf(setOf("a"), setOf("a"), setOf("a")), queries.map { it.sourceAppPackages })
    }
}
