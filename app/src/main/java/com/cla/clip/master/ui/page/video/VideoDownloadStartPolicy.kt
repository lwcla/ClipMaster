package com.cla.clip.master.ui.page.video

import com.cla.clip.base.general.dao.DownloadTaskData

/** 下载页面只观察，重置必须由显式失败重试触发，不干扰运行中的 Worker。 */
internal object VideoDownloadStartPolicy {
    /** 仅无活动 Worker 的失败/中断任务接受手动重试，查看和运行中的任务永不归零。 */
    fun shouldRetry(status: String?, explicitRetry: Boolean, hasActiveWork: Boolean): Boolean =
        explicitRetry && !hasActiveWork && (status == DownloadTaskData.STATUS_FAILED || isInterrupted(status, false))

    /** 持久化进度仍在运行但调度器无活动任务时，应提供中断重试入口。 */
    fun isInterrupted(status: String?, hasActiveWork: Boolean): Boolean =
        !hasActiveWork && (status == DownloadTaskData.STATUS_DOWNLOADING || status == DownloadTaskData.STATUS_MERGING)
}
