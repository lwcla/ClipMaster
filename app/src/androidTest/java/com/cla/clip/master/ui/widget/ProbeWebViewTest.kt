package com.cla.clip.master.ui.widget

import android.net.Uri
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 仅在隔离测试设备执行，使用 about:blank 和合成回调，不访问用户网页或账号。 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class ProbeWebViewTest {
    /** 无业务 Activity 的 Compose 测试宿主。 */
    @get:Rule val compose = createComposeRule()

    /** 用真实 DOM 点击计数证明触摸开关生效，不只断言平台配置字段。 */
    @Test fun hiddenPageBlocksTouchesAndVisiblePageReceivesThem() {
        // 全部内容内嵌在合成页面中，不联网、不保存用户数据。
        val url = "data:text/html;charset=utf-8," + Uri.encode("""
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="margin:0;height:100vh" onclick="window.clicks=(window.clicks||0)+1">test</body></html>
        """.trimIndent())
        // 开关变化只触发 update，测试要求网页实例和 DOM 计数保持连续。
        val blocked = mutableStateOf(true)
        val finished = AtomicInteger(0)
        val views = mutableListOf<WebView>()
        compose.setContent {
            ProbeWebView(url, modifier = Modifier.size(320.dp).testTag("probe"), consumeUserTouch = blocked.value,
                onWebViewReady = { views.add(it) }, onPageFinished = { _, _ -> finished.incrementAndGet() },
                shouldInterceptRequest = { _, _ -> null })
        }
        compose.waitUntil(10_000) { finished.get() > 0 }
        compose.onNodeWithTag("probe").performTouchInput { click() }
        assertEquals("0", readClicks(views.single()))
        compose.runOnIdle { blocked.value = false }
        compose.onNodeWithTag("probe").performTouchInput { click() }
        assertEquals("1", readClicks(views.single()))
        compose.runOnIdle { blocked.value = true }
        compose.onNodeWithTag("probe").performTouchInput { click() }
        assertEquals("1", readClicks(views.single()))
        compose.runOnIdle { assertEquals(1, views.size) }
    }

    /** 即使组合还留在树中，STOP 也必须立即封闭原生回调。 */
    @Test fun stopClosesCallbacksBeforeCompositionRemoval() {
        // 独立生命周期故意不挂载业务 ProbeLifecycleEffect，验证共享宿主自己的释放边界。
        val owner = TestOwner()
        val views = mutableListOf<WebView>()
        val events = mutableListOf<ProbeWebViewEvent>()
        val finished = AtomicInteger(0)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                ProbeWebView("about:blank", onWebViewReady = { views.add(it) },
                    onPageFinished = { _, _ -> finished.incrementAndGet() }, shouldInterceptRequest = { _, _ -> null },
                    onLoadEvent = { events.add(it) })
            }
        }
        compose.waitUntil(10_000) { finished.get() == 1 }
        compose.runOnIdle {
            // 保留释放前 client，模拟平台迟到事件。
            val client = views.single().webViewClient
            events.clear()
            owner.registry.currentState = Lifecycle.State.CREATED
            client.onPageStarted(views.single(), "about:blank", null)
            assertTrue(events.isEmpty())
        }
    }

    /** JS 回调异步返回，等待有界结果以读取实际网页可观察行为。 */
    private fun readClicks(view: WebView): String {
        // 测试线程只读取原子快照，所有 WebView 调用留在 UI 线程。
        val reply = AtomicReference<String?>(null)
        compose.runOnIdle { view.evaluateJavascript("window.clicks || 0") { reply.set(it) } }
        compose.waitUntil(5_000) { reply.get() != null }
        return reply.get()!!
    }

    /** 测试专用生命周期，不连接实际 Activity 或设备 Home 状态。 */
    private class TestOwner : LifecycleOwner {
        /** 显式驱动 START/STOP 以模拟页面可见性。 */
        val registry = LifecycleRegistry(this)
        /** 供共享网页组件订阅的标准生命周期。 */
        override val lifecycle: Lifecycle get() = registry
    }

    /** 显隐只更新原生交互配置，不能创建第二个网页或重新触发加载。 */
    @Test fun interactionUpdatesKeepSameWebViewAndNavigation() {
        // 切换参数模拟视频页展开/收起，不修改 session。
        val blocked = mutableStateOf(true)
        val attached = mutableStateOf(true)
        // 网页引用只在 UI 线程使用，完成计数支持测试线程等待。
        val views = mutableListOf<WebView>()
        val finished = AtomicInteger(0)
        compose.setContent {
            if (attached.value) {
                ProbeWebView("about:blank", modifier = Modifier.size(320.dp), consumeUserTouch = blocked.value,
                    onWebViewReady = { views.add(it) }, onPageFinished = { _, _ -> finished.incrementAndGet() },
                    shouldInterceptRequest = { _, _ -> null })
            }
        }
        compose.waitUntil(10_000) { finished.get() == 1 }
        compose.runOnIdle {
            assertEquals(1, views.size)
            assertFalse(views.single().isFocusable)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, views.single().importantForAccessibility)
            blocked.value = false
        }
        compose.runOnIdle {
            assertEquals(1, views.size)
            assertTrue(views.single().isFocusableInTouchMode)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, views.single().importantForAccessibility)
            assertEquals(1, finished.get())
            blocked.value = true
        }
        compose.runOnIdle {
            assertFalse(views.single().isFocusable)
            assertEquals(1, finished.get())
            attached.value = false
        }
        compose.runOnIdle { assertNull(views.single().parent) }
    }

    /** 子资源 HTTP 错误不影响页面，主文档错误去重，移除后旧 client 不再发布事件。 */
    @Test fun errorsAreMainFrameOnlyAndOldClientClosesOnRelease() {
        // 组合移除模拟页面 STOP 后不再挂载网页。
        val attached = mutableStateOf(true)
        val views = mutableListOf<WebView>()
        val events = mutableListOf<ProbeWebViewEvent>()
        val finished = AtomicInteger(0)
        compose.setContent {
            if (attached.value) ProbeWebView("about:blank", modifier = Modifier.size(320.dp),
                onWebViewReady = { views.add(it) }, onPageFinished = { _, _ -> finished.incrementAndGet() },
                shouldInterceptRequest = { _, _ -> null }, onLoadEvent = { events.add(it) })
        }
        compose.waitUntil(10_000) { finished.get() == 1 }
        // 旧 client 引用用于模拟释放后才到达的原生回调。
        var oldClient: android.webkit.WebViewClient? = null
        compose.runOnIdle {
            // 当前网页及其 client 只在主线程访问。
            val view = views.single()
            val client = view.webViewClient
            oldClient = client
            // 使用明确的当前文档回调，不访问网络。
            client.onPageStarted(view, "about:blank", null)
            events.clear()
            // HTTP 403 是合成错误；正文流为空，不保存真实响应。
            val response = WebResourceResponse("text/html", "utf-8", 403, "Forbidden", emptyMap(), null)
            client.onReceivedHttpError(view, Request(false), response)
            assertTrue(events.isEmpty())
            client.onReceivedHttpError(view, Request(true), response)
            client.onReceivedHttpError(view, Request(true), response)
            assertEquals(1, events.filterIsInstance<ProbeWebViewEvent.Failed>().size)
            attached.value = false
        }
        compose.runOnIdle {
            events.clear()
            oldClient!!.onPageStarted(views.single(), "about:blank", null)
            assertTrue(events.isEmpty())
        }
    }

    /** 只提供平台错误过滤所需的合成请求，不包含鉴权头。 */
    private class Request(
        /** 控制主文档/子资源身份，以验证错误传播边界。 */
        private val mainFrame: Boolean,
    ) : WebResourceRequest {
        /** 所有请求均指向测试空白页。 */
        override fun getUrl(): Uri = Uri.parse("about:blank")
        /** 由测试显式指定错误来源。 */
        override fun isForMainFrame(): Boolean = mainFrame
        /** 合成错误不包含重定向。 */
        override fun isRedirect(): Boolean = false
        /** 非用户点击产生的请求。 */
        override fun hasGesture(): Boolean = false
        /** 使用最小只读请求方法。 */
        override fun getMethod(): String = "GET"
        /** 测试不携带 Cookie 或其他敏感上下文。 */
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
