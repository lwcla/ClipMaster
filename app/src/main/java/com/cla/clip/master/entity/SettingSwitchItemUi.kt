package com.cla.clip.master.entity

/** 通用设置子项数据模型 */
data class SettingSwitchItemUi(
    val id: Id,
    val title: String,
    val description: String,
    val checked: Boolean,
    val enabled: Boolean = true,
) {
    sealed class Id {
        sealed class Permission : Id() {
            /** shizuku */
            object Shizuku : Permission()

            /** 通知 */
            object Notice : Permission()

            /** 悬浮窗 */
            object Overlay : Permission()
        }
    }
}