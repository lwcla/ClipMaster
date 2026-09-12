package com.cla.clip.feature.torrent.search.data.importer

/** JSON 单项在完整导入文本中的半开区间；只保存下标以避免复制整棵 DOM。 */
internal data class BookmarkJsonItemRange(
    /** 单项首字符下标。 */
    val start: Int,
    /** 单项结束后一字符下标。 */
    val endExclusive: Int,
)

/** 书签 JSON 外层扫描结果；成功时只保留单项区间和格式标志。 */
internal sealed interface BookmarkJsonEnvelopeScanResult {
    /** 外层结构和完整 JSON 语法均合法。 */
    data class Success(
        /** 按输入顺序排列的单项区间。 */
        val itemRanges: List<BookmarkJsonItemRange>,
        /** true 表示固定上游无版本顶层数组。 */
        val isLegacyArray: Boolean,
    ) : BookmarkJsonEnvelopeScanResult

    /** 外层结构、语法或资源边界不合法。 */
    data class Failure(
        /** 整包稳定失败分类。 */
        val reason: BookmarkImportFailureReason,
    ) : BookmarkJsonEnvelopeScanResult
}

/** 不构造完整 JSON DOM 的严格扫描器，只定位书签数组内的直接子项。 */
internal object BookmarkJsonEnvelopeScanner {
    /** 扫描完整文本，并在深度或条目数超限时停止。 */
    fun scan(
        /** 已通过严格 UTF-8 解码和 10 MiB 限制的完整文本。 */
        text: String,
        /** 包含根节点在内的最大 JSON 嵌套深度。 */
        maxDepth: Int,
        /** 书签数组允许的最大直接子项数量。 */
        maxItems: Int,
        /** envelope 字段名允许的最大解码字符数。 */
        maxEnvelopeKeyChars: Int,
    ): BookmarkJsonEnvelopeScanResult {
        return try {
            BookmarkJsonCursor(text, maxDepth, maxItems, maxEnvelopeKeyChars).scanRoot()
        /** failure 只携带稳定分类，不保留文本片段。 */
        } catch (failure: BookmarkJsonScanException) {
            BookmarkJsonEnvelopeScanResult.Failure(failure.reason)
        }
    }
}

