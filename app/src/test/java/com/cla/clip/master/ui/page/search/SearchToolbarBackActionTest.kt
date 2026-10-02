package com.cla.clip.master.ui.page.search

import org.junit.Assert.assertEquals
import org.junit.Test

/** 顶部返回必须逐层退出局部交互，防止关闭菜单或多选时误离页。 */
class SearchToolbarBackActionTest {
    /** 菜单独立打开时应先关闭，不触发导航。 */
    @Test
    fun openMenuClosesBeforeNavigation() {
        assertEquals(SearchToolbarBackAction.CloseMenu, resolveSearchToolbarBackAction(menuOpen = true, selectionMode = false))
    }

    /** 即便结果处于多选，菜单仍是最先处理的局部交互。 */
    @Test
    fun openMenuTakesPriorityOverSelection() {
        assertEquals(SearchToolbarBackAction.CloseMenu, resolveSearchToolbarBackAction(menuOpen = true, selectionMode = true))
    }

    /** 没有菜单的多选结果只退出多选，不能丢失当前页面。 */
    @Test
    fun selectionExitsBeforeNavigation() {
        assertEquals(SearchToolbarBackAction.ExitSelection, resolveSearchToolbarBackAction(menuOpen = false, selectionMode = true))
    }

    /** 无局部交互时直接离页；历史/结果与输入法状态不增加额外返回层级。 */
    @Test
    fun ordinaryStateNavigatesBack() {
        assertEquals(SearchToolbarBackAction.NavigateBack, resolveSearchToolbarBackAction(menuOpen = false, selectionMode = false))
    }
}
