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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.cla.clip.base.general.R
import com.cla.clip.feature.ad.api.AdPlacement
import com.cla.clip.master.ad.AdPagerPageState
import com.cla.clip.master.ad.rememberPageAdSession
import com.cla.clip.master.ui.widget.TitleBar
import com.cla.clip.master.ui.widget.TitleBarText
import com.cla.clip.master.ui.widget.ad.FixedListAdLayout
import com.cla.clip.master.ui.widget.ad.ListAdContentState
import kotlinx.coroutines.flow.Flow

/** 下载记录页 Tab 展示配置。 */
internal data class DownloadHistoryTabSpec(
    /** 内建下载历史分类；TorrentSearch 不再扩展宿主下载记录。 */
    val tab: DownloadHistoryTab,
    /** 当前 Tab 的资源化标题在页面层解析后的展示文本。 */
    val title: String,
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
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = stringResource(R.string.base_general_download_history_clear_current_tab),
                        tint = MaterialTheme.colorScheme.error
                    )
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
 * 固定广告位于横向 Pager 外，两个会话与当前稳定 Tab 的分页收集也由本容器持有。
 * 只在 settledPage 真正切换时更换业务分页和广告位置；横向拖动/回弹保留原会话，并延后广告布局变化。
 * 页面入口提供的分页 Flow 已受 STARTED 生命周期约束，广告会话另受 STARTED/RESUMED 门约束，两者并行启动。
 */
