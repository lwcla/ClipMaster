package com.cla.clip.master.ui.widget.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 保护搜索结果与历史共用的原文索引和无背景样式，避免大小写转换造成位置偏移。 */
class SearchTextHighlightTest {
    /** 土耳其大写 I 转小写会扩长，匹配必须仍使用原文索引。 */
    @Test fun unicodeCaseExpansionDoesNotMoveFollowingMatch() {
        assertEquals(listOf(1..1), findSearchMatchRanges("İx", "x", SearchMatchMode.Words))
    }

    /** 重叠关键词应完整覆盖命中字符，不丢掉后出现的较长词。 */
    @Test fun overlappingWordsMergeWithoutLosingCharacters() {
        assertEquals(listOf(0..3), findSearchMatchRanges("aaaa", "aa aaa", SearchMatchMode.Words))
    }

    /** 结果拆词并忽略大小写，重复词不能产生重复区间。 */
    @Test fun resultsMatchWordsAndDeduplicate() {
        assertEquals(listOf(0..2, 4..6), findSearchMatchRanges("ABC abc", "abc ABC", SearchMatchMode.Words))
    }

    /** 历史保留完整输入语义，不能把不连续短语误标为命中。 */
    @Test fun historyMatchesTheWholePhrase() {
        assertTrue(findSearchMatchRanges("alpha x beta", "alpha beta", SearchMatchMode.Phrase).isEmpty())
        assertEquals(listOf(0..9), findSearchMatchRanges("Alpha Beta", "alpha beta", SearchMatchMode.Phrase))
    }

    /** 空输入不生成 Span；中文与标点按原文字面匹配。 */
    @Test fun emptyAndLiteralContentRemainSafe() {
        assertTrue(findSearchMatchRanges("正文", "  ", SearchMatchMode.Words).isEmpty())
        assertEquals(listOf(2..4), findSearchMatchRanges("甲乙来源%", "来源%", SearchMatchMode.Words))
    }

    /** Emoji 占两个 UTF-16 单元，区间必须可直接交给 AnnotatedString。 */
    @Test fun emojiDoesNotShiftTextOffsets() {
        assertEquals(listOf(2..3), findSearchMatchRanges("😀会议", "会议", SearchMatchMode.Phrase))
    }

    /** 用户要求仅命中字串红色加粗且无背景，不能继续沿用旧背景样式。 */
    @Test fun annotatedMatchIsBoldRedWithNoBackground() {
        /** 合成主题红色，不依赖 Android 主题运行时。 */
        val red = Color(0xFFB64545)
        /** 共用构建结果同时供正文与历史使用。 */
        val text = buildSearchHighlightedText("a match z", "match", SearchMatchMode.Phrase, red)
        assertEquals("a match z", text.text)
        assertEquals(1, text.spanStyles.size)
        assertEquals(2, text.spanStyles.single().start)
        assertEquals(7, text.spanStyles.single().end)
        assertEquals(red, text.spanStyles.single().item.color)
        assertEquals(FontWeight.Bold, text.spanStyles.single().item.fontWeight)
        assertEquals(Color.Unspecified, text.spanStyles.single().item.background)
    }
}
