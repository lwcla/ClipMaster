package com.cla.clip.master.localization

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import android.view.ContextThemeWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cla.clip.master.R
import com.cla.clip.master.ui.page.image.component.ImageCandidateToolbar
import com.cla.clip.master.ui.page.image.component.ImageCandidateToolbarState
import com.cla.clip.master.ui.page.detail.DetailLinkActions
import com.cla.clip.master.ui.page.search.SearchFieldsDropdownContent
import com.cla.clip.base.general.search.ClipSearchField
import com.cla.clip.master.ui.theme.ClipMasterTheme
import com.cla.clip.master.ui.widget.ClipBatchSelectionActionBar
import com.cla.clip.master.ui.widget.ExpandableSettingSwitchEntryCard
import com.cla.clip.master.ui.widget.FirstLaunchConsentDialog
import com.cla.clip.master.ui.widget.SettingDropdownEntryCard
import com.cla.clip.master.ui.widget.SingleChoiceOption
import com.cla.clip.master.ui.widget.TitleBar
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.cla.clip.base.general.R as BaseR

/** 用真实生产组件测英文窄屏排版，所有动作均为空回调，不触发业务、权限或联网。 */
@RunWith(AndroidJUnit4::class)
class EnglishLayoutDeviceTest {
    /** 独立 Compose Activity 承载组件，配合普通 Application runner 隔离原应用数据。 */
    @get:Rule val compose = createComposeRule()

    /** 真正页面标题在普通及放大字号下都不能被固定侧边留白压成省略号。 */
    @Test fun pageTitlesRemainReadable() {
        // titles 对应实际页面名，合成宿主只测试标题布局，不进入数据页面。
        val titles = listOf("Image extraction", "Clipboard filtering", "Backup & restore", "Video download")
        verifyScales("titles", titles) {
            Column { titles.forEach { title -> TitleBar(title, onBack = {}) } }
        }
    }

