package com.cla.clip.feature.torrent.search.provider.builtin.common

import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** 解析 magnet btih 并统一生成 40 位小写 hex 稳定键，不在失败中回显原始 URI。 */
internal object MagnetInfoHashParser {
    /** btih hex 必须恰好 20 字节。 */
    private val hexPattern = Regex("^[0-9a-fA-F]{40}$")
    /** btih base32 使用 RFC 4648 无 padding 字符集，恰好 32 字符。 */
    private val base32Pattern = Regex("^[A-Z2-7a-z]{32}$")
    /** RFC 4648 base32 索引表，大小写输入都先转大写。 */
    private const val base32Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** 解析 magnet 的 xt=urn:btih 参数；非法 scheme、缺参数或非法 hash 返回 null。 */
    fun parse(magnetUri: String): String? {
        /** URI 解析失败只返回 null，避免底层 message 泄漏原文。 */
        val parsedUri = runCatching { URI(magnetUri) }.getOrNull() ?: return null
        if (!parsedUri.scheme.equals("magnet", ignoreCase = true)) return null
        /** magnet 在 java.net.URI 中是 opaque URI，查询需要从 `?` 开头的 scheme-specific part 读取。 */
        val rawSchemeSpecificPart = parsedUri.rawSchemeSpecificPart ?: return null
        if (!rawSchemeSpecificPart.startsWith('?')) return null
        /** 原始查询在本方法内解码，不进入日志或异常。 */
        val rawQuery = rawSchemeSpecificPart.removePrefix("?")
        /** 第一个合法 btih xt 参数，其他 tracker 或显示名不影响稳定键。 */
        val rawInfoHash = rawQuery.split('&').firstNotNullOfOrNull { parameter ->
            /** 查询名与值仅在内存中短暂存在，不传递到外部。 */
            val nameAndValue = parameter.split('=', limit = 2)
            if (nameAndValue.size != 2 || !nameAndValue[0].equals("xt", ignoreCase = true)) {
                null
            } else {
                /** URLDecoder 只处理当前 xt 值，无效百分号编码按 null 处理。 */
                val decodedValue = runCatching { URLDecoder.decode(nameAndValue[1], Charsets.UTF_8.name()) }.getOrNull()
                /** value 是已解码 xt 值，只接受 urn:btih 类型。 */
                decodedValue?.takeIf { value -> value.startsWith("urn:btih:", ignoreCase = true) }
                    ?.substringAfter(':')
                    ?.substringAfter(':')
            }
        } ?: return null
        return normalize(rawInfoHash)
    }

    /** 规范化裸 btih：40 位 hex 转小写，32 位 base32 解码为小写 hex，其他返回 null。 */
    fun normalize(rawInfoHash: String): String? {
        /** 移除协议字段周围空白，不允许 hash 内部空白。 */
        val trimmedInfoHash = rawInfoHash.trim()
        return when {
            hexPattern.matches(trimmedInfoHash) -> trimmedInfoHash.lowercase(Locale.US)
            base32Pattern.matches(trimmedInfoHash) -> decodeBase32ToHex(trimmedInfoHash)
            else -> null
        }
    }

    /** 必需语义的 magnet 解析；失败统一抛出无 message 的 ProviderParseException。 */
    fun require(magnetUri: String): String = parse(magnetUri) ?: throw ProviderParseException()

    /** 用已规范化 infoHash 生成最小 canonical magnet；非法 hash 抛出低敏解析异常。 */
    fun create(rawInfoHash: String): String {
        /** 用于业务稳定键的小写 hex，不保留输入格式差异。 */
        val normalizedInfoHash = normalize(rawInfoHash) ?: throw ProviderParseException()
        return "magnet:?xt=urn:btih:$normalizedInfoHash"
    }

    /** 将恰好 160 bit 的 base32 值按 RFC 4648 解码为 20 字节小写 hex。 */
    private fun decodeBase32ToHex(base32: String): String? {
        /** 解码后固定 20 字节的 SHA-1 infoHash。 */
        val decodedBytes = ByteArray(20)
        /** 未输出的 bit 缓冲，每次追加 5 bit。 */
        var bitBuffer = 0
        /** 当前缓冲中可用 bit 数。 */
        var bitCount = 0
        /** 下一个输出字节下标。 */
        var outputIndex = 0
        /** 每个 base32 字符提供 5 bit；无效字符立即返回 null。 */
        for (character in base32.uppercase(Locale.US)) {
            /** 当前字符在 RFC 4648 字母表中的 5 bit 数值。 */
            val value = base32Alphabet.indexOf(character)
            if (value < 0) return null
            bitBuffer = (bitBuffer shl 5) or value
            bitCount += 5
            if (bitCount >= 8) {
                bitCount -= 8
                decodedBytes[outputIndex] = (bitBuffer shr bitCount).toByte()
                outputIndex += 1
                /** 只保留尚未输出的低位 bit，防止缓冲无界增长。 */
                val remainingMask = if (bitCount == 0) 0 else (1 shl bitCount) - 1
                bitBuffer = bitBuffer and remainingMask
            }
        }
        if (outputIndex != decodedBytes.size || bitCount != 0) return null
        /** byte 是当前解码字节，按无符号两位小写 hex 输出。 */
        return decodedBytes.joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
    }
}
