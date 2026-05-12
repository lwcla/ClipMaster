package com.cla.clip.master.ui.page.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.cla.clip.base.general.dao.SourceAppData
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.master.ui.dialog.DeleteDialog
import com.cla.clip.master.ui.navigation.DetailRoute
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.page.list.ClipResultList
import com.cla.clip.master.ui.widget.TitleBar

/**
 * 剪贴搜索页。
 *
 * 页面负责搜索输入、筛选条件选择和结果展示；查询组合、分页和剪贴操作都交给 SearchViewModel，
 * 让 UI 层保持轻量，后续增加自定义日期或搜索历史时也能继续沿用这条状态流。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchPage(
    viewModel: SearchViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onNavigate: (Route) -> Unit,
) {
    val filterState by viewModel.filterState.collectAsStateWithLifecycle()
    val sourceApps by viewModel.sourceApps.collectAsStateWithLifecycle()
    val selectedSourceAppName by viewModel.selectedSourceAppName.collectAsStateWithLifecycle()
    val pagedClips = viewModel.pagedClips.collectAsLazyPagingItems()
    val gridState = rememberLazyStaggeredGridState()
    var deleteClip by remember { mutableStateOf<ClipShowEntity?>(null) }
    var showSourcePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TitleBar(
                title = stringResource(com.cla.clip.base.general.R.string.base_general_search),
                onBack = onBack
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            SearchBar(
                query = filterState.query,
                onQueryChange = viewModel::updateQuery
            )

            SearchFilters(
                filterState = filterState,
                selectedSourceAppName = selectedSourceAppName,
                onTimeFilterChange = viewModel::updateTimeFilter,
                onSourceClick = { showSourcePicker = true }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 结果区占用搜索框和筛选器下方的剩余高度，避免瀑布流在 Column 中抢占顶部控件空间。
                    .weight(1f)
            ) {
                ClipResultList(
                    gridState = gridState,
                    pagedClips = pagedClips,
                    emptyText = stringResource(com.cla.clip.base.general.R.string.base_general_search_result_empty),
                    highlightQuery = filterState.query,
                    contentPadding = PaddingValues(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 24.dp),
                    onPinToggle = { viewModel.updatePinStatus(it, !it.isPinned) },
                    onDelete = { deleteClip = it },
                    onCopy = viewModel::copyToClipboard,
                    onClick = { onNavigate(DetailRoute(it.id)) },
                    onLongClick = {}
                )
            }
        }
    }

    DeleteDialog(
        clip = deleteClip,
        onDismiss = { deleteClip = null },
        onConfirmDelete = viewModel::deleteClip
    )

    if (showSourcePicker) {
        SourceAppPickerSheet(
            sourceApps = sourceApps,
            selectedPackageName = filterState.sourceAppPackage,
            onDismiss = { showSourcePicker = false },
            onSelect = { packageName ->
                viewModel.updateSourceApp(packageName)
                showSourcePicker = false
            }
        )
    }
}

/** 搜索输入框。 */
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        singleLine = true,
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
private fun SearchFilters(
    filterState: SearchFilterState,
    selectedSourceAppName: String?,
    onTimeFilterChange: (SearchTimeFilter) -> Unit,
    onSourceClick: () -> Unit,
) {
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
                    text = selectedSourceAppName ?: stringResource(com.cla.clip.base.general.R.string.base_general_all_source_apps),
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

/**
 * 来源 App 选择弹窗。
 *
 * 弹窗内始终提供“全部来源”，即使数据库里暂时没有来源 App，也能让用户清除已有筛选。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceAppPickerSheet(
    sourceApps: List<SourceAppData>,
    selectedPackageName: String?,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringResource(com.cla.clip.base.general.R.string.base_general_source_app),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )

            LazyColumn {
                item {
                    SourceAppRow(
                        title = stringResource(com.cla.clip.base.general.R.string.base_general_all_source_apps),
                        selected = selectedPackageName == null,
                        onClick = { onSelect(null) }
                    )
                }
                items(
                    items = sourceApps,
                    key = { it.packageName }
                ) { sourceApp ->
                    SourceAppRow(
                        title = sourceApp.appName.takeIf { it.isNotBlank() } ?: sourceApp.packageName,
                        subtitle = sourceApp.packageName,
                        selected = selectedPackageName == sourceApp.packageName,
                        onClick = { onSelect(sourceApp.packageName) }
                    )
                }
            }
        }
    }
}

/** 来源 App 弹窗中的单行选项。 */
@Composable
private fun SourceAppRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (selected) {
            Text(
                text = stringResource(com.cla.clip.base.general.R.string.base_general_selected),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
