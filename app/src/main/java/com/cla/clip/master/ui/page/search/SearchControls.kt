package com.cla.clip.master.ui.page.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cla.clip.master.ui.widget.FilterChipOption
import com.cla.clip.master.ui.widget.HorizontalFilterChips
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
    tonalElevation: Dp = 2.dp,
    onQueryChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onTimeFilterChange: (SearchTimeFilter) -> Unit,
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
                onQueryChange = onQueryChange,
                onFocusChange = onFocusChange,
                onSubmit = onSubmit,
            )

            SearchFilters(
                filterState = filterState,
                selectedSourceAppNames = selectedSourceAppNames,
                onTimeFilterChange = onTimeFilterChange,
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
    )
}

/**
 * 搜索筛选区。
 *
 * 时间筛选使用 FilterChip 表达互斥选项，来源筛选单独用 AssistChip 打开弹窗，
 * 这样固定选项和动态 App 列表不会挤在同一个横向区域里。
 */
@Composable
internal fun SearchFilters(
    filterState: SearchFilterState,
    selectedSourceAppNames: List<String>,
    onTimeFilterChange: (SearchTimeFilter) -> Unit,
    onSourceClick: () -> Unit,
) {
    val selectedSourceAppLabel = when (selectedSourceAppNames.size) {
        0 -> stringResource(com.cla.clip.base.general.R.string.base_general_all_source_apps)
        1 -> selectedSourceAppNames.first()
        else -> stringResource(
            com.cla.clip.base.general.R.string.base_general_selected_source_app_count,
            selectedSourceAppNames.size
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        HorizontalFilterChips(
            options = SearchTimeFilter.entries.map { filter ->
                FilterChipOption(
                    value = filter,
                    label = filter.labelText()
                )
            },
            selectedValue = filterState.timeFilter,
            onSelected = onTimeFilterChange,
        )

        AssistChip(
            onClick = onSourceClick,
            label = {
                Text(
                    text = selectedSourceAppLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null
                )
            }
        )
    }
}

/** 时间筛选对应的本地化展示文案。 */
@Composable
private fun SearchTimeFilter.labelText(): String {
    return when (this) {
        SearchTimeFilter.ALL -> stringResource(com.cla.clip.base.general.R.string.base_general_all_time)
        SearchTimeFilter.TODAY -> stringResource(com.cla.clip.base.general.R.string.base_general_today)
        SearchTimeFilter.LAST_7_DAYS -> stringResource(com.cla.clip.base.general.R.string.base_general_last_7_days)
        SearchTimeFilter.LAST_30_DAYS -> stringResource(com.cla.clip.base.general.R.string.base_general_last_30_days)
    }
}
