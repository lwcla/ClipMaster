package com.cla.clip.master.ui.widget.clip

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.master.ui.theme.ClipMasterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 用合成卡片验证真实手势与文案接线，不加载数据库、网络、系统图标或用户内容。 */
@RunWith(AndroidJUnit4::class)
class ClipSwipePastInteractionTest {
    /** Compose 宿主提供可控帧时钟，离场副作用用计数回调替代。 */
    @get:Rule val compose = createComposeRule()
    /** 当前设备 touchSlop，输入位移需补偿它才能得到目标卡片偏移。 */
    private var touchSlopPx = 0f
    /** 动作调用次数，用于检查取消及重复执行。 */
    private var actionCount = 0
    /** 菜单复制计数，验证从第二段退回后仍能正常点击。 */
    private var copyCount = 0
    /** 当前回调可在拖动中更新，验证协程不持有旧闭包。 */
    private val action = mutableStateOf<((ClipShowEntity) -> Unit)?>({ actionCount++ })
    /** 选择模式可在同一组合内切换，检查禁用边界。 */
    private val selecting = mutableStateOf(false)
    /** 模拟只需要普通菜单的调用方，无成对文案时关闭第二段。 */
    private val textsEnabled = mutableStateOf(true)
    /** 测试中模拟窗口缩窄，触发真实测量与 pointerInput key 重启。 */
    private val currentWidth = mutableStateOf(360.dp)

