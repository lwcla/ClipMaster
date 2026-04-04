package com.cla.clip.base.general.entity

import androidx.compose.ui.graphics.Color
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import com.cla.clip.base.general.dao.data.ClipDetail
import com.cla.clip.base.general.utils.toRelativeTimeSpanString

data class ClipEntity(
    val id: Long,
    val content: String,
    val timestamp: Long,
    val formattedTime: String,
    val appName: String,
    val appIconPath: String?,
    val appColor: Color?,
    val borderColor: Color?,
    // 链接预览相关 (如果没有连接预览，UImodel里可以用密封接口更高级的处理，这里简单处理)
    val hasLinkPreview: Boolean,
    val linkTitle: String?,
    val isPinned: Boolean
)

fun ClipDetail.toUi(): ClipEntity {
    // 1. 处理空安全
    val app = this.sourceApp
    val link = this.linkPreview
    val clip = this.clip

    // 2. 颜色转换逻辑 (例如处理默认色)
    val color = runCatching {
        val primaryColor = app?.primaryColor
        // 如果 primaryColor 不为 null，创建一个 20%透明度 的 Color 对象，否则返回 null
        if (primaryColor != null) Color(primaryColor.red, primaryColor.green, primaryColor.blue, alpha = 61) else null
    }.getOrNull()

    // 3. 时间格式化
    val timeStr = clip.timestamp.toRelativeTimeSpanString()

    return ClipEntity(
        id = clip.id,
        content = clip.content,
        timestamp = clip.timestamp,
        formattedTime = timeStr,
        // 如果关联不到 App，显示默认名
        appName = app?.appName ?: "unknown",
        appIconPath = app?.iconPath,
        appColor = app?.primaryColor?.let { Color(it.red, it.green, it.blue) },
        borderColor = color,
        hasLinkPreview = link?.link.isNullOrBlank().not(),
        linkTitle = link?.title,
        isPinned = clip.pinnedTime != 0L
    )
}

fun List<ClipDetail>.toUi() = this.map { it.toUi() }