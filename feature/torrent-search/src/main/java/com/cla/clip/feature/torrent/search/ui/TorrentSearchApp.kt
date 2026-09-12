package com.cla.clip.feature.torrent.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cla.clip.feature.torrent.search.input.TorrentSearchLaunchRequest
import com.cla.clip.feature.torrent.search.ui.search.TorrentSearchScreen
import com.cla.clip.feature.torrent.search.ui.search.TorrentSearchViewModel

/** TorrentSearch feature-local 导航根；后续页面复用同一 Activity 和路由栈。 */
@Composable
internal fun TorrentSearchApp(
    /** Activity 已安全解析的最新启动快照。 */
    launchRequest: TorrentSearchLaunchRequest,
    /** 独立 Activity 退出回调。 */
    onFinish: () -> Unit,
    /** 根容器外部布局约束。 */
    modifier: Modifier = Modifier,
    /** Activity 作用域 Hilt ViewModel，搜索路由重组不会重复创建。 */
    viewModel: TorrentSearchViewModel = hiltViewModel(),
) {
    /** navController 是 feature 内页面导航唯一入口。 */
    val navController = rememberNavController()

    /** 启动快照变化只更新预填文本，不触发 ViewModel 网络动作。 */
    LaunchedEffect(launchRequest) {
        viewModel.acceptLaunchRequest(launchRequest)
    }
    NavHost(
        navController = navController,
        startDestination = ROUTE_SEARCH,
        modifier = modifier,
    ) {
        composable(ROUTE_SEARCH) {
            /** lifecycleOwner 是当前搜索 NavBackStackEntry，而不是始终可见的 Activity 根。 */
            val lifecycleOwner = LocalLifecycleOwner.current
            /** uiState 只在当前目的地至少 STARTED 时收集。 */
            val uiState by viewModel.uiState.collectAsStateWithLifecycle(lifecycleOwner = lifecycleOwner)
            /** STARTED/STOPPED 明确控制当前搜索目的地的网络许可。 */
            DisposableEffect(lifecycleOwner, viewModel) {
                /** observer 把目的地生命周期事件映射为 ViewModel 可见性入口。 */
                val observer = LifecycleEventObserver { /** source 仅满足观察者签名，event 是当前目的地生命周期变化。 */ _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> viewModel.setScreenActive(true)
                        Lifecycle.Event.ON_STOP -> viewModel.setScreenActive(false)
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    viewModel.setScreenActive(true)
                }
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    viewModel.setScreenActive(false)
                }
            }
            TorrentSearchScreen(
                state = uiState,
                onQueryChange = viewModel::updateQuery,
                onCategoryChange = viewModel::updateCategory,
                onSortChange = viewModel::updateSort,
                onSearch = viewModel::submitSearch,
                onStop = viewModel::stopSearch,
                onSafeModeChange = viewModel::requestSafeModeEnabled,
                onConfirmDisclosure = viewModel::confirmMultiSourceDisclosure,
                onDismissDisclosure = viewModel::dismissMultiSourceDisclosure,
                onConfirmDisableSafeMode = viewModel::confirmDisableSafeMode,
                onDismissDisableSafeMode = viewModel::dismissDisableSafeModeConfirmation,
                onBack = onFinish,
            )
        }
    }
}

/** 当前首个 feature-local 路由；后续切片只在此导航根追加稳定目的地。 */
private const val ROUTE_SEARCH = "search"
