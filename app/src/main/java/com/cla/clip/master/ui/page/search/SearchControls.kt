package com.cla.clip.master.ui.page.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cla.clip.master.ui.widget.SearchInputField

/**
 * 无标题搜索页顶部搜索头。
 *
 * 该组件只负责展示搜索框和筛选条件，并按页面层传入的运行时偏移整体移动；
 * 折叠状态、滚动优先级和历史模式切换都留在 SearchPage 中统一编排。
 */
@Composable
internal fun SearchCollapsibleHeader(
    query: String,
    filterState: SearchFilterState,
    selectedSourceAppNames: List<String>,
    offsetPx: Float,
    modifier: Modifier = Modifier,
    searchFieldModifier: Modifier = Modifier,
    tonalElevation: Dp = 2.dp,
    onQueryChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onTimeClick: () -> Unit,
    onSourceClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                /** 搜索头运行时位移由页面滚动状态驱动，单位为像素，避免 Dp 换算造成跟手误差。 */
                translationY = offsetPx
            },
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = tonalElevation,
        shadowElevation = tonalElevation,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(bottom = 8.dp)
        ) {
            SearchBar(
                query = query,
                modifier = searchFieldModifier,
                onQueryChange = onQueryChange,
                onFocusChange = onFocusChange,
                onSubmit = onSubmit,
            )

            SearchFilters(
                filterState = filterState,
                selectedSourceAppNames = selectedSourceAppNames,
                onTimeClick = onTimeClick,
                onSourceClick = onSourceClick,
            )
        }
    }
}

/**
 * 搜索输入框。
 *
 * 只承载单行关键词输入；输入规整在 ViewModel 层完成，这里通过固定高度和单行参数保证顶部控件高度稳定。
 */
@Composable
internal fun SearchBar(
    query: String,
    modifier: Modifier = Modifier,
    onQueryChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
) {
    SearchInputField(
        query = query,
        onQueryChange = onQueryChange,
        onFocusChange = onFocusChange,
        onSubmit = onSubmit,
        placeholder = stringResource(com.cla.clip.base.general.R.string.base_general_search_clip_hint),
        clearContentDescription = stringResource(com.cla.clip.base.general.R.string.base_general_clear_search_keyword),
        modifier = modifier,
    )
}

/**
 * 搜索筛选区。
 *
 * 时间和来源各占一半宽度，避免多来源只显示数量后筛选区左右视觉重量不一致。
 */
@Composable
internal fun SearchFilters(
    filterState: SearchFilterState,
    selectedSourceAppNames: List<String>,
    onTimeClick: () -> Unit,
    onSourceClick: () -> Unit,
) {
    /** 当前时间筛选的展示值，和单选弹窗选项共用同一份本地化文案。 */
    val selectedTimeLabel = filterState.timeFilter.labelText()

    /** 当前来源筛选的展示值，多选时只显示数量，避免长 App 名撑破半宽选择器。 */
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
        SearchFilterSelector(
            title = stringResource(com.cla.clip.base.general.R.string.base_general_time),
            value = selectedTimeLabel,
            onClick = onTimeClick,
            modifier = Modifier.weight(1f),
        )

        SearchFilterSelector(
            title = stringResource(com.cla.clip.base.general.R.string.base_general_source_app),
            value = selectedSourceAppLabel,
            onClick = onSourceClick,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 单行筛选选择器。
 *
 * 左侧显示筛选维度，右侧显示当前取值；取值区域单行省略，避免半屏宽度下把控件撑成两行。
 */
@Composable
private fun SearchFilterSelector(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            /** 标题和值之间的固定间隔，保证单行结构在窄屏下仍能读出维度和值的边界。 */
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
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
