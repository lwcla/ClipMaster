package com.cla.clip.master.ui.page.image

import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.gif.MovieDrawable
import coil3.gif.repeatCount
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.size.Size
import coil3.size.SizeResolver
import com.cla.clip.base.general.R
import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.base.general.dao.ImageExtractItemData
import com.cla.clip.base.general.repository.ImageCandidateData
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.toast
import com.cla.clip.master.utils.ImageFolderOpenHelper
import com.cla.clip.master.utils.ImageFolderOpenHelper.ImageFolderOpenResult
import com.cla.clip.master.ui.widget.ProbeWebView
import com.cla.clip.master.ui.widget.TitleBar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONTokener
import kotlin.coroutines.resume

/** 图片提取自动探测最大等待时间，单位毫秒；超时后进入失败重试，避免 WebView 长时间占用页面。 */
private const val IMAGE_PROBE_TIMEOUT_MS = 20_000L

/** DOM 图片收集前等待页面稳定的时间，避免刚完成加载时懒加载脚本还没把真实图片写入页面。 */
private const val IMAGE_COLLECT_SETTLE_DELAY_MS = 600L

/** 每轮自动滚动后的等待时间，用于给 IntersectionObserver 和滚动监听式懒加载留出触发窗口。 */
private const val IMAGE_COLLECT_STEP_DELAY_MS = 350L

/** 图片探测自动滚动的最大轮数，限制探测耗时和页面脚本执行成本。 */
private const val IMAGE_COLLECT_MAX_ROUNDS = 24

/** 图片预览底部弹窗最多占屏高度比例，避免长图预览完全遮住页面上下文。 */
private const val IMAGE_PREVIEW_SHEET_MAX_HEIGHT_FRACTION = 0.86f

/** 图片预览最小宽高比，防止极端长图把预览区域压得过窄。 */
private const val IMAGE_PREVIEW_MIN_ASPECT_RATIO = 0.2f

/** 图片预览最大宽高比，防止横幅图在底部弹窗中占用过高空白。 */
private const val IMAGE_PREVIEW_MAX_ASPECT_RATIO = 4f

/** 缩略图请求尺寸，单位为像素；只加载小图以降低列表滚动时的内存和网络成本。 */
private const val IMAGE_PREVIEW_THUMBNAIL_SIZE_PX = 420

/** 图片预览底部弹窗形状，固定顶部圆角以保持和 Material 底部弹窗视觉一致。 */
private val IMAGE_PREVIEW_SHEET_SHAPE = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

/** 图片网格缩略图形状，和选择描边共用以避免边框与图片裁剪不一致。 */
private val IMAGE_THUMBNAIL_SHAPE = RoundedCornerShape(8.dp)

/**
 * WebView DOM 图片收集脚本。
 *
 * 脚本会扫描 img/source/video poster/meta/link/CSS 背景、noscript、开放 shadowRoot 和同源 frame，
 * 并在 JS 侧先做 URL 绝对化与去重；Kotlin 侧仍会再次过滤，避免脚本误收集装饰图或不可下载资源。
 */
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

/**
 * WebView 懒加载触发脚本。
 *
 * 通过上下滚动并派发 scroll/resize 事件来触发常见懒加载库；每轮只移动一段距离，降低对页面布局和脚本的冲击。
 */
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

/**
 * 图片提取页面入口。
 *
 * 负责创建 WebView 探测会话、收集 DOM 与网络层图片候选、展示筛选/下载状态，并在下载完成后提供打开相册或图片位置的入口。
 * WebView 生命周期只绑定当前页面，页面退出或提取完成后会主动销毁，避免后台继续加载网页。
 */
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
    var collectJob by remember { mutableStateOf<Job?>(null) }

    /**
     * 清理当前探测用 WebView 和滚动收集任务。
     *
     * 页面退出、重试或提取完成都会调用这里，确保 WebView 不再继续加载网页，也避免协程在销毁后的 WebView 上执行 JS。
     */
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
                onOpen = { outputDir ->
                    // 下载完成后直接打开相册；不再尝试文件夹直达，避免不同系统文件管理器带来的不稳定体验。
                    when (ImageFolderOpenHelper.openDownloadedImageFolder(context, outputDir)) {
                        ImageFolderOpenResult.Gallery -> {
                            Unit
                        }

                        ImageFolderOpenResult.None -> {
                            coroutineScope.launch {
                                context.toast(R.string.base_general_no_available_app_to_open_image_folder)
                            }
                        }
                    }
                },
            )
        }
    }
}

