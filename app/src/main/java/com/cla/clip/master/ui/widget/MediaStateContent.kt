package com.cla.clip.master.ui.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.cla.clip.master.ui.theme.ClipMasterThemeTokens

/**
 * 居中放置加载、失败、成功等轻量状态内容。
 *
 * 该容器只负责在可用内容区视觉居中，不读取页面状态；适用于图片提取、视频提取和下载这类单状态前景提示。
 */
@Composable
internal fun CenteredStateContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxSize()
    ) {
        content()
    }
}

/**
 * 带进度圈的行内加载提示。
 *
 * 文案由调用方传入，避免共享组件耦合具体业务状态；适用于正在识别、准备下载、下载中等短状态。
 */
@Composable
internal fun InlineLoadingState(
    text: String,
    modifier: Modifier = Modifier,
) {
    /** 加载状态内边距 token，让图片和视频提取页的行内状态保持一致。 */
    val spacing = ClipMasterThemeTokens.tokens.spacing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        CircularProgressIndicator(modifier = Modifier.size(25.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(spacing.medium))
    }
}

/**
 * 行内成功提示。
 *
 * 可以传入点击回调让成功状态成为打开相册、打开文件或进入详情的入口；不传时只作为静态结果提示。
 */
@Composable
internal fun InlineSuccessState(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Done,
    onClick: (() -> Unit)? = null,
) {
    InlineIconState(
        text = text,
        icon = icon,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier,
        onClick = onClick,
    )
}

/**
 * 行内错误提示。
 *
 * 默认点击整行执行重试；如果调用方不传重试回调，则仅展示错误状态，避免共享组件假定错误一定可以恢复。
 */
@Composable
internal fun InlineErrorState(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Error,
    onClick: (() -> Unit)? = null,
) {
    InlineIconState(
        text = text,
        icon = icon,
        tint = MaterialTheme.colorScheme.error,
        modifier = modifier,
        onClick = onClick,
    )
}

/**
 * 行内图标状态的统一骨架。
 *
 * 成功和失败状态只在图标、颜色和点击语义上不同，共用骨架可以保证跨页面图标大小、内边距和文字层级一致。
 */
@Composable
private fun InlineIconState(
    text: String,
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
    onClick: (() -> Unit)?,
) {
    /** 行内状态统一间距 token，用于图标和文字的可点击热区。 */
    val spacing = ClipMasterThemeTokens.tokens.spacing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .padding(spacing.medium)
                .size(24.dp)
        )
        Text(
            text = text,
            color = tint,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 0.dp, top = spacing.medium, end = spacing.medium, bottom = spacing.medium)
        )
    }
}
