package com.cla.clip.master.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/** 保护窄屏/大字体下可读摘要，不能只判断首行后又挤压第二行。 */
class ThreeFilterLayoutTest {
    /** 模拟 280dp 窗口扣边距与 1.8 字体的时间/来源最低宽度。 */
    @Test fun largeFontOnNarrowScreenStacksAllFilters() {
        assertEquals(ThreeFilterLayout.Stacked, resolveThreeFilterLayout(256f, 210f, 149f, 149f, 8f))
    }
    /** 宽屏仍使用原 2:2:3，普通窄屏可容纳后两列时只换两行。 */
    @Test fun availableSpaceChoosesCompactLayouts() {
        assertEquals(ThreeFilterLayout.Row, resolveThreeFilterLayout(480f, 110f, 104f, 104f, 8f))
        assertEquals(ThreeFilterLayout.LeadingThenPair, resolveThreeFilterLayout(336f, 150f, 104f, 104f, 8f))
    }
}
