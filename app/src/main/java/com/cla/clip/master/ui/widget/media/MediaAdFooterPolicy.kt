package com.cla.clip.master.ui.widget.media

/** 固定广告只使用图片最小可操作视口之外的空间，不通过裁切素材来满足高度限制。 */
internal object MediaAdFooterPolicy {
    /** 输入均为非负像素；零高表示尚未加载，空间不足时返回 false 并优先保留图片。 */
    fun fits(
        // 工具栏和标题之外、图片与广告共同可用的高度。
        availableHeight: Int,
        // 实际测得的完整广告高度，包含宿主留白。
        footerHeight: Int,
        // 图片视口保留的最小可操作高度。
        minimumGridHeight: Int,
    ): Boolean = availableHeight >= minimumGridHeight &&
        footerHeight <= availableHeight - minimumGridHeight
}
