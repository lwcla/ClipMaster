package com.cla.clip.master.ui.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState

/**
 * 通用分页首次加载状态。
 *
 * 适用于 Paging refresh 阶段且页面暂无可展示数据的场景；组件只展示居中进度，不主动触发数据请求。
 */
@Composable
internal fun PagingLoadingContent(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

/**
 * 通用分页空状态。
 *
 * 页面传入已资源化文案和可选图标；共享组件只负责居中排版，避免每个分页页面重复维护空态布局。
 */
@Composable
internal fun PagingEmptyContent(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.History,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(42.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
        Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 通用分页首次加载失败状态。
 *
 * 点击错误文案会回调调用方的 retry；组件不持有 PagingItems，便于下载记录、回收站或后续分页页复用。
 */
@Composable
internal fun PagingErrorContent(
    text: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            modifier = Modifier
                .clickable(onClick = onRetry)
                .padding(16.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * 通用追加分页状态。
 *
 * 只处理 append loading 和 append error；refresh 状态仍应由页面在列表外层判断，避免空态和已有列表混在一起。
 */
internal fun LazyListScope.pagingAppendStateItem(
    loadState: LoadState,
    retryText: String,
    onRetry: () -> Unit,
) {
    when (loadState) {
        is LoadState.Loading -> {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }

        is LoadState.Error -> {
            item {
                Text(
                    text = retryText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onRetry)
                        .padding(14.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        else -> Unit
    }
}
