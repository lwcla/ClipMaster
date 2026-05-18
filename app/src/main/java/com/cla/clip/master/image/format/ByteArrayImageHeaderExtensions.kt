package com.cla.clip.master.image.format

/**
 * 图片文件头解析使用的字节数组扩展。
 *
 * 这些方法只处理二进制头、chunk 类型和大小端整数，放在格式领域包内，避免 Worker 或下载校验器重复维护底层字节解析细节。
 */

/** 检查字节数组指定位置是否匹配一组无符号字节，供文件头格式识别复用。 */
internal fun ByteArray.matchesBytes(readSize: Int, offset: Int, vararg expected: Int): Boolean {
    if (readSize < offset + expected.size) return false
    return expected.indices.all { index -> this[offset + index].toInt() and 0xff == expected[index] }
}

/** 检查字节数组指定位置是否匹配 ASCII 文本，避免把二进制头转成字符串后再做模糊判断。 */
internal fun ByteArray.matchesAscii(readSize: Int, offset: Int, expected: String): Boolean {
    if (readSize < offset + expected.length) return false
    return expected.indices.all { index -> this[offset + index].toInt().toChar() == expected[index] }
}

/** 检查字节数组中是否包含指定 ASCII 标记，用于 WebP/APNG/AVIF 动画标记诊断。 */
internal fun ByteArray.containsAsciiMarker(marker: String): Boolean {
    val markerBytes = marker.toByteArray(Charsets.US_ASCII)
    if (size < markerBytes.size) return false
    return (0..size - markerBytes.size).any { start ->
        markerBytes.indices.all { offset -> this[start + offset] == markerBytes[offset] }
    }
}

/** 按 ASCII 读取固定长度 chunk 类型，超出边界时返回空字符串以便解析器安全终止。 */
internal fun ByteArray.asciiAt(offset: Int, length: Int): String {
    if (offset < 0 || offset + length > size) return ""
    return copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)
}

/** 判断 ISO BMFF 文件头是否声明 AVIF/AVIS 品牌，用于识别 URL 无后缀的 AVIF 图片。 */
internal fun ByteArray.isAvifHeader(readSize: Int): Boolean {
    if (!matchesAscii(readSize, 4, "ftyp")) return false
    // AVIF 的主品牌或兼容品牌会出现在 ftyp box 中；只扫描已读取头部，避免额外读完整文件。
    val brandText = copyOfRange(8, readSize).toString(Charsets.US_ASCII)
    return brandText.contains("avif") || brandText.contains("avis")
}

/** 读取 24-bit little-endian 整数，用于 WebP 帧持续时间。 */
internal fun ByteArray.littleEndian24(offset: Int): Int {
    if (offset + 3 > size) return 0
    return (this[offset].toInt() and 0xff) or
        ((this[offset + 1].toInt() and 0xff) shl 8) or
        ((this[offset + 2].toInt() and 0xff) shl 16)
}

/** 读取 16-bit little-endian 整数，用于 GIF 帧延迟。 */
internal fun ByteArray.littleEndianShort(offset: Int): Int {
    if (offset + 2 > size) return 0
    return (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
}

/** 读取 32-bit little-endian 整数，用于 WebP chunk 大小。 */
internal fun ByteArray.littleEndianInt(offset: Int): Int {
    if (offset + 4 > size) return 0
    return (this[offset].toInt() and 0xff) or
        ((this[offset + 1].toInt() and 0xff) shl 8) or
        ((this[offset + 2].toInt() and 0xff) shl 16) or
        ((this[offset + 3].toInt() and 0xff) shl 24)
}

/** 读取 16-bit big-endian 整数，用于 APNG 分子/分母。 */
internal fun ByteArray.bigEndianShort(offset: Int): Int {
    if (offset + 2 > size) return 0
    return ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)
}

/** 读取 32-bit big-endian 整数，用于 PNG/APNG chunk 大小。 */
internal fun ByteArray.bigEndianInt(offset: Int): Int {
    if (offset + 4 > size) return 0
    return ((this[offset].toInt() and 0xff) shl 24) or
        ((this[offset + 1].toInt() and 0xff) shl 16) or
        ((this[offset + 2].toInt() and 0xff) shl 8) or
        (this[offset + 3].toInt() and 0xff)
}

/** 跳过 GIF 子块序列，调用方传入第一个子块长度字节所在位置。 */
internal fun ByteArray.skipGifSubBlocks(start: Int): Int {
    var position = start
    while (position < size) {
        val blockSize = this[position].toInt() and 0xff
        position += 1
        if (blockSize == 0) return position
        position += blockSize
    }
    return position
}
