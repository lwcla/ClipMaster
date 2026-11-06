package com.cla.clip.master.ui.page.image

import org.junit.Assert.*
import org.junit.Test

/** 持续过滤回归：失败事实晚于点击到达，批量选择与单图例外遵守同一规则。 */
class ImageCandidateContinuousFilterTest {
    /** 默认关闭不改变失败图片的初始选择；开启立即应用已知失败。 */
    @Test fun defaultOffAndEnableApplyKnownFailures() {
        // 两张合成候选中只有 b 已失败。
        val selection = candidates()
        selection.updateFailures(setOf("b"))
        assertFalse(selection.filterFailedEnabled)
        assertEquals(setOf("a", "b"), selection.selectedKeys.toSet())
        selection.setFilterFailed(true)
        assertEquals(setOf("a"), selection.selectedKeys.toSet())
        assertTrue(selection.isAllSelected)
    }

    /** 没有失败时即可提前打开，未来滚动才确认的失败也自动取消。 */
    @Test fun laterFailuresAndNewCandidatesAreFilteredWithoutAnotherClick() {
        // 初始两张尚未加载，之后新增 c 并确认 b/c 失败。
        val selection = candidates()
        selection.setFilterFailed(true)
        selection.update(listOf("a", "b", "c"))
        assertEquals(setOf("a", "b", "c"), selection.selectedKeys.toSet())
        selection.updateFailures(setOf("b", "c"))
        assertEquals(setOf("a"), selection.selectedKeys.toSet())
        selection.update(listOf("c", "b", "a"))
        assertEquals(setOf("a"), selection.selectedKeys.toSet())
    }

    /** 全选严格归一化到非失败集合，手动失败例外使按钮回到全选。 */
    @Test fun selectAllClearsManualExceptionAndKeepsFilterEnabled() {
        // b 失败后由用户主动选回，验证批量规则优先级。
        val selection = candidates()
        selection.updateFailures(setOf("b"))
        selection.setFilterFailed(true)
        selection.setSelected("b", true)
        assertFalse(selection.isAllSelected)
        assertEquals(1, selection.manualOverrideCount)
        selection.selectAll(true)
        assertEquals(setOf("a"), selection.selectedKeys.toSet())
        assertTrue(selection.filterFailedEnabled)
        assertTrue(selection.isAllSelected)
        assertEquals(0, selection.manualOverrideCount)
    }

    /** 同一稳定身份重复失败、请求重试及重扫不会撤销手动保留。 */
    @Test fun manualExceptionSurvivesRepeatedFailureAndRequestRecovery() {
        // 失败清除模拟请求版本升级或重试，随后再次失败。
        val selection = candidates()
        selection.setFilterFailed(true)
        selection.updateFailures(setOf("b"))
        selection.setSelected("b", true)
        selection.updateFailures(setOf("b"))
        selection.updateFailures(emptySet())
        selection.update(listOf("b", "a"))
        selection.updateFailures(setOf("b"))
        assertEquals(setOf("a", "b"), selection.selectedKeys.toSet())
        selection.setSelected("b", false)
        assertEquals(0, selection.manualOverrideCount)
        selection.updateFailures(setOf("b"))
        assertEquals(setOf("a"), selection.selectedKeys.toSet())
    }

    /** 关闭不恢复旧选择；关闭期间全选包含失败，重新开启清除历史例外。 */
    @Test fun disableDoesNotRestoreButUnfilteredSelectAllIncludesFailures() {
        // 关闭与重新开启都由明确用户动作触发。
        val selection = candidates()
        selection.setFilterFailed(true)
        selection.updateFailures(setOf("b"))
        selection.setFilterFailed(false)
        assertEquals(setOf("a"), selection.selectedKeys.toSet())
        selection.updateFailures(setOf("a", "b"))
        assertEquals(setOf("a"), selection.selectedKeys.toSet())
        selection.selectAll(true)
        assertEquals(setOf("a", "b"), selection.selectedKeys.toSet())
        selection.setFilterFailed(true)
        assertTrue(selection.selectedKeys.isEmpty())
    }

