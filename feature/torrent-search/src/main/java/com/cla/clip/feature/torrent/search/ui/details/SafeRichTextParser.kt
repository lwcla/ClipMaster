package com.cla.clip.feature.torrent.search.ui.details

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** 受限富文本的显式资源上限。 */
internal data class SafeRichTextLimits(
    /** 原始正文最大字符数。 */
    val maxRawCharacters: Int = 65_536,
    /** 输出纯文本最大字符数。 */
    val maxOutputCharacters: Int = 32_768,
    /** 可点击链接最大数量。 */
    val maxLinks: Int = 64,
    /** 正文图片最大数量。 */
    val maxImages: Int = 32,
    /** 单个 URL 最大字符数。 */
    val maxUrlCharacters: Int = 2_048,
)

/** 纯展示链接及其在输出文字中的范围。 */
internal data class SafeRichTextLink(
    /** 已规范化的 HTTP/HTTPS URL。 */
    val url: String,
    /** 链接文字起始下标。 */
    val start: Int,
    /** 链接文字结束下标。 */
    val endExclusive: Int,
)

/** 不包含可执行 HTML 的安全富文本模型。 */
internal data class SafeRichTextDocument(
    /** 截断后的纯展示文字。 */
    val text: String,
    /** 受数量与 URL 长度限制的网页链接。 */
    val links: List<SafeRichTextLink>,
    /** 受 host 白名单限制的 HTTPS 图片。 */
    val imageUrls: List<String>,
)

/** Jsoup 静态解析与最小 Markdown 提取入口。 */
internal object SafeRichTextParser {
    /** 独立校验海报或截图 URL，规则与正文图片完全一致。 */
    fun normalizeImageUrl(
        /** Provider 返回的不可信图片地址。 */
        rawUrl: String,
        /** 当前详情地址，作为相对地址基准和图片 host 白名单来源。 */
        detailsUrl: String,
        /** Provider 固定基础地址。 */
        providerBaseUrl: String,
        /** Provider 固定备用 host。 */
        alternateUrlDomains: List<String>,
        /** 单 URL 最大字符数。 */
        maxUrlCharacters: Int = SafeRichTextLimits().maxUrlCharacters,
    ): String? {
        /** effectiveMaxUrlCharacters 把错误负值收敛为零预算。 */
        val effectiveMaxUrlCharacters = maxUrlCharacters.coerceAtLeast(0)
        if (rawUrl.isBlank() || rawUrl.length > effectiveMaxUrlCharacters) return null
        /** baseUrl 是相对图片唯一允许采用的解析基准。 */
        val baseUrl = detailsUrl.toHttpUrlOrNull() ?: return null
        /** allowedHosts 只来自当前详情和固定 Provider 元数据。 */
        val allowedHosts = buildAllowedImageHosts(baseUrl, providerBaseUrl, alternateUrlDomains)
        /** parsedUrl 先尝试绝对地址，失败后相对详情地址解析。 */
        val parsedUrl = rawUrl.toHttpUrlOrNull() ?: baseUrl.resolve(rawUrl) ?: return null
        return parsedUrl.toString().takeIf { /** normalized 是规范化后的最终 URL 文本。 */ normalized ->
            parsedUrl.scheme == "https" && parsedUrl.port == 443 && parsedUrl.username.isEmpty() && parsedUrl.password.isEmpty() &&
                parsedUrl.host in allowedHosts && normalized.length <= effectiveMaxUrlCharacters
        }
    }

    /** 把不可信正文转换为封闭展示模型。 */
    fun parse(
        /** Provider 返回的原始正文。 */
        raw: String,
        /** 当前详情页地址，用于解析相对 URL 和允许同站图片。 */
        detailsUrl: String,
        /** Provider 固定基础地址。 */
        providerBaseUrl: String,
        /** Provider 固定备用 host 列表。 */
        alternateUrlDomains: List<String>,
        /** 本次解析采用的资源边界。 */
        limits: SafeRichTextLimits = SafeRichTextLimits(),
    ): SafeRichTextDocument {
        /** effectiveLimits 把调用方错误的零/负上限收敛为不可产出资源。 */
        val effectiveLimits = limits.sanitized()
        /** boundedRaw 在 Jsoup 分配 DOM 前先限制不可信输入规模。 */
        val boundedRaw = raw.take(effectiveLimits.maxRawCharacters)
        /** baseUrl 是相对链接与图片唯一允许采用的解析基准。 */
        val baseUrl = detailsUrl.toHttpUrlOrNull()
        /** allowedImageHosts 只来自详情地址和固定 Provider 元数据。 */
        val allowedImageHosts = buildAllowedImageHosts(baseUrl, providerBaseUrl, alternateUrlDomains)
        /** document 使用 Jsoup 的静态 HTML parser，不执行脚本、样式或网络。 */
        val document = Jsoup.parseBodyFragment(boundedRaw, baseUrl?.toString().orEmpty())
        document.select(ACTIVE_CONTENT_SELECTOR).remove()
        /** builder 统一管理输出文字、链接和图片资源预算。 */
        val builder = SafeRichTextBuilder(effectiveLimits, baseUrl, allowedImageHosts)
        document.body().childNodes().forEach { /** node 是顶层待静态遍历的 DOM 节点。 */ node -> builder.appendNode(node) }
        return builder.build()
    }

