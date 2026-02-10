package com.cla.clip.base.general.entity

import android.icu.text.RelativeDateTimeFormatter
import android.text.format.DateUtils
import android.text.format.DateUtils.SECOND_IN_MILLIS
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import com.cla.clip.base.general.dao.data.ClipWithSourceApp

data class ClipEntity(
    val id: Long,
    val content: String,
    val formattedTime: String,
    val appName: String,
    val appIconPath: String?,
    val appColor: Color?,
    // 链接预览相关 (如果没有连接预览，UImodel里可以用密封接口更高级的处理，这里简单处理)
    val hasLinkPreview: Boolean,
    val linkTitle: String?,
    val isPinned: Boolean
)

fun ClipWithSourceApp.toUi(): ClipEntity {
    // 1. 处理空安全
    val app = this.sourceApp
    val clip = this.clip

    // 2. 颜色转换逻辑 (例如处理默认色)
    val color = runCatching {
        val primaryColor = app?.primaryColor
        // 如果 primaryColor 不为 null，创建一个 20%透明度 的 Color 对象，否则返回 null
        if (primaryColor != null) Color(primaryColor.red, primaryColor.green, primaryColor.blue, alpha = 51) else null
    }.getOrNull()

    // 3. 时间格式化
    val now = System.currentTimeMillis()
    val diff = now - clip.timestamp
    val timeStr = if (diff < 1000) {
        // 直接返回 ICU 标准的 "现在" (Now)
        RelativeDateTimeFormatter.getInstance()
            .format(RelativeDateTimeFormatter.Direction.PLAIN, RelativeDateTimeFormatter.AbsoluteUnit.NOW)
            .toString()
    } else {
        // 超过1分钟还是用 DateUtils 处理比较方便
        DateUtils.getRelativeTimeSpanString(clip.timestamp, now, SECOND_IN_MILLIS).toString()
    }

    return ClipEntity(
        id = clip.id,
        content = clip.content,
        formattedTime = timeStr,
        // 如果关联不到 App，显示默认名
        appName = app?.appName ?: "unknown",
        appIconPath = app?.iconPath,
        appColor = color,
        hasLinkPreview = !clip.linkTitle.isNullOrEmpty(),
        linkTitle = clip.linkTitle,
        isPinned = clip.isPinned
    )
}

fun List<ClipWithSourceApp>.toUi() = this.map { it.toUi() }