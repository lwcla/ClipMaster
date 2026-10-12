package com.cla.clip.master.ui.page.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 点击 Tab 先更新业务选择时，旧稳定页继续显示但不能把操作或选择投向新分类。 */
class DownloadHistoryPagerPolicyTest {
    /** 分类切换期间保留旧页浏览态，视频/图片主键碰撞也不能串用选择集合。 */
    @Test fun pendingTabKeepsSettledContentWithoutNewTabSelection() {
        // 模拟图片已被选中、Pager 视频尚未停稳离开，两个分类可能恰好具有同一个主键。
        val selectedState = DownloadHistoryUiState(
            selectedTab = DownloadHistoryTab.Image,
            selectionMode = true,
            selectedIds = setOf(7L),
            selectedHasRunning = true,
        )
        // 旧页只借用已加载内容，不能借用新页管理状态。
        val renderedState = DownloadHistoryPagerPolicy.listState(selectedState, DownloadHistoryTab.Video)
        assertEquals(DownloadHistoryTab.Video, renderedState.selectedTab)
        assertFalse(renderedState.selectionMode)
        assertTrue(renderedState.selectedIds.isEmpty())
        assertFalse(renderedState.selectedHasRunning)
        assertFalse(DownloadHistoryPagerPolicy.canInteract(DownloadHistoryTab.Image, DownloadHistoryTab.Video, false))
    }

    /** 选中分类与稳定页相同仍须等待横向拖动结束，停稳后才恢复业务动作。 */
    @Test fun stableTabRestoresItsSelectionAndActionsAfterScrolling() {
        // 同分类管理态不复制或清空选择，保证正常多选契约。
        val state = DownloadHistoryUiState(selectionMode = true, selectedIds = setOf(7L))
        assertEquals(state, DownloadHistoryPagerPolicy.listState(state, DownloadHistoryTab.Video))
        assertFalse(DownloadHistoryPagerPolicy.canInteract(DownloadHistoryTab.Video, DownloadHistoryTab.Video, true))
        assertTrue(DownloadHistoryPagerPolicy.canInteract(DownloadHistoryTab.Video, DownloadHistoryTab.Video, false))
    }
}
