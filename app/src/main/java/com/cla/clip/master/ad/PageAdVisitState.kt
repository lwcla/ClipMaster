package com.cla.clip.master.ad

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 页面访问内的临时关闭记录；跨业务会话共享，真正离开后的新访问使用新对象，不持久化。 */
internal class PageAdVisitState {
    /** 只保存 placement/slotIndex 低敏组合键，不关联查询、业务主键或 SDK 来源。 */
    private var dismissedKeys by mutableStateOf<Set<String>>(emptySet())

    /** 同一次访问内的任何来源或业务会话都必须尊重用户关闭结果。 */
    fun isDismissed(key: String): Boolean = key in dismissedKeys

    /** 先写入终态再释放资源，关闭过程中的 SDK 回调不能重新领取该位置。 */
    fun dismiss(key: String) { dismissedKeys = dismissedKeys + key }
}
