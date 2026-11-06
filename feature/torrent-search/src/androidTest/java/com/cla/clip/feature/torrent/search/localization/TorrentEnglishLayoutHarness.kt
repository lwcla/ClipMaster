package com.cla.clip.feature.torrent.search.localization

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import com.cla.clip.feature.torrent.search.ui.enableTorrentLightEdgeToEdge
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Assume.assumeTrue

/** 模块专属仪器宿主：真实生产组件、独立英文 Context、无业务 Application 或 ViewModel。 */
abstract class TorrentEnglishLayoutHarness {
    /** 普通测试 Activity 提供真实窗口和生命周期，不依赖宿主 Hilt 初始化。 */
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** 只记录本轮组合实际收到的像素边界，供排查嵌套 View 消耗，不替生产页面添加边距。 */
    private var composeBottomInsets = "unavailable"

    /** innerComposeView 仅在当前字号场景内持有，供诊断真实窗口 Insets 到嵌套组合的分发。 */
    private var innerComposeView: ComposeView? = null

    /** progressStage 仅保存合成场景和字号，诊断不得带入输入文本或真实路径。 */
    private var progressStage = "setup"

    /** scrollController 复用真实语义树，但不调用 Compose 测试库内部的无界滚动循环。 */
    private val scrollController by lazy {
        TorrentEnglishScrollController(compose, ::markProgress) { node ->
            compose.runOnIdle { torrentEnglishNativeSafeBounds(node) }
        }
    }

    /** restoreProcessConfiguration 只跨越一个用例，防止默认英文泄漏到后续资源/组件测试。 */
    private var restoreProcessConfiguration: (() -> Unit)? = null

