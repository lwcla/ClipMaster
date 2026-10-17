package com.cla.clip.master.ui.page.video

import com.cla.clip.base.general.R

/** 原始 Worker 异常可能含签名 URL；只映射已知原因，永不直接展示外部错误文本。 */
internal fun videoDownloadFailureMessage(error: String?, taskMissing: Boolean): Int {
    if (taskMissing) return R.string.base_general_the_download_task_was_not_found
    // 仅匹配已知错误特征，未知外部内容始终降级为通用资源。
    val reason = error.orEmpty().lowercase()
    return when {
        reason == "download_interrupted" -> R.string.base_general_media_download_interrupted
        reason == "download_read_failed" -> R.string.base_general_media_read_failed
        reason.startsWith("http 401 ") || reason.startsWith("http 403 ") || reason.startsWith("http 404 ") -> R.string.base_general_media_download_expired
        reason.contains("permission denied") || reason.contains("eacces") -> R.string.base_general_storage_permission_is_required
        reason.contains("timeout") || reason.contains("timed out") || reason.contains("unable to resolve host") || reason.contains("failed to connect") -> R.string.base_general_media_download_network
        else -> R.string.base_general_video_download_failed
    }
}
