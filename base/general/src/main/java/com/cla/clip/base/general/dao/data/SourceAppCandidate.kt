package com.cla.clip.base.general.dao.data

import androidx.room.ColumnInfo

/** 来源选择候选仅携带身份和名称，图标写入后的相同投影可被等值去重。 */
data class SourceAppCandidate(
    /** 空字符串保留未知来源的合法选择身份。 */
    @ColumnInfo(name = "package_name") val packageName: String,
    /** 未规整的名称，UI 复用现有显示名工具处理空白。 */
    @ColumnInfo(name = "app_name") val appName: String,
)
