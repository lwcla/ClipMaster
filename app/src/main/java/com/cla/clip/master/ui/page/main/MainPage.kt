package com.cla.clip.master.ui.page.main

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.flowWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.cla.clip.base.general.entity.ClipEntity
import com.cla.clip.base.general.logD
import com.cla.clip.master.R

/**
 * 主屏幕的入口Composable。
 *
 * @param viewModel Hilt自动注入的MainViewModel实例。
 */
@Composable
fun MainPage(
    viewModel: MainViewModel = hiltViewModel()
) {

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pagedClips = remember(viewModel.pagedClips, lifecycle) {
        viewModel.pagedClips.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
    }.collectAsLazyPagingItems()

    logD("MainPage", { "MainPage: pagedClips itemCount = ${pagedClips.itemCount}, loadState = ${pagedClips.loadState}" })

    if (pagedClips.loadState.refresh is LoadState.NotLoading && pagedClips.itemCount == 0) {
        EmptyScreen()
    } else {
        LazyVerticalStaggeredGrid(
            // 设置两列 (也可以用 StaggeredGridCells.Adaptive(150.dp) 做自适应)
            columns = StaggeredGridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            // 列与列之间的间距
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            // 上下 Item 之间的间距
            verticalItemSpacing = 16.dp
        ) {

            if (pagedClips.itemCount > 0) {
                // 使用 items 扩展函数
                items(
                    count = pagedClips.itemCount,
                    // Paging 3 提供了 itemKey 帮助 Compose 优化复用
                    key = pagedClips.itemKey { it.id },
                    contentType = pagedClips.itemContentType { "ClipCard" }
                ) { index ->
                    val clip = pagedClips[index] // 获取数据
                    if (clip != null) {
                        ClipCard(
                            clip = clip,
                            onPinToggle = {
                                viewModel.updatePinStatus(it, !it.isPinned)
                            },
                            onDelete = {
                                viewModel.deleteClipGroup(it)
                            },
                            onClick = {
                                viewModel.copyToClipboard(it)
                            }
                        )
                    } else {
                        // 如果开启了 placeholders (占位符)，数据加载中 clip 可能为 null
                        // 这里可以渲染一个 骨架屏 (Skeleton) 卡片
                    }
                }
            }

            // 处理加载状态（底部 loading 条）
            when (pagedClips.loadState.append) {
                is LoadState.Loading -> {
                    item(span = StaggeredGridItemSpan.FullLine) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth() // 占满整行宽度，为了让 wrapContentWidth 能在整行中计算居中
                                .wrapContentWidth(Alignment.CenterHorizontally) // 在整行中居中
                                .size(26.dp) // 设置进度条的具体大小
                        )
                    }
                }

                is LoadState.Error -> {
                    // 显示重试按钮
                    item(span = StaggeredGridItemSpan.FullLine) {
                        Text(
                            text = "数据加载出错，点击重试！",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = {
                                    // 核心代码：调用 retry() 重新尝试加载失败的页面
                                    pagedClips.retry()
                                })
                                .padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            fontSize = 14.sp
                        )
                    }
                }

                else -> {}
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

    // 1. 控制菜单显示的状态
    var showMenu by remember { mutableStateOf(false) }

    val appColor = clip.appColor ?: MaterialTheme.colorScheme.outlineVariant
    val borderColor = clip.borderColor ?: MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    // 提取形状变量，确保外层卡片和内层裁剪使用相同的圆角
    val cardShape = RoundedCornerShape(12.dp)

    // 使用 Box 作为顶层，以便将 DropdownMenu 锚定在这个位置
    Box(modifier = modifier) {
        ElevatedCard(
            // 设置 Card 的物理形状（影响背景和阴影）
            shape = cardShape,
            modifier = modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {

            Box {

                if (clip.isPinned) {
                    Icon(
                        painterResource(R.drawable.icon_pinned),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth(0.25f) // 只占据卡片宽度的三分之一，避免过度覆盖内容
                            .align(Alignment.TopEnd)
                            .alpha(0.5f), // 关键：对齐到右上角
                        tint = appColor
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 2. 再添加点击事件：水波纹会绘制在裁剪区域内
                        .combinedClickable(
                            onClick = { onClick(clip) },
                            onLongClick = {
                                // 2. 长按时显示菜单
                                showMenu = true
                            }
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

        // 3. 弹出式菜单
        MaterialTheme(
            // 方法1：如果你想改变菜单的圆角，最彻底的方法是覆盖 Shape
            shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(16.dp))
        ) {
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                // 自定义背景色
                containerColor = MaterialTheme.colorScheme.surfaceContainer, // 或者 Color.White
                // 自定义整个菜单的阴影和边框
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(16.dp)
                    ),
                // 调整菜单出现的偏移位置 (x, y)
                offset = DpOffset(x = 10.dp, y = (-10).dp)
            ) {
                // 核心修改：使用 Row 将两个选项横向排列
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {

                    // 左侧按钮：置顶/取消置顶
                    MenuItemButton(
                        text = if (clip.isPinned) "取消置顶" else "置顶",
                        icon = Icons.Default.PushPin,
                        color = MaterialTheme.colorScheme.primary,
                        onClick = {
                            onPinToggle(clip)
                            showMenu = false
                        }
                    )

                    // 中间竖线分割（可选，为了美观）
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(24.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    )

                    // 右侧按钮：删除
                    MenuItemButton(
                        text = "删除",
                        icon = Icons.Default.Delete,
                        color = MaterialTheme.colorScheme.error,
                        onClick = {
                            onDelete(clip)
                            showMenu = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * 提取出来的自定义横向菜单按钮组件
 */
@Composable
private fun MenuItemButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp)) // 点击时的圆角效果
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp), // 点击区域内的内边距
    )
}

@Preview(widthDp = 150, heightDp = 300)
@Composable
private fun ClipCardPreview() {
    val clip = ClipEntity(
        id = 1L,
        content = "这是一个示例剪贴板内容，用于预览ClipCard组件的显示效果。",
        formattedTime = DateUtils.getRelativeTimeSpanString(System.currentTimeMillis()).toString(), // 伪代码：转换为 "刚刚"
        appName = "飞书",
        appIconPath = "https://img2.baidu.com/it/u=3546907450,5411894&fm=253&fmt=auto&app=120&f=JPEG?w=500&h=500",
        appColor = MaterialTheme.colorScheme.error,
        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
        hasLinkPreview = false,
        linkTitle = null,
        isPinned = true
    )

    ClipCard(clip, onPinToggle = {}, onDelete = {}, onClick = {})
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