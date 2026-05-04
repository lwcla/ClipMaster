package com.cla.clip.master.utils

import com.cla.clip.base.general.di.LinkPreviewClient
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

data class LinkMeta(
    val title: String?,
    val description: String?,
    val imageUrl: String?,
    val siteName: String?,
)

/**
 * 链接预览解析器。
 *
 * OkHttpClient 由 Hilt 网络模块提供，避免在工具类内部重复创建客户端，
 * 也方便统一管理超时、日志、代理和连接池等网络配置。
 */
@Singleton
class LinkMetaParser @Inject constructor(
    @param:LinkPreviewClient private val client: OkHttpClient,
) {

    companion object {
        private const val TAG = "LinkMetaParser"
        private const val JSOUP_TIMEOUT_MS = 7_000
        private const val MAX_BODY_SIZE = 2 * 1024 * 1024
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }

    suspend fun parse(url: String): LinkMeta = withContext(Dispatchers.IO) {
        if (url.endsWith(".json", ignoreCase = true)) {
            return@withContext LinkMeta(null, null, null, null)
        }

        parseWithRace(url)
    }

    /**
     * 同时使用 OkHttp 和 Jsoup 两套网络实现解析链接预览。
     *
     * 不同站点会对不同 HTTP 客户端做差异化处理；这里让两条路径并发执行，
     * 谁先拿到有效标题或图片就采用谁，并取消另一条还在等待的请求。
     */
    private suspend fun parseWithRace(url: String): LinkMeta = coroutineScope {
        val okHttpTask = async(Dispatchers.IO) { parseWithOkHttp(url) }
        val jsoupTask = async(Dispatchers.IO) { parseWithJsoup(url) }

        // 第一条完成的路径如果已经拿到有效预览，就立刻取消另一条，避免继续占用网络。
        val first = awaitFirst(okHttpTask, jsoupTask)
        if (first.meta.hasUsefulPreview()) {
            first.other.cancel()
            return@coroutineScope first.meta
        }

        // 如果先完成的是空结果，继续等待另一条路径，给短链/风控页面多一次机会。
        val second = first.other.await()
        if (second.hasUsefulPreview()) {
            second
        } else {
            first.meta.takeIf { it.siteName != null } ?: second
        }
    }

    /**
     * 等待两个解析任务中先完成的一个，并返回另一个任务引用，方便后续取消或继续等待。
     */
    private suspend fun awaitFirst(
        okHttpTask: Deferred<LinkMeta>,
        jsoupTask: Deferred<LinkMeta>,
    ): RaceResult = select {
        okHttpTask.onAwait { meta -> RaceResult(meta, jsoupTask) }
        jsoupTask.onAwait { meta -> RaceResult(meta, okHttpTask) }
    }

    private data class RaceResult(
        val meta: LinkMeta,
        val other: Deferred<LinkMeta>,
    )

    /**
     * 使用 OkHttp 负责联网，Jsoup 只负责解析 HTML 字符串。
     *
     * 这条路径网络控制更强，可以精确设置总超时、请求头和最大读取体积。
     */
    private fun parseWithOkHttp(url: String): LinkMeta {
        return runCatching {
            logD(TAG) { "parse html with okhttp: url=$url" }
            val html = fetchHtml(url)
            if (html.isNullOrBlank()) {
                return@runCatching LinkMeta(null, null, null, extractSiteNameFromUrl(url))
            }

            parseHtml(Jsoup.parse(html, url), url)
        }.getOrElse {
            logE(TAG, it) { "Failed to parse link preview with okhttp" }
            LinkMeta(null, null, null, extractSiteNameFromUrl(url))
        }
    }

    /**
     * 使用 Jsoup 自带连接能力联网并解析。
     *
     * 部分短链站点对 Jsoup 的请求更友好，因此保留这条路径作为 OkHttp 的并发兜底。
     */
    private fun parseWithJsoup(url: String): LinkMeta {
        return runCatching {
            if (!url.isHttpUrl()) return@runCatching LinkMeta(null, null, null, null)

            logD(TAG) { "parse html with jsoup: url=$url" }
            val doc = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .referrer(extractOrigin(url) ?: "https://www.google.com/")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .maxBodySize(MAX_BODY_SIZE)
                .timeout(JSOUP_TIMEOUT_MS)
                .followRedirects(true)
                .get()

            parseHtml(doc, url)
        }.getOrElse {
            logE(TAG, it) { "Failed to parse link preview with jsoup" }
            LinkMeta(null, null, null, extractSiteNameFromUrl(url))
        }
    }

    /**
     * 统一从 HTML 文档中提取链接预览信息。
     *
     * 两条网络路径都会进入这里，保证 JSON-LD、OpenGraph、Twitter Card 的解析规则一致。
     */
    private fun parseHtml(doc: Document, sourceUrl: String): LinkMeta {
        return parseJsonLd(doc, sourceUrl)
            ?: parseMetaTags(doc, sourceUrl)
            ?: LinkMeta(null, null, null, extractSiteNameFromUrl(sourceUrl))
    }

    /**
     * 使用 OkHttp 获取 HTML 字符串。
     *
     * 这里只读取响应体前 2MB，避免预览解析为了大页面消耗过多内存和时间。
     */
    private fun fetchHtml(url: String): String? {
        if (!url.isHttpUrl()) return null

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Referer", extractOrigin(url) ?: "https://www.google.com/")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logD(TAG) { "fetch html failed: code=${response.code}, url=$url" }
                return@use null
            }

            response.peekBody(MAX_BODY_SIZE.toLong()).string()
        }
    }

    private fun parseJsonLd(doc: Document, sourceUrl: String): LinkMeta? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val jsonText = script.data()
                .ifBlank { script.html() }
                .ifBlank { script.text() }
                .let { Parser.unescapeEntities(it, false) }
            val value = runCatching { parseJsonValue(jsonText) }.getOrNull() ?: continue
            val meta = extractFromJson(value, sourceUrl)
            if (meta.hasUsefulPreview()) return meta
        }
        return null
    }

    private fun parseMetaTags(doc: Document, sourceUrl: String): LinkMeta? {
        val title = doc.ogMeta("og:title")
            ?: doc.meta("twitter:title")
            ?: doc.select("title").firstOrNull()?.text()

        val description = doc.ogMeta("og:description")
            ?: doc.meta("description")
            ?: doc.meta("twitter:description")

        val imageUrl = selectPreviewImage(doc)

        val siteName = doc.ogMeta("og:site_name")
            ?: doc.meta("application-name")
            ?: extractSiteNameFromUrl(sourceUrl)

        val meta = LinkMeta(title, description, imageUrl, siteName)
        return meta.takeIf { it.hasUsefulPreview() }
    }

    private fun extractFromJson(value: Any?, sourceUrl: String): LinkMeta {
        val best = findBestJsonObject(value)
            ?: return LinkMeta(null, null, null, extractSiteNameFromUrl(sourceUrl))
        return LinkMeta(
            title = best.firstString("name", "headline", "title"),
            description = best.firstString("description", "summary"),
            imageUrl = best.firstString("image", "thumbnailUrl", "thumbnail")
                ?.let { resolveUrl(sourceUrl, it) }
                ?.takeIf { isUsablePreviewImageUrl(it) },
            siteName = best.firstString("publisher.name", "provider.name")
                ?: extractSiteNameFromUrl(sourceUrl),
        )
    }

    private fun findBestJsonObject(value: Any?): JSONObject? {
        var best: JSONObject? = null

        fun visit(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val hasTitle = node.hasAny("name", "headline", "title")
                    val hasImage = node.hasAny("image", "thumbnailUrl", "thumbnail")
                    if (best == null || (hasTitle && hasImage)) {
                        best = node
                    }
                    node.keys().forEach { key -> visit(node.opt(key)) }
                }

                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        visit(node.opt(i))
                    }
                }
            }
        }

        visit(value)
        return best
    }

    private fun parseJsonValue(text: String): Any? {
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("{") -> JSONObject(trimmed)
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> null
        }
    }

    private fun JSONObject.hasAny(vararg keys: String): Boolean =
        keys.any { has(it) && !optString(it).isNullOrBlankCompat() }

    private fun JSONObject.firstString(vararg paths: String): String? {
        for (path in paths) {
            val value = valueAtPath(path).firstString()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun JSONObject.valueAtPath(path: String): Any? {
        var current: Any? = this
        for (part in path.split(".")) {
            current = (current as? JSONObject)?.opt(part) ?: return null
        }
        return current
    }

    private fun Any?.firstString(): String? {
        return when (this) {
            is String -> takeIf { it.isNotBlank() }
            is JSONArray -> opt(0).firstString()
            is JSONObject -> firstString("url", "contentUrl", "name")
            else -> null
        }
    }

    /**
     * 从页面中选择更像“内容封面”的图片。
     *
     * 有些网站会把 og:image 设置成品牌 logo，这种图通常是 svg、logo、icon 或尺寸很小；
     * 因此这里会收集 meta、懒加载图片和 srcset 多种候选，再过滤明显不适合作为封面的图片。
     */
    private fun selectPreviewImage(doc: Document): String? {
        val candidates = buildList {
            addImageCandidate(doc.ogMeta("og:image"), doc.baseUri())
            addImageCandidate(doc.meta("twitter:image"), doc.baseUri())
            addImageCandidate(doc.select("link[rel=image_src]").firstOrNull()?.attr("href"), doc.baseUri())

            doc.select("img, source[srcset]").forEach { element ->
                addImageCandidatesFromElement(element, doc.baseUri())
            }
        }

        return candidates
            .filter { isUsablePreviewImageUrl(it.url) && !it.isKnownSmallImage() }
            .sortedByDescending { it.score }
            .firstOrNull()
            ?.url
    }

    /**
     * 从 img/source 标签里提取图片候选。
     *
     * 许多 H5 页面会把真实封面放在 data-src、data-original 或 srcset 中，
     * 只读取 src 很容易拿到占位图或 logo。
     */
    private fun MutableList<ImageCandidate>.addImageCandidatesFromElement(element: Element, baseUri: String) {
        val attrs = listOf(
            "src", "data-src", "data-original", "data-lazy-src",
            "data-thumb", "data-thumb-url", "data-thumb_url", "data-mediumthumb",
            "poster"
        )

        attrs.forEach { attr ->
            addImageCandidate(element.attr(attr), baseUri, element)
        }

        extractBestFromSrcSet(element.attr("srcset"))
            ?.let { addImageCandidate(it, baseUri, element) }
    }

    /**
     * 添加图片候选并计算基础评分。
     *
     * meta 图优先级较高，但如果它是 logo/svg，会在后续过滤阶段被剔除。
     */
    private fun MutableList<ImageCandidate>.addImageCandidate(
        rawUrl: String?,
        baseUri: String,
        element: Element? = null,
    ) {
        val resolved = rawUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { resolveUrl(baseUri, it) }
            ?: return

        val width = element?.attr("width")?.toIntOrNull()
        val height = element?.attr("height")?.toIntOrNull()
        val score = when {
            width != null && height != null -> width * height
            element == null -> 50_000
            else -> 10_000
        }

        add(ImageCandidate(resolved, width, height, score))
    }

    /**
     * 从 srcset 中选择最后一个候选。
     *
     * srcset 通常按清晰度从低到高排列，最后一项往往更适合作为预览封面。
     */
    private fun extractBestFromSrcSet(srcSet: String): String? {
        return srcSet.split(",")
            .mapNotNull { item -> item.trim().split(Regex("\\s+")).firstOrNull() }
            .lastOrNull { it.isNotBlank() }
    }

    /**
     * 过滤明显不是内容封面的图片 URL。
     *
     * 例如 logo、icon、sprite、placeholder、svg 等通常只适合作为站点标识，不适合作为链接封面。
     */
    private fun isUsablePreviewImageUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.startsWith("data:")) return false
        if (lower.substringBefore("?").endsWith(".svg")) return false
        if (lower.substringBefore("?").endsWith(".ico")) return false

        val blockedKeywords = listOf("logo", "icon", "sprite", "placeholder", "blank", "default")
        return blockedKeywords.none { lower.contains(it) }
    }

    private data class ImageCandidate(
        val url: String,
        val width: Int?,
        val height: Int?,
        val score: Int,
    ) {
        /**
         * 如果页面显式标了很小的宽高，基本可以判断它不是内容封面。
         */
        fun isKnownSmallImage(): Boolean {
            if (width == null || height == null) return false
            return width < 120 || height < 90
        }
    }

    private fun Document.ogMeta(property: String): String? =
        select("meta[property=$property]").firstOrNull()?.attr("content")?.takeIf { it.isNotBlank() }

    private fun Document.meta(name: String): String? =
        select("meta[name=$name]").firstOrNull()?.attr("content")?.takeIf { it.isNotBlank() }

    private fun LinkMeta?.hasUsefulPreview(): Boolean =
        this != null && (!title.isNullOrBlank() || !imageUrl.isNullOrBlank())

    private fun resolveUrl(baseUri: String, url: String): String {
        val normalized = when {
            url.startsWith("//") -> "https:$url"
            else -> url
        }
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) return normalized
        return try {
            URI(baseUri).resolve(normalized).toString()
        } catch (e: Exception) {
            normalized
        }
    }

    private fun extractOrigin(url: String): String? {
        return try {
            val uri = URI(url)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            "$scheme://$host/"
        } catch (e: Exception) {
            null
        }
    }

    private fun extractSiteNameFromUrl(url: String): String? {
        return try {
            URI(url).host
                ?.removePrefix("www.")
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private fun String.isHttpUrl(): Boolean =
        startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

    private fun String?.isNullOrBlankCompat(): Boolean =
        this == null || isBlank()
}
