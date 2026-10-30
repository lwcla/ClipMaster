package com.cla.clip.master.ui.widget.document

import org.junit.Assert.assertEquals
import org.junit.Test

/** 保护离线文档不能自动联网、跨到其他文件或启动任意应用的边界。 */
class LocalDocumentLinkPolicyTest {
    /** 文档本身和章节/语言锚点始终在当前阅读器内处理。 */
    @Test fun keepsDocumentAndAnchorsInsideReader() {
        assertEquals(LocalDocumentLinkAction.Internal, localDocumentLinkAction(LOCAL_DOCUMENT_URL, false))
        assertEquals(LocalDocumentLinkAction.Internal, localDocumentLinkAction("$LOCAL_DOCUMENT_URL#en", true))
    }

    /** 仅明确点击的 HTTPS 和联系邮箱链接允许交给外部处理程序。 */
    @Test fun opensSupportedExternalLinksOnlyAfterGesture() {
        assertEquals(LocalDocumentLinkAction.External, localDocumentLinkAction("https://example.test/policy", true))
        assertEquals(LocalDocumentLinkAction.External, localDocumentLinkAction("mailto:privacy@example.test", true))
        assertEquals(LocalDocumentLinkAction.Blocked, localDocumentLinkAction("https://example.test/policy", false))
        assertEquals(LocalDocumentLinkAction.Blocked, localDocumentLinkAction("mailto:privacy@example.test", false))
    }

    /** 文件、内容提供者、脚本、明文链接和任意应用协议不能离开阅读边界。 */
    @Test fun blocksUnsupportedSchemesAndMalformedLinks() {
        // 合成危险及非法输入只用于回归，不包含用户路径或数据。
        val blockedLinks = listOf("file:///synthetic.html", "content://synthetic/item", "javascript:alert(1)",
            "intent://synthetic", "http://example.test", "https:missing-host", "https://", "", "not a uri")
        blockedLinks.forEach { link ->
            assertEquals(link, LocalDocumentLinkAction.Blocked, localDocumentLinkAction(link, true))
        }
    }

    /** 同名文件后缀、查询串及虚拟站点其他路径不能伪装成当前文档锚点。 */
    @Test fun blocksOtherVirtualDocumentUrls() {
        assertEquals(LocalDocumentLinkAction.Blocked, localDocumentLinkAction("$LOCAL_DOCUMENT_URL.evil", true))
        assertEquals(LocalDocumentLinkAction.Blocked, localDocumentLinkAction("$LOCAL_DOCUMENT_URL?next=1", true))
        assertEquals(LocalDocumentLinkAction.Blocked, localDocumentLinkAction("https://appassets.androidplatform.net/other.html", true))
    }
}
