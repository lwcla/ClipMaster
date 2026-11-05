package com.cla.clip.master.ui.page.video

import com.cla.clip.master.entity.VideoCandidate
import com.cla.clip.master.ui.widget.ProbeWebFailure

/** 提取页面的运行方式；仅为内存状态，不参与序列化或外部协议。 */
enum class VideoProbeMode {
    /** 默认隐藏网页，以短预算自动探测。 */
    Automatic,
    /** 用户展开网页，允许播放、输入和验证，等待用户操作。 */
    Interactive,
}

/** 视频提取阶段只由内存会话驱动，不持有 WebView，也不作为导航或序列化协议。 */
sealed interface ProbeState {
    /** 等待页面进入前台，或暂停后等待重建网页。 */
    data object Idle : ProbeState
    /** 自动/交互共用同一个运行阶段，模式变化不改变网页身份。 */
    data class Probing(
        /** 本轮网页身份，隔离重试和恢复前的异步结果。 */
        val sessionId: Int,
        /** 当前显示模式，决定触摸与计时策略。 */
        val mode: VideoProbeMode = VideoProbeMode.Automatic,
    ) : ProbeState
    /** 捕获候选后关闭网页，等待显式提交下载。 */
    data class Success(
        /** 仅由下载提交消费的候选，不得展开打印日志。 */
        val candidate: VideoCandidate,
    ) : ProbeState
    /** 已终止的失败轮次不再接收网页结果。 */
    data class Failed(
        /** 稳定失败分类用于生成资源文案，不携带网页原始文本。 */
        val reason: VideoProbeFailure = VideoProbeFailure.Timeout,
    ) : ProbeState
}

/** 自动未命中与平台加载失败分开表达，避免把所有失败都归为网络问题。 */
sealed interface VideoProbeFailure {
    /** 站点已呈现确定错误页，仅表示当前网页提取不可继续。 */
    data object PageContent : VideoProbeFailure
    /** 自动预算内未发现候选，不代表网站不支持。 */
    data object Timeout : VideoProbeFailure
    /** 已获知的网页加载错误，只保留分类及数值码。 */
    data class PageLoad(
        /** 平台错误摘要，不包含 URL 或外部错误文本。 */
        val error: ProbeWebFailure,
    ) : VideoProbeFailure
}

/** 当前主文档的可见状态；完成/错误不能被同文档后续百分比回调覆盖。 */
sealed interface VideoProbePageState {
    /** 当前文档代次，只在同一网页会话中比较。 */
    val documentId: Int
    /** 网页加载进度与视频提取进度不同，百分比只表示前者。 */
    data class Loading(
        /** 主文档代次，零表示首个开始回调尚未到达。 */
        override val documentId: Int = 0,
        /** 网页进度百分比，业务入口归一化为 0 到 100。 */
        val percent: Int = 0,
    ) : VideoProbePageState
    /** 网页已完成加载，仍需等待媒体请求。 */
    data class Loaded(
        /** 已完成加载的主文档代次，后续旧进度不能改写它。 */
        override val documentId: Int,
    ) : VideoProbePageState
    /** 交互模式保留错误现场，供用户查看或重新提取。 */
    data class Error(
        /** 发生错误的主文档，不能污染下一次导航。 */
        override val documentId: Int,
        /** 仅供错误映射的安全摘要。 */
        val failure: ProbeWebFailure,
    ) : VideoProbePageState
}

/** 当前页面的辅助提示，不持久化、不改变候选接受边界。 */
internal enum class VideoProbeNotice {
    /** 前台交互等待较久，仅提示，不关闭网页。 */
    LongRunning,
    /** 恢复后新建网页，告知用户不能恢复上一轮播放现场。 */
    Reloaded,
    /** 网页企图跳转外部应用，已按既有规则拦截。 */
    ExternalAppBlocked,
}
