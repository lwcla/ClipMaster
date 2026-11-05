package com.cla.clip.master.ui.page.video

import com.cla.clip.feature.ad.api.AdPlacement
import com.cla.clip.master.entity.VideoDownloadState

/** 仅实际显示的描述或候选构成资格；可操作网页独占内容区，不请求或呈现广告。 */
internal fun videoExtractAdPlacement(state: ProbeState, hasPreviewContent: Boolean): AdPlacement? = when (state) {
    is ProbeState.Success -> AdPlacement.VideoExtractNative
    is ProbeState.Probing -> if (state.mode == VideoProbeMode.Automatic && hasPreviewContent) AdPlacement.VideoExtractNative else null
    else -> null
}

/** 下载和合并属于同一进度会话，成功结果切换单元；无实际任务、准备或失败均禁用。 */
internal fun videoDownloadAdPlacement(state: VideoDownloadState, hasTaskContent: Boolean): AdPlacement? {
    if (!hasTaskContent) return null
    return when (state) {
        is VideoDownloadState.Downloading, is VideoDownloadState.Merging -> AdPlacement.VideoDownloadProgressNative
        is VideoDownloadState.Success -> AdPlacement.VideoDownloadResultNative
        else -> null
    }
}
