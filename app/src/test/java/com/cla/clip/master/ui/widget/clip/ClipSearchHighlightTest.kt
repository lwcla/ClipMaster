package com.cla.clip.master.ui.widget.clip

import com.cla.clip.base.general.search.ClipSearchField
import androidx.compose.ui.graphics.Color
import com.cla.clip.master.ui.widget.text.SearchMatchMode
import com.cla.clip.master.ui.widget.text.buildSearchHighlightedText
import org.junit.Assert.assertEquals
import org.junit.Test

/** 搜索范围应同时约束数据与可见高亮，不能把同词出现在其他字段误标成命中原因。 */
class ClipSearchHighlightTest {
    /** 正文就是链接时独立链接行被去重，但 Link 的可见命中不能随之消失。 */
    @Test fun linkOnlyStillHighlightsTheUrlRenderedAsContent() {
        /** 合成链接仅包含公开测试域名，无用户 URL。 */
        val url = "https://example.test/needle"
        /** 只选择链接字段，不隐式选中正文。 */
        val highlight = ClipSearchHighlight("needle", setOf(ClipSearchField.Link))
        assertEquals("needle", highlight.queryForContent(url, url))
        assertEquals(null, highlight.queryForContent("note $url", url))
        assertEquals(null, highlight.queryForContent(url, null))
        assertEquals(null, ClipSearchHighlight("needle", setOf(ClipSearchField.LinkTitle)).queryForContent(url, url))
        /** 空输入即使映射到相同链接，也不能生成任何高亮 Span。 */
        val emptyQuery = ClipSearchHighlight("", setOf(ClipSearchField.Link)).queryForContent(url, url)
        assertEquals(emptyList<Any>(), buildSearchHighlightedText(url, emptyQuery, SearchMatchMode.Phrase, Color.Red).spanStyles)
    }

    /** 每个可见字段只响应相应选择，不借用隐藏元数据的碰巧同词。 */
    @Test fun targetOnlyHighlightsItsOwnField() {
        /** 手工定义业务映射，不复用生产规则生成预期值。 */
        val expected = mapOf(ClipSearchField.Content to ClipHighlightField.Content, ClipSearchField.Link to ClipHighlightField.Link,
            ClipSearchField.LinkTitle to ClipHighlightField.Title, ClipSearchField.SourceName to ClipHighlightField.SourceName)
        expected.forEach { (target, allowed) ->
            // 对每种字段检查命中与排除，错误放宽会使此断言失败。
            ClipHighlightField.entries.forEach { field ->
                assertEquals(if (field == allowed) "会议" else null, ClipSearchHighlight("会议", setOf(target)).queryFor(field))
            }
        }
    }

    /** 综合模式保持原有所有可见字段。 */
    @Test fun allFieldsKeepsEveryVisibleMatch() {
        ClipHighlightField.entries.forEach { field ->
            assertEquals("会议", ClipSearchHighlight("会议").queryFor(field))
        }
    }

    /** 穷举七字段全部非空集合，任何选择顺序都不得扩大高亮字段。 */
    @Test fun everyCombinationHighlightsOnlySelectedVisibleFields() {
        /** 显式定义业务映射，不能从生产映射派生测试预期。 */
        val visible = mapOf(ClipHighlightField.Content to ClipSearchField.Content, ClipHighlightField.Link to ClipSearchField.Link,
            ClipHighlightField.Title to ClipSearchField.LinkTitle, ClipHighlightField.SourceName to ClipSearchField.SourceName)
        for (mask in 1..127) {
            /** 位掩码只用于枚举测试组合，不作为产品协议。 */
            val selected = ClipSearchField.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            visible.forEach { (display, field) ->
                assertEquals(if (field in selected) "ab cd" else null, ClipSearchHighlight("ab cd", selected).queryFor(display))
            }
        }
    }
}
