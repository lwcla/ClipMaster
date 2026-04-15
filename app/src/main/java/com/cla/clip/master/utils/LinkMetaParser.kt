package com.cla.clip.master.utils

import com.cla.clip.base.general.utils.logE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

data class LinkMeta(
    val title: String?,
    val description: String?,
    val imageUrl: String?,
    val siteName: String?,
)

object LinkMetaParser {

    private const val TAG = "LinkMetaParser"

    /**
     * 阻塞式解析，调用方必须已经在 IO 协程中。
     * 超时 10s，User-Agent 模拟浏览器，否则很多网站会返回 403。
     */
    suspend fun parse(url: String) = withContext(Dispatchers.IO) {
        if (url.endsWith(".json")) {
            // json不需要去解析
            return@withContext LinkMeta(null, null, null, null)
        }

        runCatching {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                .timeout(10_000)
                .get()

            // 优先读 og: 标签，其次回退到普通标签
            val title = doc.ogMeta("og:title")
                ?: doc.select("title").firstOrNull()?.text()

            val description = doc.ogMeta("og:description")
                ?: doc.meta("description")

            val imageUrl = (doc.ogMeta("og:image")
                ?: doc.select("img[src]")
                    .firstOrNull { img ->
                        val src = img.attr("abs:src")
                        src.isNotBlank()
                                && !src.endsWith(".svg")
                                && !src.contains("icon", ignoreCase = true)
                    }
                    ?.attr("abs:src")
                    )?.takeIf { it.isNotBlank() }
                ?.let { resolveUrl(doc.baseUri(), it) }

            val siteName = doc.ogMeta("og:site_name")
                ?: doc.meta("application-name")
                ?: extractSiteNameFromUrl(url)

            LinkMeta(title, description, imageUrl, siteName)
        }.getOrElse {
            logE(TAG, it) { "链接解析出错" }
            LinkMeta(null, null, null, null)
        }
    }

    /** 读取 <meta property="xxx" content="..."> */
    private fun Document.ogMeta(property: String): String? =
        select("meta[property=$property]").firstOrNull()?.attr("content")?.takeIf { it.isNotBlank() }

    /** 读取 <meta name="xxx" content="..."> */
    private fun Document.meta(name: String): String? =
        select("meta[name=$name]").firstOrNull()?.attr("content")?.takeIf { it.isNotBlank() }

    /**
     * 将可能是相对路径的 url 解析为绝对路径。
     * 例如 baseUri="https://example.com/article/1", url="/img/cover.jpg"
     * → "https://example.com/img/cover.jpg"
     */
    private fun resolveUrl(baseUri: String, url: String): String {
        // 已经是绝对路径，直接返回
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return try {
            URI(baseUri).resolve(url).toString()
        } catch (e: Exception) {
            url // 解析失败就原样返回
        }
    }

    /**
     * 从 URL 中提取站点名称。
     * "https://www.example.com/article/1" → "example.com"
     * "https://mp.weixin.qq.com/s/xxx"    → "weixin.qq.com"
     */
    private fun extractSiteNameFromUrl(url: String): String? {
        return try {
            URI(url).host
                ?.removePrefix("www.")
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
}