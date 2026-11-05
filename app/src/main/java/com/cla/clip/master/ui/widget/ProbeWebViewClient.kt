package com.cla.clip.master.ui.widget

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/** 共享网页原生回调适配；主线程发布诊断，网络线程只转发未释放会话的资源请求。 */
internal class ProbeWebViewClient(
    /** 本实例加载门闩，不随显隐重组更换。 */
    private val session: ProbeLoadSession,
    /** 新文档启动时让业务取消旧扫描/辅助任务。 */
    private val onDocumentStarted: () -> Unit,
    /** 只在正常主文档首次完成时触发业务扫描/播放。 */
    private val onDocumentFinished: (WebView, String?) -> Unit,
    /** 资源拦截仍保留当前实例的原始闭包，不转交新轮次。 */
    private val onRequest: (WebView, WebResourceRequest) -> WebResourceResponse?,
    /** 仅发布低敏加载事实，业务层决定自动失败还是保留交互现场。 */
    private val onEvent: (ProbeWebViewEvent) -> Unit,
) : WebViewClient() {
    /** 主文档导航更换诊断身份，释放后的回调不再影响页面。 */
    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (!session.acceptsCallbacks) return
        session.beginDocument(url)
        onDocumentStarted()
        onEvent(ProbeWebViewEvent.Started(session.documentId))
    }

    /** 仅拦截主文档的外部应用协议，保留正常站内导航。 */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        // 协议仅用于分类，不记录目标地址或查询参数。
        val external = request.isForMainFrame && isExternalAppScheme(request.url.scheme?.lowercase().orEmpty())
        if (external && session.acceptsCallbacks) onEvent(ProbeWebViewEvent.ExternalAppBlocked(session.documentId))
        return external
    }

    /** 完成回调去重；错误页不能触发预览缓存补全或自动播放。 */
    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        if (!session.claimPageFinished(url, view.url)) return
        onEvent(ProbeWebViewEvent.Finished(session.documentId))
        if (session.documentFailure == null) onDocumentFinished(view, url)
    }

    /** 子资源错误不终结业务，只有当前主文档网络错误向上报告。 */
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        super.onReceivedError(view, request, error)
        reportFailure(view, request.url.toString(), request.isForMainFrame,
            ProbeWebFailure(ProbeWebFailureKind.Network, error.errorCode))
    }

    /** HTTP 错误只保留状态码，不读取响应头或响应体。 */
    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        super.onReceivedHttpError(view, request, errorResponse)
        reportFailure(view, request.url.toString(), request.isForMainFrame,
            ProbeWebFailure(ProbeWebFailureKind.Http, errorResponse.statusCode))
    }

    /** SSL 保持取消；没有主框架标志时，由门闩按最新导航目标匹配，不能用尚未提交的 view.url。 */
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        reportFailure(view, error.url, true,
            ProbeWebFailure(ProbeWebFailureKind.Ssl, error.primaryError))
    }

    /** 网络线程读取原子释放标记，候选发布还需业务主线程再次验证 session。 */
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        if (session.acceptsCallbacks) onRequest(view, request) else null

    /** 共用主文档校验与错误去重入口；原始 URL 仅作内部比较，不出现在事件中。 */
    private fun reportFailure(view: WebView, url: String?, isMainFrame: Boolean, failure: ProbeWebFailure) {
        if (session.claimDocumentFailure(url, view.url, isMainFrame, failure)) {
            onEvent(ProbeWebViewEvent.Failed(session.documentId, failure))
        }
    }
}
