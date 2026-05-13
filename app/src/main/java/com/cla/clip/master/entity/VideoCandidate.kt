package com.cla.clip.master.entity

import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import androidx.navigation.NavType
import com.cla.clip.base.general.dao.DownloadTaskData
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_DOWNLOADING
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_FAILED
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_MERGING
import com.cla.clip.base.general.dao.DownloadTaskData.Companion.STATUS_SUCCESS
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
@Parcelize
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
) : Parcelable

/**
 * VideoCandidate 的导航参数序列化适配器。
 *
 * 目前下载任务已通过数据库 id 导航为主，这个类型仍保留给需要直接传递候选对象的导航路径；序列化时使用 JSON 并做 URI 编码。
 */
object VideoCandidateNavType : NavType<VideoCandidate>(isNullableAllowed = false) {

    /** 导航参数 JSON 配置，忽略未知字段以兼容后续 VideoCandidate 字段扩展。 */
    private val json = Json {
        ignoreUnknownKeys = true // 反序列化时忽略未知字段，增加兼容性
        encodeDefaults = true // 序列化时包含默认值字段，确保完整性
    }

    /** 将候选对象写入 Bundle，供 Navigation 在进程内传递。 */
    override fun put(bundle: Bundle, key: String, value: VideoCandidate) {
        bundle.putString(key, json.encodeToString(value))
    }

    /** 从 Bundle 读取候选对象；缺失或空值时返回 null。 */
    override fun get(bundle: Bundle, key: String): VideoCandidate? {
        return bundle.getString(key)?.let { json.decodeFromString<VideoCandidate>(it) }
    }

    /** 解析路由字符串参数，必须先 URI decode 再按 JSON 反序列化。 */
    override fun parseValue(value: String): VideoCandidate {
        return json.decodeFromString(Uri.decode(value))
    }

    /** 将候选对象序列化为可放入路由路径的字符串，避免 URL 特殊字符破坏导航匹配。 */
    override fun serializeAsValue(value: VideoCandidate): String {
        return Uri.encode(json.encodeToString(value))
    }
}

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
