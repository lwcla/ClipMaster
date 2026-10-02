package com.cla.clip.base.general.dao.data

import androidx.room.Embedded
import androidx.room.Relation
import com.cla.clip.base.general.dao.ClipData
import com.cla.clip.base.general.dao.LinkPreviewData

/** 分页专用组合记录：保留内容和预览，排除来源表关系，使图标写入不失效结果分页。 */
data class ClipPageData(
    /** 剪贴内容、状态与来源包名；包名是独立来源订阅的稳定身份。 */
    @Embedded val clip: ClipData,
    /** 链接预览仍参与分页观察，标题或图片补全后需要正常刷新。 */
    @Relation(parentColumn = "link", entityColumn = "link") val linkPreview: LinkPreviewData?,
)
