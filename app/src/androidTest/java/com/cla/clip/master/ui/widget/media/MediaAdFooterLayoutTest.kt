package com.cla.clip.master.ui.widget.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 直接测量生产广告底栏布局，合成素材不加载 SDK，也不请求真实广告。 */
@RunWith(AndroidJUnit4::class)
class MediaAdFooterLayoutTest {
    /** 独立 Compose 空宿主，只负责布局测量。 */
    @get:Rule val compose = createComposeRule()

    /** 加载前/关闭后零占位，呈现时上下留白计入高度且素材不被裁切。 */
    @Test fun paddingExistsOnlyWithCreativeAndParticipatesInBudget() {
        // 素材高度切换模拟无填充、正常呈现和超过空间上限。
        val creativeHeight = mutableStateOf(0)
        // 记录实际外层像素高度，初始负值能识别尚未测量。
        var measuredHeight = -1
        // 设备像素密度用于将实测边界还原成 dp。
        var density = 1f
        // 记录空间超限是否通知了会话宿主。
        var oversized = false
        compose.setContent {
            // 与生产一样先换算实际像素，再使用空间策略判断。
            val currentDensity = LocalDensity.current
            density = currentDensity.density
            MediaAdFooterLayout(
                availableHeightPx = with(currentDensity) { 208.dp.roundToPx() },
                minimumContentHeightPx = with(currentDensity) { 96.dp.roundToPx() },
                contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp),
                onOversized = { oversized = true },
                modifier = Modifier.width(320.dp).onSizeChanged { measuredHeight = it.height },
            ) {
                Box(Modifier.fillMaxWidth().height(creativeHeight.value.dp).testTag("synthetic_ad"))
            }
        }
        compose.runOnIdle { assertEquals(0, measuredHeight); creativeHeight.value = 100 }
        compose.runOnIdle {
            assertEquals(112f, measuredHeight / density, 1f)
            assertFalse(oversized)
        }
        // 内容坐标包含宿主上方间隔，整张素材仍然保有自然高度。
        val bounds = compose.onNodeWithTag("synthetic_ad").fetchSemanticsNode().boundsInRoot
        assertEquals(12f, bounds.top / density, 1f)
        assertEquals(100f, bounds.height / density, 1f)
        compose.runOnIdle { creativeHeight.value = 101 }
        compose.runOnIdle { assertTrue(oversized); assertEquals(0, measuredHeight) }
        compose.runOnIdle { creativeHeight.value = 0 }
        compose.runOnIdle { assertEquals(0, measuredHeight) }
    }
}
