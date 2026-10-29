package com.cla.clip.base.general.search

/** 转义 LIKE 通配符和转义符本身；只供绑定参数使用，DAO 必须配套 ESCAPE 反斜杠。 */
internal fun escapeSearchLikeLiteral(
    /** 原始关键词，规范化由调用方决定。 */ text: String,
): String = buildString(text.length) {
    // 不变更大小写或空白，避免改变历史规范化与正文原文各自的查询契约。
    text.forEach { character ->
        if (character == '\\' || character == '%' || character == '_') append('\\')
        append(character)
    }
}
