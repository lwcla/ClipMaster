package com.cla.clip.master.ui.widget.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 固定广告不得挤掉图片的最小操作区域，也不得裁切实际素材以勉强放入。 */
class MediaAdFooterPolicyTest {
    /** 广告刚好放下可以显示，多一像素则必须让位给图片。 */
    @Test
    fun footerMustLeaveMinimumGridViewport() {
        assertTrue(MediaAdFooterPolicy.fits(300, 204, 96))
        assertFalse(MediaAdFooterPolicy.fits(300, 205, 96))
    }

    /** 页面连图片最小视口都放不下时，即使广告尚为零高度也不应发起请求。 */
    @Test
    fun shortViewportDoesNotRequestFooter() {
        assertFalse(MediaAdFooterPolicy.fits(95, 0, 96))
        assertFalse(MediaAdFooterPolicy.fits(0, 0, 96))
    }

    /** 字体或素材高度增大之后使用实际高度重新判断，不能沿用预计的小卡片高度。 */
    @Test
    fun oversizedCreativeCannotConsumeTheGrid() {
        assertTrue(MediaAdFooterPolicy.fits(240, 100, 96))
        assertFalse(MediaAdFooterPolicy.fits(240, 200, 96))
        assertFalse(MediaAdFooterPolicy.fits(240, 500, 96))
    }
}
