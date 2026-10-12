package com.cla.clip.master.ui.widget.ad

import org.junit.Assert.*
import org.junit.Test

/** 保护实际业务画面与广告呈现同源，快照残留不能冒充当前可见内容。 */
class ListAdContentStateTest {
    /** 加载画面优先于残留快照，真实列表画面才允许广告展示。 */
    @Test fun loadingScreenDoesNotQualifyRetainedSnapshot() {
        assertFalse(ListAdContentState.from(hasRenderedItems = true, showsLoading = true).containsContent)
        assertFalse(ListAdContentState.from(hasRenderedItems = false, showsLoading = true).containsContent)
        assertTrue(ListAdContentState.from(hasRenderedItems = true, showsLoading = false).containsContent)
    }

    /** 清空后立刻取消资格，恢复真实内容可以复用广告，不依赖历史非空标记。 */
    @Test fun clearingAndRestoringRecordsUpdatesPresentationImmediately() {
        assertEquals(ListAdContentState.Empty, ListAdContentState.from(false, false))
        assertEquals(ListAdContentState.Rendered, ListAdContentState.from(true, false))
        assertEquals(ListAdContentState.Empty, ListAdContentState.from(false, false))
    }
}
