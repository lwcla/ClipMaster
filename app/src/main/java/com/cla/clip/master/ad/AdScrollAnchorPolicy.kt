package com.cla.clip.master.ad

/** 连续滚动页顶部广告伸缩的纯像素锚点规则；不读取正文、任务或媒体数据。 */
internal object AdScrollAnchorPolicy {
    /** 正文已滚动时补偿广告高度差；位于顶部时维持零偏移，收起后禁止负值。 */
    fun restoreOffset(scrollOffset: Int, previousHeight: Int, newHeight: Int): Int =
        if (scrollOffset == 0) 0 else (scrollOffset + newHeight - previousHeight).coerceAtLeast(0)
}
