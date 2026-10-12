package com.cla.clip.master.ui.page.download

/** 横向切换保留旧稳定页展示，业务选择和动作只属于真正停稳的同一分类。 */
internal object DownloadHistoryPagerPolicy {
    /** 分类不一致时只投影普通浏览态，避免视频/图片相同主键串用新分类的选择集合。 */
    fun listState(
        // 标题栏可能已经选择目标分类，仍不能直接将其多选状态交给旧页。
        state: DownloadHistoryUiState,
        // Pager 尚未停稳切换前持续显示的分类。
        settledTab: DownloadHistoryTab,
    ): DownloadHistoryUiState = if (state.selectedTab == settledTab) state else state.copy(
        selectedTab = settledTab,
        selectionMode = false,
        selectedIds = emptySet(),
        selectedHasRunning = false,
    )

    /** 动画/拖动或选择尚未与稳定页同步时暂停卡片业务动作，展示与数据收集保持独立。 */
    fun canInteract(
        // ViewModel 当前业务操作所针对的分类。
        selectedTab: DownloadHistoryTab,
        // 当前仍显示内容的实际稳定分类。
        settledTab: DownloadHistoryTab,
        // 横向 Pager 的实时滚动/动画状态。
        scrolling: Boolean,
    ): Boolean = selectedTab == settledTab && !scrolling
}
