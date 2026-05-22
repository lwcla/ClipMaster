package com.cla.clip.master.ui.page.magnet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.cla.clip.base.general.R
import com.cla.clip.base.general.magnet.cache.MagnetSearchResult
import com.cla.clip.base.general.magnet.cache.MagnetSourceCacheState
import com.cla.clip.base.general.magnet.cache.MagnetSourceSearchRepository
import com.cla.clip.base.general.magnet.cache.MagnetSourceStatusReason
import com.cla.clip.base.general.magnet.cache.MagnetSourceSyncPhase
import com.cla.clip.base.general.magnet.cache.MagnetSourceSyncProgress
import com.cla.clip.master.ui.widget.ClipMasterCard
import com.cla.clip.master.ui.widget.PagingEmptyContent
import com.cla.clip.master.ui.widget.PagingErrorContent
import com.cla.clip.master.ui.widget.PagingLoadingContent
import com.cla.clip.master.ui.widget.SearchInputField
import com.cla.clip.master.ui.widget.TitleBar
import com.cla.clip.master.ui.widget.pagingAppendStateItem

/** 磁力搜索页入口，按需同步 Academic Torrents 索引并在本地缓存中搜索 magnet 结果。 */
@Composable
fun MagnetSearchPage(
    initialQuery: String,
    onBack: () -> Unit,
    viewModel: MagnetSearchVm = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val histories by viewModel.histories.collectAsStateWithLifecycle()
    val syncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()
    val pagedResults = viewModel.pagedResults.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    var searchBarFocused by remember { mutableStateOf(false) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    val showHistoryPanel = searchBarFocused && histories.isNotEmpty()
    val syncing = syncProgress.phase.isRunning

    LaunchedEffect(initialQuery) {
        viewModel.applyInitialQuery(initialQuery)
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    BackHandler(enabled = showHistoryPanel) {
        searchBarFocused = false
        focusManager.clearFocus()
    }

    Scaffold(
        topBar = {
            TitleBar(
                title = stringResource(R.string.base_general_magnet_search),
                onBack = onBack
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            SearchInputField(
                query = state.query,
                onQueryChange = viewModel::updateQuery,
                onFocusChange = { searchBarFocused = it },
                onSubmit = {
                    viewModel.submitCurrentQuery()
                    searchBarFocused = false
                    focusManager.clearFocus()
                },
                placeholder = stringResource(R.string.base_general_search_magnet_hint),
                clearContentDescription = stringResource(R.string.base_general_clear_search_keyword),
            )

            MagnetSourceStatusCard(
                state = state,
                progress = syncProgress,
                syncing = syncing,
                onSync = { viewModel.syncSource(force = false) },
                onForceSync = { viewModel.syncSource(force = true) },
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (showHistoryPanel) {
                    MagnetHistoryPanel(
                        histories = histories,
                        query = state.query,
                        onHistoryClick = { history ->
                            viewModel.selectHistory(history)
                            searchBarFocused = false
                            focusManager.clearFocus()
                        },
                        onDeleteHistory = viewModel::deleteHistory,
                        onClearHistories = { showClearHistoryConfirm = true }
                    )
                } else {
                    MagnetResultList(
                        query = state.query,
                        cacheReady = state.cacheState.isSearchable,
                        pagedResults = pagedResults,
                        listState = listState,
                        onCopyOnly = viewModel::copyOnly,
                        onCopyAndOpen = viewModel::copyAndOpen
                    )
                }
            }
        }
    }

    if (showClearHistoryConfirm) {
        ClearMagnetHistoryDialog(
            onDismiss = { showClearHistoryConfirm = false },
            onConfirm = {
                viewModel.clearHistories()
                showClearHistoryConfirm = false
            }
        )
    }
}

@Composable
private fun MagnetSourceStatusCard(
    state: MagnetSearchUiState,
    progress: MagnetSourceSyncProgress,
    syncing: Boolean,
    onSync: () -> Unit,
    onForceSync: () -> Unit,
) {
    ClipMasterCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentPadding = PaddingValues(12.dp)
    ) { _ ->
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.cacheState.statusText(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.base_general_magnet_privacy_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = onSync,
                    enabled = !syncing
                ) {
                    Text(stringResource(R.string.base_general_magnet_sync_source))
                }
            }

            if (syncing) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(
                    text = progress.progressText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (state.cacheState.isSearchable) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onForceSync) {
                    Text(stringResource(R.string.base_general_magnet_force_sync_source))
                }
            }
        }
    }
}

