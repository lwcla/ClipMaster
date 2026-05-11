package com.cla.clip.master.ui.page.image

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.cla.clip.base.general.R
import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.base.general.repository.ImageCandidateData
import com.cla.clip.base.general.utils.logD
import com.cla.clip.master.ui.widget.ProbeWebView
import com.cla.clip.master.ui.widget.TitleBar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONTokener
import kotlin.coroutines.resume

private const val IMAGE_PROBE_TIMEOUT_MS = 20_000L
private const val IMAGE_COLLECT_SETTLE_DELAY_MS = 600L
private const val IMAGE_COLLECT_STEP_DELAY_MS = 350L
private const val IMAGE_COLLECT_MAX_ROUNDS = 24

private const val COLLECT_IMAGES_JS = """
(function() {
  var imageExtRe = /\.(?:jpe?g|png|webp|gif|avif|bmp|svg)(?:[?#].*)?/i;
  var imageHintRe = /(?:^|[?&/=_-])(?:image|img|photo|pic|poster|thumbnail|thumb)(?:[=/&._-]|$)/i;
  var seen = {};
  var out = [];
  var visitedRoots = new WeakSet();

  function rootBase(root, fallback) {
    return (root && root.baseURI) ||
      (root && root.ownerDocument && root.ownerDocument.baseURI) ||
      fallback ||
      document.baseURI;
  }

  function abs(u, base) {
    try {
      u = String(u || "").trim().replace(/&amp;/g, "&");
      if (!u || u === "#" || /^javascript:/i.test(u)) return "";
      return new URL(u, base || document.baseURI).href;
    } catch(e) {
      return "";
    }
  }

  function looksLikeImageUrl(u) {
    u = String(u || "").trim();
    return imageExtRe.test(u) || imageHintRe.test(u);
  }

  function bestFromSrcset(srcset) {
    if (!srcset) return "";
    var best = "";
    var bestScore = -1;
    srcset.split(",").forEach(function(part) {
      var bits = part.trim().split(/\s+/);
      var url = bits[0] || "";
      var score = 0;
      if (bits.length > 1) {
        var d = bits[1];
        if (d.endsWith("w")) score = parseInt(d) || 0;
        else if (d.endsWith("x")) score = Math.round((parseFloat(d) || 0) * 1000);
      }
      if (url && score >= bestScore) { best = url; bestScore = score; }
    });
    return best;
  }

  function urlsFromCss(value) {
    var result = [];
    if (!value || value === "none") return result;

    var urlRe = /url\(\s*(['"]?)(.*?)\1\s*\)/g;
    var match;
    while ((match = urlRe.exec(value)) !== null) {
      if (match[2]) result.push(match[2]);
    }

    var quotedRe = /["']([^"']+\.(?:jpe?g|png|webp|gif|avif|bmp|svg)(?:[?#][^"']*)?)["']/ig;
    while ((match = quotedRe.exec(value)) !== null) {
      if (match[1]) result.push(match[1]);
    }
    return result;
  }

  function push(url, w, h, source, base) {
    url = abs(url, base);
    if (!url || seen[url]) return;
    seen[url] = true;
    out.push({ url: url, width: w || null, height: h || null, source: source });
  }

  function lazyUrl(img) {
    return img.getAttribute("data-url") ||
      img.getAttribute("data-src") ||
      img.getAttribute("data-original") ||
      img.getAttribute("data-original-url") ||
      img.getAttribute("data-lazy-src") ||
      img.getAttribute("data-lazyload") ||
      img.getAttribute("data-lazy") ||
      img.getAttribute("data-bg") ||
      img.getAttribute("data-bg-src") ||
      img.getAttribute("data-background") ||
      img.getAttribute("data-background-image") ||
      "";
  }

  function lazySrcset(el) {
    return el.getAttribute("data-srcset") ||
      el.getAttribute("data-lazy-srcset") ||
      el.getAttribute("data-original-srcset") ||
      el.getAttribute("data-lazyset") ||
      "";
  }

  function collectElementAttributes(el, base) {
    [
      "data-url",
      "data-src",
      "data-original",
      "data-original-url",
      "data-lazy-src",
      "data-lazyload",
      "data-lazy",
      "data-bg",
      "data-bg-src",
      "data-background",
      "data-background-image",
      "data-image",
      "data-img",
      "data-thumb",
      "data-thumbnail",
      "poster",
      "href",
      "xlink:href"
    ].forEach(function(name) {
      var value = el.getAttribute(name);
      if (looksLikeImageUrl(value)) {
        push(value, el.offsetWidth, el.offsetHeight, "attr:" + name, base);
      }
    });
  }

  function collectRoot(root, inheritedBase) {
    if (!root || visitedRoots.has(root)) return;
    visitedRoots.add(root);

    var base = rootBase(root, inheritedBase);

    root.querySelectorAll("img").forEach(function(img) {
      // 懒加载站点常把真实地址放在 data-*，同时保留透明 src，占位图被 Kotlin 侧过滤。
      push(lazyUrl(img), img.naturalWidth || img.width, img.naturalHeight || img.height, "img:lazy", base);
      push(bestFromSrcset(lazySrcset(img)), img.naturalWidth || img.width, img.naturalHeight || img.height, "img:lazy-srcset", base);
      push(bestFromSrcset(img.getAttribute("srcset")), img.naturalWidth || img.width, img.naturalHeight || img.height, "img:srcset", base);
      push(img.currentSrc, img.naturalWidth || img.width, img.naturalHeight || img.height, "img:currentSrc", base);
      push(img.src, img.naturalWidth || img.width, img.naturalHeight || img.height, "img:src", base);
    });

    root.querySelectorAll("source").forEach(function(source) {
      push(bestFromSrcset(lazySrcset(source)), null, null, "source:lazy-srcset", base);
      push(bestFromSrcset(source.getAttribute("srcset")), null, null, "source:srcset", base);
      push(source.getAttribute("src"), null, null, "source:src", base);
    });

    root.querySelectorAll("video[poster]").forEach(function(video) {
      push(video.getAttribute("poster"), video.offsetWidth, video.offsetHeight, "video:poster", base);
    });

    root.querySelectorAll("meta[property*='image'], meta[name*='image'], meta[itemprop*='image']").forEach(function(meta) {
      push(meta.getAttribute("content"), null, null, "meta:image", base);
    });

    root.querySelectorAll("link[rel~='preload'][as='image'], link[rel='image_src'], link[imagesrcset]").forEach(function(link) {
      push(link.getAttribute("href"), null, null, "link:image", base);
      push(bestFromSrcset(link.getAttribute("imagesrcset")), null, null, "link:imagesrcset", base);
    });

    root.querySelectorAll("noscript").forEach(function(node) {
      var template = document.createElement("template");
      template.innerHTML = node.textContent || node.innerHTML || "";
      // 很多服务端降级内容只放在 noscript 里，解析这份 HTML 能补到 JS 懒加载前的真实图。
      collectRoot(template.content, base);
    });

    root.querySelectorAll("*").forEach(function(el) {
      collectElementAttributes(el, base);

      var style = window.getComputedStyle(el);
      [
        "background-image",
        "background",
        "content",
        "mask-image",
        "-webkit-mask-image",
        "border-image-source",
        "list-style-image"
      ].forEach(function(prop) {
        urlsFromCss(style.getPropertyValue(prop)).forEach(function(url) {
          push(url, el.offsetWidth, el.offsetHeight, "css:" + prop, base);
        });
      });

      if (el.shadowRoot) {
        // 只遍历开放的 shadowRoot，封闭组件无法从页面脚本中安全读取。
        collectRoot(el.shadowRoot, base);
      }
    });

    root.querySelectorAll("iframe, frame").forEach(function(frame) {
      try {
        if (frame.contentDocument) collectRoot(frame.contentDocument, frame.src || base);
      } catch(e) {
        // 跨域 frame 不能直接读取 DOM，网络拦截仍可作为补充来源。
      }
    });
  }

  collectRoot(document, document.baseURI);
  return JSON.stringify(out);
})()
"""

