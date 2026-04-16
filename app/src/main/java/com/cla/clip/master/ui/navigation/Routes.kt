package com.cla.clip.master.ui.navigation

import kotlinx.serialization.Serializable

sealed class Route

/** 主页面 */
@Serializable
data object MainRoute : Route()

/** 剪贴数据列表 */
@Serializable
data object ClipListRoute : Route()

/** 详情页 */
@Serializable
data class DetailRoute(val clipId: Long) : Route()


/** 视频链接提取 */
@Serializable
data class VideoExtractRoute(val url: String, val name: String) : Route()


/** 视频下载 */
@Serializable
data class VideoDownloadRoute(val taskId: Long) : Route()


/** 我的页面 */
@Serializable
data object MineRoute : Route()