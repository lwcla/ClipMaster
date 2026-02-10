package com.cla.clip.base.general.entity

/**
 * 剪贴板捕获实体类，包含了剪贴板内容、时间戳以及来源应用的相关信息。
 * 这个类用于在捕获剪贴板数据时，记录下相关的上下文信息，以便后续处理和展示。
 * @param content 剪贴板的文本内容。
 * @param timestamp 捕获的时间戳，单位为毫秒。
 * @param sourcePackage 来源应用的包名。
 * @param sourceAppName 来源应用的名称。
 * @param sourceAppIconPath 来源应用图标的路径（可选）。
 * @param sourcePrimaryColor 来源应用的主色调（可选）。
 */
data class ClipCaptureEntity(
    val content: String,
    val timestamp: Long,
    // 关于来源 App 的原始信息，而不是 SourceApp 实体
    val sourcePackage: String,
    val sourceAppName: String,
    val sourceAppIconPath: String?,
    val sourcePrimaryColor: Int?,
    val linkTitle: String?,
    val linkDescription: String?,
    val linkImageUrl: String?,
    val linkSiteName: String?,
)