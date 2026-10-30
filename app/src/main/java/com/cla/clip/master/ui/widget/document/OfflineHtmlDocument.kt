package com.cla.clip.master.ui.widget.document

import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 静态 HTML 阅读器；只接受调用方已读取的可信正文，不读取文件、联网或持有业务状态。
 * HTML 改变时重建实例；重组不重载。外链由调用方处理，暂停时停止活动，离开组合时销毁。
 */
@Composable
internal fun OfflineHtmlDocument(
    // 调用方提供的随包静态正文；不得传入用户网页或可执行脚本。
    html: String,
    // 只回传明确点击的 HTTPS/邮箱链接，系统启动属于页面职责。
    onOpenExternalLink: (String) -> Unit,
    // 只回传主文档数值错误码，页面负责显示资源化错误并记录低敏诊断。
    onLoadError: (Int) -> Unit,
    // 尺寸与 Insets 由使用页面提供。
    modifier: Modifier = Modifier,
) {
    // 回调随重组更新，但不能因此重载正文、丢失阅读位置。
    val openLink by rememberUpdatedState(onOpenExternalLink)
    val loadError by rememberUpdatedState(onLoadError)
    // 生命周期来源为当前导航目的地；后台和不可见目的地均暂停阅读实例。
    val owner = LocalLifecycleOwner.current
    key(html) {
        // 仅在当前组合中保存 View 引用，不跨进程或页面保存平台对象。
        var documentView by remember { mutableStateOf<WebView?>(null) }
        DisposableEffect(owner, documentView) {
            // 捕获本轮实例，清理时只移除其观察器，销毁统一交给 AndroidView。
            val view = documentView
            // 这里只调整单个实例；不使用会影响其他 WebView 的全局 pauseTimers。
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> view?.onResume()
                    Lifecycle.Event.ON_PAUSE -> view?.onPause()
                    else -> Unit
                }
            }
            owner.lifecycle.addObserver(observer)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view?.onResume() else view?.onPause()
            // 观察器解绑不清理全局 Cookie，也不干扰媒体提取页。
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
        AndroidView(
            modifier = modifier,
            // 在主线程创建平台 View；只在此处加载一次内存正文。
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = false
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient = object : WebViewClient() {
                        /** 内部锚点留在正文；外部应用只能由实际点击触发，拒绝其他协议。 */
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            // 链接仅用于分流与打开，不写入日志或历史数据库。
                            val url = request.url.toString()
                            return when (localDocumentLinkAction(url, request.hasGesture())) {
                                LocalDocumentLinkAction.Internal -> false
                                LocalDocumentLinkAction.External -> {
                                    if (request.isForMainFrame) openLink(url)
                                    true
                                }
                                LocalDocumentLinkAction.Blocked -> true
                            }
                        }

                        /** 子资源失败不覆盖正文；主文档失败交给页面显示明确错误。 */
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) loadError(error.errorCode)
                        }
                    }
                    loadDataWithBaseURL(LOCAL_DOCUMENT_URL, html, "text/html", "UTF-8", null)
                    documentView = this
                }
            },
            // 离开阅读页立即释放，避免隐藏 WebView 占用资源或持有 Activity。
            onRelease = { view ->
                view.stopLoading()
                view.onPause()
                view.removeAllViews()
                view.destroy()
            },
        )
    }
}
