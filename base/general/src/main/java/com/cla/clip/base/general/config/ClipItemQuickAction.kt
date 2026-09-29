package com.cla.clip.base.general.config

/**
 * 普通剪贴列表 item 快捷动作区点击动作。
 *
 * 该枚举属于跨页面设置契约：我的页负责选择和保存，普通列表/普通搜索负责读取并映射到具体回调。
 * 后续新增动作时必须同步扩展字符串资源、设置弹窗、共享 item 动作映射和方案文档。
 */
enum class ClipItemQuickAction(
    /** DataStore 中的稳定值，不依赖 Kotlin 枚举名称。 */
    val storageValue: String,
) {
    /** 快捷动作区点击复制剪贴内容，是默认行为。 */
    Copy("copy"),
    /** 快捷动作区点击切换置顶/取消置顶。 */
    Pin("pin"),
    /** 快捷动作区点击打开删除选择弹窗。 */
    Delete("delete"),
    /** 快捷动作区点击折叠数据。 */
    Fold("fold"),
    /** 关闭快捷动作区，整张 item 点击进入详情。 */
    None("none");

    companion object {
        /** 未配置或未知值时采用复制动作。 */
        val Default = Copy

        /** 将稳定持久化值映射为业务枚举；未知值回退默认动作。 */
        fun fromStorageValue(value: String?): ClipItemQuickAction =
            entries.firstOrNull { it.storageValue == value } ?: Default
    }
}
