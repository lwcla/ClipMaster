package com.cla.clip.master.ui.page.video

import com.cla.clip.master.ui.widget.ProbeWebFailure
import com.cla.clip.master.ui.widget.ProbeWebFailureKind
import com.cla.clip.master.ui.widget.ProbeWebViewEvent
import org.junit.Assert.*
import org.junit.Test

/** 保护错误现场、文档隔离和辅助提示，不依赖真实网页或时间。 */
class VideoProbeDiagnosticsTest {
    /** 自动加载的 HTTP 错误应提前失败，并保留数值原因。 */
    @Test fun automaticMainDocumentFailureKeepsReason() {
        // 已开始加载的自动会话接收合成 HTTP 错误。
        val session = VideoProbeSession()
        session.resume()
        session.onLoadEvent(session.sessionId, ProbeWebViewEvent.Started(1))
        // 错误没有外部文本或用户地址。
        val error = ProbeWebFailure(ProbeWebFailureKind.Http, 403)
        session.onLoadEvent(session.sessionId, ProbeWebViewEvent.Failed(1, error))
        assertEquals(ProbeState.Failed(VideoProbeFailure.PageLoad(error)), session.state)
    }

    /** WebView 常在错误后仍调用完成，交互现场必须继续显示错误而非无限加载。 */
    @Test fun interactiveFailureSurvivesFinishedUntilNextDocument() {
        // 用户展开的网页必须留在可恢复会话中。
        val session = VideoProbeSession()
        session.showWebPage()
        session.onLoadEvent(session.sessionId, ProbeWebViewEvent.Started(1))
        // 模拟连接超时后又发出完成回调。
        val error = ProbeWebFailure(ProbeWebFailureKind.Network, -8)
        session.onLoadEvent(session.sessionId, ProbeWebViewEvent.Failed(1, error))
        session.onLoadEvent(session.sessionId, ProbeWebViewEvent.Finished(1))
        session.onLoadEvent(session.sessionId, ProbeWebViewEvent.Progress(1, 100))
        assertTrue(session.state is ProbeState.Probing)
        assertEquals(VideoProbePageState.Error(1, error), session.pageState)
        session.onLoadEvent(session.sessionId, ProbeWebViewEvent.Started(2))
        assertEquals(VideoProbePageState.Loading(2), session.pageState)
    }

    /** 新文档开始后，旧错误和旧百分比不得覆盖当前页面。 */
    @Test fun rejectsOldDocumentAndPausedSessionEvents() {
        // 两个连续导航共用一个网页，但诊断不能混在一起。
        val session = VideoProbeSession()
        session.showWebPage()
        session.onLoadEvent(session.sessionId, ProbeWebViewEvent.Started(1))
        session.onLoadEvent(session.sessionId, ProbeWebViewEvent.Started(2))
        session.onLoadEvent(session.sessionId, ProbeWebViewEvent.Progress(2, 40))
        session.onLoadEvent(session.sessionId, ProbeWebViewEvent.Progress(1, 95))
        session.onLoadEvent(session.sessionId, ProbeWebViewEvent.Failed(1, ProbeWebFailure(ProbeWebFailureKind.Http, 500)))
        assertEquals(VideoProbePageState.Loading(2, 40), session.pageState)
        // 暂停后所有旧事件失效，恢复不沿用旧诊断。
        val old = session.sessionId
        session.pause()
        session.resume()
        session.onLoadEvent(old, ProbeWebViewEvent.Started(99))
        assertEquals(VideoProbePageState.Loading(), session.pageState)
        assertEquals(VideoProbeNotice.Reloaded, session.notice)
    }

    /** 三十秒提醒只属于当前交互预算，旧任务不能把提示带到收起后的自动模式。 */
    @Test fun longWaitOnlyNotifiesCurrentInteractiveBudget() {
        // 时间由显式预算事件代替，测试不等待真实时间。
        val session = VideoProbeSession()
        session.showWebPage()
        session.onLongRunning(session.sessionId, session.timerVersion)
        assertEquals(VideoProbeNotice.LongRunning, session.notice)
        assertTrue(session.state is ProbeState.Probing)
        // 记录已经结束的交互预算。
        val oldTimer = session.timerVersion
        session.hideWebPage()
        session.onLongRunning(session.sessionId, oldTimer)
        assertNull(session.notice)
    }
}
