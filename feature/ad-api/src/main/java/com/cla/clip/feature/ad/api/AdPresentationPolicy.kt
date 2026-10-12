package com.cla.clip.feature.ad.api

/** 跨来源的内存呈现规则；内容消失立即隐藏，滚动安全门仅约束首次/重新挂载。 */
object AdPresentationPolicy {
    /** 已呈现且仍有内容时保留布局；隐藏后的缓存 View 必须重新经过安全门，素材未到零占位。 */
    fun canMount(
        /** SDK 已有可呈现的素材 View，不能以 Loaded 事件替代实际 View。 */
        hasView: Boolean,
        /** 快照和实时内容门共同决定当前是否有可展示的业务内容。 */
        contentAllowed: Boolean,
        /** 当前 View 已经呈现；自动隐藏必须清除它，不代表用户关闭。 */
        wasPresented: Boolean,
        /** 滚动、按压和覆盖层的安全许可，只控制新布局揭示。 */
        canReveal: Boolean,
    ): Boolean = hasView && contentAllowed && (wasPresented || canReveal)
}
