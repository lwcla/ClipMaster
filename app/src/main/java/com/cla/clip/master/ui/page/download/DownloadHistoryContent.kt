package com.cla.clip.master.ui.page.download

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.cla.clip.base.general.R
import kotlinx.coroutines.flow.Flow

/** 根据指定 Tab 展示对应历史列表或空状态；由 Pager 传入分类，避免渲染时只依赖当前选中态。 */
@Composable
internal fun DownloadHistoryContent(
    tab: DownloadHistoryTab,
    state: DownloadHistoryUiState,
    videoPagingFlow: Flow<PagingData<DownloadHistoryVideoItem>>,
    imagePagingFlow: Flow<PagingData<DownloadHistoryImageBatch>>,
    videoListState: LazyListState,
    imageListState: LazyListState,
    onToggleSelected: (Long) -> Unit,
    onEnterSelection: (Long) -> Unit,
    onOpenVideo: (DownloadHistoryVideoItem) -> Unit,
    onRetryVideo: (Long) -> Unit,
    onRetryImage: (Long) -> Unit,
    onPreviewImage: (String) -> Unit,
) {
    when (tab) {
        DownloadHistoryTab.VIDEO -> {
            if (state.selectedTab == DownloadHistoryTab.VIDEO) {
                val pagedVideos = videoPagingFlow.collectAsLazyPagingItems()
                VideoHistoryList(
                    pagedVideos = pagedVideos,
                    listState = videoListState,
                    state = state,
                    onToggleSelected = onToggleSelected,
                    onEnterSelection = onEnterSelection,
                    onOpenVideo = onOpenVideo,
                    onRetryVideo = onRetryVideo
                )
            }
        }

        DownloadHistoryTab.IMAGE -> {
            if (state.selectedTab == DownloadHistoryTab.IMAGE) {
                val pagedImages = imagePagingFlow.collectAsLazyPagingItems()
                ImageHistoryList(
                    pagedImages = pagedImages,
                    listState = imageListState,
                    state = state,
                    onToggleSelected = onToggleSelected,
                    onEnterSelection = onEnterSelection,
                    onRetryImage = onRetryImage,
                    onPreviewImage = onPreviewImage
                )
            }
        }
    }
}

/** 视频历史分页列表；Paging 只组合当前可见页附近的数据，避免一次性读取全部视频首帧。 */
@Composable
internal fun VideoHistoryList(
    pagedVideos: LazyPagingItems<DownloadHistoryVideoItem>,
    listState: LazyListState,
    state: DownloadHistoryUiState,
    onToggleSelected: (Long) -> Unit,
    onEnterSelection: (Long) -> Unit,
    onOpenVideo: (DownloadHistoryVideoItem) -> Unit,
    onRetryVideo: (Long) -> Unit,
) {
    when {
        pagedVideos.loadState.refresh is LoadState.Loading -> LoadingHistory()
        pagedVideos.loadState.refresh is LoadState.NotLoading && pagedVideos.itemCount == 0 -> {
            EmptyHistory(text = stringResource(R.string.base_general_download_history_video_empty))
        }

        pagedVideos.loadState.refresh is LoadState.Error && pagedVideos.itemCount == 0 -> {
            PagingErrorHistory(onRetry = pagedVideos::retry)
        }

        else -> {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    count = pagedVideos.itemCount,
                    key = pagedVideos.itemKey { it.id }
                ) { index ->
                    pagedVideos[index]?.let { item ->
                        VideoHistoryCard(
                            item = item,
                            selected = item.id in state.selectedIds,
                            selectionMode = state.selectionMode,
                            onToggleSelected = { onToggleSelected(item.id) },
                            onEnterSelection = { onEnterSelection(item.id) },
                            onOpen = { onOpenVideo(item) },
                            onRetry = { onRetryVideo(item.id) }
                        )
                    }
                }
                pagingAppendState(pagedVideos.loadState.append, onRetry = pagedVideos::retry)
            }
        }
    }
}

/** 图片历史分页列表；每个批次进入当前页附近时才读取自己的图片项和可读缩略图 URI。 */
@Composable
internal fun ImageHistoryList(
    pagedImages: LazyPagingItems<DownloadHistoryImageBatch>,
    listState: LazyListState,
    state: DownloadHistoryUiState,
    onToggleSelected: (Long) -> Unit,
    onEnterSelection: (Long) -> Unit,
    onRetryImage: (Long) -> Unit,
    onPreviewImage: (String) -> Unit,
) {
    RefreshImageHistoryWhenVisible(pagedImages)

    when {
        pagedImages.loadState.refresh is LoadState.Loading && pagedImages.itemCount == 0 -> LoadingHistory()
        pagedImages.loadState.refresh is LoadState.NotLoading && pagedImages.itemCount == 0 -> {
            EmptyHistory(text = stringResource(R.string.base_general_download_history_image_empty))
        }

        pagedImages.loadState.refresh is LoadState.Error && pagedImages.itemCount == 0 -> {
            PagingErrorHistory(onRetry = pagedImages::retry)
        }

        else -> {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    count = pagedImages.itemCount,
                    key = pagedImages.itemKey { it.id }
                ) { index ->
                    pagedImages[index]?.let { item ->
                        ImageHistoryCard(
                            item = item,
                            selected = item.id in state.selectedIds,
                            selectionMode = state.selectionMode,
                            onToggleSelected = { onToggleSelected(item.id) },
                            onEnterSelection = { onEnterSelection(item.id) },
                            onRetry = { onRetryImage(item.id) },
                            onPreviewImage = onPreviewImage
                        )
                    }
                }
                pagingAppendState(pagedImages.loadState.append, onRetry = pagedImages::retry)
            }
        }
    }
}

/** 图片页可见或页面回到前台时刷新分页数据，确保外部相册删除后的本地可读性状态能重新校验。 */
@Composable
private fun RefreshImageHistoryWhenVisible(pagedImages: LazyPagingItems<DownloadHistoryImageBatch>) {
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(pagedImages) {
        // `cachedIn` 会保留旧分页结果；图片页重新进入组合时主动 refresh，避免继续展示相册已删除的旧 URI。
        pagedImages.refresh()
    }

    DisposableEffect(lifecycleOwner, pagedImages) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 用户从系统相册删除图片后回到本页时，Room 数据不会变化，只能通过刷新 Paging 重新触发 URI 可读性检查。
                pagedImages.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** 空历史状态，保持页面轻量直接提示当前分类没有记录。 */
@Composable
internal fun EmptyHistory(text: String) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.History,
            contentDescription = null,
            modifier = Modifier.size(42.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
        Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 首次分页加载状态；只在当前 Tab 的 Paging refresh 期间展示，避免用户误以为空记录。 */
@Composable
internal fun LoadingHistory() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

/** 首次分页加载失败状态；点击文案直接调用 Paging retry，继续复用当前分页源。 */
@Composable
internal fun PagingErrorHistory(onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.base_general_data_load_failed_retry),
            modifier = Modifier
                .clickable(onClick = onRetry)
                .padding(16.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** 追加分页状态；滚动到底部加载下一页时给出轻量反馈，失败时允许用户点击重试。 */
internal fun LazyListScope.pagingAppendState(
    loadState: LoadState,
    onRetry: () -> Unit,
) {
    when (loadState) {
        is LoadState.Loading -> {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }

        is LoadState.Error -> {
            item {
                Text(
                    text = stringResource(R.string.base_general_data_load_failed_retry),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onRetry)
                        .padding(14.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        else -> Unit
    }
}
