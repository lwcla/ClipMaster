package com.cla.clip.master.ui.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cla.clip.master.R
import com.cla.clip.master.entity.SettingSwitchItemUi
import com.cla.clip.master.ui.page.mine.PermissionSettingItems
import com.cla.clip.master.ui.theme.ClipMasterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 用合成权限状态验证真实页面适配和共享卡片，不申请权限或修改用户设置。 */
@RunWith(AndroidJUnit4::class)
class ExpandableSettingSwitchEntryCardTest {
    /** 独立 Compose 宿主用于检查真实布局和嵌套点击消费。 */
    @get:Rule val compose = createComposeRule()

    /** 长说明在窄卡片中必然换行，用于识别意外恢复常驻全文的回归。 */
    private val description = "通过测试服务读取并保存内容，方便查看历史记录。未授权不影响应用前台读取，关闭通知也不影响记录保存。"

    /** 捕获业务动作，任何卡片展开导致的调用都代表误操作。 */
    private val actions = mutableListOf<Pair<SettingSwitchItemUi.Id, Boolean>>()

    /** 默认单行，展开一项不能连带展开另一项，再点恢复紧凑高度。 */
    @Test fun descriptionsStartCollapsedAndExpandIndependently() {
        showPermissions()
        assertEquals(1, lineCount(description))
        assertEquals(1, lineCount("通知：$description"))
        clickTitle("测试 Shizuku")
        assertTrue(lineCount(description) > 1)
        assertEquals(1, lineCount("通知：$description"))
        clickTitle("测试 Shizuku")
        assertEquals(1, lineCount(description))
    }

    /** 防止卡片原有整卡切换回调泄漏到新阅读交互。 */
    @Test fun cardClicksNeverRequestPermissionChanges() {
        showPermissions()
        clickTitle("测试 Shizuku")
        clickTitle("测试 Shizuku")
        clickTitle("测试通知")
        compose.runOnIdle { assertTrue("展开说明不能触发权限操作", actions.isEmpty()) }
    }

