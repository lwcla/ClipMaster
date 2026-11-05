package com.cla.clip.master.ui.widget

import java.util.concurrent.atomic.AtomicBoolean

/** 一个 WebView 实例的加载及回调边界；释放标记可在网络拦截线程安全读取。 */
internal class ProbeLoadSession {
    /** 主线程递增的文档代次；网页重定向不改变外层 WebView 的 session。 */
    var documentId: Int = 0
        private set
    /** 当前主文档最后一次错误，用于重复错误去重和禁止错误页触发业务扫描。 */
    var documentFailure: ProbeWebFailure? = null
        private set
    /** 错误按最新导航目标匹配；提交前 getUrl 可能仍为旧页，不能要求与当前显示地址相等。 */
    fun claimDocumentFailure(url: String?, currentUrl: String?, isMainFrame: Boolean, failure: ProbeWebFailure): Boolean {
        if (!acceptsCallbacks || !isMainFrame || url == null ||
            url != (startedDocumentUrl ?: currentUrl) || documentFailure == failure) return false
        documentFailure = failure
        return true
    }

    /** 主文档事件入口负责隔离子资源、过期文档与已释放实例。 */
    fun acceptsDocumentEvent(url: String?, currentUrl: String?, isMainFrame: Boolean): Boolean =
        acceptsCallbacks && isMainFrame && url != null && url == currentUrl &&
            (startedDocumentUrl == null || startedDocumentUrl == url)

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
        if (!acceptsCallbacks) return
        documentId += 1
        documentFailure = null
        startedDocumentUrl = url
        finishedDocumentUrl = null
    }

    /** 只有当前主文档的首次完成可以触发扫描，不屏蔽后续 JS/meta-refresh 导航。 */
    fun claimPageFinished(url: String?, currentUrl: String?): Boolean {
        if (!acceptsDocumentEvent(url, currentUrl, true) || url == finishedDocumentUrl) return false
        finishedDocumentUrl = url
        return true
    }

    /** 首次关闭者负责停止与销毁；STOP 和组合释放并发到达也只清理一次。 */
    fun close(): Boolean = closed.compareAndSet(false, true)
}
