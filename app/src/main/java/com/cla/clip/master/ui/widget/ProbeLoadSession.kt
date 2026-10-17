package com.cla.clip.master.ui.widget

import java.util.concurrent.atomic.AtomicBoolean

/** 一个 WebView 实例的加载及回调边界；释放标记可在网络拦截线程安全读取。 */
internal class ProbeLoadSession {
    /** 释放后立即拒绝网络与主线程回调，不转发给后续会话。 */
    private val closed = AtomicBoolean(false)
    /** 同一主文档完成只消费一次；JS 跳转的新文档仍需重新扫描/播放。 */
    private var finishedDocumentUrl: String? = null
    /** 当前主文档导航目标，拒绝导航途中旧文档的迟到完成。 */
    private var startedDocumentUrl: String? = null
    /** 首次请求的入口；不随 WebView 内部重定向改变。 */
    private var requestedTarget: String? = null
    /** 是否允许当前实例继续向业务层发送回调。 */
    val acceptsCallbacks: Boolean get() = !closed.get()

    /** 只按入口目标判断是否发起加载，WebView 内部重定向不参与身份。 */
    fun shouldLoad(targetUrl: String): Boolean {
        if (!acceptsCallbacks || targetUrl.isBlank() || requestedTarget == targetUrl) return false
        requestedTarget = targetUrl
        return true
    }

    /** 真实主文档导航开始时重置完成门闩，同 URL 主动刷新也视为新导航。 */
    fun beginDocument(url: String?) {
        startedDocumentUrl = url
        finishedDocumentUrl = null
    }

    /** 只有当前主文档的首次完成可以触发扫描，不屏蔽后续 JS/meta-refresh 导航。 */
    fun claimPageFinished(url: String?, currentUrl: String?): Boolean {
        if (!acceptsCallbacks || url == null || url != currentUrl || url == finishedDocumentUrl) return false
        if (startedDocumentUrl != null && startedDocumentUrl != url) return false
        finishedDocumentUrl = url
        return true
    }

    /** 在停止加载和销毁之前关闭回调通道，调用幂等。 */
    fun close() {
        closed.set(true)
    }
}