/**
 * 根据图片提取状态切换页面主体内容。
 *
 * 这里把探测中、失败、已提取后的批次状态分支集中起来，外层页面只需要负责 WebView 和导航级状态。
 */
@Composable
private fun ImageExtractContent(
    state: ImageProbeState,
    viewModel: ImageExtractVm,
    onRetry: () -> Unit,
    onOpen: (String?) -> Unit,
) {
    when (state) {
        ImageProbeState.Idle -> Unit
        is ImageProbeState.Probing -> CenterContent {
            LoadingText(stringResource(R.string.base_general_image_extract_loading))
        }

        ImageProbeState.Failed -> CenterContent { FailedText(onRetry) }
        is ImageProbeState.Extracted -> BatchStatusContent(state, viewModel, onRetry, onOpen)
    }
}

/**
 * 居中放置加载、失败、成功等轻量状态内容。
 *
 * 这是页面内复用的布局容器，不持有业务状态，只负责让状态提示在剩余内容区视觉居中。
 */
@Composable
private fun CenterContent(content: @Composable () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        content()
    }
}

/**
 * 展示图片提取批次的后续状态。
 *
 * 批次仍处于已提取状态时进入图片选择网格；一旦 Worker 开始下载，则根据 Room 中的批次状态展示下载进度、
 * 成功统计、部分成功统计或失败重试入口。
 */
@Composable
private fun BatchStatusContent(
    state: ImageProbeState.Extracted,
    viewModel: ImageExtractVm,
    onRetry: () -> Unit,
    onOpen: (String?) -> Unit,
) {
    val batch by viewModel.observeBatch(state.batchId).collectAsState(initial = null)
    val curBatch = batch
    if (curBatch == null || curBatch.status == ImageExtractBatchData.STATUS_EXTRACTED) {
        ImageSelectionContent(
            batchId = state.batchId,
            viewModel = viewModel,
            onConfirmDownload = { selectedIds ->
                viewModel.startDownload(state.batchId, selectedIds)
            }
        )
        return
    }

    CenterContent {
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
                SuccessText(buildImageDownloadResultText(curBatch, includeOpenText = true)) {
                    onOpen(curBatch.outputDir)
                }
            }

            ImageExtractBatchData.STATUS_PARTIAL_SUCCESS -> {
                SuccessText(buildImageDownloadResultText(curBatch, includeOpenText = true)) {
                    onOpen(curBatch.outputDir)
                }
            }

            ImageExtractBatchData.STATUS_FILTERED -> {
                // 全部被过滤时没有任何成功保存的图片，不展示“点击打开”，避免把用户带到空目录或默认相册。
                SuccessText(buildImageDownloadResultText(curBatch, includeOpenText = false))
            }

            ImageExtractBatchData.STATUS_FAILED -> {
                FailedText(onRetry, buildImageDownloadResultText(curBatch, includeOpenText = false))
            }
        }
    }
}

