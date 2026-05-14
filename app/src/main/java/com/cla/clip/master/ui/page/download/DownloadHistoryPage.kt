package com.cla.clip.master.ui.page.download

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.cla.clip.base.general.R
import com.cla.clip.base.general.dao.DownloadTaskData
import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.base.general.utils.toRelativeTimeSpanString
import com.cla.clip.base.general.utils.toast
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.navigation.VideoDownloadRoute
import com.cla.clip.master.ui.widget.TitleBar
import kotlinx.coroutines.launch

/** 图片记录横向缩略图尺寸，固定尺寸可避免加载成功/失败时列表高度抖动。 */
private val ImageThumbSize = 72.dp

/** 下载记录页卡片圆角，沿用紧凑设置页风格，不做过度装饰。 */
private val HistoryCardShape = RoundedCornerShape(8.dp)

/**
 * 下载记录页面入口。
 *
 * 页面订阅 ViewModel 的历史流和一次性动作，负责展示 Tab、列表、多选删除弹窗、系统删除授权和本地视频播放。
 */
@Composable
fun DownloadHistoryPage(
    viewModel: DownloadHistoryVm = hiltViewModel(),
    onBack: () -> Unit,
    onNavigate: (Route) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<DeleteRequestUi?>(null) }
    var previewImageUri by remember { mutableStateOf<String?>(null) }

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
                onSelected = viewModel::selectTab
            )

            DownloadHistoryContent(
                state = state,
                onToggleSelected = viewModel::toggleSelected,
                onEnterSelection = viewModel::enterSelection,
                onOpenVideo = { item ->
                    if (item.localPath.isNullOrBlank()) return@DownloadHistoryContent
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
        ImagePreviewDialog(
            uri = uri,
            onDismiss = { previewImageUri = null }
        )
    }
}

/** 标题栏区域，根据普通态和多选态切换右侧操作。 */
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp, start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onExitSelection) {
                Text(stringResource(R.string.base_general_cancel))
            }
            Text(
                text = stringResource(R.string.base_general_download_history_selected_count, state.selectedIds.size),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            TextButton(onClick = onSelectAll, enabled = state.currentItemsCount > 0) {
                Text(stringResource(R.string.base_general_select_all))
            }
        }
    } else {
        Box {
            TitleBar(
                title = stringResource(R.string.base_general_download_history),
                onBack = onBack
            )
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onEnterSelection, enabled = state.currentItemsCount > 0) {
                    Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.base_general_download_history_multi_select))
                }
                IconButton(onClick = onClearTab, enabled = state.currentItemsCount > 0) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.base_general_download_history_clear_current_tab))
                }
            }
        }
    }
}

/** 视频/图片顶部 Tab，切换时由 ViewModel 清理选择状态。 */
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
                text = { Text(title) },
                icon = {
                    Icon(
                        imageVector = if (tab == DownloadHistoryTab.VIDEO) Icons.Default.Movie else Icons.Default.Image,
                        contentDescription = null
                    )
                }
            )
        }
    }
}

/** 根据当前 Tab 展示对应历史列表或空状态。 */
@Composable
private fun DownloadHistoryContent(
    state: DownloadHistoryUiState,
    onToggleSelected: (Long) -> Unit,
    onEnterSelection: (Long) -> Unit,
    onOpenVideo: (DownloadHistoryVideoItem) -> Unit,
    onRetryVideo: (Long) -> Unit,
    onRetryImage: (Long) -> Unit,
    onPreviewImage: (String) -> Unit,
) {
    when (state.selectedTab) {
        DownloadHistoryTab.VIDEO -> {
            if (state.videos.isEmpty()) {
                EmptyHistory(text = stringResource(R.string.base_general_download_history_video_empty))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.videos, key = { it.id }) { item ->
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
        }

        DownloadHistoryTab.IMAGE -> {
            if (state.images.isEmpty()) {
                EmptyHistory(text = stringResource(R.string.base_general_download_history_image_empty))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.images, key = { it.id }) { item ->
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
        }
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
            }

            Spacer(Modifier.height(8.dp))
            if (item.imageUris.isEmpty()) {
                DeletedPlaceholder(text = stringResource(R.string.base_general_download_history_local_file_deleted))
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 2.dp)
                ) {
                    items(item.imageUris.take(12)) { uri ->
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
                }
            }
        }
    }
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

/** 单张图片预览弹窗；只展示当前图片，不提供左右切换，保持实现范围与方案一致。 */
@Composable
private fun ImagePreviewDialog(
    uri: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.base_general_sure))
            }
        },
        title = { Text(stringResource(R.string.base_general_image_preview)) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    error = rememberVectorPainter(Icons.Default.BrokenImage),
                    placeholder = rememberVectorPainter(Icons.Default.Image)
                )
            }
        }
    )
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
        DownloadHistoryTab.VIDEO -> videos.size
        DownloadHistoryTab.IMAGE -> images.size
    }

/** 当前 Tab 是否包含进行中记录，用于清空确认文案。 */
private val DownloadHistoryUiState.currentTabHasRunning: Boolean
    get() = when (selectedTab) {
        DownloadHistoryTab.VIDEO -> videos.any { it.running }
        DownloadHistoryTab.IMAGE -> images.any { it.running }
    }

/** 当前选中记录是否包含进行中任务，用于删除确认文案。 */
private val DownloadHistoryUiState.selectedHasRunning: Boolean
    get() = when (selectedTab) {
        DownloadHistoryTab.VIDEO -> videos.any { it.id in selectedIds && it.running }
        DownloadHistoryTab.IMAGE -> images.any { it.id in selectedIds && it.running }
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
