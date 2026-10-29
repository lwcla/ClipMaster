package com.cla.clip.master.ui.widget.clip

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.cla.clip.base.general.R as BaseR

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

    /** 拖动和离场动画保留图标，但不允许第二次点击菜单与既定折叠动作并发。 */
    @Test fun menuCannotRunDuringDragOrExitAnimation() {
        showCard()
        dragTo(0.82f)
        // 真实菜单仍存在，但三个入口都必须禁用，避免多指操作改变正在拖动的记录。
        readMenuBounds()
        listOf(BaseR.string.base_general_copy, BaseR.string.base_general_pinned, BaseR.string.base_general_delete)
            .forEach { descriptionRes ->
                // 用生产描述检查可访问状态，禁用不等于隐藏图标。
                val description = InstrumentationRegistry.getInstrumentation().targetContext.getString(descriptionRes)
                compose.onNodeWithContentDescription(description).assertIsNotEnabled()
            }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("swipe-card").performTouchInput { up() }
        // 推进到 220ms 离场动画中段，此时菜单完整露出而业务回调尚未执行。
        compose.mainClock.advanceTimeBy(80)
        listOf(BaseR.string.base_general_copy, BaseR.string.base_general_pinned, BaseR.string.base_general_delete)
            .forEach { descriptionRes ->
                // 对每个禁用按钮注入真实触摸，不能绕过命中测试直接调用语义回调。
                val description = InstrumentationRegistry.getInstrumentation().targetContext.getString(descriptionRes)
                compose.onNodeWithContentDescription(description)
                    .assertIsDisplayed().assertIsNotEnabled()
                    .performTouchInput { down(center); up() }
            }
        compose.runOnIdle {
            assertEquals(0, copyCount)
            assertEquals(0, actionCount)
        }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            assertEquals(0, copyCount)
            assertEquals(1, actionCount)
        }
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

    /** 跨菜单和达标边界往返时三个图标常驻且位置不变，吸附后仍可复制。 */
    @Test fun menuStaysVisibleAcrossSwipeStages() {
        showCard(cardWidth = 240.dp)
        // 此处偏移 144dp，三个图标已露出，但尚未越过 145dp 菜单边界。
        dragTo(0.60f)
        compose.onNodeWithText("继续右滑折叠数据").assertDoesNotExist()
        // 保存实际菜单坐标，以后续各阶段相同位置作为不跳变的验收依据。
        val originalBounds = readMenuBounds()
        // 依次进入第二段、达标、回拉至未达标，再回到菜单阶段。
        listOf(0.70f, 0.83f, 0.70f, 0.60f).forEach { fraction ->
            compose.onNodeWithTag("swipe-card").performTouchInput {
                moveTo(Offset(1f + touchSlopPx + width * fraction, height / 2f))
            }
            assertEquals(originalBounds, readMenuBounds())
            when (fraction) {
                0.83f -> compose.onNodeWithText("松手折叠").assertIsDisplayed()
                0.70f -> compose.onNodeWithText("继续右滑折叠数据").assertIsDisplayed()
                else -> compose.onNodeWithText("继续右滑折叠数据").assertDoesNotExist()
            }
        }
        compose.onNodeWithTag("swipe-card").performTouchInput { up() }
        compose.waitForIdle()
        // 手势结束并吸附后，用真实触摸验证菜单恢复可用，避免禁用状态遗留。
        compose.onNodeWithContentDescription(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(BaseR.string.base_general_copy)
        ).assertIsDisplayed().performTouchInput { down(center); up() }
        compose.runOnIdle {
            assertEquals(1, copyCount)
            assertEquals(0, actionCount)
        }
    }

    /** 相同继续文案随拖动只改变露出量，不横移、不重新测量换行或覆盖菜单。 */
    @Test fun hintStaysToTheRightOfMenu() {
        showCard()
        dragTo(0.60f)
        // 最后一个按钮右边界就是菜单末端，避免在测试中复制菜单宽度公式。
        val menuRight = readMenuBounds().last().right
        // 文本边界及排版结果分别检查固定起点和稳定换行。
        val originalBounds = compose.onNodeWithText("继续右滑折叠数据").fetchSemanticsNode().boundsInRoot
        val originalLayout = readHintLayout("继续右滑折叠数据")
        assertTrue("提示必须位于菜单右侧", originalBounds.left >= menuRight)
        compose.onNodeWithTag("swipe-card").performTouchInput {
            moveTo(Offset(1f + touchSlopPx + width * 0.75f, height / 2f))
        }
        // 相同文字在更大偏移处的布局应完全相同，不能跟随暴露区宽度居中。
        val movedBounds = compose.onNodeWithText("继续右滑折叠数据").fetchSemanticsNode().boundsInRoot
        val movedLayout = readHintLayout("继续右滑折叠数据")
        assertEquals(originalBounds.left, movedBounds.left, 0.5f)
        assertEquals(originalLayout.size, movedLayout.size)
        assertEquals(originalLayout.lineCount, movedLayout.lineCount)
        assertEquals(menuRight, readMenuBounds().last().right, 0.5f)
        compose.onNodeWithTag("swipe-card").performTouchInput { cancel() }
    }

    /** 常规宽度和字号下，松手取消折叠的文字在达标位置完整露出且没有省略。 */
    @Test fun normalUnfoldHintFitsExposedArea() {
        showCard(continueText = "继续右滑取消折叠", releaseText = "松手取消折叠")
        // 保存卡片初始内容位置，用实际位移计算前景遮挡边界。
        val originalContentLeft = compose.onNodeWithText("合成卡片").fetchSemanticsNode().boundsInRoot.left
        val cardLeft = compose.onNodeWithTag("swipe-card").fetchSemanticsNode().boundsInRoot.left
        dragTo(0.82f)
        // 只取文字真实行宽，不能将完整排版容器宽度误当成文字墨迹宽度。
        val layout = readHintLayout("松手取消折叠")
        val textLeft = compose.onNodeWithText("松手取消折叠").fetchSemanticsNode().boundsInRoot.left
        val contentLeft = compose.onNodeWithText("合成卡片").fetchSemanticsNode().boundsInRoot.left
        val exposedRight = cardLeft + contentLeft - originalContentLeft
        assertFalse(layout.hasVisualOverflow)
        // 每一行的最右文字都应位于正文卡片左侧，才能认定完整可见。
        repeat(layout.lineCount) { line ->
            assertTrue("达标文案不应被正文遮挡", textLeft + layout.getLineRight(line) <= exposedRight + 1f)
        }
        compose.onNodeWithTag("swipe-card").performTouchInput { cancel() }
    }

    /** 窄屏双倍字体允许部分遮挡或省略，但保留图标、右侧起点和取消折叠动作。 */
    @Test fun narrowLargeFontKeepsIconsAndUnfoldAction() {
        showCard(
            cardWidth = 240.dp,
            fontScale = 2f,
            darkTheme = true,
            continueText = "继续右滑取消折叠",
            releaseText = "松手取消折叠",
        )
        dragTo(0.83f)
        compose.onNodeWithText("松手取消折叠").assertIsDisplayed()
        // 保持真实菜单和右侧提示边界，不再假设极限字号下整句全显。
        val menuRight = readMenuBounds().last().right
        val hintBounds = compose.onNodeWithText("松手取消折叠").fetchSemanticsNode().boundsInRoot
        assertTrue("大字体提示也不能盖住菜单", hintBounds.left >= menuRight)
        compose.onNodeWithTag("swipe-card").performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, actionCount) }
    }

    /** 读取三个真实按钮的边界，同时验证它们没有被滑动阶段移出组合。 */
    private fun readMenuBounds(): List<Rect> = listOf(
        BaseR.string.base_general_copy,
        BaseR.string.base_general_pinned,
        BaseR.string.base_general_delete,
    ).map { descriptionRes ->
        // 直接使用生产资源描述，不依赖语言或图标内部结构。
        val description = InstrumentationRegistry.getInstrumentation().targetContext.getString(descriptionRes)
        compose.onNodeWithContentDescription(description).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    }

    /** 获取 Compose 的真实排版结果，避免把语义存在等同于完整可读。 */
    private fun readHintLayout(text: String): TextLayoutResult {
        // 同一提示只有一个 Text，明确要求返回单个布局结果。
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { read ->
            read(layouts)
        }
        return layouts.single()
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
