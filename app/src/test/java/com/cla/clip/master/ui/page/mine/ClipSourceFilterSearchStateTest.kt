package com.cla.clip.master.ui.page.mine

import org.junit.Assert.assertEquals
import org.junit.Test

/** 保护返回层级，防止一次返回在键盘打开时直接退出搜索或离开页面。 */
class ClipSourceFilterSearchStateTest {
    /** 搜索输入中优先收键盘，保留当前关键词和模式。 */
    @Test
    fun searchingWithKeyboardOnlyDismissesKeyboard() {
        assertEquals(ClipSourceFilterBackAction.DismissKeyboard,
            resolveClipSourceFilterBackAction(ClipSourceFilterSearchMode.Searching, imeVisible = true))
    }

    /** 键盘已关闭的搜索态只退出搜索，不能触发名单保存后的离页流程。 */
    @Test
    fun searchingWithoutKeyboardClosesSearch() {
        assertEquals(ClipSourceFilterBackAction.CloseSearch,
            resolveClipSourceFilterBackAction(ClipSourceFilterSearchMode.Searching, imeVisible = false))
    }

    /** 普通态可以离页，实际保存等待继续由已有写入协调器负责。 */
    @Test
    fun browsingWithoutKeyboardRequestsPageBack() {
        assertEquals(ClipSourceFilterBackAction.LeavePage,
            resolveClipSourceFilterBackAction(ClipSourceFilterSearchMode.Browsing, imeVisible = false))
    }

    /** 模式切换后 IME 动画尚未结束时，返回仍不能越过键盘层。 */
    @Test
    fun browsingWithKeyboardOnlyDismissesKeyboard() {
        assertEquals(ClipSourceFilterBackAction.DismissKeyboard,
            resolveClipSourceFilterBackAction(ClipSourceFilterSearchMode.Browsing, imeVisible = true))
    }
}
