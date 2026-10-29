package com.cla.clip.master.ui.page.search

import com.cla.clip.base.general.search.ClipSearchField
import org.junit.Assert.*
import org.junit.Test

/** 七项多选的真实业务状态测试，不依赖 Compose 复选框实现。 */
class SearchFieldSelectionReducerTest {
    /** 初次进入默认七项全选，取消只影响所点字段。 */
    @Test fun defaultsToSevenAndOnlyTogglesRequestedField() {
        /** 初始筛选携带无副作用默认值。 */
        val initial = SearchFilterState()
        assertEquals(ClipSearchField.entries.toSet(), initial.fields)
        assertEquals(initial.fields - ClipSearchField.LinkTitle,
            SearchFieldSelectionReducer.toggle(initial.fields, ClipSearchField.LinkTitle))
    }

    /** 最后一项不能被取消，但可以继续增加另一项。 */
    @Test fun lastFieldIsPreservedAndOtherFieldsCanBeAdded() {
        /** 单一字段是允许的最小选择。 */
        val selected = setOf(ClipSearchField.Content)
        assertEquals(selected, SearchFieldSelectionReducer.toggle(selected, ClipSearchField.Content))
        assertEquals(selected + ClipSearchField.Link, SearchFieldSelectionReducer.toggle(selected, ClipSearchField.Link))
    }
}
