package com.cla.clip.master.localization

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.master.ui.widget.clip.ClipCard
import com.cla.clip.master.ui.widget.clip.ClipCardTimeMode
import com.cla.clip.master.ui.widget.clip.ClipSearchHighlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真实共享卡片的元信息回归；仅合成数据和计数回调，不读系统图标、不复制或改数据库。 */
@RunWith(AndroidJUnit4::class)
class EnglishClipMetadataDeviceTest : EnglishScreenTestBase() {
    /** 短来源仍能与普通时间自然排版，字号不允许被局部固定。 */
    @Test fun shortSourceAndClipTimeFollowSystemFont() = verifyMetadata("short", ClipCardTimeMode.ClipTime, "Synthetic")

    /** 长来源保留一行摘要，完整普通时间必须获得独立的可读空间。 */
    @Test fun longSourceAndClipTimeRemainReadable() = verifyMetadata("clip", ClipCardTimeMode.ClipTime)

    /** 折叠前缀和日期在窄屏大字体下允许换行，不再限制半宽单行。 */
    @Test fun longSourceAndFoldedTimeRemainReadable() = verifyMetadata("folded", ClipCardTimeMode.FoldedTime)

    /** 回收站删除时间保留完整前缀，点击元信息仍服从卡片既有动作。 */
    @Test fun longSourceAndDeletedTimeRemainReadable() = verifyMetadata("deleted", ClipCardTimeMode.DeletedTime)

    /** 每个场景复用真实卡片并验证排版及点击契约，不复制来源/时间生产 UI。 */
    private fun verifyMetadata(
        /** 稳定截图场景名，不包含真实来源信息。 */
        stage: String,
        /** 分别使用生产写入、折叠和删除时间路径。 */
        timeMode: ClipCardTimeMode,
        /** 合成来源覆盖长名摘要与普通短名。 */
        sourceName: String = "Synthetic source application with a deliberately long display name",
    ) {
        // 固定历史时间只用于稳定地产生完整日期；不依赖真实剪贴或本机已安装来源。
        val clip = ClipShowEntity(
            id = 1L,
            content = "Synthetic entry",
            timestamp = 946684800000L,
            foldedAt = 946684800000L,
            deletedAt = 946684800000L,
            formattedTime = "January 1, 2000",
            sourceAppPackage = "test.synthetic.metadata",
            isPinned = false,
            isFolded = timeMode == ClipCardTimeMode.FoldedTime,
            linkImgUrl = null,
            link = null,
            linkTitle = null,
            sourceAppName = sourceName,
        )
        // 计数只证明元信息触摸和可访问点击接线保留，不执行任何真实业务动作。
        var clickCount = 0
        verifyEnglish(
            stage = "clip-metadata-$stage",
            labels = listOf(sourceName),
            allowEllipsis = { text -> text == sourceName },
            prepare = { clickCount = 0 },
            exercise = {
                // 未合并树保留实际文字边界；长来源省略是既有摘要契约，时间不在白名单。
                val source = compose.onNodeWithText(sourceName, useUnmergedTree = true)
                source.assertIsDisplayed()
                // 卡片只有正文、来源和时间三段文字，排除前两段后得到生产格式化的时间节点。
                val timeNodes = compose.onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().filter { node ->
                    // 只使用公开语义读取身份，不重算格式化结果或依赖手机日期语言。
                    val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                    text != null && text != sourceName && text != clip.content
                }
                assertEquals("One complete production time label is required", 1, timeNodes.size)
                // 时间的实际行布局必须完整；同时验证两块文字的可见边界没有交叠。
                val time = timeNodes.single()
                val layouts = mutableListOf<TextLayoutResult>()
                compose.runOnIdle { time.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) }
                assertTrue("Time label must expose its real text layout", layouts.isNotEmpty())
                layouts.forEach { layout ->
                    // String Text 的语义段落可能保留父级最大宽度；真实逐行宽度由共享宿主检查，不能直接用 hasVisualOverflow。
                    for (line in 0 until layout.lineCount) assertFalse("Time must not be ellipsized", layout.isLineEllipsized(line))
                }
                // 来源可与时间同行或分行，两种布局都不能覆盖彼此。
                val sourceBounds = source.fetchSemanticsNode().boundsInRoot
                val timeBounds = time.boundsInRoot
                assertTrue("Source and time must not overlap", timeBounds.left >= sourceBounds.right - 1f || timeBounds.top >= sourceBounds.bottom - 1f)
                source.performTouchInput { click() }
                compose.runOnIdle { assertEquals("Touching the source keeps the card callback", 1, clickCount) }
                // 元信息没有新增子级点击语义，TalkBack 继续调用卡片的既有入口。
                compose.onNode(hasClickAction() and hasAnyDescendant(hasText(sourceName))).performClick()
                compose.runOnIdle { assertEquals("Accessible click keeps the card callback", 2, clickCount) }
            },
        ) {
            // 240dp 比宿主 320dp 更窄；使用真实 Card 的 12dp 内边距和默认空图标加载器。
            Box(Modifier.width(240.dp)) {
                ClipCard(
                    clip = clip,
                    highlight = ClipSearchHighlight("Synthetic"),
                    timeMode = timeMode,
                    onPinToggle = null,
                    onDelete = null,
                    onCopy = null,
                    onSwipePastAction = null,
                    onClick = { clickCount++ },
                    onLongClick = {},
                    onKeepCurrentScrollPosition = {},
                )
            }
        }
    }
}
