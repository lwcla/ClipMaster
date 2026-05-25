package com.cla.clip.master.ui.navigation

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

/**
 * 应用内类型安全导航路由基类。
 *
 * 所有路由都需要可序列化，字段只放导航所需的最小参数；复杂业务对象应通过数据库 id 或仓库重新读取。
 * 类型安全导航会按默认完整类名解析 route serialName，必须保留类名避免 release R8 混淆后无法恢复路由。
 */
@Keep
sealed class Route

/** 首页容器 */
@Keep
@Serializable
data class MainRoute(
    /** 进入首页后默认选中的底部 Tab，用于跨流程收栈后明确回到“我的”页。 */
    val initialTab: MainInitialTab = MainInitialTab.List
) : Route()

/** 首页底部 Tab 的导航参数；类型安全路由会序列化枚举名，release R8 需要保留。 */
@Keep
@Serializable
enum class MainInitialTab {
    /** 剪贴列表 Tab。 */
    List,

    /** 我的 Tab。 */
    Mine
}

/** 剪贴板列表 */
@Keep
@Serializable
data object ClipListRoute : Route()

/** 剪贴板搜索 */
@Keep
@Serializable
data class SearchRoute(
    /** 搜索范围；普通搜索只查未折叠数据，折叠搜索只查折叠数据。 */
    val scope: SearchScope = SearchScope.VisibleOnly
) : Route()

/**
 * 搜索页路由层范围枚举。
 *
 * 路由只保存轻量范围值，页面进入后再转换为数据层 `ClipVisibilityScope`，避免把页面实现细节塞进导航调用点。
 */
@Keep
@Serializable
enum class SearchScope {
    /** 普通搜索，默认隐藏折叠数据。 */
    VisibleOnly,

    /** 折叠搜索，只搜索折叠数据。 */
    FoldedOnly
}

/** 剪贴板详情 */
@Keep
@Serializable
data class DetailRoute(
    /** 剪贴板记录 id，必须能在 ClipDao 中找到对应记录，否则详情页会展示不存在提示。 */
    val clipId: Long
) : Route()

/** 网页视频提取 */
@Keep
@Serializable
data class VideoExtractRoute(
    /** 需要加载并探测视频资源的网页 URL，通常来自剪贴板记录中的链接。 */
    val url: String,

    /** 生成下载文件名的网页名称，通常来自链接标题或剪贴板内容。 */
    val name: String
) : Route()

/** 网页图片提取 */
@Keep
@Serializable
data class ImageExtractRoute(
    /** 需要加载并扫描图片资源的网页 URL，必须是 WebView 可访问的 http/https 链接。 */
    val url: String,

    /** 图片批量下载的目录基础名称，最终保存时会再做唯一化处理。 */
    val name: String
) : Route()

/** 视频下载详情 */
@Keep
@Serializable
data class VideoDownloadRoute(
    /** 视频下载任务 id，对应 `download_tasks` 表中的主键。 */
    val taskId: Long
) : Route()

/** 下载记录页 */
@Keep
@Serializable
data object DownloadHistoryRoute : Route()

/** 折叠剪贴数据页 */
@Keep
@Serializable
data object FoldedClipsRoute : Route()

/** 回收站页 */
@Keep
@Serializable
data object RecycleBinRoute : Route()

/** 备份与恢复页 */
@Keep
@Serializable
data object BackupRoute : Route()

/** 备份恢复流程页 */
@Keep
@Serializable
data object BackupRestoreRoute : Route()

/** 恢复本地媒体关联页 */
@Keep
@Serializable
data class BackupMediaRelocationRoute(
    /** 恢复流程页当前任务 id，用于恢复页过滤媒体关联事件，避免旧流程串写回显。 */
    val restoreTaskId: String
) : Route()

/** 我的页面 */
@Keep
@Serializable
data object MineRoute : Route()
