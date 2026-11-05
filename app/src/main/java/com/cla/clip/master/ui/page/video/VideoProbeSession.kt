package com.cla.clip.master.ui.page.video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cla.clip.master.entity.VideoCandidate
import com.cla.clip.master.ui.widget.ProbeWebViewEvent

/** 主线程驱动的视频提取会话；网页资源归 Compose，控制器只保存可测试的内存状态。 */
internal class VideoProbeSession {
    /** 业务终态拒绝迟到候选，Idle 同时表示等待生命周期恢复。 */
    var state by mutableStateOf<ProbeState>(ProbeState.Idle)
        private set
    /** 网页身份仅在真正加载时更新，显隐不创建新身份。 */
    var sessionId by mutableIntStateOf(0)
        private set
    /** 页面暂停后仍保留的显示偏好，进程重建回自动模式。 */
    var mode by mutableStateOf(VideoProbeMode.Automatic)
        private set
    /** 同一网页上的预算代次，防止旧自动倒计时关闭收起后的新预算。 */
    var timerVersion by mutableIntStateOf(0)
        private set
    /** 诊断独立于计时状态，百分比重组不会延长自动探测。 */
    var pageState by mutableStateOf<VideoProbePageState>(VideoProbePageState.Loading())
        private set
    /** 辅助提示不启动网络任务，也不改变候选接受边界。 */
    var notice by mutableStateOf<VideoProbeNotice?>(null)
        private set

    /** 只恢复未完成会话，成功和失败不能因重组自动重试。 */
    fun resume() {
        if (state == ProbeState.Idle) start(reloaded = sessionId > 0)
    }

    /** 用户明确重试时沿用显示偏好，重新加载原入口。 */
    fun retry() { start() }

    /** 离开前台立即封闭旧轮次，实际资源由 AndroidView 释放。 */
    fun pause() {
        if (state !is ProbeState.Probing) return
        state = ProbeState.Idle
        timerVersion += 1
        pageState = VideoProbePageState.Loading()
        notice = null
    }

    /** 展开保留运行中的网页；失败后查看明确开启可见的新轮次。 */
    fun showWebPage() {
        if (state is ProbeState.Success) return
        if (state is ProbeState.Probing) switchMode(VideoProbeMode.Interactive)
        else {
            mode = VideoProbeMode.Interactive
            start()
        }
    }

    /** 收起只改变交互与预算，不重载网页。 */
    fun hideWebPage() {
        if (state is ProbeState.Probing) switchMode(VideoProbeMode.Automatic)
    }

    /** 只让当前自动预算失败，旧协程和交互模式的计时事件均无效。 */
    fun expire(expectedSession: Int, expectedTimer: Int) {
        if (mode == VideoProbeMode.Automatic && timerVersion == expectedTimer) fail(expectedSession)
    }

    /** 长等待只给当前交互会话提示；错误页面已有更具体说明，不用等待提示覆盖。 */
    fun onLongRunning(expectedSession: Int, expectedTimer: Int) {
        if (accepts(expectedSession) && mode == VideoProbeMode.Interactive && timerVersion == expectedTimer &&
            pageState !is VideoProbePageState.Error && notice != VideoProbeNotice.ExternalAppBlocked) {
            notice = VideoProbeNotice.LongRunning
        }
    }

    /** 平台事件按网页与主文档身份归约，错误后的完成/百分比不能抹掉错误现场。 */
    fun onLoadEvent(expectedSession: Int, event: ProbeWebViewEvent) {
        if (!accepts(expectedSession)) return
        if (event is ProbeWebViewEvent.Started) {
            if (event.documentId <= pageState.documentId) return
            pageState = VideoProbePageState.Loading(event.documentId)
            if (notice == VideoProbeNotice.ExternalAppBlocked) notice = null
            return
        }
        if (event.documentId != pageState.documentId) return
        when (event) {
            is ProbeWebViewEvent.Progress -> {
                if (pageState is VideoProbePageState.Loading) {
                    pageState = VideoProbePageState.Loading(event.documentId, event.percent.coerceIn(0, 100))
                }
            }
            is ProbeWebViewEvent.Finished -> {
                if (pageState !is VideoProbePageState.Error) pageState = VideoProbePageState.Loaded(event.documentId)
            }
            is ProbeWebViewEvent.Failed -> {
                pageState = VideoProbePageState.Error(event.documentId, event.error)
                if (mode == VideoProbeMode.Automatic) fail(expectedSession, VideoProbeFailure.PageLoad(event.error))
            }
            is ProbeWebViewEvent.ExternalAppBlocked -> notice = VideoProbeNotice.ExternalAppBlocked
            is ProbeWebViewEvent.Started -> Unit
        }
    }

    /** 仅当前已完成文档的确定业务错误结束两种模式；成功、旧导航与暂停结果一律拒绝。 */
    fun onPageUnavailable(expectedSession: Int, expectedDocument: Int): Boolean {
        if (!accepts(expectedSession) || pageState !is VideoProbePageState.Loaded ||
            pageState.documentId != expectedDocument) return false
        fail(expectedSession, VideoProbeFailure.PageContent)
        return true
    }

    /** 当前网页的首个候选进入终态，成功后任何迟到事件都被拒绝。 */
    fun accept(expectedSession: Int, candidate: VideoCandidate): Boolean {
        if (!accepts(expectedSession)) return false
        state = ProbeState.Success(candidate)
        return true
    }

    /** 自动超时或明确加载错误关闭轮次；错误对象仅保存安全分类。 */
    fun fail(expectedSession: Int, reason: VideoProbeFailure = VideoProbeFailure.Timeout) {
        if (accepts(expectedSession)) state = ProbeState.Failed(reason)
    }

    /** 只比较运行态的会话身份，不把显隐模式当作另一张网页。 */
    private fun accepts(expectedSession: Int): Boolean = (state as? ProbeState.Probing)?.sessionId == expectedSession

    /** 显隐切换更新预算版本并清理短期提示，幂等点击不重置倒计时。 */
    private fun switchMode(next: VideoProbeMode) {
        if (mode == next) return
        mode = next
        timerVersion += 1
        notice = null
        state = ProbeState.Probing(sessionId, mode)
    }

    /** 新网页和旧回调完全隔离；恢复提示告知用户网页现场已重新加载。 */
    private fun start(reloaded: Boolean = false) {
        sessionId += 1
        timerVersion += 1
        pageState = VideoProbePageState.Loading()
        notice = if (reloaded) VideoProbeNotice.Reloaded else null
        state = ProbeState.Probing(sessionId, mode)
    }
}