/**
 * 图片下载前的选择界面。
 *
 * 页面会默认选中当前批次全部图片，允许用户在网格或预览弹窗中取消不需要的图片；选择状态只保存在本 Composable
 * 的内存状态中，不写入数据库，直到用户点击确认下载时才提交给 ViewModel。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageSelectionContent(
    batchId: Long,
    viewModel: ImageExtractVm,
    onConfirmDownload: (Set<Long>) -> Unit,
) {
    val context = LocalContext.current
    val items by viewModel.observeItems(batchId).collectAsState(initial = emptyList())
    val selectedIds = remember(batchId) { mutableStateSetOf<Long>() }
    var initializedSelection by remember(batchId) { mutableStateOf(false) }
    var previewItem by remember { mutableStateOf<ImageExtractItemData?>(null) }
    val imageLoader = rememberAnimatedImageLoader(context)

    LaunchedEffect(items) {
        // 新批次首次进入选择页时默认全选；后续 Flow 更新只清理已经不存在的条目，避免覆盖用户手动取消的选择。
        val currentIds = items.map { it.id }.toSet()
        if (!initializedSelection && currentIds.isNotEmpty()) {
            selectedIds.clear()
            selectedIds.addAll(currentIds)
            initializedSelection = true
        } else {
            selectedIds.retainAll(currentIds)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ImageSelectionToolbar(
            selectedCount = selectedIds.size,
            totalCount = items.size,
            onSelectAll = { selectedIds.addAll(items.map { it.id }) },
            onUnselectAll = { selectedIds.clear() },
            onConfirmDownload = { onConfirmDownload(selectedIds.toSet()) }
        )

        if (items.isEmpty()) {
            CenterContent { LoadingText(stringResource(R.string.base_general_image_extract_loading)) }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items = items, key = { it.id }) { item ->
                    val selected = item.id in selectedIds
                    ImageCandidateTile(
                        item = item,
                        selected = selected,
                        imageLoader = imageLoader,
                        onPreview = {
                            viewModel.loadPreviewMeta(item)
                            previewItem = item
                        },
                        onToggleSelected = {
                            if (selected) selectedIds.remove(item.id) else selectedIds.add(item.id)
                        },
                        onDecodedSize = { width, height -> viewModel.updateDecodedSize(item.id, width, height) }
                    )
                }
            }
        }
    }

    val item = previewItem
    if (item != null) {
        val meta = viewModel.previewMetaCache[item.id] ?: ImagePreviewMeta(width = item.width, height = item.height)
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val selected = item.id in selectedIds
        ModalBottomSheet(
            onDismissRequest = { previewItem = null },
            sheetState = sheetState,
            shape = IMAGE_PREVIEW_SHEET_SHAPE
        ) {
            ImagePreviewSheetContent(
                item = item,
                meta = meta,
                selected = selected,
                imageLoader = imageLoader,
                onToggleSelected = {
                    if (selected) selectedIds.remove(item.id) else selectedIds.add(item.id)
                },
                onDecodedSize = { width, height -> viewModel.updateDecodedSize(item.id, width, height) }
            )
        }
    }
}

/**
 * 图片选择页顶部工具栏。
 *
 * 展示已选数量和总数，并提供全选、取消全选、确认下载三个操作；确认按钮在没有选中图片时禁用，避免创建空下载任务。
 */
@Composable
private fun ImageSelectionToolbar(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onUnselectAll: () -> Unit,
    onConfirmDownload: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = stringResource(R.string.base_general_image_select_count, selectedCount, totalCount),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            TextButton(onClick = onSelectAll, enabled = selectedCount < totalCount) {
                Text(stringResource(R.string.base_general_select_all))
            }
            TextButton(onClick = onUnselectAll, enabled = selectedCount > 0) {
                Text(stringResource(R.string.base_general_unselect_all))
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = onConfirmDownload, enabled = selectedCount > 0) {
                Text(stringResource(R.string.base_general_confirm_download_selected_images))
            }
        }
    }
}

/**
 * 图片候选网格项。
 *
 * 主体点击打开预览弹窗，右上角图标独立切换选择状态；缩略图加载成功后把解码尺寸回传给 ViewModel，
 * 供预览弹窗展示更可靠的分辨率。
 */
