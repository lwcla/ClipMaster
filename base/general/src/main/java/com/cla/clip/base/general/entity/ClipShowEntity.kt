package com.cla.clip.base.general.entity

import com.cla.clip.base.general.dao.data.ClipDetail
import com.cla.clip.base.general.utils.toRelativeTimeSpanString

/**
 * 剪贴记录在 UI 层使用的展示实体。
 *
 * 基础 mapper 合并内容和链接预览，app 装配阶段仅补历史来源名称；图标和主色由可见 UI 按包名加载，实体不持图片。时间字段同时保留剪贴时间、折叠时间和删除时间，
 * 由不同页面选择对应展示模式，避免共享列表组件理解 Room 关系结构。
 */
data class ClipShowEntity(
    /** 剪贴记录主键，用于详情导航、Paging key 和侧滑状态保存。 */
    val id: Long,
    /** 剪贴原始内容，列表、搜索高亮和复制动作都会读取。 */
    val content: String,
    /** 剪贴记录最近一次写入或刷新时间，普通列表和普通搜索以它作为主时间轴。 */
    val timestamp: Long,
    /** 本次折叠发生时间，0 表示当前未折叠；折叠列表和折叠搜索以它作为主时间轴。 */
    val foldedAt: Long,
    /** 进入回收站时间，0 表示未删除；回收站列表以它作为主时间轴。 */
    val deletedAt: Long,
    /** 剪贴时间的预格式化文案，用于首次组合时减少 UI 层重复计算。 */
    val formattedTime: String,
    /** 来源 App 包名，可能为空；详情页按它执行后续剪贴过滤，不使用展示名做身份。 */
    val sourceAppPackage: String?,
    /** 是否置顶；普通范围和折叠范围都可维护该状态，具体排序优先级由各范围查询决定。 */
    val isPinned: Boolean,
    /** 是否折叠；普通范围和折叠范围用它决定当前记录所在管理空间。 */
    val isFolded: Boolean,
    /** 链接预览图地址，可能为空；列表只在存在预览信息时展示。 */
    val linkImgUrl: String?,
    /** 剪贴内容中提取的链接或预览链接，可能为空。 */
    val link: String?,
    /** 链接预览标题，可能为空；搜索页会对可见标题做关键词高亮。 */
    val linkTitle: String?,
    /** 本次加载时取得的来源名称，缺失由共享显示名规则兜底。 */
    val sourceAppName: String? = null,
)

/** 将数据库关系实体转换为 UI 展示实体，集中处理内容、链接预览和时间字段，排除来源展示快照。 */
fun ClipDetail.toUi(): ClipShowEntity {
    // 普通剪贴时间是默认展示时间；折叠/回收站页面会在共享组件中按 foldedAt/deletedAt 重新格式化。
    val timeStr = clip.timestamp.toRelativeTimeSpanString()
    return toUiWithFormattedTime(timeStr)
}

/**
 * 将数据库关系实体转换为 UI 展示实体，并允许调用方传入已格式化时间。
 *
 * @param formattedTime 剪贴时间的预格式化文案；单元测试用它避开 Android DateUtils 的 JVM stub。
 */
internal fun ClipDetail.toUiWithFormattedTime(formattedTime: String): ClipShowEntity {
    // 链接预览仍是内容的一部分，来源信息只保留稳定包名供独立订阅。
    val linkPreview = this.linkPreview
    /** 内容行提供状态/时间/来源身份，排除来源展示快照。 */
    val clip = this.clip

    return ClipShowEntity(
        id = clip.id,
        content = clip.content,
        timestamp = clip.timestamp,
        foldedAt = clip.foldedAt,
        deletedAt = clip.deletedAt,
        formattedTime = formattedTime,
        sourceAppPackage = clip.sourceAppPackage?.takeIf { it.isNotBlank() },
        isPinned = clip.pinnedTime != 0L,
        isFolded = clip.isFolded,
        link = linkPreview?.link,
        linkImgUrl = linkPreview?.imageUrl,
        linkTitle = linkPreview?.title
    )
}

/** 批量转换非分页详情查询，保持后台/单条调用的内容契约。 */
fun List<ClipDetail>.toUi() = this.map { it.toUi() }
