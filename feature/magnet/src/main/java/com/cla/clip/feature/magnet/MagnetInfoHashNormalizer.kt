package com.cla.clip.feature.magnet

import java.util.Locale

/**
 * BTIH infoHash 规范化工具。
 *
 * 第一版只接受 40 位十六进制 SHA-1 infoHash，入库统一转为小写；非法值返回 null，
 * 由调用方按同步跳过、恢复跳过或禁用操作处理。
 */
object MagnetInfoHashNormalizer {
    private val Sha1HexRegex = Regex("^[0-9a-fA-F]{40}$")

    /** 返回规范化后的 40 位小写十六进制 infoHash；非法输入返回 null。 */
    fun normalize(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (!Sha1HexRegex.matches(trimmed)) return null
        return trimmed.lowercase(Locale.ROOT)
    }
}
