package com.cla.clip.master.ui.page.download

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.cla.clip.base.general.R
import com.cla.clip.base.general.dao.DownloadTaskData
import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.base.general.utils.toRelativeTimeSpanString
import com.cla.clip.base.general.utils.toast
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.navigation.VideoDownloadRoute
import com.cla.clip.master.ui.widget.TitleBar
import com.cla.clip.master.ui.widget.TitleBarText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** 图片记录横向缩略图尺寸，固定尺寸可避免加载成功/失败时列表高度抖动。 */
private val ImageThumbSize = 72.dp

/** 下载记录页卡片圆角，沿用紧凑设置页风格，不做过度装饰。 */
private val HistoryCardShape = RoundedCornerShape(8.dp)

/**
 * 下载记录页面入口。
 *
 * 页面订阅 ViewModel 的历史流和一次性动作，负责展示 Tab、横向分页列表、多选删除弹窗、系统删除授权和本地视频播放。
 * 横向 Pager 只保存在 Compose 层，用于提供左右滑动切换分类的交互；最终分类状态仍同步回 ViewModel，保证标题栏数量和删除目标一致。
 * 视频和图片记录使用 Paging 分页加载，且只在页面 STARTED 生命周期内收集当前 Tab 的分页流，避免页面不可见时继续读取媒体。
 * 每个 Tab 的列表滚动状态在页面入口独立持有，切换 Tab、主题重组或横竖屏恢复时尽量保留原滚动位置。
 * 多选态是页面内的临时管理状态，系统返回键会优先退出多选，避免用户误离开下载记录页面。
 */
