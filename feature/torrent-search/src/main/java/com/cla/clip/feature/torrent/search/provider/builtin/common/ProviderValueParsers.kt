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
    /** ISO-8601 站点时间可能带毫秒或不带毫秒，按严格顺序尝试。 */
    private val isoPatterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
    )

    /** 严格解析 ISO-8601 时间；无法完整消费输入时返回 null。 */
    fun parseIsoEpochMillis(rawDate: String): Long? = isoPatterns.firstNotNullOfOrNull { pattern ->
        parseUtcEpochMillis(rawDate, pattern)
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
