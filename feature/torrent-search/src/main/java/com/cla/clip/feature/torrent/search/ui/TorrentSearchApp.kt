package com.cla.clip.feature.torrent.search.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cla.clip.feature.torrent.search.input.TorrentSearchLaunchRequest
import com.cla.clip.feature.torrent.search.R
import com.cla.clip.feature.torrent.search.cloudflare.CloudflareChallengeScreen
import com.cla.clip.feature.torrent.search.cloudflare.CloudflareChallengeUnavailableScreen
import com.cla.clip.feature.torrent.search.cloudflare.CloudflareChallengeViewModel
import com.cla.clip.feature.torrent.search.data.document.AndroidTorrentDocumentTarget
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
import com.cla.clip.feature.torrent.search.ui.settings.TorrentProviderManagementScreen
import com.cla.clip.feature.torrent.search.ui.settings.TorrentSettingsScreen
import com.cla.clip.feature.torrent.search.ui.settings.TorrentSettingsViewModel
import com.cla.clip.feature.torrent.search.ui.local.BookmarksScreen
import com.cla.clip.feature.torrent.search.ui.local.BookmarksViewModel
import com.cla.clip.feature.torrent.search.ui.local.LocalDataHomeScreen
import com.cla.clip.feature.torrent.search.ui.local.SearchHistoryScreen
import com.cla.clip.feature.torrent.search.ui.local.SearchHistoryViewModel
import com.cla.clip.feature.torrent.search.ui.local.ViewedTorrentsScreen
import com.cla.clip.feature.torrent.search.ui.local.ViewedTorrentsViewModel
import com.cla.clip.feature.torrent.search.system.AndroidTorrentExternalActionGateway
import com.cla.clip.feature.torrent.search.system.TorrentExternalActionFactory
import com.cla.clip.feature.torrent.search.system.TorrentExternalActionResult

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
                onSettings = { navController.navigate(ROUTE_SETTINGS) },
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
        composable(ROUTE_SETTINGS) {
            /** settingsViewModel 属于当前设置 NavBackStackEntry。 */
            val settingsViewModel = hiltViewModel<TorrentSettingsViewModel>()
            /** lifecycleOwner 是当前设置 NavBackStackEntry。 */
            val lifecycleOwner = LocalLifecycleOwner.current
            /** uiState 只在设置目的地至少 STARTED 时收集。 */
            val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
            TorrentSettingsScreen(
                state = uiState,
                onProviders = { navController.navigate(ROUTE_PROVIDERS) },
                onSafeModeChange = settingsViewModel::setSafeModeEnabled,
                onSortChange = settingsViewModel::setSort,
                onDnsModeChange = settingsViewModel::setDnsMode,
                onResetDisclosure = settingsViewModel::resetMultiSourceDisclosure,
                onClearError = settingsViewModel::clearError,
                onBack = navController::popBackStack,
            )
        }
        composable(ROUTE_PROVIDERS) {
            /** settingsViewModel 属于当前 Provider 管理 NavBackStackEntry。 */
            val settingsViewModel = hiltViewModel<TorrentSettingsViewModel>()
            /** lifecycleOwner 是当前 Provider 管理 NavBackStackEntry。 */
            val lifecycleOwner = LocalLifecycleOwner.current
            /** uiState 只在 Provider 管理目的地至少 STARTED 时收集。 */
            val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
            TorrentProviderManagementScreen(
                state = uiState,
                onProviderEnabledChange = settingsViewModel::setProviderEnabled,
                onEnableAll = settingsViewModel::enableAllProviders,
                onDisableAll = settingsViewModel::disableAllProviders,
                onReset = settingsViewModel::resetProvidersToDefault,
                onNewTorznabConfigId = settingsViewModel::newTorznabConfigId,
                onSaveTorznab = settingsViewModel::saveTorznabConfig,
                onDeleteTorznab = settingsViewModel::deleteTorznabConfig,
                onOpenChallenge = { /** providerId 是已知受保护来源的稳定身份。 */ providerId ->
                    navController.navigate("$ROUTE_CLOUDFLARE/${providerId.value}")
                },
                onClearError = settingsViewModel::clearError,
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
            /** context 仅在用户点击时执行系统动作或展示固定结果。 */
            val context = LocalContext.current
            /** externalActionGateway 以应用 Context 复用，不持有当前 Activity。 */
            val externalActionGateway = remember(context.applicationContext) { AndroidTorrentExternalActionGateway(context.applicationContext) }
            /** chooserTitle 是系统分享选择器的本地化标题。 */
            val chooserTitle = stringResource(R.string.torrent_search_share_chooser_title)
            /** createTorrentDocumentLauncher 只接收本次系统返回 URI 并立即包装为一次性目标。 */
            val createTorrentDocumentLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/x-bittorrent"),
            ) { /** uri 是本次系统文档选择结果，取消时为空。 */ uri ->
                if (uri == null) detailsViewModel.cancelTorrentSave()
                else detailsViewModel.saveTorrentDownload(AndroidTorrentDocumentTarget(uri))
            }
            LaunchedEffect(uiState.downloadRequestId, uiState.downloadPhase) {
                /** suggestedFileName 只在已校验字节等待目标时存在。 */
                val suggestedFileName = uiState.downloadSuggestedFileName
                if (uiState.downloadPhase == com.cla.clip.feature.torrent.search.ui.details.TorrentDownloadPhase.AWAITING_DESTINATION && suggestedFileName != null) {
                    createTorrentDocumentLauncher.launch(suggestedFileName)
                }
            }
            TorrentDetailsScreen(
                state = uiState,
                imagesActive = destinationActiveState.value,
                onRetry = detailsViewModel::retry,
                onToggleBookmark = detailsViewModel::toggleBookmark,
                onOpenMagnet = {
                    /** action 只从已校验详情的合法 btih magnet 构造。 */
                    val action = uiState.details?.magnetUri?.let(TorrentExternalActionFactory::openMagnet)
                    showExternalActionResult(context, action?.let { /** validAction 是工厂批准的磁力动作。 */ validAction -> externalActionGateway.execute(validAction, chooserTitle) })
                },
                onShareMagnet = {
                    /** action 只从当前详情非空磁力文本构造。 */
                    val action = uiState.details?.magnetUri?.let(TorrentExternalActionFactory::shareText)
                    showExternalActionResult(context, action?.let { /** validAction 是工厂批准的分享动作。 */ validAction -> externalActionGateway.execute(validAction, chooserTitle) })
                },
                onCopyMagnet = {
                    /** action 只从当前详情非空磁力文本构造。 */
                    val action = uiState.details?.magnetUri?.let(TorrentExternalActionFactory::copyText)
                    showExternalActionResult(context, action?.let { /** validAction 是工厂批准的复制动作。 */ validAction -> externalActionGateway.execute(validAction, chooserTitle) }, successMessage = R.string.torrent_search_copy_success)
                },
                onDownloadTorrent = detailsViewModel::requestTorrentDownload,
                onBack = navController::popBackStack,
            )
        }
        composable("$ROUTE_CLOUDFLARE/{$ARG_PROVIDER_ID}") { /** backStackEntry 只提供稳定 Provider ID 文本。 */ backStackEntry ->
            /** challengeViewModel 只持有固定 Provider 注册表，不持有 WebView。 */
            val challengeViewModel = hiltViewModel<CloudflareChallengeViewModel>()
            /** rawProviderId 是挑战路由中的稳定 ID 候选。 */
            val rawProviderId = backStackEntry.arguments?.getString(ARG_PROVIDER_ID)
            /** providerId 对非法深链参数返回 null。 */
            val providerId = runCatching { rawProviderId?.let(::ProviderId) }.getOrNull()
            /** target 只有已知且声明 Cloudflare 保护的 Provider 才能解析。 */
            val target = providerId?.let(challengeViewModel::resolve)
            if (target == null) CloudflareChallengeUnavailableScreen(navController::popBackStack)
            else CloudflareChallengeScreen(target, navController::popBackStack)
        }
    }
}