@Composable
fun DownloadHistoryPage(
    viewModel: DownloadHistoryVm = hiltViewModel(),
    onBack: () -> Unit,
    onNavigate: (Route) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<DeleteRequestUi?>(null) }
    var previewImageUri by remember { mutableStateOf<String?>(null) }
    // 下载记录只包含视频和图片两个一级分类，列表顺序需要同时驱动 Tab 指示器和横向 Pager 页码。
    val historyTabs = remember { listOf(DownloadHistoryTab.VIDEO, DownloadHistoryTab.IMAGE) }
    // Pager 状态放在页面层持有，避免 ViewModel 依赖 Compose 类型；初始页跟随 ViewModel 当前分类。
    val pagerState = rememberPagerState(
        initialPage = historyTabs.indexOf(state.selectedTab).coerceAtLeast(0),
        pageCount = { historyTabs.size }
    )
    // 和剪贴列表页保持一致：分页 Flow 对象稳定，只跟随页面 STARTED 生命周期收集；具体是否收集由当前 Tab 的内容分支决定。
    val videoPagingFlow = remember(viewModel.pagedVideos, lifecycle) {
        viewModel.pagedVideos.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
    }
    val imagePagingFlow = remember(viewModel.pagedImages, lifecycle) {
        viewModel.pagedImages.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
    }
    // 每个分类持有自己的可保存列表状态；切换 Tab、主题重组或 Activity 重建后都尽量回到原位置。
    val videoListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val imageListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    BackHandler(enabled = state.selectionMode && pendingDelete == null && previewImageUri == null) {
        // 删除弹窗和图片预览存在时应优先响应自己的返回关闭逻辑；普通多选态返回只清空选择，不退出页面。
        viewModel.exitSelection()
    }

    val deletePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
        onResult = { result: ActivityResult ->
            viewModel.onMediaDeletePermissionResult(result.resultCode == Activity.RESULT_OK)
        }
    )

    LaunchedEffect(viewModel) {
        viewModel.actions.collect { action ->
            when (action) {
                is DownloadHistoryAction.NavigateVideoDownload -> onNavigate(VideoDownloadRoute(action.taskId))
                is DownloadHistoryAction.RequestMediaDeletePermission -> deletePermissionLauncher.launch(action.request)
            }
        }
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    LaunchedEffect(pagerState, historyTabs) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            // 只在滑动最终停稳后同步 ViewModel，避免拖拽过程中频繁清空多选状态或让标题栏数量抖动。
            historyTabs.getOrNull(page)?.let(viewModel::selectTab)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DownloadHistoryTitleBar(
                state = state,
                onBack = onBack,
                onExitSelection = viewModel::exitSelection,
                onEnterSelection = { viewModel.enterSelection() },
                onSelectAll = viewModel::selectAllCurrentTab,
                onClearTab = {
                    pendingDelete = DeleteRequestUi(
                        kind = DeleteRequestKind.ClearTab,
                        count = state.currentItemsCount,
                        hasRunning = state.currentTabHasRunning
                    )
                }
            )

            DownloadHistoryTabs(
                selectedTab = state.selectedTab,
                onSelected = { tab ->
                    val page = historyTabs.indexOf(tab)
                    if (page < 0) return@DownloadHistoryTabs
                    // 点击 Tab 时立即更新分类状态以刷新标题栏动作，再平滑滚动内容页保持视觉联动。
                    viewModel.selectTab(tab)
                    scope.launch { pagerState.animateScrollToPage(page) }
                }
            )

            DownloadHistoryPager(
                tabs = historyTabs,
                pagerState = pagerState,
                state = state,
                videoPagingFlow = videoPagingFlow,
                imagePagingFlow = imagePagingFlow,
                videoListState = videoListState,
                imageListState = imageListState,
                onToggleSelected = viewModel::toggleSelected,
                onEnterSelection = viewModel::enterSelection,
                onOpenVideo = { item ->
                    if (item.localPath.isNullOrBlank()) return@DownloadHistoryPager
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(item.localPath), "video/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(intent) }
                        .onFailure { scope.launch { context.toast(R.string.base_general_there_is_no_available_application_to_open_this_video) } }
                },
                onRetryVideo = viewModel::retryVideo,
                onRetryImage = viewModel::retryImageBatch,
                onPreviewImage = { previewImageUri = it }
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )

        val selectedCount = state.selectedIds.size
        if (state.selectionMode && selectedCount > 0) {
            SelectionActionBar(
                selectedCount = selectedCount,
                onDelete = {
                    pendingDelete = DeleteRequestUi(
                        kind = DeleteRequestKind.Selected,
                        count = selectedCount,
                        hasRunning = state.selectedHasRunning
                    )
                },
            )
        }

        if (state.busy) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }

    pendingDelete?.let { request ->
        DeleteModeDialog(
            request = request,
            onDismiss = { pendingDelete = null },
            onDeleteRecordOnly = {
                pendingDelete = null
                when (request.kind) {
                    DeleteRequestKind.Selected -> viewModel.deleteSelected(deleteFiles = false)
                    DeleteRequestKind.ClearTab -> viewModel.clearCurrentTab(deleteFiles = false)
                }
            },
            onDeleteRecordAndFiles = {
                pendingDelete = null
                when (request.kind) {
                    DeleteRequestKind.Selected -> viewModel.deleteSelected(deleteFiles = true)
                    DeleteRequestKind.ClearTab -> viewModel.clearCurrentTab(deleteFiles = true)
                }
            }
        )
    }

    previewImageUri?.let { uri ->
        ImagePreviewBottomSheet(
            uri = uri,
            onDismiss = { previewImageUri = null }
        )
    }
}

/** 标题栏区域，复用通用插槽标题栏统一状态栏、安全高度和按钮垂直对齐规则。 */
@Composable
private fun DownloadHistoryTitleBar(
    state: DownloadHistoryUiState,
    onBack: () -> Unit,
    onExitSelection: () -> Unit,
    onEnterSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onClearTab: () -> Unit,
) {
    if (state.selectionMode) {
        TitleBar(
            navigation = {
                TextButton(onClick = onExitSelection) {
                    Text(stringResource(R.string.base_general_cancel))
                }
            },
            title = {
                TitleBarText(
                    text = stringResource(R.string.base_general_download_history_selected_count, state.selectedIds.size),
                )
            },
            actions = {
                TextButton(onClick = onSelectAll, enabled = state.currentItemsCount > 0) {
                    Text(stringResource(R.string.base_general_select_all))
                }
            }
        )
    } else {
        TitleBar(
            title = stringResource(R.string.base_general_download_history),
            onBack = onBack,
            actions = {
                IconButton(onClick = onEnterSelection, enabled = state.currentItemsCount > 0) {
                    Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.base_general_download_history_multi_select))
                }
                IconButton(onClick = onClearTab, enabled = state.currentItemsCount > 0) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.base_general_download_history_clear_current_tab))
                }
            }
        )
    }
}