    /** 双动作与反向布局也使用真实宽度，不能挤掉标题全文。 */
    @Test fun titleWithTwoActionsRemainsReadableInRtl() {
        verifyScales("title-actions-rtl", listOf("Image download history")) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                TitleBar("Image download history", onBack = {}, actions = {
                    // 合成按钮保持生产 48dp 热区，只占位而不执行任何业务操作。
                    IconButton(onClick = {}) { Icon(Icons.Default.Info, "First action") }
                    IconButton(onClick = {}) { Icon(Icons.Default.Info, "Second action") }
                })
            }
        }
        // bounds 来自大字号真实语义节点；两个动作都必须可见且不与标题重叠。
        val titleBounds = compose.onNodeWithText("Image download history").fetchSemanticsNode().boundsInRoot
        for (description in listOf("First action", "Second action")) {
            val action = compose.onNodeWithContentDescription(description).assertIsDisplayed()
            assertFalse("Title overlaps $description", titleBounds.overlaps(action.fetchSemanticsNode().boundsInRoot))
        }
    }

    /** 设置标题需保留完整含义，说明正文仍允许按产品设计默认收起。 */
    @Test fun permissionSettingTitlesRemainReadable() {
        verifyScales("settings", listOf("Notification permission", "Shizuku permission", "Online previews")) {
            Column {
                listOf(R.string.host_notification_permission_title, R.string.host_shizuku_permission_title,
                    R.string.host_link_preview_title).forEach { titleId ->
                    ExpandableSettingSwitchEntryCard(
                        title = stringResource(titleId), description = "Optional feature. Tap to read the full explanation.",
                        checked = false, enabled = true, icon = { Icon(Icons.Default.Info, null) }, onCheckedChange = {},
                    )
                }
            }
        }
    }

    /** 当前设置和入口名称均有业务含义，不能只保证读屏能获得被视觉截断的全文。 */
    @Test fun dropdownSettingRemainsReadable() {
        verifyScales("dropdown", listOf("Quick action", "Extract images")) {
            SettingDropdownEntryCard(
                title = stringResource(BaseR.string.base_general_clip_item_quick_action_setting),
                selectedLabel = "Extract images", options = listOf(SingleChoiceOption("image", "Extract images")),
                selectedValue = "image", expanded = false, onExpandedChange = {}, onSelect = {},
                accessibilityDescription = "Quick action: Extract images", icon = { Icon(Icons.Default.Info, null) },
            )
        }
    }

    /** 图片下载按钮不得占满同一行，挤掉提取状态与计数。 */
    @Test fun imageCandidateActionsRemainReadable() {
        verifyScales("image-actions", listOf("Extraction complete", "Selected: 185/205", "Download selected images"), maxLabelLines = 2) {
            ImageCandidateToolbar(
                state = ImageCandidateToolbarState(false, false, 185, 205, false, true, true, false),
                onRetry = {}, onToggleSelectAll = {}, onFilterFailedChange = {}, onStopExtract = {}, onConfirmDownload = {},
            )
        }
    }

    /** 完整字段菜单必须展示真实选项，区别于允许省略的筛选入口摘要。 */
    @Test fun searchFieldOptionsRemainReadable() {
        verifyScales("search-fields", listOf("Clipboard content", "Link description", "Source app package name")) {
            SearchFieldsDropdownContent(ClipSearchField.entries.toSet(), {}, Modifier.width(220.dp))
        }
    }

    /** 详情快捷区和弹层复用同一动作组，窄卡片内也必须保留完整的三个操作。 */
    @Test fun detailLinkActionsRemainReadable() {
        // canExtract 和 clicks 只验证页面传入的许可与回调，不执行复制或导航。
        val canExtract = mutableStateOf(true)
        val clicks = mutableListOf<String>()
        verifyScales("detail-actions", listOf("Copy link", "Extract video", "Extract images")) {
            DetailLinkActions(canExtract.value, { clicks += "copy" }, { clicks += "video" }, { clicks += "images" }, Modifier.width(240.dp))
        }
        compose.onNodeWithText("Copy link").performClick()
        compose.onNodeWithText("Extract video").performClick()
        compose.onNodeWithText("Extract images").performClick()
        assertEquals(listOf("copy", "video", "images"), clicks)
        compose.runOnIdle { canExtract.value = false }
        compose.onNodeWithText("Copy link").assertIsDisplayed()
        compose.onNodeWithText("Extract video").assertDoesNotExist()
        compose.onNodeWithText("Extract images").assertDoesNotExist()
    }

    /** 批量动作与选中计数需同时可见，避免按钮把计数挤成逐字竖排。 */
    @Test fun batchActionsRemainReadable() {
        verifyScales("batch-actions", listOf("Selected: 185", "Delete", "Fold")) {
            ClipBatchSelectionActionBar("Selected: 185", "Delete", "Fold", {}, {})
        }
    }

    /** 长说明可滚动，但弹窗标题与两个决定按钮必须完整可读。 */
    @Test fun consentDialogActionsRemainReadable() {
        verifyScales("consent", listOf("Clipboard and optional features", "Agree and continue", "Not now")) {
            FirstLaunchConsentDialog(false, false, {}, {}, Modifier.width(320.dp))
        }
    }

    /** 一次挂载组件后切换字号，先截图再汇总溢出，确保失败也留下实际渲染证据。 */
    private fun verifyScales(stage: String, labels: List<String>, maxLabelLines: Int = Int.MAX_VALUE, content: @Composable () -> Unit) {
        // instrumentation/target 提供真实 APK 资源和截图目录，不改变手机系统语言。
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        // scale 控制当前合成宿主的字体放大倍数，不写系统设置。
        val scale = mutableFloatStateOf(1f)
        // failures 保存两个字号下的全部问题，避免首个失败阻止大字号证据采集。
        val failures = mutableListOf<String>()
        compose.setContent {
            key(scale.floatValue) {
                // configuration/context 仅覆盖本测试 View，弹窗也从相同 View Context 获取字号，避免只覆盖 LocalDensity 的假大字测试。
                val configuration = Configuration(LocalConfiguration.current).apply {
                    setLocales(LocaleList.forLanguageTags("en-US"))
                    fontScale = scale.floatValue
                }
                val englishContext = ContextThemeWrapper(LocalContext.current, 0).apply { applyOverrideConfiguration(configuration) }
                AndroidView(modifier = Modifier.fillMaxSize(), factory = {
                    // 独立 ComposeView 继承 Activity 生命周期；key 变化时随 AndroidView 正常释放旧组合。
                    ComposeView(englishContext).apply { setContent {
                        ClipMasterTheme(darkTheme = false, dynamicColor = false) {
                            Surface(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                    Column(Modifier.width(320.dp)) { content() }
                                }
                            }
                        }
                    } }
                })
            }
        }
        // fontScale 覆盖正常字号和常用放大字号；只驱动测试组合，不写系统设置。
        for (fontScale in listOf(1f, 1.3f)) {
            compose.runOnIdle { scale.floatValue = fontScale }
            compose.waitForIdle()
            // directory/file 仅保存当前独立应用的测试截图，不包含用户列表、网页或通知栏。
            val directory = File(target.getExternalFilesDir(null), "english-layout").apply { mkdirs() }
            val file = File(directory, "$stage-$fontScale.png")
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            file.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
            bitmap.recycle()
            // label 是关键操作或标题全文；有意收起的描述和内容预览不纳入此断言。
            for (label in labels) {
                // nodes 使用未合并语义树定位真实 Text，避免只验证父卡片保留的读屏文案。
                val nodes = compose.onAllNodesWithText(label, useUnmergedTree = true)
                if (nodes.fetchSemanticsNodes().isEmpty()) {
                    failures += "$stage/$fontScale missing text: $label"
                    continue
                }
                nodes[0].assertIsDisplayed()
                // layouts 收集生产 Text 的真实排版结果，不复制组件的 maxLines 或尺寸规则。
                val layouts = mutableListOf<TextLayoutResult>()
                nodes[0].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getResults -> getResults(layouts) }
                assertTrue("$stage/$label requires actual text layout", layouts.isNotEmpty())
                for (layout in layouts) {
                    // 验证实际 Text 排版字号，尤其防止 Dialog 新窗口绕过测试的字号配置。
                    assertEquals("$stage/$label font scale", fontScale, layout.layoutInput.density.fontScale, 0.01f)
                    // paragraph.width 是排版预留宽度而非字形宽度；按真实行边界判断，并容忍一像素舍入。
                    if (layout.multiParagraph.height > layout.size.height + 1f ||
                        (0 until layout.lineCount).any { layout.getLineRight(it) > layout.size.width + 1f || layout.getLineLeft(it) < -1f } ||
                        layout.multiParagraph.didExceedMaxLines ||
                        layout.lineCount > maxLabelLines ||
                        (0 until layout.lineCount).any { layout.isLineEllipsized(it) }) {
                        failures += "$stage/$fontScale clipped: $label size=${layout.size} lines=${layout.lineCount}"
                    }
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
