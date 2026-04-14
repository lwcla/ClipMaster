package com.cla.clip.base.general.entity

import androidx.compose.ui.graphics.Color
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import com.cla.clip.base.general.dao.data.ClipDetail
import com.cla.clip.base.general.utils.toRelativeTimeSpanString
import kotlinx.serialization.Serializable

@Serializable
data class ClipShowEntity(
    val id: Long,
    val content: String,
    val timestamp: Long,
    val formattedTime: String,
    val appName: String?,
    val appIconPath: String?,
    val appColor: Color?,
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
    val appColor = app?.primaryColor?.takeIf { it != -1 }
    // 3. 时间格式化
    val timeStr = clip.timestamp.toRelativeTimeSpanString()

    return ClipShowEntity(
        id = clip.id,
        content = clip.content,
        timestamp = clip.timestamp,
        formattedTime = timeStr,
        // 如果关联不到 App，显示默认名
        appName = app?.appName?.takeIf { it.isNotBlank() },
        appIconPath = app?.iconPath,
        appColor = appColor?.let { Color(it.red, it.green, it.blue) },
        isPinned = clip.pinnedTime != 0L,
        link = linkPreview?.link,
        linkImgUrl = linkPreview?.imageUrl,
        linkTitle = linkPreview?.title
    )
}

fun List<ClipDetail>.toUi() = this.map { it.toUi() }