package com.cla.clip.master.ad

/** 列表布局保护只处理业务交互，不再承载已删除的每二十条混排位置规则。 */
internal object AdListLayoutPolicy {
    /** 列表惯性、卡片侧滑和置顶纠偏全部结束后，广告才允许改变布局。 */
    fun canMutateLayout(
        // 当前业务列表正在拖动或惯性滚动。
        scrolling: Boolean,
        // 卡片侧滑、页面按压或其他动作尚未结束。
        interactionBlocked: Boolean,
        // 取消置顶产生的业务滚动恢复尚未完成。
        pinRestorePending: Boolean,
    ): Boolean = !scrolling && !interactionBlocked && !pinRestorePending
}
