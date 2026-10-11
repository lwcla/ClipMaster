package com.cla.clip.master.ui.page.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cla.clip.master.ui.widget.SearchTopBar
import com.cla.clip.master.R
import com.cla.clip.base.general.dao.data.SourceAppCandidate
import com.cla.clip.master.ui.widget.sourceapp.AppIconLoader
import com.cla.clip.master.ui.widget.sourceapp.EmptyAppIconLoader
import com.cla.clip.master.ui.widget.FilterDropdown
import com.cla.clip.master.ui.widget.SingleChoiceDropdownContent
import com.cla.clip.master.ui.widget.SingleChoiceOption

/** 页面瞬时互斥菜单状态，不跨重建或离页保存。 */
internal enum class SearchFilterMenu {
    /** 无浮层。 */
    None,
    /** 时间单选浮层。 */
    Time,
    /** 来源即时多选浮层。 */
    Source,
}

/**
 * 无标题搜索页顶部搜索头。
 *
 * 该组件只负责展示搜索框和筛选条件，并按页面层传入的运行时偏移整体移动；
 * 折叠状态、滚动优先级和历史模式切换都留在 SearchPage 中统一编排。
 * 筛选按钮底部不额外留白，也不显示分割线或阴影，高度变化继续由页面实测。
 */
@Composable
internal fun SearchCollapsibleHeader(
    /** 页面唯一关键词，展示层不再规整输入。 */
    query: String,
    /** 当前时间与来源筛选，保持页面查询边界。 */
    filterState: SearchFilterState,
    /** 页面映射的来源名称，只用于筛选入口摘要。 */
    selectedSourceAppNames: List<String>,
    /** 生命周期内收集的来源候选，不由搜索头加载。 */
    sourceApps: List<SourceAppCandidate>,
    /** 页面注入图标加载边界，纯预览默认占位。 */
    iconLoader: AppIconLoader = EmptyAppIconLoader,
    /** 页面统一控制的互斥菜单，不在组件内保存。 */
    expandedMenu: SearchFilterMenu,
    /** 滚动驱动的像素位移，真实高度仍由页面测量。 */
    offsetPx: Float,
    /** 搜索头整体布局及尺寸测量接口。 */
    modifier: Modifier = Modifier,
    /** 仅作用于输入节点的焦点、触摸与读屏意图修饰。 */
    searchFieldModifier: Modifier = Modifier,
    /** 真实编辑事件交给页面维护历史及查询。 */
    onQueryChange: (String) -> Unit,
    /** 真实输入焦点变化由页面保护菜单被动恢复。 */
    onFocusChange: (Boolean) -> Unit,
    /** 顶部返回交给页面按局部交互优先级处理。 */
    onBack: () -> Unit,
    /** 清空只发出事件，焦点及筛选保留由页面负责。 */
    onClear: () -> Unit,
    /** IME 搜索交给页面提交并切换结果。 */
    onSubmit: () -> Unit,
    /** 页面负责菜单互斥、广告保护与生命周期关闭。 */
    onMenuChange: (SearchFilterMenu) -> Unit,
    /** 时间选中后沿用页面即时查询。 */
    onTimeSelected: (SearchTimeFilter) -> Unit,
    /** 来源全选事件只交给既有选择规则。 */
    onToggleAllSources: () -> Unit,
    /** 稳定包名标识具体来源，包括未知来源空包名。 */
    onToggleSource: (String) -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                /** 搜索头运行时位移由页面滚动状态驱动，单位为像素，避免 Dp 换算造成跟手误差。 */
                translationY = offsetPx
            },
        color = MaterialTheme.colorScheme.surface,
        // 搜索栏自身使用原始 surface；外层也禁用色调叠加，避免筛选区出现另一块底色。
        tonalElevation = 0.dp,
        // 筛选按钮下方不再显示投影形成的横线，滚动时也保持同一底部样式。
        shadowElevation = 0.dp,
    ) {
        Column(
            // SearchTopBar 唯一处理状态栏；筛选按钮底部不加空白或分隔效果。
            modifier = Modifier.fillMaxWidth()
        ) {
            SearchBar(
                query = query,
                inputModifier = searchFieldModifier,
                onQueryChange = onQueryChange,
                onFocusChange = onFocusChange,
                onBack = onBack,
                onClear = onClear,
                onSubmit = onSubmit,
            )

            SearchFilters(
                filterState = filterState,
                selectedSourceAppNames = selectedSourceAppNames,
                sourceApps = sourceApps,
                iconLoader = iconLoader,
                expandedMenu = expandedMenu,
                onMenuChange = onMenuChange,
                onTimeSelected = onTimeSelected,
                onToggleAllSources = onToggleAllSources,
                onToggleSource = onToggleSource,
            )
        }
    }
}

/**
 * 搜索输入框。
 *
 * 复用来源过滤页无边框搜索栏；输入规整在 ViewModel 层完成，焦点和业务事件只向页面回传。
 */
