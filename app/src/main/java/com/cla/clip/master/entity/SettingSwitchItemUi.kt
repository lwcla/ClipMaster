package com.cla.clip.master.entity

/**
 * 通用设置开关子项 UI 模型。
 *
 * 用于“我的/权限”页面展示某个权限或设置项的标题、描述、选中状态和可用状态，不直接持久化。
 */
data class SettingSwitchItemUi(
    /** 设置项稳定 id，用于区分点击后应进入哪一种权限或设置流程。 */
    val id: Id,

    /** 用户可见标题，调用方应从字符串资源读取后传入。 */
    val title: String,

    /** 用户可见说明文案，描述该开关或权限当前的作用。 */
    val description: String,

    /** 当前是否已开启或已授权；只反映 UI 状态，不负责执行授权动作。 */
    val checked: Boolean,

    /** 当前条目是否可点击，false 时通常表示前置条件未满足或系统能力不可用。 */
    val enabled: Boolean = true,
) {
    /** 设置项 id 层级，后续新增设置分类时应扩展这里而不是复用字符串常量。 */
    sealed class Id {
        /** 权限类设置项 id，点击后通常跳转系统设置或第三方授权流程。 */
        sealed class Permission : Id() {
            /** Shizuku 授权项，用于读取其他应用剪贴板来源信息。 */
            object Shizuku : Permission()

            /** 通知权限项，用于展示剪贴板读取和下载状态通知。 */
            object Notice : Permission()

            /** 悬浮窗权限项，用于后台读取剪贴板时保持服务可见性。 */
            object Overlay : Permission()
        }
    }
}
