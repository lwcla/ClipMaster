package com.cla.clip.base.general.utils

import java.net.URI
import java.util.Locale

object LinkUtils {

    /** URL正则表达式，用于检测内容是否为链接 */
    private val URL_PATTERN = Regex(
        """(https?|ftp|file)://[^\s<>"'`]+""",
        RegexOption.IGNORE_CASE
    )

    private val NON_PREVIEWABLE_EXTENSIONS = setOf(
        // data / config
        "json", "xml", "txt", "csv", "yml", "yaml",
        // web assets
        "js", "mjs", "css", "map",
        // images
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico",
        // audio / video
        "mp3", "wav", "aac", "flac", "ogg", "m4a",
        "mp4", "mkv", "mov", "avi", "webm", "m3u8",
        // documents
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        // archives / packages
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz",
        "apk", "ipa", "exe", "dmg", "msi", "iso"
    )

    private val DOWNLOADABLE_MEDIA_EXTENSIONS = setOf(
        // streaming playlists / manifests
        "m3u8", "mpd",
        // common video containers
        "mp4", "mkv", "mov", "avi", "webm", "ts",
        // common audio containers
        "mp3", "wav", "aac", "flac", "ogg", "m4a"
    )

    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico", "avif"
    )

    /** 提取字符串中的第一个 URL（不保证适合做网页预览） */
    fun extractFirstUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null

        val rawUrl = URL_PATTERN.find(text)?.value ?: return null
        return cleanupTrailingPunctuation(rawUrl)
    }

    /** 提取字符串中第一个“适合做网页预览”的 URL */
    fun extractFirstPreviewableUrl(text: String?): String? {
        val url = extractFirstUrl(text) ?: return null
        return if (isPreviewableUrl(url)) url else null
    }

    /** 提取字符串中第一个“可下载媒体链接” URL */
    fun extractFirstDownloadableMediaUrl(text: String?): String? {
        val url = extractFirstUrl(text) ?: return null
        return if (isDownloadableMediaUrl(url)) url else null
    }

    /** 判断 URL 是否适合做网页预览 */
    fun isPreviewableUrl(url: String): Boolean {
        val uri = parsePublicHttpUri(url) ?: return false

        val path = uri.path.orEmpty()
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)

        if (ext.isNotBlank() && ext in NON_PREVIEWABLE_EXTENSIONS) {
            return false
        }

        return true
    }

    /** 判断 URL 是否是可下载媒体链接（如 mp4 / m3u8） */
    fun isDownloadableMediaUrl(url: String): Boolean {
        val uri = parsePublicHttpUri(url) ?: return false
        val path = uri.path.orEmpty()
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext.isNotBlank() && ext in DOWNLOADABLE_MEDIA_EXTENSIONS
    }

    fun isImageUrl(url: String): Boolean {
        val uri = parsePublicHttpUri(url) ?: return false
        val path = uri.path.orEmpty()
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext.isNotBlank() && ext in IMAGE_EXTENSIONS
    }

    /**
     * 只去掉明显是句尾附带的标点。
     * 注意不要无脑去掉 ')'，否则会误伤 Wikipedia 这类合法 URL。
     */
    private fun cleanupTrailingPunctuation(url: String): String {
        var result = url.trimEnd(
            '.', ',', ';', ':', '!', '?',
            '，', '。', '；', '：', '！', '？'
        )

        // 如果结尾是右括号/右中括号/右大括号，仅在“右边数量多于左边”时去掉
        while (result.isNotEmpty()) {
            val last = result.last()
            val shouldTrim = when (last) {
                ')' -> result.count { it == ')' } > result.count { it == '(' }
                ']' -> result.count { it == ']' } > result.count { it == '[' }
                '}' -> result.count { it == '}' } > result.count { it == '{' }
                else -> false
            }
            if (!shouldTrim) break
            result = result.dropLast(1)
        }

        return result
    }

    private fun isLocalHost(host: String): Boolean {
        return host == "localhost"
    }

    /** 仅允许公网 http/https URL */
    private fun parsePublicHttpUri(url: String): URI? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null

        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null

        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return null
        if (host.isBlank()) return null
        if (isLocalHost(host) || isPrivateIp(host)) return null

        return uri
    }

    /** 本地/局域网地址 */
    private fun isPrivateIp(host: String): Boolean {
        // 127.0.0.1
        if (host.startsWith("127.")) return true
        // 10.0.0.0/8
        if (host.startsWith("10.")) return true
        // 192.168.0.0/16
        if (host.startsWith("192.168.")) return true
        // 172.16.0.0 - 172.31.255.255
        if (host.startsWith("172.")) {
            val second = host.split(".").getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        return false
    }
}
