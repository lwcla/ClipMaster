package com.cla.clip.master.ui.page.image

import org.junit.Assert.*
import org.junit.Test

/** 候选的显示位置、URL 升级和页面重建不能替用户撤销筛选。 */
class ImageCandidateSelectionTest {
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