private const val IMAGE_SCROLL_PROBE_JS = """
(function() {
  var doc = document.documentElement;
  var body = document.body;
  var viewport = window.innerHeight || doc.clientHeight || 800;
  var maxScroll = Math.max(
    body ? body.scrollHeight : 0,
    doc ? doc.scrollHeight : 0,
    body ? body.offsetHeight : 0,
    doc ? doc.offsetHeight : 0
  ) - viewport;
  maxScroll = Math.max(0, maxScroll);

  if (!window.__clipImageProbe) {
    window.__clipImageProbe = { y: window.scrollY || window.pageYOffset || 0, direction: 1 };
  }

  var state = window.__clipImageProbe;
  var current = window.scrollY || window.pageYOffset || 0;
  var next = current + Math.max(300, Math.floor(viewport * 0.85)) * state.direction;
  if (next >= maxScroll) {
    next = maxScroll;
    state.direction = -1;
  } else if (next <= 0) {
    next = 0;
    state.direction = 1;
  }

  window.scrollTo(0, next);
  // 派发滚动/缩放事件，兼容只监听事件而不依赖 IntersectionObserver 的懒加载库。
  window.dispatchEvent(new Event("scroll"));
  window.dispatchEvent(new Event("resize"));
  return JSON.stringify({ y: next, max: maxScroll });
})()
"""

