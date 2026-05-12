package com.cla.clip.master.ui.navigation

import kotlinx.serialization.Serializable

sealed class Route

/** 首页容器 */
@Serializable
data object MainRoute : Route()

/** 剪贴板列表 */
@Serializable
data object ClipListRoute : Route()

/** 剪贴板搜索 */
@Serializable
data object SearchRoute : Route()

/** 剪贴板详情 */
@Serializable
data class DetailRoute(val clipId: Long) : Route()

/** 网页视频提取 */
@Serializable
data class VideoExtractRoute(val url: String, val name: String) : Route()

/** 网页图片提取 */
@Serializable
data class ImageExtractRoute(val url: String, val name: String) : Route()

/** 视频下载详情 */
@Serializable
data class VideoDownloadRoute(val taskId: Long) : Route()

/** 我的页面 */
@Serializable
data object MineRoute : Route()
