package com.cla.clip.feature.magnet

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.map
import com.cla.clip.base.general.utils.toRelativeTimeSpanString
import com.cla.clip.feature.magnet.api.MagnetDownloadHistoryCallbacks
import com.cla.clip.feature.magnet.api.MagnetDownloadHistoryEntry
import com.cla.clip.feature.magnet.api.MagnetDownloadSelectionState
import com.cla.clip.feature.magnet.data.MagnetDownloadRecordData
import com.cla.clip.feature.magnet.data.MagnetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val MAGNET_HISTORY_PAGE_SIZE = 20

/** 下载记录页的磁力扩展 Tab。 */
@Singleton
class MagnetDownloadHistoryEntryImpl @Inject constructor(
    private val magnetRepository: MagnetRepository,
) : MagnetDownloadHistoryEntry {
    override val tabId: String = "magnet"
    override val tabTitleRes: Int = R.string.magnet_feature_magnet
    override val deleteSelectedMessageRes: Int = R.string.magnet_feature_download_history_delete_selected_magnet_message
    override val clearTabMessageRes: Int = R.string.magnet_feature_download_history_clear_magnet_message

    override fun observeCount() = magnetRepository.observeDownloadRecordCount()

    override suspend fun getRecordIds(): List<Long> = magnetRepository.getDownloadRecordIds()

    override suspend fun deleteRecords(ids: Set<Long>): Int = magnetRepository.deleteDownloadRecords(ids)

    @Composable
    override fun Content(
        selectionState: MagnetDownloadSelectionState,
        callbacks: MagnetDownloadHistoryCallbacks,
        modifier: Modifier,
    ) {
        val viewModel: MagnetDownloadHistoryVm = hiltViewModel()
        val pagingItems = viewModel.pagedItems.collectAsLazyPagingItems()
        val listState = rememberLazyListState()
        val retryText = stringResource(com.cla.clip.base.general.R.string.base_general_data_load_failed_retry)

        LaunchedEffect(viewModel, callbacks) {
            viewModel.messages.collect(callbacks.onShowMessage)
        }

        when {
            pagingItems.loadState.refresh is androidx.paging.LoadState.Loading && pagingItems.itemCount == 0 -> {
                MagnetPagingLoadingContent(modifier = modifier)
            }
            pagingItems.loadState.refresh is androidx.paging.LoadState.NotLoading && pagingItems.itemCount == 0 -> {
                MagnetPagingEmptyContent(
                    text = stringResource(R.string.magnet_feature_download_history_magnet_empty),
                    modifier = modifier,
                    icon = Icons.Default.Link
                )
            }
            pagingItems.loadState.refresh is androidx.paging.LoadState.Error && pagingItems.itemCount == 0 -> {
                MagnetPagingErrorContent(
                    text = retryText,
                    onRetry = pagingItems::retry,
                    modifier = modifier
                )
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        count = pagingItems.itemCount,
                        key = { index -> pagingItems[index]?.id ?: "magnet-placeholder-$index" }
                    ) { index ->
                        pagingItems[index]?.let { item ->
                            MagnetDownloadHistoryCard(
                                item = item,
                                selected = item.id in selectionState.selectedIds,
                                selectionMode = selectionState.selectionMode,
                                onToggleSelected = { callbacks.onToggleSelected(item.id) },
                                onEnterSelection = { callbacks.onEnterSelection(item.id) },
                                onCopy = { viewModel.copy(item.id) },
                                onOpen = { viewModel.copyAndOpen(item.id) }
                            )
                        }
                    }
                    magnetPagingAppendStateItem(
                        loadState = pagingItems.loadState.append,
                        retryText = retryText,
                        onRetry = pagingItems::retry
                    )
                }
            }
        }
    }
}

@HiltViewModel
class MagnetDownloadHistoryVm @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val magnetRepository: MagnetRepository,
    private val actionHandler: MagnetActionHandler,
) : ViewModel() {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

    val pagedItems = Pager(
        config = PagingConfig(
            pageSize = MAGNET_HISTORY_PAGE_SIZE,
            prefetchDistance = 5,
            enablePlaceholders = false
        )
    ) {
        magnetRepository.pagingDownloadRecords()
    }.flow.map { pagingData: PagingData<MagnetDownloadRecordData> ->
        pagingData.map { record -> record.toItem() }
    }.cachedIn(viewModelScope)

    fun copy(recordId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = actionHandler.copyRecord(recordId)
            _messages.emit(appContext.getString(result.messageRes))
        }
    }

    fun copyAndOpen(recordId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = actionHandler.copyAndOpenRecord(recordId)
            _messages.emit(appContext.getString(result.messageRes))
        }
    }

    private fun MagnetDownloadRecordData.toItem(): MagnetDownloadHistoryItem {
        return MagnetDownloadHistoryItem(
            id = id,
            title = title,
            sourceId = sourceId,
            category = category,
            sizeBytes = sizeBytes,
            lastSourceQuery = lastSourceQuery,
            lastUsedAt = lastUsedAt
        )
    }
}

data class MagnetDownloadHistoryItem(
    val id: Long,
    val title: String,
    val sourceId: String,
    val category: String?,
    val sizeBytes: Long?,
    val lastSourceQuery: String?,
    val lastUsedAt: Long,
)

@Composable
private fun MagnetDownloadHistoryCard(
    item: MagnetDownloadHistoryItem,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelected: () -> Unit,
    onEnterSelection: () -> Unit,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
) {
    MagnetFeatureCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            if (selectionMode) onToggleSelected() else onOpen()
        },
        onLongClick = onEnterSelection,
        contentPadding = PaddingValues(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (selectionMode && selected) Icons.Default.CheckCircle else Icons.Default.Link,
                contentDescription = null,
                tint = if (selectionMode && selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MagnetDownloadChip(text = magnetSourceName(item.sourceId))
                    MagnetDownloadChip(text = item.category?.takeIf { it.isNotBlank() } ?: stringResource(R.string.magnet_feature_magnet_uncategorized))
                    MagnetDownloadChip(text = formatMagnetSize(item.sizeBytes))
                }
                item.lastSourceQuery?.takeIf { it.isNotBlank() }?.let { query ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.magnet_feature_magnet_last_source_query, query),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = item.lastUsedAt.toRelativeTimeSpanString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!selectionMode) {
                Column {
                    IconButton(onClick = onCopy) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.magnet_feature_magnet_copy_only))
                    }
                    IconButton(onClick = onOpen) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.magnet_feature_magnet_open_external))
                    }
                }
            }
        }
    }
}

@Composable
private fun MagnetDownloadChip(text: String) {
    androidx.compose.material3.AssistChip(
        onClick = {},
        label = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    )
}
