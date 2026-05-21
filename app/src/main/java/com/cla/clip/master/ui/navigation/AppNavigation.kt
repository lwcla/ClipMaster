package com.cla.clip.master.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.cla.clip.master.ui.page.backup.BackupPage
import com.cla.clip.master.ui.page.backup.BackupRestoreFlowPage
import com.cla.clip.master.ui.page.backup.BackupRestoreFlowState
import com.cla.clip.master.ui.page.backup.BackupRestoreVm
import com.cla.clip.master.ui.page.detail.DetailPage
import com.cla.clip.master.ui.page.download.DownloadHistoryPage
import com.cla.clip.master.ui.page.image.ImageExtractPage
import com.cla.clip.master.ui.page.list.ClipListPage
import com.cla.clip.master.ui.page.list.FoldedClipListPage
import com.cla.clip.master.ui.page.main.MainPage
import com.cla.clip.master.ui.page.mine.MinePage
import com.cla.clip.master.ui.page.recycle.RecycleBinPage
import com.cla.clip.master.ui.page.search.SearchPage
import com.cla.clip.master.ui.page.video.VideoDownloadPage
import com.cla.clip.master.ui.page.video.VideoExtractPage

/** Compose Navigation 页面切换进入动画时长，单位毫秒；比上一版略拉长，让左进右出的页面层级感更完整。 */
private const val NAV_PAGE_ENTER_DURATION_MS = 260

/** Compose Navigation 页面切换退出动画时长，单位毫秒；与进入动画接近，保证左进右出效果完整。 */
private const val NAV_PAGE_EXIT_DURATION_MS = 260

/**
 * 应用主导航图。
 *
 * 统一注册首页、列表、搜索、详情、视频/图片提取、视频下载和我的页面的路由，并为子页面提供通用的返回和跳转回调。
 * 下载页返回时会特殊处理导航栈，避免用户从下载完成页返回到已经完成使命的视频提取页。
 */
@Composable
fun AppNavigation(navController: NavHostController) {
    val appSharedViewModel = viewModel<AppSharedViewModel>()

    /** 子页面统一使用的跳转回调，保持所有页面都通过类型安全 Route 导航。 */
    val onNavigate = { route: Route ->
        navController.navigate(route)
    }

    /** 子页面统一返回回调，默认弹出当前目的地。 */
    val onBack = {
        navController.popBackStack()
        Unit
    }

    NavHost(
        navController = navController,
        startDestination = MainRoute,
        // 使用 Compose Navigation 官方容器滑动转场：前进时右侧页面进入、当前页向左退出；返回时反向。
        // 这里只负责页面切换动画，不额外处理点击穿透或禁用退出页动画。
        enterTransition = { forwardEnterTransition() },
        exitTransition = { forwardExitTransition() },
        popEnterTransition = { backEnterTransition() },
        popExitTransition = { backExitTransition() },
    ) {
        // 主页
        composable<MainRoute> {
            MainPage(
                onNavigate = onNavigate
            )
        }

        // 剪贴数据列表页
        composable<ClipListRoute> {
            ClipListPage(
                onNavigate = onNavigate
            )
        }

        // 剪贴搜索页
        composable<SearchRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<SearchRoute>()
            SearchPage(
                scope = route.scope,
                onBack = onBack,
                onNavigate = onNavigate
            )
        }

        // 详情页
        composable<DetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<DetailRoute>()
            DetailPage(
                clipId = route.clipId,
                onBack = onBack,
                onNavigate = onNavigate
            )
        }

        // 视频提取页
        composable<VideoExtractRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<VideoExtractRoute>()
            VideoExtractPage(
                pageUrl = route.url,
                pageName = route.name,
                onBack = onBack,
                onNavigate = onNavigate
            )
        }

        // 视频下载页
        composable<VideoDownloadRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<VideoDownloadRoute>()
            VideoDownloadPage(
                taskId = route.taskId,
                onBack = {
                    // inclusive = true → 连 VideoExtractRoute 自身也从栈里移除
                    // 等效于：返回时跳过 VideoExtractPage，直接回到它的上一级
                    // inclusive = true 的含义：弹出到 VideoExtractRoute 这一层，并且把它自身也一起弹出，最终停在 VideoExtractRoute 的上一个目标（即你从哪里进的视频提取页，就回哪里）
                    val poppedExtract = navController.popBackStack<VideoExtractRoute>(inclusive = true)
                    // 在首页从通知栏打开视频下载结果页，这个时候返回操作会失效，需要在这里判断
                    if (!poppedExtract) {
                        navController.popBackStack() // 从通知进来时，回到 MainPage
                    }
                }
            )
        }

        // 图片提取页
        composable<ImageExtractRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ImageExtractRoute>()
            ImageExtractPage(
                pageUrl = route.url,
                pageName = route.name,
                onBack = onBack
            )
        }

        // 下载记录页
        composable<DownloadHistoryRoute> {
            DownloadHistoryPage(
                onBack = onBack,
                onNavigate = onNavigate
            )
        }

        // 折叠数据页
        composable<FoldedClipsRoute> {
            FoldedClipListPage(
                onBack = onBack,
                onNavigate = onNavigate
            )
        }

        // 回收站
        composable<RecycleBinRoute> {
            RecycleBinPage(
                onBack = onBack
            )
        }

        // 备份与恢复
        composable<BackupRoute> {
            BackupPage(
                onBack = onBack,
                onNavigate = onNavigate,
                appSharedViewModel = appSharedViewModel
            )
        }

        // 备份恢复流程页
        composable<BackupRestoreRoute> {
            BackupRestoreFlowRoute(
                appSharedViewModel = appSharedViewModel,
                onBack = onBack
            )
        }

        composable<MineRoute> {
            MinePage(
                onNavigate = onNavigate
            )
        }
    }
}

