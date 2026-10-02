package com.cla.clip.base.general.entity

import com.cla.clip.base.general.dao.data.ClipPageData
import com.cla.clip.base.general.utils.toRelativeTimeSpanString

/** 分页映射只处理内容，来源展示通过包名另行订阅，不依赖 source_apps。 */
fun ClipPageData.toUi(): ClipShowEntity = toUiWithFormattedTime(clip.timestamp.toRelativeTimeSpanString())

/** 独立时间参数便于 JVM 覆盖内容契约，避免调用 Android DateUtils 桩。 */
internal fun ClipPageData.toUiWithFormattedTime(formattedTime: String): ClipShowEntity = ClipShowEntity(
    id = clip.id, content = clip.content, timestamp = clip.timestamp,
    foldedAt = clip.foldedAt, deletedAt = clip.deletedAt, formattedTime = formattedTime,
    sourceAppPackage = clip.sourceAppPackage?.takeIf { it.isNotBlank() },
    isPinned = clip.pinnedTime != 0L, isFolded = clip.isFolded,
    link = linkPreview?.link, linkImgUrl = linkPreview?.imageUrl, linkTitle = linkPreview?.title,
)
