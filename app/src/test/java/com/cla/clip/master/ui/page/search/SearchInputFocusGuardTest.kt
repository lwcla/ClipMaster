package com.cla.clip.master.ui.page.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 防止菜单恢复焦点切模式，同时保护硬件键盘和读屏真实输入意图不会永久被屏蔽。 */
class SearchInputFocusGuardTest {
    /** 菜单返回的被动焦点应被忽略。 */
    @Test
    fun restoredFocusDoesNotEnterHistory() {
        /** 当前组合的焦点策略，不依赖 Android 焦点系统。 */
        val guard = SearchInputFocusGuard()
        guard.onMenuOpened()
        assertFalse(guard.shouldShowHistory(true))
    }

    /** 触摸、键盘 Tab 与语义点击共用意图入口，主动输入后聚焦必须进入历史。 */
    @Test
    fun explicitInputIntentAllowsHistoryAfterMenuClose() {
        /** 同一个策略先经历菜单，再经历真实键盘输入。 */
        val guard = SearchInputFocusGuard()
        guard.onMenuOpened()
        guard.onUserInputIntent()
        assertTrue(guard.shouldShowHistory(true))
    }

    /** 用户意图不能把失焦事件解释为历史模式入口。 */
    @Test
    fun userIntentStillIgnoresFocusLoss() {
        /** 已解除保护的策略仍需判断实际聚焦状态。 */
        val guard = SearchInputFocusGuard()
        guard.onMenuOpened()
        guard.onUserInputIntent()
        assertFalse(guard.shouldShowHistory(false))
    }
}
