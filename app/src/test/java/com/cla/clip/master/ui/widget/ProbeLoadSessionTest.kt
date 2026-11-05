package com.cla.clip.master.ui.widget

import org.junit.Assert.*
import org.junit.Test

/** 共享探测加载只由会话目标驱动，重定向和重组不能重新发起导航。 */
class ProbeLoadSessionTest {
    /** 新导航提交前 getUrl 仍可能是旧页面，网络/HTTP/SSL 失败要匹配最新导航目标。 */
    @Test fun pendingNavigationFailureDoesNotRequireCommittedCurrentUrl() {
        // 每种错误使用独立会话，避免去重状态掩盖地址匹配规则。
        for (kind in ProbeWebFailureKind.entries) {
            // 已显示 old，开始跳转 next，但下一页尚未提交。
            val session = ProbeLoadSession()
            val failure = ProbeWebFailure(kind, 1)
            session.beginDocument("old")
            session.beginDocument("next")
            assertFalse(session.claimDocumentFailure("old", "old", true, failure))
            assertFalse(session.claimDocumentFailure("next", "old", false, failure))
            assertTrue("新导航主文档错误不能依赖已提交 URL，kind=$kind",
                session.claimDocumentFailure("next", "old", true, failure))
            // 完成仍要求当前显示页面匹配，错误规则不能放松完成去重契约。
            assertFalse(session.claimPageFinished("next", "old"))
        }
    }

    /** 生命周期 STOP 与 AndroidView 离场可能先后清理，同一资源只允许释放一次。 */
    @Test fun onlyFirstCloseOwnsResourceRelease() {
        // 两个清理入口共享同一会话门闩。
        val session = ProbeLoadSession()
        assertTrue(session.close())
        assertFalse(session.close())
        assertFalse(session.acceptsCallbacks)
    }

    /** 同文档错误只报告一次；新导航清除错误，但旧回调不能重新污染它。 */
    @Test fun documentFailureIsDeduplicatedAndClearedOnNavigation() {
        // 错误只使用合成状态码，不包含真实 URL。
        val session = ProbeLoadSession()
        val error = ProbeWebFailure(ProbeWebFailureKind.Http, 403)
        session.beginDocument("first")
        assertFalse(session.claimDocumentFailure("first", "first", false, error))
        assertTrue(session.claimDocumentFailure("first", "first", true, error))
        assertFalse(session.claimDocumentFailure("first", "first", true, error))
        assertEquals(error, session.documentFailure)
        session.beginDocument("second")
        assertNull(session.documentFailure)
        assertFalse(session.claimDocumentFailure("first", "second", true, error))
        session.close()
        assertFalse(session.claimDocumentFailure("second", "second", true, error))
    }

    /** 旧文档和子资源错误不得冒充当前网页加载失败，释放后全部拒绝。 */
    @Test fun onlyCurrentMainDocumentCanReportFailure() {
        // 合成地址分别代表重定向中间文档和最终页面。
        val session = ProbeLoadSession()
        session.beginDocument("old")
        session.beginDocument("current")
        assertFalse(session.acceptsDocumentEvent("old", "current", true))
        assertFalse(session.acceptsDocumentEvent("current", "current", false))
        assertTrue(session.acceptsDocumentEvent("current", "current", true))
        session.close()
        assertFalse(session.acceptsDocumentEvent("current", "current", true))
    }

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
