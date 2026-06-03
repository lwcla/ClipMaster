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
import kotlinx.coroutines.flow.StateFlow
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.cla.clip.base.general.utils.logD
import com.cla.clip.feature.ad.api.AdSourceEntry
import com.cla.clip.feature.ad.api.AdSourceSelector
import com.cla.clip.feature.magnet.api.MagnetFeatureEntry
import com.cla.clip.master.ad.DetailAdSensitivityPolicy
import com.cla.clip.master.ui.page.backup.BackupMediaRelocationPage
import com.cla.clip.master.ui.page.backup.BackupPage
import com.cla.clip.master.ui.page.backup.BackupRestoreFlowPage
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

private const val TAG = "AppNavigation"

/**
 * 应用主导航图。
 *
 * 统一注册首页、列表、搜索、详情、视频/图片提取、视频下载和我的页面的路由，并为子页面提供通用的返回和跳转回调。
 * 下载页返回时会特殊处理导航栈，避免用户从下载完成页返回到已经完成使命的视频提取页。
 */
@Composable
fun AppNavigation(
    navController: NavHostController,
    magnetFeatures: Set<MagnetFeatureEntry>,
    adSources: Set<AdSourceEntry>,
    adSourceSelector: AdSourceSelector,
    activeAdSourceIdFlow: StateFlow<String>,
    adsGlobalEnabledFlow: StateFlow<Boolean>,
    adConsentStateFlow: StateFlow<String>,
    adPrivacyPolicyVersionFlow: StateFlow<String>,
    adDisabledSourceIdsFlow: StateFlow<Set<String>>,
    isMainProcess: Boolean,
    detailAdSensitivityPolicy: DetailAdSensitivityPolicy,
    onDisableAdSource: (String) -> Unit,
) {
    /** 子页面统一使用的跳转回调，保持所有页面都通过类型安全 Route 导航。 */
    val onNavigate = { route: Route ->
        navController.navigate(route) {
            launchSingleTop = route is BackupMediaRelocationRoute
        }
    }

    /** 子页面统一返回回调，默认弹出当前目的地。 */
    val onBack = {
        navController.popBackStack()
        Unit
    }

    NavHost(
        navController = navController,
        startDestination = MainRoute(),
        // 使用 Compose Navigation 官方容器滑动转场：前进时右侧页面进入、当前页向左退出；返回时反向。
        // 这里只负责页面切换动画，不额外处理点击穿透或禁用退出页动画。
        enterTransition = { forwardEnterTransition() },
        exitTransition = { forwardExitTransition() },
        popEnterTransition = { backEnterTransition() },
        popExitTransition = { backExitTransition() },
    ) {
        // 主页
        composable<MainRoute> { backStackEntry ->
            /** 首页路由参数；当前只用来决定初始 Tab。 */
            val route = backStackEntry.toRoute<MainRoute>()
            MainPage(
                initialTab = route.initialTab,
                onNavigate = onNavigate,
                magnetFeatures = magnetFeatures,
                onOpenMagnetSearch = { feature, query -> feature.openSearch(navController, query) }
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
            /** 搜索页路由参数；包含搜索范围。 */
            val route = backStackEntry.toRoute<SearchRoute>()
            SearchPage(
                scope = route.scope,
                onNavigate = onNavigate
            )
        }

        // 详情页
        composable<DetailRoute> { backStackEntry ->
            /** 详情页路由参数；包含要打开的剪贴 id。 */
            val route = backStackEntry.toRoute<DetailRoute>()
            DetailPage(
                clipId = route.clipId,
                onBack = onBack,
                onNavigate = onNavigate,
                magnetFeatures = magnetFeatures,
                onOpenMagnetSearch = { feature, query -> feature.openSearch(navController, query) },
                adSources = adSources,
                adSourceSelector = adSourceSelector,
                activeAdSourceIdFlow = activeAdSourceIdFlow,
                adsGlobalEnabledFlow = adsGlobalEnabledFlow,
                adConsentStateFlow = adConsentStateFlow,
                adPrivacyPolicyVersionFlow = adPrivacyPolicyVersionFlow,
                adDisabledSourceIdsFlow = adDisabledSourceIdsFlow,
                isMainProcess = isMainProcess,
                detailAdSensitivityPolicy = detailAdSensitivityPolicy,
                onDisableAdSource = onDisableAdSource,
            )
        }

        // 视频提取页
        composable<VideoExtractRoute> { backStackEntry ->
            /** 视频提取页路由参数；包含页面 URL 和标题。 */
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
            /** 视频下载页路由参数；包含下载任务 id。 */
            val route = backStackEntry.toRoute<VideoDownloadRoute>()
            VideoDownloadPage(
                taskId = route.taskId,
                onBack = {
                    // inclusive = true → 连 VideoExtractRoute 自身也从栈里移除
                    // 等效于：返回时跳过 VideoExtractPage，直接回到它的上一级
                    // inclusive = true 的含义：弹出到 VideoExtractRoute 这一层，并且把它自身也一起弹出，最终停在 VideoExtractRoute 的上一个目标（即你从哪里进的视频提取页，就回哪里）
                    /** 是否成功从导航栈中移除了视频提取链路。 */
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
            /** 图片提取页路由参数；包含原页面 URL 和标题。 */
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
                onNavigate = onNavigate
            )
        }

        // 备份恢复流程页
        composable<BackupRestoreRoute> {
            BackupRestoreFlowPage(
                onBack = onBack,
                onNavigate = onNavigate,
                magnetFeatures = magnetFeatures
            )
        }

        // 恢复本地媒体关联页
        composable<BackupMediaRelocationRoute> { backStackEntry ->
            /** 媒体重定位页路由参数；包含恢复任务 id。 */
            val route = backStackEntry.toRoute<BackupMediaRelocationRoute>()
            BackupMediaRelocationPage(
                restoreTaskId = route.restoreTaskId,
                onBack = onBack,
                onBackToMine = { navController.navigateToMineTabAfterMediaRelocation() }
            )
        }

        composable<MineRoute> {
            MinePage(
                onNavigate = onNavigate,
                magnetFeatures = magnetFeatures,
                onOpenMagnetSearch = { feature -> feature.openSearch(navController) },
                visibleToUser = true,
            )
        }

        magnetFeatures
            .sortedBy { it.featureId }
            .forEach { feature ->
                feature.registerNavigation(this, onBack)
            }
    }
}

/** 媒体关联正向终态后关闭恢复链路，直接回到首页“我的”Tab。 */
private fun NavHostController.navigateToMineTabAfterMediaRelocation() {
    navigate(MainRoute(initialTab = MainInitialTab.Mine)) {
        popUpTo(graph.findStartDestination().id) {
            inclusive = true
        }
        launchSingleTop = true
    }
    logD(TAG) { "媒体关联成功终态返回我的页面 reasonCode=media_relocation_back_to_mine" }
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
