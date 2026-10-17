package com.cla.clip.master.ui.page.video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cla.clip.master.entity.VideoCandidate

/** 视频探测的内存状态机；不持有 WebView，主线程驱动，终态拒绝迟到结果。 */
internal class VideoProbeSession {
    /** 当前展示状态；Idle 同时表示未开始或离开前台后等待恢复。 */
    var state by mutableStateOf<ProbeState>(ProbeState.Idle)
        private set
    /** 每次真正加载分配新的轮次，重组不改变身份。 */
    var sessionId by mutableIntStateOf(0)
        private set

    /** 只恢复尚未完成的探测；成功/失败在重新组合时保持。 */
    fun resume() {
        if (state == ProbeState.Idle) start()
    }

    /** 显式重试开启下一轮，不受首次进入保护影响。 */
    fun retry() {
        start()
    }

    /** 退出前台关闭当前轮次；候选和错误终态不受影响。 */
    fun pause() {
        if (state is ProbeState.HiddenProbing) state = ProbeState.Idle
    }

    /** 仅当前运行轮次可以接受结果，返回是否发生有效状态转换。 */
    fun accept(expectedSession: Int, candidate: VideoCandidate): Boolean {
        if (state != ProbeState.HiddenProbing(expectedSession)) return false
        state = ProbeState.Success(candidate)
        return true
    }

    /** 超时或加载错误只关闭仍在运行的对应轮次。 */
    fun fail(expectedSession: Int) {
        if (state == ProbeState.HiddenProbing(expectedSession)) state = ProbeState.Failed
    }

    /** 新轮次与旧回调彻底隔离，调用者随后挂载对应 WebView。 */
    private fun start() {
        sessionId += 1
        state = ProbeState.HiddenProbing(sessionId)
    }
}
