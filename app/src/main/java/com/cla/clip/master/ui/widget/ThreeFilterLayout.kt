package com.cla.clip.master.ui.widget

/** 共享三入口布局状态，仅由可用空间与实际字体宽度决定。 */
internal enum class ThreeFilterLayout {
    /** 三列同一行。 */ Row,
    /** 首列独占，后两列 2:3。 */ LeadingThenPair,
    /** 三个入口各占一行。 */ Stacked,
}

/** 同时检查三列和第二行比例；像素或 dp 都可用，但输入必须同单位。 */
internal fun resolveThreeFilterLayout(
    /** 扣除页面外边距后的可用宽度。 */ width: Float,
    /** 首列完整摘要最小宽度。 */ leading: Float,
    /** 中列完整摘要最小宽度。 */ middle: Float,
    /** 末列基础摘要最小宽度。 */ trailing: Float,
    /** 相邻入口间距。 */ gap: Float,
): ThreeFilterLayout {
    /** 三列分配前扣除两个列间距。 */
    val unit = (width - 2 * gap) / 7
    if (2 * unit >= maxOf(leading, middle) && 3 * unit >= trailing) return ThreeFilterLayout.Row
    /** 第二行只有一个间距，仍须确保时间和来源基础摘要可完整显示。 */
    val pairUnit = (width - gap) / 5
    return if (2 * pairUnit >= middle && 3 * pairUnit >= trailing) ThreeFilterLayout.LeadingThenPair else ThreeFilterLayout.Stacked
}
