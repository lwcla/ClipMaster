package com.cla.clip.master.ui.page.main

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PermIdentity
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.page.list.ClipListPage
import com.cla.clip.master.ui.page.mine.MinePage
import kotlinx.coroutines.launch


private typealias Icon = @Composable () -> Unit

private data class BottomTab(val page: TabPage, val icon: Icon)

private sealed class TabPage {
    /** 列表 */
    data object List : TabPage()

    /** 我的 */
    data object Mine : TabPage()
}

/** 主页面 */
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainPage(
    onNavigate: (Route) -> Unit  // 跳转页面
) {
    val tabs = listOf(
        BottomTab(TabPage.List) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "List") },
        BottomTab(TabPage.Mine) { Icon(Icons.Default.PermIdentity, contentDescription = "Mine") },
    )

    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyStaggeredGridState()

    Scaffold(
        bottomBar = {
            // 外层容器负责为系统导航栏预留安全区，避免底部栏覆盖到底部手势/三键区域。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                // 内层 NavigationBar 只负责视觉上的底部栏高度，这里关闭自身 inset，避免重复留白。
                NavigationBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Red)
                        .height(45.dp),
                    windowInsets = WindowInsets(0, 0, 0, 0),
                ) {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                // 点击底部 tab 后平滑切换到对应页面，保持与 ViewPager 类似的交互体验。
                                scope.launch {
                                    val isCurrentTab = pagerState.currentPage == index
                                    val isListTab = tab.page == TabPage.List
                                    if (isCurrentTab && isListTab) {
                                        // 当前已经停留在列表页时，再次点击列表入口需要回到顶部，避免依赖页面下标判断列表身份。
                                        gridState.scrollToItem(0)
                                    } else {
                                        // 点击其他入口时执行正常分页切换。
                                        pagerState.animateScrollToPage(index)
                                    }
                                }
                            },
                            icon = tab.icon,
//                            label = { Text(tab.title) }
                            alwaysShowLabel = false,
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = paddingValues.calculateBottomPadding())
        ) { index ->
            val tab = tabs[index]
            when (tab.page) {
                TabPage.List -> ClipListPage(gridState = gridState, onNavigate = onNavigate)
                TabPage.Mine -> MinePage(onNavigate = onNavigate)
            }
        }
    }
}
