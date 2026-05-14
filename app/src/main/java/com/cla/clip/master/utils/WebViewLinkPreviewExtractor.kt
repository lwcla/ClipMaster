package com.cla.clip.master.utils

import android.webkit.WebView
import com.cla.clip.base.general.dao.LinkPreviewData
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * WebView 链接预览补全器。
 *
 * 首轮剪贴保存使用 OkHttp/Jsoup 快速解析，遇到知乎等站点的 403 风控时可能只能保存域名兜底。
 * 图片/视频提取页已经会用 WebView 真实加载网页，因此这里复用加载后的 DOM 抽取标题、描述、站点名和预览图，
 * 再补写到独立的 `link_previews` 表，让用户返回列表时能看到更完整的链接卡片。
 */
@Singleton
class WebViewLinkPreviewExtractor @Inject constructor(
    /** 剪贴仓库延迟注入，避免工具类初始化时提前触发数据库创建。 */
    private val clipRepository: dagger.Lazy<ClipRepository>,
) {

    companion object {
        /** 日志标签，仅用于链接预览补全调试，不展示给用户。 */
        private const val TAG = "WebViewLinkPreviewExtractor"

        /**
         * 在已加载页面中抽取链接预览信息。
         *
         * JS 侧负责把相对图片地址解析成绝对地址，并按 meta、JSON-LD、video poster、正文图片的优先级挑选封面；
         * Kotlin 侧只接收一个 JSON 字符串，降低 WebView 与 Room 写入之间的耦合。
         */
        private const val EXTRACT_LINK_PREVIEW_JS = """
(function() {
  function text(value) {
    return value && String(value).trim() ? String(value).trim() : null;
  }
  function attr(selector, name) {
    var el = document.querySelector(selector);
    return el ? text(el.getAttribute(name)) : null;
  }
  function meta(selector) {
    return attr(selector, "content");
  }
  function absolute(raw) {
    try {
      var value = text(raw);
      return value ? new URL(value, document.baseURI).href : null;
    } catch (e) {
      return text(raw);
    }
  }
  function usableImage(raw) {
    var url = absolute(raw);
    if (!url) return null;
    var lower = url.toLowerCase();
    var clean = lower.split("?")[0].split("#")[0];
    if (lower.indexOf("data:") === 0) return null;
    if (clean.endsWith(".svg")) return null;
    if (clean.endsWith(".ico")) return null;
    if (/(logo|icon|sprite|placeholder|blank|default)/i.test(lower)) return null;
    return url;
  }
  function firstString(value) {
    if (!value) return null;
    if (typeof value === "string") return text(value);
    if (Array.isArray(value)) {
      for (var i = 0; i < value.length; i++) {
        var item = firstString(value[i]);
        if (item) return item;
      }
      return null;
    }
    if (typeof value === "object") {
      return firstString(value.url) || firstString(value.contentUrl) || firstString(value.name);
    }
    return null;
  }
  function pathValue(obj, path) {
    var cur = obj;
    for (var i = 0; i < path.length; i++) {
      if (!cur || typeof cur !== "object") return null;
      cur = cur[path[i]];
    }
    return cur;
  }
  function jsonLd() {
    var best = null;
    var bestScore = -1;
    function score(node) {
      var value = 0;
      if (firstString(node.name) || firstString(node.headline) || firstString(node.title)) value += 3;
      if (firstString(node.image) || firstString(node.thumbnailUrl) || firstString(node.thumbnail)) value += 4;
      if (firstString(node.description) || firstString(node.summary)) value += 2;
      if (firstString(pathValue(node, ["publisher", "name"])) || firstString(pathValue(node, ["provider", "name"]))) value += 1;
      return value;
    }
    function visit(node) {
      if (!node) return;
      if (Array.isArray(node)) {
        for (var i = 0; i < node.length; i++) visit(node[i]);
        return;
      }
      if (typeof node === "object") {
        var curScore = score(node);
        if (curScore > bestScore) {
          bestScore = curScore;
          best = node;
        }
        Object.keys(node).forEach(function(key) { visit(node[key]); });
      }
    }
    document.querySelectorAll('script[type="application/ld+json"]').forEach(function(script) {
      try { visit(JSON.parse(script.textContent || script.innerText || "{}")); } catch (e) {}
    });
    if (!best || bestScore <= 0) return {};
    return {
      title: firstString(best.name) || firstString(best.headline) || firstString(best.title),
      description: firstString(best.description) || firstString(best.summary),
      imageUrl: usableImage(firstString(best.image) || firstString(best.thumbnailUrl) || firstString(best.thumbnail)),
      siteName: firstString(pathValue(best, ["publisher", "name"])) || firstString(pathValue(best, ["provider", "name"]))
    };
  }
  function bestSrcSet(value) {
    value = text(value);
    if (!value) return null;
    var parts = value.split(",");
    for (var i = parts.length - 1; i >= 0; i--) {
      var item = text(parts[i]);
      if (item) return item.split(/\s+/)[0];
    }
    return null;
  }
  function bestImage() {
    var candidates = [];
    function add(raw, score) {
      var url = usableImage(raw);
      if (url) candidates.push({ url: url, score: score || 0 });
    }
    add(meta('meta[property="og:image"]'), 90000);
    add(meta('meta[name="twitter:image"]'), 85000);
    add(attr('link[rel="image_src"]', "href"), 80000);
    document.querySelectorAll("video[poster]").forEach(function(el) {
      add(el.getAttribute("poster"), 70000);
    });
    document.querySelectorAll("img, source[srcset]").forEach(function(el) {
      var width = parseInt(el.getAttribute("width") || "0", 10);
      var height = parseInt(el.getAttribute("height") || "0", 10);
      var score = width > 0 && height > 0 ? width * height : 10000;
      add(el.getAttribute("src"), score);
      add(el.getAttribute("data-src"), score);
      add(el.getAttribute("data-original"), score);
      add(el.getAttribute("data-lazy-src"), score);
      add(el.getAttribute("data-thumb"), score);
      add(el.getAttribute("data-thumbnail"), score);
      add(bestSrcSet(el.getAttribute("srcset")), score);
    });
    candidates.sort(function(a, b) { return b.score - a.score; });
    return candidates.length ? candidates[0].url : null;
  }
  var ld = jsonLd();
  var host = location.hostname ? location.hostname.replace(/^www\./, "") : null;
  return JSON.stringify({
    title: meta('meta[property="og:title"]') || meta('meta[name="twitter:title"]') || ld.title || text(document.title),
    description: meta('meta[property="og:description"]') || meta('meta[name="description"]') || meta('meta[name="twitter:description"]') || ld.description,
    imageUrl: bestImage() || ld.imageUrl,
    siteName: meta('meta[property="og:site_name"]') || meta('meta[name="application-name"]') || ld.siteName || host
  });
})();
"""
    }

    /**
     * 从当前 WebView 页面抽取并保存链接预览。
     *
     * @param webView 已完成或正在加载目标页面的 WebView，必须在主线程访问。
     * @param sourceUrl 剪贴记录里保存的原始链接，作为 `link_previews` 主键；即使 WebView 发生跳转也不能改成最终 URL。
     * @param fallbackImageUrl 图片提取阶段已经确认的首张有效候选图；DOM meta 没有封面时作为兜底预览图。
     */
    suspend fun extractAndSave(
        webView: WebView,
        sourceUrl: String,
        fallbackImageUrl: String? = null,
    ) {
        if (!sourceUrl.isHttpUrl()) return
        runCatching {
            val result = webView.evaluateJavascriptAwait(EXTRACT_LINK_PREVIEW_JS)
            val preview = parsePreviewResult(result, sourceUrl, fallbackImageUrl) ?: return
            clipRepository.get().upsertLinkPreview(preview)
            logD(TAG) { "extractAndSave: preview=$preview" }
        }.onFailure {
            // 预览补全只是列表体验增强，失败不能影响图片/视频提取主流程。
            logE(TAG, it) { "extractAndSave: 补全链接预览失败" }
        }
    }

    /**
     * 解析 WebView evaluateJavascript 返回值。
     *
     * Android 会对 JS 返回值额外做一层 JSON 编码，因此这里先用 JSONTokener 拆外层，再把里面的字符串转成对象。
     */
    private fun parsePreviewResult(value: String?, sourceUrl: String, fallbackImageUrl: String?): LinkPreviewData? {
        val obj = parseJsonObject(value) ?: JSONObject()
        val imageUrl = (obj.optStringOrNull("imageUrl") ?: fallbackImageUrl)
            ?.let { resolveUrl(sourceUrl, it) }
            ?.takeIf { isUsablePreviewImageUrl(it) }
        val siteName = obj.optStringOrNull("siteName") ?: extractSiteNameFromUrl(sourceUrl)
        val preview = LinkPreviewData(
            link = sourceUrl,
            title = obj.optStringOrNull("title"),
            description = obj.optStringOrNull("description"),
            imageUrl = imageUrl,
            siteName = siteName,
        )
        val hasPreviewValue = !preview.title.isNullOrBlank() ||
                !preview.description.isNullOrBlank() ||
                !preview.imageUrl.isNullOrBlank() ||
                !preview.siteName.isNullOrBlank()
        return preview.takeIf { hasPreviewValue }
    }

    /** 将 JS 返回的对象或双重编码字符串安全转换为 JSONObject，解析失败时返回 null。 */
    private fun parseJsonObject(value: String?): JSONObject? {
        return runCatching {
            when (val decoded = JSONTokener(value ?: "{}").nextValue()) {
                is JSONObject -> decoded
                is String -> JSONObject(decoded)
                else -> JSONObject(value ?: "{}")
            }
        }.getOrNull()
    }

    /** 读取可空字符串字段，过滤空白和字面量 null，避免把无效值写入数据库。 */
    private fun JSONObject.optStringOrNull(key: String): String? {
        return optString(key)
            .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    /** 等待 WebView JS 执行结果；页面销毁或回调为空时返回 null，让调用方自然降级。 */
    private suspend fun WebView.evaluateJavascriptAwait(script: String): String? {
        return suspendCancellableCoroutine { continuation ->
            evaluateJavascript(script) { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
        }
    }

    /** 解析相对 URL，确保写入数据库的预览图可以被列表页图片加载器直接请求。 */
    private fun resolveUrl(baseUri: String, url: String): String {
        val normalized = when {
            url.startsWith("//") -> "https:$url"
            else -> url
        }
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) return normalized
        return runCatching { URI(baseUri).resolve(normalized).toString() }.getOrElse { normalized }
    }

    /** 过滤明显不适合作为列表预览封面的图片地址，例如 logo、icon、占位图和内联 data URI。 */
    private fun isUsablePreviewImageUrl(url: String): Boolean {
        val lower = url.lowercase()
        val clean = lower.substringBefore("?").substringBefore("#")
        if (lower.startsWith("data:")) return false
        if (clean.endsWith(".svg") || clean.endsWith(".ico")) return false
        val blockedKeywords = listOf("logo", "icon", "sprite", "placeholder", "blank", "default")
        return blockedKeywords.none { lower.contains(it) }
    }

    /** 从原始链接推导站点名，只作为 WebView 页面没有暴露站点名时的兜底。 */
    private fun extractSiteNameFromUrl(url: String): String? {
        return runCatching {
            URI(url).host
                ?.removePrefix("www.")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** 链接预览只处理公网网页 URL，避免 file、content 或应用私有 scheme 被写入预览缓存。 */
    private fun String.isHttpUrl(): Boolean =
        startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)
}