/** 视频/图片顶部 Tab；这里仅展示文字，不配置图标，避免 Tab 过高挤占下方历史列表空间。 */
@Composable
private fun DownloadHistoryTabs(
    selectedTab: DownloadHistoryTab,
    onSelected: (DownloadHistoryTab) -> Unit,
) {
    val tabs = listOf(
        DownloadHistoryTab.VIDEO to stringResource(R.string.base_general_video),
        DownloadHistoryTab.IMAGE to stringResource(R.string.base_general_image)
    )
    PrimaryTabRow(selectedTabIndex = tabs.indexOfFirst { it.first == selectedTab }.coerceAtLeast(0)) {
        tabs.forEach { (tab, title) ->
            Tab(
                selected = selectedTab == tab,
                onClick = { onSelected(tab) },
                text = { Text(title) }
            )
        }
    }
}

/**
 * 下载记录横向分页容器。
 *
 * Pager 负责承载视频页和图片页的左右滑动体验；具体列表仍交给 DownloadHistoryContent 渲染，避免分页状态进入 ViewModel。
 */
@Composable
private fun DownloadHistoryPager(
    tabs: List<DownloadHistoryTab>,
    pagerState: androidx.compose.foundation.pager.PagerState,
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
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        DownloadHistoryContent(
            tab = tabs.getOrElse(page) { DownloadHistoryTab.VIDEO },
            state = state,
            videoPagingFlow = videoPagingFlow,
            imagePagingFlow = imagePagingFlow,
            videoListState = videoListState,
            imageListState = imageListState,
            onToggleSelected = onToggleSelected,
            onEnterSelection = onEnterSelection,
            onOpenVideo = onOpenVideo,
            onRetryVideo = onRetryVideo,
            onRetryImage = onRetryImage,
            onPreviewImage = onPreviewImage
        )
    }
}

