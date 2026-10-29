package com.cla.clip.master.ui.page.search

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.dao.data.SourceAppCandidate
import com.cla.clip.base.general.search.ClipSearchField
import com.cla.clip.master.R
import com.cla.clip.master.ui.widget.FilterDropdown
import com.cla.clip.master.ui.widget.SingleChoiceDropdownContent
import com.cla.clip.master.ui.widget.SingleChoiceOption
import com.cla.clip.master.ui.widget.ThreeFilterRow
import com.cla.clip.master.ui.widget.sourceapp.AppIconLoader
import com.cla.clip.master.ui.widget.sourceapp.EmptyAppIconLoader
import com.cla.clip.base.general.R as BaseR

/** 搜索筛选薄适配；三个入口固定同行，唯一生效状态由页面持有。 */
@Composable
internal fun SearchFilters(
    /** 当前条件，菜单不保留草稿。 */ filterState: SearchFilterState,
    /** 手动所选来源的摘要名称。 */ selectedSourceAppNames: List<String>,
    /** 生命周期内收集的来源投影。 */ sourceApps: List<SourceAppCandidate>,
    /** 来源菜单可见行按需加载图标。 */ iconLoader: AppIconLoader = EmptyAppIconLoader,
    /** 页面统一维护互斥弹层。 */ expandedMenu: SearchFilterMenu,
    /** 仅改变浮层状态，不提交或回滚选择。 */ onMenuChange: (SearchFilterMenu) -> Unit,
    /** 单项切换立即更新结果，菜单保持打开。 */ onToggleField: (ClipSearchField) -> Unit,
    /** 时间单选仍会关闭菜单。 */ onTimeSelected: (SearchTimeFilter) -> Unit,
    /** 来源全选保留既有语义。 */ onToggleAllSources: () -> Unit,
    /** 按稳定包名切换来源。 */ onToggleSource: (String) -> Unit,
) {
    /** 范围入口只展示短摘要，不把所有选中名称挤到单行。 */
    val fieldLabel = filterState.fields.summaryText()
    /** 读屏保留全部字段名称，即使视觉摘要被截断也能辨认。 */
    val fieldDescription = ClipSearchField.entries.filter { it in filterState.fields }.map { it.labelText() }.joinToString("、")
    /** 时间值使用既有资源。 */
    val timeLabel = filterState.timeFilter.labelText()
    /** 来源摘要沿用既有空/单个/多个规则。 */
    val sourceLabel = when (selectedSourceAppNames.size) {
        0 -> stringResource(BaseR.string.base_general_all_source_apps)
        1 -> selectedSourceAppNames.first()
        else -> stringResource(BaseR.string.base_general_selected_source_app_count, selectedSourceAppNames.size)
    }
    ThreeFilterRow(
        modifier = Modifier.padding(horizontal = 12.dp),
        leadingContent = { cellModifier ->
            FilterDropdown(
                value = timeLabel,
                accessibilityDescription = stringResource(R.string.host_search_filter_accessibility, stringResource(BaseR.string.base_general_time), timeLabel),
                expanded = expandedMenu == SearchFilterMenu.Time,
                onExpandedChange = { onMenuChange(if (it) SearchFilterMenu.Time else SearchFilterMenu.None) },
                modifier = cellModifier,
            ) {
                SingleChoiceDropdownContent(
                    options = SearchTimeFilter.entries.map { SingleChoiceOption(it, it.labelText()) },
                    selectedValue = filterState.timeFilter, onSelect = onTimeSelected,
                    onDismiss = { onMenuChange(SearchFilterMenu.None) },
                )
            }
        },
        middleContent = { cellModifier ->
            FilterDropdown(
                value = fieldLabel,
                accessibilityDescription = stringResource(R.string.host_search_filter_accessibility, stringResource(R.string.host_search_target), fieldDescription),
                expanded = expandedMenu == SearchFilterMenu.Target,
                onExpandedChange = { onMenuChange(if (it) SearchFilterMenu.Target else SearchFilterMenu.None) },
                modifier = cellModifier, menuMinWidth = 220.dp,
            ) {
                SearchFieldsDropdownContent(filterState.fields, onToggleField)
            }
        },
        trailingContent = { cellModifier ->
            FilterDropdown(
                value = sourceLabel,
                accessibilityDescription = stringResource(R.string.host_search_filter_accessibility, stringResource(BaseR.string.base_general_source_app), sourceLabel),
                expanded = expandedMenu == SearchFilterMenu.Source,
                onExpandedChange = { onMenuChange(if (it) SearchFilterMenu.Source else SearchFilterMenu.None) },
                modifier = cellModifier,
            ) {
                SourceAppDropdownContent(sourceApps = sourceApps, selection = filterState.sourceAppSelection,
                    iconLoader = iconLoader, onToggleAll = onToggleAllSources, onToggleItem = onToggleSource)
            }
        },
    )
}

/** 时间值复用既有资源，独立于字段集合。 */
@Composable
internal fun SearchTimeFilter.labelText(): String = stringResource(when (this) {
    SearchTimeFilter.ALL -> BaseR.string.base_general_all_time
    SearchTimeFilter.TODAY -> BaseR.string.base_general_today
    SearchTimeFilter.LAST_7_DAYS -> BaseR.string.base_general_last_7_days
    SearchTimeFilter.LAST_30_DAYS -> BaseR.string.base_general_last_30_days
})
