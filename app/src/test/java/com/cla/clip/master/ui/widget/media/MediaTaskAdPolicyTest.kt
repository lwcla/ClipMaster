package com.cla.clip.master.ui.widget.media

import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.feature.ad.api.AdPlacement
import com.cla.clip.master.entity.VideoCandidate
import com.cla.clip.master.entity.VideoDownloadState
import com.cla.clip.master.ui.page.video.ProbeState
import com.cla.clip.master.ui.page.video.videoExtractAdPlacement
import com.cla.clip.master.ui.page.video.videoDownloadAdPlacement
import com.cla.clip.master.ui.page.image.imageBatchAdPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 媒体广告仅由真实内容和阶段决定，避免空态、错误态或进度重组触发请求。 */
class MediaTaskAdPolicyTest {
    /** 无候选、无预览的提取进度必须保持无广告。 */
    @Test fun extractionRequiresContentBeyondTitle() {
        assertNull(videoExtractAdPlacement(ProbeState.HiddenProbing(0), false))
        assertEquals(AdPlacement.VideoExtractNative, videoExtractAdPlacement(ProbeState.HiddenProbing(0), true))
        assertNull(videoExtractAdPlacement(ProbeState.Failed, true))
    }

    /** 捕获真实候选后沿用提取广告位，不因提取完成切换广告单元。 */
    @Test fun extractionCandidateKeepsExtractionPlacement() {
        // 合成资源用于表达有效候选，不包含真实用户 URL 或鉴权信息。
        val candidate = VideoCandidate("https://example.test/a.mp4", null, null, null, "fixture")
        assertEquals(AdPlacement.VideoExtractNative, videoExtractAdPlacement(ProbeState.Success(candidate), false))
    }

    /** 下载和合并共享进度位，完成才切换到结果位，失败不显示。 */
    @Test fun videoPhaseSeparatesResultWithoutReloadingProgress() {
        assertEquals(AdPlacement.VideoDownloadProgressNative, videoDownloadAdPlacement(VideoDownloadState.Downloading(5), true))
        assertEquals(AdPlacement.VideoDownloadProgressNative, videoDownloadAdPlacement(VideoDownloadState.Merging(80), true))
        assertEquals(AdPlacement.VideoDownloadResultNative, videoDownloadAdPlacement(VideoDownloadState.Success("content://fixture/1"), true))
        assertNull(videoDownloadAdPlacement(VideoDownloadState.Failed(null), true))
        assertNull(videoDownloadAdPlacement(VideoDownloadState.Idle, true))
        assertNull(videoDownloadAdPlacement(VideoDownloadState.Downloading(5), false))
    }

    /** 图片结果仅在成功或部分成功且实际媒体非空时具备展示资格。 */
    @Test fun imagePhaseRejectsEmptyFilteredAndErrorResults() {
        assertEquals(AdPlacement.ImageDownloadProgressNative, imageBatchAdPlacement(ImageExtractBatchData.STATUS_DOWNLOADING, true))
        assertEquals(AdPlacement.ImageDownloadResultNative, imageBatchAdPlacement(ImageExtractBatchData.STATUS_SUCCESS, true))
        assertEquals(AdPlacement.ImageDownloadResultNative, imageBatchAdPlacement(ImageExtractBatchData.STATUS_PARTIAL_SUCCESS, true))
        assertNull(imageBatchAdPlacement(ImageExtractBatchData.STATUS_SUCCESS, false))
        assertNull(imageBatchAdPlacement(ImageExtractBatchData.STATUS_FAILED, true))
        assertNull(imageBatchAdPlacement(ImageExtractBatchData.STATUS_FILTERED, true))
    }
}
