package com.cla.clip.master.ui.page.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.cla.clip.master.ui.widget.PagingEmptyContent
import com.cla.clip.master.ui.widget.PagingErrorContent
import com.cla.clip.master.ui.widget.PagingLoadingContent
import com.cla.clip.master.ui.widget.pagingAppendStateItem
import kotlinx.coroutines.flow.Flow

/** 根据指定 Tab 展示对应历史列表或空状态；由 Pager 传入分类，避免渲染时只依赖当前选中态。 */
@Composable
internal fun DownloadHistoryContent(
    tab: DownloadHistoryTab,
    state: DownloadHistoryUiState,
    videoPagingFlow: Flow<PagingData<DownloadHistoryVideoItem>>,
    imagePagingFlow: Flow<PagingData<DownloadHistoryImageBatch>>,
    magnetPagingFlow: Flow<PagingData<DownloadHistoryMagnetItem>>,
    videoListState: LazyListState,
    imageListState: LazyListState,
    magnetListState: LazyListState,
    onToggleSelected: (Long) -> Unit,
    onEnterSelection: (Long) -> Unit,
    onOpenVideo: (DownloadHistoryVideoItem) -> Unit,
    onRetryVideo: (Long) -> Unit,
    onRetryImage: (Long) -> Unit,
    onCopyMagnet: (Long) -> Unit,
    onOpenMagnet: (Long) -> Unit,
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

        DownloadHistoryTab.MAGNET -> {
            if (state.selectedTab == DownloadHistoryTab.MAGNET) {
                val pagedMagnets = magnetPagingFlow.collectAsLazyPagingItems()
                MagnetHistoryList(
                    pagedMagnets = pagedMagnets,
                    listState = magnetListState,
                    state = state,
                    onToggleSelected = onToggleSelected,
                    onEnterSelection = onEnterSelection,
                    onCopyMagnet = onCopyMagnet,
                    onOpenMagnet = onOpenMagnet
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
    val retryText = stringResource(R.string.base_general_data_load_failed_retry)
    when {
        pagedVideos.loadState.refresh is LoadState.Loading -> PagingLoadingContent()
        pagedVideos.loadState.refresh is LoadState.NotLoading && pagedVideos.itemCount == 0 -> {
            PagingEmptyContent(text = stringResource(R.string.base_general_download_history_video_empty))
        }

        pagedVideos.loadState.refresh is LoadState.Error && pagedVideos.itemCount == 0 -> {
            PagingErrorContent(
                text = retryText,
                onRetry = pagedVideos::retry
            )
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
                pagingAppendStateItem(
                    loadState = pagedVideos.loadState.append,
                    retryText = retryText,
                    onRetry = pagedVideos::retry
                )
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
    val retryText = stringResource(R.string.base_general_data_load_failed_retry)

    when {
        pagedImages.loadState.refresh is LoadState.Loading && pagedImages.itemCount == 0 -> PagingLoadingContent()
        pagedImages.loadState.refresh is LoadState.NotLoading && pagedImages.itemCount == 0 -> {
            PagingEmptyContent(text = stringResource(R.string.base_general_download_history_image_empty))
        }

        pagedImages.loadState.refresh is LoadState.Error && pagedImages.itemCount == 0 -> {
            PagingErrorContent(
                text = retryText,
                onRetry = pagedImages::retry
            )
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
                pagingAppendStateItem(
                    loadState = pagedImages.loadState.append,
                    retryText = retryText,
                    onRetry = pagedImages::retry
                )
            }
        }
    }
}

/** 磁力记录分页列表；只读取主库元数据，不访问本地文件或启动下载任务。 */
@Composable
internal fun MagnetHistoryList(
    pagedMagnets: LazyPagingItems<DownloadHistoryMagnetItem>,
    listState: LazyListState,
    state: DownloadHistoryUiState,
    onToggleSelected: (Long) -> Unit,
    onEnterSelection: (Long) -> Unit,
    onCopyMagnet: (Long) -> Unit,
    onOpenMagnet: (Long) -> Unit,
) {
    val retryText = stringResource(R.string.base_general_data_load_failed_retry)
    when {
        pagedMagnets.loadState.refresh is LoadState.Loading && pagedMagnets.itemCount == 0 -> PagingLoadingContent()
        pagedMagnets.loadState.refresh is LoadState.NotLoading && pagedMagnets.itemCount == 0 -> {
            PagingEmptyContent(text = stringResource(R.string.base_general_download_history_magnet_empty))
        }

        pagedMagnets.loadState.refresh is LoadState.Error && pagedMagnets.itemCount == 0 -> {
            PagingErrorContent(
                text = retryText,
                onRetry = pagedMagnets::retry
            )
        }

        else -> {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    count = pagedMagnets.itemCount,
                    key = pagedMagnets.itemKey { it.id }
                ) { index ->
                    pagedMagnets[index]?.let { item ->
                        MagnetHistoryCard(
                            item = item,
                            selected = item.id in state.selectedIds,
                            selectionMode = state.selectionMode,
                            onToggleSelected = { onToggleSelected(item.id) },
                            onEnterSelection = { onEnterSelection(item.id) },
                            onCopy = { onCopyMagnet(item.id) },
                            onOpen = { onOpenMagnet(item.id) }
                        )
                    }
                }
                pagingAppendStateItem(
                    loadState = pagedMagnets.loadState.append,
                    retryText = retryText,
                    onRetry = pagedMagnets::retry
                )
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