/** 根据指定 Tab 展示对应历史列表或空状态；由 Pager 传入分类，避免渲染时只依赖当前选中态。 */
@Composable
private fun DownloadHistoryContent(
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
private fun VideoHistoryList(
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
private fun ImageHistoryList(
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

/**
 * 视频历史卡片。
 *
 * 普通态点击成功且文件存在的视频会打开系统播放器；已删除、失败或下载中时点击不播放，避免用户进入无效 Intent。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoHistoryCard(
    item: DownloadHistoryVideoItem,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelected: () -> Unit,
    onEnterSelection: () -> Unit,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (selectionMode) onToggleSelected() else if (item.status == DownloadTaskData.STATUS_SUCCESS && item.localExists) onOpen()
                },
                onLongClick = onEnterSelection
            ),
        shape = HistoryCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SelectableMediaBox(
                selected = selected,
                selectionMode = selectionMode,
                deleted = item.deletedLocal,
                bitmap = item.thumbnail,
                icon = Icons.Default.Movie
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
                    HistoryChip(text = videoStatusText(item))
                    item.sizeBytes?.let { HistoryChip(text = formatFileSize(it)) }
                    item.durationMs?.let { HistoryChip(text = formatDuration(it)) }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = item.updateTime.toRelativeTimeSpanString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.running) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { item.progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (!selectionMode && (item.deletedLocal || item.status == DownloadTaskData.STATUS_FAILED)) {
                IconButton(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.base_general_download_history_retry))
                }
            } else if (!selectionMode && item.status == DownloadTaskData.STATUS_SUCCESS && item.localExists) {
                IconButton(onClick = onOpen) {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.base_general_download_history_play_video))
                }
            }
        }
    }
}

/**
 * 图片批次历史卡片。
 *
 * 缩略图只展示成功且仍可读取的图片 URI；点击缩略图弹出单张预览，不在 App 内做左右切换或完整相册。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageHistoryCard(
    item: DownloadHistoryImageBatch,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelected: () -> Unit,
    onEnterSelection: () -> Unit,
    onRetry: () -> Unit,
    onPreviewImage: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelected() },
                onLongClick = onEnterSelection
            ),
        shape = HistoryCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.Image,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.updateTime.toRelativeTimeSpanString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!selectionMode && item.deletedLocal) {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.base_general_download_history_retry))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HistoryChip(text = imageStatusText(item))
                HistoryChip(text = stringResource(R.string.base_general_download_history_image_count, item.successCount, item.totalCount))
                if (item.failedCount > 0) HistoryChip(text = stringResource(R.string.base_general_image_failed_count, item.failedCount))
                if (item.filteredCount > 0) HistoryChip(text = stringResource(R.string.base_general_image_filtered_count, item.filteredCount))
                if (item.unreadableCount > 0) HistoryChip(text = stringResource(R.string.base_general_download_history_unreadable_image_count, item.unreadableCount))
            }

            item.outputFolderName()?.let { folderName ->
                Spacer(Modifier.height(6.dp))
                FolderNameText(folderName = folderName)
            }

            Spacer(Modifier.height(8.dp))
            if (item.imageUris.isEmpty()) {
                DeletedPlaceholder(text = stringResource(R.string.base_general_download_history_local_file_deleted))
            } else {
                HistoryImagePreviewGrid(
                    imageUris = item.imageUris,
                    selectionMode = selectionMode,
                    onPreviewImage = onPreviewImage
                )
            }
        }
    }
}

/** 图片批次输出文件夹展示区；文件夹名允许多行，避免同标题下载只靠被省略的 chip 无法区分。 */
@Composable
private fun FolderNameText(folderName: String) {
    Column {
        Text(
            text = stringResource(R.string.base_general_download_history_image_folder),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = folderName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 图片批次横向缩略图。
 *
 * 8 张以内维持单行，便于快速浏览小批次；超过 8 张后每列最多放两张图，减少长批次横向滑动距离。
 * LazyRow 只按列懒加载，因此可以展示全部可读成功图片，同时避免一次性把大批量缩略图全部组合出来。
 */
@Composable
private fun HistoryImagePreviewGrid(
    imageUris: List<String>,
    selectionMode: Boolean,
    onPreviewImage: (String) -> Unit,
) {
    // 超过 8 张才启用双行，避免小批次记录因为第二行留白而显得过重。
    val useTwoRows = imageUris.size > 8
    // LazyRow 的 item 是“列”；单行模式每列 1 张，双行模式每列最多 2 张。
    val columns = remember(imageUris, useTwoRows) {
        if (useTwoRows) imageUris.chunked(2) else imageUris.map { listOf(it) }
    }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 2.dp)
    ) {
        items(columns) { column ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                column.forEach { uri ->
                    HistoryImageThumb(
                        uri = uri,
                        selectionMode = selectionMode,
                        onPreviewImage = onPreviewImage
                    )
                }
                if (useTwoRows && column.size == 1) {
                    Spacer(Modifier.size(ImageThumbSize))
                }
            }
        }
    }
}

/** 单张历史图片缩略图，点击只预览当前图片，不进入左右切换相册。 */
@Composable
private fun HistoryImageThumb(
    uri: String,
    selectionMode: Boolean,
    onPreviewImage: (String) -> Unit,
) {
    AsyncImage(
        model = uri,
        contentDescription = null,
        modifier = Modifier
            .size(ImageThumbSize)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = !selectionMode) { onPreviewImage(uri) },
        contentScale = ContentScale.Crop,
        error = rememberVectorPainter(Icons.Default.BrokenImage),
        placeholder = rememberVectorPainter(Icons.Default.Image)
    )
}

/** 可选中的视频缩略图区域，统一处理首帧、已删除和选择覆盖层。 */
@Composable
private fun SelectableMediaBox(
    selected: Boolean,
    selectionMode: Boolean,
    deleted: Boolean,
    bitmap: Bitmap?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            deleted -> DeletedPlaceholder(text = stringResource(R.string.base_general_download_history_deleted_short))

            else -> Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (selectionMode) {
            Surface(
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
                modifier = Modifier.fillMaxSize()
            ) {}
            Icon(
                imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            )
        }
    }
}

/** 历史列表标签，用于展示状态、数量、体积和时长等短信息。 */
@Composable
private fun HistoryChip(text: String) {
    AssistChip(onClick = {}, label = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) })
}

