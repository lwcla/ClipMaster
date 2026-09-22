package com.cla.clip.master.ui.page.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
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
import com.cla.clip.feature.ad.api.AdPlacement
import com.cla.clip.master.ad.rememberPageAdSession
import com.cla.clip.master.ad.rememberListAdLayoutCoordinator
import com.cla.clip.master.ui.widget.clip.ClipListAdConfig
import com.cla.clip.master.ui.widget.clip.ListAdContentItem
import com.cla.clip.master.ui.widget.PagingEmptyContent
import com.cla.clip.master.ui.widget.PagingErrorContent
import com.cla.clip.master.ui.widget.PagingLoadingContent
import com.cla.clip.master.ui.widget.pagingAppendStateItem
import kotlinx.coroutines.flow.Flow

/** 根据指定 Tab 展示对应历史列表或空状态；由 Pager 传入分类，避免渲染时只依赖当前选中态。 */
@Composable
internal fun DownloadHistoryContent(
    tabSpec: DownloadHistoryTabSpec,
    // 已停稳并且可见的 Tab 才允许广告请求。
    adsEnabled: Boolean,
    // 预览和确认覆盖层通过页面向下传递，不进入持久化业务状态。
    adOverlayBlocked: Boolean,
    // 根页面按压覆盖标题栏，松手前保留现有广告高度。
    adPointerPressed: Boolean,
    /** 同帧 SDK 返回时直接读取根按压事实，不能依赖重组后的布尔快照。 */
    adPointerPressedNow: () -> Boolean = { adPointerPressed },
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
    when (tabSpec.tab) {
        DownloadHistoryTab.Video -> {
            if (state.selectedTab == DownloadHistoryTab.Video) {
                val pagedVideos = videoPagingFlow.collectAsLazyPagingItems()
                VideoHistoryList(
                    adsEnabled = adsEnabled,
                    adOverlayBlocked = adOverlayBlocked,
                    adPointerPressed = adPointerPressed,
                    adPointerPressedNow = adPointerPressedNow,
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

        DownloadHistoryTab.Image -> {
            if (state.selectedTab == DownloadHistoryTab.Image) {
                val pagedImages = imagePagingFlow.collectAsLazyPagingItems()
                ImageHistoryList(
                    adsEnabled = adsEnabled,
                    adOverlayBlocked = adOverlayBlocked,
                    adPointerPressed = adPointerPressed,
                    adPointerPressedNow = adPointerPressedNow,
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
    // 横向 Pager 的稳定可见状态，不代替页面生命周期。
    adsEnabled: Boolean,
    // 删除确认、预览和任务执行期间禁止广告交互。
    adOverlayBlocked: Boolean,
    // 根页面按压覆盖标题栏，松手前保留现有广告高度。
    adPointerPressed: Boolean,
    /** 同帧 SDK 返回时直接读取根按压事实，不能依赖重组后的布尔快照。 */
    adPointerPressedNow: () -> Boolean = { adPointerPressed },
    pagedVideos: LazyPagingItems<DownloadHistoryVideoItem>,
    listState: LazyListState,
    state: DownloadHistoryUiState,
    onToggleSelected: (Long) -> Unit,
    onEnterSelection: (Long) -> Unit,
    onOpenVideo: (DownloadHistoryVideoItem) -> Unit,
    onRetryVideo: (Long) -> Unit,
) {
    // 每个 Tab 独立会话，图片密度按批次计数而非缩略图张数。
    val adSession = rememberPageAdSession(AdPlacement.VideoHistoryNative, enabled = adsEnabled)
    // 页面的覆盖层和多选态共同关闭广告资格，关闭后不更改下载记录数量。
    val adConfig = ClipListAdConfig(
        session = adSession,
        placement = AdPlacement.VideoHistoryNative,
        suppressed = state.selectionMode || adOverlayBlocked,
        layoutBlocked = adOverlayBlocked || adPointerPressed,
    )
    // 快照只保留每个业务主键的首项，并保存原源索引以保留 Paging 访问提示。
    val renderEntries = remember(pagedVideos.itemSnapshotList) {
        pagedVideos.itemSnapshotList.items.withIndex().distinctBy { it.value.id }
    }
    // 测量只记录业务卡片，广告尾部不作为首个可见内容参与恢复。
    val contentHeights = remember { mutableMapOf<Any, Int>() }
    SideEffect {
        adSession.suppressed = adConfig.suppressed
        adSession.pointerPressed = adPointerPressed
        adSession.pointerPressedNow = adPointerPressedNow
    }
    rememberListAdLayoutCoordinator(
        session = adSession,
        listState = listState,
        contentKeys = renderEntries.map { it.value.id },
        blocked = adOverlayBlocked || adPointerPressed,
        contentHeights = contentHeights,
    )

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
                // 下载记录视频列表普通态保留统一轻量留白；多选态为底部删除条额外预留空间，避免最后几项被挡住。
                contentPadding = PaddingValues(
                    start = 12.dp,
                    top = 12.dp,
                    end = 12.dp,
                    bottom = if (state.selectionMode) 96.dp else 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    count = renderEntries.size,
                    key = { index -> renderEntries[index].value.id }
                ) { index ->
                    // 去重列表的呈现索引映射回真实 Paging 索引，追加与预取不受广告影响。
                    val sourceIndex = pagedVideos.itemSnapshotList.placeholdersBefore + renderEntries[index].index
                    pagedVideos[sourceIndex]?.let { item ->
                        ListAdContentItem(
                            contentKey = item.id,
                            contentIndex = index,
                            listState = listState,
                            adConfig = adConfig,
                            contentHeights = contentHeights,
                        ) {
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
    // 横向 Pager 的稳定可见状态，不代替页面生命周期。
    adsEnabled: Boolean,
    // 删除确认、预览和任务执行期间禁止广告交互。
    adOverlayBlocked: Boolean,
    // 根页面按压覆盖标题栏，松手前保留现有广告高度。
    adPointerPressed: Boolean,
    /** 同帧 SDK 返回时直接读取根按压事实，不能依赖重组后的布尔快照。 */
    adPointerPressedNow: () -> Boolean = { adPointerPressed },
    pagedImages: LazyPagingItems<DownloadHistoryImageBatch>,
    listState: LazyListState,
    state: DownloadHistoryUiState,
    onToggleSelected: (Long) -> Unit,
    onEnterSelection: (Long) -> Unit,
    onRetryImage: (Long) -> Unit,
    onPreviewImage: (String) -> Unit,
) {
    // 每个 Tab 独立会话，图片密度按批次计数而非缩略图张数。
    val adSession = rememberPageAdSession(AdPlacement.ImageHistoryNative, enabled = adsEnabled)
    // 页面的覆盖层和多选态共同关闭广告资格，关闭后不更改下载记录数量。
    val adConfig = ClipListAdConfig(
        session = adSession,
        placement = AdPlacement.ImageHistoryNative,
        suppressed = state.selectionMode || adOverlayBlocked,
        layoutBlocked = adOverlayBlocked || adPointerPressed,
    )
    // 快照只保留每个业务主键的首项，并保存原源索引以保留 Paging 访问提示。
    val renderEntries = remember(pagedImages.itemSnapshotList) {
        pagedImages.itemSnapshotList.items.withIndex().distinctBy { it.value.id }
    }
    // 测量只记录业务卡片，广告尾部不作为首个可见内容参与恢复。
    val contentHeights = remember { mutableMapOf<Any, Int>() }
    SideEffect {
        adSession.suppressed = adConfig.suppressed
        adSession.pointerPressed = adPointerPressed
        adSession.pointerPressedNow = adPointerPressedNow
    }
    rememberListAdLayoutCoordinator(
        session = adSession,
        listState = listState,
        contentKeys = renderEntries.map { it.value.id },
        blocked = adOverlayBlocked || adPointerPressed,
        contentHeights = contentHeights,
    )

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
                // 下载记录图片列表底部留白与视频列表保持一致；多选态同样为底部删除条预留空间。
                contentPadding = PaddingValues(
                    start = 12.dp,
                    top = 12.dp,
                    end = 12.dp,
                    bottom = if (state.selectionMode) 96.dp else 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    count = renderEntries.size,
                    key = { index -> renderEntries[index].value.id }
                ) { index ->
                    // 去重列表的呈现索引映射回真实 Paging 索引，追加与预取不受广告影响。
                    val sourceIndex = pagedImages.itemSnapshotList.placeholdersBefore + renderEntries[index].index
                    pagedImages[sourceIndex]?.let { item ->
                        ListAdContentItem(
                            contentKey = item.id,
                            contentIndex = index,
                            listState = listState,
                            adConfig = adConfig,
                            contentHeights = contentHeights,
                        ) {
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
