package com.cla.clip.master.ui.page.mine

/** 页面局部顶部模式，仅参与配置恢复，不用于设置、序列化或外部协议。 */
internal enum class ClipSourceFilterSearchMode {
    /** 常规标题及搜索入口。 */
    Browsing,
    /** 顶部输入与就地筛选。 */
    Searching,
}

/** 返回事件的唯一动作，平台焦点与写入等待由页面执行，纯规则不产生副作用。 */
internal enum class ClipSourceFilterBackAction {
    /** IME 尚可见时仅收起键盘。 */
    DismissKeyboard,
    /** 搜索态退出并恢复完整当前范围。 */
    CloseSearch,
    /** 普通态进入已有保存等待及导航流程。 */
    LeavePage,
}

/** 按键盘、搜索、页面顺序决定返回边界；一次事件只处理一层，不直接执行导航或写入。 */
internal fun resolveClipSourceFilterBackAction(
    /** 当前顶部展示模式，不受候选数量影响。 */
    mode: ClipSourceFilterSearchMode,
    /** 当前 IME 可见性，由页面读取系统 Insets。 */
    imeVisible: Boolean,
): ClipSourceFilterBackAction = when {
    // IME 动画和平台返回消费时序不同，显式优先键盘避免同一次返回越过两层。
    imeVisible -> ClipSourceFilterBackAction.DismissKeyboard
    mode == ClipSourceFilterSearchMode.Searching -> ClipSourceFilterBackAction.CloseSearch
    else -> ClipSourceFilterBackAction.LeavePage
}
