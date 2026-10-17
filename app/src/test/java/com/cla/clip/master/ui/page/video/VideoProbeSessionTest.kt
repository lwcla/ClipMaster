package com.cla.clip.master.ui.page.video

import com.cla.clip.master.entity.VideoCandidate
import org.junit.Assert.*
import org.junit.Test

/** 保护失败重试和旧 WebView 回调不能覆盖新会话的业务闭环。 */
class VideoProbeSessionTest {
    /** 超时后明确重试必须重新进入探测，而不是停在错误页。 */
    @Test fun retryAfterFailureStartsNewSession() {
        // 会话和旧轮次用于模拟超时后点击重试。
        val session = VideoProbeSession()
        session.resume()
        // 失败或暂停前的旧身份，不允许迟到请求覆盖新状态。
        val old = session.sessionId
        session.fail(old)
        session.retry()
        assertTrue(session.state is ProbeState.HiddenProbing)
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
