package com.cla.clip.master.ui.widget

/** WebView 原生错误的最小摘要；只在 Kotlin 内传递，无需反射或序列化保留。 */
data class ProbeWebFailure(
    /** 平台错误类别，与 Android 原始 message 解耦。 */
    val kind: ProbeWebFailureKind,
    /** 网络错误码、HTTP 状态码或 SSL 错误码，不包含外部文本。 */
    val code: Int,
)

/** 仅报告可验证的平台事实，业务方决定失败或保留交互现场。 */
enum class ProbeWebFailureKind {
    /** WebView 网络加载错误。 */
    Network,
    /** 主文档返回 HTTP 错误状态。 */
    Http,
    /** SSL 校验失败，连接仍按默认取消策略处理。 */
    Ssl,
}

/** 共享网页加载事件；不传 URL、标题、Cookie、HTML 或页面实体。 */
sealed interface ProbeWebViewEvent {
    /** 所属主文档代次，业务会话还需校验 WebView session。 */
    val documentId: Int
    /** 新导航使旧文档诊断失效。 */
    data class Started(
        /** 新开始加载的主文档代次。 */
        override val documentId: Int,
    ) : ProbeWebViewEvent
    /** 进度只更新 UI，不逐回调打印日志。 */
    data class Progress(
        /** 进度所属的当前文档代次。 */
        override val documentId: Int,
        /** 平台报告的网页加载百分比。 */
        val percent: Int,
    ) : ProbeWebViewEvent
    /** 主文档完成不等同于视频识别成功。 */
    data class Finished(
        /** 完成回调所归属的主文档。 */
        override val documentId: Int,
    ) : ProbeWebViewEvent
    /** 仅转发当前主文档的错误，子资源错误在组件内部过滤。 */
    data class Failed(
        /** 出错文档身份，用于拒绝迟到错误。 */
        override val documentId: Int,
        /** 不含敏感原文的错误摘要。 */
        val error: ProbeWebFailure,
    ) : ProbeWebViewEvent
    /** 外部协议已被拦截，不将目标地址交给 UI。 */
    data class ExternalAppBlocked(
        /** 发起外部协议导航的文档，不携带目标 URL。 */
        override val documentId: Int,
    ) : ProbeWebViewEvent
}
