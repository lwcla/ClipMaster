package com.cla.clip.master.ad

/** Pager 的广告访问和布局许可分开表达；纯内存状态，不依赖 Android 或外部协议。 */
internal class AdPagerPageState(
    /** 当前被组合的页面索引。 */
    page: Int,
    /** 已完成切换的稳定选中页，拖动到中间位置不改变它。 */
    settledPage: Int,
    /** Pager 拖动或惯性是否仍在进行，仅用于布局门控。 */
    scrolling: Boolean,
) {
    /** 只用稳定页界定访问，拖动/回弹不冒充离开页面，也不能重置请求账本。 */
    val visibleToUser: Boolean = page == settledPage
    /** 动画过程中延后新广告展开，已显示的素材不因门控改变而销毁。 */
    val layoutBlocked: Boolean = scrolling
}
