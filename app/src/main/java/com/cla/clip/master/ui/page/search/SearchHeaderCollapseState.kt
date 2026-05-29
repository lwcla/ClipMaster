package com.cla.clip.master.ui.page.search

import kotlin.math.abs

/** 搜索页顶部搜索头的稳定吸附状态，只保存两态以避免配置变更后恢复到半截像素位置。 */
internal enum class SearchHeaderCollapseState {
    /** 搜索框和筛选条件完整展开，便于用户继续修改搜索条件。 */
    Expanded,

    /** 搜索框和筛选条件完整收起，结果列表获得最大可见空间。 */
    Collapsed,
}

/**
 * 根据当前拖拽位置和 fling 方向决定搜索头最终吸附到哪一态。
 *
 * `offsetPx` 使用运行时像素偏移，取值范围由调用方钳制到 `[-headerHeightPx, 0]`；
 * 返回值只表达稳定状态，避免把主题切换、字体变化或重新测量前的像素值持久保存下来。
 */
internal fun resolveSearchHeaderCollapseState(
    offsetPx: Float,
    headerHeightPx: Float,
    velocityY: Float = 0f,
): SearchHeaderCollapseState {
    /** 搜索头高度无效时只能保持展开，避免除零或把未完成测量误判为收起。 */
    if (headerHeightPx <= 0f) {
        return SearchHeaderCollapseState.Expanded
    }

    /** fling 方向阈值用于忽略极小速度抖动，单位是像素每秒。 */
    val velocityThresholdPx = 80f
    if (velocityY <= -velocityThresholdPx) {
        return SearchHeaderCollapseState.Collapsed
    }
    if (velocityY >= velocityThresholdPx) {
        return SearchHeaderCollapseState.Expanded
    }

    /** 当前已经收起的距离，负 offset 越大代表头部越接近完全收起。 */
    val collapsedDistancePx = abs(offsetPx).coerceIn(0f, headerHeightPx)
    /** 超过一半则吸到收起，否则回到展开，保证两态切换规则可预测。 */
    return if (collapsedDistancePx >= headerHeightPx / 2f) {
        SearchHeaderCollapseState.Collapsed
    } else {
        SearchHeaderCollapseState.Expanded
    }
}
