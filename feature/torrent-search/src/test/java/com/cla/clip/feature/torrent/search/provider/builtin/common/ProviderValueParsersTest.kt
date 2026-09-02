package com.cla.clip.feature.torrent.search.provider.builtin.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 时间和大小公共解析器的单位、时区与固定上源回退契约测试。 */
class ProviderValueParsersTest {
    /** ISO-8601 带 Z 时间必须精确转为 UTC epochMillis。 */
    @Test
    fun isoParserReturnsUtcEpochMillis() {
        assertEquals(1709528767000L, ProviderDateParser.parseIsoEpochMillis("2024-03-04T05:06:07Z"))
    }

    /** ISO-8601 的 1/3/6/9 位小数秒都必须接受，并以毫秒精度截断多余位。 */
    @Test
    fun isoParserAcceptsOneToNineFractionDigitsAndTruncatesToMillis() {
        /** 输入与期望毫秒值由独立 DateTimeOffset 工具计算，避免复用生产解析逻辑。 */
        val cases = listOf(
            "2024-03-04T05:06:07.1Z" to 1709528767100L,
            "2024-03-04T05:06:07.123Z" to 1709528767123L,
            "2024-03-04T05:06:07.123456Z" to 1709528767123L,
            "2024-03-04T05:06:07.123456789Z" to 1709528767123L,
        )

        /** case 是当前独立固定向量，first 为原始时间，second 为毫秒期望。 */
        cases.forEach { case ->
            assertEquals(case.second, ProviderDateParser.parseIsoEpochMillis(case.first))
        }
    }

    /** ISO-8601 偏移量必须参与 UTC 换算，不能按设备时区或原地时钟解释。 */
    @Test
    fun isoParserAppliesHourMinuteOffset() {
        assertEquals(
            1709499967123L,
            ProviderDateParser.parseIsoEpochMillis("2024-03-04T05:06:07.123456+08:00"),
        )
    }

    /** 非法日期返回 null，不抛出含原文的解析异常。 */
    @Test
    fun dateParserReturnsNullForInvalidInput() {
        assertNull(ProviderDateParser.parseUtcEpochMillis("not-a-date", "yyyy-MM-dd"))
    }

    /** Unix epoch second 必须以 1000 转为 epochMillis，乘法溢出则返回 null。 */
    @Test
    fun epochSecondConversionUsesMillisAndRejectsOverflow() {
        assertEquals(1714998600000L, ProviderDateParser.epochSecondsToMillis(1714998600L))
        assertNull(ProviderDateParser.epochSecondsToMillis(Long.MAX_VALUE))
    }

    /** RFC1123 日期时间必须按显式 GMT 偏移转为 UTC 毫秒。 */
    @Test
    fun rfc1123ParserReturnsUtcEpochMillis() {
        assertEquals(
            1709528767000L,
            ProviderDateParser.parseRfc1123EpochMillis("Mon, 4 Mar 2024 05:06:07 GMT"),
        )
    }

    /** 相对日期使用注入基准时间，月和年继续保留固定上源的 30/365 天语义。 */
    @Test
    fun relativeParserUsesInjectedClockAndFixedUpstreamDurations() {
        /** 测试基准时间固定为 2024-03-04T05:06:07Z。 */
        val now = 1709528767000L

        assertEquals(now - 2L * 60L * 60L * 1000L, ProviderDateParser.parseRelativeEpochMillis("2 hours ago", now))
        assertEquals(now - 30L * 86_400_000L, ProviderDateParser.parseRelativeEpochMillis("1 month", now))
        assertEquals(now - 365L * 86_400_000L, ProviderDateParser.parseRelativeEpochMillis("1 Year+", now))
        assertNull(ProviderDateParser.parseRelativeEpochMillis("unknown", now))
    }

    /** 固定上源对不足 1 KiB 的输入显示 0.00 B，该行为需防止移植漂移。 */
    @Test
    fun fileSizeFormatterPreservesSubKilobyteUpstreamFallback() {
        assertEquals("0.00 B", TorrentFileSizeFormatter.formatBytes(512L))
        assertEquals("1.00 MB", TorrentFileSizeFormatter.formatBytes(1_048_576L))
    }
}
