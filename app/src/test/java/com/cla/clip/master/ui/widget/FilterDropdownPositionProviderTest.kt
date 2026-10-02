package com.cla.clip.master.ui.widget

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/** 菜单定位只依赖窗口、锚点与安全区，保护输入法开启及窗口边缘不越界。 */
class FilterDropdownPositionProviderTest {
    /** 横屏右侧三键导航占位时，宽菜单右边缘应停在导航栏左侧。 */
    @Test
    fun landscapeRightNavigationInsetKeepsMenuInsideSafeArea() {
        assertEquals(IntOffset(408, 104), FilterDropdownPositionProvider(8, 0, 0, 4, rightInsetPx = 64).calculatePosition(
            IntRect(700, 60, 780, 100), IntSize(800, 400), LayoutDirection.Ltr, IntSize(320, 200),
        ))
    }

    /** 左侧刘海安全区不能只使用普通窗口边距。 */
    @Test
    fun leftDisplayCutoutInsetKeepsMenuAwayFromCutout() {
        assertEquals(IntOffset(56, 104), FilterDropdownPositionProvider(8, 0, 0, 4, leftInsetPx = 48).calculatePosition(
            IntRect(0, 60, 160, 100), IntSize(800, 400), LayoutDirection.Ltr, IntSize(320, 200),
        ))
    }
    /** Android Popup 传入的可见框可能已经排除输入法，定位仍应基于完整窗口只扣一次 Insets。 */
    @Test
    fun popupVisibleFrameDoesNotSubtractKeyboardTwice() {
        assertEquals(IntOffset(20, 196), FilterDropdownPositionProvider(8, 20, 300, 4, IntSize(400, 800)).calculatePosition(
            IntRect(20, 400, 180, 440), IntSize(400, 500), LayoutDirection.Ltr, IntSize(160, 200),
        ))
    }
    /** 下方空间足够时贴近锚点展开，不凭列表滚动重新选位置。 */
    @Test
    fun menuPrefersSpaceBelowAnchor() {
        assertEquals(IntOffset(20, 104), FilterDropdownPositionProvider(8, 20, 0, 4).calculatePosition(
            IntRect(20, 60, 180, 100), IntSize(400, 800), LayoutDirection.Ltr, IntSize(160, 200),
        ))
    }

    /** 输入法占位压缩下方空间时菜单应翻到锚点上方。 */
    @Test
    fun keyboardInsetMovesMenuAboveAnchor() {
        assertEquals(IntOffset(20, 196), FilterDropdownPositionProvider(8, 20, 300, 4).calculatePosition(
            IntRect(20, 400, 180, 440), IntSize(400, 800), LayoutDirection.Ltr, IntSize(160, 200),
        ))
    }

    /** 半宽右侧入口的宽菜单应钳制到安全边缘。 */
    @Test
    fun wideMenuStaysInsideRightWindowEdge() {
        assertEquals(IntOffset(72, 104), FilterDropdownPositionProvider(8, 20, 0, 4).calculatePosition(
            IntRect(210, 60, 388, 100), IntSize(400, 800), LayoutDirection.Ltr, IntSize(320, 200),
        ))
    }

    /** RTL 菜单按入口逻辑起始边对齐，不能固定左对齐。 */
    @Test
    fun rtlMenuAlignsWithAnchorEnd() {
        assertEquals(IntOffset(120, 104), FilterDropdownPositionProvider(8, 20, 0, 4).calculatePosition(
            IntRect(100, 60, 280, 100), IntSize(400, 800), LayoutDirection.Rtl, IntSize(160, 200),
        ))
    }

    /** 可用高度取锚点上下空间较大者，并扣除输入法与状态栏安全区。 */
    @Test
    fun availableHeightAccountsForKeyboardAndStatusBar() {
        assertEquals(368, FilterDropdownPositionProvider(8, 20, 300, 4).availableHeight(
            IntRect(20, 400, 180, 440), IntSize(400, 800),
        ))
    }
}