@Composable
private fun ImageCandidateTile(
    item: ImageExtractItemData,
    selected: Boolean,
    imageLoader: ImageLoader,
    onPreview: () -> Unit,
    onToggleSelected: () -> Unit,
    onDecodedSize: (Int?, Int?) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(IMAGE_THUMBNAIL_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = IMAGE_THUMBNAIL_SHAPE
            )
            .clickable(onClick = onPreview)
    ) {
        AsyncImage(
            model = buildImageRequest(item, preview = false),
            imageLoader = imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onSuccess = { state -> onDecodedSize(state.result.image.width, state.result.image.height) },
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (selected) 1f else 0.42f)
        )

        Icon(
            imageVector = if (selected) Icons.Default.CheckCircleOutline else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(26.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f))
                .clickable(role = Role.Checkbox, onClick = onToggleSelected)
                .padding(2.dp)
        )
    }
}

/**
 * 单张图片预览底部弹窗内容。
 *
 * 弹窗支持长图纵向滚动、动图播放和元信息展示；底部按钮允许在不关闭弹窗的情况下保留或移除当前图片。
 */
@Composable
private fun ImagePreviewSheetContent(
    item: ImageExtractItemData,
    meta: ImagePreviewMeta,
    selected: Boolean,
    imageLoader: ImageLoader,
    onToggleSelected: () -> Unit,
    onDecodedSize: (Int?, Int?) -> Unit,
) {
    val scrollState = rememberScrollState()
    val unknownText = stringResource(R.string.base_general_unknow)
    val resolutionText = formatResolution(meta.width ?: item.width, meta.height ?: item.height, unknownText)
    val fileTypeText = formatMimeType(meta.mimeType, item.url, unknownText)
    val fileSizeText = formatFileSize(meta.contentLength, unknownText)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(IMAGE_PREVIEW_SHEET_MAX_HEIGHT_FRACTION)
            .padding(start = 16.dp, end = 16.dp, bottom = 20.dp)
    ) {
        Text(
            text = stringResource(R.string.base_general_image_preview),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 10.dp)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.TopCenter
            ) {
                AsyncImage(
                    model = buildImageRequest(item, preview = true),
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    onSuccess = { state -> onDecodedSize(state.result.image.width, state.result.image.height) },
                    onError = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp)
                        .then(previewAspectModifier(meta.width ?: item.width, meta.height ?: item.height))
                )
            }
        }

        Column(modifier = Modifier.padding(top = 12.dp)) {
            Text(
                text = stringResource(R.string.base_general_image_resolution, resolutionText),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.base_general_image_file_type, fileTypeText),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = stringResource(R.string.base_general_image_file_size, fileSizeText),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Text(
                    text = item.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedButton(onClick = onToggleSelected) {
                    Text(
                        stringResource(
                            if (selected) R.string.base_general_remove_this_image else R.string.base_general_keep_this_image
                        )
                    )
                }
            }
        }
    }
}

/**
 * 记住支持动图的 Coil ImageLoader。
 *
 * 使用 `remember(context)` 避免每次重组都创建解码器；Android 9 及以上走 ImageDecoder，低版本走 GifDecoder，
 * 保证 GIF 和系统支持的动图格式在缩略图/预览中尽量正常播放。
 */
@Composable
private fun rememberAnimatedImageLoader(context: android.content.Context): ImageLoader {
    return remember(context) {
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory())
                // API 28+ 的 ImageDecoder 支持 GIF、Animated WebP 和 Animated HEIF；低版本用 Movie 解 GIF。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(true)
            .repeatCount(MovieDrawable.REPEAT_INFINITE)
            .build()
    }
}

/**
 * 构建预览和缩略图共用的图片请求。
 *
 * 缩略图请求限制尺寸以节省资源，预览请求保留原始尺寸以便查看长图和动图；两者都会携带反盗链请求头，
 * 避免 UI 预览和实际下载表现不一致。
 */
@Composable
private fun buildImageRequest(item: ImageExtractItemData, preview: Boolean): ImageRequest {
    val context = LocalContext.current
    val size = if (preview) {
        SizeResolver.ORIGINAL
    } else {
        SizeResolver(Size(IMAGE_PREVIEW_THUMBNAIL_SIZE_PX, IMAGE_PREVIEW_THUMBNAIL_SIZE_PX))
    }
    return remember(item.id, item.url, item.referer, item.userAgent, item.cookie, preview) {
        ImageRequest.Builder(context)
            .data(item.url)
            .size(size)
            .allowHardware(false)
            .httpHeaders(buildNetworkHeaders(item))
            .build()
    }
}

