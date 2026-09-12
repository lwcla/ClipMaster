package com.cla.clip.feature.torrent.search.security

import java.io.InputStream
import java.security.MessageDigest

/** Torrent 文件校验失败的稳定协议，日志和 UI 只能使用显式 code。 */
internal enum class TorrentValidationFailureReason(
    /** 对外稳定 reasonCode，不包含文件内容或底层异常。 */
    val code: String,
) {
    /** 输入流没有任何字节。 */
    EMPTY("empty"),
    /** 输入超过 20 MiB。 */
    TOO_LARGE("too_large"),
    /** 输入流发生低敏 IO 失败。 */
    READ_FAILURE("read_failure"),
    /** 顶层值不是 Bencode 字典。 */
    TOP_LEVEL_NOT_DICTIONARY("top_level_not_dictionary"),
    /** Bencode 语法、规范顺序或尾随数据损坏。 */
    MALFORMED_BENCODE("malformed_bencode"),
    /** 顶层缺少必需 info 键。 */
    INFO_MISSING("info_missing"),
    /** 顶层出现多个 info 键，身份存在歧义。 */
    INFO_DUPLICATE("info_duplicate"),
    /** info 值不是 Bencode 字典。 */
    INFO_NOT_DICTIONARY("info_not_dictionary"),
    /** 嵌套超过固定安全深度。 */
    NESTING_TOO_DEEP("nesting_too_deep"),
}

/** Torrent 校验公开结果；成功只返回稳定 infoHash 与低敏大小。 */
internal sealed interface TorrentValidationResult {
    /** 完整 torrent 结构合法。 */
    data class Valid(
        /** 对 info 原始字节计算的 40 位小写 SHA-1。 */
        val infoHash: String,
        /** 完整 torrent 文件字节数。 */
        val sizeBytes: Int,
    ) : TorrentValidationResult

    /** 文件不可安全使用。 */
    data class Invalid(
        /** 稳定失败分类，不附带原始内容。 */
        val reason: TorrentValidationFailureReason,
    ) : TorrentValidationResult
}

/** 校验完整 Torrent Bencode，并从原始 info 字节生成 BitTorrent v1 身份。 */
internal object TorrentFileValidator {
    /** Torrent 文件固定上限 20 MiB。 */
    const val MAX_TORRENT_BYTES = 20 * 1024 * 1024

    /** 从调用方拥有的流读取并校验；本方法不关闭流。 */
    fun validate(
        /** 不可信 torrent 输入流。 */
        input: InputStream,
    ): TorrentValidationResult {
        /** readResult 先执行真实读取上限，再允许 Bencode 解析。 */
        val readResult = BoundedInputReader.read(input, MAX_TORRENT_BYTES)
        /** torrentBytes 只在完整且未超限时存在。 */
        val torrentBytes = when (readResult) {
            is BoundedInputReadResult.Success -> readResult.bytes
            BoundedInputReadResult.TooLarge -> return TorrentValidationResult.Invalid(TorrentValidationFailureReason.TOO_LARGE)
            BoundedInputReadResult.ReadFailure -> return TorrentValidationResult.Invalid(TorrentValidationFailureReason.READ_FAILURE)
        }
        if (torrentBytes.isEmpty()) return TorrentValidationResult.Invalid(TorrentValidationFailureReason.EMPTY)
        if (torrentBytes.first() != BENCODE_DICTIONARY) {
            return TorrentValidationResult.Invalid(TorrentValidationFailureReason.TOP_LEVEL_NOT_DICTIONARY)
        }
        return try {
            /** infoRange 精确指向输入中的 info 字典编码区间。 */
            val infoRange = BencodeTorrentScanner(torrentBytes).scanTorrent()
            /** digest 仅用于 BitTorrent v1 兼容身份，不承担凭据或签名安全。 */
            val digest = MessageDigest.getInstance("SHA-1").apply {
                update(torrentBytes, infoRange.start, infoRange.endExclusive - infoRange.start)
            }.digest()
            TorrentValidationResult.Valid(
                infoHash = digest.toLowerHex(),
                sizeBytes = torrentBytes.size,
            )
        /** failure 只携带稳定枚举，不包含输入片段。 */
        } catch (failure: BencodeValidationException) {
            TorrentValidationResult.Invalid(failure.reason)
        }
    }

    /** Bencode 字典起始字节。 */
    private const val BENCODE_DICTIONARY = 'd'.code.toByte()
}

/** 原始 info 字典的半开字节区间。 */
private data class ByteRange(
    /** info 值首字节位置。 */
    val start: Int,
    /** info 值结束后一字节位置。 */
    val endExclusive: Int,
)

