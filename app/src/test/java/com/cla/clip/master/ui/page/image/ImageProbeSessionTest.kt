package com.cla.clip.master.ui.page.image

import org.junit.Assert.*
import org.junit.Test

/** 覆盖图片批次后的重新提取、生命周期恢复和旧回调隔离。 */
class ImageProbeSessionTest {
    /** 已提交批次后用户重新提取不能被 Extracted 的初始化保护挡住。 */
    @Test fun explicitRestartLeavesCompletedBatch() {
        // 模拟一次提交之后的重新提取动作。
        val session = ImageProbeSession()
        session.resume()
        session.finish(session.sessionId, true)
        session.submitted(7, 2)
        session.restart()
        assertTrue(session.state is ImageProbeState.Probing)
    }

    /** 待确认结果在组合重建/恢复时保持，正在扫描则恢复到新的回调身份。 */
    @Test fun resumeKeepsReadyStateAndRejectsPreviousGeneration() {
        // 旧会话代表进入后台前持有的 WebView。
        val session = ImageProbeSession()
        session.resume()
        // 进入后台前的会话身份，恢复后必须被拒绝。
        val old = session.sessionId
        session.pause()
        assertFalse(session.accepts(old))
        session.resume()
        assertFalse(session.accepts(old))
        session.finish(session.sessionId, true)
        session.resume()
        assertEquals(ImageProbeState.ReadyToDownload, session.state)
    }
}