@Composable
internal fun DownloadHistoryPager(
    tabs: List<DownloadHistoryTabSpec>,
    // 页面层传入预览和确认覆盖状态，不将广告状态写入下载 ViewModel。
    adOverlayBlocked: Boolean,
    // 整页按压只暂停布局，不把按压当作隐藏广告的管理状态。
    adPointerPressed: Boolean,
    /** 直接读取根页面的按压状态，防止中间 Pager 将同步许可退化为旧快照。 */
    adPointerPressedNow: () -> Boolean = { adPointerPressed },
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
    onPreviewImage: (String) -> Unit,
) {
    // 视频页的访问与布局门复用共享规则，滑动不把原页冒充为已离开。
    val videoAdPageState = AdPagerPageState(
        page = tabs.indexOfFirst { it.tab == DownloadHistoryTab.Video },
        settledPage = pagerState.settledPage,
        scrolling = pagerState.isScrollInProgress,
    )
    // 图片页只有在真正停稳后才有新增请求许可，预组合邻页不取得资格。
    val imageAdPageState = AdPagerPageState(
        page = tabs.indexOfFirst { it.tab == DownloadHistoryTab.Image },
        settledPage = pagerState.settledPage,
        scrolling = pagerState.isScrollInProgress,
    )
    // 两个稳定访问所有者都留在 Pager 外；回弹不会因邻页组合/释放而丢有效素材。
    val videoAdSession = rememberPageAdSession(
        key = AdPlacement.VideoHistoryNative,
        visibleToUser = videoAdPageState.visibleToUser,
    )
    // 真实切到图片或返回图片才开启图片新访问，不依赖数据库首批结果。
    val imageAdSession = rememberPageAdSession(
        key = AdPlacement.ImageHistoryNative,
        visibleToUser = imageAdPageState.visibleToUser,
    )
    // 点击 Tab 的 ViewModel 选中态可能先于动画停稳，只按实际稳定页选择当前业务与广告。
    val settledTab = tabs[pagerState.settledPage].tab
    // 当前视频分页实例直接供广告状态和列表使用；不可见分类不调用 collect，Flow 已受页面生命周期约束。
    val pagedVideos = if (settledTab == DownloadHistoryTab.Video) {
        videoPagingFlow.collectAsLazyPagingItems()
    } else null
    // 图片同样只在稳定可见分类收集，不因横向拖动邻页而开始读缩略图。
    val pagedImages = if (settledTab == DownloadHistoryTab.Image) {
        imagePagingFlow.collectAsLazyPagingItems()
    } else null
    // 当前固定槽会话由稳定 Tab 选择，业务 refresh/append 不更换身份。
    val adSession = when (settledTab) {
        DownloadHistoryTab.Video -> videoAdSession
        DownloadHistoryTab.Image -> imageAdSession
    }
    // 独立广告单元随稳定分类切换，固定宿主不进入横向 Pager 页或业务加载/空态分支。
    val adPlacement = when (settledTab) {
        DownloadHistoryTab.Video -> AdPlacement.VideoHistoryNative
        DownloadHistoryTab.Image -> AdPlacement.ImageHistoryNative
    }
    // 点击 Tab 动画期间继续展示实际稳定页，不能因标题选择提前变化而隐藏固定广告。
    val adContentState = when (settledTab) {
        DownloadHistoryTab.Video -> pagedVideos?.let { videos ->
            ListAdContentState.from(
                hasRenderedItems = videos.itemSnapshotList.items.isNotEmpty(),
                showsLoading = videos.loadState.refresh is LoadState.Loading,
            )
        }
        // 图片原画面只有无旧批次时才变 loading；旧批次仍渲染时可继续呈现有效广告。
        DownloadHistoryTab.Image -> pagedImages?.let { images ->
            ListAdContentState.from(
                hasRenderedItems = images.itemSnapshotList.items.isNotEmpty(),
                showsLoading = images.loadState.refresh is LoadState.Loading && images.itemCount == 0,
            )
        }
    } ?: ListAdContentState.Empty
    // 旧页保留浏览内容但不借用目标分类的选择，两个表的相同主键也不能互相串选。
    val settledListState = DownloadHistoryPagerPolicy.listState(state, settledTab)
    // 事件发生时读取最新门，动画中的旧卡片不能操作已选中的另一分类。
    val businessActionsEnabled by rememberUpdatedState(
        DownloadHistoryPagerPolicy.canInteract(state.selectedTab, settledTab, pagerState.isScrollInProgress),
    )
    // 页面临时管理/覆盖状态只关闭请求与交互，不撤销广告资源资格。
    val adSuppressed = state.selectionMode || adOverlayBlocked
    // 横向拖动与整页按压只阻止尺寸变化；纵向滚动由各业务列表的锚点协调器补充。
    val adLayoutBlocked = adSuppressed || adPointerPressed || videoAdPageState.layoutBlocked
    // 会话事实在同次组合提交后同步；SDK 同帧发布仍直接读取根按压与 Pager 实时滚动。
    SideEffect {
        adSession.pointerPressed = adPointerPressed || pagerState.isScrollInProgress
        // 该闭包只读取页面 UI 交互，不把下载数据交给广告来源。
        adSession.pointerPressedNow = { adPointerPressedNow() || pagerState.isScrollInProgress }
    }

    FixedListAdLayout(
        session = adSession,
        placement = adPlacement,
        contentState = adContentState,
        blocked = adLayoutBlocked,
        modifier = Modifier.fillMaxSize(),
    ) { contentModifier ->
        // 共享固定区给出的剩余空间只分配给业务 Pager，广告不会随横向或纵向列表滑动。
        HorizontalPager(
            state = pagerState,
            modifier = contentModifier,
            // Pager 的 key 会进入 Android 保存状态，使用稳定字符串而不是不可写入 Bundle 的分类单例。
            key = { page -> DownloadHistoryPagerPolicy.pageKey(tabs[page].tab) },
        ) { page ->
            // 稳定页继续画已加载内容，邻页不预读；过渡中的业务动作由实时门暂停。
            if (page == pagerState.settledPage) {
                when (settledTab) {
                    DownloadHistoryTab.Video -> pagedVideos?.let { videos ->
                        VideoHistoryList(
                            adSession = adSession,
                            adContentState = adContentState,
                            adLayoutBlocked = adLayoutBlocked,
                            pagedVideos = videos,
                            listState = videoListState,
                            state = settledListState,
                            // 选择只发给停稳且与 ViewModel 同分类的记录。
                            onToggleSelected = { if (businessActionsEnabled) onToggleSelected(it) },
                            // 横向动画期间长按不能为目标分类选择旧页主键。
                            onEnterSelection = { if (businessActionsEnabled) onEnterSelection(it) },
                            // 旧页视频继续展示，停稳前暂停外部播放导航。
                            onOpenVideo = { if (businessActionsEnabled) onOpenVideo(it) },
                            // 重试副作用不能跨分类过渡触发。
                            onRetryVideo = { if (businessActionsEnabled) onRetryVideo(it) },
                        )
                    }
                    DownloadHistoryTab.Image -> pagedImages?.let { images ->
                        ImageHistoryList(
                            adSession = adSession,
                            adContentState = adContentState,
                            adLayoutBlocked = adLayoutBlocked,
                            pagedImages = images,
                            listState = imageListState,
                            state = settledListState,
                            // 图片主键选择同样受实际稳定页约束。
                            onToggleSelected = { if (businessActionsEnabled) onToggleSelected(it) },
                            // 动画结束前不切入旧页的管理模式。
                            onEnterSelection = { if (businessActionsEnabled) onEnterSelection(it) },
                            // 图片批次重试只能由已停稳的图片分类发出。
                            onRetryImage = { if (businessActionsEnabled) onRetryImage(it) },
                            // 过渡期间不开预览，避免弹层中断分类动画。
                            onPreviewImage = { if (businessActionsEnabled) onPreviewImage(it) },
                        )
                    }
                }
            }
        }
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
    }

/** 当前 Tab 是否包含进行中记录，用于清空确认文案。 */
internal val DownloadHistoryUiState.currentTabHasRunning: Boolean
    get() = when (selectedTab) {
        DownloadHistoryTab.Video -> videoRunningCount > 0
        DownloadHistoryTab.Image -> imageRunningCount > 0
    }
