package com.cla.clip.base.general.search

/** 搜索页可选的真实字段；仅编译期和页面会话使用，不作为数据库或序列化协议。 */
enum class ClipSearchField(/** UI 稳定身份及短暂保存键，不依赖枚举名或顺序；不写入数据库。 */ val id: String) {
    /** 原始剪贴正文。 */ Content("content"),
    /** 提取出的链接地址。 */ Link("link"),
    /** 预览真实标题。 */ LinkTitle("title"),
    /** 预览摘要。 */ LinkDescription("description"),
    /** 预览站点名。 */ SiteName("site"),
    /** 已记录来源名称。 */ SourceName("source_name"),
    /** 剪贴记录上的原始来源包名。 */ SourcePackage("source_package"),
}
