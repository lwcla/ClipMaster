package com.cla.clip.master.ad

/** 广告位置只依据去重后的真实内容数量；驻留资源上限不限制累计广告位置。 */
internal object ListAdPlacementPolicy {
    /** 相邻广告锚点之间的真实内容数，不包含广告和分页状态项。 */
    private const val CONTENT_INTERVAL = 20

    /** 单列惰性列表按当前真实内容索引查询广告序号；非锚点和非法索引返回 null。 */
    fun singleColumnSlotIndex(contentIndex: Int, eligible: Boolean = true): Int? =
        contentIndex.takeIf { eligible && it >= 0 && it % CONTENT_INTERVAL == 0 }?.div(CONTENT_INTERVAL)

    /** 首位随内容准备，其余位置只在真实锚点与视口相交时允许请求；空布局和预取均不算可见。 */
    fun shouldRequestSlot(
        slotIndex: Int,
        anchorOffset: Int?,
        anchorHeight: Int?,
        viewportStart: Int,
        viewportEnd: Int,
    ): Boolean {
        if (slotIndex == 0) return true
        if (anchorOffset == null || anchorHeight == null || anchorHeight <= 0) return false
        return anchorOffset < viewportEnd && anchorOffset.toLong() + anchorHeight > viewportStart
    }

    /** 返回零基内容锚点；网格首位在第一行末，后续每二十条内容插入一次。 */
    fun anchorIndices(itemCount: Int, columns: Int = 1): List<Int> {
        if (itemCount <= 0) return emptyList()
        require(columns > 0)
        // 首行不足列数时紧随最后一个真实内容，不生成虚构占位。
        val firstAnchor = minOf(itemCount, columns) - 1
        return generateSequence(firstAnchor.toLong()) { it + CONTENT_INTERVAL.toLong() }
            .takeWhile { it < itemCount.toLong() }
            .map { it.toInt() }
            .toList()
    }

    /** 列表惯性、卡片侧滑和置顶纠偏全部结束后，广告才允许改变布局。 */
    fun canMutateLayout(scrolling: Boolean, interactionBlocked: Boolean, pinRestorePending: Boolean): Boolean =
        !scrolling && !interactionBlocked && !pinRestorePending
}
