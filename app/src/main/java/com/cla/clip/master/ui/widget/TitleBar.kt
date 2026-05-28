package com.cla.clip.master.ui.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.R as BaseR

/** 标题栏内容区高度，统一普通态、多选态和带右侧操作的页面标题，避免各页面手写高度造成按钮偏移。 */
private val TitleBarContentHeight = 48.dp

/** 标题栏左右内边距，与 48dp 图标按钮配合，保证点击热区贴近系统常见导航栏尺寸。 */
private val TitleBarHorizontalPadding = 4.dp

/** 标题居中区域的左右保护间距，按两枚 48dp 操作按钮预留空间，避免标题和左右按钮视觉重叠。 */
private val TitleBarTitleSidePadding = 112.dp

/**
 * 通用返回标题栏。
 *
 * 左侧固定返回按钮，中间单行标题，右侧支持填充操作按钮；适用于大多数二级页面，不负责处理导航栈细节。
 * 标题使用覆盖式居中布局，不参与左右按钮的 Row 分配，因此右侧有多个按钮时仍保持相对整条标题栏居中。
 */
@Composable
fun TitleBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TitleBar(
        modifier = modifier,
        navigation = {
            TitleBarBackButton(onBack = onBack)
        },
        title = {
            TitleBarText(text = title)
        },
        actions = {
            actions()
        }
    )
}

/**
 * 插槽式通用标题栏容器。
 *
 * 页面可以按需填充左侧导航区、标题区和右侧操作区；容器统一处理状态栏 padding、48dp 内容高度和垂直居中。
 * 标题层始终覆盖式居中，左右按钮贴边放置；这样一级页无返回按钮、二级页右侧有多个按钮时，标题都不会被左右内容数量拉偏。
 */
@Composable
fun TitleBar(
    modifier: Modifier = Modifier,
    navigation: @Composable RowScope.() -> Unit,
    title: @Composable RowScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .height(TitleBarContentHeight)
            .padding(horizontal = TitleBarHorizontalPadding)
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = TitleBarTitleSidePadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            title()
        }

        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navigation()
        }

        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions()
        }
    }
}

/**
 * 一级页面标题栏。
 *
 * 列表页、我的页这类底部 Tab 的根页面没有返回语义，但仍需要展示当前页面身份；该组件复用通用标题栏的状态栏 padding、
 * 48dp 内容高度和垂直居中规则，只省略左侧返回按钮，避免一级页面误导用户可以返回上一级。
 */
@Composable
fun TopLevelTitleBar(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TitleBar(
        modifier = modifier,
        navigation = {},
        title = {
            TitleBarText(text = title)
        },
        actions = actions
    )
}

/**
 * 标题栏文案组件。
 *
 * 所有普通标题、一级页面标题和管理态标题都应复用这里的字号、字重、居中和省略规则；统一收口后，调整标题视觉层级时只需要修改这一处。
 */
@Composable
fun TitleBarText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    )
}

/** 标题栏默认返回按钮，集中维护返回图标尺寸和无障碍文案，避免各页面重复硬编码。 */
@Composable
private fun TitleBarBackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack, Modifier.size(48.dp)) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(BaseR.string.base_general_back)
        )
    }
}

@Preview(showBackground = true)
/** 标题栏预览，用于检查返回按钮、标题居中和状态栏 padding 的基础效果。 */
@Composable
private fun TitleBarPreview() {
    TitleBar(title = "测试", onBack = {})
}
