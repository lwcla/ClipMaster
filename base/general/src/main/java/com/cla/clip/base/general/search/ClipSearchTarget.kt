package com.cla.clip.base.general.search

/** 编译期搜索字段契约；不落库、不序列化，普通和折叠数据范围仍独立表达。 */
enum class ClipSearchTarget {
    /** 兼容现有综合索引与模糊搜索。 */
    All,
    /** 仅原始剪贴正文。 */
    Content,
    /** 仅关联网页标题，不使用展示兜底。 */
    LinkTitle,
    /** 仅已记录的真实来源名称，不包含包名。 */
    SourceName,
}
