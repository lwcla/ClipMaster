package com.cla.clip.master.ui.page.image

import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.feature.ad.api.AdPlacement

/** 只有有真实媒体的下载进度或成功/部分成功结果可展示；过滤、错误和空批次禁用。 */
internal fun imageBatchAdPlacement(status: String, hasItems: Boolean): AdPlacement? {
    if (!hasItems) return null
    return when (status) {
        ImageExtractBatchData.STATUS_DOWNLOADING -> AdPlacement.ImageDownloadProgressNative
        ImageExtractBatchData.STATUS_SUCCESS, ImageExtractBatchData.STATUS_PARTIAL_SUCCESS -> AdPlacement.ImageDownloadResultNative
        else -> null
    }
}
