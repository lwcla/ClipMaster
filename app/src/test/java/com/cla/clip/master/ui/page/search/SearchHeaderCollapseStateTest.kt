package com.cla.clip.master.ui.page.search

import org.junit.Assert.assertEquals
import org.junit.Test

/** 搜索页顶部搜索头吸附规则测试，保护两态吸附和 fling 方向优先级。 */
class SearchHeaderCollapseStateTest {

    @Test
    /** 当前收起距离未超过一半时，搜索头应吸附回完整展开。 */
    fun resolveSearchHeaderCollapseStateExpandsBeforeHalfCollapsed() {
        /** 未过半的运行时偏移，表示用户只轻微上滑了顶部搜索头。 */
        val offsetPx = -40f

        /** 搜索头当前测量高度，用于判定半程阈值。 */
        val headerHeightPx = 100f

        assertEquals(
            SearchHeaderCollapseState.Expanded,
            resolveSearchHeaderCollapseState(offsetPx, headerHeightPx),
        )
    }

    @Test
    /** 当前收起距离超过一半时，搜索头应吸附到完整收起。 */
    fun resolveSearchHeaderCollapseStateCollapsesAfterHalfCollapsed() {
        /** 已过半的运行时偏移，表示用户已经明显把顶部搜索头推向收起。 */
        val offsetPx = -70f

        /** 搜索头当前测量高度，用于判定半程阈值。 */
        val headerHeightPx = 100f

        assertEquals(
            SearchHeaderCollapseState.Collapsed,
            resolveSearchHeaderCollapseState(offsetPx, headerHeightPx),
        )
    }

    @Test
    /** 明确向上 fling 时，即使当前位置未过半也应优先吸附到收起。 */
    fun resolveSearchHeaderCollapseStateCollapsesWhenFlingUp() {
        /** 未过半的运行时偏移，用于确认 fling 方向会覆盖半程判定。 */
        val offsetPx = -20f

        /** 搜索头当前测量高度，用于限制运行时偏移语义。 */
        val headerHeightPx = 100f

        /** 明确向上的 fling 速度，Compose 中向上滚动对应负向 Y 速度。 */
        val velocityY = -400f

        assertEquals(
            SearchHeaderCollapseState.Collapsed,
            resolveSearchHeaderCollapseState(offsetPx, headerHeightPx, velocityY),
        )
    }

    @Test
    /** 明确向下 fling 时，即使当前位置已过半也应优先吸附到展开。 */
    fun resolveSearchHeaderCollapseStateExpandsWhenFlingDown() {
        /** 已过半的运行时偏移，用于确认 fling 方向会覆盖半程判定。 */
        val offsetPx = -80f

        /** 搜索头当前测量高度，用于限制运行时偏移语义。 */
        val headerHeightPx = 100f

        /** 明确向下的 fling 速度，Compose 中向下滚动对应正向 Y 速度。 */
        val velocityY = 400f

        assertEquals(
            SearchHeaderCollapseState.Expanded,
            resolveSearchHeaderCollapseState(offsetPx, headerHeightPx, velocityY),
        )
    }
}
