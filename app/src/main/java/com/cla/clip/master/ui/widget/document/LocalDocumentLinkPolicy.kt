package com.cla.clip.master.ui.widget.document

import java.net.URI

/** 仅用作 loadDataWithBaseURL 的虚拟文档身份；正文来自内存，绝不请求此地址。 */
internal const val LOCAL_DOCUMENT_URL = "https://appassets.androidplatform.net/document.html"

/** 仅供编译期分支使用的跳转结果，不参与持久化或序列化。 */
internal enum class LocalDocumentLinkAction {
    /** 当前正文或锚点由阅读器处理。 */
    Internal,
    /** 用户主动打开的 HTTPS/邮箱链接交给系统应用。 */
    External,
    /** 其他协议、无手势跳转和虚拟站点其他资源拒绝处理。 */
    Blocked,
}

/** 分类离线正文跳转；非法 URI 一律拒绝，防止虚拟文档地址被交给浏览器联网。 */
internal fun localDocumentLinkAction(url: String, hasGesture: Boolean): LocalDocumentLinkAction {
    if (url.substringBefore('#') == LOCAL_DOCUMENT_URL) return LocalDocumentLinkAction.Internal
    // URI 仅解析当前链接，不解析或输出正文；格式错误视为不可打开。
    val uri = runCatching { URI(url) }.getOrNull() ?: return LocalDocumentLinkAction.Blocked
    if (!hasGesture || uri.host.equals("appassets.androidplatform.net", ignoreCase = true)) {
        return LocalDocumentLinkAction.Blocked
    }
    return when {
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() -> LocalDocumentLinkAction.External
        uri.scheme.equals("mailto", ignoreCase = true) && !uri.schemeSpecificPart.isNullOrBlank() -> LocalDocumentLinkAction.External
        else -> LocalDocumentLinkAction.Blocked
    }
}
