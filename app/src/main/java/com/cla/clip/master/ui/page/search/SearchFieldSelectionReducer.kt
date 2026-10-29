package com.cla.clip.master.ui.page.search

import com.cla.clip.base.general.search.ClipSearchField

/** 字段选择纯归约器，只处理集合，不包含 UI、查询或持久化副作用。 */
internal object SearchFieldSelectionReducer {
    /** 切换单项且保留最后一个字段；集合复制更新，不修改调用方原对象。 */
    fun toggle(
        /** 当前实际勾选集合。 */ selected: Set<ClipSearchField>,
        /** 本次点击的稳定字段身份。 */ field: ClipSearchField,
    ): Set<ClipSearchField> = when {
        !canToggle(selected, field) -> selected
        field !in selected -> selected + field
        else -> selected - field
    }

    /** UI 禁用态与事件归约共用最后一项保护，避免画面可操作但业务忽略的状态分歧。 */
    fun canToggle(
        /** 当前字段集合。 */ selected: Set<ClipSearchField>,
        /** 需要判断是否允许切换的字段。 */ field: ClipSearchField,
    ): Boolean = field !in selected || selected.size > 1
}
