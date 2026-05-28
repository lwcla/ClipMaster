package com.cla.clip.master.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cla.clip.master.ui.theme.ClipMasterThemeTokens

/**
 * 一级 Tab 页面骨架。
 *
 * 统一处理页面背景、一级标题栏和内容区域；列表页和我的页只需要提供标题、右侧动作和主体内容。
 */
@Composable
fun TopLevelPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    /** 一级页面背景色，跟随固定品牌主题，避免各页面自己设置底色。 */
    val pageBackgroundColor = MaterialTheme.colorScheme.background
    Scaffold(
        modifier = modifier.fillMaxSize(),
        // 一级页面顶部由 TopLevelTitleBar 处理状态栏，底部由主页导航栏处理系统导航区，这里关闭默认 inset 避免重复留白。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopLevelTitleBar(
                title = title,
                actions = actions,
            )
        },
        containerColor = pageBackgroundColor,
        content = content,
    )
}

/**
 * 二级页面骨架。
 *
 * 统一返回标题栏、页面背景、可选底部栏和 Snackbar；复杂页面仍可在 content 内维护自己的业务状态。
 */
@Composable
fun SecondaryPageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    /** 二级页面背景色，确保子页面和根页面拥有一致底色。 */
    val pageBackgroundColor = MaterialTheme.colorScheme.background
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TitleBar(
                title = title,
                onBack = onBack,
                actions = actions,
            )
        },
        bottomBar = bottomBar,
        snackbarHost = {
            snackbarHostState?.let { state ->
                SnackbarHost(hostState = state)
            }
        },
        containerColor = pageBackgroundColor,
        content = content,
    )
}

/**
 * 页面内容背景容器。
 *
 * 用于旧页面逐步迁入骨架前的局部包裹，避免流程页还没完全改造时出现白底和新主题割裂。
 */
@Composable
fun PageBackground(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    content: @Composable () -> Unit,
) {
    /** 页面内容默认间距 token；调用方可以传入 Scaffold padding 或额外内边距。 */
    val spacing = ClipMasterThemeTokens.tokens.spacing
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(contentPadding)
            .padding(horizontal = spacing.small),
    ) {
        content()
    }
}
