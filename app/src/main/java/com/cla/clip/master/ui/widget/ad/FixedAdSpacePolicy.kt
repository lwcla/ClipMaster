package com.cla.clip.master.ui.widget.ad

import com.cla.clip.master.ui.widget.media.MediaAdFooterPolicy

/** 固定列表广告空间不足后锁存本访问终态，初始小窗口则保留首次请求机会。 */
internal object FixedAdSpacePolicy {
    /** 自然高度挤占最小内容视口时，只有已申请或已有实测素材的槽需要终止。 */
    fun mustStopSlot(
        // 标题和系统安全区域之外的当前可用像素高度。
        availableHeight: Int,
        // 实际广告与外围间距占高；未展示或自动隐藏时可为零。
        adHeight: Int,
        // 列表必须保留的最小可操作像素高度。
        minimumContentHeight: Int,
        // 已取得当前广告位资源租约，不等同于已经曝光。
        hasResource: Boolean,
    ): Boolean = !MediaAdFooterPolicy.fits(availableHeight, adHeight, minimumContentHeight) &&
        (hasResource || adHeight > 0)
}
