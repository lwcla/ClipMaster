package com.cla.clip.base.general.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证剪贴文本中的链接提取、去重和安全分类规则。 */
class LinkUtilsTest {

    /** 空文本不应生成任何链接候选，避免详情页渲染空操作区。 */
    @Test
    fun extractUrlsReturnsEmptyListForBlankText() {
        assertEquals(emptyList<String>(), LinkUtils.extractUrls(null))
        assertEquals(emptyList<String>(), LinkUtils.extractUrls("   "))
    }

    /** 单链接提取结果必须与历史首链接语义保持一致。 */
    @Test
    fun extractFirstUrlKeepsSingleLinkBehavior() {
        /** 测试剪贴文本；只包含一个合成域名链接，避免使用真实用户 URL。 */
        val text = "打开 https://one.example.test/video/page"

        assertEquals(
            "https://one.example.test/video/page",
            LinkUtils.extractFirstUrl(text)
        )
    }

    /** 多链接按正文出现顺序返回，供详情页按用户看到的顺序展示。 */
    @Test
    fun extractUrlsReturnsMultipleLinksInSourceOrder() {
        /** 测试剪贴文本；包含两个不同合成域名链接。 */
        val text = "A https://a.example.test/one B https://b.example.test/two"

        assertEquals(
            listOf(
                "https://a.example.test/one",
                "https://b.example.test/two"
            ),
            LinkUtils.extractUrls(text)
        )
    }

    /** 重复的完整 URL 只保留第一条，避免详情页重复出现同一操作目标。 */
    @Test
    fun extractUrlsDeduplicatesByCompleteCleanedUrl() {
        /** 测试剪贴文本；同一完整链接重复出现两次。 */
        val text = "https://dup.example.test/a https://dup.example.test/a"

        assertEquals(
            listOf("https://dup.example.test/a"),
            LinkUtils.extractUrls(text)
        )
    }

    /** 同 host 但 query 不同可能代表不同资源，不能被摘要或 host 级规则误去重。 */
    @Test
    fun extractUrlsKeepsSameHostWithDifferentQuery() {
        /** 测试剪贴文本；两个链接只在 query 上不同。 */
        val text = "https://same.example.test/watch?id=1 https://same.example.test/watch?id=2"

        assertEquals(
            listOf(
                "https://same.example.test/watch?id=1",
                "https://same.example.test/watch?id=2"
            ),
            LinkUtils.extractUrls(text)
        )
    }

    /** 句尾中英文标点应被清理，避免复制和导航时携带正文标点。 */
    @Test
    fun extractUrlsCleansTrailingPunctuation() {
        /** 测试剪贴文本；链接后面跟随中英文句尾标点。 */
        val text = "英文 https://punct.example.test/a, 中文 https://punct.example.test/b。"

        assertEquals(
            listOf(
                "https://punct.example.test/a",
                "https://punct.example.test/b"
            ),
            LinkUtils.extractUrls(text)
        )
    }

    /** 括号数量平衡时保留右括号，不破坏 Wikipedia 类合法 URL。 */
    @Test
    fun extractUrlsKeepsBalancedClosingParenthesis() {
        /** 测试剪贴文本；URL path 内部括号是资源名的一部分。 */
        val text = "https://wiki.example.test/wiki/Name_(demo)"

        assertEquals(
            listOf("https://wiki.example.test/wiki/Name_(demo)"),
            LinkUtils.extractUrls(text)
        )
    }

    /** 可预览链接应从全部候选中选择第一条公网网页链接，而不是被第一条文件链接阻断。 */
    @Test
    fun extractFirstPreviewableUrlSkipsNonPreviewableCandidate() {
        /** 测试剪贴文本；第一条是图片直链，第二条是网页链接。 */
        val text = "https://asset.example.test/a.png https://page.example.test/article"

        assertEquals(
            "https://page.example.test/article",
            LinkUtils.extractFirstPreviewableUrl(text)
        )
    }

    /** 可下载媒体链接应从全部候选中选择第一条媒体直链。 */
    @Test
    fun extractFirstDownloadableMediaUrlFindsFirstMediaCandidate() {
        /** 测试剪贴文本；第一条是网页链接，第二条是视频直链。 */
        val text = "https://page.example.test/article https://media.example.test/video.mp4"

        assertEquals(
            "https://media.example.test/video.mp4",
            LinkUtils.extractFirstDownloadableMediaUrl(text)
        )
    }

    /** 公网提取判断只允许公网 http/https，其他协议、本机和内网地址不进入提取流程。 */
    @Test
    fun isPublicHttpUrlRejectsUnsupportedOrPrivateTargets() {
        assertTrue(LinkUtils.isPublicHttpUrl("https://public.example.test/page"))
        assertFalse(LinkUtils.isPublicHttpUrl("ftp://public.example.test/file"))
        assertFalse(LinkUtils.isPublicHttpUrl("file:///sdcard/local.txt"))
        assertFalse(LinkUtils.isPublicHttpUrl("http://localhost/page"))
        assertFalse(LinkUtils.isPublicHttpUrl("http://192.168.1.2/page"))
    }
}