@Composable
fun ImageExtractPage(
    imageExtractVm: ImageExtractVm = hiltViewModel(),
    pageUrl: String,
    pageName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var probeUserAgent by remember { mutableStateOf<String?>(null) }
    var showOpenDialog by remember { mutableStateOf(false) }
    var collectJob by remember { mutableStateOf<Job?>(null) }

    fun clearWebView() {
        collectJob?.cancel()
        collectJob = null
        webViewRef?.webChromeClient = null
        webViewRef?.stopLoading()
        webViewRef?.clearHistory()
        webViewRef?.destroy()
        webViewRef = null
    }

    DisposableEffect(Unit) {
        onDispose { clearWebView() }
    }

    LaunchedEffect(imageExtractVm.sessionId) {
        if (imageExtractVm.probeState is ImageProbeState.Extracted) return@LaunchedEffect
        collectJob?.cancel()
        collectJob = null
        imageExtractVm.probeState = ImageProbeState.Probing(imageExtractVm.sessionId)
        webViewRef?.stopLoading()
        webViewRef?.clearHistory()
        webViewRef?.loadUrl(pageUrl)
    }

    LaunchedEffect(imageExtractVm.probeState) {
        val state = imageExtractVm.probeState
        if (state is ImageProbeState.Probing) {
            delay(IMAGE_PROBE_TIMEOUT_MS)
            if (imageExtractVm.probeState == state) {
                imageExtractVm.probeState = ImageProbeState.Failed
            }
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            TitleBar(stringResource(R.string.base_general_image_extract_title), onBack)

            val state = imageExtractVm.probeState
            if (state is ImageProbeState.Probing) {
                ProbeWebView(
                    targetUrl = pageUrl,
                    onWebViewReady = {
                        webViewRef = it
                        // UserAgent 只能在 WebView 所在线程读取，后续后台拦截回调只使用这个缓存值。
                        probeUserAgent = it.settings.userAgentString
                    },
                    onPageFinished = { view, _ ->
                        collectJob?.cancel()
                        collectJob = coroutineScope.launch {
                            // 页面刚完成时很多懒加载图还没写入 DOM，先滚动探测几轮再按 DOM 顺序落库。
                            val json = collectImagesAfterLazyLoad(view)
                            val candidates = parseDomCandidates(json, view.url ?: pageUrl, view.settings.userAgentString)
                            imageExtractVm.saveExtractedImages(pageUrl, pageName, candidates)
                            clearWebView()
                        }
                    },
                    shouldInterceptRequest = { _, request ->
                        val candidate = request.toNetworkCandidate(pageUrl, probeUserAgent)
                        if (candidate != null) {
                            imageExtractVm.addNetworkCandidate(candidate)
                        }
                        null
                    }
                )
            }

            ImageExtractContent(
                state = state,
                viewModel = imageExtractVm,
                onRetry = { imageExtractVm.sessionId += 1 },
                onOpen = { showOpenDialog = true },
            )
        }
    }

    if (showOpenDialog) {
        AlertDialog(
            onDismissRequest = { showOpenDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    showOpenDialog = false
                    openGallery(context)
                }) {
                    Text(stringResource(R.string.base_general_open_gallery))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showOpenDialog = false
                    openFolder(context)
                }) {
                    Text(stringResource(R.string.base_general_open_folder))
                }
            },
            title = { Text(stringResource(R.string.base_general_open_images)) },
            text = { Text(stringResource(R.string.base_general_choose_saved_image_location)) }
        )
    }
}

