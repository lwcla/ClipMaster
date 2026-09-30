package com.cla.clip.master.ui.page.mine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 安装列表自动加载规则测试，保护会话去重与首次失败可重试的边界。 */
class InstalledAppAutoLoadStateTest {

    @Test
    /** 新页面会话没有成功结果时必须读取，不能沿用上一个会话的加载标记。 */
    fun newSessionNeedsInitialLoad() {
        assertTrue(InstalledAppListState().needsInitialLoad)
    }

    @Test
    /** 首次扫描尚未结束时不得再次自动启动扫描。 */
    fun runningInitialLoadSkipsAutomaticLoad() {
        assertFalse(InstalledAppListState(loading = true).needsInitialLoad)
    }

    @Test
    /** 同一会话已成功读取后，不因摘要时间很旧而重新自动扫描。 */
    fun successfulSessionSkipsAutomaticLoadRegardlessOfSummaryTime() {
        /** 已成功读取且摘要时间固定为极早值；自动加载必须只看会话状态。 */
        val state = InstalledAppListState(
            everLoaded = true,
            lastLoadSummary = InstalledAppLoadSummary(appCount = 1, elapsedMs = 1L, loadedAtMillis = 1L),
        )

        assertFalse(state.needsInitialLoad)
    }

    @Test
    /** 第一次读取失败不代表已有成功结果，下次自动入口应允许重试。 */
    fun initialFailureAllowsAutomaticRetry() {
        assertTrue(InstalledAppListState(unavailableReasonCode = "package_scan_failed").needsInitialLoad)
    }

    @Test
    /** 已成功会话后续刷新失败时，应由手动刷新重试，不重新启动自动扫描。 */
    fun failureAfterSuccessSkipsAutomaticLoad() {
        assertFalse(
            InstalledAppListState(everLoaded = true, unavailableReasonCode = "package_scan_failed").needsInitialLoad
        )
    }

    @Test
    /** 已成功会话的手动刷新仍在进行时，自动入口不能取消或替换它。 */
    fun runningRefreshAfterSuccessSkipsAutomaticLoad() {
        assertFalse(InstalledAppListState(loading = true, everLoaded = true).needsInitialLoad)
    }
}
