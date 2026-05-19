package com.cla.clip.base.general.entity

import androidx.compose.ui.graphics.Color
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import com.cla.clip.base.general.dao.data.ClipDetail
import com.cla.clip.base.general.utils.toRelativeTimeSpanString

/**
 * 剪贴记录在 UI 层使用的展示实体。
 *
 * 这里把数据库记录、来源应用和链接预览合并成页面所需的扁平结构；时间字段同时保留剪贴时间、折叠时间和删除时间，
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
    /** 来源 App 展示名，可能为空；为空时 UI 会回退到未知来源文案。 */
    val appName: String?,
    /** 来源 App 图标缓存路径，可能为空；为空时 UI 使用兜底图标。 */
    val appIconPath: String?,
    /** 来源 App 主色，可能为空；为空时 UI 使用主题轮廓色。 */
    val appColor: Color?,
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
)

/** 将数据库关系实体转换为 UI 展示实体，集中处理来源 App、链接预览和时间字段的空值边界。 */
fun ClipDetail.toUi(): ClipShowEntity {
    // 来源应用和链接预览是可选关系，先取局部变量，避免后续字段拼装时重复解包。
    val app = this.sourceApp
    val linkPreview = this.linkPreview
    val clip = this.clip
    val appColor = app?.primaryColor?.takeIf { it != -1 }
    // 普通剪贴时间是默认展示时间；折叠/回收站页面会在共享组件中按 foldedAt/deletedAt 重新格式化。
    val timeStr = clip.timestamp.toRelativeTimeSpanString()

    return ClipShowEntity(
        id = clip.id,
        content = clip.content,
        timestamp = clip.timestamp,
        foldedAt = clip.foldedAt,
        deletedAt = clip.deletedAt,
        formattedTime = timeStr,
        // 来源 App 可能已经无法关联或名称为空，UI 层会用统一未知来源文案兜底。
        appName = app?.appName?.takeIf { it.isNotBlank() },
        appIconPath = app?.iconPath,
        appColor = appColor?.let { Color(it.red, it.green, it.blue) },
        isPinned = clip.pinnedTime != 0L,
        isFolded = clip.isFolded,
        link = linkPreview?.link,
        linkImgUrl = linkPreview?.imageUrl,
        linkTitle = linkPreview?.title
    )
}

/** 批量转换分页查询结果，保持列表页和搜索页拿到一致的 UI 实体结构。 */
fun List<ClipDetail>.toUi() = this.map { it.toUi() }