    /** 在安装英文内容前保存当前进程状态；主线程顺序与 Activity 资源配置分发保持一致。 */
    @Before fun captureOriginalProcessConfiguration() {
        // instrumentation 提供本测试进程主线程和资源上下文，不读取用户设置。
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            restoreProcessConfiguration = captureTorrentProcessConfigurationRestorer(instrumentation.targetContext)
        }
    }

    /** 即使排版断言失败也执行语言还原，测试顺序不能改变其他用例的资源匹配结果。 */
    @After fun restoreOriginalProcessConfiguration() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            restoreProcessConfiguration?.invoke()
            restoreProcessConfiguration = null
        }
    }

    /** 从实际库测试 APK 读取英文资源，避免硬编码预期文案与翻译脱节。 */
    protected fun english(resource: Int, vararg arguments: Any): String {
        // configuration 仅作用于当前资源上下文，不修改设备语言或字号。
        val configuration = Configuration().apply { setLocales(LocaleList.forLanguageTags("en-US")) }
        return InstrumentationRegistry.getInstrumentation().targetContext
            .createConfigurationContext(configuration).getString(resource, *arguments)
    }

    /** 将当前场景的静态资源列表转换为真实英文标签，保留显式阅读顺序。 */
    protected fun labels(vararg resources: Int): List<String> = resources.map { resource -> english(resource) }

    /** 打开惰性列表中的真实按钮前先滚动定位；不会替代控件实际点击行为。 */
    protected fun reveal(matcher: SemanticsMatcher) {
        scrollController.reveal(matcher)
    }

    /** 各字号先留截图再汇总断言；beforeCheck 可打开真实弹窗，labels 按页面阅读顺序滚动。 */
    protected fun verify(
        stage: String,
        labels: List<String>,
        allowedSummaries: Set<String> = emptySet(),
        beforeCheck: () -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        // API 29 起可通过公开窗口清单精确约束弹窗宽度；当前统一真机为 API 31。
        assumeTrue("Exact dialog width requires public WindowInspector on API 29+", Build.VERSION.SDK_INT >= 29)
        // scale 是测试组合唯一可变输入；key 重建窗口以保证弹窗也继承新的 fontScale。
        val scale = mutableFloatStateOf(1f)
        // failures 累积所有字号问题，首个布局失败不阻止后续证据采集。
        val failures = mutableListOf<String>()
        // 直接使用同一 ComponentActivity 测试规则的公开 Activity，避免不安全 Context 转型或复制 unwrap。
        compose.runOnIdle {
            // 复用生产浅色窗口策略；完整 Insets 与系统图标对比度都必须反映实际入口。
            compose.activity.enableTorrentLightEdgeToEdge()
            configureTorrentEnglishProcess(InstrumentationRegistry.getInstrumentation().targetContext)
        }
        compose.setContent {
            // outerView 是测试规则的外层 Compose 所有者，必须在创建嵌套宿主前明确其 Insets 传递约定。
            val outerView = LocalView.current
            key(scale.floatValue) {
                // configuration、context 分别固定英文/字号与真实 View 配置，不能只覆盖 LocalDensity。
                val configuration = Configuration(LocalConfiguration.current).apply {
                    setLocales(LocaleList.forLanguageTags("en-US"))
                    fontScale = scale.floatValue
                }
                val context = ContextThemeWrapper(LocalContext.current, 0).apply { applyOverrideConfiguration(configuration) }
                AndroidView(modifier = Modifier.fillMaxSize(), factory = {
                    forwardOuterWindowInsets(outerView)
                    // ComposeView 使用宿主 Activity 生命周期，重建时随 AndroidView 释放。
                    ComposeView(context).apply {
                        innerComposeView = this
                        // attached 在嵌套 View 已加入树后请求系统重发，避免首轮分发发生在 factory 创建之前。
                        doOnAttach { attached -> ViewCompat.requestApplyInsets(attached) }
                        setContent {
                            // density 与三个 bottom 值属于真实内层组合，不读取外层 Insets 以免改变其消费行为。
                            val density = LocalDensity.current
                            val navigationBottom = WindowInsets.navigationBars.getBottom(density)
                            val mandatoryBottom = WindowInsets.mandatorySystemGestures.getBottom(density)
                            val safeBottom = WindowInsets.safeDrawing.getBottom(density)
                            SideEffect {
                                composeBottomInsets = "compose navigation=$navigationBottom mandatory=$mandatoryBottom safe=$safeBottom"
                            }
                            MaterialTheme {
                                Surface(Modifier.fillMaxSize()) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                        Box(Modifier.width(320.dp).fillMaxSize()) { content() }
                                    }
                                }
                            }
                        }
                    }
                })
            }
        }
        // fontScale 分别覆盖默认、常用放大和无障碍最大常见字号，不改系统设置。
        for (fontScale in listOf(1f, 1.3f, 2f)) {
            progressStage = "$stage/$fontScale"
            markProgress("composition-start")
            compose.runOnIdle { scale.floatValue = fontScale }
            compose.waitForIdle()
            forwardNativeWindowInsets()
            compose.waitForIdle()
            markProgress("before-check-start")
            beforeCheck()
            // 点击只投递打开事件；先等真实 Dialog 组合完成，不能在窗口尚未创建时就认定 IME 已隐藏。
            compose.waitForIdle()
            markProgress("before-check-end")
            // Dialog 有独立焦点所有者，须逐窗口清焦点并有限等待原生 IME 消失。
            clearFocusAndHideIme()
            // Dialog 有独立 Window，不能把 320dp 页面容器误当作弹窗实际宽度。
            compose.runOnIdle { constrainDialogWindows() }
            compose.waitForIdle()
            forwardNativeWindowInsets()
            compose.waitForIdle()
            screenshot("$stage-$fontScale-start")
            // index 与 label 分别标识低敏诊断顺序及静态/合成文本，诊断标记不输出文本。
            for ((index, label) in labels.withIndex()) {
                markProgress("label=$index-start")
                runCatching { checkLabel(stage, fontScale, label, label in allowedSummaries) }
                    .onFailure { failure ->
                        // failure 仍保留原始布局失败，不能因滚动受限就将标签视为通过。
                        failures += "$stage/$fontScale: ${failure.message}"
                        markProgress("label=$index-failed")
                    }
                markProgress("label=$index-end")
            }
            screenshot("$stage-$fontScale-end")
            markProgress("scale-complete")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** 只调整本测试进程的 Compose Dialog 窗口；不使用反射、系统显示设置或真实业务 Activity。 */
    private fun constrainDialogWindows() {
        // widthPixels 由固定 320dp 和当前设备密度换算，与字体放大无关。
        val widthPixels = (320 * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density).toInt()
        // root 是 WindowInspector 返回的同进程窗口根，不枚举其他应用窗口。
        for (root in WindowInspector.getGlobalWindowViews()) constrainDialogInView(root, widthPixels)
    }

    /** 外层测试容器不消费供生产页面使用的 Insets；不读取外层 Compose Insets 或添加固定边距。 */
    private fun forwardOuterWindowInsets(view: View) {
        // outer 是当前所有者最近的 AbstractComposeView；声明在嵌套 View 首次组合之前。
        val outer = generateSequence(view) { ancestor -> ancestor.parent as? View }
            .filterIsInstance<AbstractComposeView>().firstOrNull()
            ?: throw AssertionError("Outer ComposeView is missing; cannot establish Insets forwarding")
        outer.consumeWindowInsets = false
        ViewCompat.requestApplyInsets(outer)
    }

    /** 将原生窗口当前真实 Insets 分发给嵌套组合，诊断 AndroidView 中途消费；不构造或改写数值。 */
    private fun forwardNativeWindowInsets() {
        compose.runOnIdle {
            // inner/insets 分别是当前字号的实际 View 和系统提供的完整窗口边界，均不得为空兜底零值。
            val inner = innerComposeView ?: throw AssertionError("Inner ComposeView is missing")
            val insets = ViewCompat.getRootWindowInsets(inner)
                ?: throw AssertionError("Inner ComposeView has no native WindowInsets")
            ViewCompat.dispatchApplyWindowInsets(inner, insets)
        }
        markProgress("native-insets-dispatched")
    }

    /** 遍历公开 View 层级查找 Compose 提供的窗口接口；固定宽度保留正文滚动与包裹高度。 */
    private fun constrainDialogInView(view: View, widthPixels: Int) {
        if (view is DialogWindowProvider) view.window.setLayout(widthPixels, WindowManager.LayoutParams.WRAP_CONTENT)
        if (view is ViewGroup) {
            // index 只遍历当前窗口直接子 View，不保存 View/Window 引用。
            for (index in 0 until view.childCount) constrainDialogInView(view.getChildAt(index), widthPixels)
        }
    }

    /** 通过生产滚动容器完整定位；省略摘要只测实际可见字符，其他文本仍要求全文可读。 */
    private fun checkLabel(stage: String, scale: Float, label: String, allowSummary: Boolean) {
        // dialogVisible、matcher 将弹窗断言限制在真实 Dialog 窗口，防止同名背景按钮替代目标。
        val dialogVisible = compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        val matcher = if (dialogVisible) hasText(label) and hasAnyAncestor(isDialog()) else hasText(label)
        // nodes 优先读取未合并的 Text 叶子，避免父 Card 的读屏全文掩盖视觉截断。
        val nodes = compose.onAllNodes(matcher, useUnmergedTree = true)
        scrollController.reveal(matcher)
        assertTrue("missing: $label", nodes.fetchSemanticsNodes().isNotEmpty())
        nodes[0].assertIsDisplayed()
        // layouts 获取 Text 排版密度、可见行边界与省略信息，不复制生产 maxLines 规则。
        val layouts = mutableListOf<TextLayoutResult>()
        nodes[0].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getResults -> getResults(layouts) }
        assertTrue("no text layout: $label", layouts.isNotEmpty())
        // layout 对应单个真实文本节点；允许一像素的栅格化舍入误差。
        for (layout in layouts) {
            assertTrue("$label scale=${layout.layoutInput.density.fontScale}, expected=$scale",
                kotlin.math.abs(layout.layoutInput.density.fontScale - scale) < 0.01f)
            assertTrue("$stage clipped height: $label", layout.multiParagraph.height <= layout.size.height + 1f)
            // line 是实际绘制行；Android 的省略尾行逻辑边界不能代表剩余可见字符的边界。
            for (line in 0 until layout.lineCount) {
                if (allowSummary && layout.isLineEllipsized(line)) {
                    assertTrue("$stage unexpected summary overflow mode: $label", layout.layoutInput.overflow == TextOverflow.Ellipsis)
                    // visibleStart/visibleEnd 只覆盖实际显示的字符，不把已由省略号替代的尾部当作裁切。
                    val visibleStart = layout.getLineStart(line)
                    val visibleEnd = layout.getLineEnd(line, visibleEnd = true)
                    assertTrue("$stage empty summary: $label line=$line", visibleEnd > visibleStart)
                    assertTrue("$stage clipped visible summary: $label line=$line width=${layout.size.width}",
                        (visibleStart until visibleEnd).all { offset ->
                            // bounds 来自真实 TextLayoutResult 的字符边界，不按字号估算宽度。
                            val bounds = layout.getBoundingBox(offset)
                            bounds.left >= -1f && bounds.right <= layout.size.width + 1f
                        })
                } else {
                    assertTrue("$stage clipped glyph: $label line=$line left=${layout.getLineLeft(line)} right=${layout.getLineRight(line)} width=${layout.size.width}",
                        layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
                }
            }
            if (!allowSummary) {
                assertTrue("$stage truncated: $label", !layout.multiParagraph.didExceedMaxLines &&
                    (0 until layout.lineCount).none { line -> layout.isLineEllipsized(line) })
            }
            // node 与 viewportHeight 共同区分可滚动长段落和本应一次完整显示的短标签。
            val node = nodes[0].fetchSemanticsNode()
            // safeBounds 来自当前真实窗口，不能用 Compose 自报零 Insets 掩盖导航条实际覆盖。
            val safeBounds = compose.runOnIdle { torrentEnglishNativeSafeBounds(node) }
            val viewportHeight = generateSequence(node.parent) { parent -> parent.parent }
                .filter { parent -> parent.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                .map { parent -> parent.boundsInRoot.height }.minOrNull()?.coerceAtMost(safeBounds.height) ?: safeBounds.height
            if (layout.size.height <= viewportHeight + 1f) {
                assertTrue("$stage partially visible label: $label height=${node.boundsInRoot.height}/${layout.size.height}",
                    node.boundsInRoot.height + 1f >= layout.size.height)
                assertTrue("$stage label overlaps native navigation area: $label bottom=${node.boundsInRoot.bottom} safe=${safeBounds.bottom}",
                    node.boundsInRoot.bottom <= safeBounds.bottom + 1f)
            }
        }
    }

    /** 截图只保存独立测试 APK 的合成界面；文件名不携带搜索词、URL 或用户路径。 */
    private fun screenshot(name: String) {
        markProgress("screenshot-start name=$name")
        assertProcessWindowFocused("$name-before")
        assertTrue("$name-before: IME obscures screenshot", !isImeVisible())
        // instrumentation 提供真实设备截图，directory 仅位于独立测试应用的外部私有目录。
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "english-all-pages").apply { mkdirs() }
        // nativeInsets 只含窗口序号和像素值；与内层 Compose 对照才能判断生产或宿主边界问题。
        val nativeInsets = compose.runOnIdle {
            WindowInspector.getGlobalWindowViews().mapIndexed { index, root ->
                // insets 是当前进程窗口的系统原始边界，不枚举其他应用窗口或读取业务内容。
                val insets = ViewCompat.getRootWindowInsets(root)
                "window=$index height=${root.height} navigation=${insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom} " +
                    "mandatory=${insets?.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())?.bottom} " +
                    "imeVisible=${insets?.isVisible(WindowInsetsCompat.Type.ime())}"
            }.joinToString("\n")
        }
        File(directory, "$name-insets.txt").writeText("$composeBottomInsets\n$nativeInsets\n")
        // bitmap 与 output 只在本次写入期间持有，随后释放设备截图内存。
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            File(directory, "$name.png").outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
            assertProcessWindowFocused("$name-after")
            assertTrue("$name-after: IME obscures screenshot", !isImeVisible())
        } finally {
            bitmap.recycle()
        }
        markProgress("screenshot-end name=$name")
    }

    /** 逐个真实窗口清除焦点并收键盘；不用 Espresso 空闲循环，5 秒内未关闭就明确失败。 */
    private fun clearFocusAndHideIme() {
        markProgress("ime-hide-start")
        // deadline 是真实时间上限；hiddenSince 要求至少连续半秒隐藏，防止打开弹窗后迟到的 IME 请求。
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var hiddenSince: Long? = null
        clearWindowFocusAndHideIme()
        while (SystemClock.uptimeMillis() < deadline) {
            if (isImeVisible()) {
                hiddenSince = null
                clearWindowFocusAndHideIme()
            } else if (hiddenSince == null) {
                hiddenSince = SystemClock.uptimeMillis()
            } else if (SystemClock.uptimeMillis() - hiddenSince >= 500L) {
                markProgress("ime-stably-hidden")
                compose.waitForIdle()
                if (!isImeVisible()) {
                    markProgress("ime-hide-end")
                    return
                }
                hiddenSince = null
            }
            SystemClock.sleep(50L)
        }
        throw AssertionError("$progressStage: IME remained visible after 5 seconds")
    }

    /** 让测试 DecorView 接收焦点，防止 clearFocus 后框架再次把焦点交给弹窗第一输入框。 */
    private fun clearWindowFocusAndHideIme() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // root 只来自本测试进程；焦点承接只在此 Activity 生命周期内生效，不改变生产输入控件。
            for (root in WindowInspector.getGlobalWindowViews()) {
                root.findFocus()?.clearFocus()
                root.isFocusableInTouchMode = true
                if (root is ViewGroup) {
                    // previous 暂存子树焦点规则，仅在根请求焦点这一刻阻止输入框重新抢占。
                    val previous = root.descendantFocusability
                    root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    root.requestFocus()
                    root.descendantFocusability = previous
                } else root.requestFocus()
                root.context.getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(root.windowToken, 0)
            }
        }
    }

    /** 只检查本进程原生 IME Insets；截图前后再次调用，避免等待结束后重现遮挡。 */
    private fun isImeVisible(): Boolean {
        // visible 在主线程读取后同步返回，既不读取输入值，也不枚举其他应用窗口。
        var visible = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            visible = WindowInspector.getGlobalWindowViews().any { root ->
                ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
        }
        return visible
    }

    /** 记录可定位的低敏阶段；卡住时保留最后一步，不输出标签、输入值或真实用户数据。 */
    private fun markProgress(step: String) {
        // message 与 directory 仅用于独立测试产物，生产代码没有新增日志或文件副作用。
        val message = "${SystemClock.uptimeMillis()} $progressStage $step"
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "english-all-pages").apply { mkdirs() }
        Log.i("TorrentEnglishLayout", message)
        File(directory, "torrent-progress.txt").appendText("$message\n")
    }

    /** 截图前后都须由本进程持有焦点，系统充电等对话框遮挡不能被计作有效视觉通过。 */
    private fun assertProcessWindowFocused(stage: String) {
        assertTrue("$stage: test window lost focus; screenshot is not valid visual evidence", compose.runOnIdle {
            // root 仅来自本进程公开窗口清单，不读取 SystemUI 或其他应用内容。
            WindowInspector.getGlobalWindowViews().any { root -> root.hasWindowFocus() }
        })
    }
}
