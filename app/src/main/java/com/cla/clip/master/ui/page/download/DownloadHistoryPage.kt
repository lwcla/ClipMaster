package com.cla.clip.master.ui.page.download

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import com.cla.clip.base.general.R
import com.cla.clip.base.general.utils.toast
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.navigation.VideoDownloadRoute
import com.cla.clip.master.ui.widget.SharedImagePreviewBottomSheet
import kotlinx.coroutines.launch

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
    val historyTabs = buildList {
        add(DownloadHistoryTabSpec(DownloadHistoryTab.Video, stringResource(R.string.base_general_video)))
        add(DownloadHistoryTabSpec(DownloadHistoryTab.Image, stringResource(R.string.base_general_image)))
        viewModel.extensionEntries.forEach { entry ->
            add(
                DownloadHistoryTabSpec(
                    tab = DownloadHistoryTab.Extension(entry.tabId),
                    title = stringResource(entry.tabTitleRes),
                    extensionEntry = entry
                )
            )
        }
    }
    // Pager 状态放在页面层持有，避免 ViewModel 依赖 Compose 类型；初始页跟随 ViewModel 当前分类。
    val pagerState = rememberPagerState(
        initialPage = historyTabs.indexOfFirst { it.tab == state.selectedTab }.coerceAtLeast(0),
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
            historyTabs.getOrNull(page)?.tab?.let(viewModel::selectTab)
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
                        hasRunning = state.currentTabHasRunning,
                        allowDeleteFiles = state.selectedTab !is DownloadHistoryTab.Extension,
                        message = state.deleteConfirmMessage(
                            context = context,
                            kind = DeleteRequestKind.ClearTab,
                            count = state.currentItemsCount,
                            tabs = historyTabs
                        )
                    )
                }
            )

            DownloadHistoryTabs(
                tabs = historyTabs,
                selectedTab = state.selectedTab,
                onSelected = { tab ->
                    val page = historyTabs.indexOfFirst { it.tab == tab }
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
                onShowMessage = { message ->
                    scope.launch { snackbarHostState.showSnackbar(message) }
                },
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
                        hasRunning = state.selectedHasRunning,
                        allowDeleteFiles = state.selectedTab !is DownloadHistoryTab.Extension,
                        message = state.deleteConfirmMessage(
                            context = context,
                            kind = DeleteRequestKind.Selected,
                            count = selectedCount,
                            tabs = historyTabs
                        )
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
        SharedImagePreviewBottomSheet(
            model = uri,
            onDismiss = { previewImageUri = null }
        )
    }
}

private fun DownloadHistoryUiState.deleteConfirmMessage(
    context: android.content.Context,
    kind: DeleteRequestKind,
    count: Int,
    tabs: List<DownloadHistoryTabSpec>,
): String {
    val extensionEntry = (selectedTab as? DownloadHistoryTab.Extension)
        ?.let { extensionTab -> tabs.firstOrNull { it.tab == extensionTab }?.extensionEntry }
    return when {
        extensionEntry != null -> {
            val resId = when (kind) {
                DeleteRequestKind.Selected -> extensionEntry.deleteSelectedMessageRes
                DeleteRequestKind.ClearTab -> extensionEntry.clearTabMessageRes
            }
            context.getString(resId, count)
        }
        kind == DeleteRequestKind.Selected -> context.getString(R.string.base_general_download_history_delete_selected_message, count)
        else -> context.getString(R.string.base_general_download_history_clear_message, count)
    }
}
