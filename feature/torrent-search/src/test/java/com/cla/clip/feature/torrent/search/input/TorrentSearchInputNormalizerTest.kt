package com.cla.clip.feature.torrent.search.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 系统入口与页面共用输入规范化器的边界测试。 */
class TorrentSearchInputNormalizerTest {
    /** 合法输入应删除控制字符、裁剪边界空白，并允许自动搜索。 */
    @Test
    fun normalizeSanitizesValidInputAndAllowsAutoSearch() {
        /** result 使用包含换行与 NUL 的合成输入验证控制字符不会进入搜索链路。 */
        val result = TorrentSearchInputNormalizer.normalize("  alpha\n\u0000beta  ")

        assertEquals("alphabeta", result.query)
        assertTrue(result.canAutoSearch)
        assertFalse(result.wasTruncated)
    }

    /** 超过 512 字符的输入只能预填截断文本，不得自动搜索或写入历史。 */
    @Test
    fun normalizeTruncatesOverLimitInputAndBlocksAutoSearch() {
        /** rawQuery 比允许的最大长度多一个字符，用于锁定边界而不包含真实搜索内容。 */
        val rawQuery = "a".repeat(513)
        /** result 必须保留前 512 个安全字符并携带超限状态。 */
        val result = TorrentSearchInputNormalizer.normalize(rawQuery)

        assertEquals(512, result.query.length)
        assertEquals("a".repeat(512), result.query)
        assertFalse(result.canAutoSearch)
        assertTrue(result.wasTruncated)
    }

    /** 空白或仅控制字符的输入不能触发自动搜索。 */
    @Test
    fun normalizeBlocksBlankInputFromAutoSearch() {
        /** result 覆盖清洗后为空的输入分支。 */
        val result = TorrentSearchInputNormalizer.normalize(" \t\n ")

        assertEquals("", result.query)
        assertFalse(result.canAutoSearch)
        assertFalse(result.wasTruncated)
    }
}