    /** 全失败时目标为空，手动例外存在时仍允许全选以清除例外。 */
    @Test fun emptyTargetDisablesActionUnlessManualSelectionNeedsClearing() {
        // 空页面、全失败、手动例外分别验证按钮可用性。
        val selection = ImageCandidateSelection()
        assertFalse(selection.canSelectAll)
        selection.update(listOf("a"))
        selection.setFilterFailed(true)
        selection.updateFailures(setOf("a"))
        assertFalse(selection.isAllSelected)
        assertFalse(selection.canSelectAll)
        selection.setSelected("a", true)
        assertTrue(selection.canSelectAll)
        assertFalse(selection.isAllSelected)
        selection.selectAll(true)
        assertTrue(selection.selectedKeys.isEmpty())
        assertFalse(selection.canSelectAll)
    }

    /** 取消全选保持开关，之后新增候选仍默认选中并接受后续过滤。 */
    @Test fun cancelAllClearsExceptionsButNewCandidatesStillDefaultSelected() {
        // 取消全部只针对当时存在的候选。
        val selection = candidates()
        selection.setFilterFailed(true)
        selection.updateFailures(setOf("b"))
        selection.setSelected("b", true)
        selection.selectAll(false)
        assertTrue(selection.filterFailedEnabled)
        assertEquals(0, selection.manualOverrideCount)
        selection.update(listOf("a", "b", "c"))
        assertEquals(setOf("c"), selection.selectedKeys.toSet())
        selection.updateFailures(setOf("b", "c"))
        assertTrue(selection.selectedKeys.isEmpty())
    }

    /** 成功或版本升级清除失败证据，不自动恢复此前过滤的选择。 */
    @Test fun successDoesNotReselectAndUnknownManualSelectionIsNotException() {
        // a 在尚未失败时被主动选择，仍须受将来的失败过滤。
        val selection = candidates()
        selection.setFilterFailed(true)
        selection.updateFailures(setOf("b"))
        selection.updateFailures(emptySet())
        selection.update(listOf("a", "b"))
        assertEquals(setOf("a"), selection.selectedKeys.toSet())
        selection.setSelected("a", true)
        selection.updateFailures(setOf("a"))
        assertTrue(selection.selectedKeys.isEmpty())
    }

    /** 提交期间只累积有效失败事实，提交失败解冻后应用且保留手动例外。 */
    @Test fun submissionFreezesSelectionAndThawAppliesPendingFailure() {
        // b 为已有手动例外，a 在提交途中失败。
        val selection = candidates()
        selection.setFilterFailed(true)
        selection.updateFailures(setOf("b"))
        selection.setSelected("b", true)
        selection.setFrozen(true)
        selection.updateFailures(setOf("a", "b"))
        selection.selectAll(false)
        selection.setSelected("b", false)
        selection.setFilterFailed(false)
        assertEquals(setOf("a", "b"), selection.selectedKeys.toSet())
        assertTrue(selection.filterFailedEnabled)
        selection.setFrozen(false)
        assertEquals(setOf("b"), selection.selectedKeys.toSet())
    }

    /** 普通更新保留开关，显式重提取重置开关、失败和例外。 */
    @Test fun resetStartsDefaultOffSessionAndDropsStaleFailures() {
        // 重置后可复用相同 key，但不能继承旧失败事实。
        val selection = candidates()
        selection.setFilterFailed(true)
        selection.updateFailures(setOf("b", "missing"))
        selection.setSelected("b", true)
        selection.update(listOf("a", "b"))
        assertTrue(selection.filterFailedEnabled)
        selection.reset()
        selection.update(listOf("a", "b"))
        assertFalse(selection.filterFailedEnabled)
        assertEquals(0, selection.manualOverrideCount)
        assertEquals(setOf("a", "b"), selection.selectedKeys.toSet())
    }

    /** 每个用例独立创建短小候选集合，不依赖 Android 或请求地址。 */
    private fun candidates() = ImageCandidateSelection().apply { update(listOf("a", "b")) }
}
