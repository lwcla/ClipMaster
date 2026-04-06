package com.cla.clip.master.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.cla.clip.master.ui.page.detail.DetailPage
import com.cla.clip.master.ui.page.main.MainPage
import com.cla.clip.master.ui.page.video.VideoExtractPage

@Composable
fun AppNavigation(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = MainRoute
    ) {
        composable<MainRoute> {
            MainPage(
                onNavigate = { route -> navController.navigate(route) }
            )
        }

        // 详情页
        composable<DetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<DetailRoute>()
            DetailPage(
                clipId = route.clipId,
                onBack = { navController.popBackStack() },
                onNavigate = { route -> navController.navigate(route) }
            )
        }

        // 视频提取页
        composable<VideoExtractRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<VideoExtractRoute>()
            VideoExtractPage(
                pageUrl = route.url,
                onBack = { navController.popBackStack() }
            )
        }
    }
}