package com.cla.clip.master.ui.page.image

import android.net.Uri
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.cla.clip.base.general.repository.ImageCandidateData
import com.cla.clip.base.general.utils.logD
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/** 探测开始后连续没有任何有效图片候选的最大等待时间，单位毫秒；超过后进入失败流程。 */
private const val IMAGE_NO_CANDIDATE_TIMEOUT_MS = 10_000L

/** 自动完成前至少等待 DOM 出现有效图片证据的窗口，避免页面初始壳触底后仅凭网络占位图落库。 */
private const val IMAGE_EMPTY_DOM_WAIT_MS = IMAGE_NO_CANDIDATE_TIMEOUT_MS


/** DOM 图片收集前等待页面稳定的时间，避免刚完成加载时懒加载脚本还没把真实图片写入页面。 */
private const val IMAGE_COLLECT_SETTLE_DELAY_MS = 600L

/** 每轮自动滚动后的等待时间，用于给 IntersectionObserver 和滚动监听式懒加载留出触发窗口。 */
private const val IMAGE_COLLECT_STEP_DELAY_MS = 350L

/** 双轮滚动中允许从底部回到顶部重试的次数，固定一次以避免无限上下循环。 */
private const val IMAGE_COLLECT_MAX_SCROLL_PASSES = 2

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
 * 将 WebView 网络请求转换成图片候选。
 *
 * 只有疑似图片请求才会保留；请求头优先使用本次网络请求自带值，缺失时回退到页面 URL 和 WebView User-Agent，
 * 确保后续预览和下载尽量复用同一反盗链上下文。
 */
internal fun WebResourceRequest.toNetworkCandidate(defaultReferer: String, defaultUserAgent: String?): ImageCandidateData? {
    if (isForMainFrame) {
        // 主文档请求的 Accept 也可能带 image/webp 等能力声明，不能把网页本身当作图片候选。
        return null
    }
    // 非主文档请求的实际资源地址，只用于过滤和构造候选，不打印日志。
    val reqUrl = url.toString()
    if (!isLikelyImageRequest(url, requestHeaders.orEmpty())) {
        return null
    }
    // 捕获请求上下文，后续预览与下载复用防盗链字段。
    val headers = requestHeaders.orEmpty()
    // 当前资源的登录态只随候选传递，不进入 UI 文案或日志。
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
internal suspend fun collectImagesWithDoublePass(
    view: WebView,
    referer: String,
    userAgent: String?,
    viewModel: ImageExtractVm,
    sessionId: Int,
): List<ImageCandidateData> {
    // 单调时钟只用于等待 SPA 正文的短窗口，页面另有总扫描预算。
    val startedAt = SystemClock.elapsedRealtime()
    delay(IMAGE_COLLECT_SETTLE_DELAY_MS)

    // 保存最近一次 DOM 证据，第二轮退出时不退回网络占位图。
    var latestDomCandidates = collectAndPublishDomCandidates(
        view = view,
        script = COLLECT_LIGHT_IMAGES_JS,
        referer = referer,
        userAgent = userAgent,
        viewModel = viewModel,
        sessionId = sessionId,
    )

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

            // 脚本无响应时按未触底处理，最终由页面总预算终止。
            val scrollResult = parseScrollProbeResult(view.evaluateJavascriptAwait(IMAGE_SCROLL_STATUS_JS))
            if (scrollResult.atBottom) {
                // 触底时完整扫描用于判定正文图片是否确实存在。
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
                // 统计当前 DOM 懒加载占位，决定是否允许仅一次回顶补扫。
                val domStatus = parseDomStatus(view.evaluateJavascriptAwait(IMAGE_DOM_STATUS_JS))
                // 线程安全池快照包含网络补充候选。
                val probingSnapshot = viewModel.snapshotProbingCandidates()
                // 只判断当前 DOM 的完整性，不对后续动态追加做承诺。
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
    // 脚本外部输入经既有过滤解析后才能合入当前会话。
    val candidates = parseDomCandidates(view.evaluateJavascriptAwait(script), referer, userAgent)
    // 过期会话返回 null，停止向当前 UI 发布旧扫描结果。
    val snapshot = viewModel.addDomCandidates(sessionId, candidates)
    if (snapshot != null) {
        viewModel.publishProbingCandidates(sessionId)
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
    // 当前已识别地址集合，用于核对 DOM 证据覆盖情况。
    val probingUrls = probingCandidates.mapTo(mutableSetOf()) { it.url }
    // 任何 DOM 图片遗漏都会触发一次补扫。
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
        // WebView 回调可能对 JSON 再编码一层字符串，先解开外层。
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
    return kotlinx.coroutines.withTimeoutOrNull(1_500L) {
        suspendCancellableCoroutine { continuation ->
            evaluateJavascript(script) { result ->
                if (continuation.isActive) continuation.resume(result)
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
        // WebView 回调可能对 JSON 再编码一层字符串，先解开外层。
        val decoded = JSONTokener(value ?: "[]").nextValue()
        // 候选数组解析失败由调用方降级为空，不部分接受损坏 JSON。
        val array = JSONArray(if (decoded is String) decoded else value ?: "[]")
        buildList {
            for (index in 0 until array.length()) {
                // 当前图片条目只读取约定字段，忽略未知属性。
                val obj = array.getJSONObject(index)
                // 图片 URL 保持原始签名，后续过滤不改写查询参数。
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
        // 外部页面解析错误不复制 message，避免网页原文进入公共采集。
        logD("ImageExtractPage") { "parseDomCandidates failed type=${it.javaClass.simpleName}" }
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
    // 图片 URL 保持原始签名，后续过滤不改写查询参数。
    val url = uri.toString()
    // 只在路径上匹配图片扩展名，降低接口关键词误识别。
    val path = uri.encodedPath.orEmpty().lowercase()
    // 请求支持的媒体类型用于辅助判断，不当作实际响应 MIME。
    val accept = headers.headerValue("Accept").lowercase()
    // 浏览器声明 image 的资源优先视为图片请求。
    val fetchDest = headers.headerValue("Sec-Fetch-Dest").lowercase()
    // 常见图片后缀允许识别没有 Fetch-Dest 的兼容站点。
    val byExt = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".bmp").any { ext ->
        path.endsWith(ext)
    }
    // URL 语义只能配合其他图片证据使用，避免接受主文档。
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
    // 大小写无关的图片路径线索，不修改最终下载 URL。
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
    // 大小写无关的图片路径线索，不修改最终下载 URL。
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
