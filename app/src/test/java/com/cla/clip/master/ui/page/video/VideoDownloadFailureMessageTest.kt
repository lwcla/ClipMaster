package com.cla.clip.master.ui.page.video

import com.cla.clip.base.general.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** 错误分类只返回资源 id，地址、Token 和外部正文不能进入失败文案。 */
class VideoDownloadFailureMessageTest {
    /** 任务缺失、鉴权、网络、权限和未知问题各有稳定兜底。 */
    @Test fun classifiesKnownFailuresWithoutReturningExternalContent() {
        assertEquals(R.string.base_general_the_download_task_was_not_found, videoDownloadFailureMessage(null, true))
        assertEquals(R.string.base_general_media_download_expired, videoDownloadFailureMessage("HTTP 403 Forbidden, url=https://example.test/?token=secret", false))
        assertEquals(R.string.base_general_media_download_network, videoDownloadFailureMessage("timeout", false))
        assertEquals(R.string.base_general_storage_permission_is_required, videoDownloadFailureMessage("Permission denied", false))
        assertEquals(R.string.base_general_video_download_failed, videoDownloadFailureMessage("unexpected body https://example.test/private", false))
    }
}