@Composable
private fun ImageExtractContent(
    state: ImageProbeState,
    viewModel: ImageExtractVm,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        when (state) {
            ImageProbeState.Idle -> Unit
            is ImageProbeState.Probing -> LoadingText(stringResource(R.string.base_general_image_extract_loading))
            ImageProbeState.Failed -> FailedText(onRetry)
            is ImageProbeState.Extracted -> BatchStatusContent(state, viewModel, onRetry, onOpen)
        }
    }
}

@Composable
private fun BatchStatusContent(
    state: ImageProbeState.Extracted,
    viewModel: ImageExtractVm,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
) {
    val batch by viewModel.observeBatch(state.batchId).collectAsState(initial = null)
    val curBatch = batch
    if (curBatch == null || curBatch.status == ImageExtractBatchData.STATUS_EXTRACTED) {
        Text(stringResource(R.string.base_general_image_extract_count, state.count), style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = {
                // 用户确认后才开启批量下载，避免仅预览提取结果时消耗存储空间。
                viewModel.startDownload(state.batchId)
            },
            modifier = Modifier.padding(top = 12.dp)
        ) {
            Text(stringResource(R.string.base_general_download_all_images))
        }
        return
    }

    when (curBatch.status) {
        ImageExtractBatchData.STATUS_DOWNLOADING -> {
            LoadingText(
                stringResource(
                    R.string.base_general_image_download_progress,
                    curBatch.successCount + curBatch.failedCount + curBatch.filteredCount,
                    curBatch.totalCount
                )
            )
        }

        ImageExtractBatchData.STATUS_SUCCESS -> {
            SuccessText(buildImageDownloadResultText(curBatch, includeOpenText = true), onOpen)
        }

        ImageExtractBatchData.STATUS_PARTIAL_SUCCESS -> {
            SuccessText(buildImageDownloadResultText(curBatch, includeOpenText = true), onOpen)
        }

        ImageExtractBatchData.STATUS_FILTERED -> {
            SuccessText(buildImageDownloadResultText(curBatch, includeOpenText = true), onOpen)
        }

        ImageExtractBatchData.STATUS_FAILED -> {
            FailedText(onRetry, buildImageDownloadResultText(curBatch, includeOpenText = false))
        }
    }
}

/** 组装图片下载结果文案，统一使用字符串资源，避免 UI 层硬编码中文。 */
@Composable
private fun buildImageDownloadResultText(batch: ImageExtractBatchData, includeOpenText: Boolean): String {
    val context = LocalContext.current
    val parts = buildList {
        if (batch.successCount > 0) add(context.getString(R.string.base_general_image_saved_count, batch.successCount))
        if (batch.filteredCount > 0) add(context.getString(R.string.base_general_image_filtered_count, batch.filteredCount))
        if (batch.failedCount > 0) add(context.getString(R.string.base_general_image_failed_count, batch.failedCount))
        if (isEmpty()) add(context.getString(R.string.base_general_image_download_failed))
    }
    val separator = context.getString(R.string.base_general_text_separator)
    return parts.joinToString(separator) + if (includeOpenText) separator + context.getString(R.string.base_general_image_open_suffix) else ""
}

