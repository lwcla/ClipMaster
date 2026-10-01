package com.cla.clip.master.ui.page.mine

import org.junit.Assert.assertEquals
import org.junit.Test

/** 展示状态回归使用合成数量，不读取真实安装应用、搜索词或系统时钟。 */
class ClipSourceBlockListContentStateTest {
    /** 普通用例模拟已按当前摘要完成映射；独立用例显式传入过期摘要保护延迟场景。 */
    private fun resolveClipSourceBlockListContentState(
        // 本用例的会话状态，无真实时钟和平台依赖。
        state: InstalledAppListState,
        // 合成的未裁剪候选数量。
        candidateCount: Int,
        // 合成的当前可见行数。
        visibleCandidateCount: Int,
    ): ClipSourceBlockListContentState {
        /** 成功 fixture 补齐真实协调器始终提供的摘要，普通用例模拟同一快照已映射完成。 */
        val completedState = if (state.everLoaded && state.lastLoadSummary == null) {
            state.copy(lastLoadSummary = InstalledAppLoadSummary(state.loadedAppCount, 0L, 0L))
        } else state
        return com.cla.clip.master.ui.page.mine.resolveClipSourceBlockListContentState(
            completedState, candidateCount, visibleCandidateCount, completedState.lastLoadSummary,
        )
    }

    /** 数量相同但候选来自上次扫描，也不能把旧搜索零命中当成本次结果。 */
    @Test
    fun sameCountRefreshWaitsForCurrentSnapshot() {
        /** 两个数值相同的摘要仍来自不同扫描，不能通过值相等推断映射已更新。 */
        val previousSummary = InstalledAppLoadSummary(1, 0L, 0L)
        /** 同数量、同时间的成功摘要对象在每次扫描都重新创建，身份必须区别。 */
        val currentSummary = InstalledAppLoadSummary(1, 0L, 0L)
        /** 当前扫描成功，但页面仍观察到上次扫描映射的候选。 */
        val state = InstalledAppListState(everLoaded = true, loadedAppCount = 1, lastLoadSummary = currentSummary)
        assertEquals(ClipSourceBlockListContentState.Loading, com.cla.clip.master.ui.page.mine.resolveClipSourceBlockListContentState(state, 1, 0, previousSummary))
    }

    /** 当前成功摘要已随候选到达后，正常无匹配应结束加载并展示空结果。 */
    @Test
    fun currentSnapshotWithoutMatchesShowsEmpty() {
        /** 合成扫描摘要既作为当前状态事实，也直接传给候选，不复制或按值重建。 */
        val summary = InstalledAppLoadSummary(1, 0L, 0L)
        /** 本次读取已成功且后台映射完成，当前搜索没有可见行。 */
        val state = InstalledAppListState(everLoaded = true, loadedAppCount = 1, lastLoadSummary = summary)
        assertEquals(ClipSourceBlockListContentState.Empty, com.cla.clip.master.ui.page.mine.resolveClipSourceBlockListContentState(state, 1, 0, summary))
    }

    /** 首帧尚未触发可见扫描，默认 loading=false 不能被解释为成功空结果。 */
    @Test
    fun initialEmptySnapshotShowsLoadingBeforeScanStarts() {
        assertEquals(ClipSourceBlockListContentState.Loading, resolveClipSourceBlockListContentState(InstalledAppListState(), 0, 0))
    }

    /** 首次扫描进行中且没有候选，应显示进度而不是无应用提示。 */
    @Test
    fun firstScanInProgressShowsLoading() {
        assertEquals(ClipSourceBlockListContentState.Loading, resolveClipSourceBlockListContentState(InstalledAppListState(loading = true), 0, 0))
    }

    /** 完整成功零结果才是可以宣告的空应用状态。 */
    @Test
    fun successfulZeroResultShowsEmpty() {
        assertEquals(ClipSourceBlockListContentState.Empty, resolveClipSourceBlockListContentState(InstalledAppListState(everLoaded = true), 0, 0))
    }

    /** 已成功的非空快照被搜索或范围过滤到零命中，仍是正常空结果。 */
    @Test
    fun successfulScanWithNoVisibleMatchesShowsEmpty() {
        assertEquals(ClipSourceBlockListContentState.Empty, resolveClipSourceBlockListContentState(InstalledAppListState(everLoaded = true, loadedAppCount = 2), 2, 0))
    }

    /** 成功状态先到、后台候选映射尚未发布，不能在这一帧闪出空提示。 */
    @Test
    fun successfulScanWaitsForCandidateMapping() {
        assertEquals(ClipSourceBlockListContentState.Loading, resolveClipSourceBlockListContentState(InstalledAppListState(everLoaded = true, loadedAppCount = 2), 0, 0))
    }

    /** 新扫描零结果先到、旧候选尚未替换，展示就绪判断也要等待数量一致。 */
    @Test
    fun zeroResultWaitsForPreviousCandidatesToBeReplaced() {
        assertEquals(ClipSourceBlockListContentState.Loading, resolveClipSourceBlockListContentState(InstalledAppListState(everLoaded = true), 1, 0))
    }

    /** 旧结果为空的刷新过程中不能继续宣告当前扫描也为空。 */
    @Test
    fun refreshWithoutVisibleRowsShowsLoading() {
        assertEquals(ClipSourceBlockListContentState.Loading, resolveClipSourceBlockListContentState(InstalledAppListState(everLoaded = true, loading = true), 0, 0))
    }

    /** 刷新有可用旧行时保持列表，加载指示由下拉容器负责。 */
    @Test
    fun refreshKeepsVisibleRows() {
        assertEquals(ClipSourceBlockListContentState.Content, resolveClipSourceBlockListContentState(InstalledAppListState(everLoaded = true, loading = true, loadedAppCount = 2), 2, 1))
    }

    /** 首次读取失败不是零结果成功，必须显示失败/重试。 */
    @Test
    fun firstScanFailureShowsFailure() {
        assertEquals(ClipSourceBlockListContentState.Failed, resolveClipSourceBlockListContentState(InstalledAppListState(unavailableReasonCode = "synthetic_failure"), 0, 0))
    }

    /** 刷新失败且当前无行，不能把失败误显示为空应用。 */
    @Test
    fun refreshFailureWithoutRowsShowsFailure() {
        assertEquals(ClipSourceBlockListContentState.Failed, resolveClipSourceBlockListContentState(InstalledAppListState(everLoaded = true, unavailableReasonCode = "synthetic_failure"), 0, 0))
    }

    /** 刷新失败仍有可见旧行，继续展示而不是全屏替换为失败。 */
    @Test
    fun refreshFailureKeepsVisibleRows() {
        assertEquals(ClipSourceBlockListContentState.Content, resolveClipSourceBlockListContentState(InstalledAppListState(everLoaded = true, loadedAppCount = 2, unavailableReasonCode = "synthetic_failure"), 2, 1))
    }

    /** 正常成功且有可见行时不显示加载或空态。 */
    @Test
    fun successfulRowsShowContent() {
        assertEquals(ClipSourceBlockListContentState.Content, resolveClipSourceBlockListContentState(InstalledAppListState(everLoaded = true, loadedAppCount = 2), 2, 2))
    }
}
