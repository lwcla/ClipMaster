package com.cla.clip.feature.torrent.search.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cla.clip.feature.torrent.search.R
import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.SortCriteria
import com.cla.clip.feature.torrent.search.model.contract.SortOrder
import com.cla.clip.feature.torrent.search.ui.component.TorrentResultCard
import com.cla.clip.feature.torrent.search.ui.component.toTorrentResultCardState

/** 搜索主页面纯渲染入口；所有业务状态和副作用由调用方提升。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TorrentSearchScreen(
    /** 当前不可变页面状态。 */
    state: TorrentSearchUiState,
    /** 用户修改查询文本。 */
    onQueryChange: (String) -> Unit,
    /** 用户选择分类。 */
    onCategoryChange: (Category) -> Unit,
    /** 用户切换排序。 */
    onSortChange: (SortCriteria, SortOrder) -> Unit,
    /** 用户显式提交搜索。 */
    onSearch: () -> Unit,
    /** 用户显式停止搜索。 */
    onStop: () -> Unit,
    /** 用户请求安全模式目标值。 */
    onSafeModeChange: (Boolean) -> Unit,
    /** 用户确认首次多源隐私披露。 */
    onConfirmDisclosure: () -> Unit,
    /** 用户取消首次多源隐私披露。 */
    onDismissDisclosure: () -> Unit,
    /** 用户确认关闭安全模式。 */
    onConfirmDisableSafeMode: () -> Unit,
    /** 用户取消关闭安全模式。 */
    onDismissDisableSafeMode: () -> Unit,
    /** 用户退出独立 Activity。 */
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.torrent_search_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.torrent_search_action_back))
                    }
                },
            )
        },
    ) { /** contentPadding 是 Scaffold 为状态栏和工具栏提供的安全内边距。 */ contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp),
        ) {
            SearchControls(
                state = state,
                onQueryChange = onQueryChange,
                onCategoryChange = onCategoryChange,
                onSortChange = onSortChange,
                onSearch = onSearch,
                onStop = onStop,
                onSafeModeChange = onSafeModeChange,
            )
            SearchResults(state = state, modifier = Modifier.weight(1f))
        }
    }
    if (state.showMultiSourceDisclosure) {
        AlertDialog(
            onDismissRequest = onDismissDisclosure,
            title = { Text(stringResource(R.string.torrent_search_multi_source_disclosure_title)) },
            text = {
                Text(stringResource(R.string.torrent_search_multi_source_disclosure_message, state.pendingDisclosureProviderCount))
            },
            confirmButton = {
                TextButton(onClick = onConfirmDisclosure) {
                    Text(stringResource(R.string.torrent_search_multi_source_disclosure_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDisclosure) {
                    Text(stringResource(R.string.torrent_search_button_cancel))
                }
            },
        )
    }
    if (state.showDisableSafeModeConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissDisableSafeMode,
            title = { Text(stringResource(R.string.torrent_search_disable_safe_mode_title)) },
            text = { Text(stringResource(R.string.torrent_search_disable_safe_mode_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmDisableSafeMode) {
                    Text(stringResource(R.string.torrent_search_disable_safe_mode_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDisableSafeMode) {
                    Text(stringResource(R.string.torrent_search_button_cancel))
                }
            },
        )
    }
}

/** 搜索输入、分类、排序和安全模式控制区。 */
@Composable
private fun SearchControls(
    /** 当前页面状态。 */
    state: TorrentSearchUiState,
    /** 查询文本变化回调。 */
    onQueryChange: (String) -> Unit,
    /** 分类变化回调。 */
    onCategoryChange: (Category) -> Unit,
    /** 排序变化回调。 */
    onSortChange: (SortCriteria, SortOrder) -> Unit,
    /** 搜索提交回调。 */
    onSearch: () -> Unit,
    /** 搜索停止回调。 */
    onStop: () -> Unit,
    /** 安全模式变化回调。 */
    onSafeModeChange: (Boolean) -> Unit,
) {
    /** visibleCategories 在安全模式下移除成人分类，防止 UI 暴露无效选择。 */
    val visibleCategories = Category.entries.filter { /** category 是待检查安全可见性的稳定分类。 */ category ->
        !state.safeModeEnabled || !category.isNsfw
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.torrent_search_search_filter_query_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.isSearching) {
                OutlinedButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Text(stringResource(R.string.torrent_search_search_action_stop_search))
                }
            } else {
                Button(onClick = onSearch, enabled = state.isScreenActive && state.settingsReady) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Text(stringResource(R.string.torrent_search_home_button_search))
                }
            }
            Text(
                text = stringResource(R.string.torrent_search_enabled_provider_count, state.enabledProviderCount),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.torrent_search_safe_mode), style = MaterialTheme.typography.labelMedium)
            Switch(checked = state.safeModeEnabled, onCheckedChange = onSafeModeChange)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visibleCategories, key = { /** category 是分类 chip 的稳定枚举身份。 */ category -> category.code }) { /** category 是当前分类 chip。 */ category ->
                FilterChip(
                    selected = state.category == category,
                    onClick = { onCategoryChange(category) },
                    label = { Text(categoryLabel(category)) },
                )
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SortCriteria.entries, key = { /** criteria 是排序 chip 的稳定 code。 */ criteria -> criteria.code }) { /** criteria 是当前排序 chip。 */ criteria ->
                FilterChip(
                    selected = state.sortCriteria == criteria,
                    onClick = { onSortChange(criteria, state.sortOrder) },
                    label = { Text(sortCriteriaLabel(criteria)) },
                )
            }
            item {
                FilterChip(
                    selected = true,
                    onClick = {
                        /** nextOrder 在升序和降序之间切换。 */
                        val nextOrder = if (state.sortOrder == SortOrder.ASCENDING) SortOrder.DESCENDING else SortOrder.ASCENDING
                        onSortChange(state.sortCriteria, nextOrder)
                    },
                    label = { Text(sortOrderLabel(state.sortOrder)) },
                )
            }
        }
        state.error?.let { /** error 是当前待资源化的本地错误分类。 */ error ->
            Text(
                text = errorLabel(error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 渲染搜索进度、低敏汇总和稳定 key 结果列表。 */
@Composable
private fun SearchResults(
    /** 当前页面状态。 */
    state: TorrentSearchUiState,
    /** 结果区域外部布局约束。 */
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isSearching) CircularProgressIndicator()
                Text(stringResource(R.string.torrent_search_results_count, state.results.size))
            }
            if (state.providerFailures.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.torrent_search_provider_failures_count, state.providerFailures.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.phase == TorrentSearchPhase.IDLE && state.error == null) {
            item {
                Text(
                    text = stringResource(R.string.torrent_search_idle_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.phase == TorrentSearchPhase.COMPLETED && state.results.isEmpty() && state.error == null) {
            item {
                Text(
                    text = stringResource(R.string.torrent_search_empty_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.phase == TorrentSearchPhase.STOPPED && state.error == null) {
            item {
                Text(
                    text = stringResource(R.string.torrent_search_stopped),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(
            items = state.results,
            key = { /** index 只满足签名且不参与身份，torrent 的 infoHash 是稳定 key。 */ _, torrent -> torrent.infoHash },
        ) { /** index 仅用于 Compose itemsIndexed 签名，torrent 是当前稳定结果。 */ _, torrent ->
            TorrentResultCard(state = torrent.toTorrentResultCardState())
        }
    }
}

/** 将分类稳定枚举映射为资源文案。 */
@Composable
private fun categoryLabel(category: Category): String = stringResource(
    when (category) {
        Category.ALL -> R.string.torrent_search_category_all
        Category.ANIME -> R.string.torrent_search_category_anime
        Category.APPS -> R.string.torrent_search_category_apps
        Category.BOOKS -> R.string.torrent_search_category_books
        Category.GAMES -> R.string.torrent_search_category_games
        Category.MOVIES -> R.string.torrent_search_category_movies
        Category.MUSIC -> R.string.torrent_search_category_music
        Category.PORN -> R.string.torrent_search_category_porn
        Category.SERIES -> R.string.torrent_search_category_series
        Category.OTHER -> R.string.torrent_search_category_other
    },
)

/** 将排序标准映射为既有资源文案。 */
@Composable
private fun sortCriteriaLabel(criteria: SortCriteria): String = stringResource(
    when (criteria) {
        SortCriteria.NAME -> R.string.torrent_search_action_sort_criteria_name
        SortCriteria.SEEDERS -> R.string.torrent_search_action_sort_criteria_seeders
        SortCriteria.PEERS -> R.string.torrent_search_action_sort_criteria_peers
        SortCriteria.FILE_SIZE -> R.string.torrent_search_action_sort_criteria_file_size
        SortCriteria.DATE -> R.string.torrent_search_action_sort_criteria_date
    },
)

/** 将排序方向映射为资源文案。 */
@Composable
private fun sortOrderLabel(order: SortOrder): String = stringResource(
    if (order == SortOrder.ASCENDING) R.string.torrent_search_sort_ascending else R.string.torrent_search_sort_descending,
)

/** 将本地错误分类映射为不含外部文本的资源文案。 */
@Composable
private fun errorLabel(error: TorrentSearchUiError): String = stringResource(
    when (error) {
        TorrentSearchUiError.SETTINGS_LOADING -> R.string.torrent_search_error_settings_loading
        TorrentSearchUiError.SCREEN_NOT_ACTIVE -> R.string.torrent_search_error_screen_inactive
        TorrentSearchUiError.EMPTY_QUERY -> R.string.torrent_search_error_empty_query
        TorrentSearchUiError.QUERY_TOO_LONG -> R.string.torrent_search_error_query_too_long
        TorrentSearchUiError.NO_ENABLED_PROVIDERS -> R.string.torrent_search_error_no_providers
        TorrentSearchUiError.SETTINGS_WRITE_FAILED -> R.string.torrent_search_error_settings_write_failed
    },
)
