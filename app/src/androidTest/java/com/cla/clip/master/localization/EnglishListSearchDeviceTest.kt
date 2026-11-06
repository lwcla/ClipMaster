package com.cla.clip.master.localization

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.dao.SearchHistoryData
import com.cla.clip.base.general.dao.data.SourceAppCandidate
import com.cla.clip.master.ui.dialog.ClipBatchDeleteChoiceDialog
import com.cla.clip.master.ui.page.search.ClearSearchHistoryDialog
import com.cla.clip.master.ui.page.search.SearchFilterMenu
import com.cla.clip.master.ui.page.search.SearchFilters
import com.cla.clip.master.ui.page.search.SearchFilterState
import com.cla.clip.master.ui.page.search.SearchHistoryPanel
import com.cla.clip.master.ui.widget.ClipBatchSelectionActionBar
import com.cla.clip.master.ui.widget.ListEntryCard
import com.cla.clip.master.ui.widget.SelectableListBottomSheet
import com.cla.clip.master.ui.widget.SelectableListItemState
import com.cla.clip.master.ui.widget.SingleChoiceDialog
import com.cla.clip.master.ui.widget.SingleChoiceOption
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 列表/搜索与共享选择入口覆盖；实际整页和业务 VM 的组合由独立集成测试补齐。 */
@RunWith(Parameterized::class)
class EnglishListSearchDeviceTest(
    /** 每个场景独立 Activity 与弹窗状态，避免上一弹窗影响下一场景。 */
    private val scenario: String,
) : EnglishScreenTestBase() {
    companion object {
        /** 场景按真实入口/弹窗结构划分，不为重复文案枚举制造无价值测试。 */
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun scenarios(): List<Array<String>> = listOf(
            "entry", "batch-actions", "batch-delete", "clear-history", "history-empty", "history-long",
            "filters", "fields-menu", "time-menu", "source-menu", "source-empty",
            "single-choice", "selection-sheet", "selection-error",
        ).map { arrayOf(it) }
    }

    /** 生产入口在 320dp、大字及合成超长内容下可读，省略白名单仅限历史/筛选入口摘要。 */
    @Test fun listSearchAndSharedOverlaysRemainReadable() {
        verifyEnglish("list-search-$scenario", labels = when (scenario) {
            "batch-actions" -> listOf("Selected: 123456789", "Delete", "Fold", "Clipboard entries remain visible")
            "selection-sheet" -> listOf("Select clipboard source applications", "Select all applications", "Cancel", "Apply selection")
            "selection-error" -> listOf("Select clipboard source applications", "Select all applications", "Cancel", "Apply selection", "Select at least one application before continuing.")
            else -> emptyList()
        }, allowEllipsis = { text ->
            text.startsWith("Synthetic search query")
        }, exercise = { rule ->
            if (scenario == "single-choice") rule.onNodeWithText("Keep all clipboard entries indefinitely").performScrollTo()
            if (scenario.startsWith("selection-")) {
                // 逐项滚动并验证真实列表可见，避免零高 LazyColumn 中仅有语义数据而没有可用视口。
                for (title in listOf("Synthetic international productivity application", "Synthetic document and information management application")) {
                    rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(title))
                    rule.onNodeWithText(title).assertIsDisplayed()
                    checkAndCapture("list-search-$scenario-option-${title.length}")
                }
            }
        }) {
            when (scenario) {
                "entry" -> ListEntryCard("Clipboard filtering and source applications",
                    "Manage which applications can save clipboard entries, including newly installed applications.", {},
                    icon = { Icon(Icons.Default.Info, null) })
                "batch-actions" -> Scaffold(Modifier.height(480.dp), bottomBar = {
                    ClipBatchSelectionActionBar("Selected: 123456789", "Delete", "Fold", {}, {})
                }) { padding ->
                    // 与生产相同的底栏 Insets 关系，验证大字操作栏仍给结果列表保留可见区域。
                    Box(Modifier.fillMaxSize().padding(padding)) { Text("Clipboard entries remain visible") }
                }
                "batch-delete" -> ClipBatchDeleteChoiceDialog(123456789, true, {}, {}, {})
                "clear-history" -> ClearSearchHistoryDialog({}, {})
                "history-empty", "history-long" -> SearchHistoryPanel(
                    listState = rememberLazyListState(), query = "", histories = if (scenario == "history-empty") emptyList() else listOf(
                        SearchHistoryData(id = 1, query = "Synthetic search query with a long descriptive phrase for narrow screens",
                            normalizedQuery = "synthetic", isFolded = false, updatedAt = 1700000000000),
                    ), onHistoryClick = {}, onDeleteHistory = {}, onClearHistories = {}, modifier = Modifier.fillMaxSize(),
                )
                "filters", "fields-menu", "time-menu", "source-menu", "source-empty" -> SearchFilters(
                    filterState = SearchFilterState(), selectedSourceAppNames = emptyList(),
                    sourceApps = if (scenario == "source-empty") emptyList() else listOf(
                        SourceAppCandidate(packageName = "synthetic.app", appName = "Synthetic international productivity application"),
                    ), expandedMenu = when (scenario) {
                        "fields-menu" -> SearchFilterMenu.Target
                        "time-menu" -> SearchFilterMenu.Time
                        "source-menu", "source-empty" -> SearchFilterMenu.Source
                        else -> SearchFilterMenu.None
                    }, onMenuChange = {}, onToggleField = {}, onTimeSelected = {}, onToggleAllSources = {}, onToggleSource = {},
                )
                "single-choice" -> SingleChoiceDialog("Choose automatic cleanup behavior", listOf(
                    SingleChoiceOption(1, "Remove clipboard entries older than thirty days"),
                    SingleChoiceOption(2, "Keep entries until they are deleted manually"),
                    SingleChoiceOption(3, "Keep all clipboard entries indefinitely"),
                ), 1, {}, {}, description = "This setting controls how long clipboard entries remain available. " .repeat(6), dismissText = "Cancel")
                "selection-sheet", "selection-error" -> SelectableListBottomSheet(
                    title = "Select clipboard source applications", items = listOf(
                        SelectableListItemState("a", "Synthetic international productivity application", selected = true),
                        SelectableListItemState("b", "Synthetic document and information management application", selected = false),
                    ), onDismiss = {}, onToggleItem = {}, onConfirm = {}, confirmEnabled = true,
                    confirmText = "Apply selection", cancelText = "Cancel", showSelectAll = true,
                    selectAllText = "Select all applications", onToggleSelectAll = {},
                    errorText = if (scenario == "selection-error") "Select at least one application before continuing." else null,
                )
            }
        }
    }
}
