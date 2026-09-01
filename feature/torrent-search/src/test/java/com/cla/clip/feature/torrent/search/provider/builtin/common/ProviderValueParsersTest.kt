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

    /** 固定上源对不足 1 KiB 的输入显示 0.00 B，该行为需防止移植漂移。 */
    @Test
    fun fileSizeFormatterPreservesSubKilobyteUpstreamFallback() {
        assertEquals("0.00 B", TorrentFileSizeFormatter.formatBytes(512L))
        assertEquals("1.00 MB", TorrentFileSizeFormatter.formatBytes(1_048_576L))
    }
}
