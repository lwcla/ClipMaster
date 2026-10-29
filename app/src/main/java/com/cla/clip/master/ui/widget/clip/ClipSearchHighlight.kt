package com.cla.clip.master.ui.widget.clip

import com.cla.clip.base.general.search.ClipSearchField

/** 卡片内可高亮字段，属于展示契约而非数据库列名。 */
internal enum class ClipHighlightField {
    /** 原始剪贴文字。 */ Content,
    /** 独立展示的提取链接。 */ Link,
    /** 真实网页标题。 */ Title,
    /** 真实来源名称。 */ SourceName,
}

/** 查询与字段成对传递，避免共享卡片依赖页面状态或一组零散 Boolean。 */
data class ClipSearchHighlight(
    /** 本次结果对应的原始关键词。 */ val query: String,
    /** 本次结果限定的匹配字段，普通列表不创建该配置。 */ val fields: Set<ClipSearchField> = ClipSearchField.entries.toSet(),
) {
    /** 正文完全等于链接时也承载已去重链接的高亮，其他正文不得借用 Link 字段扩大标记。 */
    internal fun queryForContent(
        /** 正文实际展示值。 */ content: String,
        /** 当前提取链接，可能由正文承载而不再单独展示。 */ link: String?,
    ): String? = queryFor(ClipHighlightField.Content)
        ?: queryFor(ClipHighlightField.Link).takeIf { !link.isNullOrBlank() && content == link }

    /** 非目标字段返回 null，不允许偶然同词被标红；空词由共享文字构建器统一忽略。 */
    internal fun queryFor(/** 当前正在展示的文本字段。 */ field: ClipHighlightField): String? {
        /** 每个可见区域只对应一个真实字段；隐藏元数据不冒充可见字段命中。 */
        val searchField = when (field) {
            ClipHighlightField.Content -> ClipSearchField.Content
            ClipHighlightField.Link -> ClipSearchField.Link
            ClipHighlightField.Title -> ClipSearchField.LinkTitle
            ClipHighlightField.SourceName -> ClipSearchField.SourceName
        }
        return query.takeIf { searchField in fields }
    }
}