/** 单遍 JSON 游标；未知字段只跳过语法，不创建对象树或大字符串副本。 */
private class BookmarkJsonCursor(
    /** 完整导入文本。 */
    private val text: String,
    /** 最大语法深度。 */
    private val maxDepth: Int,
    /** 最大书签条目数。 */
    private val maxItems: Int,
    /** envelope 字段名最大解码字符数。 */
    private val maxEnvelopeKeyChars: Int,
) {
    /** 当前待读取字符下标。 */
    private var cursor = 0

    /** 扫描根数组、版本 envelope 或其他合法 JSON 根值。 */
    fun scanRoot(): BookmarkJsonEnvelopeScanResult {
        skipWhitespace()
        /** result 是根结构对应的语义结果。 */
        val result = when (peekOrNull()) {
            '[' -> BookmarkJsonEnvelopeScanResult.Success(
                itemRanges = scanItemArray(depth = 1),
                isLegacyArray = true,
            )
            '{' -> scanVersionedEnvelope()
            else -> {
                skipValue(depth = 1)
                BookmarkJsonEnvelopeScanResult.Failure(BookmarkImportFailureReason.INVALID_ROOT)
            }
        }
        skipWhitespace()
        if (cursor != text.length) fail(BookmarkImportFailureReason.MALFORMED_JSON)
        return result
    }

    /** 扫描版本 1 envelope，字段顺序可变但 version/bookmarks 稳定字段重复时拒绝。 */
    private fun scanVersionedEnvelope(): BookmarkJsonEnvelopeScanResult {
        consume('{')
        skipWhitespace()
        /** seenVersion 防止稳定版本字段重复造成解释歧义。 */
        var seenVersion = false
        /** seenBookmarks 防止稳定书签数组字段重复造成解释歧义。 */
        var seenBookmarks = false
        /** version 保存已验证的十进制整数版本。 */
        var version: Long? = null
        /** versionIsInteger 区分非法类型/小数与未知整数版本。 */
        var versionIsInteger = false
        /** itemRanges 在 bookmarks 数组出现后保存其直接子项区间。 */
        var itemRanges: List<BookmarkJsonItemRange>? = null
        /** bookmarksTypeValid 区分缺失/错误类型与合法空数组。 */
        var bookmarksTypeValid = false
        if (peekOrNull() == '}') {
            consume('}')
            return BookmarkJsonEnvelopeScanResult.Failure(BookmarkImportFailureReason.INVALID_ROOT)
        }
        while (true) {
            /** key 是已解码的 envelope 字段名。 */
            val key = readString(
                decode = true,
                maxDecodedChars = maxEnvelopeKeyChars,
            ) ?: fail(BookmarkImportFailureReason.MALFORMED_JSON)
            skipWhitespace()
            consume(':')
            skipWhitespace()
            when (key) {
                "version" -> {
                    if (seenVersion) fail(BookmarkImportFailureReason.MALFORMED_JSON)
                    seenVersion = true
                    /** versionToken 是未经宽松转换的 JSON 数值文本。 */
                    val versionToken = if (peekOrNull() == '-' || peekOrNull()?.isDigit() == true) {
                        scanNumber()
                    } else {
                        skipValue(depth = 2)
                        null
                    }
                    versionIsInteger = versionToken?.matches(INTEGER_PATTERN) == true
                    version = versionToken?.toLongOrNull()
                }
                "bookmarks" -> {
                    if (seenBookmarks) fail(BookmarkImportFailureReason.MALFORMED_JSON)
                    seenBookmarks = true
                    if (peekOrNull() == '[') {
                        itemRanges = scanItemArray(depth = 2)
                        bookmarksTypeValid = true
                    } else {
                        skipValue(depth = 2)
                    }
                }
                else -> skipValue(depth = 2)
            }
            skipWhitespace()
            when (peekOrNull()) {
                ',' -> {
                    consume(',')
                    skipWhitespace()
                }
                '}' -> {
                    consume('}')
                    break
                }
                else -> fail(BookmarkImportFailureReason.MALFORMED_JSON)
            }
        }
        if (!versionIsInteger || version == null || !bookmarksTypeValid || itemRanges == null) {
            return BookmarkJsonEnvelopeScanResult.Failure(BookmarkImportFailureReason.INVALID_ROOT)
        }
        if (version != SUPPORTED_VERSION) {
            return BookmarkJsonEnvelopeScanResult.Failure(BookmarkImportFailureReason.UNSUPPORTED_VERSION)
        }
        return BookmarkJsonEnvelopeScanResult.Success(itemRanges, isLegacyArray = false)
    }

    /** 扫描书签数组并记录每个直接子值的原文本区间。 */
    private fun scanItemArray(
        /** 当前数组在完整 JSON 中的深度。 */
        depth: Int,
    ): List<BookmarkJsonItemRange> {
        checkDepth(depth)
        consume('[')
        skipWhitespace()
        /** ranges 只保存两个 Int，不复制每个条目的 JSON 文本。 */
        val ranges = mutableListOf<BookmarkJsonItemRange>()
        if (peekOrNull() == ']') {
            consume(']')
            return ranges
        }
        while (true) {
            if (ranges.size >= maxItems) fail(BookmarkImportFailureReason.TOO_MANY_ITEMS)
            /** itemStart 跳过分隔空白后指向当前直接子值首字符。 */
            val itemStart = cursor
            skipValue(depth + 1)
            ranges += BookmarkJsonItemRange(itemStart, cursor)
            skipWhitespace()
            when (peekOrNull()) {
                ',' -> {
                    consume(',')
                    skipWhitespace()
                }
                ']' -> {
                    consume(']')
                    return ranges
                }
                else -> fail(BookmarkImportFailureReason.MALFORMED_JSON)
            }
        }
    }

    /** 跳过一个完整 JSON 值，同时验证语法和深度。 */
    private fun skipValue(
        /** 当前值在完整 JSON 中的深度。 */
        depth: Int,
    ) {
        checkDepth(depth)
        skipWhitespace()
        when (peekOrNull()) {
            '"' -> readString(decode = false)
            '{' -> skipObject(depth)
            '[' -> skipArray(depth)
            't' -> consumeLiteral("true")
            'f' -> consumeLiteral("false")
            'n' -> consumeLiteral("null")
            '-', in '0'..'9' -> scanNumber()
            else -> fail(BookmarkImportFailureReason.MALFORMED_JSON)
        }
    }

    /** 跳过普通 JSON 对象，字段名和值都不保留。 */
    private fun skipObject(
        /** 当前对象深度。 */
        depth: Int,
    ) {
        consume('{')
        skipWhitespace()
        if (peekOrNull() == '}') {
            consume('}')
            return
        }
        while (true) {
            readString(decode = false)
            skipWhitespace()
            consume(':')
            skipValue(depth + 1)
            skipWhitespace()
            when (peekOrNull()) {
                ',' -> {
                    consume(',')
                    skipWhitespace()
                }
                '}' -> {
                    consume('}')
                    return
                }
                else -> fail(BookmarkImportFailureReason.MALFORMED_JSON)
            }
        }
    }

    /** 跳过普通 JSON 数组，成员内容不保留。 */
    private fun skipArray(
        /** 当前数组深度。 */
        depth: Int,
    ) {
        consume('[')
        skipWhitespace()
        if (peekOrNull() == ']') {
            consume(']')
            return
        }
        while (true) {
            skipValue(depth + 1)
            skipWhitespace()
            when (peekOrNull()) {
                ',' -> {
                    consume(',')
                    skipWhitespace()
                }
                ']' -> {
                    consume(']')
                    return
                }
                else -> fail(BookmarkImportFailureReason.MALFORMED_JSON)
            }
        }
    }

    /** 扫描 JSON 字符串；仅 envelope 键需要解码并返回文本。 */
    private fun readString(
        /** true 时构造解码文本，false 时只验证并移动游标。 */
        decode: Boolean,
        /** 解码文本最大字符数；只扫描语法时不参与限制。 */
        maxDecodedChars: Int = Int.MAX_VALUE,
    ): String? {
        consume('"')
        /** output 仅为短 envelope 键分配，未知大字符串不产生第二份副本。 */
        val output = if (decode) StringBuilder() else null
        while (true) {
            /** character 是当前原始 JSON 字符。 */
            val character = peekOrNull() ?: fail(BookmarkImportFailureReason.MALFORMED_JSON)
            cursor += 1
            when {
                character == '"' -> return output?.toString()
                character == '\\' -> {
                    /** escaped 是反斜杠后的固定转义标记。 */
                    val escaped = peekOrNull() ?: fail(BookmarkImportFailureReason.MALFORMED_JSON)
                    cursor += 1
                    when (escaped) {
                        '"', '\\', '/' -> output?.append(escaped)
                        'b' -> output?.append('\b')
                        'f' -> output?.append('\u000C')
                        'n' -> output?.append('\n')
                        'r' -> output?.append('\r')
                        't' -> output?.append('\t')
                        'u' -> {
                            /** codePoint 保存当前四位十六进制 UTF-16 code unit。 */
                            var codePoint = 0
                            repeat(4) {
                                /** hexDigit 是当前转义位的十六进制数值。 */
                                val hexDigit = peekOrNull()?.digitToIntOrNull(16)
                                    ?: fail(BookmarkImportFailureReason.MALFORMED_JSON)
                                cursor += 1
                                codePoint = codePoint * 16 + hexDigit
                            }
                            output?.append(codePoint.toChar())
                        }
                        else -> fail(BookmarkImportFailureReason.MALFORMED_JSON)
                    }
                }
                character < ' ' -> fail(BookmarkImportFailureReason.MALFORMED_JSON)
                else -> output?.append(character)
            }
            if (output != null && output.length > maxDecodedChars) {
                fail(BookmarkImportFailureReason.ENVELOPE_KEY_TOO_LONG)
            }
        }
    }

    /** 扫描严格 JSON 数字并返回原始 token，拒绝前导零和不完整指数。 */
    private fun scanNumber(): String {
        /** start 是数字 token 首字符下标。 */
        val start = cursor
        if (peekOrNull() == '-') cursor += 1
        when (peekOrNull()) {
            '0' -> {
                cursor += 1
                if (peekOrNull()?.isDigit() == true) fail(BookmarkImportFailureReason.MALFORMED_JSON)
            }
            in '1'..'9' -> while (peekOrNull()?.isDigit() == true) cursor += 1
            else -> fail(BookmarkImportFailureReason.MALFORMED_JSON)
        }
        if (peekOrNull() == '.') {
            cursor += 1
            if (peekOrNull()?.isDigit() != true) fail(BookmarkImportFailureReason.MALFORMED_JSON)
            while (peekOrNull()?.isDigit() == true) cursor += 1
        }
        if (peekOrNull() == 'e' || peekOrNull() == 'E') {
            cursor += 1
            if (peekOrNull() == '+' || peekOrNull() == '-') cursor += 1
            if (peekOrNull()?.isDigit() != true) fail(BookmarkImportFailureReason.MALFORMED_JSON)
            while (peekOrNull()?.isDigit() == true) cursor += 1
        }
        return text.substring(start, cursor)
    }

    /** 消费 true、false 或 null 固定字面量。 */
    private fun consumeLiteral(
        /** 当前语法位置必须完整匹配的字面量。 */
        expected: String,
    ) {
        if (!text.regionMatches(cursor, expected, 0, expected.length)) {
            fail(BookmarkImportFailureReason.MALFORMED_JSON)
        }
        cursor += expected.length
    }

    /** 跳过 JSON 标准允许的四种空白字符。 */
    private fun skipWhitespace() {
        while (peekOrNull() == ' ' || peekOrNull() == '\t' || peekOrNull() == '\r' || peekOrNull() == '\n') {
            cursor += 1
        }
    }

    /** 检查当前值深度，超限使用独立稳定分类。 */
    private fun checkDepth(
        /** 当前一基深度。 */
        depth: Int,
    ) {
        if (depth > maxDepth) fail(BookmarkImportFailureReason.JSON_TOO_DEEP)
    }

    /** 消费固定字符；不匹配或提前结束均视为 JSON 语法损坏。 */
    private fun consume(
        /** 当前语法位置必须出现的字符。 */
        expected: Char,
    ) {
        if (peekOrNull() != expected) {
            fail(BookmarkImportFailureReason.MALFORMED_JSON)
        }
        cursor += 1
    }

    /** 返回当前字符或 null，不改变游标。 */
    private fun peekOrNull(): Char? = text.getOrNull(cursor)

    /** 抛出仅携带稳定失败分类的内部控制流异常。 */
    private fun fail(
        /** 调用方最终观察到的整包失败分类。 */
        reason: BookmarkImportFailureReason,
    ): Nothing = throw BookmarkJsonScanException(reason)

    /** JSON envelope 固定协议。 */
    companion object {
        /** 当前唯一支持的导入版本。 */
        private const val SUPPORTED_VERSION = 1L
        /** 版本字段只接受不带小数或指数的 JSON 整数。 */
        private val INTEGER_PATTERN = Regex("-?(?:0|[1-9][0-9]*)")
    }
}

/** JSON 扫描内部控制流异常，不携带原始输入或底层 message。 */
private class BookmarkJsonScanException(
    /** 最终返回给调用方的稳定失败分类。 */
    val reason: BookmarkImportFailureReason,
) : Exception()
