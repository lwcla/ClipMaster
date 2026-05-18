package com.cla.clip.master.ui.page.image

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
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
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/** 探测开始后连续没有任何有效图片候选的最大等待时间，单位毫秒；超过后进入失败流程。 */
private const val IMAGE_NO_CANDIDATE_TIMEOUT_MS = 10_000L

/** 自动完成前至少等待 DOM 出现有效图片证据的窗口，避免页面初始壳触底后仅凭网络占位图落库。 */
private const val IMAGE_EMPTY_DOM_WAIT_MS = IMAGE_NO_CANDIDATE_TIMEOUT_MS

/** 长页面首次触底前的提示时间，单位毫秒；只提示用户图片较多，不主动停止探测。 */
private const val IMAGE_LONG_RUNNING_HINT_MS = 60_000L

/** DOM 图片收集前等待页面稳定的时间，避免刚完成加载时懒加载脚本还没把真实图片写入页面。 */
private const val IMAGE_COLLECT_SETTLE_DELAY_MS = 600L

/** 每轮自动滚动后的等待时间，用于给 IntersectionObserver 和滚动监听式懒加载留出触发窗口。 */
private const val IMAGE_COLLECT_STEP_DELAY_MS = 350L

/** 双轮滚动中允许从底部回到顶部重试的次数，固定一次以避免无限上下循环。 */
private const val IMAGE_COLLECT_MAX_SCROLL_PASSES = 2

/** 图片预览底部弹窗最多占屏高度比例，避免长图预览完全遮住页面上下文。 */
private const val IMAGE_PREVIEW_SHEET_MAX_HEIGHT_FRACTION = 0.86f

/** 图片预览最小宽高比，防止极端长图把预览区域压得过窄。 */
private const val IMAGE_PREVIEW_MIN_ASPECT_RATIO = 0.2f

/** 图片预览最大宽高比，防止横幅图在底部弹窗中占用过高空白。 */
private const val IMAGE_PREVIEW_MAX_ASPECT_RATIO = 4f

/** 缩略图请求尺寸，单位为像素；只加载小图以降低列表滚动时的内存和网络成本。 */
private const val IMAGE_PREVIEW_THUMBNAIL_SIZE_PX = 420

/** 图片候选网格固定列数；提取页按用户筛选效率固定一行四张，避免自适应列宽在不同状态下跳动。 */
private const val IMAGE_CANDIDATE_GRID_COLUMNS = 4

