package com.cla.clip.feature.magnet

import java.util.Locale

/** 磁力搜索历史、最近来源搜索词和 LIKE 查询共用的轻量文本规范化能力。 */
object MagnetTextNormalizer {
    /** 单条用户可见搜索词长度上限，避免误粘贴大段内容进入历史和备份。 */
    const val MAX_QUERY_LENGTH = 200

    /** 转成单行展示文本并按上限截断。 */
    fun normalizeDisplayQuery(query: String): String {
        return query
            .lineSequence()
            .joinToString(separator = " ")
            .trim()
            .take(MAX_QUERY_LENGTH)
    }

    /** 用于去重和匹配的规范化关键词。 */
    fun normalizeKey(query: String): String {
        return normalizeDisplayQuery(query).lowercase(Locale.ROOT)
    }

    /** LIKE 查询转义，确保 `%`、`_` 和反斜杠都按普通字符匹配。 */
    fun escapeForLike(value: String): String {
        return buildString(value.length) {
            value.forEach { char ->
                if (char == '\\' || char == '%' || char == '_') append('\\')
                append(char)
            }
        }
    }
}