/** 空历史状态，保持页面轻量直接提示当前分类没有记录。 */
@Composable
private fun EmptyHistory(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(10.dp))
        Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 首次分页加载状态；只在当前 Tab 的 Paging refresh 期间展示，避免用户误以为空记录。 */
@Composable
private fun LoadingHistory() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

/** 首次分页加载失败状态；点击文案直接调用 Paging retry，继续复用当前分页源。 */
@Composable
private fun PagingErrorHistory(onRetry: () -> Unit) {
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
private fun LazyListScope.pagingAppendState(
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

/** 本地文件不可读占位，既用于视频首帧区域，也用于图片批次缩略图区。 */
@Composable
private fun DeletedPlaceholder(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 底部多选操作条，固定只承载删除动作，清空分类仍在标题栏右侧。 */
@Composable
private fun SelectionActionBar(
    selectedCount: Int,
    onDelete: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.base_general_download_history_selected_count, selectedCount),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.base_general_delete))
                }
            }
        }
    }
}

/** 删除方式选择弹窗，明确区分“仅删除记录”和“删除记录和本地文件”。 */
@Composable
private fun DeleteModeDialog(
    request: DeleteRequestUi,
    onDismiss: () -> Unit,
    onDeleteRecordOnly: () -> Unit,
    onDeleteRecordAndFiles: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.base_general_download_history_delete_title)) },
        text = {
            Column {
                Text(
                    text = when (request.kind) {
                        DeleteRequestKind.Selected -> stringResource(R.string.base_general_download_history_delete_selected_message, request.count)
                        DeleteRequestKind.ClearTab -> stringResource(R.string.base_general_download_history_clear_message, request.count)
                    }
                )
                if (request.hasRunning) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.base_general_download_history_delete_running_tip),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDeleteRecordAndFiles) {
                Text(stringResource(R.string.base_general_download_history_delete_records_and_files))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDeleteRecordOnly) {
                    Text(stringResource(R.string.base_general_download_history_delete_records_only))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.base_general_cancel))
                }
            }
        }
    )
}

/**
 * 单张图片预览底部弹窗。
 *
 * 图片按弹窗宽度完整排版，并把图片区域做成纵向滚动容器；高图不会被固定高度裁切，用户可以继续向下滑查看完整内容。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImagePreviewBottomSheet(
    uri: String,
    onDismiss: () -> Unit,
) {
    // 跳过半展开态，打开后直接给图片预览尽量多的垂直空间；真正超出屏幕的部分交给图片区域滚动。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 每次打开预览都使用独立滚动状态，避免上一张高图的滚动位置影响下一张图片。
    val imageScrollState = rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.base_general_image_preview),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.base_general_sure))
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(imageScrollState),
                contentAlignment = Alignment.TopCenter
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                    error = rememberVectorPainter(Icons.Default.BrokenImage),
                    placeholder = rememberVectorPainter(Icons.Default.Image)
                )
            }
        }
    }
}

/** 删除弹窗的来源，决定确认后调用删除选中记录还是清空当前分类。 */
private enum class DeleteRequestKind {
    /** 删除当前多选选中的记录。 */
    Selected,