    /** 将调用方上限收敛到非负区间，防止 take 等 API 接收负数。 */
    private fun SafeRichTextLimits.sanitized(): SafeRichTextLimits = copy(
        maxRawCharacters = maxRawCharacters.coerceAtLeast(0),
        maxOutputCharacters = maxOutputCharacters.coerceAtLeast(0),
        maxLinks = maxLinks.coerceAtLeast(0),
        maxImages = maxImages.coerceAtLeast(0),
        maxUrlCharacters = maxUrlCharacters.coerceAtLeast(0),
    )

    /** 建立图片精确 host 白名单，非法备用域名不会扩大网络边界。 */
    private fun buildAllowedImageHosts(
        /** 当前详情地址解析结果。 */
        detailsUrl: HttpUrl?,
        /** Provider 固定基础地址。 */
        providerBaseUrl: String,
        /** Provider 固定备用 host。 */
        alternateUrlDomains: List<String>,
    ): Set<String> = buildSet {
        detailsUrl?.host?.let(::add)
        providerBaseUrl.toHttpUrlOrNull()?.host?.let(::add)
        alternateUrlDomains.forEach { /** domain 是待严格解析的固定备用 host。 */ domain ->
            if (domain.isNotBlank() && domain.none { /** character 是路径、端口或凭据分隔符。 */ character -> character in "/\\@:?" }) {
                "https://$domain".toHttpUrlOrNull()?.host?.let(::add)
            }
        }
    }

    /** 主动内容连同子树全部丢弃，避免脚本正文或 SVG/MathML 文本泄漏到展示。 */
    private const val ACTIVE_CONTENT_SELECTOR = "script,style,iframe,object,embed,form,svg,math,noscript,template"
}

