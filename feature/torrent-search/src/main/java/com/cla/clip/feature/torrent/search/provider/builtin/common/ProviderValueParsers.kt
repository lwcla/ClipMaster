package com.cla.clip.feature.torrent.search.provider.builtin.common

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Provider 日期纯解析器；所有输出为 UTC epochMillis，且不引入 minSdk 24 全局 desugaring。 */
internal object ProviderDateParser {
    /** 所有无时区站点字段使用 UTC，避免设备时区导致测试和排序漂移。 */
    private val utcTimeZone: TimeZone = TimeZone.getTimeZone("UTC")
    /** ISO-8601 只接受秒后 1..9 位可选小数及 Z/±HH:mm，避免宽松日期解析吞掉污染文本。 */
    private val isoPattern = Regex(
        "^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d{1,9}))?(Z|[+-]\\d{2}:\\d{2})$",
    )
    /** 固定上源相对日期格式；数值只接受非负整数或小数并向下取整。 */
    private val relativePattern = Regex(
        """\b(\d+(?:\.\d+)?)\s*(s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days|w|wk|wks|week|weeks|mo|mos|month|months|y|yr|yrs|year|years)\b\s*(ago)?\b""",
        RegexOption.IGNORE_CASE,
    )

    /** 严格解析 ISO-8601 时间；超过毫秒的精度向下截断，无法完整消费输入时返回 null。 */
    fun parseIsoEpochMillis(rawDate: String): Long? {
        /** 去除协议值两端空白，不允许时间主体内部出现空白。 */
        val normalizedInput = rawDate.trim()
        /** 匹配结果分别固定秒级主体、小数秒和显式时区，任何缺失边界都返回 null。 */
        val match = isoPattern.matchEntire(normalizedInput) ?: return null
        /** 秒级时间主体不含小数和时区，用于组装三位毫秒格式。 */
        val secondsPart = match.groupValues[1]
        /** 小数不足三位右补零，超过三位截断，从而稳定落到 epochMillis。 */
        val millisPart = match.groupValues[2].padEnd(3, '0').take(3)
        /** 时区保留 Z 或 ±HH:mm，由 `XXX` 严格换算到 UTC。 */
        val zonePart = match.groupValues[3]
        /** SimpleDateFormat 仅接收统一三位毫秒输入，兼容 minSdk 24 且不依赖 java.time desugaring。 */
        val normalizedIsoDate = "$secondsPart.$millisPart$zonePart"
        return parseUtcEpochMillis(normalizedIsoDate, "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    }

    /** 使用显式格式严格解析 UTC 时间；返回 null 而不抛出带原文的异常。 */
    fun parseUtcEpochMillis(rawDate: String, pattern: String): Long? {
        /** 每次调用创建独立 formatter，避免 SimpleDateFormat 的线程不安全状态泄漏。 */
        val formatter = runCatching { SimpleDateFormat(pattern, Locale.US) }.getOrNull() ?: return null
        formatter.isLenient = false
        formatter.timeZone = utcTimeZone
        /** ParsePosition 用于确认整个字符串都被消费，禁止尾部污染被静默忽略。 */
        val parsePosition = ParsePosition(0)
        /** 解析出的 UTC Date，空值或未完整消费均视为失败。 */
        val parsedDate = formatter.parse(rawDate.trim(), parsePosition) ?: return null
        return parsedDate.time.takeIf { parsePosition.index == rawDate.trim().length }
    }

    /** 严格解析 RFC1123 日期时间并尊重输入中的显式时区；失败返回 null。 */
    fun parseRfc1123EpochMillis(rawDate: String): Long? =
        parseUtcEpochMillis(rawDate, "EEE, d MMM yyyy HH:mm:ss zzz")

    /** 按固定上源的秒/分/时/日/周/30 日月/365 日年语义解析相对时间。 */
    fun parseRelativeEpochMillis(rawDate: String, currentEpochMillis: Long): Long? {
        /** 去除旧资源使用的尾部加号，但不放宽中间文本。 */
        val normalizedDate = rawDate.removeSuffix("+").trim()
        /** today/即时表达保持注入时刻，yesterday 保持上源 UTC 前一日零点语义。 */
        val specialEpochMillis = when (normalizedDate.lowercase(Locale.US)) {
            "today", "just now", "moments ago" -> currentEpochMillis
            "yesterday" -> utcDayStart(currentEpochMillis) - MILLIS_PER_DAY
            "last week" -> currentEpochMillis - 7L * MILLIS_PER_DAY
            "last month" -> currentEpochMillis - 30L * MILLIS_PER_DAY
            "last year" -> currentEpochMillis - 365L * MILLIS_PER_DAY
            else -> null
        }
        if (specialEpochMillis != null) return specialEpochMillis
        /** 匹配结果只读取首个完整相对时间片段，与固定上源行为一致。 */
        val match = relativePattern.find(normalizedDate) ?: return null
        /** 小数数值沿用上源 `toLong` 语义向下取整。 */
        val value = match.groupValues[1].toDoubleOrNull()?.toLong() ?: return null
        /** 单位统一小写后映射为固定毫秒倍数。 */
        val unit = match.groupValues[2].lowercase(Locale.US)
        /** durationMillis 使用 Math.multiplyExact 拒绝恶意超大数值溢出。 */
        val durationMillis = runCatching {
            Math.multiplyExact(
                value,
                when (unit) {
                    "s", "sec", "secs", "second", "seconds" -> MILLIS_PER_SECOND
                    "m", "min", "mins", "minute", "minutes" -> 60L * MILLIS_PER_SECOND
                    "h", "hr", "hrs", "hour", "hours" -> 60L * 60L * MILLIS_PER_SECOND
                    "d", "day", "days" -> MILLIS_PER_DAY
                    "w", "wk", "wks", "week", "weeks" -> 7L * MILLIS_PER_DAY
                    "mo", "mos", "month", "months" -> 30L * MILLIS_PER_DAY
                    "y", "yr", "yrs", "year", "years" -> 365L * MILLIS_PER_DAY
                    else -> return null
                },
            )
        }.getOrNull() ?: return null
        return runCatching { Math.subtractExact(currentEpochMillis, durationMillis) }.getOrNull()
    }

    /** 解析 AnimeTosho 的 Today/Yesterday/d/M/yyyy，相对日期使用注入基准时间。 */
    fun parseAnimeToshoEpochMillis(rawDate: String, currentEpochMillis: Long): Long? {
        /** 去除日期属性两端空白，保留其他文本供严格分支判定。 */
        val normalizedDate = rawDate.trim()
        return when {
            normalizedDate.startsWith("Today", ignoreCase = true) -> utcDayStart(currentEpochMillis)
            normalizedDate.startsWith("Yesterday", ignoreCase = true) -> utcDayStart(currentEpochMillis) - MILLIS_PER_DAY
            else -> {
                /** 固定日期只取第一个空白前的 d/M/yyyy，与固定上源语义一致。 */
                val datePart = normalizedDate.substringBefore(' ')
                parseUtcEpochMillis(datePart, "d/M/yyyy")
            }
        }
    }

    /** 将秒级 Unix 时间安全转换为毫秒；溢出时返回 null。 */
    fun epochSecondsToMillis(epochSeconds: Long): Long? = runCatching {
        Math.multiplyExact(epochSeconds, MILLIS_PER_SECOND)
    }.getOrNull()

    /** 计算给定时间所在 UTC 日的 00:00:00.000，不读取设备默认时区。 */
    private fun utcDayStart(epochMillis: Long): Long {
        /** UTC Calendar 仅在当前调用内使用，避免跨线程共享可变状态。 */
        val calendar = Calendar.getInstance(utcTimeZone, Locale.US)
        calendar.time = Date(epochMillis)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /** 日期天边界的固定毫秒数。 */
    private const val MILLIS_PER_DAY = 86_400_000L
    /** Unix 秒转毫秒的固定倍数。 */
    private const val MILLIS_PER_SECOND = 1_000L
}

/** 将 AniLibria 字节大小格式化为与固定上源一致的两位小数 IEC 阈值文本。 */
internal object TorrentFileSizeFormatter {
    /** 按 1024 进位的展示单位，字符串保留上源 KB/MB 命名。 */
    private val units = listOf("B", "KB", "MB", "GB", "TB", "PB")

    /** 格式化非负字节数；负值按缺失数据返回 null。 */
    fun formatBytes(bytes: Long): String? {
        if (bytes < 0L) return null
        /** 当前浮点数值，每跨过一个单位除以 1024。 */
        var value = if (bytes < 1024L) 0.0 else bytes.toDouble()
        /** 当前单位下标，最多到 PB 以与上源能力一致。 */
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        return String.format(Locale.US, "%.2f %s", value, units[unitIndex])
    }
}
