package com.cla.clip.base.general.magnet.cache

/**
 * 磁力源搜索查询格式化器。
 *
 * FTS 查询只保留字母和数字，特殊符号输入由 Repository 降级到 LIKE；这样能避免用户输入 URL、括号或标点时触发 MATCH 语法错误。
 */
object MagnetSourceSearchQueryFormatter {
    private val SearchableCharRegex = Regex("[\\p{L}\\p{N}]")

    /** 构造逐字符前缀 FTS 查询；无法构造时返回空字符串。 */
    fun buildFtsQuery(query: String): String {
        return query
            .trim()
            .mapNotNull { char ->
                char.takeIf { SearchableCharRegex.matches(it.toString()) }?.let { "$it*" }
            }
            .joinToString(separator = " ")
    }
}
