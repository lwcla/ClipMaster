package com.cla.clip.master.ui.widget.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 保护实时图片集合中的浏览身份，防止新增、重排或删除导致跳图。 */
class ImagePreviewPagerStateTest {
    /** 打开时定位点击身份，不把列表第一张当作默认预览。 */
    @Test fun opensClickedImage() {
        assertEquals(ImagePreviewPosition("c", 2), resolveImagePreviewPosition(listOf("a", "b", "c"), "c", null))
    }

    /** 追加与重排均保持当前图片，显示序号随实际位置变化。 */
    @Test fun appendAndReorderKeepCurrentIdentity() {
        assertEquals(ImagePreviewPosition("b", 1), resolveImagePreviewPosition(listOf("a", "b", "c"), "b", 1))
        assertEquals(ImagePreviewPosition("b", 2), resolveImagePreviewPosition(listOf("c", "a", "b"), "b", 1))
    }

    /** 当前项被移除时选择相同位置，删除末项则回退到新的末项。 */
    @Test fun removalFallsBackToNearestPosition() {
        assertEquals(ImagePreviewPosition("c", 1), resolveImagePreviewPosition(listOf("a", "c"), "b", 1))
        assertEquals(ImagePreviewPosition("a", 0), resolveImagePreviewPosition(listOf("a"), "b", 1))
    }

    /** 打开目标丢失和最终空列表必须关闭，避免误开另一张或产生负页码。 */
    @Test fun missingInitialTargetAndEmptyListClosePreview() {
        assertNull(resolveImagePreviewPosition(listOf("a"), "missing", null))
        assertNull(resolveImagePreviewPosition(emptyList(), "a", 0))
    }
}
