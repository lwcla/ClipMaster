package com.cla.clip.master.ui.page.video

import com.cla.clip.base.general.dao.DownloadTaskData
import org.junit.Assert.*
import org.junit.Test

/** 查看任务不能隐式重置 Worker 已经写入的下载/合并状态。 */
class VideoDownloadStartPolicyTest {
    /** 冷启动取消 Worker 后，遗留进度不再永久显示运行中；只有手动重试才重置。 */
    @Test fun cancelledWorkCanBeRetriedWithoutResettingOnView() {
        assertTrue(VideoDownloadStartPolicy.isInterrupted(DownloadTaskData.STATUS_DOWNLOADING, false))
        assertTrue(VideoDownloadStartPolicy.isInterrupted(DownloadTaskData.STATUS_MERGING, false))
        assertFalse(VideoDownloadStartPolicy.isInterrupted(DownloadTaskData.STATUS_SUCCESS, false))
        assertFalse(VideoDownloadStartPolicy.isInterrupted(DownloadTaskData.STATUS_DOWNLOADING, true))
        assertFalse(VideoDownloadStartPolicy.shouldRetry(DownloadTaskData.STATUS_MERGING, false, false))
        assertTrue(VideoDownloadStartPolicy.shouldRetry(DownloadTaskData.STATUS_MERGING, true, false))
        assertFalse(VideoDownloadStartPolicy.shouldRetry(DownloadTaskData.STATUS_MERGING, true, true))
    }
    /** 观察任何任务都不发起下载，仅明确重试失败任务才允许重置。 */
    @Test fun viewingOrRetryingRunningTaskNeverResetsProgress() {
        assertFalse(VideoDownloadStartPolicy.shouldRetry(DownloadTaskData.STATUS_DOWNLOADING, false, true))
        assertFalse(VideoDownloadStartPolicy.shouldRetry(DownloadTaskData.STATUS_MERGING, true, true))
        assertFalse(VideoDownloadStartPolicy.shouldRetry(DownloadTaskData.STATUS_SUCCESS, true, false))
        assertFalse(VideoDownloadStartPolicy.shouldRetry(null, true, false))
        assertFalse(VideoDownloadStartPolicy.shouldRetry(DownloadTaskData.STATUS_FAILED, false, false))
        assertTrue(VideoDownloadStartPolicy.shouldRetry(DownloadTaskData.STATUS_FAILED, true, false))
    }
}
