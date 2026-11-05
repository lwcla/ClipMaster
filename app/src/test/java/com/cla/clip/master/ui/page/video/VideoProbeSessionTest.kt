package com.cla.clip.master.ui.page.video

import com.cla.clip.master.entity.VideoCandidate
import org.junit.Assert.*
import org.junit.Test

/** 保护失败重试和旧 WebView 回调不能覆盖新会话的业务闭环。 */
class VideoProbeSessionTest {
    /** 展开必须沿用网页身份，旧自动倒计时不能关闭正在操作的页面。 */
    @Test fun showingPageKeepsSessionAndRejectsAutomaticTimeout() {
        // 先启动自动探测，记住即将过期的计时身份。
        val session = VideoProbeSession()
        session.resume()
        // session 标识网页实例，计时版本标识一次自动预算。
        val original = session.sessionId
        val timer = session.timerVersion
        session.showWebPage()
        assertEquals(original, session.sessionId)
        assertEquals(VideoProbeMode.Interactive, session.mode)
        session.expire(original, timer)
        assertTrue(session.state is ProbeState.Probing)
        assertTrue(session.accept(original, candidate()))
    }

    /** 收起重新计时；同一网页上先前的倒计时也不能误杀新的预算。 */
    @Test fun hidingPageRejectsOldBudgetButAcceptsCurrentBudget() {
        // 一个网页依次经历隐藏、展开、收起。
        val session = VideoProbeSession()
        session.resume()
        // 第一段自动计时必须在显隐切换后失效。
        val oldTimer = session.timerVersion
        session.showWebPage()
        session.hideWebPage()
        assertEquals(VideoProbeMode.Automatic, session.mode)
        assertTrue(session.timerVersion > oldTimer)
        session.expire(session.sessionId, oldTimer)
        assertTrue(session.state is ProbeState.Probing)
        session.expire(session.sessionId, session.timerVersion)
        assertEquals(ProbeState.Failed(), session.state)
    }

    /** 已失败网页不存在，查看必须创建可交互的新会话并拒绝旧候选。 */
    @Test fun viewingAfterFailureRetriesVisiblyAndRejectsPreviousCandidate() {
        // 模拟自动超时，然后通过查看入口继续排障。
        val session = VideoProbeSession()
        session.resume()
        // 原网页身份在重试后永久失效。
        val previous = session.sessionId
        session.fail(previous)
        session.showWebPage()
        assertTrue(session.sessionId > previous)
        assertEquals(VideoProbeMode.Interactive, session.mode)
        assertFalse(session.accept(previous, candidate()))
        assertTrue(session.accept(session.sessionId, candidate()))
    }

    /** 暂停释放网页，恢复保留用户展开偏好，但不能接收暂停前的回调。 */
    @Test fun resumeKeepsInteractivePreferenceWithNewSession() {
        // 展开的网页离开前台再回来，不退回不可见模式。
        val session = VideoProbeSession()
        session.resume()
        session.showWebPage()
        // 暂停前身份不能写入恢复后的会话。
        val previous = session.sessionId
        session.pause()
        assertFalse(session.accept(previous, candidate()))
        session.resume()
        assertTrue(session.sessionId > previous)
        assertEquals(VideoProbeMode.Interactive, session.mode)
    }

    /** 超时后明确重试必须重新进入探测，而不是停在错误页。 */
    @Test fun retryAfterFailureStartsNewSession() {
        // 会话和旧轮次用于模拟超时后点击重试。
        val session = VideoProbeSession()
        session.resume()
        // 失败或暂停前的旧身份，不允许迟到请求覆盖新状态。
        val old = session.sessionId
        session.fail(old)
        session.retry()
        assertTrue(session.state is ProbeState.Probing)
        assertTrue(session.sessionId > old)
        assertFalse(session.accept(old, candidate()))
        assertTrue(session.accept(session.sessionId, candidate()))
    }

    /** 暂停关闭旧回调，恢复只重启未完成探测，成功结果不重置。 */
    @Test fun pauseRejectsLateCandidateAndResumePreservesSuccess() {
        // 暂停中的原轮次不得在恢复前后写回。
        val session = VideoProbeSession()
        session.resume()
        // 失败或暂停前的旧身份，不允许迟到请求覆盖新状态。
        val old = session.sessionId
        session.pause()
        assertFalse(session.accept(old, candidate()))
        session.resume()
        assertFalse(session.accept(old, candidate()))
        assertTrue(session.accept(session.sessionId, candidate()))
        session.pause()
        session.resume()
        assertTrue(session.state is ProbeState.Success)
    }

    /** 候选仅为合成资源，不包含真实鉴权或用户内容。 */
    private fun candidate() = VideoCandidate("https://example.test/movie.mp4", null, null, null, "测试")
}
