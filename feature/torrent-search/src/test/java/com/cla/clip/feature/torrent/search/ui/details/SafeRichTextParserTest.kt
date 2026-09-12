package com.cla.clip.feature.torrent.search.ui.details

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 安全富文本 parser 的主动内容、链接、图片和资源上限回归。 */
class SafeRichTextParserTest {
    /** HTML 主动内容及其正文必须被丢弃，普通文字和换行保留。 */
    @Test
    fun parseDropsActiveHtmlWithoutExecutingOrExposingItsBody() {
        /** raw 同时包含安全段落和多种主动内容。 */
        val raw = "<p>Visible</p><script>secret()</script><style>hidden</style><iframe>frame</iframe><svg><text>vector</text></svg>"

        /** document 是纯展示模型，不包含可执行节点。 */
        val document = SafeRichTextParser.parse(
            raw = raw,
            detailsUrl = "https://fixture.invalid/item",
            providerBaseUrl = "https://fixture.invalid",
            alternateUrlDomains = emptyList(),
        )

        assertTrue(document.text.contains("Visible"))
        assertFalse(document.text.contains("secret"))
        assertFalse(document.text.contains("hidden"))
        assertFalse(document.text.contains("frame"))
        assertFalse(document.text.contains("vector"))
    }

    /** HTML 与最小 Markdown 链接只保留规范化 HTTP/HTTPS，其他协议降级为纯文字。 */
    @Test
    fun parseKeepsOnlyNormalizedWebLinks() {
        /** raw 覆盖 HTML、Markdown、磁力和脚本协议。 */
        val raw = "<a href='/relative'>Relative</a> [Secure](https://fixture.invalid/docs) [Magnet](magnet:?xt=x) [Script](javascript:alert(1))"

        /** document 只应产出两个可点击网页链接。 */
        val document = SafeRichTextParser.parse(raw, "https://fixture.invalid/item", "https://fixture.invalid", emptyList())

        assertEquals(listOf("https://fixture.invalid/relative", "https://fixture.invalid/docs"), document.links.map { /** link 是已规范化网页链接。 */ link -> link.url })
        assertTrue(document.text.contains("Magnet"))
        assertTrue(document.text.contains("Script"))
    }

    /** 正文图片必须为 HTTPS 且命中详情、基础或备用域名，拒绝凭据和跨域。 */
    @Test
    fun parseKeepsOnlyAllowlistedHttpsImages() {
        /** raw 覆盖同站相对图、备用域、明文、跨域和 user-info。 */
        val raw = "![A](/a.jpg) ![B](https://cdn.fixture.invalid/b.jpg) ![C](http://fixture.invalid/c.jpg) " +
            "![D](https://other.invalid/d.jpg) ![E](https://user@fixture.invalid/e.jpg)"

        /** document 应只保留同站和显式备用域图片。 */
        val document = SafeRichTextParser.parse(
            raw,
            "https://fixture.invalid/item",
            "https://fixture.invalid",
            listOf("cdn.fixture.invalid"),
        )

        assertEquals(listOf("https://fixture.invalid/a.jpg", "https://cdn.fixture.invalid/b.jpg"), document.imageUrls)
    }

    /** 原文、输出、链接、图片和 URL 均受显式上限约束且超限不崩溃。 */
    @Test
    fun parseEnforcesAllResourceLimits() {
        /** limits 使用小阈值让每条边界都可直接观察。 */
        val limits = SafeRichTextLimits(
            maxRawCharacters = 120,
            maxOutputCharacters = 24,
            maxLinks = 1,
            maxImages = 1,
            maxUrlCharacters = 48,
        )
        /** raw 包含超长正文、多链接、多图片和超长 URL。 */
        val raw = "[A](https://fixture.invalid/a) [B](https://fixture.invalid/b) " +
            "![I](https://fixture.invalid/i.jpg) ![J](https://fixture.invalid/j.jpg) ${"x".repeat(80)}"

        /** document 必须在所有集合和文字上限内安全收敛。 */
        val document = SafeRichTextParser.parse(raw, "https://fixture.invalid/item", "https://fixture.invalid", emptyList(), limits)

        assertTrue(document.text.length <= limits.maxOutputCharacters)
        assertEquals(1, document.links.size)
        assertEquals(1, document.imageUrls.size)
        assertTrue(document.links.all { /** link 是待复核 URL 长度的安全链接。 */ link -> link.url.length <= limits.maxUrlCharacters })
        assertTrue(document.imageUrls.all { /** url 是待复核长度上限的安全图片。 */ url -> url.length <= limits.maxUrlCharacters })
    }
}
