package com.cla.clip.master.ui.page.search

/** 顶部返回的唯一动作，仅用于页面编译期交互，不持久化或参与外部协议。 */
internal enum class SearchToolbarBackAction {
    /** 优先关闭筛选菜单，保留已应用条件。 */
    CloseMenu,
    /** 菜单关闭后退出结果多选，保留查询。 */
    ExitSelection,
    /** 无局部交互时交给宿主返回上一页。 */
    NavigateBack,
}

/** 只判断返回优先级，不操作焦点、键盘、导航或查询；输入的四种组合均有明确动作。 */
internal fun resolveSearchToolbarBackAction(
    /** 当前时间或来源菜单是否打开。 */
    menuOpen: Boolean,
    /** 当前范围是否真正处于可见结果多选态。 */
    selectionMode: Boolean,
): SearchToolbarBackAction = when {
    // 菜单是最上层交互，一次返回只关闭它，不能同时退出多选或离页。
    menuOpen -> SearchToolbarBackAction.CloseMenu
    selectionMode -> SearchToolbarBackAction.ExitSelection
    else -> SearchToolbarBackAction.NavigateBack
}
