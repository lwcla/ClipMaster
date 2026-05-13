package com.cla.clip.master.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp


/**
 * 带大标题折叠效果的页面脚手架。
 *
 * 调用方传入标题和内容区域；内容会拿到 Scaffold 的 paddingValues，用于和 LargeTopAppBar 的折叠滚动行为对齐。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollapsingTitle(title: String, content: @Composable (paddingValues: PaddingValues) -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier
            .background(Color.Transparent)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                            .statusBarsPadding(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(title)
                    }
                },
                expandedHeight = 250.dp, // 默认更高/更低都可以
                windowInsets = WindowInsets(0, 0, 0, 0), // 不自动吃状态栏
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                )
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        content(paddingValues)
    }
}
