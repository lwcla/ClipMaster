package com.cla.clip.master.entity

import com.cla.clip.base.general.dao.DownloadTaskData
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_DOWNLOADING
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_FAILED
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_MERGING
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_SUCCESS

/**
 * 视频提取阶段捕获到的候选下载地址。
 *
 * 该对象只在提取页到下载任务创建之间传递，保存真实视频 URL 以及 WebView 请求上下文，下载 Worker 会复用这些头信息降低反盗链失败概率。
 */
data class VideoCandidate(
    /** 视频资源地址，可能是 mp4/webm/flv 直链，也可能是 m3u8/mpd 等播放列表地址。 */
    val url: String,

    /** WebView 请求中的 Referer，可能为空；下载时原样带回，避免部分站点拒绝跨站请求。 */
    val referer: String?,

    /** WebView 请求中的 User-Agent，可能为空；下载时用于模拟同一浏览环境。 */
    val userAgent: String?,

    /** WebView CookieManager 中读取到的 Cookie，可能为空；仅用于当前下载请求，不单独持久化为用户可见数据。 */
    val cookie: String?,

    /** 生成视频文件名的基础名称，通常来自网页标题或剪贴板内容，保存时会追加 mp4 后缀。 */
    val fileName: String
)

/**
 * 视频下载页 UI 状态。
 *
 * 由 Room 中的 DownloadTaskData 映射而来，只表达页面展示需要的信息，不直接暴露数据库状态字符串。
 */
sealed class VideoDownloadState {
    /** 初始或未知状态，页面展示“准备下载”。 */
    object Idle : VideoDownloadState()

    data class Downloading(
        /** 下载进度百分比，范围会在映射时收敛到 0..100。 */
        val progress: Int
    ) : VideoDownloadState()

    data class Merging(
        /** M3U8 分片合并进度百分比，范围会在映射时收敛到 0..100。 */
        val progress: Int
    ) : VideoDownloadState()

    data class Success(
        /** 下载完成后可打开的视频 URI 或路径；为空时成功提示仍展示，但点击播放不会执行。 */
        val savePath: String?
    ) : VideoDownloadState()

    data class Failed(
        /** 下载失败原因，可能来自网络、媒体校验或文件保存；为空时 UI 展示通用失败文案。 */
        val errorMsg: String?
    ) : VideoDownloadState()
}

/** 将数据库下载任务映射成视频下载页状态，统一收敛进度范围并屏蔽数据库状态字符串。 */
fun DownloadTaskData.toUi() = when (status) {
    STATUS_DOWNLOADING -> {
        VideoDownloadState.Downloading(progress.coerceIn(0, 100))
    }

    STATUS_MERGING -> {
        VideoDownloadState.Merging(progress.coerceIn(0, 100))
    }

    STATUS_SUCCESS -> {
        VideoDownloadState.Success(savePath)
    }

    STATUS_FAILED -> {
        VideoDownloadState.Failed(errorMsg)
    }

    else -> VideoDownloadState.Idle
}
