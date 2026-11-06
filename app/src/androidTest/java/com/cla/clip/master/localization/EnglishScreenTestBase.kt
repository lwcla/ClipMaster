package com.cla.clip.master.localization

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import android.view.ContextThemeWrapper
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.cla.clip.master.ui.theme.ClipMasterTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule

/** 全页面英文验收共用的设备宿主；复用真实组件，不为测试复制页面布局。 */
abstract class EnglishScreenTestBase {
    /** 每个测试使用独立 Activity/组合，测试本身只操作合成数据。 */
    @get:Rule open val compose: ComposeContentTestRule = createComposeRule()

    /** 当前字号只属于测试组合，不写手机的系统设置。 */
    private var activeScale = 1f
    /** 当前场景允许省略的用户内容摘要；固定操作和说明不得随意豁免。 */
    private var truncationAllowed: (String) -> Boolean = { false }
    /** 各字号的失败汇总，保证首个截断仍能留下后续字号的证据。 */
    private val failures = mutableListOf<String>()

    /** 原始进程配置清理动作只跨越单个用例，不泄漏到后续测试类。 */
    private var restoreProcessConfiguration: (() -> Unit)? = null

    /** 父类 Before 先于真实页面 Before 执行，捕获任何英文覆盖之前的语言状态。 */
    @Before fun captureOriginalProcessConfiguration() {
        // 所有资源配置更新均在主线程，与 Activity 的配置分发顺序一致。
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            restoreProcessConfiguration = captureProcessConfigurationRestorer(instrumentation.targetContext)
        }
    }

    /** 即便字号断言失败也恢复进程配置；后续资源回退测试不得继承英文实验环境。 */
    @After fun restoreOriginalProcessConfiguration() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            restoreProcessConfiguration?.invoke()
            restoreProcessConfiguration = null
        }
    }

    /** 挂载真实页面，逐字号检查首屏与交互后状态；一个测试方法只调用一次。 */
    protected fun verifyEnglish(
        // stage 是稳定的合成场景 ID，也用作截图文件名前缀。
        stage: String,
        // labels 是交互后必须实际可见的关键文案，空列表仍检查所有可见 Text。
        labels: List<String> = emptyList(),
        // allowEllipsis 必须只覆盖明确允许省略的文件名、用户内容或入口摘要。
        allowEllipsis: (String) -> Boolean = { false },
        // prepare 在截图之前设置合成搜索条件，避免真实页面带入本机应用列表等无关内容。
        prepare: (ComposeContentTestRule) -> Unit = {},
        // exercise 只做当前场景的滚动/展开，不提交真实业务或权限动作。
        exercise: (ComposeContentTestRule) -> Unit = {},
        // content 必须调用生产呈现函数，合成状态和空回调由领域测试提供。
        content: @Composable () -> Unit,
    ) {
        // scale 每次变化会重建局部 View Context，使 Dialog 同样拿到真实字体配置。
        val scale = mutableFloatStateOf(1f)
        failures.clear()
        truncationAllowed = allowEllipsis
        // Activity 已启动后再同步进程语言，保证上下文外的 ICU/DateUtils 也按英文生成元信息。
        compose.runOnIdle { configureEnglishProcess(InstrumentationRegistry.getInstrumentation().targetContext) }
        installEnglishContent {
            key(scale.floatValue) {
                // configuration 保留设备密度/窗口，仅把本测试宿主改为英文和目标字号。
                val configuration = Configuration(LocalConfiguration.current).apply {
                    setLocales(LocaleList.forLanguageTags("en-US"))
                    fontScale = scale.floatValue
                }
                // englishContext 以 Activity 为基础，保留弹窗 token 和生命周期。
                val englishContext = ContextThemeWrapper(LocalContext.current, 0).apply {
                    applyOverrideConfiguration(configuration)
                }
                AndroidView(modifier = Modifier.fillMaxSize(), factory = {
                    // ComposeView 随 AndroidView 移除释放；不持有后台任务或用户数据。
                    ComposeView(englishContext).apply { setContent {
                        ClipMasterTheme(darkTheme = false, dynamicColor = false) {
                            Surface(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                    Box(Modifier.width(320.dp)) { content() }
                                }
                            }
                        }
                    } }
                })
            }
        }
        // fontScale 覆盖正常、中等放大和无障碍大字号；不依赖手机原字号。
        for (fontScale in listOf(1f, 1.3f, 2f)) {
            activeScale = fontScale
            compose.runOnIdle { scale.floatValue = fontScale }
            compose.waitForIdle()
            prepare(compose)
            constrainDialogWindows()
            checkAndCapture("$stage-top")
            try {
                exercise(compose)
                compose.waitForIdle()
                checkAndCapture("$stage-end")
                // label 要求视觉可见，不能仅凭读屏语义包含全文便判定通过。
                for (label in labels) {
                    // 弹窗前景存在时只认其自身标签，避免背景同文操作代替被挤出的按钮。
                    val hasDialog = compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
                    // matcher 将同文标签限定在前景窗口，防止背景内容掩盖弹窗按钮丢失。
                    val matcher = if (hasDialog) hasText(label) and hasAnyAncestor(isDialog()) else hasText(label)
                    // nodes 保留未合并文字节点，只有实际存在且显示的标签才通过。
                    val nodes = compose.onAllNodes(matcher, useUnmergedTree = true)
                    if (nodes.fetchSemanticsNodes().isEmpty()) {
                        failures += "$stage/$fontScale missing label: $label"
                    } else {
                        nodes[0].assertIsDisplayed()
                    }
                }
            } catch (error: AssertionError) {
                failures += "$stage/$fontScale interaction: ${error.message}"
                captureEnglish("$stage-interaction-failed")
            }
        }
        assertTrue(failures.distinct().joinToString("\n"), failures.isEmpty())
    }

    /** 普通合成场景使用空 Activity；完整页面用例覆写此入口以保留 Hilt Activity 宿主。 */
    protected open fun installEnglishContent(content: @Composable () -> Unit) {
        compose.setContent(content)
    }

    /** 供长列表/弹窗在每次滚动后复用；先截图，再检查当前可见 Text 的实际排版。 */
    protected fun checkAndCapture(stage: String) {
        compose.waitForIdle()
        constrainDialogWindows()
        captureEnglish(stage)
        // nodes 来自未合并语义树，包含各 Text 的真实排版动作，避免父容器全文掩盖截断。
        val nodes = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true,
        ).fetchSemanticsNodes()
        // node 是当前可读文字的独立语义节点，逐个核对其布局与祖先滚动契约。
        for (node in nodes) {
            // 可滚动祖先允许视口外内容暂不可见；无滚动祖先的隐藏文字必须报告，不能静默跳过。
            val scrollable = generateSequence(node.parent) { parent -> parent.parent }.any { parent ->
                parent.config.contains(SemanticsProperties.VerticalScrollAxisRange) ||
                    parent.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
            }
            if (scrollable && (node.boundsInRoot.width <= 0f || node.boundsInRoot.height <= 0f)) continue
            // layouts 接收真实 Text 的排版结果，不能用语义字符串长度代替字形测量。
            val layouts = mutableListOf<TextLayoutResult>()
            compose.runOnIdle { node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) }
            // layout 可能来自同一语义节点的多个实际文本，分别保留失败证据。
            for (layout in layouts) {
                // text 只来自合成场景和资源，不记录真实用户剪贴或凭据。
                val text = layout.layoutInput.text.text
                if (text.isBlank()) continue
                if (kotlin.math.abs(layout.layoutInput.density.fontScale - activeScale) > 0.01f) {
                    failures += "$stage/$activeScale wrong font scale: $text"
                }
                // 下拉菜单必须展示完整选项，不能因与背景筛选摘要同文而继承省略白名单。
                val inPopup = generateSequence(node.parent) { parent -> parent.parent }
                    .any { parent -> parent.config.contains(SemanticsProperties.IsPopup) }
                if (truncationAllowed(text) && !inPopup) continue
                // 行边界才是实际文字宽度；paragraph.width 是预留宽度，不能用来判断溢出。
                val clipped = layout.multiParagraph.height > layout.size.height + 1f ||
                    layout.multiParagraph.didExceedMaxLines ||
                    (0 until layout.lineCount).any { line ->
                        layout.isLineEllipsized(line) || layout.getLineRight(line) > layout.size.width + 1f ||
                            layout.getLineLeft(line) < -1f
                    }
                if (clipped) failures += "$stage/$activeScale clipped: $text size=${layout.size} lines=${layout.lineCount}" +
                    " paragraphHeight=${layout.multiParagraph.height} exceeded=${layout.multiParagraph.didExceedMaxLines}" +
                    " edges=${(0 until layout.lineCount).map { line -> Triple(layout.getLineLeft(line), layout.getLineRight(line), layout.isLineEllipsized(line)) }}"
                if (!scrollable && (node.boundsInRoot.height + 1f < layout.size.height || node.boundsInRoot.width + 1f < layout.size.width)) {
                    failures += "$stage/$activeScale outside non-scrollable viewport: $text"
                }
            }
        }
    }

    /** 整页集成沿真实竖向滚动容器逐屏检查，列表下方区域不以首屏截图代替。 */
    protected fun scrollAndInspect(stage: String) {
        // step 只限制异常无限滚动，正常设置/列表到末尾后立即结束。
        for (step in 0 until 64) {
            // scrollables 只收集竖向滚动能力，避免横向筛选条影响正文覆盖判断。
            val scrollables = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange), useUnmergedTree = true)
            // snapshots 固定本次定位所使用的节点顺序，下一次滚动后重新读取。
            val snapshots = scrollables.fetchSemanticsNodes()
            // index 选择尚未到末尾且有可见视口的容器；全部结束时返回负值。
            val index = snapshots.indexOfFirst { node ->
                // range 来自真实滚动状态，不根据已加载列表数量猜测终点。
                val range = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                range != null && range.value() + 1f < range.maxValue() && node.boundsInRoot.height > 0f
            }
            if (index < 0) return
            // 小视口保留重叠区域，不能固定大步长跳过中间未检查的内容。
            val distance = (snapshots[index].boundsInRoot.height * 0.7f).coerceAtLeast(1f)
            scrollables[index].performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(0f, distance) }
            checkAndCapture("$stage-scroll-$step")
        }
        failures += "$stage/$activeScale scroll coverage exceeded 64 steps"
    }

    /** Compose Dialog 是独立窗口，须额外约束真实窗口宽度，不能只约束页面父容器。 */
    private fun constrainDialogWindows() {
        if (Build.VERSION.SDK_INT < 29) return
        // widthPixels 使用设备原密度将统一 320dp 压力宽度转换为原生窗口尺寸。
        val widthPixels = (320 * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density).toInt()
        compose.runOnIdle {
            // root 限于本测试进程，不枚举或修改其他应用窗口。
            for (root in WindowInspector.getGlobalWindowViews()) constrainDialogView(root, widthPixels)
        }
        compose.waitForIdle()
    }

    /** 只通过公开窗口接口调整 Dialog，保持原正文滚动和内容高度。 */
    private fun constrainDialogView(view: View, widthPixels: Int) {
        if (view is DialogWindowProvider) view.window.setLayout(widthPixels, view.window.attributes.height)
        if (view is ViewGroup) {
            // index 仅遍历当前层，不在测试结束后保存平台 View 引用。
            for (index in 0 until view.childCount) constrainDialogView(view.getChildAt(index), widthPixels)
        }
    }

    /** 保存当前测试屏幕，系统状态栏仅含设备图标，不包含原应用业务内容。 */
    protected fun captureEnglish(stage: String) {
        // instrumentation 仅用于本轮测试的截图与配置查询。
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // target 始终来自独立测试包，不读取用户原应用数据。
        val target = instrumentation.targetContext
        // directory 属于独立包的外部私有目录，取回证据后随测试包统一清理。
        val directory = File(target.getExternalFilesDir(null), "english-all-pages").apply { mkdirs() }
        // file 只以稳定合成场景和字号命名，不包含用户内容。
        val file = File(directory, "$stage-$activeScale.png")
        // bitmap 在写出 PNG 后立即释放，避免多页验收累计原生图像内存。
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        file.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
        bitmap.recycle()
        if (Build.VERSION.SDK_INT >= 29) {
            // 系统充电/权限对话框会遮住截图而不改变 Compose 语义，必须明确失败，禁止算作视觉通过。
            compose.runOnIdle {
                assertTrue("Screenshot obscured by another application or system window: $stage",
                    WindowInspector.getGlobalWindowViews().any { root -> root.hasWindowFocus() })
            }
        }
    }
}