/** 单次富文本解析的有界构建器，不向 Compose 暴露 Jsoup 节点。 */
private class SafeRichTextBuilder(
    /** 当前解析的固定资源边界。 */
    private val limits: SafeRichTextLimits,
    /** 解析相对 URL 的详情页基准。 */
    private val baseUrl: HttpUrl?,
    /** 正文图片允许的精确 host。 */
    private val allowedImageHosts: Set<String>,
) {
    /** boundedText 保存最终纯展示文字。 */
    private val boundedText = StringBuilder()
    /** safeLinks 保存文字范围有效的网页链接。 */
    private val safeLinks = mutableListOf<SafeRichTextLink>()
    /** safeImages 保存去重后的白名单 HTTPS 图片。 */
    private val safeImages = linkedSetOf<String>()

    /** 递归遍历已移除主动内容的 DOM，只处理文字、链接、图片和换行。 */
    fun appendNode(
        /** 当前静态 DOM 节点。 */
        node: Node,
    ) {
        if (boundedText.length >= limits.maxOutputCharacters && safeImages.size >= limits.maxImages) return
        when (node) {
            is TextNode -> appendMarkdownText(node.wholeText)
            is Element -> appendElement(node)
            else -> node.childNodes().forEach { /** child 是未知容器下继续静态遍历的子节点。 */ child -> appendNode(child) }
        }
    }

    /** 按元素语义处理安全锚点、图片、换行和普通容器。 */
    private fun appendElement(
        /** 已经过主动标签清理的元素。 */
        element: Element,
    ) {
        /** tagName 统一为小写 HTML 标签名。 */
        val tagName = element.normalName()
        when (tagName) {
            "img" -> addImage(element.attr("src"))
            "br" -> appendPlain("\n")
            "a" -> {
                /** start 是锚点文字写入前的输出下标。 */
                val start = boundedText.length
                element.childNodes().forEach { /** child 是锚点内的纯展示节点。 */ child -> appendNode(child) }
                /** endExclusive 是受截断后的实际锚点文字结尾。 */
                val endExclusive = boundedText.length
                addLink(element.attr("href"), start, endExclusive)
            }
            else -> {
                /** blockElement 标记遍历结束后是否补一个语义换行。 */
                val blockElement = tagName in BLOCK_TAGS
                element.childNodes().forEach { /** child 是普通容器内的静态子节点。 */ child -> appendNode(child) }
                if (blockElement) appendPlain("\n")
            }
        }
    }

    /** 从纯文本节点提取最小 Markdown 链接/图片，其余内容按普通文字输出。 */
    private fun appendMarkdownText(
        /** Jsoup 解码实体后的纯文本节点内容。 */
        text: String,
    ) {
        /** cursor 指向尚未写入的源文本位置。 */
        var cursor = 0
        MARKDOWN_RESOURCE_PATTERN.findAll(text).forEach { /** match 是当前最小 Markdown 资源。 */ match ->
            appendPlain(text.substring(cursor, match.range.first))
            /** isImage 由可选前导感叹号决定资源类型。 */
            val isImage = match.groupValues[1].isNotEmpty()
            /** label 是链接文字或图片替代文字。 */
            val label = match.groupValues[2]
            /** rawUrl 是尚未规范化的不可信 URL。 */
            val rawUrl = match.groupValues[3]
            if (isImage) {
                addImage(rawUrl)
            } else {
                /** start 是 Markdown 链接文字写入前的输出下标。 */
                val start = boundedText.length
                appendPlain(label)
                /** endExclusive 是受输出截断后的链接文字结尾。 */
                val endExclusive = boundedText.length
                addLink(rawUrl, start, endExclusive)
            }
            cursor = match.range.last + 1
        }
        appendPlain(text.substring(cursor))
    }

    /** 在输出字符预算内追加普通文字。 */
    private fun appendPlain(
        /** 待追加且不包含执行语义的文字。 */
        value: String,
    ) {
        /** remaining 是本次仍可写入的字符数量。 */
        val remaining = limits.maxOutputCharacters - boundedText.length
        if (remaining > 0) boundedText.append(value.take(remaining))
    }

    /** 规范化网页链接，并仅在有实际文字范围时加入模型。 */
    private fun addLink(
        /** HTML/Markdown 提供的不可信地址。 */
        rawUrl: String,
        /** 链接文字起始下标。 */
        start: Int,
        /** 链接文字结束下标。 */
        endExclusive: Int,
    ) {
        if (safeLinks.size >= limits.maxLinks || endExclusive <= start) return
        /** normalizedUrl 是通过 HTTP/HTTPS、userinfo、主机和长度校验的规范地址。 */
        val normalizedUrl = normalizeUrl(rawUrl, requireHttps = false) ?: return
        safeLinks += SafeRichTextLink(normalizedUrl.toString(), start, endExclusive)
    }

    /** 规范化图片地址，并执行 HTTPS、默认端口和精确 host 白名单。 */
    private fun addImage(
        /** HTML/Markdown 提供的不可信图片地址。 */
        rawUrl: String,
    ) {
        if (safeImages.size >= limits.maxImages) return
        /** normalizedUrl 是通过通用 URL 边界的 HTTPS 地址。 */
        val normalizedUrl = normalizeUrl(rawUrl, requireHttps = true) ?: return
        if (normalizedUrl.port == 443 && normalizedUrl.host in allowedImageHosts) safeImages += normalizedUrl.toString()
    }

    /** 解析相对或绝对 URL，并拒绝非网页协议、user-info、无 host 与超长值。 */
    private fun normalizeUrl(
        /** 不可信候选地址。 */
        rawUrl: String,
        /** 图片为 true，网页链接允许 HTTP/HTTPS。 */
        requireHttps: Boolean,
    ): HttpUrl? {
        if (rawUrl.isBlank() || rawUrl.length > limits.maxUrlCharacters) return null
        /** parsedUrl 优先作为绝对地址解析，失败时才相对当前详情页解析。 */
        val parsedUrl = rawUrl.toHttpUrlOrNull() ?: baseUrl?.resolve(rawUrl) ?: return null
        /** allowedScheme 限定网页链接协议，magnet 留给后续 06E。 */
        val allowedScheme = if (requireHttps) parsedUrl.scheme == "https" else parsedUrl.scheme == "http" || parsedUrl.scheme == "https"
        return parsedUrl.takeIf { /** url 是待检查规范长度与凭据边界的地址。 */ url ->
            allowedScheme && url.username.isEmpty() && url.password.isEmpty() && url.host.isNotEmpty() && url.toString().length <= limits.maxUrlCharacters
        }
    }

    /** 生成不可变展示模型，并移除多余尾部换行。 */
    fun build(): SafeRichTextDocument {
        /** finalText 只裁剪块标签产生的尾部换行，不改变链接下标之前的内容。 */
        val finalText = boundedText.toString().trimEnd('\n')
        /** boundedLinks 丢弃因尾部裁剪而越界的链接，正常链接保持原下标。 */
        val boundedLinks = safeLinks.filter { /** link 是待检查最终文字边界的链接。 */ link -> link.endExclusive <= finalText.length }
        return SafeRichTextDocument(finalText, boundedLinks, safeImages.toList())
    }

    /** 富文本静态语义常量。 */
    companion object {
        /** 支持的最小 Markdown 图片/链接语法，不接受跨行标签或括号 URL。 */
        private val MARKDOWN_RESOURCE_PATTERN = Regex("(!?)\\[([^]\\r\\n]{0,256})]\\(([^\\s()]{1,4096})\\)")
        /** 常见块级标签结束时补换行，避免段落文字粘连。 */
        private val BLOCK_TAGS = setOf("p", "div", "section", "article", "header", "footer", "li", "ul", "ol", "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6")
    }
}