    /** 有菜单达标显示松手文案，继续拖动不会因重组丢失手势。 */
    @Test fun showsReleaseHintAndCommitsOnceAfterExit() {
        showCard()
        dragTo(0.82f)
        compose.onNodeWithText("松手折叠").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("swipe-card").performTouchInput { up() }
        compose.runOnIdle { assertEquals(0, actionCount) }
        compose.mainClock.advanceTimeBy(160)
        compose.runOnIdle { assertEquals(0, actionCount) }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertEquals(1, actionCount) }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertEquals(1, actionCount) }
    }

    /** 无菜单回收站同样使用 80%，并明确彻底删除语义。 */
    @Test fun recycleWithoutMenuShowsReleaseDelete() {
        showCard(showMenu = false, continueText = "继续右滑彻底删除")
        dragTo(0.82f)
        compose.onNodeWithText("松手彻底删除").assertIsDisplayed()
        compose.onNodeWithTag("swipe-card").performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, actionCount) }
    }

    /** 未达标松手只吸附，不提交折叠。 */
    @Test fun belowThresholdDoesNotCommit() {
        showCard()
        dragTo(0.75f)
        compose.onNodeWithText("继续右滑折叠数据").assertIsDisplayed()
        compose.onNodeWithTag("swipe-card").performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, actionCount) }
    }

    /** 达标后回拉立即恢复继续提示，松手不使用历史峰值提交。 */
    @Test fun pullingBackBelowThresholdCancelsReadiness() {
        showCard()
        dragTo(0.82f)
        compose.onNodeWithText("松手折叠").assertIsDisplayed()
        compose.onNodeWithTag("swipe-card").performTouchInput {
            moveTo(Offset(1f + touchSlopPx + width * 0.70f, height / 2f))
        }
        compose.onNodeWithText("继续右滑折叠数据").assertIsDisplayed()
        compose.onNodeWithTag("swipe-card").performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, actionCount) }
    }

    /** 系统取消不能等价为用户松手，已达标也不执行删除或折叠。 */
    @Test fun cancellingReadyGestureDoesNotCommit() {
        showCard()
        dragTo(0.82f)
        compose.onNodeWithTag("swipe-card").performTouchInput { cancel() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, actionCount) }
    }

    /** 手势协程执行最新业务回调，文案重组不应重启当前拖动。 */
    @Test fun usesLatestCallbackWithoutRestartingDrag() {
        showCard()
        dragTo(0.82f)
        compose.runOnIdle { action.value = { actionCount += 10 } }
        compose.onNodeWithTag("swipe-card").performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(10, actionCount) }
    }

    /** 多选模式下不能执行第二段动作。 */
    @Test fun selectionDisablesSwipeAction() {
        selecting.value = true
        showCard()
        dragTo(0.82f)
        compose.onNodeWithTag("swipe-card").performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, actionCount) }
    }

    /** 缺失回调时仅保留菜单能力，不显示没有真实动作的松手提示。 */
    @Test fun missingCallbackDisablesReleaseHint() {
        action.value = null
        showCard()
        dragTo(0.82f)
        compose.onNodeWithText("松手折叠").assertDoesNotExist()
        compose.onNodeWithTag("swipe-card").performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, actionCount) }
    }

    /** 没有文案配置的调用方只允许展开菜单，不以业务回调存在为由自动启用第二段。 */
    @Test fun missingTextsKeepsOnlyTheMenu() {
        textsEnabled.value = false
        showCard()
        dragTo(0.82f)
        compose.onNodeWithText("松手折叠").assertDoesNotExist()
        compose.onNodeWithTag("swipe-card").performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, actionCount) }
    }

    /** 进入第二段移除菜单语义，回拉并吸附到菜单后恢复按钮及其原有回调。 */
    @Test fun menuReturnsAfterPullback() {
        // 读取生产复制按钮的资源，避免测试另猜无障碍描述。
        val copyDescription = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(com.cla.clip.base.general.R.string.base_general_copy)
        showCard(cardWidth = 240.dp)
        dragTo(0.83f)
        compose.onNodeWithContentDescription(copyDescription).assertDoesNotExist()
        compose.onNodeWithTag("swipe-card").performTouchInput {
            moveTo(Offset(1f + touchSlopPx + width * 0.50f, height / 2f))
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(copyDescription).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, copyCount)
            assertEquals(0, actionCount)
        }
    }

    /** 窄屏、双倍字体和暗色下取消折叠提示仍完整可见，不依赖菜单后的狭小空间。 */
    @Test fun narrowLargeFontUnfoldHintFitsExposedArea() {
        showCard(
            cardWidth = 240.dp,
            fontScale = 2f,
            darkTheme = true,
            continueText = "继续右滑取消折叠",
            releaseText = "松手取消折叠",
        )
        dragTo(0.83f)
        compose.onNodeWithText("松手取消折叠").assertIsDisplayed()
        // 真实文字布局结果验证没有省略，不用语义文本存在代替可读性验证。
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("松手取消折叠").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { read ->
            read(layouts)
        }
        assertFalse(layouts.single().hasVisualOverflow)
        compose.onNodeWithTag("swipe-card").performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, actionCount) }
    }

    /** 按住时改变测量宽度会取消旧协程，必须清掉偏移而不是留下整卡外移。 */
    @Test fun widthChangeDuringDragResetsCardWithoutCommitting() {
        showCard()
        dragTo(0.82f)
        compose.onNodeWithText("松手折叠").assertIsDisplayed()
        compose.runOnIdle { currentWidth.value = 240.dp }
        compose.waitForIdle()
        compose.onNodeWithText("松手折叠").assertDoesNotExist()
        compose.onNodeWithText("合成卡片").assertIsDisplayed()
        compose.onNodeWithTag("swipe-card").performTouchInput { up() }
        compose.runOnIdle { assertEquals(0, actionCount) }
    }

    /** 拖动中关闭文案时，同样触发 key 取消，不能留下大偏移或在旧手势松手时执行。 */
    @Test fun removingTextsDuringDragResetsCardWithoutCommitting() {
        showCard()
        // 保存真实初始位置，验证偏移清理而不绑定正文内边距的实现常量。
        val originalLeft = compose.onNodeWithText("合成卡片").fetchSemanticsNode().boundsInRoot.left
        dragTo(0.82f)
        compose.runOnIdle { textsEnabled.value = false }
        compose.waitForIdle()
        compose.onNodeWithText("松手折叠").assertDoesNotExist()
        assertEquals(originalLeft, compose.onNodeWithText("合成卡片").fetchSemanticsNode().boundsInRoot.left, 0.5f)
        compose.onNodeWithTag("swipe-card").performTouchInput { up() }
        compose.runOnIdle { assertEquals(0, actionCount) }
    }

    /** 构造稳定大小的真实卡片，传入空图标加载器的默认值以避免系统与网络读取。 */
    private fun showCard(
        showMenu: Boolean = true,
        continueText: String = "继续右滑折叠数据",
        releaseText: String = if (showMenu) "松手折叠" else "松手彻底删除",
        cardWidth: Dp = 360.dp,
        fontScale: Float = 1f,
        darkTheme: Boolean = false,
    ) {
        currentWidth.value = cardWidth
        // 固定合成数据，不使用当前时间、用户来源或真实链接。
        val clip = ClipShowEntity(1L, "合成卡片", 1L, 0L, 0L, "", null, false, false, null, null, null)
        compose.setContent {
            touchSlopPx = LocalViewConfiguration.current.touchSlop
            // 只替换字体缩放，保留设备真实像素密度和系统手势阈值。
            val testDensity = Density(LocalDensity.current.density, fontScale)
            CompositionLocalProvider(LocalDensity provides testDensity) {
              ClipMasterTheme(dynamicColor = false, darkTheme = darkTheme) {
                Box(Modifier.width(currentWidth.value)) {
                    ClipCard(
                        clip = clip,
                        onPinToggle = if (showMenu) ({}) else null,
                        onDelete = if (showMenu) ({}) else null,
                        onCopy = if (showMenu) ({ copyCount++ }) else null,
                        onSwipePastAction = action.value,
                        onClick = {},
                        onLongClick = {},
                        onKeepCurrentScrollPosition = {},
                        swipePastTexts = if (textsEnabled.value) ClipSwipePastTexts(continueText, releaseText) else null,
                        selectionMode = selecting.value,
                        modifier = Modifier.testTag("swipe-card"),
                    )
                }
              }
            }
        }
    }

    /** 分两次移动跨过 touchSlop，最后保持按下，便于检查文案和模拟回拉/取消。 */
    private fun dragTo(fraction: Float) {
        compose.onNodeWithTag("swipe-card").performTouchInput {
            down(Offset(1f, height / 2f))
            moveTo(Offset(1f + touchSlopPx + 1f, height / 2f))
            moveTo(Offset(1f + touchSlopPx + width * fraction, height / 2f))
        }
    }
}
