package com.cla.clip.master.ui.widget

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** 固定三列入口不能令时间/来源菜单窄到无法辨认，同时必须服从窗口安全区。 */
class FilterDropdownWidthTest {
    /** 280dp 和 360dp 页面三列入口都很窄，默认菜单应仍能展示名称。 */
    @Test fun narrowAnchorsKeepReadableMenuWidth() {
        assertEquals(220.dp, resolveFilterDropdownMenuWidth(80.dp, 264.dp))
        assertEquals(220.dp, resolveFilterDropdownMenuWidth(106.dp, 344.dp))
    }

    /** 极窄窗口优先避免出界，较宽入口不被最小值缩小。 */
    @Test fun safeWindowBoundsAndWideAnchorsAreRespected() {
        assertEquals(190.dp, resolveFilterDropdownMenuWidth(80.dp, 190.dp))
        assertEquals(320.dp, resolveFilterDropdownMenuWidth(320.dp, 400.dp))
        assertEquals(160.dp, resolveFilterDropdownMenuWidth(80.dp, 300.dp, 160.dp))
    }
}
