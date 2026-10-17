package com.cla.clip.master.ui.page.image

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 图片探测与批次展示状态；候选和选择由 VM 保存，暂停不清空用户工作。 */
internal class ImageProbeSession {
    /** 唯一页面阶段来源，只有本控制器可改变。 */
    var state by mutableStateOf<ImageProbeState>(ImageProbeState.Idle)
        private set
    /** 运行轮次不等于用户重新提取次数，后台恢复也会分配新回调身份。 */
    var sessionId by mutableIntStateOf(0)
        private set

    /** 恢复未完成会话，待确认和批次结果保持原状。 */
    fun resume() {
        if (state == ImageProbeState.Idle) start()
    }

    /** 用户显式重新提取可以离开失败或已提交批次。 */
    fun restart() {
        start()
    }

    /** 暂停运行中的扫描；用户候选和选择不属于此状态机，不会被清空。 */
    fun pause() {
        if (state is ImageProbeState.Probing) state = ImageProbeState.Idle
    }

    /** 只允许当前运行会话回写，终态和暂停均拒绝。 */
    fun accepts(expectedSession: Int): Boolean = state == ImageProbeState.Probing(expectedSession)

    /** 有候选时进入确认，没有候选时提供失败重试入口。 */
    fun finish(expectedSession: Int, hasCandidates: Boolean) {
        if (accepts(expectedSession)) {
            state = if (hasCandidates) ImageProbeState.ReadyToDownload else ImageProbeState.Failed
        }
    }

    /** 成功创建正式批次后切换任务观察，下载不受页面生命周期影响。 */
    fun submitted(batchId: Long, count: Int) {
        state = ImageProbeState.Extracted(batchId, count)
    }

    /** 新 WebView 回调身份，避免暂停前的网络回调混入恢复后的扫描。 */
    private fun start() {
        sessionId += 1
        state = ImageProbeState.Probing(sessionId)
    }
}
