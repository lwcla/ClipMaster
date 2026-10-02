package com.cla.clip.master.ui.widget

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider

/** 锚定菜单定位的纯协作者；只处理安全区和边缘，不依赖 Context 或来源业务。 */
internal class FilterDropdownPositionProvider(
    /** 窗口四周额外安全间距，单位像素。 */
    private val windowMarginPx: Int,
    /** 状态栏顶部占位，单位像素。 */
    private val topInsetPx: Int,
    /** 输入法与导航栏较大的底部占位，单位像素。 */
    private val bottomInsetPx: Int,
    /** 浮层和锚点的间距，单位像素。 */
    private val gapPx: Int,
    /** Popup 平台可见框已扣输入法，调用方完整窗口用于避免安全区重复扣除；空值用于纯定位测试。 */
    private val fullWindowSize: IntSize? = null,
    /** 横屏左侧刘海或导航栏安全区，单位像素。 */
    private val leftInsetPx: Int = 0,
    /** 横屏右侧刘海或导航栏安全区，单位像素。 */
    private val rightInsetPx: Int = 0,
) : PopupPositionProvider {
    /** 优先贴锚点向下展开，下方不足则向上，最后钳制到窗口安全边界。 */
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        /** Android Popup 的可见框可能已扣输入法；传入完整窗口时统一只扣一次安全区。 */
        val layoutSize = fullWindowSize ?: windowSize
        /** 顶部安全边界，包含状态栏和窗口额外间距。 */
        val safeTop = (topInsetPx + windowMarginPx).coerceAtMost(layoutSize.height)
        /** 底部边界扣除输入法和安全间距，极小窗口保证不小于顶部。 */
        val safeBottom = (layoutSize.height - bottomInsetPx - windowMarginPx).coerceAtLeast(safeTop)
        /** 逻辑起始边对齐后进行横向钳制，RTL 使用锚点右边缘。 */
        val preferredX = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
        /** 横屏侧边系统区域同样必须避让，不能仅使用普通左右间距。 */
        val minX = leftInsetPx + windowMarginPx
        /** 最右起点扣除菜单自身宽度，极小窗口以左边界兜底。 */
        val maxX = (layoutSize.width - rightInsetPx - windowMarginPx - popupContentSize.width).coerceAtLeast(minX)
        /** 下方足够才向下展开，否则向上并处理极小窗口兜底。 */
        val belowY = anchorBounds.bottom + gapPx
        /** 优先起点根据可用空间选择锚点下方或上方。 */
        val preferredY = if (belowY + popupContentSize.height <= safeBottom) belowY else anchorBounds.top - gapPx - popupContentSize.height
        /** 最低起点扣除浮层高度，避免浮层伸入底部系统区域。 */
        val maxY = (safeBottom - popupContentSize.height).coerceAtLeast(safeTop)
        return IntOffset(preferredX.coerceIn(minX, maxX), preferredY.coerceIn(safeTop, maxY))
    }

    /** 取上下可见空间较大者作为菜单最大高度，至少返回一像素避免无效约束。 */
    fun availableHeight(anchorBounds: IntRect, windowSize: IntSize): Int {
        /** 锚点上方空间扣除顶部安全区和菜单间隔。 */
        val above = anchorBounds.top - topInsetPx - windowMarginPx - gapPx
        /** 锚点下方空间扣除底部安全区和菜单间隔。 */
        val below = windowSize.height - bottomInsetPx - windowMarginPx - anchorBounds.bottom - gapPx
        return maxOf(above, below, 1)
    }
}
