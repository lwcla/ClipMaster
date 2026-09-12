package com.cla.clip.feature.torrent.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cla.clip.feature.torrent.search.input.TorrentSearchLaunchRequest
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.ui.browse.TorrentBrowseScreen
import com.cla.clip.feature.torrent.search.ui.browse.TorrentBrowseViewModel
import com.cla.clip.feature.torrent.search.ui.component.DestinationLifecycleEffect
import com.cla.clip.feature.torrent.search.ui.component.rememberDestinationActiveState
import com.cla.clip.feature.torrent.search.ui.details.TorrentDetailSelectionViewModel
import com.cla.clip.feature.torrent.search.ui.details.TorrentDetailsScreen
import com.cla.clip.feature.torrent.search.ui.details.TorrentDetailsViewModel
import com.cla.clip.feature.torrent.search.ui.search.TorrentSearchScreen
import com.cla.clip.feature.torrent.search.ui.search.TorrentSearchViewModel
import com.cla.clip.feature.torrent.search.ui.local.BookmarksScreen
import com.cla.clip.feature.torrent.search.ui.local.BookmarksViewModel
import com.cla.clip.feature.torrent.search.ui.local.LocalDataHomeScreen
import com.cla.clip.feature.torrent.search.ui.local.SearchHistoryScreen
import com.cla.clip.feature.torrent.search.ui.local.SearchHistoryViewModel
import com.cla.clip.feature.torrent.search.ui.local.ViewedTorrentsScreen
import com.cla.clip.feature.torrent.search.ui.local.ViewedTorrentsViewModel

