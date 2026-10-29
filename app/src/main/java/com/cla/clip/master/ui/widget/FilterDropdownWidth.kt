package com.cla.clip.master.ui.widget

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 筛选菜单默认最小宽度；入口窄于菜单时，菜单仍由当前窗口安全区限制。 */
internal val DEFAULT_FILTER_DROPDOWN_MENU_WIDTH = 220.dp

/** 菜单宽度规则独立于入口布局，允许显式覆盖最小宽度，但不能超出安全可见宽度。 */
internal fun resolveFilterDropdownMenuWidth(
    /** 已测量入口宽度。 */ anchorWidth: Dp,
    /** 已扣除窗口 inset 和边距的正宽度。 */ availableWidth: Dp,
    /** 未指定时使用共享默认值。 */ minimumWidth: Dp = DEFAULT_FILTER_DROPDOWN_MENU_WIDTH,
): Dp = maxOf(anchorWidth, minimumWidth).coerceAtMost(availableWidth)