/** 图片请求 Accept，尽量贴近浏览器图片加载，避免预览和 Worker 因内容协商差异拿到不同动静态版本。 */
private const val IMAGE_REQUEST_ACCEPT = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"

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
      img.getAttribute("data-actualsrc") ||
      img.getAttribute("data-original") ||
      img.getAttribute("data-original-src") ||
      img.getAttribute("data-original-url") ||
      img.getAttribute("data-full-src") ||
      img.getAttribute("data-fullsrc") ||
      img.getAttribute("data-default-watermark-src") ||
      img.getAttribute("data-watermark-src") ||
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
      "data-actualsrc",
      "data-original",
      "data-original-src",
      "data-original-url",
      "data-full-src",
      "data-fullsrc",
      "data-default-watermark-src",
      "data-watermark-src",
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

  function collectBiliOpusStateImages() {
    try {
      var state = window.__INITIAL_STATE__ || {};
      var modules = (((state.opus || {}).detail || {}).modules || []);
      modules.forEach(function(module) {
        var paragraphs = (((module || {}).module_content || {}).paragraphs || []);
        paragraphs.forEach(function(paragraph) {
          var pics = (((paragraph || {}).pic || {}).pics || []);
          pics.forEach(function(pic) {
            // B 站动态页的正文原图常在初始状态中，页面渲染层可能只暴露带 @ 样式后缀的预览地址。
            push(pic && pic.url, pic && pic.width, pic && pic.height, "state:bili-opus-pic", document.baseURI);
            push(pic && pic.live_url, pic && pic.width, pic && pic.height, "state:bili-opus-live", document.baseURI);
          });
        });
      });
    } catch(e) {
      // 只作为站点增强，失败时继续使用通用 DOM/网络候选。
    }
  }

  function collectFromStateObject(value, source, depth) {
    if (depth > 8 || value == null) return;
    if (typeof value === "string") {
      if (looksLikeImageUrl(value)) {
        push(value, null, null, source, document.baseURI);
      }
      return;
    }
    if (typeof value !== "object") return;
    if (Array.isArray(value)) {
      value.slice(0, 800).forEach(function(item) {
        collectFromStateObject(item, source, depth + 1);
      });
      return;
    }
    Object.keys(value).slice(0, 800).forEach(function(key) {
      collectFromStateObject(value[key], source + ":" + key, depth + 1);
    });
  }

  function collectGenericInitialStateImages() {
    [
      "__INITIAL_STATE__",
      "__NEXT_DATA__",
      "__NUXT__",
      "__APOLLO_STATE__",
      "__RELAY_STORE__",
      "__PRELOADED_STATE__",
      "__INITIAL_DATA__",
      "__DATA__"
    ].forEach(function(name) {
      try {
        collectFromStateObject(window[name], "state:" + name, 0);
      } catch(e) {
      }
    });
  }

  function collectInlineScriptImageUrls() {
    var literalRe = /(?:https?:)?\/\/[^"'\s<>]+?(?:\.(?:jpe?g|png|webp|gif|avif|bmp|svg)(?:@[^"'\s<>]+)?)(?:[?#][^"'\s<>]*)?/ig;
    document.querySelectorAll("script:not([src])").forEach(function(script) {
      var text = (script.textContent || "").replace(/\\u002F/ig, "/").replace(/\\\//g, "/").replace(/&amp;/g, "&");
      if (!text || text.length > 1500000) return;
      var match;
      while ((match = literalRe.exec(text)) !== null) {
        var url = match[0];
        push(url, null, null, "script:inline-url", document.baseURI);
      }
    });
  }

  collectGenericInitialStateImages();
  collectBiliOpusStateImages();
  collectInlineScriptImageUrls();
  collectRoot(document, document.baseURI);
  return JSON.stringify(out);
})()
"""

/**
 * WebView 轻量 DOM 图片收集脚本。
 *
 * 滚动过程中高频执行时只扫描常见图片标签和懒加载属性，不遍历所有 CSS 背景和 shadowRoot；触底后的完整性判断仍会使用
 * `COLLECT_IMAGES_JS` 做完整快照，平衡进度反馈及时性和页面脚本执行成本。
 */
private const val COLLECT_LIGHT_IMAGES_JS = """
(function() {
  var seen = {};
  var out = [];

  function abs(u, base) {
    try {
      u = String(u || "").trim().replace(/&amp;/g, "&");
      if (!u || u === "#" || /^javascript:/i.test(u)) return "";
      return new URL(u, base || document.baseURI).href;
    } catch(e) {
      return "";
    }
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

  function push(url, w, h, source, base) {
    url = abs(url, base);
    if (!url || seen[url]) return;
    seen[url] = true;
    out.push({ url: url, width: w || null, height: h || null, source: source });
  }

  function firstAttr(el, names) {
    for (var i = 0; i < names.length; i++) {
      var value = el.getAttribute(names[i]);
      if (value) return value;
    }
    return "";
  }

  var lazyAttrs = [
    "data-url",
    "data-src",
    "data-actualsrc",
    "data-original",
    "data-original-src",
    "data-original-url",
    "data-full-src",
    "data-fullsrc",
    "data-default-watermark-src",
    "data-watermark-src",
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
    "data-thumbnail"
  ];
  var lazySrcsetAttrs = ["data-srcset", "data-lazy-srcset", "data-original-srcset", "data-lazyset"];

  document.querySelectorAll("img").forEach(function(img) {
    push(firstAttr(img, lazyAttrs), img.naturalWidth || img.width, img.naturalHeight || img.height, "img:lazy", document.baseURI);
    push(bestFromSrcset(firstAttr(img, lazySrcsetAttrs)), img.naturalWidth || img.width, img.naturalHeight || img.height, "img:lazy-srcset", document.baseURI);
    push(bestFromSrcset(img.getAttribute("srcset")), img.naturalWidth || img.width, img.naturalHeight || img.height, "img:srcset", document.baseURI);
    push(img.currentSrc, img.naturalWidth || img.width, img.naturalHeight || img.height, "img:currentSrc", document.baseURI);
    push(img.src, img.naturalWidth || img.width, img.naturalHeight || img.height, "img:src", document.baseURI);
  });

  document.querySelectorAll("source").forEach(function(source) {
    push(bestFromSrcset(firstAttr(source, lazySrcsetAttrs)), null, null, "source:lazy-srcset", document.baseURI);
    push(bestFromSrcset(source.getAttribute("srcset")), null, null, "source:srcset", document.baseURI);
    push(source.getAttribute("src"), null, null, "source:src", document.baseURI);
  });

  document.querySelectorAll("video[poster]").forEach(function(video) {
    push(video.getAttribute("poster"), video.offsetWidth, video.offsetHeight, "video:poster", document.baseURI);
  });

  document.querySelectorAll("[data-url], [data-src], [data-actualsrc], [data-original], [data-original-src], [data-original-url], [data-full-src], [data-fullsrc], [data-default-watermark-src], [data-watermark-src], [data-lazy-src], [data-bg], [data-background], [data-image], [data-img], [data-thumb], [data-thumbnail]").forEach(function(el) {
    push(firstAttr(el, lazyAttrs), el.offsetWidth, el.offsetHeight, "attr:lazy", document.baseURI);
  });

  document.querySelectorAll("meta[property*='image'], meta[name*='image'], meta[itemprop*='image']").forEach(function(meta) {
    push(meta.getAttribute("content"), null, null, "meta:image", document.baseURI);
  });

  document.querySelectorAll("link[rel~='preload'][as='image'], link[rel='image_src'], link[imagesrcset]").forEach(function(link) {
    push(link.getAttribute("href"), null, null, "link:image", document.baseURI);
    push(bestFromSrcset(link.getAttribute("imagesrcset")), null, null, "link:imagesrcset", document.baseURI);
  });

  return JSON.stringify(out);
})()
"""

/**
 * WebView 懒加载触发脚本。
 *
 * 每次只向下滚动一段距离并返回是否已经触底；触底后的回顶重试由 Kotlin 控制，避免页面在 JS 内部无限上下循环。
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

  var current = window.scrollY || window.pageYOffset || 0;
  var step = Math.max(300, Math.floor(viewport * 0.85));
  var next = Math.min(maxScroll, current + step);

  window.scrollTo(0, next);
  // 派发滚动/缩放事件，兼容只监听事件而不依赖 IntersectionObserver 的懒加载库。
  window.dispatchEvent(new Event("scroll"));
  window.dispatchEvent(new Event("resize"));
  return JSON.stringify({ y: next, max: maxScroll, atBottom: next >= maxScroll - 2 });
})()
"""

/**
 * 读取当前滚动状态脚本。
 *
 * 触底后页面可能因为懒加载追加内容而变高，因此滚动等待后需要用这个只读脚本复核一次，避免把旧高度误判成真正到底。
 */
private const val IMAGE_SCROLL_STATUS_JS = """
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
  var current = window.scrollY || window.pageYOffset || 0;
  return JSON.stringify({ y: current, max: maxScroll, atBottom: current >= maxScroll - 2 });
})()
"""

/**
 * WebView 回到顶部脚本。
 *
 * 第一轮触底但仍发现懒加载占位时使用；只重置滚动位置，不清空已捕获候选。
 */
private const val IMAGE_SCROLL_TOP_JS = """
(function() {
  window.scrollTo(0, 0);
  window.dispatchEvent(new Event("scroll"));
  window.dispatchEvent(new Event("resize"));
  return JSON.stringify({ y: window.scrollY || window.pageYOffset || 0 });
})()
"""

/**
 * 当前 DOM 图片完整性快照脚本。
 *
 * 复用完整图片收集脚本得到可解析 URL 数量，同时统计仍带有常见懒加载属性但没有可解析 URL 的占位元素；
 * 该占位数量只作为“再滚一轮”的启发式信号，不承诺动态页面的绝对完整性。
 */
private const val IMAGE_DOM_STATUS_JS = """
(function() {
  var imageExtRe = /\.(?:jpe?g|png|webp|gif|avif|bmp|svg)(?:[?#].*)?/i;
  function attr(el, name) {
    return (el.getAttribute(name) || "").trim();
  }
  function hasLikelyLazyMarker(el) {
    return attr(el, "data-src") || attr(el, "data-actualsrc") || attr(el, "data-original") || attr(el, "data-original-src") || attr(el, "data-original-url") ||
      attr(el, "data-full-src") || attr(el, "data-fullsrc") || attr(el, "data-default-watermark-src") || attr(el, "data-watermark-src") ||
      attr(el, "data-lazy-src") || attr(el, "data-lazyload") || attr(el, "data-lazy") ||
      attr(el, "data-srcset") || attr(el, "data-lazy-srcset") || attr(el, "data-original-srcset") ||
      attr(el, "data-bg") || attr(el, "data-background") || attr(el, "data-image") ||
      attr(el, "data-img") || attr(el, "data-thumb") || attr(el, "data-thumbnail");
  }
  function hasUsableUrl(el) {
    return imageExtRe.test(attr(el, "src")) || imageExtRe.test(attr(el, "srcset")) ||
      imageExtRe.test(attr(el, "currentSrc")) || imageExtRe.test(attr(el, "href")) ||
      imageExtRe.test(attr(el, "poster")) || imageExtRe.test(hasLikelyLazyMarker(el));
  }
  var unresolved = 0;
  document.querySelectorAll("img, source, video[poster], [data-src], [data-actualsrc], [data-original], [data-original-src], [data-original-url], [data-full-src], [data-fullsrc], [data-default-watermark-src], [data-watermark-src], [data-lazy-src], [data-srcset], [data-bg], [data-background], [data-image], [data-img], [data-thumb], [data-thumbnail]").forEach(function(el) {
    if (hasLikelyLazyMarker(el) && !hasUsableUrl(el)) unresolved += 1;
  });
  return JSON.stringify({ unresolvedLazy: unresolved });
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

    /** 当前隐藏探测 WebView 的引用，只在页面生命周期内有效，用于重试、失败、完成和退出时主动销毁。 */
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    /** WebView User-Agent 缓存，供后台网络拦截回调构造候选下载上下文，避免在非主线程直接读取 WebView 设置。 */
    var probeUserAgent by remember { mutableStateOf<String?>(null) }

    /** 当前滚动和 DOM 扫描协程；页面退出、重试、手动结束或失败时会取消，避免销毁后继续执行 JS。 */
    var collectJob by remember { mutableStateOf<Job?>(null) }

    /** 是否展示长耗时提示；只有超过 60 秒、尚未首次触底且已有候选时才置为 true。 */
    var showLongRunningHint by remember { mutableStateOf(false) }

    /** 当前会话是否已经首次触底，用于保证 60 秒提示只覆盖“仍未首次触底”的长页面场景。 */
    var hasReachedFirstBottom by remember { mutableStateOf(false) }

    /** 是否正在停止探测；停止期间禁用重复点击和重新提取，避免 WebView 销毁和候选快照发布重复执行。 */
    var isStoppingExtract by remember { mutableStateOf(false) }

    /** 是否正在提交下载；提交期间禁用重复点击和重新提取，避免重复创建批次。 */
    var isSubmittingDownload by remember { mutableStateOf(false) }

    /** 是否展示重新提取确认弹窗；已有候选未下载时需要先提醒会清空当前选择。 */
    var showRetryConfirm by remember { mutableStateOf(false) }

    /**
     * 停止当前探测用 WebView。
     *
     * 页面退出、重试或提取完成都会调用这里，确保 WebView 不再继续加载网页；是否取消滚动协程由调用方决定，
     * 避免收集协程内部收尾时把自己取消掉。
     */
    fun destroyProbeWebView(closeCandidateUpdates: Boolean = true) {
        (imageExtractVm.probeState as? ImageProbeState.Probing)?.let { state ->
            if (closeCandidateUpdates) {
                imageExtractVm.closeCandidateUpdates(state.sessionId)
            }
        }
        webViewRef?.webChromeClient = null
        webViewRef?.stopLoading()
        webViewRef?.clearHistory()
        webViewRef?.destroy()
        webViewRef = null
    }

    /**
     * 清理当前探测用 WebView 和滚动收集任务。
     *
     * 用户返回、重新提取或失败重试时使用；主动取消协程后也关闭候选写入通道，避免迟到回调污染新会话。
     */
    fun clearWebView() {
        collectJob?.cancel()
        collectJob = null
        destroyProbeWebView(closeCandidateUpdates = true)
    }

    DisposableEffect(Unit) {
        onDispose { clearWebView() }
    }

    LaunchedEffect(imageExtractVm.sessionId) {
        if (imageExtractVm.probeState is ImageProbeState.Extracted) return@LaunchedEffect
        collectJob?.cancel()
        collectJob = null
        showLongRunningHint = false
        hasReachedFirstBottom = false
        isStoppingExtract = false
        isSubmittingDownload = false
        showRetryConfirm = false
        imageExtractVm.resetProbeSession(imageExtractVm.sessionId)
        imageExtractVm.probeState = ImageProbeState.Probing(imageExtractVm.sessionId)
        webViewRef?.stopLoading()
        webViewRef?.clearHistory()
        webViewRef?.loadUrl(pageUrl)
    }

    LaunchedEffect(imageExtractVm.probeState) {
        val state = imageExtractVm.probeState
        if (state is ImageProbeState.Probing) {
            delay(IMAGE_NO_CANDIDATE_TIMEOUT_MS)
            if (imageExtractVm.probeState == state && imageExtractVm.snapshotProbingCandidates().isEmpty()) {
                clearWebView()
                imageExtractVm.failProbeIfActive(state.sessionId)
            }
        }
    }

    LaunchedEffect(imageExtractVm.probeState, showLongRunningHint) {
        val state = imageExtractVm.probeState
        if (state is ImageProbeState.Probing && !showLongRunningHint) {
            delay(IMAGE_LONG_RUNNING_HINT_MS)
            if (
                imageExtractVm.probeState == state &&
                !hasReachedFirstBottom &&
                imageExtractVm.snapshotProbingCandidates().isNotEmpty()
            ) {
                showLongRunningHint = true
            }
        }
    }

    // TitleBar 内部已经处理状态栏高度；放入 Scaffold.topBar 后，内容区只避让标题栏总高度，避免再叠加一份默认顶部安全区。
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TitleBar(stringResource(R.string.base_general_image_extract_title), onBack)
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            val state = imageExtractVm.probeState
            if (state is ImageProbeState.Probing) {
                ProbeWebView(
                    targetUrl = pageUrl,
                    // 探测 WebView 需要保持完整视口来触发真实懒加载和滚动扫描，但它只是后台探测层，不能在 Column 中占位把加载态挤到屏幕底部。
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0f),
                    consumeUserTouch = true,
                    onWebViewReady = {
                        webViewRef = it
                        // UserAgent 只能在 WebView 所在线程读取，后续后台拦截回调只使用这个缓存值。
                        probeUserAgent = it.settings.userAgentString
                    },
                    onPageFinished = { view, _ ->
                        collectJob?.cancel()
                        val sessionId = state.sessionId
                        collectJob = coroutineScope.launch {
                            // 页面刚完成时很多懒加载图还没写入 DOM，双轮滚动会尽量触发懒加载，同时避免无限上下循环。
                            val candidates = collectImagesWithDoublePass(
                                view = view,
                                referer = view.url ?: pageUrl,
                                userAgent = view.settings.userAgentString,
                                viewModel = imageExtractVm,
                                sessionId = sessionId,
                                onFirstBottomReached = {
                                    hasReachedFirstBottom = true
                                    showLongRunningHint = false
                                },
                            )
                            // WebView 已经按真实浏览器上下文加载页面，这里顺手补齐列表链接预览；失败不会影响图片候选保存。
                            imageExtractVm.saveWebViewLinkPreview(
                                webView = view,
                                pageUrl = pageUrl,
                                fallbackImageUrl = candidates.firstOrNull()?.url
                            )
                            if (candidates.isEmpty()) {
                                // 自动完成必须至少看到当前 DOM 中的有效图片；否则网络层孤立占位图不应直接进入选择页。
                                imageExtractVm.failProbeIfActive(sessionId)
                            } else {
                                val snapshot = imageExtractVm.snapshotProbingCandidates()
                                imageExtractVm.publishProbingCandidatesImmediately(sessionId, snapshot)
                                imageExtractVm.markProbeReadyIfActive(sessionId)
                            }
                            collectJob = null
                            destroyProbeWebView(closeCandidateUpdates = false)
                        }
                    },
                    shouldInterceptRequest = { _, request ->
                        val candidate = request.toNetworkCandidate(pageUrl, probeUserAgent)
                        if (candidate != null) {
                            val snapshot = imageExtractVm.addNetworkCandidate(state.sessionId, candidate)
                            if (snapshot != null) {
                                coroutineScope.launch {
                                    imageExtractVm.publishProbingCandidates(state.sessionId, snapshot)
                                }
                            }
                        }
                        null
                    }
                )
            }

            ImageExtractContent(
                state = state,
                viewModel = imageExtractVm,
                showLongRunningHint = showLongRunningHint,
                isStoppingExtract = isStoppingExtract,
                isSubmittingDownload = isSubmittingDownload,
                onRetry = {
                    if (isStoppingExtract || isSubmittingDownload) return@ImageExtractContent
                    if (
                        imageExtractVm.probingCandidates.isNotEmpty() &&
                        (imageExtractVm.probeState is ImageProbeState.Probing ||
                            imageExtractVm.probeState is ImageProbeState.ReadyToDownload)
                    ) {
                        showRetryConfirm = true
                    } else {
                        clearWebView()
                        imageExtractVm.sessionId += 1
                    }
                },
                onStopExtract = {
                    if (isStoppingExtract || isSubmittingDownload) return@ImageExtractContent
                    val sessionId = (imageExtractVm.probeState as? ImageProbeState.Probing)?.sessionId
                        ?: return@ImageExtractContent
                    isStoppingExtract = true
                    collectJob?.cancel()
                    collectJob = null
                    val candidates = imageExtractVm.snapshotProbingCandidates()
                    val view = webViewRef
                    if (view != null) {
                        coroutineScope.launch {
                            // 用户手动停止只冻结当前候选并补齐链接预览，不创建下载批次；下载必须等待下一次明确点击。
                            imageExtractVm.saveWebViewLinkPreview(
                                webView = view,
                                pageUrl = pageUrl,
                                fallbackImageUrl = candidates.firstOrNull()?.url
                            )
                            imageExtractVm.publishProbingCandidatesImmediately(sessionId, candidates)
                            imageExtractVm.markProbeReadyIfActive(sessionId)
                            destroyProbeWebView(closeCandidateUpdates = false)
                            isStoppingExtract = false
                        }
                    } else {
                        imageExtractVm.publishProbingCandidatesImmediately(sessionId, candidates)
                        imageExtractVm.markProbeReadyIfActive(sessionId)
                        destroyProbeWebView(closeCandidateUpdates = false)
                        isStoppingExtract = false
                    }
                },
                onConfirmDownload = { selectedKeys ->
                    if (isSubmittingDownload || selectedKeys.isEmpty()) return@ImageExtractContent
                    if (imageExtractVm.probeState is ImageProbeState.Probing) return@ImageExtractContent
                    isSubmittingDownload = true
                    coroutineScope.launch {
                        val success = imageExtractVm.createBatchAndStartDownload(pageUrl, pageName, selectedKeys)
                        if (!success) {
                            isSubmittingDownload = false
                            context.toast(R.string.base_general_failed_to_create_the_download_task)
                        } else {
                            isSubmittingDownload = false
                        }
                    }
                },
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

            if (showRetryConfirm) {
                RetryExtractConfirmDialog(
                    onDismiss = { showRetryConfirm = false },
                    onConfirm = {
                        showRetryConfirm = false
                        clearWebView()
                        imageExtractVm.sessionId += 1
                    }
                )
            }
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
    showLongRunningHint: Boolean,
    isStoppingExtract: Boolean,
    isSubmittingDownload: Boolean,
    onRetry: () -> Unit,
    onStopExtract: () -> Unit,
    onConfirmDownload: (Set<String>) -> Unit,
    onOpen: (String?) -> Unit,
) {
    // 探测中已有候选和自动完成待确认共用同一个调用位置，避免状态分支切换时重建实时网格，
    // 导致用户取消的选择、预览弹窗状态和 LazyVerticalGrid 滚动位置在完成瞬间丢失。
    val shouldShowLiveSelection = when (state) {
        is ImageProbeState.Probing -> viewModel.probingCandidates.isNotEmpty()
        ImageProbeState.ReadyToDownload -> true
        else -> false
    }

    if (shouldShowLiveSelection) {
        LiveCandidateSelectionContent(
            candidates = viewModel.probingCandidates,
            viewModel = viewModel,
            isExtracting = state is ImageProbeState.Probing,
            isStoppingExtract = isStoppingExtract,
            isSubmittingDownload = isSubmittingDownload,
            onRetry = onRetry,
            onStopExtract = onStopExtract,
            onConfirmDownload = onConfirmDownload,
        )
    } else {
        when (state) {
            ImageProbeState.Idle -> Unit
            is ImageProbeState.Probing -> CenterContent {
                ProbingLoadingContent(showLongRunningHint = showLongRunningHint)
            }
            ImageProbeState.ReadyToDownload -> Unit
            ImageProbeState.Failed -> CenterContent { FailedText(onRetry) }
            is ImageProbeState.Extracted -> BatchStatusContent(state, viewModel, onRetry, onOpen)
        }
    }
}

/**
 * 图片探测中的加载内容。
 *
 * 普通情况下只展示加载文案；长页面超过提示阈值且已有候选时，展示已获取数量和进度入口，让用户可查看当前阶段性结果。
 */
@Composable
private fun ProbingLoadingContent(
    showLongRunningHint: Boolean,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        LoadingText(
            stringResource(
                if (showLongRunningHint) {
                    R.string.base_general_image_extract_long_running
                } else {
                    R.string.base_general_image_extract_loading
                }
            )
        )
    }
}

/**
 * 实时图片候选选择视图。
 *
 * 该视图只读取内存候选，不提前写数据库；新增候选默认选中，用户取消过的候选会记录到取消集合，
 * 因此 DOM 重扫或 URL 升级不会覆盖用户的选择。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveCandidateSelectionContent(
    candidates: List<ImageCandidateData>,
    viewModel: ImageExtractVm,
    isExtracting: Boolean,
    isStoppingExtract: Boolean,
    isSubmittingDownload: Boolean,
    onRetry: () -> Unit,
    onStopExtract: () -> Unit,
    onConfirmDownload: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    val selectedKeys = remember { mutableStateSetOf<String>() }
    val unselectedKeys = remember { mutableStateSetOf<String>() }
    var previewCandidate by remember { mutableStateOf<ImageCandidateData?>(null) }
    val imageLoader = rememberAnimatedImageLoader(context)
    // `candidates` 由 ViewModel 的 SnapshotStateList 传入，列表对象本身会复用；用稳定 key 快照触发副作用，
    // 才能在实时追加新图片时立即补上默认选中，而不是等状态分支重建后才一次性全选。
    val candidateKeys = candidates.map { viewModel.candidateKey(it) }

    LaunchedEffect(candidateKeys) {
        // 新候选默认选中；已被用户取消过的 key 不会因为候选刷新或 URL 优先级升级而恢复选中。
        val currentKeys = candidateKeys.toSet()
        selectedKeys.retainAll(currentKeys)
        unselectedKeys.retainAll(currentKeys)
        currentKeys.forEach { key ->
            if (key !in unselectedKeys) {
                selectedKeys.add(key)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LiveCandidateToolbar(
            isExtracting = isExtracting,
            isStoppingExtract = isStoppingExtract,
            isSubmittingDownload = isSubmittingDownload,
            selectedCount = selectedKeys.size,
            totalCount = candidates.size,
            onRetry = onRetry,
            onSelectAll = {
                unselectedKeys.clear()
                selectedKeys.clear()
                selectedKeys.addAll(candidates.map { viewModel.candidateKey(it) })
            },
            onUnselectAll = {
                selectedKeys.clear()
                unselectedKeys.clear()
                unselectedKeys.addAll(candidates.map { viewModel.candidateKey(it) })
            },
            onStopExtract = onStopExtract,
            onConfirmDownload = { onConfirmDownload(selectedKeys.toSet()) }
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(IMAGE_CANDIDATE_GRID_COLUMNS),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items = candidates, key = { viewModel.candidateKey(it) }) { candidate ->
                val key = viewModel.candidateKey(candidate)
                val selected = key in selectedKeys
                LiveCandidateTile(
                    candidate = candidate,
                    selected = selected,
                    imageLoader = imageLoader,
                    onPreview = {
                        viewModel.loadCandidatePreviewMeta(candidate)
                        previewCandidate = candidate
                    },
                    onToggleSelected = {
                        if (selected) {
                            selectedKeys.remove(key)
                            unselectedKeys.add(key)
                        } else {
                            unselectedKeys.remove(key)
                            selectedKeys.add(key)
                        }
                    },
                    onDecodedSize = { width, height -> viewModel.updateCandidateDecodedSize(key, width, height) }
                )
            }
        }
    }

    val candidate = previewCandidate
    if (candidate != null) {
        val candidateKey = viewModel.candidateKey(candidate)
        val meta = viewModel.candidatePreviewMetaCache[candidateKey]
            ?: ImagePreviewMeta(width = candidate.width, height = candidate.height)
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val selected = candidateKey in selectedKeys
        ModalBottomSheet(
            onDismissRequest = { previewCandidate = null },
            sheetState = sheetState,
            shape = IMAGE_PREVIEW_SHEET_SHAPE
        ) {
            CandidatePreviewSheetContent(
                candidate = candidate,
                meta = meta,
                selected = selected,
                imageLoader = imageLoader,
                onToggleSelected = {
                    if (selected) {
                        selectedKeys.remove(candidateKey)
                        unselectedKeys.add(candidateKey)
                    } else {
                        unselectedKeys.remove(candidateKey)
                        selectedKeys.add(candidateKey)
                    }
                },
                onDecodedSize = { width, height -> viewModel.updateCandidateDecodedSize(candidateKey, width, height) }
            )
        }
    }
}

/**
 * 实时选择页顶部工具栏。
 *
 * 展示提取是否仍在运行、候选总数、已选数量和核心操作；停止提取或提交下载时会禁用会话与选择动作。
 */
@Composable
private fun LiveCandidateToolbar(
    isExtracting: Boolean,
    isStoppingExtract: Boolean,
    isSubmittingDownload: Boolean,
    selectedCount: Int,
    totalCount: Int,
    onRetry: () -> Unit,
    onSelectAll: () -> Unit,
    onUnselectAll: () -> Unit,
    onStopExtract: () -> Unit,
    onConfirmDownload: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (isExtracting) R.string.base_general_image_extract_still_running else R.string.base_general_image_extract_done_waiting
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.base_general_image_live_count, selectedCount, totalCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = {
                    if (isExtracting) {
                        onStopExtract()
                    } else {
                        onConfirmDownload()
                    }
                },
                enabled = !isStoppingExtract && !isSubmittingDownload && (isExtracting || selectedCount > 0)
            ) {
                Text(
                    stringResource(
                        when {
                            isStoppingExtract -> R.string.base_general_image_extract_stopping
                            isSubmittingDownload -> R.string.base_general_image_extract_submitting
                            isExtracting -> R.string.base_general_finish_image_extract
                            else -> R.string.base_general_confirm_download_selected_images
                        }
                    )
                )
            }
        }
        if (!isExtracting && selectedCount == 0 && totalCount > 0) {
            Text(
                text = stringResource(R.string.base_general_image_select_at_least_one),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            TextButton(onClick = onRetry, enabled = !isStoppingExtract && !isSubmittingDownload) {
                Text(stringResource(R.string.base_general_re_extract_images))
            }
            TextButton(onClick = onSelectAll, enabled = !isStoppingExtract && !isSubmittingDownload && selectedCount < totalCount) {
                Text(stringResource(R.string.base_general_select_all))
            }
            TextButton(onClick = onUnselectAll, enabled = !isStoppingExtract && !isSubmittingDownload && selectedCount > 0) {
                Text(stringResource(R.string.base_general_unselect_all))
            }
        }
    }
}

/**
 * 实时候选缩略图。
 *
 * 缩略图失败不会移除候选，因为最终 Worker 带请求头下载仍可能成功；失败时显示占位和 URL 尾部供用户识别。
 */
@Composable
private fun LiveCandidateTile(
    candidate: ImageCandidateData,
    selected: Boolean,
    imageLoader: ImageLoader,
    onPreview: () -> Unit,
    onToggleSelected: () -> Unit,
    onDecodedSize: (Int?, Int?) -> Unit,
) {
    var loadFailed by remember(candidate.url) { mutableStateOf(false) }
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
            model = buildImageRequest(candidate),
            imageLoader = imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onSuccess = { state ->
                loadFailed = false
                onDecodedSize(state.result.image.width, state.result.image.height)
            },
            onError = { loadFailed = true },
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (selected) 1f else 0.42f)
        )

        if (loadFailed) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.base_general_image_load_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = candidate.url.substringAfterLast('/').takeIf { it.isNotBlank() } ?: candidate.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

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
 * 重新提取确认弹窗。
 *
 * 当前候选和选择状态只存在页面内存中，重新提取会全部清空；已有候选未下载时必须让用户明确确认这个代价。
 */
@Composable
private fun RetryExtractConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.base_general_re_extract_images)) },
        text = { Text(stringResource(R.string.base_general_re_extract_images_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.base_general_sure))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.base_general_cancel))
            }
        }
    )
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
        CenterContent {
            LoadingText(stringResource(R.string.base_general_preparing_download))
        }
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
                columns = GridCells.Fixed(IMAGE_CANDIDATE_GRID_COLUMNS),
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
 * 实时候选图片预览底部弹窗内容。
 *
 * 候选未落库时没有 item id，因此元信息和选择状态都通过稳定候选 key 在页面内存中维护；展示和已落库图片预览保持一致。
 */
@Composable
private fun CandidatePreviewSheetContent(
    candidate: ImageCandidateData,
    meta: ImagePreviewMeta,
    selected: Boolean,
    imageLoader: ImageLoader,
    onToggleSelected: () -> Unit,
    onDecodedSize: (Int?, Int?) -> Unit,
) {
    val scrollState = rememberScrollState()
    val unknownText = stringResource(R.string.base_general_unknow)
    val resolutionText = formatResolution(meta.width ?: candidate.width, meta.height ?: candidate.height, unknownText)
    val fileTypeText = formatMimeType(meta.mimeType, candidate.url, unknownText)
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
                    model = buildImageRequest(candidate, preview = true),
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    onSuccess = { state -> onDecodedSize(state.result.image.width, state.result.image.height) },
                    onError = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp)
                        .then(previewAspectModifier(meta.width ?: candidate.width, meta.height ?: candidate.height))
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
                    text = candidate.url,
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
 * 构建阶段性候选缩略图请求。
 *
 * 进度视图没有数据库图片项，因此直接使用候选中的反盗链上下文；缩略图尺寸固定为小图，避免长耗时页面额外消耗过多流量。
 */
@Composable
private fun buildImageRequest(candidate: ImageCandidateData, preview: Boolean = false): ImageRequest {
    val context = LocalContext.current
    val size = if (preview) {
        SizeResolver.ORIGINAL
    } else {
        SizeResolver(Size(IMAGE_PREVIEW_THUMBNAIL_SIZE_PX, IMAGE_PREVIEW_THUMBNAIL_SIZE_PX))
    }
    return remember(candidate.url, candidate.referer, candidate.userAgent, candidate.cookie, preview) {
        ImageRequest.Builder(context)
            .data(candidate.url)
            .size(size)
            .allowHardware(false)
            .httpHeaders(buildNetworkHeaders(candidate))
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
        // 与 Worker 下载请求保持一致，减少 CDN 因 Accept 不同返回静态预览或不同转码格式的概率。
        set("Accept", IMAGE_REQUEST_ACCEPT)
        if (!referer.isNullOrBlank()) set("Referer", referer)
        if (!userAgent.isNullOrBlank()) set("User-Agent", userAgent)
        if (!cookie.isNullOrBlank()) set("Cookie", cookie)
    }.build()
}

/**
 * 构建阶段性候选图片加载请求头。
 *
 * 与正式图片项使用同一套 Referer/User-Agent/Cookie 规则，确保进度页缩略图和最终选择页尽量表现一致。
 */
private fun buildNetworkHeaders(candidate: ImageCandidateData): NetworkHeaders {
    return NetworkHeaders.Builder().apply {
        val referer = candidate.referer
        val userAgent = candidate.userAgent
        val cookie = candidate.cookie
        // 实时网格预览和最终下载使用同一 Accept，方便对比“预览可动”和“保存后静态”的真实原因。
        set("Accept", IMAGE_REQUEST_ACCEPT)
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
            modifier = Modifier
                .padding(12.dp)
                .size(24.dp)
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
            modifier = Modifier
                .padding(12.dp)
                .size(24.dp)
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
    if (isForMainFrame) {
        // 主文档请求的 Accept 也可能带 image/webp 等能力声明，不能把网页本身当作图片候选。
        return null
    }
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
 * 执行最多两轮从顶部到底部的图片探测。
 *
 * 每轮滚动期间用轻量 DOM 扫描持续刷新阶段性候选，触底后再用完整 DOM 快照判断当前可解析图片是否都已进入候选集；
 * 第一轮不完整时只回顶一次，第二轮触底后无论是否仍有占位都会结束，避免动态页面无限上下滚动。
 */
private suspend fun collectImagesWithDoublePass(
    view: WebView,
    referer: String,
    userAgent: String?,
    viewModel: ImageExtractVm,
    sessionId: Int,
    onFirstBottomReached: () -> Unit,
): List<ImageCandidateData> {
    val startedAt = SystemClock.elapsedRealtime()
    delay(IMAGE_COLLECT_SETTLE_DELAY_MS)

    var latestDomCandidates = collectAndPublishDomCandidates(
        view = view,
        script = COLLECT_LIGHT_IMAGES_JS,
        referer = referer,
        userAgent = userAgent,
        viewModel = viewModel,
        sessionId = sessionId,
    )
    var firstBottomReached = false

    repeat(IMAGE_COLLECT_MAX_SCROLL_PASSES) { passIndex ->
        while (true) {
            parseScrollProbeResult(view.evaluateJavascriptAwait(IMAGE_SCROLL_PROBE_JS))
            delay(IMAGE_COLLECT_STEP_DELAY_MS)

            latestDomCandidates = collectAndPublishDomCandidates(
                view = view,
                script = COLLECT_LIGHT_IMAGES_JS,
                referer = referer,
                userAgent = userAgent,
                viewModel = viewModel,
                sessionId = sessionId,
            )

            val scrollResult = parseScrollProbeResult(view.evaluateJavascriptAwait(IMAGE_SCROLL_STATUS_JS))
            if (scrollResult.atBottom) {
                if (!firstBottomReached) {
                    firstBottomReached = true
                    onFirstBottomReached()
                }

                val fullDomCandidates = collectAndPublishDomCandidates(
                    view = view,
                    script = COLLECT_IMAGES_JS,
                    referer = referer,
                    userAgent = userAgent,
                    viewModel = viewModel,
                    sessionId = sessionId,
                )
                if (fullDomCandidates.isEmpty() && shouldContinueWaitingForDomEvidence(startedAt)) {
                    // WebView 可能刚加载完 SPA 初始壳，此时高度很小会立刻触底；继续等 DOM 图片证据，避免网络占位图提前生成批次。
                    delay(IMAGE_COLLECT_STEP_DELAY_MS)
                    continue
                }
                val domStatus = parseDomStatus(view.evaluateJavascriptAwait(IMAGE_DOM_STATUS_JS))
                val probingSnapshot = viewModel.snapshotProbingCandidates()
                val domSnapshotComplete = isCurrentDomSnapshotComplete(
                    domCandidates = fullDomCandidates,
                    probingCandidates = probingSnapshot,
                    domStatus = domStatus,
                )

                if (passIndex == 0 && !domSnapshotComplete) {
                    // 第一轮仍有未解析懒加载占位时只回顶一次；已有候选会保留，用第二轮补齐迟到的真实 URL。
                    view.evaluateJavascriptAwait(IMAGE_SCROLL_TOP_JS)
                    delay(IMAGE_COLLECT_SETTLE_DELAY_MS)
                    latestDomCandidates = collectAndPublishDomCandidates(
                        view = view,
                        script = COLLECT_LIGHT_IMAGES_JS,
                        referer = referer,
                        userAgent = userAgent,
                        viewModel = viewModel,
                        sessionId = sessionId,
                    )
                    break
                }

                // 完整 DOM 快照已经按页面顺序解析过，最终保存时再由 ViewModel 合并网络层补充候选。
                return fullDomCandidates
            }
        }
    }

    return latestDomCandidates
}

/**
 * 空 DOM 触底时是否继续等待正文图片出现。
 *
 * 10 秒无候选兜底同样以探测开始为窗口；这里复用这个窗口，让空白页、SPA 初始壳或只有占位网络图的页面不会马上进入选择页。
 */
private fun shouldContinueWaitingForDomEvidence(startedAt: Long): Boolean {
    return SystemClock.elapsedRealtime() - startedAt < IMAGE_EMPTY_DOM_WAIT_MS
}

/**
 * 执行一次 DOM 图片扫描并同步阶段性候选。
 *
 * 脚本返回的 URL 会复用既有过滤规则；发布 UI 前带上 session 校验，防止旧协程在重试或失败后覆盖当前进度。
 */
private suspend fun collectAndPublishDomCandidates(
    view: WebView,
    script: String,
    referer: String,
    userAgent: String?,
    viewModel: ImageExtractVm,
    sessionId: Int,
): List<ImageCandidateData> {
    val candidates = parseDomCandidates(view.evaluateJavascriptAwait(script), referer, userAgent)
    val snapshot = viewModel.addDomCandidates(sessionId, candidates)
    if (snapshot != null) {
        viewModel.publishProbingCandidates(sessionId, snapshot)
    }
    return candidates
}

/**
 * 判断触底时的当前 DOM 快照是否已经完整进入阶段性候选池。
 *
 * 这里的“完整”只面向当前快照：所有可解析、可用的图片 URL 都已进入候选，且没有明显仍未解析出真实 URL 的懒加载占位；
 * 对后续滚动或脚本动态追加的新 DOM 不做承诺。
 */
private fun isCurrentDomSnapshotComplete(
    domCandidates: List<ImageCandidateData>,
    probingCandidates: List<ImageCandidateData>,
    domStatus: DomSnapshotStatus,
): Boolean {
    if (domCandidates.isEmpty()) {
        // SPA 初始壳或 about:blank 一类页面高度可能立刻“触底”，但此时正文图片尚未写入 DOM，不能因为网络里有占位图就提前结束。
        return false
    }
    val probingUrls = probingCandidates.mapTo(mutableSetOf()) { it.url }
    val missingDomUrl = domCandidates.any { candidate -> candidate.url !in probingUrls }
    return !missingDomUrl && domStatus.unresolvedLazyCount == 0
}

/**
 * 解析滚动脚本返回值。
 *
 * 如果 JS 返回异常或 WebView 已经被取消，默认认为尚未触底，让调用方继续等待或由外层取消任务处理。
 */
private fun parseScrollProbeResult(value: String?): ScrollProbeResult {
    return parseJsonObject(value)
        ?.let { obj ->
            ScrollProbeResult(
                y = obj.optDouble("y", 0.0),
                max = obj.optDouble("max", 0.0),
                atBottom = obj.optBoolean("atBottom", false),
            )
        }
        ?: ScrollProbeResult()
}

/**
 * 解析当前 DOM 快照状态。
 *
 * `unresolvedLazyCount` 只统计带懒加载属性但仍无法解析真实 URL 的节点，是第一轮是否需要回顶重试的启发式信号。
 */
private fun parseDomStatus(value: String?): DomSnapshotStatus {
    return parseJsonObject(value)
        ?.let { obj -> DomSnapshotStatus(unresolvedLazyCount = obj.optInt("unresolvedLazy", 0)) }
        ?: DomSnapshotStatus()
}

/**
 * 解码 `evaluateJavascript` 返回的对象字符串。
 *
 * WebView 会把 JS 返回值再 JSON 编码一层，因此这里先用 `JSONTokener` 拆掉外层字符串，再交给 `JSONObject` 解析。
 */
private fun parseJsonObject(value: String?): JSONObject? {
    return runCatching {
        val decoded = JSONTokener(value ?: "{}").nextValue()
        when (decoded) {
            is JSONObject -> decoded
            is String -> JSONObject(decoded)
            else -> JSONObject(value ?: "{}")
        }
    }.getOrNull()
}

/**
 * 单次滚动后的页面位置。
 *
 * `atBottom` 是双轮探测的关键状态，`y/max` 仅用于调试和后续扩展，不直接展示给用户。
 */
private data class ScrollProbeResult(
    /** 当前纵向滚动位置，单位为 CSS 像素；解析失败时为 0。 */
    val y: Double = 0.0,

    /** 当前文档最大可滚动位置，单位为 CSS 像素；动态页面可能在滚动过程中继续变大。 */
    val max: Double = 0.0,

    /** 本次滚动后是否已经抵达当前文档底部。 */
    val atBottom: Boolean = false,
)

/**
 * 当前 DOM 快照的懒加载完整性状态。
 *
 * 只记录仍未解析出真实 URL 的懒加载占位数量；大于 0 时说明第一轮触底后仍值得回顶再触发一次懒加载。
 */
private data class DomSnapshotStatus(
    /** 当前 DOM 中仍疑似等待懒加载填充真实图片地址的元素数量。 */
    val unresolvedLazyCount: Int = 0,
)

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
 * 规则同时参考 Fetch-Dest、路径扩展名和 URL 图片语义；普通网页 Accept 里带图片能力声明不算图片请求，
 * 这样可以避免页面主文档、接口或脚本在正文渲染前被误加入候选。
 */
private fun isLikelyImageRequest(uri: Uri, headers: Map<String, String>): Boolean {
    val url = uri.toString()
    val path = uri.encodedPath.orEmpty().lowercase()
    val accept = headers.headerValue("Accept").lowercase()
    val fetchDest = headers.headerValue("Sec-Fetch-Dest").lowercase()
    val byExt = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".bmp").any { ext ->
        path.endsWith(ext)
    }
    val byHint = hasImageUrlHint(url)
    // 普通页面请求的 Accept 常包含 image/avif,image/webp；只有明确的 image fetch 或图片 URL 特征才进入候选池。
    return (fetchDest == "image" || byExt || accept.contains("image/") && byHint) && isUsableImageUrl(url)
}

/** 按大小写不敏感方式读取请求头，兼容不同 WebView/Chromium 版本的 header key 大小写差异。 */
private fun Map<String, String>.headerValue(name: String): String {
    return entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()
}

/**
 * 判断无扩展名 URL 是否带有图片资源特征。
 *
 * 该规则只用于网络拦截兜底，避免把文档、接口或脚本请求误判成图片；真正可见的 DOM 图片仍由 DOM 扫描负责收集。
 */
private fun hasImageUrlHint(url: String): Boolean {
    val lower = url.lowercase()
    return Regex("""(?:^|[?&/=_-])(?:image|img|photo|pic|poster|thumbnail|thumb)(?:[=/&._-]|$)""")
        .containsMatchIn(lower)
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
 * 只匹配文件名中的 sprite、placeholder、blank、spacer、pixel、favicon、icon、logo 等明确模式，避免路径目录中出现这些词时误过滤正文图。
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
        Regex("""(^|[-_.@])empty([-_.@]|$)"""),
        Regex("""(^|[-_.@])spacer([-_.@]|$)"""),
        Regex("""(^|[-_.@])pixel([-_.@]|$)"""),
        Regex("""(^|[-_.@])1x1([-_.@]|$)"""),
        Regex("""(^|[-_.@])loading([-_.@]|$)"""),
        Regex("""(^|[-_.@])loader([-_.@]|$)"""),
        Regex("""(^|[-_.@])favicon([-_.@]|$)"""),
        Regex("""(^|[-_.@])icon([-_.@]|$)"""),
        Regex("""(^|[-_.@])logo([-_.@]|$)"""),
    ).any { it.containsMatchIn(fileName) }
}
