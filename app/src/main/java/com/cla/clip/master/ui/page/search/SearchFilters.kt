package com.cla.clip.master.ui.page.search

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.dao.data.SourceAppCandidate
import com.cla.clip.base.general.search.ClipSearchTarget
import com.cla.clip.master.R
import com.cla.clip.master.ui.widget.AdaptiveThreeFilterRow
import com.cla.clip.master.ui.widget.FilterDropdown
import com.cla.clip.master.ui.widget.SingleChoiceDropdownContent
import com.cla.clip.master.ui.widget.SingleChoiceOption
import com.cla.clip.master.ui.widget.sourceapp.AppIconLoader
import com.cla.clip.master.ui.widget.sourceapp.EmptyAppIconLoader
import com.cla.clip.base.general.R as BaseR

/** 搜索页筛选薄适配：业务条件由页面持有，布局和菜单分别复用共享组件。 */
@Composable
internal fun SearchFilters(
    /** 唯一生效条件，不保存菜单草稿副本。 */ filterState: SearchFilterState,
    /** 已选来源名称只用于摘要。 */ selectedSourceAppNames: List<String>,
    /** 页面生命周期内收集的轻量候选。 */ sourceApps: List<SourceAppCandidate>,
    /** 可见菜单行的图标加载边界，Preview 默认占位。 */ iconLoader: AppIconLoader = EmptyAppIconLoader,
    /** 三种互斥浮层由页面管理。 */ expandedMenu: SearchFilterMenu,
    /** 关闭菜单不提交或回滚业务状态。 */ onMenuChange: (SearchFilterMenu) -> Unit,
    /** 新字段立即生效，重复项只关闭。 */ onTargetSelected: (ClipSearchTarget) -> Unit,
    /** 时间选择保留其他条件。 */ onTimeSelected: (SearchTimeFilter) -> Unit,
    /** 来源批量选择仍保留现有真实勾选语义。 */ onToggleAllSources: () -> Unit,
    /** 空字符串包名也是未知来源的合法身份。 */ onToggleSource: (String) -> Unit,
) {
    /** 四种字段名称由本页面资源化，不放进底层 enum。 */
    val targetLabel = filterState.target.labelText()
    /** 当前时间值与菜单共用本地化文案。 */
    val timeLabel = filterState.timeFilter.labelText()
    /** 全部来源既是默认摘要，也是来源列最小可读宽度依据。 */
    val allSourceLabel = stringResource(BaseR.string.base_general_all_source_apps)
    /** 多选仅显示数量，单个长名称继续由入口单行省略。 */
    val sourceLabel = when (selectedSourceAppNames.size) {
        0 -> allSourceLabel
        1 -> selectedSourceAppNames.first()
        else -> stringResource(BaseR.string.base_general_selected_source_app_count, selectedSourceAppNames.size)
    }
    /** 使用与共享入口相同的字号测量，动态字体变化自动重新计算换行条件。 */
    val textMeasurer = rememberTextMeasurer()
    /** 窗口密度负责把实测文字像素转为布局宽度。 */
    val density = LocalDensity.current
    /** 入口本身的文字样式，避免测量与绘制不一致。 */
    val labelStyle = MaterialTheme.typography.bodyMedium
    /** 24dp 左右内边距与 24dp 箭头为所有入口保留固定空间。 */
    val targetMinWidth = with(density) { textMeasurer.measure(targetLabel, labelStyle, maxLines = 1).size.width.toDp() } + 48.dp
    /** 时间标签完整可读，不用固定设备阈值替代字体测量。 */
    val timeMinWidth = with(density) { textMeasurer.measure(timeLabel, labelStyle, maxLines = 1).size.width.toDp() } + 48.dp
    /** 来源长名称可以省略，但“全部来源”应至少完整可读。 */
    val sourceMinWidth = with(density) { textMeasurer.measure(allSourceLabel, labelStyle, maxLines = 1).size.width.toDp() } + 48.dp

    AdaptiveThreeFilterRow(
        leadingMinWidth = targetMinWidth,
        middleMinWidth = timeMinWidth,
        trailingMinWidth = sourceMinWidth,
        modifier = Modifier.padding(horizontal = 12.dp),
        // 字段菜单与时间、来源使用同一 Popup 定位和焦点保护，最小菜单宽度避免长选项省略。
        leadingContent = { cellModifier ->
            FilterDropdown(
                value = targetLabel,
                accessibilityDescription = stringResource(R.string.host_search_filter_accessibility, stringResource(R.string.host_search_target), targetLabel),
                expanded = expandedMenu == SearchFilterMenu.Target,
                onExpandedChange = { onMenuChange(if (it) SearchFilterMenu.Target else SearchFilterMenu.None) },
                modifier = cellModifier,
                menuMinWidth = 220.dp,
            ) {
                SingleChoiceDropdownContent(
                    options = ClipSearchTarget.entries.map { SingleChoiceOption(it, it.labelText()) },
                    selectedValue = filterState.target,
                    onSelect = onTargetSelected,
                    onDismiss = { onMenuChange(SearchFilterMenu.None) },
                )
            }
        },
        // 时间即时单选保持原有行为，只有外部宽度由自适应骨架分配。
        middleContent = { cellModifier ->
            FilterDropdown(
                value = timeLabel,
                accessibilityDescription = stringResource(R.string.host_search_filter_accessibility, stringResource(BaseR.string.base_general_time), timeLabel),
                expanded = expandedMenu == SearchFilterMenu.Time,
                onExpandedChange = { onMenuChange(if (it) SearchFilterMenu.Time else SearchFilterMenu.None) },
                modifier = cellModifier,
            ) {
                SingleChoiceDropdownContent(
                    options = SearchTimeFilter.entries.map { SingleChoiceOption(it, it.labelText()) },
                    selectedValue = filterState.timeFilter,
                    onSelect = onTimeSelected,
                    onDismiss = { onMenuChange(SearchFilterMenu.None) },
                )
            }
        },
        // 来源菜单仍多选且即时生效；新字段维度不改变真实勾选状态。
        trailingContent = { cellModifier ->
            FilterDropdown(
                value = sourceLabel,
                accessibilityDescription = stringResource(R.string.host_search_filter_accessibility, stringResource(BaseR.string.base_general_source_app), sourceLabel),
                expanded = expandedMenu == SearchFilterMenu.Source,
                onExpandedChange = { onMenuChange(if (it) SearchFilterMenu.Source else SearchFilterMenu.None) },
                modifier = cellModifier,
            ) {
                SourceAppDropdownContent(
                    sourceApps = sourceApps,
                    selection = filterState.sourceAppSelection,
                    iconLoader = iconLoader,
                    onToggleAll = onToggleAllSources,
                    onToggleItem = onToggleSource,
                )
            }
        },
    )
}

/** 搜索字段仅在页面层本地化，底层查询不依赖 Android 字符串资源。 */
@Composable
internal fun ClipSearchTarget.labelText(): String = stringResource(when (this) {
    ClipSearchTarget.All -> R.string.host_search_target_all
    ClipSearchTarget.Content -> R.string.host_search_target_content
    ClipSearchTarget.LinkTitle -> R.string.host_search_target_title
    ClipSearchTarget.SourceName -> R.string.host_search_target_source
})

/** 时间值复用原有资源与业务语义，和新字段筛选独立。 */
@Composable
internal fun SearchTimeFilter.labelText(): String = stringResource(when (this) {
    SearchTimeFilter.ALL -> BaseR.string.base_general_all_time
    SearchTimeFilter.TODAY -> BaseR.string.base_general_today
    SearchTimeFilter.LAST_7_DAYS -> BaseR.string.base_general_last_7_days
    SearchTimeFilter.LAST_30_DAYS -> BaseR.string.base_general_last_30_days
})
