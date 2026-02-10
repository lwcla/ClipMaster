package com.cla.clip.master.ui.screen.main

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.cla.clip.base.general.entity.ClipEntity

/**
 * 主屏幕的入口Composable。
 *
 * @param viewModel Hilt自动注入的MainViewModel实例。
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Box {
        when {
            uiState.isLoading -> LoadingScreen()
            uiState.pinnedClips.isEmpty() && uiState.latestClips.isEmpty() -> EmptyScreen()
            else -> ClipList(
                pinnedClips = uiState.pinnedClips,
                latestClips = uiState.latestClips,
                onPinToggle = { clip ->
                    viewModel.updateClip(clip.copy(isPinned = !clip.isPinned))
                },
                onDelete = { clip ->
                    viewModel.deleteClipGroup(clip)
                },
                onClick = { clip ->
                    viewModel.copyToClipboard(clip)
                }
            )
        }
    }
}

/**
 * 显示剪贴板列表的Composable。
 */
@Composable
private fun ClipList(
    pinnedClips: List<ClipEntity>,
    latestClips: List<ClipEntity>,
    onPinToggle: (ClipEntity) -> Unit,
    onDelete: (ClipEntity) -> Unit,
    onClick: (ClipEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalStaggeredGrid(
        // 设置两列 (也可以用 StaggeredGridCells.Adaptive(150.dp) 做自适应)
        columns = StaggeredGridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        // 列与列之间的间距
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        // 上下 Item 之间的间距
        verticalItemSpacing = 16.dp
    ) {
        // 置顶区域
        if (pinnedClips.isNotEmpty()) {
            // 标题需要跨满整行
            item(span = StaggeredGridItemSpan.FullLine) {
                Text(
                    text = "Pinned", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(pinnedClips, key = { it.id }) { clip ->
                ClipCard(
                    clip = clip,
                    onPinToggle = onPinToggle,
                    onDelete = onDelete,
                    onClick = onClick
                )
            }
        }

        // 最新区域
        if (latestClips.isNotEmpty()) {
            item(span = StaggeredGridItemSpan.FullLine) {
                Text(
                    text = "Latest", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }
            items(latestClips, key = { it.id }) { clip ->
                ClipCard(
                    clip = clip,
                    onPinToggle = onPinToggle,
                    onDelete = onDelete,
                    onClick = onClick
                )
            }
        }
    }
}

/**
 * 显示单个剪贴板内容的卡片。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipCard(
    clip: ClipEntity,
    onPinToggle: (ClipEntity) -> Unit,
    onDelete: (ClipEntity) -> Unit,
    onClick: (ClipEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val borderColor = clip.appColor ?: MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
    // 提取形状变量，确保外层卡片和内层裁剪使用相同的圆角
    val cardShape = RoundedCornerShape(12.dp)

    ElevatedCard(
        // 设置 Card 的物理形状（影响背景和阴影）
        shape = cardShape,
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 2. 再添加点击事件：水波纹会绘制在裁剪区域内
                .combinedClickable(
                    onClick = { onClick(clip) },
                    onLongClick = { Toast.makeText(context, "触发长按", Toast.LENGTH_SHORT).show() }
                )
                .clip(cardShape)
                .border(1.dp, borderColor, cardShape)
                // 1. 先进行裁剪：确保水波纹被限制在圆角内 (这只会裁剪 Column 自身，不会裁剪父级 Card 的阴影)
                // 3. 最后添加内边距：这样文字内容会有边距，但点击区域（水波纹）是铺满卡片的
                .padding(12.dp))
        {
            Text(
                text = clip.content,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))

            // 时间戳和来源
            Text(
                text = clip.formattedTime,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = clip.appIconPath,
                    contentDescription = "App Icon",
                    placeholder = rememberVectorPainter(Icons.Filled.Image),
                    error = rememberVectorPainter(Icons.Filled.Image),
                    modifier = Modifier.size(14.dp),
                    contentScale = ContentScale.Crop
                )

                Spacer(modifier = Modifier.width(4.dp)) // 建议加一点间距

                Text(
                    text = clip.appName,
                    style = MaterialTheme.typography.bodySmall.copy(
                        // 可选：如果觉得文字偏下，可以尝试禁用字体上下的默认留白
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Preview(widthDp = 150)
@Composable
private fun ClipCardPreview() {
    val clip = ClipEntity(
        id = 1L,
        content = "这是一个示例剪贴板内容，用于预览ClipCard组件的显示效果。",
        formattedTime = DateUtils.getRelativeTimeSpanString(System.currentTimeMillis()).toString(), // 伪代码：转换为 "刚刚"
        appName = "飞书",
        appIconPath = "https://img2.baidu.com/it/u=3546907450,5411894&fm=253&fmt=auto&app=120&f=JPEG?w=500&h=500",
        appColor = MaterialTheme.colorScheme.primary,
        hasLinkPreview = false,
        linkTitle = null,
        isPinned = false
    )

    ClipCard(clip, onPinToggle = {}, onDelete = {}, onClick = {})
}

/**
 * 加载状态屏幕。
 */
@Composable
private fun LoadingScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

/**
 * 空状态屏幕。
 */
@Composable
private fun EmptyScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp), // 1. 在这里添加左右边距 (数值可自定义),
        contentAlignment = Alignment.Center,
    ) {
        Text("还没有数据哦，快去复制点什么吧！", style = MaterialTheme.typography.bodyMedium)
    }
}