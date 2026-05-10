package com.cla.clip.master.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.cla.clip.master.ui.page.detail.DetailPage
import com.cla.clip.master.ui.page.image.ImageExtractPage
import com.cla.clip.master.ui.page.list.ClipListPage
import com.cla.clip.master.ui.page.main.MainPage
import com.cla.clip.master.ui.page.mine.MinePage
import com.cla.clip.master.ui.page.video.VideoDownloadPage
import com.cla.clip.master.ui.page.video.VideoExtractPage

@Composable
fun AppNavigation(navController: NavHostController) {
    val onNavigate = { route: Route ->
        navController.navigate(route)
    }

    val onBack = {
        navController.popBackStack()
        Unit
    }

    NavHost(
        navController = navController,
        startDestination = MainRoute
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

        composable<MineRoute> {
            MinePage(
                onNavigate = onNavigate
            )
        }
    }
}
