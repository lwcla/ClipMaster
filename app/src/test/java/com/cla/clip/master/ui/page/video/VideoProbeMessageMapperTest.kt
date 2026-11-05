package com.cla.clip.master.ui.page.video

import com.cla.clip.master.R
import com.cla.clip.master.ui.widget.ProbeWebFailure
import com.cla.clip.master.ui.widget.ProbeWebFailureKind
import org.junit.Assert.*
import org.junit.Test

/** 错误提示区分自动未命中与真实加载错误，并只透传数值参数。 */
class VideoProbeMessageMapperTest {
    /** 自动预算到期不能误导成连接超时，HTTP 错误需保留状态码。 */
    @Test fun extractionTimeoutDiffersFromHttpFailure() {
        assertEquals(VideoProbeMessage(R.string.video_probe_timeout), videoProbeFailureMessage(VideoProbeFailure.Timeout))
        assertEquals(VideoProbeMessage(R.string.video_probe_http, 403),
            videoProbeFailureMessage(VideoProbeFailure.PageLoad(ProbeWebFailure(ProbeWebFailureKind.Http, 403))))
    }

    /** 已知网络超时有明确提示，未知错误码安全降级而不是直接展示外部文本。 */
    @Test fun networkAndSslErrorsUseSafeSpecificMessages() {
        assertEquals(VideoProbeMessage(R.string.video_probe_network_timeout),
            videoProbeFailureMessage(VideoProbeFailure.PageLoad(ProbeWebFailure(ProbeWebFailureKind.Network, -8))))
        assertEquals(VideoProbeMessage(R.string.video_probe_network),
            videoProbeFailureMessage(VideoProbeFailure.PageLoad(ProbeWebFailure(ProbeWebFailureKind.Network, -999))))
        assertEquals(VideoProbeMessage(R.string.video_probe_ssl),
            videoProbeFailureMessage(VideoProbeFailure.PageLoad(ProbeWebFailure(ProbeWebFailureKind.Ssl, 3))))
    }
}
