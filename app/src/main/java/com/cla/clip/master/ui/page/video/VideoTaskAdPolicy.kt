package com.cla.clip.master.ui.page.video

import com.cla.clip.feature.ad.api.AdPlacement
import com.cla.clip.master.entity.VideoDownloadState

/** 提取阶段只有真实候选或缓存内容时展示广告；标题、纯加载和错误不构成展示资格。 */
internal fun videoExtractAdPlacement(state: ProbeState, hasPreviewContent: Boolean): AdPlacement? = when (state) {
    is ProbeState.Success -> AdPlacement.VideoExtractNative
    is ProbeState.HiddenProbing, is ProbeState.NeedUserPlay -> if (hasPreviewContent) AdPlacement.VideoExtractNative else null
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