    /** 读屏焦点落在可点击卡片时必须同时获得展开状态，不能另生成无动作的状态节点。 */
    @Test fun clickableCardAnnouncesItsExpansionState() {
        showPermissions()
        /** 与生产组件同源的本地化状态文案，测试不依赖设备语言。 */
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText("测试 Shizuku").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.host_dropdown_collapsed)),
        )
        clickTitle("测试 Shizuku")
        compose.onNodeWithText("测试 Shizuku").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.host_dropdown_expanded)),
        )
    }

    /** 真实触摸 Switch 只发送一次业务动作，不冒泡展开卡片。 */
    @Test fun switchClickRequestsChangeWithoutExpandingDescription() {
        showPermissions()
        compose.onAllNodes(isToggleable())[0].performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(listOf(SettingSwitchItemUi.Id.Permission.Shizuku to true), actions)
        }
        assertEquals(1, lineCount(description))
    }

    /** 右侧是唯一业务入口，读屏必须知道每个开关的标题且激活时不展开说明。 */
    @Test fun switchesHaveAccessibleNamesAndIndependentActions() {
        showPermissions()
        compose.onAllNodes(isToggleable())[0].assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("测试 Shizuku")),
        )
        compose.onAllNodes(isToggleable())[1].assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("测试通知")),
        )
        compose.onAllNodes(isToggleable())[1].performClick()
        compose.runOnIdle { assertEquals(listOf(SettingSwitchItemUi.Id.Permission.Notice to true), actions) }
        assertEquals(1, lineCount("通知：$description"))
    }

    /** 业务操作禁用时仍能展开阅读，避免加载/保存状态锁住说明。 */
    @Test fun disabledSwitchDoesNotPreventReadingDescription() {
        showPermissions(enabled = false)
        assertEquals(1, lineCount(description))
        clickTitle("测试 Shizuku")
        assertTrue(lineCount(description) > 1)
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    /** 禁用开关仍占据自己的触摸区域，点击它不能冒泡为卡片阅读动作。 */
    @Test fun disabledSwitchClickDoesNotExpandOrSendAction() {
        showPermissions(enabled = false)
        compose.onAllNodes(isToggleable())[0].performTouchInput { click() }
        assertEquals(1, lineCount(description))
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    /** 手动推进动画时钟，防止全文直接跳变高度或只对展开方向添加动画。 */
    @Test fun cardHeightAnimatesWhenExpandingAndCollapsing() {
        showPermissions()
        /** 初始卡片高度用于确认收起后恢复紧凑布局。 */
        val collapsedHeight = cardHeight()
        compose.mainClock.autoAdvance = false
        clickTitle("测试 Shizuku")
        compose.mainClock.advanceTimeBy(80)
        /** 动画中间高度必须介于最终两态之间，避免仅有箭头动画。 */
        val expandingHeight = cardHeight()
        compose.mainClock.advanceTimeBy(300)
        /** 全文完成布局后的高度应大于折叠态。 */
        val expandedHeight = cardHeight()
        assertTrue("展开途中卡片高度应连续增加", expandingHeight > collapsedHeight && expandingHeight < expandedHeight)
        clickTitle("测试 Shizuku")
        compose.mainClock.advanceTimeBy(80)
        /** 反向动画同样不能直接跳回单行高度。 */
        val collapsingHeight = cardHeight()
        assertTrue("收起途中卡片高度应连续减小", collapsingHeight > collapsedHeight && collapsingHeight < expandedHeight)
        compose.mainClock.advanceTimeBy(300)
        assertEquals(collapsedHeight, cardHeight(), 1f)
    }

    /** 联网预览使用的反馈 slot 在大字体、折叠和禁用状态下仍可见、可重试。 */
    @Test fun supportingFeedbackAndRetryStayVisibleWithCollapsedDescription() {
        /** 仅记录重试次数，不发起 Repository 或网络请求。 */
        var retries = 0
        compose.setContent {
            /** 保留设备密度，单独放大字体覆盖窄屏排版边界。 */
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                ClipMasterTheme(dynamicColor = false) {
                    Column(Modifier.width(320.dp)) {
                        ExpandableSettingSwitchEntryCard(
                            title = "测试联网预览", description = description, checked = false, enabled = false,
                            icon = { Icon(Icons.Default.Info, contentDescription = null) },
                            onCheckedChange = { error("禁用时不应提交设置") },
                            supportingContent = {
                                Text("测试读取失败")
                                TextButton(onClick = { retries += 1 }) { Text("测试重试") }
                            },
                        )
                    }
                }
            }
        }
        assertEquals(1, lineCount(description))
        compose.onNodeWithText("测试读取失败").assertIsDisplayed()
        compose.onNodeWithText("测试重试").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, retries) }
        assertEquals(1, lineCount(description))
        clickTitle("测试联网预览")
        assertTrue(lineCount(description) > 1)
        compose.onNodeWithText("测试读取失败").assertIsDisplayed()
    }

    /** 渲染页面真实权限适配，两项使用稳定身份和不同说明，不启动 ViewModel。 */
    private fun showPermissions(enabled: Boolean = true) {
        compose.setContent {
            ClipMasterTheme(dynamicColor = false) {
                Column(Modifier.width(320.dp)) {
                    PermissionSettingItems(
                        items = listOf(
                            SettingSwitchItemUi(SettingSwitchItemUi.Id.Permission.Shizuku, "测试 Shizuku", description, false, enabled),
                            SettingSwitchItemUi(SettingSwitchItemUi.Id.Permission.Notice, "测试通知", "通知：$description", false, enabled),
                        ),
                        // 仅记录目标操作，测试不真正调用系统权限。
                        onItemCheckedChange = { id, checked -> actions.add(id to checked) },
                    )
                }
            }
        }
    }

    /** 从标题实际坐标触摸父卡片，覆盖子控件与卡片的指针事件边界。 */
    private fun clickTitle(title: String) {
        compose.onNodeWithText(title, useUnmergedTree = true).performTouchInput { click() }
    }

    /** 合并语义节点对应整张可点击卡片，可测量动画带来的实际可见高度变化。 */
    private fun cardHeight(): Float = compose.onNodeWithText("测试 Shizuku").fetchSemanticsNode().boundsInRoot.height

    /** 查询 Text 的真实排版行数，避免只检查仍含全文的无障碍字符串。 */
    private fun lineCount(text: String): Int {
        /** 接收生产 Text 暴露的排版结果，不复制生产 maxLines 判断。 */
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getResults -> getResults(layouts) }
        return layouts.single().lineCount
    }
}