    /** 清空当前 Tab 下全部记录。 */
    ClearTab
}

/** 删除弹窗 UI 参数，集中记录数量和是否包含进行中任务。 */
private data class DeleteRequestUi(
    /** 删除动作来源。 */
    val kind: DeleteRequestKind,

    /** 本次会影响的记录数量。 */
    val count: Int,

    /** 是否包含正在下载的记录；包含时弹窗提示会先停止下载任务。 */
    val hasRunning: Boolean,
)

/** 当前 Tab 的记录总数，用于标题栏按钮可用性和清空弹窗数量。 */
private val DownloadHistoryUiState.currentItemsCount: Int
    get() = when (selectedTab) {
        DownloadHistoryTab.VIDEO -> videoCount
        DownloadHistoryTab.IMAGE -> imageCount
    }

/** 当前 Tab 是否包含进行中记录，用于清空确认文案。 */
private val DownloadHistoryUiState.currentTabHasRunning: Boolean
    get() = when (selectedTab) {
        DownloadHistoryTab.VIDEO -> videoRunningCount > 0
        DownloadHistoryTab.IMAGE -> imageRunningCount > 0
    }

/** 将视频任务状态映射为用户可见文案；失败态优先展示具体错误。 */
@Composable
private fun videoStatusText(item: DownloadHistoryVideoItem): String {
    return when {
        item.deletedLocal -> stringResource(R.string.base_general_download_history_local_file_deleted)
        item.status == DownloadTaskData.STATUS_SUCCESS -> stringResource(R.string.base_general_download_completed)
        item.status == DownloadTaskData.STATUS_FAILED -> item.errorMsg ?: stringResource(R.string.base_general_download_failed)
        item.status == DownloadTaskData.STATUS_MERGING -> stringResource(R.string.base_general_merge_progress, item.progress)
        else -> stringResource(R.string.base_general_download_progress, item.progress)
    }
}

/** 将图片批次状态映射为用户可见文案。 */
@Composable
private fun imageStatusText(item: DownloadHistoryImageBatch): String {
    return when {
        item.deletedLocal -> stringResource(R.string.base_general_download_history_local_file_deleted)
        item.status == ImageExtractBatchData.STATUS_SUCCESS -> stringResource(R.string.base_general_download_completed)
        item.status == ImageExtractBatchData.STATUS_PARTIAL_SUCCESS -> stringResource(R.string.base_general_download_history_partial_success)
        item.status == ImageExtractBatchData.STATUS_FILTERED -> stringResource(R.string.base_general_download_history_filtered)
        item.status == ImageExtractBatchData.STATUS_DOWNLOADING -> stringResource(R.string.base_general_image_download_progress, item.successCount + item.failedCount + item.filteredCount, item.totalCount)
        else -> stringResource(R.string.base_general_download_failed)
    }
}

/** 从图片批次输出目录中提取最后一级文件夹名，用于区分同标题多次下载的不同保存位置。 */
private fun DownloadHistoryImageBatch.outputFolderName(): String? {
    return outputDir
        ?.trim()
        ?.replace('\\', '/')
        ?.trim('/')
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
}

/** 格式化文件大小，保持和图片提取页相同的 B/KB/MB/GB 粒度。 */
@Composable
private fun formatFileSize(bytes: Long): String {
    val value = bytes.toDouble()
    return when {
        bytes < 1024L -> stringResource(R.string.base_general_file_size_bytes, bytes)
        bytes < 1024L * 1024L -> stringResource(R.string.base_general_file_size_kb, value / 1024.0)
        bytes < 1024L * 1024L * 1024L -> stringResource(R.string.base_general_file_size_mb, value / 1024.0 / 1024.0)
        else -> stringResource(R.string.base_general_file_size_gb, value / 1024.0 / 1024.0 / 1024.0)
    }
}

/** 格式化视频时长，短视频展示分秒，超过一小时展示时分秒。 */
private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
