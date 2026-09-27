package com.cla.clip.master.ad

/** 连续滚动页单广告伸缩的纯像素锚点规则；不读取正文、任务或媒体数据。 */
internal object AdScrollAnchorPolicy {
    /**
     * 越过广告前的内容后才补偿高度差，收起最多回到广告前沿，避免带动仍在阅读的说明。
     * contentBeforeAdHeight 是广告前全部内容与间距的非负实测高度；默认零保持原顶部广告规则。
     */
    fun restoreOffset(
        scrollOffset: Int,
        previousHeight: Int,
        newHeight: Int,
        contentBeforeAdHeight: Int = 0,
    ): Int = if (scrollOffset <= contentBeforeAdHeight) scrollOffset
        else (scrollOffset + newHeight - previousHeight).coerceAtLeast(contentBeforeAdHeight)
}
