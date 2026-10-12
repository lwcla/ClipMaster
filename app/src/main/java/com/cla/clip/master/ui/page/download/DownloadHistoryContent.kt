package com.cla.clip.master.ui.page.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.cla.clip.base.general.R
import com.cla.clip.master.ad.PageAdSession
import com.cla.clip.master.ad.rememberListAdLayoutCoordinator
import com.cla.clip.master.ui.widget.ad.ListAdContentState
import com.cla.clip.master.ui.widget.PagingEmptyContent
import com.cla.clip.master.ui.widget.PagingErrorContent
import com.cla.clip.master.ui.widget.PagingLoadingContent
import com.cla.clip.master.ui.widget.pagingAppendStateItem

/**
 * 视频历史分页画面，分页实例和实际呈现状态由稳定 Tab 的外层固定广告宿主传入。
 * refresh 加载时不展示旧记录；业务卡片只保留原 Paging 索引和稳定 key，广告不占列表索引。
 */
@Composable
internal fun VideoHistoryList(
    /** Pager 外持有的稳定视频访问，列表只注册业务锚点，不申请广告。 */
    adSession: PageAdSession,
    /** 与固定广告共用的真实画面状态，加载画面不能用旧快照冒充内容。 */
    adContentState: ListAdContentState,
    /** 页面覆盖、按压和横向拖动门，纵向滚动由列表协调器实时补充。 */
    adLayoutBlocked: Boolean,
    pagedVideos: LazyPagingItems<DownloadHistoryVideoItem>,
    listState: LazyListState,
    state: DownloadHistoryUiState,
    onToggleSelected: (Long) -> Unit,
    onEnterSelection: (Long) -> Unit,
    onOpenVideo: (DownloadHistoryVideoItem) -> Unit,
    onRetryVideo: (Long) -> Unit,
) {
    // 快照只保留每个业务主键的首项，并保存原源索引以保留 Paging 访问提示。
    val renderEntries = remember(pagedVideos.itemSnapshotList) {
        pagedVideos.itemSnapshotList.items.withIndex().distinctBy { it.value.id }
    }
    // 固定广告不占 Lazy 条目，卡片的原测量高度即可界定业务锚点，无需尾槽高度表。
    rememberListAdLayoutCoordinator(
        session = adSession,
        listState = listState,
        contentKeys = renderEntries.map { it.value.id },
        blocked = adLayoutBlocked,
    )

    // 首次或追加失败的资源化重试文案仍由业务列表负责。
    val retryText = stringResource(R.string.base_general_data_load_failed_retry)
    when {
        adContentState == ListAdContentState.Loading -> PagingLoadingContent()
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
                    // 广告不进入 key；呈现索引只用于定位去重后的业务记录。
                    key = { index -> renderEntries[index].value.id }
                ) { index ->
                    // 去重列表的呈现索引映射回真实 Paging 索引，追加与预取不受广告影响。
                    val sourceIndex = pagedVideos.itemSnapshotList.placeholdersBefore + renderEntries[index].index
                    pagedVideos[sourceIndex]?.let { item ->
                        // 当前真实业务项仍透传给原卡片，点击和长按只调用页面业务事件。
                        VideoHistoryCard(
                            item = item,
                            selected = item.id in state.selectedIds,
                            selectionMode = state.selectionMode,
                            // 多选身份只使用当前记录 id，不包含固定广告。
                            onToggleSelected = { onToggleSelected(item.id) },
                            // 长按进入多选的业务事件保持原记录身份。
                            onEnterSelection = { onEnterSelection(item.id) },
                            // 播放继续使用当前记录的本地媒体引用。
                            onOpen = { onOpenVideo(item) },
                            // 重试继续由 ViewModel 创建新的业务下载记录。
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

/**
 * 图片历史分页画面，固定广告位于外层，批次卡片与缩略图不携带广告槽。
 * 有旧批次的 refresh 仍展示原列表；画面与广告共用外层直接计算的状态，不复制分页状态。
 */
@Composable
internal fun ImageHistoryList(
    /** Pager 外持有的稳定图片访问，不因邻页预组合重建素材。 */
    adSession: PageAdSession,
    /** 与固定广告同源的真实画面状态，有旧批次时刷新继续展示内容。 */
    adContentState: ListAdContentState,
    /** 页面覆盖、按压和横向拖动门，纵向滚动由列表协调器补充。 */
    adLayoutBlocked: Boolean,
    pagedImages: LazyPagingItems<DownloadHistoryImageBatch>,
    listState: LazyListState,
    state: DownloadHistoryUiState,
    onToggleSelected: (Long) -> Unit,
    onEnterSelection: (Long) -> Unit,
    onRetryImage: (Long) -> Unit,
    onPreviewImage: (String) -> Unit,
) {
    // 快照只保留每个业务主键的首项，并保存原源索引以保留 Paging 访问提示。
    val renderEntries = remember(pagedImages.itemSnapshotList) {
        pagedImages.itemSnapshotList.items.withIndex().distinctBy { it.value.id }
    }
    // 固定广告不占 Lazy 索引，业务卡片自身高度足以恢复首个真实可见批次。
    rememberListAdLayoutCoordinator(
        session = adSession,
        listState = listState,
        contentKeys = renderEntries.map { it.value.id },
        blocked = adLayoutBlocked,
    )

    RefreshImageHistoryWhenVisible(pagedImages)
    // 初次或追加加载失败沿用共享分页状态与资源化重试文案。
    val retryText = stringResource(R.string.base_general_data_load_failed_retry)

    when {
        adContentState == ListAdContentState.Loading -> PagingLoadingContent()
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
                    // 批次 id 是业务稳定 key，缩略图和固定广告都不改变计数。
                    key = { index -> renderEntries[index].value.id }
                ) { index ->
                    // 去重列表的呈现索引映射回真实 Paging 索引，追加与预取不受广告影响。
                    val sourceIndex = pagedImages.itemSnapshotList.placeholdersBefore + renderEntries[index].index
                    pagedImages[sourceIndex]?.let { item ->
                        // 原批次卡片直接进入 Lazy 容器，不再附加广告尾部。
                        ImageHistoryCard(
                            item = item,
                            selected = item.id in state.selectedIds,
                            selectionMode = state.selectionMode,
                            // 批次选择范围保持业务 id，不把图片数量当作记录数量。
                            onToggleSelected = { onToggleSelected(item.id) },
                            // 长按进入管理多选仍使用当前批次。
                            onEnterSelection = { onEnterSelection(item.id) },
                            // 重新下载继续为当前批次创建新记录。
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
