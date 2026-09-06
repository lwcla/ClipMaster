package com.cla.clip.feature.torrent.search.provider.builtin.megapeer

import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser

/** MegaPeer 俄文短月与固定 2000..2099 年纯解析器。 */
internal object MegaPeerRussianDateParser {
    /** 固定短月映射，Мая 归一到五月。 */
    private val months = mapOf(
        "Янв" to "Jan", "Фев" to "Feb", "Мар" to "Mar", "Апр" to "Apr",
        "Май" to "May", "Мая" to "May", "Июн" to "Jun", "Июл" to "Jul",
        "Авг" to "Aug", "Сен" to "Sep", "Окт" to "Oct", "Ноя" to "Nov",
        "Дек" to "Dec",
    )

    /** 严格拆分三段并显式扩展世纪，失败返回 null。 */
    fun parseEpochMillis(
        /** 俄文短日期。 */
        rawDate: String,
    ): Long? {
        /** parts 必须恰好包含日、月和两位年。 */
        val parts = rawDate.trim().split(Regex("\\s+")).takeIf { fields ->
            fields.size == FIELD_COUNT
        } ?: return null
        /** month 是固定英语短月。 */
        val month = months[parts[1]] ?: return null
        /** shortYear 必须恰好为两位数字。 */
        val shortYear = parts[2]
            /** year 是待确认的两位数字字段。 */
            .takeIf { year -> year.length == SHORT_YEAR_LENGTH && year.all(Char::isDigit) }
            ?.toIntOrNull()
            ?: return null
        /** fullYear 固定落在 2000..2099，不依赖运行时滑动窗口。 */
        val fullYear = 2000 + shortYear
        return ProviderDateParser.parseUtcEpochMillis(
            rawDate = "${parts[0]} $month $fullYear",
            pattern = "d MMM yyyy",
        )
    }

    /** 固定字段数量。 */
    private const val FIELD_COUNT = 3

    /** 两位年份长度。 */
    private const val SHORT_YEAR_LENGTH = 2
}
