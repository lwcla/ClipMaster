package com.cla.clip.base.general.dao.data

/**
 * 最近一条剪贴板记录的轻量投影。
 *
 * 仅用于去重/读取判断，不加载关联表，避免后台剪贴板轮询时产生不必要的 Room 关系查询成本。
 */
data class LastClipData(
    /** 最近记录的原始剪贴内容，用于判断系统剪贴板内容是否已经保存过。 */
    val content: String,

    /** 最近记录的来源应用包名，可能为空；与 content 一起参与去重判断。 */
    val sourceAppPackage: String?,

    /** 最近记录的来源应用名称，可能为空；名称为 Unknown/未知 时按不可信来源处理。 */
    val sourceAppName: String?
)