/** TorrentSearch feature-local 导航根；只用稳定 Provider ID 作为详情路由参数。 */
@Composable
internal fun TorrentSearchApp(
    /** Activity 已安全解析的最新启动快照。 */
    launchRequest: TorrentSearchLaunchRequest,
    /** 独立 Activity 退出回调。 */
    onFinish: () -> Unit,
    /** 根容器外部布局约束。 */
    modifier: Modifier = Modifier,
    /** Activity 作用域搜索 ViewModel，路由重组不会重复创建。 */
    searchViewModel: TorrentSearchViewModel = hiltViewModel(),
    /** Activity 作用域内存候选，避免 URL、磁力或正文进入路由与 SavedState。 */
    selectionViewModel: TorrentDetailSelectionViewModel = hiltViewModel(),
) {
    /** navController 是 feature 内页面导航唯一入口。 */
    val navController = rememberNavController()

    /** 启动快照变化只更新预填文本，不触发 ViewModel 网络动作。 */
    LaunchedEffect(launchRequest) {
        searchViewModel.acceptLaunchRequest(launchRequest)
    }
    NavHost(
        navController = navController,
        startDestination = ROUTE_SEARCH,
        modifier = modifier,
    ) {
        composable(ROUTE_SEARCH) {
            /** lifecycleOwner 是当前搜索 NavBackStackEntry。 */
            val lifecycleOwner = LocalLifecycleOwner.current
            /** uiState 只在当前目的地至少 STARTED 时收集。 */
            val uiState by searchViewModel.uiState.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
            DestinationLifecycleEffect(searchViewModel::setScreenActive)
            TorrentSearchScreen(
                state = uiState,
                onQueryChange = searchViewModel::updateQuery,
                onCategoryChange = searchViewModel::updateCategory,
                onSortChange = searchViewModel::updateSort,
                onSearch = searchViewModel::submitSearch,
                onStop = searchViewModel::stopSearch,
                onBrowse = { navController.navigate(ROUTE_BROWSE) },
                onLocalData = { navController.navigate(ROUTE_LOCAL_DATA) },
                onResultClick = { /** torrent 是待保存在 Activity 内存的搜索结果。 */ torrent ->
                    openDetails(navController, selectionViewModel, torrent)
                },
                onSafeModeChange = searchViewModel::requestSafeModeEnabled,
                onConfirmDisclosure = searchViewModel::confirmMultiSourceDisclosure,
                onDismissDisclosure = searchViewModel::dismissMultiSourceDisclosure,
                onConfirmDisableSafeMode = searchViewModel::confirmDisableSafeMode,
                onDismissDisableSafeMode = searchViewModel::dismissDisableSafeModeConfirmation,
                onBack = onFinish,
            )
        }
        composable(ROUTE_LOCAL_DATA) {
            LocalDataHomeScreen(
                onBookmarks = { navController.navigate(ROUTE_BOOKMARKS) },
                onHistory = { navController.navigate(ROUTE_HISTORY) },
                onViewed = { navController.navigate(ROUTE_VIEWED) },
                onBack = navController::popBackStack,
            )
        }
        composable(ROUTE_BOOKMARKS) {
            /** bookmarksViewModel 属于当前书签 NavBackStackEntry。 */
            val bookmarksViewModel = hiltViewModel<BookmarksViewModel>()
            /** lifecycleOwner 是当前书签 NavBackStackEntry。 */
            val lifecycleOwner = LocalLifecycleOwner.current
            /** uiState 只在书签目的地至少 STARTED 时收集。 */
            val uiState by bookmarksViewModel.uiState.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
            DestinationLifecycleEffect(bookmarksViewModel::setScreenActive)
            BookmarksScreen(
                state = uiState,
                onOpenDetails = { /** torrent 是从书签快照映射的完整内存候选。 */ torrent ->
                    openDetails(navController, selectionViewModel, torrent)
                },
                onRemove = bookmarksViewModel::remove,
                onRequestClear = bookmarksViewModel::requestClear,
                onConfirmClear = bookmarksViewModel::confirmClear,
                onDismissClear = bookmarksViewModel::dismissClear,
                onImportDocument = bookmarksViewModel::importFrom,
                onExportDocument = bookmarksViewModel::exportTo,
                onBack = navController::popBackStack,
            )
        }
        composable(ROUTE_HISTORY) {
            /** historyViewModel 属于当前搜索历史 NavBackStackEntry。 */
            val historyViewModel = hiltViewModel<SearchHistoryViewModel>()
            /** lifecycleOwner 是当前搜索历史 NavBackStackEntry。 */
            val lifecycleOwner = LocalLifecycleOwner.current
            /** uiState 只在搜索历史目的地至少 STARTED 时收集。 */
            val uiState by historyViewModel.uiState.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
            DestinationLifecycleEffect(historyViewModel::setScreenActive)
            SearchHistoryScreen(
                state = uiState,
                onReplay = { /** query 只交给搜索 ViewModel 内存待处理，不在历史页联网。 */ query ->
                    searchViewModel.requestHistoryReplay(query)
                    navController.popBackStack(ROUTE_SEARCH, false)
                },
                onRemove = historyViewModel::remove,
                onRequestClear = historyViewModel::requestClear,
                onConfirmClear = historyViewModel::confirmClear,
                onDismissClear = historyViewModel::dismissClear,
                onBack = navController::popBackStack,
            )
        }
        composable(ROUTE_VIEWED) {
            /** viewedViewModel 属于当前已查看 NavBackStackEntry。 */
            val viewedViewModel = hiltViewModel<ViewedTorrentsViewModel>()
            /** lifecycleOwner 是当前已查看 NavBackStackEntry。 */
            val lifecycleOwner = LocalLifecycleOwner.current
            /** uiState 只在已查看目的地至少 STARTED 时收集。 */
            val uiState by viewedViewModel.uiState.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
            DestinationLifecycleEffect(viewedViewModel::setScreenActive)
            ViewedTorrentsScreen(
                state = uiState,
                onRequestClear = viewedViewModel::requestClear,
                onConfirmClear = viewedViewModel::confirmClear,
                onDismissClear = viewedViewModel::dismissClear,
                onBack = navController::popBackStack,
            )
        }
        composable(ROUTE_BROWSE) {
            /** browseViewModel 属于当前 Browse NavBackStackEntry。 */
            val browseViewModel = hiltViewModel<TorrentBrowseViewModel>()
            /** lifecycleOwner 是当前 Browse NavBackStackEntry。 */
            val lifecycleOwner = LocalLifecycleOwner.current
            /** uiState 只在 Browse 目的地至少 STARTED 时收集。 */
            val uiState by browseViewModel.uiState.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
            DestinationLifecycleEffect(browseViewModel::setScreenActive)
            TorrentBrowseScreen(
                state = uiState,
                onModeChange = browseViewModel::updateMode,
                onCategoryChange = browseViewModel::updateCategory,
                onRetry = browseViewModel::reload,
                onResultClick = { /** torrent 是待保存在 Activity 内存的浏览结果。 */ torrent ->
                    openDetails(navController, selectionViewModel, torrent)
                },
                onBack = navController::popBackStack,
            )
        }
        composable("$ROUTE_DETAILS/{$ARG_PROVIDER_ID}") { /** backStackEntry 提供唯一稳定 Provider ID 参数。 */ backStackEntry ->
            /** detailsViewModel 属于当前 Details NavBackStackEntry。 */
            val detailsViewModel = hiltViewModel<TorrentDetailsViewModel>()
            /** lifecycleOwner 是当前 Details NavBackStackEntry。 */
            val lifecycleOwner = LocalLifecycleOwner.current
            /** uiState 只在 Details 目的地至少 STARTED 时收集。 */
            val uiState by detailsViewModel.uiState.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
            /** selectedTorrent 是 Activity 内存候选，不经过路由或 SavedState。 */
            val selectedTorrent by selectionViewModel.selected.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
            /** rawProviderId 是路由中的有限稳定 ID 文本。 */
            val rawProviderId = backStackEntry.arguments?.getString(ARG_PROVIDER_ID)
            /** routeProviderId 对非法深链参数返回 null，不抛到 Composition。 */
            val routeProviderId = runCatching { rawProviderId?.let(::ProviderId) }.getOrNull()
            LaunchedEffect(selectedTorrent, routeProviderId, detailsViewModel) {
                detailsViewModel.setCandidate(selectedTorrent, routeProviderId)
            }
            /** destinationActiveState 直接来自详情 NavBackStackEntry，STOP 时即刻释放图片子 Composition。 */
            val destinationActiveState = rememberDestinationActiveState(detailsViewModel::setScreenActive)
            TorrentDetailsScreen(
                state = uiState,
                imagesActive = destinationActiveState.value,
                onRetry = detailsViewModel::retry,
                onToggleBookmark = detailsViewModel::toggleBookmark,
                onBack = navController::popBackStack,
            )
        }
    }
}

