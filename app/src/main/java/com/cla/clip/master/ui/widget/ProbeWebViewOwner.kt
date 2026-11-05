package com.cla.clip.master.ui.widget

import android.webkit.WebView
import android.webkit.WebViewClient

/** 当前组合内的网页资源所有者；STOP 可直接清理，不依赖暂停后的重组执行。 */
internal class ProbeWebViewOwner(
    /** 回调和资源释放共享同一个原子门闩。 */
    private val session: ProbeLoadSession,
) {
    /** 只在主线程访问的平台实例，不向 ViewModel 或单例泄露。 */
    private var webView: WebView? = null

    /** 创建后登记唯一实例；若生命周期已关闭则立即销毁迟到创建的资源。 */
    fun attach(view: WebView) {
        if (session.acceptsCallbacks) webView = view else destroy(view)
    }

    /** 生命周期或组合离场均可调用，只有首次关闭者执行平台销毁。 */
    fun release() {
        if (!session.close()) return
        webView?.let { destroy(it) }
        webView = null
    }

    /** 先停止加载和媒体，再移除回调与子 View；不使用影响其他网页的全局 pauseTimers。 */
    private fun destroy(view: WebView) {
        view.stopLoading()
        view.onPause()
        view.webChromeClient = null
        view.webViewClient = WebViewClient()
        view.removeAllViews()
        view.destroy()
    }
}