@Composable
private fun LoadingText(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(25.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun SuccessText(text: String, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onOpen)
    ) {
        Icon(
            painter = rememberVectorPainter(Icons.Default.Done),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(12.dp).size(24.dp)
        )
        Text(text = text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FailedText(onRetry: () -> Unit, text: String = stringResource(R.string.base_general_image_extract_failed_retry)) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onRetry)
    ) {
        Icon(
            painter = rememberVectorPainter(Icons.Default.Error),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(12.dp).size(24.dp)
        )
        Text(text = text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun WebResourceRequest.toNetworkCandidate(defaultReferer: String, defaultUserAgent: String?): ImageCandidateData? {
    val reqUrl = url.toString()
    if (!isLikelyImageRequest(url, requestHeaders.orEmpty())) {
        return null
    }
    val headers = requestHeaders.orEmpty()
    val cookie = CookieManager.getInstance().getCookie(reqUrl)
    return ImageCandidateData(
        url = reqUrl,
        referer = headers["Referer"] ?: defaultReferer,
        userAgent = headers["User-Agent"] ?: defaultUserAgent,
        cookie = cookie,
        displayOrder = Int.MAX_VALUE,
        width = null,
        height = null
    )
}

/** 等待页面稳定并自动滚动触发懒加载，最后再执行 DOM 图片收集脚本。 */
private suspend fun collectImagesAfterLazyLoad(view: WebView): String? {
    delay(IMAGE_COLLECT_SETTLE_DELAY_MS)

    repeat(IMAGE_COLLECT_MAX_ROUNDS) {
        // 自动滚动可以触发 IntersectionObserver、scroll 事件和图片懒加载库，补齐首屏外图片。
        view.evaluateJavascriptAwait(IMAGE_SCROLL_PROBE_JS)
        delay(IMAGE_COLLECT_STEP_DELAY_MS)
    }

    // 回到顶部能让后续命名仍以文档顺序为准，也避免销毁前页面停在底部造成可见闪动。
    view.evaluateJavascriptAwait("window.scrollTo(0, 0);")
    delay(IMAGE_COLLECT_SETTLE_DELAY_MS)
    return view.evaluateJavascriptAwait(COLLECT_IMAGES_JS)
}

/** 将 WebView 的回调式 JS 执行封装成挂起函数，便于按顺序完成滚动和收集。 */
private suspend fun WebView.evaluateJavascriptAwait(script: String): String? {
    return suspendCancellableCoroutine { continuation ->
        evaluateJavascript(script) { result ->
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }
    }
}

private fun parseDomCandidates(value: String?, referer: String, userAgent: String?): List<ImageCandidateData> {
    return runCatching {
        val decoded = JSONTokener(value ?: "[]").nextValue()
        val array = JSONArray(if (decoded is String) decoded else value ?: "[]")
        buildList {
            for (index in 0 until array.length()) {
                val obj = array.getJSONObject(index)
                val url = obj.optString("url")
                if (!isUsableImageUrl(url)) continue
                add(
                    ImageCandidateData(
                        url = url,
                        referer = referer,
                        userAgent = userAgent,
                        cookie = CookieManager.getInstance().getCookie(url),
                        displayOrder = index,
                        width = obj.optInt("width").takeIf { it > 0 },
                        height = obj.optInt("height").takeIf { it > 0 }
                    )
                )
            }
        }
    }.getOrElse {
        logD("ImageExtractPage") { "parseDomCandidates failed: ${it.message}" }
        emptyList()
    }
}

private fun isLikelyImageRequest(uri: Uri, headers: Map<String, String>): Boolean {
    val url = uri.toString()
    val path = uri.encodedPath.orEmpty().lowercase()
    val accept = headers["Accept"].orEmpty().lowercase()
    val byExt = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif").any { path.endsWith(it) || path.contains("$it?") }
    return (byExt || accept.contains("image/")) && isUsableImageUrl(url)
}

private fun isUsableImageUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val lower = url.lowercase()
    if (lower.startsWith("data:") || lower.startsWith("blob:")) return false
    if (lower.contains("bg_transparency") || lower.contains("transparent") || lower.contains("transparency")) return false
    return !isDecorativeImageUrl(lower)
}

private fun isDecorativeImageUrl(lowerUrl: String): Boolean {
    val fileName = lowerUrl.substringBefore('?').substringBefore('#').substringAfterLast('/')
    if (fileName.isBlank()) return false

    // 只拦截明确的装饰/占位文件名，避免正文图片路径里偶然包含 icon、logo 等词时被误伤。
    if (fileName == "favicon.ico") return true
    return listOf(
        Regex("""(^|[-_.@])sprite([-_.@]|$)"""),
        Regex("""(^|[-_.@])placeholder([-_.@]|$)"""),
        Regex("""(^|[-_.@])blank([-_.@]|$)"""),
        Regex("""(^|[-_.@])favicon([-_.@]|$)"""),
        Regex("""(^|[-_.@])icon([-_.@]|$)"""),
        Regex("""(^|[-_.@])logo([-_.@]|$)"""),
    ).any { it.containsMatchIn(fileName) }
}

private fun openGallery(context: android.content.Context) {
    val intent = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun openFolder(context: android.content.Context) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        type = "image/*"
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.base_general_open_image_folder_chooser))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
