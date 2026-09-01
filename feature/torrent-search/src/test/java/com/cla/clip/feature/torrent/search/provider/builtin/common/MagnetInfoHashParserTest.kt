package com.cla.clip.feature.torrent.search.provider.builtin.common

import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** 公共 btih 解析的稳定输出与低敏失败契约测试。 */
class MagnetInfoHashParserTest {
    /** 40 位 hex 必须大小写无关并归一为小写。 */
    @Test
    fun parseNormalizesFortyCharacterHexHash() {
        /** 含大写 btih 的合成 magnet，不包含真实资源标识。 */
        val magnet = "magnet:?dn=fixture&xt=urn:btih:89ABCDEF0123456789ABCDEF0123456789ABCDEF"

        assertEquals("89abcdef0123456789abcdef0123456789abcdef", MagnetInfoHashParser.parse(magnet))
    }

    /** 32 位 base32 btih 必须解码为同一 20 字节的小写 hex 稳定键。 */
    @Test
    fun parseConvertsThirtyTwoCharacterBase32HashToHex() {
        /** 20 个零字节的合成 base32，便于独立手算预期值。 */
        val magnet = "MAGNET:?xt=urn:btih:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

        assertEquals("0000000000000000000000000000000000000000", MagnetInfoHashParser.parse(magnet))
        assertEquals(
            "0000000000000000000000000000000000000000",
            MagnetInfoHashParser.parse("magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
        )
    }

    /** 无效长度、字符或非 magnet scheme 按可空契约返回 null。 */
    @Test
    fun parseReturnsNullForInvalidValues() {
        assertNull(MagnetInfoHashParser.parse("magnet:?xt=urn:btih:1234"))
        assertNull(MagnetInfoHashParser.parse("magnet:?xt=urn:btih:11111111111111111111111111111111"))
        assertNull(MagnetInfoHashParser.parse("https://fixture.invalid/value"))
    }

    /** 必需解析失败时异常不得携带原始 magnet 或用户可控文本。 */
    @Test
    fun requireThrowsLowSensitivityProviderParseException() {
        /** 特意放入的敏感标记，用于证明失败对象不回显原文。 */
        val sensitiveMarker = "SENSITIVE_FIXTURE_MARKER"
        /** 必需契约的稳定解析异常。 */
        val failure = assertThrows(ProviderParseException::class.java) {
            MagnetInfoHashParser.require("magnet:?xt=urn:btih:$sensitiveMarker")
        }

        assertFalse(failure.toString().contains(sensitiveMarker))
    }
}
