package com.cla.clip.master.ui.page.video

import com.cla.clip.master.R
import com.cla.clip.master.entity.VideoCandidate
import com.cla.clip.master.ui.widget.ProbeWebViewEvent
import org.junit.Assert.*
import org.junit.Test

/** 站点业务错误与原生加载错误分离；明确不可用页面无需等到超时。 */
class VideoPageUnavailableTest {
    /** 两种模式都应终止本轮，旧倒计时不能改写成超时。 */
    @Test fun currentLoadedErrorEndsBothModesBeforeTimeout() {
        VideoProbeMode.entries.forEach { mode ->
            // 不依赖时钟，直接模拟已完成文档的确定诊断。
            val session = loadedSession(mode)
            assertTrue(session.onPageUnavailable(session.sessionId, 1))
            assertEquals(ProbeState.Failed(VideoProbeFailure.PageContent), session.state)
            session.expire(session.sessionId, session.timerVersion)
            assertEquals(ProbeState.Failed(VideoProbeFailure.PageContent), session.state)
        }
    }

    /** 成功候选优先，业务错误的迟到回调不得撤销可下载结果。 */
    @Test fun acceptedCandidateSurvivesLatePageError() {
        // 当前文档处于允许接受候选的已加载状态。
        val session = loadedSession()
        // 候选仅为内存合成值，不执行下载。
        val candidate = VideoCandidate("https://example.invalid/fixture.mp4", null, null, null, "fixture")
        assertTrue(session.accept(session.sessionId, candidate))
        assertFalse(session.onPageUnavailable(session.sessionId, 1))
        assertEquals(ProbeState.Success(candidate), session.state)
    }

    /** 新导航、未完成文档、暂停和重试均封闭旧 DOM 的结果。 */
    @Test fun rejectsOldDocumentAndStoppedOrRetriedSession() {
        // 每次导航都需要独立已完成事件，不能复用上一页的诊断。
        val session = loadedSession()
        // 保存旧轮次以验证重试后不会接收上一轮的页面结论。
        val previous = session.sessionId
        session.onLoadEvent(previous, ProbeWebViewEvent.Started(2))
        assertFalse(session.onPageUnavailable(previous, 1))
        assertFalse(session.onPageUnavailable(previous, 2))
        session.onLoadEvent(previous, ProbeWebViewEvent.Finished(2))
        assertFalse(session.onPageUnavailable(previous, 1))
        session.pause()
        assertFalse(session.onPageUnavailable(previous, 2))
        session.retry()
        session.onLoadEvent(session.sessionId, ProbeWebViewEvent.Started(1))
        session.onLoadEvent(session.sessionId, ProbeWebViewEvent.Finished(1))
        assertFalse(session.onPageUnavailable(previous, 1))
        assertTrue(session.state is ProbeState.Probing)
    }

    /** 对用户只展示宿主资源中的通用失败文案，不携带平台原因。 */
    @Test fun pageErrorMapsToGenericExtractionFailure() {
        assertEquals(VideoProbeMessage(R.string.host_video_probe_failed), videoProbeFailureMessage(VideoProbeFailure.PageContent))
    }

    /** 只构造最小当前文档，不连接 Android WebView 或外网。 */
    private fun loadedSession(mode: VideoProbeMode = VideoProbeMode.Automatic): VideoProbeSession {
        // 当前轮次先开始再完成，符合生产回调顺序。
        val session = VideoProbeSession()
        if (mode == VideoProbeMode.Interactive) session.showWebPage() else session.resume()
        session.onLoadEvent(session.sessionId, ProbeWebViewEvent.Started(1))
        session.onLoadEvent(session.sessionId, ProbeWebViewEvent.Finished(1))
        return session
    }
}
