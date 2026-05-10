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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.base.general.entity.ImageCandidateData
import com.cla.clip.base.general.utils.logD
import com.cla.clip.master.ui.widget.ProbeWebView
import com.cla.clip.master.ui.widget.TitleBar
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONTokener

private const val IMAGE_PROBE_TIMEOUT_MS = 10_000L

private const val COLLECT_IMAGES_JS = """
(function() {
  function abs(u) {
    try { return new URL(u, document.baseURI).href; } catch(e) { return ""; }
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
  var seen = {};
  var out = [];
  function push(url, w, h, source) {
    url = abs(url);
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
      img.getAttribute("data-lazy") ||
      "";
  }
  document.querySelectorAll("img").forEach(function(img) {
    // 优先读取懒加载真实地址，WEBTOON 等站点会把 src 放成透明占位图。
    push(lazyUrl(img) || img.currentSrc || img.src || bestFromSrcset(img.getAttribute("srcset")), img.naturalWidth || img.width, img.naturalHeight || img.height, "img");
  });
  document.querySelectorAll("source[srcset]").forEach(function(source) {
    push(bestFromSrcset(source.getAttribute("srcset")), null, null, "source");
  });
  document.querySelectorAll("*").forEach(function(el) {
    var bg = window.getComputedStyle(el).getPropertyValue("background-image");
    if (!bg || bg === "none") return;
    var matches = bg.match(/url\(["']?([^"')]+)["']?\)/g) || [];
    matches.forEach(function(item) {
      var m = item.match(/url\(["']?([^"')]+)["']?\)/);
      if (m && m[1]) push(m[1], el.offsetWidth, el.offsetHeight, "background");
    });
  });
  return JSON.stringify(out);
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
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var probeUserAgent by remember { mutableStateOf<String?>(null) }
    var showOpenDialog by remember { mutableStateOf(false) }

    fun clearWebView() {
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
            TitleBar("图片提取", onBack)

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
                        // 页面加载完成后从 DOM 收集图片，DOM 顺序才是最终命名顺序的基准。
                        view.evaluateJavascript(COLLECT_IMAGES_JS) { json ->
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
                    Text("打开相册")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showOpenDialog = false
                    openFolder(context)
                }) {
                    Text("打开文件夹")
                }
            },
            title = { Text("打开图片") },
            text = { Text("选择查看已保存图片的位置") }
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
            is ImageProbeState.Probing -> LoadingText("正在提取网页图片...")
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
        Text("已提取到 ${state.count} 张图片", style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = {
                // 用户确认后才开启批量下载，避免仅预览提取结果时消耗存储空间。
                viewModel.startDownload(state.batchId)
            },
            modifier = Modifier.padding(top = 12.dp)
        ) {
            Text("下载全部图片")
        }
        return
    }

    when (curBatch.status) {
        ImageExtractBatchData.STATUS_DOWNLOADING -> {
            LoadingText("正在下载 ${curBatch.successCount + curBatch.failedCount}/${curBatch.totalCount}")
        }

        ImageExtractBatchData.STATUS_SUCCESS -> {
            SuccessText("已保存 ${curBatch.successCount} 张图片，点击打开", onOpen)
        }

        ImageExtractBatchData.STATUS_PARTIAL_SUCCESS -> {
            SuccessText("已保存 ${curBatch.successCount} 张图片，失败 ${curBatch.failedCount} 张，点击打开", onOpen)
        }

        ImageExtractBatchData.STATUS_FAILED -> {
            FailedText(onRetry, "下载失败，失败 ${curBatch.failedCount} 张")
        }
    }
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
private fun FailedText(onRetry: () -> Unit, text: String = "图片提取失败，点击重试") {
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
    val blocked = listOf("favicon", "sprite", "placeholder", "blank", "icon", "logo")
    return blocked.none { lower.contains(it) }
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
    runCatching { context.startActivity(Intent.createChooser(intent, "打开图片文件夹").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
