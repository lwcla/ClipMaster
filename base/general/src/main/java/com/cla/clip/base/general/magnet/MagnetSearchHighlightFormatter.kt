package com.cla.clip.base.general.magnet

import java.util.Locale

/** 磁力搜索结果高亮片段格式化器，只处理纯文本范围，不依赖 Compose 或 Android UI。 */
object MagnetSearchHighlightFormatter {
    private const val DEFAULT_SNIPPET_MAX_CHARS = 160
    private const val DEFAULT_SNIPPET_RADIUS = 56

    /** 按搜索词生成描述片段和高亮范围；未命中时返回文本开头的稳定片段。 */
    fun buildSnippet(
        text: String?,
        query: String,
        maxChars: Int = DEFAULT_SNIPPET_MAX_CHARS,
    ): MagnetHighlightSnippet? {
        val source = text?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val tokens = queryTokens(query)
        if (source.length <= maxChars) {
            return MagnetHighlightSnippet(
                text = source,
                ranges = findRanges(source, tokens),
                prefixEllipsis = false,
                suffixEllipsis = false
            )
        }

        val normalizedSource = source.lowercase(Locale.ROOT)
        val firstMatch = tokens
            .mapNotNull { token -> normalizedSource.indexOf(token).takeIf { it >= 0 } }
            .minOrNull()
        val start = if (firstMatch == null) {
            0
        } else {
            (firstMatch - DEFAULT_SNIPPET_RADIUS).coerceAtLeast(0)
        }
        val end = if (firstMatch == null) {
            maxChars.coerceAtMost(source.length)
        } else {
            (firstMatch + DEFAULT_SNIPPET_RADIUS * 2).coerceAtMost(source.length)
        }
        val snippet = source.substring(start, end)
        return MagnetHighlightSnippet(
            text = snippet,
            ranges = findRanges(snippet, tokens),
            prefixEllipsis = start > 0,
            suffixEllipsis = end < source.length
        )
    }

    /** 返回标题或短文本中的全部高亮范围。 */
    fun findRanges(text: String, query: String): List<IntRange> {
        return findRanges(text, queryTokens(query))
    }

    /** 将用户搜索词拆为稳定高亮 token；空白和重复 token 会被忽略。 */
    fun queryTokens(query: String): List<String> {
        return MagnetTextNormalizer.normalizeDisplayQuery(query)
            .lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun findRanges(text: String, tokens: List<String>): List<IntRange> {
        if (text.isBlank() || tokens.isEmpty()) return emptyList()
        val normalizedText = text.lowercase(Locale.ROOT)
        val ranges = mutableListOf<IntRange>()
        tokens.forEach { token ->
            var startIndex = normalizedText.indexOf(token)
            while (startIndex >= 0) {
                val range = startIndex until (startIndex + token.length)
                if (range.none { index -> ranges.any { index in it } }) {
                    ranges += range
                }
                startIndex = normalizedText.indexOf(token, startIndex + token.length)
            }
        }
        return ranges.sortedBy { it.first }
    }
}

/** UI 可直接消费的高亮片段，ranges 使用闭区间以便和 Kotlin 字符索引语义保持一致。 */
data class MagnetHighlightSnippet(
    val text: String,
    val ranges: List<IntRange>,
    val prefixEllipsis: Boolean,
    val suffixEllipsis: Boolean,
)
