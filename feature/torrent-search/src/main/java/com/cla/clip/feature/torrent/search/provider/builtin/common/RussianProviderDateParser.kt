package com.cla.clip.feature.torrent.search.provider.builtin.common

/** MegaPeer、Rutor 与 XXXTracker 共享的俄文短日期纯解析器。 */
internal object RussianProviderDateParser {
    /** 固定短月映射，`Мая` 作为五月属格兼容值。 */
    private val months = mapOf(
        "Янв" to "Jan",
        "Фев" to "Feb",
        "Мар" to "Mar",
        "Апр" to "Apr",
        "Май" to "May",
        "Мая" to "May",
        "Июн" to "Jun",
        "Июл" to "Jul",
        "Авг" to "Aug",
        "Сен" to "Sep",
        "Окт" to "Oct",
        "Ноя" to "Nov",
        "Дек" to "Dec",
    )

    /** 严格接收日、俄文短月和两位年，失败返回 null。 */
    fun parseShortYearEpochMillis(
        /** 待解析的俄文短日期。 */
        rawDate: String,
    ): Long? {
        /** fields 必须恰好包含日、月和两位年。 */
        val fields = rawDate.trim().split(WHITESPACE_PATTERN)
            /** parts 必须保持严格三字段。 */
            .takeIf { parts -> parts.size == FIELD_COUNT }
            ?: return null
        /** englishMonth 是固定俄文 token 对应的英语短月。 */
        val englishMonth = months[fields[1]] ?: return null
        /** shortYear 必须恰好为两位 ASCII 数字。 */
        val shortYear = fields[2]
            /** year 是待校验的两位年字段。 */
            .takeIf { year ->
                /** hasExpectedLength 防止单字符和四位年进入共享协议。 */
                val hasExpectedLength = year.length == SHORT_YEAR_LENGTH
                /** hasOnlyAsciiDigits 禁止 Unicode 数字产生协议歧义。 */
                val hasOnlyAsciiDigits = year.all { digit -> digit in '0'..'9' }
                hasExpectedLength && hasOnlyAsciiDigits
            }
            ?.toIntOrNull()
            ?: return null
        /** fullYear 固定扩展到 2000..2099，避免运行时滑动窗口。 */
        val fullYear = 2000 + shortYear
        return ProviderDateParser.parseUtcEpochMillis(
            rawDate = "${fields[0]} $englishMonth $fullYear",
            pattern = "d MMM yyyy",
        )
    }

    /** 严格字段数量。 */
    private const val FIELD_COUNT = 3

    /** 两位年份长度。 */
    private const val SHORT_YEAR_LENGTH = 2

    /** 日期字段间允许一个或多个空白。 */
    private val WHITESPACE_PATTERN = Regex("\\s+")
}
