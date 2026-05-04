package com.cla.clip.master.ui.page.main

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.R
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.page.list.ClipListPage
import com.cla.clip.master.ui.page.mine.MinePage
import kotlinx.coroutines.launch

data class BottomTab(
    val title: String,
    val icon: @Composable () -> Unit
)

/** 主页面 */
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainPage(
    onNavigate: (Route) -> Unit  // 跳转页面
) {
    val tabs = listOf(
        BottomTab(stringResource(R.string.base_general_list)) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Home") },
        BottomTab(stringResource(R.string.base_general_mine)) { Icon(Icons.Default.PermIdentity, contentDescription = "Mine") },
    )

    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyStaggeredGridState()

    Scaffold(
        bottomBar = {
            NavigationBar(
                modifier = Modifier.height(50.dp),
                windowInsets = WindowInsets(0, 0, 0, 0) // 如需去掉系统底部额外 inset
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        icon = tab.icon,
//                        label = { Text(tab.title) }
                    )
                }
            }
        }
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = paddingValues.calculateBottomPadding())
        ) { page ->
            when (page) {
                0 -> ClipListPage(gridState = gridState, onNavigate = onNavigate)
                1 -> MinePage(onNavigate = onNavigate)
            }
        }
    }
}