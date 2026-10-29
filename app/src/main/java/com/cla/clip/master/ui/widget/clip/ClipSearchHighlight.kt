package com.cla.clip.master.ui.widget.clip

import com.cla.clip.base.general.search.ClipSearchTarget

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
    /** 本次结果限定的匹配字段，普通列表不创建该配置。 */ val target: ClipSearchTarget = ClipSearchTarget.All,
) {
    /** 非目标字段返回 null，不允许偶然同词被标红；空词由共享文字构建器统一忽略。 */
    internal fun queryFor(/** 当前正在展示的文本字段。 */ field: ClipHighlightField): String? = when (target) {
        ClipSearchTarget.All -> query
        ClipSearchTarget.Content -> query.takeIf { field == ClipHighlightField.Content }
        ClipSearchTarget.LinkTitle -> query.takeIf { field == ClipHighlightField.Title }
        ClipSearchTarget.SourceName -> query.takeIf { field == ClipHighlightField.SourceName }
    }
}
