package com.cla.clip.feature.torrent.search.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 详情、分享和下载统一外部链接 scheme 白名单测试。 */
class ExternalLinkPolicyTest {
    /** HTTP、HTTPS 与 magnet 是唯一允许交给系统处理的外部 scheme。 */
    @Test
    fun allowsOnlyReviewedExternalSchemes() {
        assertTrue(ExternalLinkPolicy.isAllowed("https://example.invalid/item"))
        assertTrue(ExternalLinkPolicy.isAllowed("HTTP://example.invalid/item"))
        assertTrue(ExternalLinkPolicy.isAllowed("magnet:?xt=urn:btih:0123456789012345678901234567890123456789"))
    }

    /** 脚本、文件、相对路径和语法损坏输入必须在启动系统组件前拒绝。 */
    @Test
    fun rejectsUnreviewedOrMalformedExternalLinks() {
        assertFalse(ExternalLinkPolicy.isAllowed("javascript:alert(1)"))
        assertFalse(ExternalLinkPolicy.isAllowed("file:///data/local/tmp/item"))
        assertFalse(ExternalLinkPolicy.isAllowed("/relative/path"))
        assertFalse(ExternalLinkPolicy.isAllowed("https://exa mple.invalid"))
        assertFalse(ExternalLinkPolicy.isAllowed("   "))
    }
}