/** 保存完整候选到 Activity 内存并只把稳定 Provider ID 放入导航路径。 */
private fun openDetails(
    /** feature-local 导航控制器。 */
    navController: NavHostController,
    /** Activity 作用域候选持有者。 */
    selectionViewModel: TorrentDetailSelectionViewModel,
    /** 用户点击的搜索或浏览结果。 */
    torrent: Torrent,
) {
    selectionViewModel.select(torrent)
    navController.navigate("$ROUTE_DETAILS/${torrent.providerId.value}")
}

/** 搜索首页稳定内部路由。 */
private const val ROUTE_SEARCH = "search"
/** Latest/Top 浏览稳定内部路由。 */
private const val ROUTE_BROWSE = "browse"
/** 原生详情稳定内部路由前缀。 */
private const val ROUTE_DETAILS = "details"
/** 本地数据轻量主页稳定内部路由。 */
private const val ROUTE_LOCAL_DATA = "local_data"
/** 书签独立目的地稳定内部路由。 */
private const val ROUTE_BOOKMARKS = "bookmarks"
/** 搜索历史独立目的地稳定内部路由。 */
private const val ROUTE_HISTORY = "history"
/** 已查看独立目的地稳定内部路由。 */
private const val ROUTE_VIEWED = "viewed"
/** 详情路由唯一参数，只允许 ProviderId 构造器批准的文本。 */
private const val ARG_PROVIDER_ID = "providerId"
