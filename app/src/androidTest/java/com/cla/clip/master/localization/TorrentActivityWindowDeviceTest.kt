package com.cla.clip.master.localization

import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真实可选功能 Activity 的窗口回归；必须使用隔离包的真实 App/Hilt，不通过导出入口或测试布局替身。 */
@RunWith(AndroidJUnit4::class)
class TorrentActivityWindowDeviceTest {
    /** 空规则只观察真实生产 Activity 的 Compose 树，不安装测试内容或嵌套 ComposeView。 */
    @get:Rule val compose = createEmptyComposeRule()

    /** probeNavigation/probeMandatory/probeSafe 保存公开组合边界；-1 明确表示探针尚未完成首次组合。 */
    private var probeNavigation = -1
    private var probeMandatory = -1
    private var probeSafe = -1

    /** 空 Intent 只进入搜索待机页，记录系统栏边界；不会发起搜索、浏览、解锁或下载。 */
    @Test fun realTorrentActivityWindow() {
        // instrumentation 与 target 只允许当前独立安装身份，拒绝误启动真实用户应用。
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assertTrue("Only the isolated localization application may be launched",
            target.packageName == "com.cla.clip.master.localizationcheck")
        // component 保持字符串契约，默认关闭可选模块时测试仍可编译并明确跳过。
        val component = ComponentName(target.packageName, "com.cla.clip.feature.torrent.search.TorrentSearchActivity")
        assumeTrue("Optional torrent module is disabled", runCatching {
            target.packageManager.getActivityInfo(component, 0)
        }.isSuccess)
        // restore 在任何断言/启动失败后还原测试进程资源，不污染随后运行的资源回退测试。
        var restore: (() -> Unit)? = null
        instrumentation.runOnMainSync {
            restore = captureProcessConfigurationRestorer(target)
            configureEnglishProcess(target)
        }
        try {
            // intent 不携带查询或外部数据，生产入口解析后只能等待显式用户搜索。
            val intent = Intent().setComponent(component)
            ActivityScenario.launch<ComponentActivity>(intent).use { scenario ->
                // idleLabel 是实际资源解析出的待机标记，必须等到旧数据门禁和设置初始化完成。
                var idleLabel = ""
                scenario.onActivity { activity ->
                    configureEnglishProcess(activity)
                    // 将进程内英文配置分发给现有真实组合，不覆盖生产 setContent 或窗口策略。
                    activity.onConfigurationChanged(Configuration(activity.resources.configuration))
                    // resourceId 避免 app 静态引用可选 feature 的 R 类，缺失时明确失败。
                    val resourceId = activity.resources.getIdentifier("torrent_search_idle_prompt", "string", target.packageName)
                    assertTrue("Torrent idle marker resource is missing", resourceId != 0)
                    idleLabel = activity.getString(resourceId)
                    // probe 附加到真实窗口的同一内容父容器，不占用焦点、无可点击内容且不绘制可见像素。
                    val probe = ComposeView(activity).apply {
                        alpha = 0f
                        isFocusable = false
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        setContent {
                            // density/navigation/mandatory/safe 均取公开 CompositionLocal 与 WindowInsets API。
                            val density = LocalDensity.current
                            val navigation = WindowInsets.navigationBars.getBottom(density)
                            val mandatory = WindowInsets.mandatorySystemGestures.getBottom(density)
                            val safe = WindowInsets.safeDrawing.getBottom(density)
                            SideEffect {
                                probeNavigation = navigation
                                probeMandatory = mandatory
                                probeSafe = safe
                            }
                        }
                    }
                    activity.addContentView(probe, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT))
                }
                compose.waitUntil(timeoutMillis = 30_000) {
                    runCatching {
                        compose.onAllNodes(hasText(idleLabel), useUnmergedTree = true)[0].assertIsDisplayed()
                    }.isSuccess
                }
                // directory 仅是独立测试包的外部私有产物位置，不包含用户输入或真实搜索来源。
                val directory = File(target.getExternalFilesDir(null), "english-all-pages").apply { mkdirs() }
                // 只观察生产窗口策略，不再由测试重设系统栏而掩盖入口的主题或 Insets 回归。
                compose.waitForIdle()
                // marker 保存生产 Text 的实际几何，不反射读取 Compose 内部 Insets 或替换页面内容。
                val marker = compose.onAllNodes(hasText(idleLabel), useUnmergedTree = true)[0]
                    .assertIsDisplayed().fetchSemanticsNode()
                scenario.onActivity { activity ->
                    // root/insets 来自未替换内容的真实 Activity 窗口，零值也原样记录用于 baseline 比较。
                    val root = activity.window.decorView
                    val insets = ViewCompat.getRootWindowInsets(root)
                        ?: throw AssertionError("Real torrent Activity has no WindowInsets")
                    // nativeMandatory/protectedBottom 是真实系统底部边界；生产原状就必须给 Compose 完整分发。
                    val nativeMandatory = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom
                    val protectedBottom = maxOf(nativeMandatory, insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
                    // controller 读取实际系统栏图标样式；固定浅色页面必须使用深色图标，避免随设备夜间模式变白。
                    val controller = WindowCompat.getInsetsController(activity.window, root)
                    assertTrue("Real torrent Activity is obscured", root.hasWindowFocus())
                    assertTrue("IME obscures real torrent Activity", !insets.isVisible(WindowInsetsCompat.Type.ime()))
                    assertTrue("Production status icons must contrast with the light page", controller.isAppearanceLightStatusBars)
                    // API 24/25 导航图标不能改成深色，由生产窗口的暗遮罩保证旧平台对比度。
                    if (Build.VERSION.SDK_INT >= 26) {
                        assertTrue("Production navigation icons must contrast with the light page", controller.isAppearanceLightNavigationBars)
                    }
                    assertTrue("Production Compose mandatory=$probeMandatory, native=$nativeMandatory",
                        probeMandatory == nativeMandatory)
                    assertTrue("Production idle text overlaps system navigation area",
                        marker.boundsInWindow.bottom <= root.height - protectedBottom + 1f)
                    File(directory, "real-torrent-activity-window-production-insets.txt").writeText(
                        "height=${root.height} width=${root.width} " +
                            "navigation=${insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom} " +
                            "mandatory=${insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom} " +
                            "status=${insets.getInsets(WindowInsetsCompat.Type.statusBars()).top}\n" +
                            "idleBoundsInRoot=${marker.boundsInRoot} idleBoundsInWindow=${marker.boundsInWindow}\n" +
                            "probe navigation=$probeNavigation mandatory=$probeMandatory safe=$probeSafe\n" +
                            "lightStatusBars=${controller.isAppearanceLightStatusBars} " +
                            "lightNavigationBars=${controller.isAppearanceLightNavigationBars}\n",
                    )
                }
                // bitmap 只记录真实待机界面，保存后立即释放像素内存。
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                try {
                    File(directory, "real-torrent-activity-window-production.png").outputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    }
                    scenario.onActivity { activity ->
                        assertTrue("Real torrent Activity lost focus during capture", activity.window.decorView.hasWindowFocus())
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            instrumentation.runOnMainSync { restore?.invoke() }
        }
    }
}
