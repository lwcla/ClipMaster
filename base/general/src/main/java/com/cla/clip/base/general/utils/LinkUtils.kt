package com.cla.clip.base.general.utils

import java.net.URI
import java.util.Locale

/**
 * 链接识别和分类工具。
 *
 * 负责从剪贴文本中提取 URL，并区分“适合网页预览”“可直接下载媒体”“图片链接”等场景；提取阶段保留原文可识别 URL，
 * 进入预览、WebView 或下载流程前再限制为公网 http/https，避免本地地址或内网地址被误用。
 */
object LinkUtils {

    /** URL正则表达式，用于检测内容是否为链接 */
    private val URL_PATTERN = Regex(
        """(https?|ftp|file)://[^\s<>"'`]+""",
        RegexOption.IGNORE_CASE
    )

    /** 不适合做网页预览的扩展名集合，命中后应避免走 HTML 元信息解析。 */
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

    /** 可直接作为媒体下载入口的扩展名集合，主要用于识别剪贴板里直接复制的音视频地址。 */
    private val DOWNLOADABLE_MEDIA_EXTENSIONS = setOf(
        // streaming playlists / manifests
        "m3u8", "mpd",
        // common video containers
        "mp4", "mkv", "mov", "avi", "webm", "ts",
        // common audio containers
        "mp3", "wav", "aac", "flac", "ogg", "m4a"
    )

    /** 可识别为图片资源的扩展名集合，供图片预览或过滤逻辑使用。 */
    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico", "avif"
    )

    /**
     * 提取字符串中的全部 URL（不保证适合做网页预览）。
     *
     * 返回值按原文出现顺序排列，并按清理后的完整 URL 去重；同一 host 但 query 不同的资源会被保留为不同链接。
     */
    fun extractUrls(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()

        /** 按插入顺序保存清理后的完整链接，避免重复链接在详情页重复展示。 */
        val orderedUrls = linkedSetOf<String>()
        URL_PATTERN.findAll(text).forEach { matchResult ->
            /** 正则命中的原始链接片段，可能带有句尾标点。 */
            val rawUrl = matchResult.value
            /** 去掉句尾标点后的完整链接，后续复制和导航都会使用这个值。 */
            val cleanedUrl = cleanupTrailingPunctuation(rawUrl)
            if (cleanedUrl.isNotBlank()) {
                orderedUrls.add(cleanedUrl)
            }
        }

        return orderedUrls.toList()
    }

    /** 提取字符串中的第一个 URL（不保证适合做网页预览） */
    fun extractFirstUrl(text: String?): String? {
        return extractUrls(text).firstOrNull()
    }

    /** 提取字符串中第一个“适合做网页预览”的 URL */
    fun extractFirstPreviewableUrl(text: String?): String? {
        return extractUrls(text).firstOrNull { url ->
            isPreviewableUrl(url)
        }
    }

    /** 提取字符串中第一个“可下载媒体链接” URL */
    fun extractFirstDownloadableMediaUrl(text: String?): String? {
        return extractUrls(text).firstOrNull { url ->
            isDownloadableMediaUrl(url)
        }
    }

    /** 判断 URL 是否是可进入 WebView 或下载流程的公网 http/https 链接。 */
    fun isPublicHttpUrl(url: String): Boolean {
        return parsePublicHttpUri(url) != null
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

    /**
     * 判断主机是否为 localhost。
     *
     * 本工具只允许公网 URL 进入预览/下载，因此 localhost 会被视为不可公开访问地址。
     */
    private fun isLocalHost(host: String): Boolean {
        return host == "localhost"
    }

    /**
     * 解析并校验公网 http/https URL。
     *
     * 非 http/https、空 host、localhost 和私有网段都会返回 null，避免 WebView 或下载模块访问本机/内网资源。
     */
    private fun parsePublicHttpUri(url: String): URI? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null

        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null

        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return null
        if (host.isBlank()) return null
        if (isLocalHost(host) || isPrivateIp(host)) return null

        return uri
    }

    /**
     * 判断 host 是否属于本地或局域网 IPv4 地址。
     *
     * 当前只识别常见 IPv4 私有网段；域名解析后的内网地址不在这里处理，后续如需更严格防护应在网络层补充。
     */
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
