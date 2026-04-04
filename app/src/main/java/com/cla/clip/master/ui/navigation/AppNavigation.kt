package com.cla.clip.master.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.cla.clip.master.ui.page.detail.DetailPage
import com.cla.clip.master.ui.page.main.MainPage

@Composable
fun AppNavigation(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = "main"
    ) {
        composable("main") {
            MainPage(
                onNavigateToDetail = { clipId ->
                    navController.navigate("detail/$clipId")
                }
            )
        }

        composable("detail/{clipId}") { backStackEntry ->
            val clipId = backStackEntry.arguments?.getString("clipId")?.toLong() ?: 0L
            DetailPage(
                clipId = clipId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}