/** 用固定 Toast 告知系统动作失败或可选成功，不回显磁力与异常正文。 */
private fun showExternalActionResult(
    /** 当前页面 Context 只用于短提示。 */
    context: Context,
    /** 平台固定结果；null 表示输入未通过工厂校验。 */
    result: TorrentExternalActionResult?,
    /** 只有复制动作需要显式成功提示。 */
    successMessage: Int? = null,
) {
    /** messageResource 根据固定结果选择本地资源。 */
    val messageResource = when (result) {
        TorrentExternalActionResult.SUCCESS -> successMessage
        TorrentExternalActionResult.NO_HANDLER -> R.string.torrent_search_external_action_no_handler
        TorrentExternalActionResult.FAILED,
        null,
        -> R.string.torrent_search_external_action_failed
    }
    if (messageResource != null) Toast.makeText(context, messageResource, Toast.LENGTH_SHORT).show()
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
/** TorrentSearch 独立设置稳定内部路由。 */
private const val ROUTE_SETTINGS = "settings"
/** Provider 与 Torznab 管理稳定内部路由。 */
private const val ROUTE_PROVIDERS = "providers"
/** Cloudflare 挑战页稳定内部路由前缀，参数只携带 Provider ID。 */
private const val ROUTE_CLOUDFLARE = "cloudflare"
/** 详情路由唯一参数，只允许 ProviderId 构造器批准的文本。 */
private const val ARG_PROVIDER_ID = "providerId"
