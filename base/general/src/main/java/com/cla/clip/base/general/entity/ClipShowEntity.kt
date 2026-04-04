package com.cla.clip.base.general.entity

import androidx.compose.ui.graphics.Color
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import com.cla.clip.base.general.dao.data.ClipDetail
import com.cla.clip.base.general.utils.toRelativeTimeSpanString

data class ClipShowEntity(
    val id: Long,
    val content: String,
    val timestamp: Long,
    val formattedTime: String,
    val appName: String,
    val appIconPath: String?,
    val appColor: Color?,
    val borderColor: Color?,
    val isPinned: Boolean,
    val linkImgUrl: String?,
    val link: String?,
    val linkTitle: String?,
)

fun ClipDetail.toUi(): ClipShowEntity {
    // 1. 处理空安全
    val app = this.sourceApp
    val linkPreview = this.linkPreview
    val clip = this.clip

    // 2. 颜色转换逻辑 (例如处理默认色)
    val color = runCatching {
        val primaryColor = app?.primaryColor
        // 如果 primaryColor 不为 null，创建一个 20%透明度 的 Color 对象，否则返回 null
        if (primaryColor != null) Color(primaryColor.red, primaryColor.green, primaryColor.blue, alpha = 61) else null
    }.getOrNull()

    // 3. 时间格式化
    val timeStr = clip.timestamp.toRelativeTimeSpanString()

    return ClipShowEntity(
        id = clip.id,
        content = clip.content,
        timestamp = clip.timestamp,
        formattedTime = timeStr,
        // 如果关联不到 App，显示默认名
        appName = app?.appName ?: "unknown",
        appIconPath = app?.iconPath,
        appColor = app?.primaryColor?.let { Color(it.red, it.green, it.blue) },
        borderColor = color,
        isPinned = clip.pinnedTime != 0L,
        link = linkPreview?.link,
        linkImgUrl = linkPreview?.imageUrl,
        linkTitle = linkPreview?.title
    )
}

fun List<ClipDetail>.toUi() = this.map { it.toUi() }