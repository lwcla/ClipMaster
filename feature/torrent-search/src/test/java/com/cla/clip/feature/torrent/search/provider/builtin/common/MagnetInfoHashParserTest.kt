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

    /** 非零 20 字节已知向量必须保持 RFC 4648 位序，不能只靠全零样本掩盖错位。 */
    @Test
    fun parseConvertsIndependentNonZeroBase32Vector() {
        /** 该 base32 由 Python 标准库对固定字节 `001122...3040` 独立计算，不复用生产 helper。 */
        val magnet = "magnet:?xt=urn:btih:AAISEM2EKVTHPCEZVK54ZXPO74ICAMCA"

        assertEquals("00112233445566778899aabbccddeeff10203040", MagnetInfoHashParser.parse(magnet))
    }

    /** 前一个 btih 非法时必须继续查找后续合法 xt，避免多 xt magnet 被错误拒绝。 */
    @Test
    fun parseSkipsInvalidBtihAndUsesLaterValidXt() {
        /** 第一个 xt 长度非法，第二个 xt 是独立固定的合法 40 位 hash。 */
        val magnet = "magnet:?xt=urn:btih:invalid&xt=urn:btih:00112233445566778899AABBCCDDEEFF10203040"

        assertEquals("00112233445566778899aabbccddeeff10203040", MagnetInfoHashParser.parse(magnet))
    }

    /** 创建 magnet 必须保留固定快照的 20 个公共 tracker、顺序和 URL 编码。 */
    @Test
    fun createIncludesSnapshotTrackersInExactEncodedOrder() {
        /** 完整期望由固定 `ba82e889` tracker 列表逐项独立 URL 编码后写死。 */
        val expected = "magnet:?xt=urn:btih:00112233445566778899aabbccddeeff10203040" +
            "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce" +
            "&tr=udp%3A%2F%2Fopen.demonoid.ch%3A6969%2Fannounce" +
            "&tr=udp%3A%2F%2Fopen.demonii.com%3A1337%2Fannounce" +
            "&tr=udp%3A%2F%2Fopen.stealth.si%3A80%2Fannounce" +
            "&tr=udp%3A%2F%2Fexodus.desync.com%3A6969%2Fannounce" +
            "&tr=udp%3A%2F%2Ftracker.torrent.eu.org%3A451%2Fannounce" +
            "&tr=udp%3A%2F%2Fexplodie.org%3A6969%2Fannounce" +
            "&tr=udp%3A%2F%2Ftracker2.dler.org%3A80%2Fannounce" +
            "&tr=udp%3A%2F%2Ftracker.qu.ax%3A6969%2Fannounce" +
            "&tr=udp%3A%2F%2Ftracker.filemail.com%3A6969%2Fannounce" +
            "&tr=udp%3A%2F%2Ftracker.dler.org%3A6969%2Fannounce" +
            "&tr=udp%3A%2F%2Ftracker.bittor.pw%3A1337%2Fannounce" +
            "&tr=udp%3A%2F%2Ftracker.0x7c0.com%3A6969%2Fannounce" +
            "&tr=udp%3A%2F%2Ftracker-udp.gbitt.info%3A80%2Fannounce" +
            "&tr=udp%3A%2F%2Frun.publictracker.xyz%3A6969%2Fannounce" +
            "&tr=udp%3A%2F%2Fretracker01-msk-virt.corbina.net%3A80%2Fannounce" +
            "&tr=udp%3A%2F%2Fp4p.arenabg.com%3A1337%2Fannounce" +
            "&tr=udp%3A%2F%2Fopentracker.io%3A6969%2Fannounce" +
            "&tr=udp%3A%2F%2Fopen.tracker.cl%3A1337%2Fannounce" +
            "&tr=udp%3A%2F%2Fleet-tracker.moe%3A1337%2Fannounce"

        assertEquals(expected, MagnetInfoHashParser.create("00112233445566778899AABBCCDDEEFF10203040"))
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
