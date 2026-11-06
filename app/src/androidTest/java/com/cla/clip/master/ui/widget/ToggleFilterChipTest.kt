package com.cla.clip.master.ui.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 共享胶囊的默认启用兼容性与禁用语义，不读取安装应用列表。 */
@RunWith(AndroidJUnit4::class)
class ToggleFilterChipTest {
    /** 独立空宿主用于控件交互。 */
    @get:Rule val compose = createComposeRule()

    /** 系统应用等既有调用方不传 enabled，仍发送相反的选中目标值。 */
    @Test fun defaultEnabledPreservesSystemAppsInteraction() {
        // 模拟系统应用筛选，只保留内存布尔状态。
        val selected = mutableStateOf(false)
        compose.setContent {
            MaterialTheme { ToggleFilterChip("系统应用", "显示系统应用", selected.value, { selected.value = it }) }
        }
        compose.onNodeWithText("系统应用").assertIsEnabled().assertIsNotSelected()
            .performClick().assertIsSelected().performClick().assertIsNotSelected()
    }

    /** 禁用不回调，但读屏仍能知道当前已选中。 */
    @Test fun disabledChipPreservesSelectionWithoutCallback() {
        // 回调计数用于发现只变色却没有真正禁用的实现。
        var callbacks = 0
        compose.setContent {
            MaterialTheme { ToggleFilterChip("过滤失败图片", "自动取消失败选择", true, { callbacks++ }, enabled = false) }
        }
        compose.onNodeWithText("过滤失败图片").assertIsNotEnabled().assertIsSelected().performClick()
        compose.runOnIdle { assertEquals(0, callbacks) }
    }
}
