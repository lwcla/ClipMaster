package com.cla.clip.master.localization

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.cla.clip.master.ui.page.privacy.PrivacyPolicyPage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 使用普通 LocalizationTestRunner 挂载真实政策页；只读取随包正文，不创建 Hilt、网络或用户数据依赖。 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class EnglishPrivacyDeviceTest : EnglishScreenTestBase() {
    /** 同一生产网页在三个真实 Context 字号下检查正文缩放、英文行宽和逐屏阅读到底。 */
    @Test
    fun privacyPolicyEnglishBodyScalesAndScrollsWithoutHorizontalOverflow() {
        verifyEnglish(
            stage = "privacy-document",
            prepare = { awaitLoadedDocument() },
            exercise = { inspectEnglishDocument() },
        ) {
            PrivacyPolicyPage(onBack = {})
        }
    }

    /** 仅更新阅读字号时复用同一 WebView 和原文档，不重新读取资产或把阅读位置送回顶部。 */
    @Test
    fun privacyFontConfigurationUpdatesExistingDocumentWithoutReload() {
        // 覆盖值仅模拟宿主向现有组合传播 Configuration；null 沿用共享宿主的真实配置。
        val fontOverride = mutableStateOf<Float?>(null)
        verifyEnglish(stage = "privacy-font-reconfiguration", prepare = {
            compose.runOnIdle { fontOverride.value = null }
            awaitLoadedDocument()
        }, exercise = {
            // 原实例用于证明配置更新没有触发 key 重建或重新加载正文。
            val original = compose.runOnIdle { requireNotNull(findDocumentView()) }
            // 每轮选择不同于初始配置的字号，确保 AndroidView.update 必须实际执行。
            val changedScale = compose.runOnIdle { if (original.context.resources.configuration.fontScale < 1.9f) 2f else 1.3f }
            try {
                compose.runOnIdle {
                    assertFalse("JavaScript must remain disabled before reconfiguration", original.settings.javaScriptEnabled)
                    assertTrue("Network must remain blocked before reconfiguration", original.settings.blockNetworkLoads)
                    original.settings.javaScriptEnabled = true
                }
                evaluateObject(original, """
                    (function() {
                        // 标记只属于测试 JS 运行时；重载文档会清空，不修改 HTML 或 CSS。
                        window.__privacyFontConfigurationMarker = 13579;
                        document.getElementById('en').scrollIntoView({block:'start', inline:'nearest'});
                        return JSON.stringify({marked:true});
                    })()
                """.trimIndent())
                waitForVisualUpdate(original)
                compose.runOnIdle {
                    original.settings.javaScriptEnabled = false
                    assertTrue("Reading position must start below the document top", original.scrollY > 0)
                    fontOverride.value = changedScale
                }
                compose.waitUntil(5_000) {
                    compose.runOnIdle { findDocumentView()?.settings?.textZoom == (changedScale * 100).roundToInt() }
                }
                waitForVisualUpdate(original)
                compose.runOnIdle {
                    assertSame("Font configuration must keep the existing WebView", original, findDocumentView())
                    assertFalse("Font update must not enable JavaScript", original.settings.javaScriptEnabled)
                    assertTrue("Font update must not enable network loads", original.settings.blockNetworkLoads)
                    assertTrue("Font update must not reset reading to the document top", original.scrollY > 0)
                    original.settings.javaScriptEnabled = true
                }
                // 再次开启 JS 只为读取测试标记；标记存在证明原文档未被 loadData/reload 替换。
                val marker = evaluateObject(original, "JSON.stringify({marker:window.__privacyFontConfigurationMarker || 0})")
                assertEquals("Font update must not reload the document", 13579, marker.getInt("marker"))
            } finally {
                compose.runOnIdle { findDocumentView()?.settings?.javaScriptEnabled = false }
            }
        }) {
            // 只变更已有 LocalConfiguration，保留实际 View Context 和 Compose 密度以验证 update 路径。
            val configuration = Configuration(LocalConfiguration.current).apply {
                fontOverride.value?.let { fontScale = it }
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) { PrivacyPolicyPage(onBack = {}) }
        }
    }

    /** 等待真实本地文档完成排版；不替换 WebViewClient、不注入 HTML、不访问远端地址。 */
    private fun awaitLoadedDocument() {
        compose.waitUntil(15_000) {
            compose.runOnIdle {
                // 当前实例必须可见且完成页面加载，旧字号释放中的实例不能参与检查。
                val view = findDocumentView()
                view != null && view.progress == 100 && view.contentHeight > 0 && view.width > 0 && view.height > 0
            }
        }
    }

    /** 汇总字号与行宽失败后继续采集全部英文页，最后再失败，避免首个问题阻止滚动证据。 */
    private fun inspectEnglishDocument() {
        // 当前 WebView 来自测试 Activity 的生产页面，不持有跨字号或跨测试实例。
        val view = compose.runOnIdle { requireNotNull(findDocumentView()) }
        // 每轮只累积本字号的低敏布局诊断；不输出整个政策正文或任何用户数据。
        val failures = mutableListOf<String>()
        // 可见章节序号由生产 DOM 返回，逐屏经过全部英文标题才算阅读覆盖完整。
        val seenHeadings = mutableSetOf<Int>()
        compose.runOnIdle {
            assertFalse("Production privacy document must disable JavaScript", view.settings.javaScriptEnabled)
            assertTrue("Production privacy document must block network loads", view.settings.blockNetworkLoads)
            // 期望倍率取真实 WebView Context，防止只把 Compose 标题放大而正文仍是默认大小。
            val expectedZoom = (100f * view.context.resources.configuration.fontScale).roundToInt()
            if (abs(view.settings.textZoom - expectedZoom) > 1) {
                failures += "Privacy textZoom=${view.settings.textZoom}, expected=$expectedZoom from actual View Context"
            }
        }
        try {
            // 仅测试在已验证禁网的本地正文中临时启用 JS，读取 DOM 和定位锚点，不改页面内容或样式。
            compose.runOnIdle { view.settings.javaScriptEnabled = true }
            // 正式英文锚点由生产资产定义，直接定位而不是复制英文正文到测试网页。
            val start = evaluateObject(view, """
                (function() {
                    // 英文章节由正式资产提供，缺失直接报告，不能用替代 HTML 掩盖问题。
                    const english = document.getElementById('en');
                    if (!english) return JSON.stringify({found:false});
                    english.scrollIntoView({block:'start', inline:'nearest'});
                    return JSON.stringify({found:true, count:english.querySelectorAll('h3').length});
                })()
            """.trimIndent())
            assertTrue("Production policy must contain #en", start.optBoolean("found"))
            assertTrue("Production English policy must contain its ten sections", start.optInt("count") == 10)
            waitForVisualUpdate(view)

            // 每步重叠四分之一视口；双倍正文在窄屏可超过 80 屏，512 仅防异常无限滚动，不替代末页断言。
            for (page in 0 until 512) {
                checkAndCapture("privacy-english-page-$page")
                // 指标使用真实文本 Range 的行边界，包含正文、链接及英文标题。
                val metrics = evaluateObject(view, DocumentMetricsScript)
                collectLayoutEvidence(metrics, page, seenHeadings, failures)
                if (!compose.runOnIdle { view.canScrollVertically(1) }) break
                compose.runOnIdle {
                    // 原生像素步长保留画面重叠，不使用 JS 翻页或替换滚动容器。
                    val step = (view.height * 0.75f).roundToInt().coerceAtLeast(1)
                    view.scrollBy(0, step)
                }
                waitForVisualUpdate(view)
            }
            // 最后一帧必须是真正底部，同时最后一个英文区块落在当前可见视口。
            val end = evaluateObject(view, DocumentMetricsScript)
            if (compose.runOnIdle { view.canScrollVertically(1) }) failures += "English document did not reach native scroll bottom"
            if (end.optDouble("englishBottom") > end.optDouble("viewportHeight") + 2.0 || end.optDouble("englishBottom") <= 0.0) {
                failures += "English final section is not visible at native scroll bottom"
            }
            if (seenHeadings.size != start.optInt("count")) {
                failures += "English headings visited=${seenHeadings.sorted()}, expected=${start.optInt("count")}"
            }
            assertTrue(failures.distinct().joinToString("\n"), failures.isEmpty())
        } finally {
            // 无论断言、JS 回调还是截图失败，均恢复生产默认；禁网设置始终没有被放开。
            compose.runOnIdle { view.settings.javaScriptEnabled = false }
        }
    }

    /** 汇总公开文档的纯布局指标，仅报告标签/行边界，不保存正文全文。 */
    private fun collectLayoutEvidence(metrics: JSONObject, page: Int, seenHeadings: MutableSet<Int>, failures: MutableList<String>) {
        // 文档最大宽度与视口宽度独立检查，防止文字区域之外的容器造成横向溢出。
        val documentWidth = metrics.getDouble("documentWidth")
        val viewportWidth = metrics.getDouble("viewportWidth")
        if (documentWidth > viewportWidth + 2.0) failures += "English page $page documentWidth=$documentWidth exceeds viewport=$viewportWidth"
        // 每个失败记录仅包含元素标签和像素边界，足够定位 CSS 问题而不复制政策正文。
        val overflows = metrics.getJSONArray("overflows")
        for (index in 0 until overflows.length()) failures += "English text overflow: ${overflows.getString(index)}"
        // 标题序号取正式 DOM 顺序，必须有一帧实际落在视口内。
        val visibleHeadings = metrics.getJSONArray("visibleHeadings")
        for (index in 0 until visibleHeadings.length()) seenHeadings += visibleHeadings.getInt(index)
    }

    /** 递归查找本进程 Activity 根中的可见 WebView，避免依赖 Hilt Activity 或私有字段反射。 */
    private fun findDocumentView(): WebView? {
        for (root in WindowInspector.getGlobalWindowViews()) {
            // 窗口只属于当前测试进程；递归仍校验 WebView 的 Activity Context 与附着状态。
            val found = findWebView(root)
            if (found != null) return found
        }
        return null
    }

    /** 只接受已附着的真实页面实例；逐层遍历当前 View 树，不缓存根节点。 */
    private fun findWebView(view: View): WebView? {
        if (view is WebView && view.isAttachedToWindow && view.visibility == View.VISIBLE && hasActivityContext(view.context)) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                // 下标只用于平台树遍历，不是页面身份或状态 key。
                val found = findWebView(view.getChildAt(index))
                if (found != null) return found
            }
        }
        return null
    }

    /** 解开英文 ContextThemeWrapper，确认正文最终由当前测试 Activity 承载。 */
    private fun hasActivityContext(context: Context): Boolean {
        // 当前 Context 沿公开包装链向外查找，不访问 Window 或 Activity 私有实现。
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return true
            if (current.baseContext === current) return false
            current = current.baseContext
        }
        return current is Activity
    }

    /** 有界等待测试专用 DOM 查询；JSON 只包含布局数值，不执行外链或读取外部来源。 */
    private fun evaluateObject(view: WebView, script: String): JSONObject {
        // 回调在 UI 线程，原子引用只连接该次查询与仪器线程等待。
        val result = AtomicReference<String?>()
        compose.runOnIdle { view.evaluateJavascript(script) { value -> result.set(value) } }
        compose.waitUntil(5_000) { result.get() != null }
        // evaluateJavascript 会再次编码 JSON.stringify 的结果，先解出字符串再解析对象。
        val decoded = JSONTokener(requireNotNull(result.get())).nextValue()
        return JSONObject(decoded as String)
    }

    /** 等待网页合成器提交滚动后的实际画面，避免截图停留在前一屏。 */
    private fun waitForVisualUpdate(view: WebView) {
        // 此标志只代表当前滚动帧已提交，不读取 WebView 私有状态。
        val ready = AtomicBoolean(false)
        compose.runOnIdle {
            view.postVisualStateCallback(1L, object : WebView.VisualStateCallback() {
                /** 主线程回调只结束本次有界等待，不触发新的页面操作。 */
                override fun onComplete(requestId: Long) { ready.set(true) }
            })
        }
        compose.waitUntil(5_000) { ready.get() }
    }

    companion object {
        /** 对正式 #en 文本逐行测量；不修改 HTML/CSS，不用元素盒宽度冒充实际文字行宽。 */
        private val DocumentMetricsScript = """
            (function() {
                // 英文容器与文档根仅用于读取正式页面的布局边界。
                const english = document.getElementById('en');
                const root = document.documentElement;
                // 视口为当前 WebView 的 CSS 像素，不使用测试预设宽度推断排版。
                const viewportWidth = root.clientWidth;
                const viewportHeight = window.innerHeight;
                // TreeWalker 与 Range 逐个文本节点读取浏览器真实行矩形。
                const walker = document.createTreeWalker(english, NodeFilter.SHOW_TEXT);
                const range = document.createRange();
                const overflows = [];
                // 当前节点只在同步遍历内保留，不写入 DOM。
                let node;
                while ((node = walker.nextNode())) {
                    if (!node.textContent.trim()) continue;
                    // 隐藏内容没有可见行，不应作为当前正文溢出报告。
                    const style = window.getComputedStyle(node.parentElement);
                    if (style.display === 'none' || style.visibility === 'hidden') continue;
                    range.selectNodeContents(node);
                    for (const rect of range.getClientRects()) {
                        if (rect.width > 0 && (rect.left < -2 || rect.right > viewportWidth + 2)) {
                            overflows.push(node.parentElement.tagName + ':left=' + rect.left.toFixed(2) + ',right=' + rect.right.toFixed(2));
                        }
                    }
                }
                // 标题是否真实进入过视口用于证明逐屏覆盖了全部英文章节。
                const visibleHeadings = Array.from(english.querySelectorAll('h3')).flatMap(function(heading, index) {
                    const rect = heading.getBoundingClientRect();
                    return rect.bottom > 0 && rect.top < viewportHeight ? [index] : [];
                });
                return JSON.stringify({
                    viewportWidth:viewportWidth, viewportHeight:viewportHeight,
                    documentWidth:Math.max(root.scrollWidth, document.body.scrollWidth),
                    englishBottom:english.getBoundingClientRect().bottom,
                    overflows:overflows, visibleHeadings:visibleHeadings
                });
            })()
        """.trimIndent()
    }
}
