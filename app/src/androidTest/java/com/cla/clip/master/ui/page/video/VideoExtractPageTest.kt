package com.cla.clip.master.ui.page.video

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cla.clip.base.general.dao.DownloadDao
import com.cla.clip.base.general.dao.LinkPreviewData
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.base.general.repository.DownloadRepository
import com.cla.clip.master.ui.theme.ClipMasterTheme
import com.cla.clip.master.utils.WebViewLinkPreviewExtractor
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 完整页面只注入合成数据；预览不落库，下载调用直接失败，允许在已授权真机执行。 */
@RunWith(AndroidJUnit4::class)
class VideoExtractPageTest {
    /** 独立 Activity 承载生产页面，避免导航进入用户剪贴列表。 */
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** 无服务器的 DOM 验证完整页面的显隐、真实点击、键盘和失败重试接线。 */
    @Test fun longTitleInteractivePageAndRetryWorkOnDevice() {
        // 长标题必然超三行，封面即使存在也不应占用页面空间。
        val title = "真机验收：这是一段用于检查三行省略和网页操作区域的合成视频标题。".repeat(8)
        // 合成表单不请求网络、不使用用户 Cookie，也不提交任何输入。
        val url = "data:text/html;charset=utf-8," + Uri.encode("""
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="margin:0;height:150vh;background:#e5f2ff" onclick="window.taps=(window.taps||0)+1">
            <div style="padding:20px">Synthetic interactive page</div>
            <input id="entry" placeholder="Test input" style="margin:16px;font-size:18px">
            </body></html>
        """.trimIndent())
        // 替身返回固定预览，所有非预览业务访问都抛错以防测试误触真实业务。
        val preview = LinkPreviewData(url, title, null, "data:image/png;base64,AA==", "测试")
        val clips = Proxy.newProxyInstance(ClipRepository::class.java.classLoader,
            arrayOf(ClipRepository::class.java)) { _, method, _ ->
            when (method.name) {
                "loadLinkPreview" -> preview
                "upsertLinkPreview" -> Unit
                else -> error("测试禁止仓库操作：${method.name}")
            }
        } as ClipRepository
        // 下载 DAO 不连接数据库；页面若误创建任务，测试立即失败。
        val downloads = Proxy.newProxyInstance(DownloadDao::class.java.classLoader,
            arrayOf(DownloadDao::class.java)) { _, method, _ ->
            error("测试禁止下载操作：${method.name}")
        } as DownloadDao
        // 真实 ViewModel 连接替身，完整复用页面会话和计时逻辑。
        val vm = VideoExtractVm(compose.activity.applicationContext, DownloadRepository(downloads),
            WebViewLinkPreviewExtractor(dagger.Lazy { clips }), clips)
        compose.setContent {
            ClipMasterTheme {
                VideoExtractPage(vm, url, title, onBack = {}, onNavigate = { error("不应进入下载") })
            }
        }
        compose.onNodeWithText("查看提取过程").assertIsDisplayed()
        // 使用生产文本布局结果确认三行省略，不复制排版规则。
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(title).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(3, layouts.single().lineCount)
        assertTrue(layouts.single().isLineEllipsized(2))
        compose.onNodeWithText("查看提取过程").performClick()
        compose.waitUntil(10_000) { vm.probeSession.pageState is VideoProbePageState.Loaded }
        // 原生引用只在主线程获取；之后通过测试主线程访问 DOM 和尺寸。
        val first = compose.runOnIdle { findWebView(compose.activity.window.decorView)!! }
        screenshot("expanded")
        // 根节点真实触摸穿过页面容器，证明网页没有被透明遮罩挡住。
        val position = compose.runOnIdle {
            // 将原生屏幕坐标转换为 Compose 根节点坐标。
            val location = IntArray(2)
            first.getLocationOnScreen(location)
            Offset(location[0] + first.width / 2f, location[1] + first.height * 0.7f)
        }
        // 根节点屏幕位置用于消除状态栏/测试宿主的偏移。
        val root = compose.onRoot().fetchSemanticsNode().boundsInWindow
        compose.onRoot().performTouchInput { click(position - root.topLeft) }
        assertEquals("1", javascript(first, "window.taps || 0"))
        compose.runOnIdle { first.requestFocus() }
        javascript(first, "document.getElementById('entry').focus(); true")
        compose.runOnIdle {
            // 只请求系统键盘显示，不向用户账号输入任何内容。
            (compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(first, InputMethodManager.SHOW_IMPLICIT)
        }
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.onNodeWithText("收起网页").assertIsDisplayed()
        screenshot("keyboard")
        compose.onNodeWithText("收起网页").performClick()
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == false
        }
        compose.runOnIdle { assertSame(first, findWebView(compose.activity.window.decorView)) }
        assertEquals("1", javascript(first, "window.taps || 0"))
        // 收起恢复十秒预算，等待真实超时后通过页面入口开启可见新轮次。
        compose.waitUntil(15_000) { vm.probeState is ProbeState.Failed }
        compose.onNodeWithText("查看网页并重试").assertIsDisplayed()
        screenshot("timeout")
        compose.onNodeWithText("查看网页并重试").performClick()
        compose.waitUntil(10_000) { vm.probeSession.pageState is VideoProbePageState.Loaded }
        compose.onNodeWithText("收起网页").assertIsDisplayed()
        compose.runOnIdle {
            assertNotSame(first, findWebView(compose.activity.window.decorView))
            assertEquals(VideoProbeMode.Interactive, vm.probeSession.mode)
        }
        // 使用实际 Activity STOP/START 验证资源回收，不只驱动合成 LifecycleOwner。
        val previousSession = compose.runOnIdle { vm.sessionId }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(ProbeState.Idle, vm.probeState)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) { vm.probeSession.pageState is VideoProbePageState.Loaded }
        compose.onNodeWithText("网页已重新加载，请重新播放或操作").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(vm.sessionId > previousSession)
            assertEquals(VideoProbeMode.Interactive, vm.probeSession.mode)
            // 保留域名没有真实服务器；仅触发 WebView 资源拦截以验证候选到成功态的生产接线。
            findWebView(compose.activity.window.decorView)!!.evaluateJavascript(
                "window.probeResource=new Image(); window.probeResource.src='https://example.invalid/fixture.mp4';", null)
        }
        compose.waitUntil(10_000) { vm.probeState is ProbeState.Success }
        compose.runOnIdle { assertNull(findWebView(compose.activity.window.decorView)) }
        screenshot("success")
    }

    /** 按原生树定位唯一网页；找不到返回空，不访问业务对象。 */
    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            // 下标仅用于遍历即时 View 树，不参与页面状态身份。
            for (index in 0 until view.childCount) {
                // 优先返回首个实际挂载的网页。
                val found = findWebView(view.getChildAt(index))
                if (found != null) return found
            }
        }
        return null
    }

    /** 有界等待实际 JS 结果，所有平台调用在 UI 线程执行。 */
    private fun javascript(view: WebView, script: String): String {
        // 原子结果连接 UI 线程回调和仪器线程等待。
        val result = AtomicReference<String?>(null)
        compose.runOnIdle { view.evaluateJavascript(script) { result.set(it) } }
        compose.waitUntil(5_000) { result.get() != null }
        return result.get()!!
    }

    /** 仅截取此合成 Activity，输出到应用外部测试文件以便 ADB 拉取人工检查。 */
    private fun screenshot(stage: String) {
        compose.waitForIdle()
        // 文件名前缀限定清理范围，不覆盖应用已有媒体。
        val target = File(compose.activity.getExternalFilesDir(null), "video-probe-qa-$stage.png")
        // 系统截图包含键盘，可检查操作按钮与网页区域是否仍可达。
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