/** 不复制值的严格 Bencode 扫描器，只定位 torrent 顶层 info 原始字节。 */
private class BencodeTorrentScanner(
    /** 完整且已通过 20 MiB 上限的输入字节。 */
    private val bytes: ByteArray,
) {
    /** 当前待扫描字节下标。 */
    private var cursor = 0

    /** 扫描顶层字典并返回唯一 info 字典区间。 */
    fun scanTorrent(): ByteRange {
        consume(DICTIONARY_START)
        /** previousKey 用无符号字节序校验顶层键严格递增。 */
        var previousKey: ByteSlice? = null
        /** infoRange 在遇到唯一 info 字典后保存其原始区间。 */
        var infoRange: ByteRange? = null
        while (peek() != VALUE_END) {
            /** key 是当前顶层字典键的原始字节切片。 */
            val key = readByteString()
            if (key.contentEquals(INFO_KEY) && infoRange != null) fail(TorrentValidationFailureReason.INFO_DUPLICATE)
            if (previousKey != null && previousKey.compareUnsigned(key) >= 0) {
                fail(TorrentValidationFailureReason.MALFORMED_BENCODE)
            }
            previousKey = key
            /** valueStart 是当前值在原输入中的起始位置。 */
            val valueStart = cursor
            if (key.contentEquals(INFO_KEY)) {
                if (peek() != DICTIONARY_START) fail(TorrentValidationFailureReason.INFO_NOT_DICTIONARY)
                scanValue(depth = 2)
                infoRange = ByteRange(valueStart, cursor)
            } else {
                scanValue(depth = 2)
            }
        }
        consume(VALUE_END)
        if (cursor != bytes.size) fail(TorrentValidationFailureReason.MALFORMED_BENCODE)
        return infoRange ?: fail(TorrentValidationFailureReason.INFO_MISSING)
    }

    /** 扫描一个完整值，不构造对象树；depth 超过 128 立即停止。 */
    private fun scanValue(
        /** 当前值在完整 Bencode 树中的一基深度。 */
        depth: Int,
    ) {
        if (depth > MAX_NESTING_DEPTH) fail(TorrentValidationFailureReason.NESTING_TOO_DEEP)
        when (peek()) {
            INTEGER_START -> scanInteger()
            LIST_START -> scanList(depth)
            DICTIONARY_START -> scanDictionary(depth)
            in DIGIT_ZERO..DIGIT_NINE -> readByteString()
            else -> fail(TorrentValidationFailureReason.MALFORMED_BENCODE)
        }
    }

    /** 扫描规范整数，拒绝空值、前导零和负零。 */
    private fun scanInteger() {
        consume(INTEGER_START)
        /** negative 表示整数是否有负号。 */
        val negative = peek() == MINUS
        if (negative) cursor += 1
        /** digitStart 是首个数字位置，用于验证至少一个数字。 */
        val digitStart = cursor
        while (true) {
            /** currentByte 是待判断的整数内容字节，输入结束时退出数字扫描。 */
            val currentByte = peekOrNull() ?: break
            if (currentByte < DIGIT_ZERO || currentByte > DIGIT_NINE) break
            cursor += 1
        }
        if (cursor == digitStart) fail(TorrentValidationFailureReason.MALFORMED_BENCODE)
        /** digitCount 用于拒绝任何前导零。 */
        val digitCount = cursor - digitStart
        if (bytes[digitStart] == DIGIT_ZERO && (digitCount > 1 || negative)) {
            fail(TorrentValidationFailureReason.MALFORMED_BENCODE)
        }
        consume(VALUE_END)
    }

    /** 扫描列表内的任意值直到闭合。 */
    private fun scanList(
        /** 当前列表深度，子项在此基础上增加。 */
        depth: Int,
    ) {
        consume(LIST_START)
        while (peek() != VALUE_END) scanValue(depth + 1)
        consume(VALUE_END)
    }

    /** 扫描规范字典，键必须是严格递增的字节串。 */
    private fun scanDictionary(
        /** 当前字典深度，值在此基础上增加。 */
        depth: Int,
    ) {
        consume(DICTIONARY_START)
        /** previousKey 保存前一个键切片，不复制可能为二进制的键。 */
        var previousKey: ByteSlice? = null
        while (peek() != VALUE_END) {
            /** key 是当前键切片。 */
            val key = readByteString()
            if (previousKey != null && previousKey.compareUnsigned(key) >= 0) {
                fail(TorrentValidationFailureReason.MALFORMED_BENCODE)
            }
            previousKey = key
            scanValue(depth + 1)
        }
        consume(VALUE_END)
    }

    /** 读取规范字节串并返回内容切片，长度字段不允许前导零或越界。 */
    private fun readByteString(): ByteSlice {
        /** lengthStart 是十进制长度字段首字节。 */
        val lengthStart = cursor
        while (true) {
            /** currentByte 是待判断的长度字段字节，输入结束时退出数字扫描。 */
            val currentByte = peekOrNull() ?: break
            if (currentByte < DIGIT_ZERO || currentByte > DIGIT_NINE) break
            cursor += 1
        }
        if (cursor == lengthStart) fail(TorrentValidationFailureReason.MALFORMED_BENCODE)
        /** lengthDigitCount 用于拒绝 00、01 等非规范长度。 */
        val lengthDigitCount = cursor - lengthStart
        if (bytes[lengthStart] == DIGIT_ZERO && lengthDigitCount > 1) {
            fail(TorrentValidationFailureReason.MALFORMED_BENCODE)
        }
        consume(LENGTH_SEPARATOR)
        /** contentLength 逐位累积并在 Int 越界前拒绝。 */
        var contentLength = 0
        /** digitIndex 遍历长度字段的每个 ASCII 数字。 */
        for (digitIndex in lengthStart until lengthStart + lengthDigitCount) {
            /** digit 是当前十进制数值。 */
            val digit = bytes[digitIndex] - DIGIT_ZERO
            if (contentLength > (Int.MAX_VALUE - digit) / 10) fail(TorrentValidationFailureReason.MALFORMED_BENCODE)
            contentLength = contentLength * 10 + digit
        }
        if (contentLength > bytes.size - cursor) fail(TorrentValidationFailureReason.MALFORMED_BENCODE)
        /** contentStart 是字节串内容首字节。 */
        val contentStart = cursor
        cursor += contentLength
        return ByteSlice(bytes, contentStart, contentLength)
    }

    /** 返回当前字节；输入意外结束时统一分类结构损坏。 */
    private fun peek(): Byte = peekOrNull() ?: fail(TorrentValidationFailureReason.MALFORMED_BENCODE)

    /** 返回当前字节或 null，供数字循环识别输入结尾。 */
    private fun peekOrNull(): Byte? = bytes.getOrNull(cursor)

    /** 消费固定结构字节，不匹配时统一失败。 */
    private fun consume(
        /** 当前语法位置必须出现的字节。 */
        expected: Byte,
    ) {
        if (peek() != expected) fail(TorrentValidationFailureReason.MALFORMED_BENCODE)
        cursor += 1
    }

    /** 抛出不含输入正文的内部失败。 */
    private fun fail(
        /** 调用方最终可观察的稳定失败分类。 */
        reason: TorrentValidationFailureReason,
    ): Nothing = throw BencodeValidationException(reason)

    /** 扫描器固定协议字节与安全边界。 */
    companion object {
        /** 字典起始。 */
        private val DICTIONARY_START = 'd'.code.toByte()
        /** 列表起始。 */
        private val LIST_START = 'l'.code.toByte()
        /** 整数起始。 */
        private val INTEGER_START = 'i'.code.toByte()
        /** 列表、字典与整数终止。 */
        private val VALUE_END = 'e'.code.toByte()
        /** 字节串长度分隔符。 */
        private val LENGTH_SEPARATOR = ':'.code.toByte()
        /** 负整数符号。 */
        private val MINUS = '-'.code.toByte()
        /** ASCII 数字下界。 */
        private val DIGIT_ZERO = '0'.code.toByte()
        /** ASCII 数字上界。 */
        private val DIGIT_NINE = '9'.code.toByte()
        /** 顶层必需 info 键的 ASCII 字节。 */
        private val INFO_KEY = "info".toByteArray()
        /** 最大嵌套深度，防止恶意列表/字典耗尽调用栈。 */
        private const val MAX_NESTING_DEPTH = 128
    }
}

