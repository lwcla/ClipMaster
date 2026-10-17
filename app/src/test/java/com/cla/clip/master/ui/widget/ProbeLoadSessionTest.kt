package com.cla.clip.master.ui.widget

import org.junit.Assert.*
import org.junit.Test

/** 共享探测加载只由会话目标驱动，重定向和重组不能重新发起导航。 */
class ProbeLoadSessionTest {
    /** JS 中转页真正完成后再跳正文，正文仍须触发扫描和播放探测。 */
    @Test fun completedRedirectPageDoesNotConsumeFinalDocument() {
        // 同一 WebView 中有两个先后完成的不同主文档。
        val session = ProbeLoadSession()
        assertTrue(session.claimPageFinished("redirect", "redirect"))
        assertTrue(session.claimPageFinished("article", "article"))
        assertFalse(session.claimPageFinished("article", "article"))
    }
    /** 短链跳转后的当前 URL 不应被当作新加载请求。 */
    @Test fun redirectedUrlDoesNotReloadOriginalTarget() {
        // 独立会话在首次加载后模拟短链重定向和 UI 更新。
        val session = ProbeLoadSession()
        assertTrue(session.shouldLoad("https://example.test/short"))
        assertTrue(session.claimPageFinished("https://example.test/final", "https://example.test/final"))
        assertFalse(session.shouldLoad("https://example.test/short"))
    }

    /** 完成回调必须来自当前文档且一轮只启动一次扫描，释放后不接受任何回调。 */
    @Test fun ignoresStaleAndDuplicatePageFinishedCallbacks() {
        // 两个 URL 代表已跳过的中间页和当前文档。
        val session = ProbeLoadSession()
        session.beginDocument("current")
        assertFalse(session.claimPageFinished("old", "current"))
        assertTrue(session.claimPageFinished("current", "current"))
        assertFalse(session.claimPageFinished("current", "current"))
        session.beginDocument("current")
        assertTrue(session.claimPageFinished("current", "current"))
        session.close()
        assertFalse(session.acceptsCallbacks)
        assertFalse(session.claimPageFinished("new", "new"))
    }
}