@Composable
internal fun SearchBar(
    /** 当前页面关键词，不由组件保存副本。 */
    query: String,
    /** 搜索行的整体布局接口，不承载输入焦点。 */
    modifier: Modifier = Modifier,
    /** 实际输入节点的焦点请求及用户意图监听。 */
    inputModifier: Modifier = Modifier,
    /** 关键词编辑交给页面更新唯一状态。 */
    onQueryChange: (String) -> Unit,
    /** 输入真实焦点变化，避免返回按钮获得焦点触发历史。 */
    onFocusChange: (Boolean) -> Unit,
    /** 页面负责返回优先级和宿主导航。 */
    onBack: () -> Unit,
    /** 页面负责清词、恢复编辑焦点及展示历史。 */
    onClear: () -> Unit,
    /** 页面负责提交查询并切换结果模式。 */
    onSubmit: () -> Unit,
) {
    SearchTopBar(
        query = query,
        onQueryChange = onQueryChange,
        onBack = onBack,
        onClear = onClear,
        onSubmit = onSubmit,
        placeholder = stringResource(com.cla.clip.base.general.R.string.base_general_search_clip_hint),
        backContentDescription = stringResource(com.cla.clip.base.general.R.string.base_general_back),
        clearContentDescription = stringResource(com.cla.clip.base.general.R.string.base_general_clear_search_keyword),
        modifier = modifier,
        // 监听与 FocusRequester 都挂实际输入节点，菜单关闭后的焦点继续由页面 guard 判断。
        inputModifier = inputModifier.onFocusChanged { onFocusChange(it.isFocused) },
    )
}

/**
 * 搜索筛选区。
 *
 * 时间与来源按 2:3 分配宽度，为应用名称保留更多空间；选择与菜单状态由页面持有。
 */
@Composable
internal fun SearchFilters(
    /** 页面当前时间与来源条件，本组件不创建选择副本。 */
    filterState: SearchFilterState,
    /** 已选来源名称用于入口摘要，多选只显示数量。 */
    selectedSourceAppNames: List<String>,
    /** 页面生命周期内收集的来源候选，仅在菜单展示。 */
    sourceApps: List<SourceAppCandidate>,
    /** 页面注入图标加载边界，纯预览默认占位。 */
    iconLoader: AppIconLoader = EmptyAppIconLoader,
    /** 页面统一管理互斥菜单及关闭边界。 */
    expandedMenu: SearchFilterMenu,
    /** 只切换浮层，保持当前查询条件。 */
    onMenuChange: (SearchFilterMenu) -> Unit,
    /** 时间选中事件沿用即时查询契约。 */
    onTimeSelected: (SearchTimeFilter) -> Unit,
    /** 来源批量选择由既有页面规则处理。 */
    onToggleAllSources: () -> Unit,
    /** 具体来源稳定包名原样交给页面，不按显示名称选择。 */
    onToggleSource: (String) -> Unit,
) {
    /** 当前时间值与下拉选项共用文案；入口只展示当前值，不显示维度标题。 */
    val selectedTimeLabel = filterState.timeFilter.labelText()

    /** 当前来源筛选的展示值，多选时只显示数量，长单个名称由入口单行省略。 */
    val selectedSourceAppLabel = when (selectedSourceAppNames.size) {
        0 -> stringResource(com.cla.clip.base.general.R.string.base_general_all_source_apps)
        1 -> selectedSourceAppNames.first()
        else -> stringResource(
            com.cla.clip.base.general.R.string.base_general_selected_source_app_count,
            selectedSourceAppNames.size
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterDropdown(
            value = selectedTimeLabel,
            accessibilityDescription = stringResource(R.string.host_search_filter_accessibility,
                stringResource(com.cla.clip.base.general.R.string.base_general_time), selectedTimeLabel),
            expanded = expandedMenu == SearchFilterMenu.Time,
            // 时间入口与来源互斥，关闭只改变浮层状态，不提交筛选。
            onExpandedChange = { onMenuChange(if (it) SearchFilterMenu.Time else SearchFilterMenu.None) },
            // 时间取 2 份宽度，来源取 3 份；固定间距不参与比例。
            modifier = Modifier.weight(2f),
        ) {
            SingleChoiceDropdownContent(
                options = SearchTimeFilter.entries.map { SingleChoiceOption(it, it.labelText()) },
                selectedValue = filterState.timeFilter,
                onSelect = onTimeSelected,
                // 重复点击当前时间只关闭菜单，保持至少一项选中。
                onDismiss = { onMenuChange(SearchFilterMenu.None) },
            )
        }

        FilterDropdown(
            value = selectedSourceAppLabel,
            accessibilityDescription = stringResource(R.string.host_search_filter_accessibility,
                stringResource(com.cla.clip.base.general.R.string.base_general_source_app), selectedSourceAppLabel),
            expanded = expandedMenu == SearchFilterMenu.Source,
            // 来源菜单开关不回滚即时选择，统一由页面处理生命周期和广告保护。
            onExpandedChange = { onMenuChange(if (it) SearchFilterMenu.Source else SearchFilterMenu.None) },
            modifier = Modifier.weight(3f),
        ) {
            SourceAppDropdownContent(
                sourceApps = sourceApps,
                iconLoader = iconLoader,
                selection = filterState.sourceAppSelection,
                onToggleAll = onToggleAllSources,
                onToggleItem = onToggleSource,
            )
        }
    }
}

/** 时间筛选对应的本地化展示文案。 */
@Composable
internal fun SearchTimeFilter.labelText(): String {
    return when (this) {
        SearchTimeFilter.ALL -> stringResource(com.cla.clip.base.general.R.string.base_general_all_time)
        SearchTimeFilter.TODAY -> stringResource(com.cla.clip.base.general.R.string.base_general_today)
        SearchTimeFilter.LAST_7_DAYS -> stringResource(com.cla.clip.base.general.R.string.base_general_last_7_days)
        SearchTimeFilter.LAST_30_DAYS -> stringResource(com.cla.clip.base.general.R.string.base_general_last_30_days)
    }
}