@Composable
private fun MagnetResultList(
    query: String,
    cacheReady: Boolean,
    pagedResults: androidx.paging.compose.LazyPagingItems<MagnetSearchResult>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onCopyOnly: (MagnetSearchResult) -> Unit,
    onCopyAndOpen: (MagnetSearchResult) -> Unit,
) {
    val retryText = stringResource(R.string.base_general_data_load_failed_retry)
    when {
        !cacheReady -> PagingEmptyContent(
            text = stringResource(R.string.base_general_magnet_source_need_sync),
            icon = Icons.Default.CloudSync
        )
        query.length < MagnetSourceSearchRepository.MIN_SEARCH_QUERY_LENGTH -> PagingEmptyContent(
            text = stringResource(R.string.base_general_magnet_query_too_short),
            icon = Icons.Default.Search
        )
        pagedResults.loadState.refresh is LoadState.Loading && pagedResults.itemCount == 0 -> PagingLoadingContent()
        pagedResults.loadState.refresh is LoadState.Error && pagedResults.itemCount == 0 -> PagingErrorContent(
            text = retryText,
            onRetry = pagedResults::retry
        )
        pagedResults.loadState.refresh is LoadState.NotLoading && pagedResults.itemCount == 0 -> PagingEmptyContent(
            text = stringResource(R.string.base_general_magnet_result_empty),
            icon = Icons.Default.Search
        )
        else -> {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    count = pagedResults.itemCount,
                    key = pagedResults.itemKey { "${it.sourceId}:${it.infoHash}" }
                ) { index ->
                    pagedResults[index]?.let { item ->
                        MagnetResultCard(
                            item = item,
                            query = query,
                            onCopyOnly = { onCopyOnly(item) },
                            onCopyAndOpen = { onCopyAndOpen(item) }
                        )
                    }
                }
                pagingAppendStateItem(
                    loadState = pagedResults.loadState.append,
                    retryText = retryText,
                    onRetry = pagedResults::retry
                )
            }
        }
    }
}

@Composable
private fun MagnetSourceSyncProgress.progressText(): String {
    return when (phase) {
        MagnetSourceSyncPhase.Checking -> stringResource(R.string.base_general_magnet_sync_checking)
        MagnetSourceSyncPhase.Downloading -> stringResource(R.string.base_general_magnet_sync_downloading)
        MagnetSourceSyncPhase.Parsing -> stringResource(R.string.base_general_magnet_sync_parsing, parsedCount)
        MagnetSourceSyncPhase.Importing -> stringResource(R.string.base_general_magnet_sync_importing, importedCount)
        MagnetSourceSyncPhase.Completed -> stringResource(R.string.base_general_magnet_sync_completed)
        MagnetSourceSyncPhase.Failed -> stringResource(R.string.base_general_magnet_sync_failed)
        MagnetSourceSyncPhase.Cancelled -> stringResource(R.string.base_general_magnet_sync_cancelled)
        MagnetSourceSyncPhase.Idle -> stringResource(R.string.base_general_magnet_sync_idle)
    }
}

private val MagnetSourceSyncPhase.isRunning: Boolean
    get() = this == MagnetSourceSyncPhase.Checking ||
            this == MagnetSourceSyncPhase.Downloading ||
            this == MagnetSourceSyncPhase.Parsing ||
            this == MagnetSourceSyncPhase.Importing

private val MagnetSourceCacheState.isSearchable: Boolean
    get() = itemCount > 0 &&
            (reason == MagnetSourceStatusReason.Ready ||
                    reason == MagnetSourceStatusReason.NotModified ||
                    reason == MagnetSourceStatusReason.Cooldown ||
                    reason == MagnetSourceStatusReason.NetworkFailed ||
                    reason == MagnetSourceStatusReason.NetworkTimeout ||
                    reason == MagnetSourceStatusReason.ParseFailed)
