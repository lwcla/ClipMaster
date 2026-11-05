package com.cla.clip.master.ui.page.video

import com.cla.clip.master.R
import com.cla.clip.master.ui.widget.ProbeWebFailureKind

/** 资源消息只携带可选数值参数，不保存外部网页错误原文。 */
internal data class VideoProbeMessage(
    /** 本应用可本地化的文案资源身份。 */
    val resourceId: Int,
    /** HTTP 状态码或加载百分比；无占位符时为空。 */
    val number: Int? = null,
)

/** 提取失败分类到用户文案的纯映射，未知平台错误按通用网络失败处理。 */
internal fun videoProbeFailureMessage(reason: VideoProbeFailure): VideoProbeMessage = when (reason) {
    VideoProbeFailure.Timeout -> VideoProbeMessage(R.string.host_video_probe_timeout)
    is VideoProbeFailure.PageLoad -> when (reason.error.kind) {
        ProbeWebFailureKind.Http -> VideoProbeMessage(R.string.host_video_probe_http, reason.error.code)
        ProbeWebFailureKind.Ssl -> VideoProbeMessage(R.string.host_video_probe_ssl)
        // WebViewClient.ERROR_TIMEOUT 的稳定数值为 -8；纯映射不依赖 Android runtime。
        ProbeWebFailureKind.Network -> VideoProbeMessage(
            if (reason.error.code == -8) R.string.host_video_probe_network_timeout else R.string.host_video_probe_network,
        )
    }
}
