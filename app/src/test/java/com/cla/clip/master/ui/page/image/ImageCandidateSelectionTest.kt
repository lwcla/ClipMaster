package com.cla.clip.master.ui.page.image

import org.junit.Assert.*
import org.junit.Test

/** 候选的显示位置、URL 升级和页面重建不能替用户撤销筛选。 */
class ImageCandidateSelectionTest {
    /** 空集合不能显示取消全选；部分选择切换为全选，全部选择才能取消全部。 */
    @Test fun selectAllStateFollowsCurrentCandidates() {
        // 每项断言覆盖用户看到的选择按钮语义。
        val selection = ImageCandidateSelection()
        assertFalse(selection.isAllSelected)
        selection.update(listOf("a", "b"))
        assertTrue(selection.isAllSelected)
        selection.setSelected("b", false)
        assertFalse(selection.isAllSelected)
        selection.selectAll(!selection.isAllSelected)
        assertEquals(setOf("a", "b"), selection.selectedKeys.toSet())
        selection.selectAll(!selection.isAllSelected)
        assertTrue(selection.selectedKeys.isEmpty())
        selection.update(emptyList())
        assertFalse(selection.isAllSelected)
    }

    /** 批量取消只影响仍选中的有效候选，重扫和新增候选不会恢复旧取消。 */
    @Test fun deselectFailedIntersectionPersistsUntilExplicitSelectAll() {
        // a 已由用户取消，b 为当前失败，missing 不属于当前图片。
        val selection = ImageCandidateSelection()
        selection.update(listOf("a", "b", "c"))
        selection.setSelected("a", false)
        assertEquals(1, selection.deselect(setOf("a", "b", "missing")))
        assertEquals(setOf("c"), selection.selectedKeys.toSet())
        assertEquals(0, selection.deselect(setOf("a", "b")))
        selection.update(listOf("a", "b", "c", "d"))
        assertEquals(setOf("c", "d"), selection.selectedKeys.toSet())
        selection.selectAll(true)
        assertTrue(selection.isAllSelected)
        assertEquals(4, selection.selectedKeys.size)
    }

    /** 重扫相同 key 保留取消，新发现候选默认选中。 */
    @Test fun refreshKeepsDeselectionAndSelectsOnlyNewCandidates() {
        // 独立于 Compose 页面保存的选择容器。
        val selection = ImageCandidateSelection()
        selection.update(listOf("first", "second"))
        selection.setSelected("first", false)
        selection.update(listOf("first", "second", "third"))
        assertEquals(setOf("second", "third"), selection.selectedKeys.toSet())
        selection.update(listOf("first", "second", "third"))
        assertEquals(setOf("second", "third"), selection.selectedKeys.toSet())
    }

    /** 只有明确重新提取才清除用户选择记忆。 */
    @Test fun explicitResetAllowsNewSessionToSelectAgain() {
        // 同一资源在用户重新提取后应恢复默认选择。
        val selection = ImageCandidateSelection()
        selection.update(listOf("first"))
        selection.setSelected("first", false)
        selection.reset()
        selection.update(listOf("first"))
        assertEquals(setOf("first"), selection.selectedKeys.toSet())
    }
}