/**
 * 构建图片加载请求头。
 *
 * Referer、User-Agent 和 Cookie 来自 WebView 探测时记录的上下文，缺失时不强行补默认值，避免给站点发送误导性头信息。
 */
private fun buildNetworkHeaders(item: ImageExtractItemData): NetworkHeaders {
    return NetworkHeaders.Builder().apply {
        val referer = item.referer
        val userAgent = item.userAgent
        val cookie = item.cookie
        if (!referer.isNullOrBlank()) set("Referer", referer)
        if (!userAgent.isNullOrBlank()) set("User-Agent", userAgent)
        if (!cookie.isNullOrBlank()) set("Cookie", cookie)
    }.build()
}

/**
 * 根据图片尺寸生成预览宽高比约束。
 *
 * 未知尺寸时只限制最大高度；已知尺寸时把极端比例夹在可接受范围内，避免超长图或横幅图破坏底部弹窗布局。
 */
private fun previewAspectModifier(width: Int?, height: Int?): Modifier {
    if (width == null || height == null || width <= 0 || height <= 0) {
        return Modifier.heightIn(max = 560.dp)
    }
    val aspectRatio = (width.toFloat() / height.toFloat())
        .coerceIn(IMAGE_PREVIEW_MIN_ASPECT_RATIO, IMAGE_PREVIEW_MAX_ASPECT_RATIO)
    return Modifier.aspectRatio(aspectRatio, matchHeightConstraintsFirst = false)
}

/**
 * 格式化图片分辨率展示文案。
 *
 * 只有宽高都有效时才显示像素尺寸，否则显示“未知”，避免把 0 或缺失值误导性展示给用户。
 */
@Composable
private fun formatResolution(width: Int?, height: Int?, unknownText: String): String {
    return if (width != null && height != null && width > 0 && height > 0) {
        stringResource(R.string.base_general_image_resolution_value, width, height)
    } else {
        unknownText
    }
}

/**
 * 格式化图片类型展示文案。
 *
 * 优先使用响应头 MIME；缺失时从 URL 后缀推断，并把 `image/jpeg`、`svg+xml` 等技术值转换成用户更容易识别的格式名。
 */
private fun formatMimeType(mimeType: String?, url: String, unknownText: String): String {
    val type = mimeType?.substringAfter("image/", missingDelimiterValue = mimeType)?.uppercase()
        ?: url.substringBefore("?").substringBefore("#").substringAfterLast('.', "").uppercase().takeIf { it.isNotBlank() }
    return when (type) {
        "JPEG" -> "JPG"
        "SVG+XML" -> "SVG"
        null -> unknownText
        else -> type
    }
}

/**
 * 格式化图片体积展示文案。
 *
 * 体积单位根据字节数自动在 B、KB、MB、GB 之间切换；为空或非正数时显示“未知”，因为预览阶段不会完整下载图片来强算体积。
 */
@Composable
private fun formatFileSize(bytes: Long?, unknownText: String): String {
    if (bytes == null || bytes <= 0L) return unknownText
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> stringResource(R.string.base_general_file_size_gb, gb)
        mb >= 1 -> stringResource(R.string.base_general_file_size_mb, mb)
        kb >= 1 -> stringResource(R.string.base_general_file_size_kb, kb)
        else -> stringResource(R.string.base_general_file_size_bytes, bytes)
    }
}

/**
 * 组装图片下载结果文案。
 *
 * 按成功、过滤、失败数量拼接批次结果，并可选追加“点击打开”提示；统一使用字符串资源，避免 UI 层硬编码中文。
 */
