package com.cla.clip.master.ui.page.main

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.flowWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.logD
import com.cla.clip.master.R
import com.cla.clip.master.ui.navigation.DetailRoute
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.widget.rememberFormattedTime
import kotlin.math.max

/**
 * 主屏幕的入口Composable。
 *
 * @param viewModel Hilt自动注入的MainViewModel实例。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainPage(
    viewModel: MainViewModel = hiltViewModel(),
    onNavigate: (Route) -> Unit  // 跳转页面
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pagedClips = remember(viewModel.pagedClips, lifecycle) {
        viewModel.pagedClips.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
    }.collectAsLazyPagingItems()

    // --- BottomSheet 状态管理 ---
    // 保存当前长按选中的 Clip，如果为 null 则不显示 Sheet
    var selectedClipForSheet by remember { mutableStateOf<ClipShowEntity?>(null) }
    val sheetState = rememberModalBottomSheetState()

    logD("MainPage", { "MainPage: pagedClips itemCount = ${pagedClips.itemCount}, loadState = ${pagedClips.loadState}" })

    Box(modifier = Modifier.fillMaxWidth()) {
        ClipList(
            viewModel = viewModel,
            pagedClips = pagedClips,
            onLongClick = { clip ->
                // 长按时，设置选中的 Clip，触发 BottomSheet 显示
                selectedClipForSheet = clip
            },
            onNavigate = onNavigate
        )

        // 底部弹出窗口
        val clip = selectedClipForSheet
        if (clip != null) {
            ModalBottomSheet(
                onDismissRequest = { selectedClipForSheet = null },
                sheetState = sheetState
            ) {
                // BottomSheet 的内容
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 添加垂直滚动修饰符
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                ) {
                    Text(
                        text = clip.content,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipList(
    viewModel: MainViewModel,
    pagedClips: LazyPagingItems<ClipShowEntity>,
    onLongClick: (ClipShowEntity) -> Unit,
    onNavigate: (Route) -> Unit,
) {
    if (pagedClips.loadState.refresh is LoadState.NotLoading && pagedClips.itemCount == 0) {
        EmptyScreen()
    } else {
        LazyVerticalStaggeredGrid(
            // 设置两列 (也可以用 StaggeredGridCells.Adaptive(150.dp) 做自适应)
            columns = StaggeredGridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(10.dp),
            // 列与列之间的间距
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            // 上下 Item 之间的间距
            verticalItemSpacing = 10.dp
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
                            onPinToggle = { viewModel.updatePinStatus(it, !it.isPinned) },
                            onDelete = { viewModel.deleteClipGroup(it) },
                            onCopy = { viewModel.copyToClipboard(it) },
                            onClick = { onNavigate(DetailRoute(it.id)) },
                            onLongClick = onLongClick
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
    clip: ClipShowEntity,
    onPinToggle: (ClipShowEntity) -> Unit,
    onDelete: (ClipShowEntity) -> Unit,
    onCopy: (ClipShowEntity) -> Unit,
    onClick: (ClipShowEntity) -> Unit,
    onLongClick: (ClipShowEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 2. 再添加点击事件：水波纹会绘制在裁剪区域内
                        .combinedClickable(
                            onClick = { onClick(clip) },
                            onLongClick = { onLongClick(clip) }
                        )
                        .clip(cardShape)
                        .border(1.dp, borderColor, cardShape)
                        // 1. 先进行裁剪：确保水波纹被限制在圆角内 (这只会裁剪 Column 自身，不会裁剪父级 Card 的阴影)
                        // 3. 最后添加内边距：这样文字内容会有边距，但点击区域（水波纹）是铺满卡片的
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp))
                {
                    ClipContent(clip)

                    Spacer(Modifier.height(8.dp))
                    SourceAppNameWithTime(clip)

                    // 中间竖线分割（可选，为了美观）
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    )
                    CardButtonContainer(clip, onPinToggle, onDelete, onCopy)
                }

                if (clip.isPinned) {
                    // 显示置顶标签
                    Icon(
                        painterResource(R.drawable.host_icon_pinned),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth(0.25f) // 只占据卡片宽度的三分之一，避免过度覆盖内容
                            .align(Alignment.TopEnd)
                            .alpha(0.6f), // 关键：对齐到右上角
                        tint = appColor
                    )
                }
            }
        }
    }
}

/** 剪贴数据的内容显示 */
@Composable
private fun ClipContent(clip: ClipShowEntity, modifier: Modifier = Modifier) {
    val imagUrl = clip.linkImgUrl
    if (imagUrl.isNullOrBlank()) {
        Text(
            text = clip.content,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 7,
            overflow = TextOverflow.Ellipsis
        )
    } else {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = imagUrl,
                    contentDescription = "Link Preview Image",
                    modifier = Modifier
                        .size(55.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )

                val title = clip.linkTitle
                if (title.isNullOrBlank().not()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = clip.content,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 剪贴数据来源app和写入剪贴板的时间 */
@Composable
private fun SourceAppNameWithTime(clip: ClipShowEntity, modifier: Modifier = Modifier) {
// 获取当前的密度信息
    val currentDensity = LocalDensity.current
    // 创建一个新的 Density，强制 fontScale 为 1f (不缩放)，不响应系统字体大小设置
    CompositionLocalProvider(LocalDensity provides Density(density = currentDensity.density, fontScale = 1f)) {
        // 使用自定义 Layout 来替代 Row
        Layout(
            content = {
                // -------------------------------------------------------------
                // [组件 0] 左侧部分: Icon + AppName
                // -------------------------------------------------------------
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = clip.appIconPath,
                        contentDescription = "App Icon",
                        placeholder = rememberVectorPainter(Icons.Filled.Image),
                        error = rememberVectorPainter(Icons.Filled.Image),
                        modifier = Modifier.size(15.dp),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = clip.appName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // -------------------------------------------------------------
                // [组件 1] 右侧部分: formattedTime
                // -------------------------------------------------------------
                Text(
                    text = clip.rememberFormattedTime(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 11.sp,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { measurables, constraints ->
            // 1. 先把两个组件提取出来
            val leftNode = measurables[0]
            val rightNode = measurables[1]

            // 2. 策略：优先测量右边的 Time，但限制它最大只能占 75% 的宽度
            // 这样保证左边至少有 25% 的空间展示 Icon
            val maxTimeWidth = (constraints.maxWidth * 0.5f).toInt()

            // 测量右边 (Time)
            val rightPlaceable = rightNode.measure(
                constraints.copy(
                    minWidth = 0,
                    maxWidth = maxTimeWidth
                )
            )

            // 3. 计算左边剩余的空间
            // 至少给左边留 gap (4.dp approx 10px) 的空间，这里简单处理直接用剩余宽度
            val remainingWidth = constraints.maxWidth - rightPlaceable.width - 8.dp.roundToPx()
            val leftMaxWidth = max(0, remainingWidth)

            // 测量左边 (Icon + Name)
            val leftPlaceable = leftNode.measure(
                constraints.copy(
                    minWidth = 0,
                    maxWidth = leftMaxWidth
                )
            )

            // 4. 计算高度 (取两者最高)
            val height = max(leftPlaceable.height, rightPlaceable.height)

            // 5. 布局放置
            layout(constraints.maxWidth, height) {
                // 左侧靠左放
                leftPlaceable.placeRelative(0, (height - leftPlaceable.height) / 2) // 垂直居中

                // 右侧靠右放
                rightPlaceable.placeRelative(constraints.maxWidth - rightPlaceable.width, (height - rightPlaceable.height) / 2) // 垂直居中
            }
        }
    }
}

/** 底部按钮 */
@Composable
private fun CardButtonContainer(
    clip: ClipShowEntity,
    onPinToggle: (ClipShowEntity) -> Unit,
    onDelete: (ClipShowEntity) -> Unit,
    onCopy: (ClipShowEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 核心修改：使用 Row 将两个选项横向排列
    Row(verticalAlignment = Alignment.CenterVertically) {

        // 置顶/取消置顶
        Box(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = { onPinToggle(clip) })
        ) {
            Icon(
                if (clip.isPinned) {
                    painterResource(R.drawable.host_icon_unpinned)
                } else {
                    painterResource(R.drawable.host_icon_to_pinned)
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(38.dp)
                    .padding(top = 8.dp, bottom = 12.dp)

            )
        }

        // 中间竖线分割（可选，为了美观）
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(18.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        )

        // 删除按钮
        Box(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = { onDelete(clip) })
        ) {
            Icon(
                painterResource(R.drawable.host_icon_delete),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(38.dp)
                    .padding(top = 8.dp, bottom = 12.dp)
            )
        }

        // 中间竖线分割（可选，为了美观）
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(18.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        )

        // 复制
        Box(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = { onCopy(clip) })
        ) {
            Icon(
                painterResource(R.drawable.host_icon_copy),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(38.dp)
                    .padding(top = 8.dp, bottom = 12.dp)
            )
        }
    }
}

@Preview(widthDp = 150, heightDp = 300)
@Composable
private fun ClipCardPreview() {
    val clip = ClipShowEntity(
        id = 1L,
        content = "这是一个示例剪贴板内容，用于预览ClipCard组件的显示效果。用于预览ClipCard组件的显示效果。",
//        formattedTime = DateUtils.getRelativeTimeSpanString(System.currentTimeMillis()).toString(), // 伪代码：转换为 "刚刚"
        timestamp = System.currentTimeMillis(),
        formattedTime = "刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚", // 伪代码：转换为 "刚刚"
        appName = "飞书飞书飞书飞书",
        appIconPath = "https://img2.baidu.com/it/u=3546907450,5411894&fm=253&fmt=auto&app=120&f=JPEG?w=500&h=500",
        appColor = MaterialTheme.colorScheme.error,
        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
        isPinned = false,
        link = "https://www.wanandroid.com/",
        linkImgUrl = "https://img0.baidu.com/it/u=2280054277,2128244139&fm=253&fmt=auto&app=138&f=JPEG?w=973&h=304",
        linkTitle = "这是链接的标题这是链接的标题这是链接的标题"
    )

    ClipCard(clip, onPinToggle = {}, onDelete = {}, onCopy = {}, onClick = {}, onLongClick = {})
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