/**
 * 备份恢复流程的真实导航页面。
 *
 * 备份页只把一次性打开请求写入 Activity 级临时 ViewModel；恢复页接管后立即清理请求，避免长期持有备份页状态。
 */
@Composable
private fun BackupRestoreFlowRoute(
    appSharedViewModel: AppSharedViewModel,
    onBack: () -> Unit,
    restoreVm: BackupRestoreVm = hiltViewModel(),
) {
    val request by appSharedViewModel.backupRestoreRequest.collectAsStateWithLifecycle()
    val state by restoreVm.uiState.collectAsStateWithLifecycle()
    val restoreFlow = state.restoreFlow
    LaunchedEffect(request?.requestId) {
        request?.let {
            restoreVm.startFromRequest(it)
            appSharedViewModel.clearBackupRestoreRequest(it.requestId)
        }
    }
    if (restoreFlow is BackupRestoreFlowState.Hidden) {
        LaunchedEffect(request) {
            if (request == null) onBack()
        }
        return
    }
    BackupRestoreFlowPage(
        state = restoreFlow,
        mediaRelocation = state.mediaRelocation,
        onBack = {
            restoreVm.dismissRestoreFlow()
            appSharedViewModel.clearBackupRestoreRequest()
            onBack()
        },
        onForceBack = {
            restoreVm.forceCloseRestoreFlow()
            appSharedViewModel.clearBackupRestoreRequest()
            onBack()
        },
        onRestore = restoreVm::restoreSelectedBackup,
        onEstimateMedia = restoreVm::estimateMediaRelocation,
        onMediaPermissionResult = restoreVm::onMediaRelocationPermissionResult,
        onStartMediaScan = restoreVm::startMediaRelocationScan
    )
}

/**
 * 前进导航进入动画。
 *
 * `SlideDirection.Left` 表示内容整体向左运动，因此新页面会从右侧滑入，符合进入下一层页面的层级感。
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.forwardEnterTransition(): EnterTransition {
    return slideIntoContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Left,
        animationSpec = tween(
            durationMillis = NAV_PAGE_ENTER_DURATION_MS,
            easing = FastOutSlowInEasing
        )
    ) + fadeIn(
        animationSpec = tween(durationMillis = NAV_PAGE_ENTER_DURATION_MS)
    )
}

/**
 * 前进导航退出动画。
 *
 * 当前页面随层级推进向左退出，和右侧新页面进入组成完整的左进右出页面切换。
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.forwardExitTransition(): ExitTransition {
    return slideOutOfContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Left,
        animationSpec = tween(
            durationMillis = NAV_PAGE_EXIT_DURATION_MS,
            easing = FastOutLinearInEasing
        )
    ) + fadeOut(
        animationSpec = tween(durationMillis = NAV_PAGE_EXIT_DURATION_MS)
    )
}

/**
 * 返回导航进入动画。
 *
 * 返回上一层时，上一页从左侧进入，与前进方向形成明确反向关系。
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.backEnterTransition(): EnterTransition {
    return slideIntoContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Right,
        animationSpec = tween(
            durationMillis = NAV_PAGE_ENTER_DURATION_MS,
            easing = FastOutSlowInEasing
        )
    ) + fadeIn(
        animationSpec = tween(durationMillis = NAV_PAGE_ENTER_DURATION_MS)
    )
}

/**
 * 返回导航退出动画。
 *
 * 当前二级页向右退出，和上一层从左侧进入组成完整的返回方向转场。
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.backExitTransition(): ExitTransition {
    return slideOutOfContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Right,
        animationSpec = tween(
            durationMillis = NAV_PAGE_EXIT_DURATION_MS,
            easing = FastOutLinearInEasing
        )
    ) + fadeOut(
        animationSpec = tween(durationMillis = NAV_PAGE_EXIT_DURATION_MS)
    )
}
