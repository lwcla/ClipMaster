package com.cla.clip.master.ui.page.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证详情页链接摘要、类型和提取安全边界。 */
class DetailLinkFormatterTest {

    /** 链接摘要应隐藏 query 参数，只保留 host 和有限 path 片段。 */
    @Test
    fun summarizeDetailLinkHidesQueryParameters() {
        /** 带 query 的合成链接；query 不应进入 UI 摘要。 */
        val url = "https://www.example.test/video/watch?id=1&token=hidden"

        assertEquals(
            "example.test/video/watch",
            summarizeDetailLink(url)
        )
    }

    /** 长 path 应压缩为前两段并追加省略标记，避免撑高详情页底部。 */
    @Test
    fun summarizeDetailLinkCompressesLongPath() {
        /** path 超过两段的合成链接，用于验证摘要截断策略。 */
        val url = "https://example.test/first/second/third/fourth"

        assertEquals(
            "example.test/first/second/...",
            summarizeDetailLink(url)
        )
    }

    /** 同摘要冲突时也不追加序号，列表只展示识别到的链接摘要本身。 */
    @Test
    fun buildDetailLinkItemsKeepsDuplicateSummariesWithoutSequence() {
        /** 两条链接 query 不同但摘要相同，必须保留但不额外添加编号。 */
        val content = "https://same.example.test/watch?id=1 https://same.example.test/watch?id=2"

        /** 构建出的详情页链接模型；顺序应与正文一致。 */
        val items = buildDetailLinkItems(content)

        assertEquals("same.example.test/watch", items[0].summary)
        assertEquals("same.example.test/watch", items[1].summary)
        assertEquals(2, items.size)
    }

    /** 图片、媒体、网页和其他链接应被归入对应类型，方便多链接选择。 */
    @Test
    fun buildDetailLinkItemsClassifiesLinkTypes() {
        /** 覆盖四种类型的合成剪贴文本。 */
        val content = listOf(
            "https://asset.example.test/a.png",
            "https://media.example.test/a.mp4",
            "https://page.example.test/article",
            "ftp://files.example.test/archive.zip"
        ).joinToString(separator = " ")

        /** 构建出的详情页链接模型；类型判断由 LinkUtils 复用完成。 */
        val items = buildDetailLinkItems(content)

        assertEquals(DetailLinkType.Image, items[0].type)
        assertEquals(DetailLinkType.Media, items[1].type)
        assertEquals(DetailLinkType.Web, items[2].type)
        assertEquals(DetailLinkType.Other, items[3].type)
    }

    /** 非公网或非 http/https 链接可以复制，但不能进入图片/视频提取流程。 */
    @Test
    fun buildDetailLinkItemsDisablesExtractionForUnsafeTargets() {
        /** 覆盖 file、ftp、本机和内网地址的合成剪贴文本。 */
        val content = listOf(
            "file:///sdcard/local.txt",
            "ftp://files.example.test/archive.zip",
            "http://localhost/page",
            "http://192.168.1.2/page",
            "https://public.example.test/page"
        ).joinToString(separator = " ")

        /** 构建出的详情页链接模型；只有公网 https 链接允许提取。 */
        val items = buildDetailLinkItems(content)

        assertFalse(items[0].canExtract)
        assertFalse(items[1].canExtract)
        assertFalse(items[2].canExtract)
        assertFalse(items[3].canExtract)
        assertTrue(items[4].canExtract)
    }

    /** 无法解析 host 的输入回退为截断后的原始文本，避免摘要生成抛异常。 */
    @Test
    fun summarizeDetailLinkFallsBackToShortRawText() {
        /** 非 URL 文本用于验证兜底摘要路径。 */
        val text = "not-a-valid-url-value-with-a-very-long-tail-for-detail-summary"

        assertEquals(
            "not-a-valid-url-value-with-a-very-long-tail-f...",
            summarizeDetailLink(text)
        )
    }
}
