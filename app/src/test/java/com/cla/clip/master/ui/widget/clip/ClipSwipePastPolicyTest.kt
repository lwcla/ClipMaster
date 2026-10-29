package com.cla.clip.master.ui.widget.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 使用合成像素验证右滑距离和松手资格，避免过低阈值误触或两套判定分叉。 */
class ClipSwipePastPolicyTest {
    /** 常规三按钮和无菜单均应在整卡 80% 达标，密度翻倍不改变比例。 */
    @Test fun usesEightyPercentWithOrWithoutMenus() {
        assertEquals(288f, calculateClipSwipePastThreshold(360f, 145f, 48f)!!, 0.001f)
        assertEquals(288f, calculateClipSwipePastThreshold(360f, 0f, 48f)!!, 0.001f)
        assertEquals(288f, calculateClipSwipePastThreshold(360f, 48f, 48f)!!, 0.001f)
        assertEquals(288f, calculateClipSwipePastThreshold(360f, 96.5f, 48f)!!, 0.001f)
        assertEquals(576f, calculateClipSwipePastThreshold(720f, 290f, 96f)!!, 0.001f)
    }

    /** 窄卡片仍在菜单后保留第二段，并使用剩余空间的一半避免必须拖到底。 */
    @Test fun narrowCardsKeepSpaceBeyondTheMenu() {
        assertEquals(192.5f, calculateClipSwipePastThreshold(240f, 145f, 48f)!!, 0.001f)
        assertEquals(162.5f, calculateClipSwipePastThreshold(180f, 145f, 48f)!!, 0.001f)
    }

    /** 未测量或非法几何不产生可执行阈值，尤其不能让无菜单回收站以零阈值提交。 */
    @Test fun invalidGeometryDisablesTheAction() {
        assertNull(calculateClipSwipePastThreshold(0f, 0f, 48f))
        assertNull(calculateClipSwipePastThreshold(-1f, 0f, 48f))
        assertNull(calculateClipSwipePastThreshold(100f, 100f, 48f))
        assertNull(calculateClipSwipePastThreshold(100f, 145f, 48f))
        assertNull(calculateClipSwipePastThreshold(360f, -1f, 48f))
        assertNull(calculateClipSwipePastThreshold(360f, 145f, 0f))
        assertNull(calculateClipSwipePastThreshold(360f, 145f, -1f))
        // 三个几何输入都不能接受 NaN 或无穷，否则提示及松手状态会失去边界。
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { invalid ->
            assertNull(calculateClipSwipePastThreshold(invalid, 145f, 48f))
            assertNull(calculateClipSwipePastThreshold(360f, invalid, 48f))
            assertNull(calculateClipSwipePastThreshold(360f, 145f, invalid))
        }
    }

    /** 提示和提交使用相同边界，回拉立即取消资格，不锁定手势曾经达到的最远位置。 */
    @Test fun readinessFollowsCurrentOffsetIncludingPullback() {
        assertEquals(ClipSwipePastState.Hidden, resolveClipSwipePastState(145f, 145f, 288f))
        assertEquals(ClipSwipePastState.Dragging, resolveClipSwipePastState(287.9f, 145f, 288f))
        assertEquals(ClipSwipePastState.Ready, resolveClipSwipePastState(288f, 145f, 288f))
        assertEquals(ClipSwipePastState.Ready, resolveClipSwipePastState(300f, 145f, 288f))
        assertEquals(ClipSwipePastState.Dragging, resolveClipSwipePastState(287.9f, 145f, 288f))
        assertEquals(ClipSwipePastState.Hidden, resolveClipSwipePastState(0f, 145f, 288f))
    }

    /** 回收站从零偏移开始第二段，无效输入即使偏移很大也不能显示松手提示。 */
    @Test fun noMenuAndInvalidStateInputsAreSafe() {
        assertEquals(ClipSwipePastState.Hidden, resolveClipSwipePastState(0f, 0f, 288f))
        assertEquals(ClipSwipePastState.Dragging, resolveClipSwipePastState(1f, 0f, 288f))
        assertEquals(ClipSwipePastState.Ready, resolveClipSwipePastState(288f, 0f, 288f))
        assertEquals(ClipSwipePastState.Hidden, resolveClipSwipePastState(300f, 145f, null))
        assertEquals(ClipSwipePastState.Hidden, resolveClipSwipePastState(300f, 145f, 145f))
        assertEquals(ClipSwipePastState.Hidden, resolveClipSwipePastState(300f, -1f, 288f))
        assertEquals(ClipSwipePastState.Hidden, resolveClipSwipePastState(-1f, 0f, 288f))
        // 当前偏移、菜单宽度和阈值均拒绝非有限输入。
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { invalid ->
            assertEquals(ClipSwipePastState.Hidden, resolveClipSwipePastState(invalid, 0f, 288f))
            assertEquals(ClipSwipePastState.Hidden, resolveClipSwipePastState(300f, invalid, 288f))
            assertEquals(ClipSwipePastState.Hidden, resolveClipSwipePastState(300f, 0f, invalid))
        }
    }
}
