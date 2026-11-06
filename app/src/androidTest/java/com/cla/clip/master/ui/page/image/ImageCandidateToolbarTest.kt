package com.cla.clip.master.ui.page.image

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cla.clip.master.ui.page.image.component.ImageCandidateToolbar
import com.cla.clip.master.ui.page.image.component.ImageCandidateToolbarState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真实工具栏与选择规则联动，不创建提取任务、不访问网页或用户数据。 */
@RunWith(AndroidJUnit4::class)
class ImageCandidateToolbarTest {
    /** Compose 空宿主用于点击和可用性断言。 */
    @get:Rule val compose = createComposeRule()

    /** 持续过滤默认关闭，手动例外后的全选保留开关并清除例外。 */
    @Test fun filterAndSelectAllUpdateCountAndActionAvailability() {
        // 两张合成图片中 b 失败；使用生产选择对象而非模拟点击计数。
        val selection = ImageCandidateSelection().apply {
            update(listOf("a", "b")); updateFailures(setOf("b"))
        }
        compose.setContent {
            MaterialTheme {
                ImageCandidateToolbar(
                    state = ImageCandidateToolbarState(false, false, selection.selectedKeys.size, 2,
                        selection.isAllSelected, selection.canSelectAll, selection.filterFailedEnabled, false),
                    onRetry = {},
                    onToggleSelectAll = { selection.selectAll(!selection.isAllSelected) },
                    onFilterFailedChange = selection::setFilterFailed,
                    onStopExtract = {},
                    onConfirmDownload = {},
                )
            }
        }
        compose.onNodeWithText("取消全选").assertIsEnabled()
        compose.onNodeWithText("过滤失败图片").assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithText("取消全选").assertIsEnabled()
        compose.runOnIdle { selection.setSelected("b", true) }
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("过滤失败图片").assertIsEnabled().assertIsSelected()
        compose.runOnIdle { assertEquals(setOf("a"), selection.selectedKeys.toSet()) }
        compose.onNodeWithText("取消全选").performClick()
        compose.onNodeWithText("下载已选图片").assertIsNotEnabled()
        compose.onNodeWithText("全选").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, selection.selectedKeys.size) }
    }

    /** 无失败时可提前开启；提交期间禁用所有选择动作并保留开关选中语义。 */
    @Test fun emptyAndSubmittingStatesDisableSelectionActions() {
        // 切换同一组件状态以验证没有遗留旧按钮可用性。
        val state = mutableStateOf(ImageCandidateToolbarState(false, false, 0, 0, false, false, false, false))
        compose.setContent {
            MaterialTheme {
                ImageCandidateToolbar(state.value, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("全选").assertIsNotEnabled()
        compose.onNodeWithText("过滤失败图片").assertIsEnabled().assertIsNotSelected()
        compose.runOnIdle { state.value = ImageCandidateToolbarState(false, true, 1, 2, true, true, true, false) }
        compose.onNodeWithText("取消全选").assertIsNotEnabled()
        compose.onNodeWithText("过滤失败图片").assertIsNotEnabled().assertIsSelected()
        compose.onNodeWithText("重新提取").assertIsNotEnabled()
    }

    /** 小屏大字体下三项次要操作仍完整可见且不重叠。 */
    @Test fun narrowLargeFontWrapsActionsInsteadOfClipping() {
        compose.setContent {
            // 只扩大字体，不改变设备像素密度；容器模拟窄窗口。
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme {
                    Box(Modifier.width(280.dp)) {
                        ImageCandidateToolbar(ImageCandidateToolbarState(false, false, 2, 2, true, true, false, false),
                            {}, {}, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("重新提取").assertIsDisplayed()
        compose.onNodeWithText("取消全选").assertIsDisplayed()
        compose.onNodeWithText("过滤失败图片").assertIsDisplayed().assertIsEnabled()
        // 两个按钮纵向错开或横向不相交即可；不绑定具体字体测量值。
        val selectBounds = compose.onNodeWithText("取消全选").fetchSemanticsNode().boundsInRoot
        // 过滤按钮边界与前一动作比较，验证换行而非互相覆盖。
        val filterBounds = compose.onNodeWithText("过滤失败图片").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertFalse(selectBounds.overlaps(filterBounds))
        org.junit.Assert.assertTrue(filterBounds.top >= selectBounds.bottom)
        // 主下载按钮定义内容区右边缘，换行后仍与其对齐。
        val primaryBounds = compose.onNodeWithText("下载已选图片").fetchSemanticsNode().boundsInRoot
        assertEquals(primaryBounds.right, filterBounds.right, 1f)
    }

    /** 文案从取消全选变为全选时，右侧开关的位置与尺寸保持不变。 */
    @Test fun filterRemainsRightAlignedAcrossSelectionLabels() {
        // 只改变左侧按钮文案，开关无需重新定位。
        val state = mutableStateOf(ImageCandidateToolbarState(false, false, 2, 2, true, true, false, false))
        compose.setContent { MaterialTheme { ImageCandidateToolbar(state.value, {}, {}, {}, {}, {}) } }
        // 捕获真实可交互节点的布局边界。
        val before = compose.onNodeWithText("过滤失败图片").fetchSemanticsNode().boundsInRoot
        // 第一行主按钮与第二行开关共用内容右边距。
        val primary = compose.onNodeWithText("下载已选图片").fetchSemanticsNode().boundsInRoot
        assertEquals(primary.right, before.right, 1f)
        compose.runOnIdle { state.value = state.value.copy(allSelected = false) }
        compose.onNodeWithText("全选").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithText("过滤失败图片").fetchSemanticsNode().boundsInRoot)
    }
}
