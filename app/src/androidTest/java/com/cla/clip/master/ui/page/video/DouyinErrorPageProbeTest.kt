package com.cla.clip.master.ui.page.video

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume

/** 真 WebView 只加载合成 HTML，不访问网络、用户剪贴或登录凭据。 */
@RunWith(AndroidJUnit4::class)
class DouyinErrorPageProbeTest {
    /** 独立测试 Activity 提供正常布局，避免依赖业务页面数据。 */
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    /** 原生视图只允许在主线程调用，测试结束立即销毁。 */
    private lateinit var view: WebView

    /** 创建支持 JS 的实际网页容器，以验证可见性和 DOM 查询。 */
    @Before fun createView() {
        activity.scenario.onActivity { host ->
            view = WebView(host)
            view.settings.javaScriptEnabled = true
            host.setContentView(view)
        }
    }

    /** 清理全部测试网页资源，不改业务应用或浏览器配置。 */
    @After fun destroyView() {
        activity.scenario.onActivity { view.destroy() }
    }

    /** 错误区真实存在时，脚本识别并在第二次快照后结束等待。 */
    @Test fun visibleErrorConfirmsBeforeAutomaticTimeout() = runBlocking {
        load(errorHtml())
        assertEquals("true", evaluate(DOUYIN_ERROR_PAGE_JS))
        withContext(Dispatchers.Main) { assertTrue("合成 WebView 地址：${view.url}", isDouyinSharePage(view.url)) }
        assertTrue(withTimeout(3_000) { withContext(Dispatchers.Main) { DouyinErrorPageProbe.awaitError(view) } })
    }

    /** 祖先隐藏的历史错误节点不能提前结束仍在加载的网页。 */
    @Test fun hiddenErrorComponentsAreIgnored() = runBlocking {
        // 各种隐藏方式都是合成样式，不依赖站点在线资源。
        val attributes = listOf("style='display:none'", "style='visibility:hidden'", "style='opacity:0'", "hidden", "aria-hidden='true'")
        attributes.forEach { attribute ->
            load("<section $attribute>${errorHtml()}</section>")
            assertEquals(attribute, "false", evaluate(DOUYIN_ERROR_PAGE_JS))
        }
    }

    /** 普通打开按钮、正文中的错误文案及已有媒体源都应沿用原有提取。 */
    @Test fun ordinaryPromptsCommentsAndMediaSourcesAreIgnored() = runBlocking {
        // 精确文案也不能脱离已核验的错误组件结构触发失败。
        val fixtures = listOf("<button>打开抖音</button>",
            "<article>抱歉出错了<p>请尝试在抖音内观看</p><button>打开抖音</button></article>",
            errorHtml().replace("抱歉出错了", "正在加载"),
            errorHtml() + "<video src='data:video/mp4;base64,AA=='></video>")
        fixtures.forEach { fixture ->
            load(fixture)
            assertEquals("false", evaluate(DOUYIN_ERROR_PAGE_JS))
        }
    }

    /** 同样 DOM 出现在其他来源时不得成为抖音业务错误。 */
    @Test fun unrelatedPageIsNotClassified() = runBlocking {
        load(errorHtml(), "https://example.invalid/share/video/123/")
        assertEquals("false", evaluate(DOUYIN_ERROR_PAGE_JS))
        assertFalse(withContext(Dispatchers.Main) { DouyinErrorPageProbe.awaitError(view) })
    }

    /** 第一次快照后恢复正常，必须继续等待而非立即报告失败。 */
    @Test fun transientErrorDoesNotConfirmAndCancellationStopsProbe() = runBlocking {
        load(errorHtml())
        evaluate("setTimeout(function(){document.querySelector('.special-case-wrap').remove();}, 100); true")
        // 外层预算取消模拟页面离开；取消不能被探测器误吞并返回已确认错误。
        val result = withTimeoutOrNull(1_500) { withContext(Dispatchers.Main) { DouyinErrorPageProbe.awaitError(view) } }
        assertNull(result)
    }

    /** 使用合成作品号提供页面来源，内容直接注入，不请求该域名。 */
    private suspend fun load(body: String, baseUrl: String = "https://www.iesdouyin.com/share/video/123/") {
        // 完成信号只属于当前合成文档，后续 JS 等待均有独立上限。
        val loaded = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            view.webViewClient = object : WebViewClient() {
                /** 页面主文档完成后允许读取实际 DOM。 */
                override fun onPageFinished(view: WebView, url: String?) { loaded.complete(Unit) }
            }
            view.loadDataWithBaseURL(baseUrl,
                "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head><body>$body</body></html>",
                // historyUrl 同步原生 getUrl 与 DOM 来源；传 null 会让原生地址保持 about:blank。
                "text/html", "UTF-8", baseUrl)
        }
        withTimeout(5_000) { loaded.await() }
        // 等待真实布局完成，不以固定长睡眠掩盖页面渲染时序。
        withTimeout(5_000) {
            while (evaluate("document.body.getBoundingClientRect().height > 0") != "true") delay(20)
        }
    }

    /** 回调返回布尔/字符串编码原值，取消后不恢复测试协程。 */
    private suspend fun evaluate(script: String): String? = withContext(Dispatchers.Main) {
        withTimeout(2_000) {
            suspendCancellableCoroutine { continuation ->
                view.evaluateJavascript(script) { result ->
                    if (continuation.isActive) continuation.resume(result)
                }
            }
        }
    }

    /** 手工合成最小错误组件，不复制真实网页或用户数据。 */
    private fun errorHtml(): String = """
        <div class="special-case-wrap"><div class="special-case-container">
        <div class="special-case-title">抱歉出错了</div>
        <div class="special-case-subtitle">请尝试在抖音内观看</div>
        <div class="special-case-btn">打开抖音</div>
        </div></div>
    """.trimIndent()
}
