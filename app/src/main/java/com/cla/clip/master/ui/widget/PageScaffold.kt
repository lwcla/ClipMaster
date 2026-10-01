package com.cla.clip.master.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
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
 * 根层避让并消费未处理的导航栏 Insets；嵌入首页时由父层消费已留出的底栏空间，独立使用时自行兜底。
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
        modifier = modifier
            .fillMaxSize()
            .background(pageBackgroundColor)
            // 只补父层尚未消费的导航区；背景先绘制，保证安全区也沿用页面底色。
            .navigationBarsPadding(),
        // 状态栏交给标题栏，导航栏由根 modifier 负责，关闭默认内容 Insets 避免重复留白。
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
 * 根层统一避让并消费导航栏 Insets，底栏只负责业务布局；内容仍需应用传入的标题栏和底栏 padding。
 * 自定义 topBar 替换整条顶部栏并负责状态栏避让；未提供时保留原有 TitleBar 契约。
 */
@Composable
fun SecondaryPageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    /** 可选顶部展示 slot，例如搜索输入栏；默认 null 保持所有既有调用方的标题布局。 */
    topBar: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    /** 二级页面背景色，确保子页面和根页面拥有一致底色。 */
    val pageBackgroundColor = MaterialTheme.colorScheme.background
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(pageBackgroundColor)
            // 有无业务底栏都在骨架根层处理导航区，内部 Scaffold 会排除已消费的 Insets。
            .navigationBarsPadding(),
        topBar = {
            // 自定义顶部自行处理状态栏，骨架不能再叠加第二份 Insets。
            if (topBar != null) {
                topBar()
            } else {
                TitleBar(title = title, onBack = onBack, actions = actions)
            }
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
