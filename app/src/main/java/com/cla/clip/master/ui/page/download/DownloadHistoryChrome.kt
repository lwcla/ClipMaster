package com.cla.clip.master.ui.page.download

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.paging.PagingData
import com.cla.clip.base.general.R
import com.cla.clip.feature.magnet.api.MagnetDownloadHistoryEntry
import com.cla.clip.master.ui.widget.TitleBar
import com.cla.clip.master.ui.widget.TitleBarText
import kotlinx.coroutines.flow.Flow

/** 下载记录页 Tab 展示配置。 */
internal data class DownloadHistoryTabSpec(
    val tab: DownloadHistoryTab,
    val title: String,
    val extensionEntry: MagnetDownloadHistoryEntry? = null,
)

/** 标题栏区域，复用通用插槽标题栏统一状态栏、安全高度和按钮垂直对齐规则。 */
@Composable
internal fun DownloadHistoryTitleBar(
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
internal fun DownloadHistoryTabs(
    tabs: List<DownloadHistoryTabSpec>,
    selectedTab: DownloadHistoryTab,
    onSelected: (DownloadHistoryTab) -> Unit,
) {
    PrimaryTabRow(selectedTabIndex = tabs.indexOfFirst { it.tab == selectedTab }.coerceAtLeast(0)) {
        tabs.forEach { spec ->
            Tab(
                selected = selectedTab == spec.tab,
                onClick = { onSelected(spec.tab) },
                text = { Text(spec.title) }
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
internal fun DownloadHistoryPager(
    tabs: List<DownloadHistoryTabSpec>,
    pagerState: PagerState,
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
    onShowMessage: (String) -> Unit,
    onPreviewImage: (String) -> Unit,
) {
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        DownloadHistoryContent(
            tabSpec = tabs.getOrElse(page) {
                DownloadHistoryTabSpec(DownloadHistoryTab.Video, "")
            },
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
            onShowMessage = onShowMessage,
            onPreviewImage = onPreviewImage
        )
    }
}

/**
 * 下载记录页顶部区域。
 *
 * 标题栏和纯文字 Tab 必须按垂直顺序组合；如果直接在 `Scaffold.topBar` 里并列发射两个根节点，
 * Compose 会把它们放进同一个 topBar slot，视觉上就会出现标题和 Tab 重叠。
 */
@Composable
internal fun DownloadHistoryTopBar(
    state: DownloadHistoryUiState,
    tabs: List<DownloadHistoryTabSpec>,
    onBack: () -> Unit,
    onExitSelection: () -> Unit,
    onEnterSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onClearTab: () -> Unit,
    onSelected: (DownloadHistoryTab) -> Unit,
) {
    Column {
        DownloadHistoryTitleBar(
            state = state,
            onBack = onBack,
            onExitSelection = onExitSelection,
            onEnterSelection = onEnterSelection,
            onSelectAll = onSelectAll,
            onClearTab = onClearTab,
        )
        DownloadHistoryTabs(
            tabs = tabs,
            selectedTab = state.selectedTab,
            onSelected = onSelected,
        )
    }
}

/** 当前 Tab 的记录总数，用于标题栏按钮可用性和清空弹窗数量。 */
internal val DownloadHistoryUiState.currentItemsCount: Int
    get() = when (selectedTab) {
        DownloadHistoryTab.Video -> videoCount
        DownloadHistoryTab.Image -> imageCount
        is DownloadHistoryTab.Extension -> extensionCounts[selectedTab.tabId] ?: 0
    }

/** 当前 Tab 是否包含进行中记录，用于清空确认文案。 */
internal val DownloadHistoryUiState.currentTabHasRunning: Boolean
    get() = when (selectedTab) {
        DownloadHistoryTab.Video -> videoRunningCount > 0
        DownloadHistoryTab.Image -> imageRunningCount > 0
        is DownloadHistoryTab.Extension -> false
    }