/** 指向原输入的字节串内容，比较时按 Bencode 要求使用无符号字节序。 */
private data class ByteSlice(
    /** 完整输入数组。 */
    val source: ByteArray,
    /** 内容起始下标。 */
    val start: Int,
    /** 内容字节数。 */
    val length: Int,
) {
    /** 比较两个字节串的无符号词典序。 */
    fun compareUnsigned(
        /** 另一个原输入切片。 */
        other: ByteSlice,
    ): Int {
        /** sharedLength 是逐字节比较的共同长度。 */
        val sharedLength = minOf(length, other.length)
        /** offset 是切片内当前相对位置。 */
        for (offset in 0 until sharedLength) {
            /** left 是当前切片无符号字节。 */
            val left = source[start + offset].toInt() and 0xff
            /** right 是另一个切片无符号字节。 */
            val right = other.source[other.start + offset].toInt() and 0xff
            if (left != right) return left - right
        }
        return length - other.length
    }

    /** 判断切片是否与固定键字节完全相等。 */
    fun contentEquals(
        /** 固定 ASCII 键字节。 */
        expected: ByteArray,
    ): Boolean {
        if (length != expected.size) return false
        /** offset 是待比较的键内位置。 */
        for (offset in expected.indices) {
            if (source[start + offset] != expected[offset]) return false
        }
        return true
    }
}

/** 扫描器内部控制流异常，只携带稳定失败分类。 */
private class BencodeValidationException(
    /** 最终返回给调用方的 reasonCode 对应枚举。 */
    val reason: TorrentValidationFailureReason,
) : Exception()

/** 将摘要字节输出为固定小写十六进制，不受 locale 影响。 */
private fun ByteArray.toLowerHex(): String {
    /** alphabet 是稳定的小写十六进制字符表。 */
    val alphabet = "0123456789abcdef"
    /** output 为每个输入字节预留两个字符。 */
    val output = CharArray(size * 2)
    /** byteIndex 是摘要字节位置。 */
    for (byteIndex in indices) {
        /** value 将有符号 Byte 转成 0..255。 */
        val value = this[byteIndex].toInt() and 0xff
        output[byteIndex * 2] = alphabet[value ushr 4]
        output[byteIndex * 2 + 1] = alphabet[value and 0x0f]
    }
    return output.concatToString()
}
