package com.cla.clip.master.ui.page.main

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PermIdentity
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cla.clip.base.general.R
import com.cla.clip.feature.magnet.api.MagnetFeatureEntry
import com.cla.clip.master.ui.navigation.MainInitialTab
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.page.list.ClipListPage
import com.cla.clip.master.ui.page.mine.MinePage
import com.cla.clip.master.ui.theme.ClipMasterThemeTokens
import kotlinx.coroutines.launch


/** 底部导航图标槽位，保持 Tab 配置和具体 Icon 组件解耦。 */
private typealias Icon = @Composable () -> Unit

/**
 * 首页底部 Tab 配置。
 *
 * page 用于决定 Pager 显示哪个业务页面，icon 只负责底部栏展示，避免 UI 图标影响导航判断。
 */
private data class BottomTab(
    /** Tab 对应的业务页面。 */
    val page: TabPage,

    /** 底部栏标题文案。 */
    val title: String,

    /** 底部栏图标内容。 */
    val icon: Icon
)

/** 首页 Pager 支持的页面类型。 */
private sealed class TabPage {
    /** 列表 */
    data object List : TabPage()

    /** 我的 */
    data object Mine : TabPage()
}

/**
 * 应用主页面。
 *
 * 使用 HorizontalPager 承载剪贴列表和我的页，底部 Tab 控制页面切换；
 * 列表页的竖向列表状态放在主页面持有，便于重复点击列表 Tab 时滚回顶部。
 */
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainPage(
    initialTab: MainInitialTab = MainInitialTab.List,
    onNavigate: (Route) -> Unit,
    magnetFeatures: Set<MagnetFeatureEntry> = emptySet(),
    onOpenMagnetSearch: (MagnetFeatureEntry, String) -> Unit = { _, _ -> },
) {
    /** 首页底部导航间距 token，统一底部栏安全区和内容节奏。 */
    val spacing = ClipMasterThemeTokens.tokens.spacing
    /** 首页底部栏支持的 Tab 列表；顺序和 Pager 页码保持一致。 */
    val tabs = listOf(
        BottomTab(TabPage.List, stringResource(R.string.base_general_list)) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.base_general_list_tab))
        },
        BottomTab(TabPage.Mine, stringResource(R.string.base_general_mine)) {
            Icon(Icons.Default.PermIdentity, contentDescription = stringResource(R.string.base_general_mine_tab))
        },
    )
    /** 路由传入的初始 Tab 映射结果。 */
    val initialTabPage = initialTab.toTabPage()
    /** 初始页码；映射不到时回退到列表页。 */
    val initialPage = tabs.indexOfFirst { it.page == initialTabPage }.takeIf { it >= 0 } ?: 0

    /** 底部 Tab 和 HorizontalPager 共享的页码状态。 */
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { tabs.size }
    )
    /** 底部栏点击后驱动平滑滚动的页面级协程作用域。 */
    val scope = rememberCoroutineScope()
    /** 列表页的滚动状态；重复点击列表 Tab 时用它回到顶部。 */
    val listState = rememberLazyListState()

    Scaffold(
        // 首页内容自身不需要再吃系统栏 inset；顶部由一级标题栏处理，底部由 NavigationBar 自己处理。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // 底部栏自己消费系统导航区 inset，这样背景可以贴到底部手势区，不会再像悬浮卡片一样上浮。
            NavigationBar(
                modifier = Modifier.fillMaxWidth(),
                windowInsets = NavigationBarDefaults.windowInsets,
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                tonalElevation = spacing.tiny,
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            // 点击底部 tab 后平滑切换到对应页面，保持与 ViewPager 类似的交互体验。
                            scope.launch {
                                /** 当前点击的是否正好是已选中的 Tab。 */
                                val isCurrentTab = pagerState.currentPage == index
                                /** 当前点击的 Tab 是否对应列表页。 */
                                val isListTab = tab.page == TabPage.List
                                if (isCurrentTab && isListTab) {
                                    // 当前已经停留在列表页时，再次点击列表入口需要回到顶部，避免依赖页面下标判断列表身份。
                                    listState.scrollToItem(0)
                                } else {
                                    // 点击其他入口时执行正常分页切换。
                                    pagerState.animateScrollToPage(index)
                                }
                            }
                        },
                        icon = tab.icon,
                        label = { Text(tab.title) },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                            indicatorColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) { index ->
            /** 当前 Pager 页对应的底部 Tab 配置。 */
            val tab = tabs[index]
            when (tab.page) {
                TabPage.List -> ClipListPage(listState = listState, onNavigate = onNavigate)
                TabPage.Mine -> MinePage(
                    onNavigate = onNavigate,
                    magnetFeatures = magnetFeatures,
                    onOpenMagnetSearch = { feature -> onOpenMagnetSearch(feature, "") },
                    visibleToUser = pagerState.currentPage == index,
                )
            }
        }
    }
}

/** 把路由层的初始 Tab 枚举映射成主页内部 Pager 页面类型。 */
private fun MainInitialTab.toTabPage(): TabPage {
    return when (this) {
        MainInitialTab.List -> TabPage.List
        MainInitialTab.Mine -> TabPage.Mine
    }
}
