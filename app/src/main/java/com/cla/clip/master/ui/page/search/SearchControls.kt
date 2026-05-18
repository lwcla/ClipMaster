package com.cla.clip.master.ui.page.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 搜索输入框的固定单行高度。
 *
 * `OutlinedTextField` 默认会根据输入内容和字体测量高度；搜索页顶部空间有限，这里固定为 Material 单行输入框常用高度，
 * 避免粘贴多行内容或异常测量时把下方结果列表挤小。
 */
private val SEARCH_BAR_FIELD_HEIGHT = 56.dp

/**
 * 搜索框和筛选区的 AppBarLayout 式折叠容器。
 *
 * 容器先在保持父级宽度约束的前提下放宽高度，测量完整内容；再用自身可见高度和裁剪表现折叠进度。
 * 这样搜索框和筛选区即使已经折叠到 0，也仍能保留完整高度基准，避免列表滚动反向污染顶部控件测量。
 */
@Composable
internal fun CollapsibleSearchControls(
    collapseOffsetPx: Float,
    expandedHeightPx: Int,
    onExpandedHeightChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds(),
        content = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 子节点按完整高度测量后再回写给父层折叠状态；这是顶部控件高度变化时的唯一事实来源。
                    .onSizeChanged { size ->
                        if (size.height > 0 && size.height != expandedHeightPx) {
                            onExpandedHeightChange(size.height)
                        }
                    }
            ) {
                content()
            }
        }
    ) { measurables, constraints ->
        val measurable = measurables.firstOrNull()
        val placeable = measurable?.measure(
            constraints.copy(
                minHeight = 0,
                // 只放宽高度，宽度仍沿用父级约束，避免搜索框在无界宽度下失去 fillMaxWidth 行为。
                maxHeight = Constraints.Infinity
            )
        )
        val contentHeightPx = placeable?.height ?: 0
        val collapseBaseHeightPx = if (expandedHeightPx > 0) expandedHeightPx else contentHeightPx
        val normalizedCollapsePx = if (collapseBaseHeightPx > 0) {
            collapseOffsetPx.coerceIn(0f, collapseBaseHeightPx.toFloat())
        } else {
            0f
        }
        val visibleHeightPx = (collapseBaseHeightPx - normalizedCollapsePx.roundToInt()).coerceAtLeast(0)
        val layoutWidth = (placeable?.width ?: constraints.minWidth).coerceIn(constraints.minWidth, constraints.maxWidth)
        val layoutHeight = visibleHeightPx.coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(layoutWidth, layoutHeight) {
            placeable?.placeRelative(x = 0, y = -normalizedCollapsePx.roundToInt())
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
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .heightIn(min = SEARCH_BAR_FIELD_HEIGHT, max = SEARCH_BAR_FIELD_HEIGHT)
            .onFocusChanged { onFocusChange(it.isFocused) },
        singleLine = true,
        maxLines = 1,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                onSubmit()
            }
        ),
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null
            )
        },
        trailingIcon = {
            if (query.isNotBlank()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(com.cla.clip.base.general.R.string.base_general_clear_search_keyword)
                    )
                }
            }
        },
        placeholder = {
            Text(stringResource(com.cla.clip.base.general.R.string.base_general_search_clip_hint))
        }
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 固定时间选项在窄屏上可能横向放不下，允许轻量横滑比压缩文字更可读。
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TimeFilterChip(SearchTimeFilter.ALL, filterState.timeFilter, onTimeFilterChange)
            TimeFilterChip(SearchTimeFilter.TODAY, filterState.timeFilter, onTimeFilterChange)
            TimeFilterChip(SearchTimeFilter.LAST_7_DAYS, filterState.timeFilter, onTimeFilterChange)
            TimeFilterChip(SearchTimeFilter.LAST_30_DAYS, filterState.timeFilter, onTimeFilterChange)
        }

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

/** 单个时间筛选 Chip。 */
@Composable
private fun TimeFilterChip(
    filter: SearchTimeFilter,
    selectedFilter: SearchTimeFilter,
    onClick: (SearchTimeFilter) -> Unit,
) {
    FilterChip(
        selected = filter == selectedFilter,
        onClick = { onClick(filter) },
        label = {
            Text(
                text = filter.labelText(),
                maxLines = 1
            )
        }
    )
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
