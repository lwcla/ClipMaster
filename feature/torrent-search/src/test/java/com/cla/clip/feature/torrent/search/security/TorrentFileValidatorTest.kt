package com.cla.clip.feature.torrent.search.security

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Torrent 文件的 Bencode 结构、原始 infoHash 与 20 MiB 安全边界测试。 */
class TorrentFileValidatorTest {
    /** 合法顶层与 info 字典应按 info 原始字节计算固定 SHA-1。 */
    @Test
    fun validateHashesExactInfoDictionaryBytes() {
        /** torrentBytes 使用人工可核对的最小规范 Bencode，info 字典不经重编码。 */
        val torrentBytes = "d8:announce15:https://fixture4:infod6:lengthi1e4:name7:fixtureee".toByteArray()

        /** result 是公开校验结果，固定 hash 由独立 SHA-1 工具预先核对。 */
        val result = TorrentFileValidator.validate(ByteArrayInputStream(torrentBytes))

        assertEquals(
            TorrentValidationResult.Valid(
                infoHash = "c7823cff30b9ced462ad8e86e67893e6f4c5388e",
                sizeBytes = torrentBytes.size,
            ),
            result,
        )
    }

    /** 空输入、非字典顶层、缺失 info 与非字典 info 必须给出稳定失败分类。 */
    @Test
    fun validateClassifiesRequiredTorrentStructureFailures() {
        /** cases 逐项固定输入与业务可观察 reasonCode，不依赖解析器内部异常。 */
        val cases = listOf(
            byteArrayOf() to TorrentValidationFailureReason.EMPTY,
            "li1ee".toByteArray() to TorrentValidationFailureReason.TOP_LEVEL_NOT_DICTIONARY,
            "d4:name7:fixturee".toByteArray() to TorrentValidationFailureReason.INFO_MISSING,
            "d4:info4:nopee".toByteArray() to TorrentValidationFailureReason.INFO_NOT_DICTIONARY,
        )

        /** case 表示当前合成字节与其预期安全失败分类。 */
        cases.forEach { case ->
            assertEquals(
                TorrentValidationResult.Invalid(case.second),
                TorrentFileValidator.validate(ByteArrayInputStream(case.first)),
            )
        }
    }

    /** 非规范整数、未排序/重复字典键、尾随数据与截断值必须统一拒绝。 */
    @Test
    fun validateRejectsMalformedOrAmbiguousBencode() {
        /** malformedInputs 覆盖会产生解析歧义或错误身份的最小 Bencode。 */
        val malformedInputs = listOf(
            "d4:infod1:ai01eee",
            "d4:infod1:bi1e1:ai2eee",
            "d4:infod1:ai1e1:ai2eee",
            "d4:infodejunk",
            "d4:infod4:name7:shortee",
        )

        /** encoded 是当前应被拒绝的中性合成 Bencode。 */
        malformedInputs.forEach { encoded ->
            assertEquals(
                TorrentValidationResult.Invalid(TorrentValidationFailureReason.MALFORMED_BENCODE),
                TorrentFileValidator.validate(ByteArrayInputStream(encoded.toByteArray())),
            )
        }
    }

    /** 过深嵌套必须在固定深度停止，避免递归耗尽调用栈。 */
    @Test
    fun validateRejectsExcessiveNesting() {
        /** nestedInfo 构造超过 128 层的列表后仍保持语法闭合。 */
        val nestedInfo = "l".repeat(129) + "0:" + "e".repeat(129)
        /** torrentBytes 把过深值放入 info 字典，使顶层身份仍明确。 */
        val torrentBytes = "d4:infod1:a${nestedInfo}ee".toByteArray()

        assertEquals(
            TorrentValidationResult.Invalid(TorrentValidationFailureReason.NESTING_TOO_DEEP),
            TorrentFileValidator.validate(ByteArrayInputStream(torrentBytes)),
        )
    }

    /** 精确 20 MiB 可进入结构校验，超过一个字节必须在解析前分类为超限。 */
    @Test
    fun validateEnforcesTwentyMebibyteLimit() {
        /** exactLimitBytes 只验证大小边界，填充值不是合法 torrent。 */
        val exactLimitBytes = ByteArray(TorrentFileValidator.MAX_TORRENT_BYTES) { 'x'.code.toByte() }
        /** overLimitBytes 比协议上限多一个字节，读取器不得继续交给 Bencode。 */
        val overLimitBytes = exactLimitBytes + byteArrayOf('x'.code.toByte())

        assertEquals(
            TorrentValidationResult.Invalid(TorrentValidationFailureReason.TOP_LEVEL_NOT_DICTIONARY),
            TorrentFileValidator.validate(ByteArrayInputStream(exactLimitBytes)),
        )
        assertEquals(
            TorrentValidationResult.Invalid(TorrentValidationFailureReason.TOO_LARGE),
            TorrentFileValidator.validate(ByteArrayInputStream(overLimitBytes)),
        )
        assertTrue(overLimitBytes.size > TorrentFileValidator.MAX_TORRENT_BYTES)
    }
}
