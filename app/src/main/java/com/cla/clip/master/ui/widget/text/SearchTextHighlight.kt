package com.cla.clip.master.ui.widget.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

/** 结果按词匹配、历史按完整短语匹配，仅用于 UI，不参与外部协议。 */
internal enum class SearchMatchMode {
    /** 搜索结果沿用按空白拆词。 */
    Words,
    /** 搜索历史沿用完整输入。 */
    Phrase,
}

/** 在原文 UTF-16 坐标上匹配并合并重叠区间；不先 lowercase，以免 Unicode 扩长造成 Span 偏移。 */
internal fun findSearchMatchRanges(
    /** 原始可见文本。 */ text: String,
    /** 用户当前搜索输入。 */ query: String?,
    /** 结果或历史的匹配边界。 */ mode: SearchMatchMode,
): List<IntRange> {
    /** 只裁掉输入外层空白，历史完整短语保留内部空白。 */
    val trimmed = query?.trim().orEmpty()
    if (text.isEmpty() || trimmed.isEmpty()) return emptyList()
    /** 结果拆词，历史完整匹配；去重只减少重复扫描，不决定原文索引。 */
    val words = if (mode == SearchMatchMode.Words) trimmed.split(Regex("\\s+")).distinct() else listOf(trimmed)
    /** 先收集原文字串上的闭区间，再统一合并，避免短词覆盖后丢掉长词尾部。 */
    val matches = mutableListOf<IntRange>()
    words.forEach { word ->
        // 每次前进一个 UTF-16 单元，保证连续重叠词也能覆盖完整命中区域。
        for (offset in 0..(text.length - word.length)) {
            if (text.regionMatches(offset, word, 0, word.length, ignoreCase = true)) {
                matches += offset until offset + word.length
            }
        }
    }
    /** 排序后的区间按可见覆盖范围归并，不重复叠加同一字符的样式。 */
    val merged = mutableListOf<IntRange>()
    matches.sortedBy { it.first }.forEach { range ->
        /** 已输出的最后区间，null 表示本次第一次命中。 */
        val previous = merged.lastOrNull()
        if (previous != null && range.first <= previous.last + 1) {
            merged[merged.lastIndex] = previous.first..maxOf(previous.last, range.last)
        } else {
            merged += range
        }
    }
    return merged
}

/** 保留原文及所有非命中文字样式；结果和历史统一使用主题红色、Bold，无背景属性。 */
internal fun buildSearchHighlightedText(
    /** 原始展示文字。 */ text: String,
    /** 可空关键词表示普通无搜索显示。 */ query: String?,
    /** 明确拆词或整段匹配语义。 */ mode: SearchMatchMode,
    /** 由主题提供的命中红色。 */ color: Color,
): AnnotatedString = buildAnnotatedString {
    append(text)
    // Span 只覆盖颜色与字重，不设置背景，也不改变字号或点击语义。
    findSearchMatchRanges(text, query, mode).forEach { range ->
        addStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold), range.first, range.last + 1)
    }
}
