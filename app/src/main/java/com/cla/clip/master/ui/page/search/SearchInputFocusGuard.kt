package com.cla.clip.master.ui.page.search

/** 搜索框焦点意图保护；菜单焦点恢复不切历史，但真实触摸、键盘或语义输入可以解除保护。 */
internal class SearchInputFocusGuard {
    /** 当前是否等待真实输入意图，实例只在页面组合内使用，不影响查询或保存态。 */
    private var userIntentRequired = false

    /** 菜单打开后开始屏蔽平台的被动焦点恢复。 */
    fun onMenuOpened() { userIntentRequired = true }

    /** 用户主动触摸、键盘导航、语义点击或编辑时解除屏蔽，允许进入历史。 */
    fun onUserInputIntent() { userIntentRequired = false }

    /** 只有聚焦且无需等待用户意图时才进入历史，失焦从不切模式。 */
    fun shouldShowHistory(isFocused: Boolean): Boolean = isFocused && !userIntentRequired
}