@Composable
private fun buildImageDownloadResultText(batch: ImageExtractBatchData, includeOpenText: Boolean): String {
    val parts = buildList {
        if (batch.successCount > 0) add(stringResource(R.string.base_general_image_saved_count, batch.successCount))
        if (batch.filteredCount > 0) add(stringResource(R.string.base_general_image_filtered_count, batch.filteredCount))
        if (batch.failedCount > 0) add(stringResource(R.string.base_general_image_failed_count, batch.failedCount))
        if (isEmpty()) add(stringResource(R.string.base_general_image_download_failed))
    }
    val separator = stringResource(R.string.base_general_text_separator)
    return parts.joinToString(separator) + if (includeOpenText) separator + stringResource(R.string.base_general_image_open_suffix) else ""
}

/**
 * 带进度圈的加载提示。
 *
 * 用于图片提取中和批量下载中两个场景，文案由调用方传入，避免在通用组件里耦合具体业务状态。
 */
@Composable
private fun LoadingText(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(25.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

/**
 * 可点击的成功提示。
 *
 * 用于下载完成、部分完成或过滤完成后的结果展示；只有存在可查看下载内容时才传入 onOpen 让外层打开相册/文件查看入口。
 */
@Composable
private fun SuccessText(text: String, onOpen: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier
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

/**
 * 可点击的失败提示。
 *
 * 默认用于图片提取失败重试，也可传入批量下载失败统计文案；点击后由调用方决定是重新探测还是重新展示当前批次。
 */
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

/**
 * 将 WebView 网络请求转换成图片候选。
 *
 * 只有疑似图片请求才会保留；请求头优先使用本次网络请求自带值，缺失时回退到页面 URL 和 WebView User-Agent，
 * 确保后续预览和下载尽量复用同一反盗链上下文。
 */
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

/**
 * 等待页面稳定并自动滚动触发懒加载，最后再执行 DOM 图片收集脚本。
 *
 * 这个函数运行在页面的协程作用域中，按顺序执行滚动、等待、回到顶部和 DOM 收集，避免并发 JS 调用导致返回结果交错。
 */
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

/**
 * 将 WebView 的回调式 JS 执行封装成挂起函数。
 *
 * 调用方可以用顺序代码组织滚动和收集流程；协程取消后如果回调才返回，会通过 `isActive` 避免恢复已取消的 continuation。
 */
private suspend fun WebView.evaluateJavascriptAwait(script: String): String? {
    return suspendCancellableCoroutine { continuation ->
        evaluateJavascript(script) { result ->
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }
    }
}

/**
 * 解析 DOM 收集脚本返回的 JSON，并转换成可落库的图片候选。
 *
 * WebView `evaluateJavascript` 可能返回双重编码字符串，因此先用 JSONTokener 解码；异常时返回空列表，
 * 让网络拦截候选仍有机会补足结果。
 */
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

/**
 * 判断 WebView 网络请求是否像图片资源。
 *
 * 规则同时参考路径扩展名和 Accept 请求头，并复用可用图片过滤，避免把占位图、透明图或 data/blob URL 加入候选。
 */
private fun isLikelyImageRequest(uri: Uri, headers: Map<String, String>): Boolean {
    val url = uri.toString()
    val path = uri.encodedPath.orEmpty().lowercase()
    val accept = headers["Accept"].orEmpty().lowercase()
    val byExt = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif").any { path.endsWith(it) || path.contains("$it?") }
    return (byExt || accept.contains("image/")) && isUsableImageUrl(url)
}

/**
 * 判断图片 URL 是否适合展示和下载。
 *
 * 过滤空地址、data/blob 内联资源、透明背景图和明确的装饰资源；保留判断尽量克制，避免误删正文图片。
 */
private fun isUsableImageUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val lower = url.lowercase()
    if (lower.startsWith("data:") || lower.startsWith("blob:")) return false
    if (lower.contains("bg_transparency") || lower.contains("transparent") || lower.contains("transparency")) return false
    return !isDecorativeImageUrl(lower)
}

/**
 * 判断 URL 文件名是否属于明显装饰图。
 *
 * 只匹配文件名中的 sprite、placeholder、blank、favicon、icon、logo 等明确模式，避免路径目录中出现这些词时误过滤正文图。
 */
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
