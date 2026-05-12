package com.cla.clip.master.ui.page.list

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
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ReportGmailerrorred
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.master.R
import com.cla.clip.master.ui.theme.cardCornerShape
import com.cla.clip.master.ui.widget.rememberFormattedTime
import kotlin.math.max

/**
 * 剪贴结果瀑布流列表。
 *
 * 该组件同时服务普通列表页和搜索页：列表页传空 `highlightQuery`，搜索页传当前关键词。
 * 这样卡片布局、按钮行为和分页加载状态只维护一份，避免两个页面在后续迭代中出现体验分叉。
 */
@Composable
fun ClipResultList(
    gridState: LazyStaggeredGridState,
    pagedClips: LazyPagingItems<ClipShowEntity>,
    emptyText: String,
    onPinToggle: (ClipShowEntity) -> Unit,
    onDelete: (ClipShowEntity) -> Unit,
    onCopy: (ClipShowEntity) -> Unit,
    onClick: (ClipShowEntity) -> Unit,
    onLongClick: (ClipShowEntity) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(10.dp),
    highlightQuery: String? = null,
) {
    if (pagedClips.loadState.refresh is LoadState.NotLoading && pagedClips.itemCount == 0) {
        EmptyScreen(text = emptyText, modifier = modifier)
    } else {
        LazyVerticalStaggeredGrid(
            state = gridState,
            // 固定两列与原列表页保持一致，搜索页复用时不会改变用户对结果密度的预期。
            columns = StaggeredGridCells.Fixed(2),
            modifier = modifier.fillMaxSize(),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalItemSpacing = 10.dp
        ) {
            if (pagedClips.itemCount > 0) {
                items(
                    count = pagedClips.itemCount,
                    // Paging key 使用数据库主键，保证置顶、删除、搜索条件变化时 Compose 复用稳定。
                    key = pagedClips.itemKey { it.id },
                    contentType = pagedClips.itemContentType { "ClipCard" }
                ) { index ->
                    val clip = pagedClips[index]
                    if (clip != null) {
                        ClipCard(
                            clip = clip,
                            highlightQuery = highlightQuery,
                            onPinToggle = onPinToggle,
                            onDelete = onDelete,
                            onCopy = onCopy,
                            onClick = onClick,
                            onLongClick = onLongClick
                        )
                    }
                }
            }

            when (pagedClips.loadState.append) {
                is LoadState.Loading -> {
                    item(span = StaggeredGridItemSpan.FullLine) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentWidth(Alignment.CenterHorizontally)
                                .size(26.dp)
                        )
                    }
                }

                is LoadState.Error -> {
                    item(span = StaggeredGridItemSpan.FullLine) {
                        Text(
                            text = stringResource(com.cla.clip.base.general.R.string.base_general_data_load_failed_retry),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = {
                                    // append 加载失败时直接使用 Paging 的 retry，避免页面自己维护重试状态。
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
 *
 * 卡片本身只处理展示和用户点击回调，不直接访问 ViewModel 或 Repository，
 * 因此普通列表页和搜索页可以在各自页面层决定置顶、删除、复制和详情跳转行为。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClipCard(
    clip: ClipShowEntity,
    onPinToggle: (ClipShowEntity) -> Unit,
    onDelete: (ClipShowEntity) -> Unit,
    onCopy: (ClipShowEntity) -> Unit,
    onClick: (ClipShowEntity) -> Unit,
    onLongClick: (ClipShowEntity) -> Unit,
    modifier: Modifier = Modifier,
    highlightQuery: String? = null,
) {
    val appColor = clip.appColor ?: MaterialTheme.colorScheme.outlineVariant
    val borderColor = appColor.copy(alpha = 0.3f)
    val lineColor = appColor.copy(alpha = 0.3f)
    // 外层 Card 和内层点击区域共用同一个圆角，保证阴影、水波纹和边框视觉一致。
    val cardShape = cardCornerShape

    Box(modifier = modifier) {
        ElevatedCard(
            shape = cardShape,
            modifier = modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Box {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onClick(clip) },
                            onLongClick = { onLongClick(clip) }
                        )
                        .clip(cardShape)
                        .border(1.dp, borderColor, cardShape)
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp)
                ) {
                    ClipContent(clip, highlightQuery)

                    Spacer(Modifier.height(8.dp))
                    SourceAppNameWithTime(clip, highlightQuery)

                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(lineColor)
                    )
                    CardButtonContainer(clip, lineColor, onPinToggle, onDelete, onCopy)
                }

                if (clip.isPinned) {
                    Icon(
                        painterResource(R.drawable.host_icon_pinned),
                        contentDescription = null,
                        modifier = Modifier
                            // 置顶角标只占用右上角一小块区域，避免遮挡正文和链接预览标题。
                            .fillMaxWidth(0.25f)
                            .align(Alignment.TopEnd)
                            .alpha(0.6f),
                        tint = appColor
                    )
                }
            }
        }
    }
}

/** 剪贴数据来源 App 和写入剪贴板的时间。 */
@Composable
private fun SourceAppNameWithTime(clip: ClipShowEntity, highlightQuery: String?) {
    val currentDensity = LocalDensity.current
    // 来源 App 与时间区域空间很窄，锁定 fontScale 可以避免系统大字体下挤压到不可读。
    CompositionLocalProvider(LocalDensity provides Density(density = currentDensity.density, fontScale = 1f)) {
        Layout(
            content = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = clip.appIconPath,
                        contentDescription = null,
                        placeholder = rememberVectorPainter(Icons.Filled.ReportGmailerrorred),
                        error = rememberVectorPainter(Icons.Filled.ReportGmailerrorred),
                        modifier = Modifier.size(15.dp),
                        contentScale = ContentScale.Crop,
                        colorFilter = if (clip.appIconPath.isNullOrBlank()) {
                            // 缺少真实图标时使用错误色提示来源未知，真实图标则保持原始颜色。
                            ColorFilter.tint(MaterialTheme.colorScheme.error)
                        } else {
                            null
                        }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    HighlightableText(
                        text = clip.appName ?: stringResource(com.cla.clip.base.general.R.string.base_general_unknow),
                        highlightQuery = highlightQuery,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }

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
            val leftNode = measurables[0]
            val rightNode = measurables[1]

            // 时间最多占一半宽度，优先保留来源 App 图标和名称的识别空间。
            val maxTimeWidth = (constraints.maxWidth * 0.5f).toInt()
            val rightPlaceable = rightNode.measure(
                constraints.copy(
                    minWidth = 0,
                    maxWidth = maxTimeWidth
                )
            )

            // 左右之间固定留 8dp 间隔，避免长 App 名和长时间文本贴在一起。
            val remainingWidth = constraints.maxWidth - rightPlaceable.width - 8.dp.roundToPx()
            val leftMaxWidth = max(0, remainingWidth)
            val leftPlaceable = leftNode.measure(
                constraints.copy(
                    minWidth = 0,
                    maxWidth = leftMaxWidth
                )
            )

            val height = max(leftPlaceable.height, rightPlaceable.height)
            layout(constraints.maxWidth, height) {
                leftPlaceable.placeRelative(0, (height - leftPlaceable.height) / 2)
                rightPlaceable.placeRelative(constraints.maxWidth - rightPlaceable.width, (height - rightPlaceable.height) / 2)
            }
        }
    }
}

/** 剪贴数据的内容显示。 */
@Composable
private fun ClipContent(clip: ClipShowEntity, highlightQuery: String?) {
    val imageUrl = clip.linkImgUrl
    if (imageUrl.isNullOrBlank()) {
        HighlightableText(
            text = clip.content,
            highlightQuery = highlightQuery,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 7
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
                    model = imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(55.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )

                val title = clip.linkTitle
                if (title.isNullOrBlank().not()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    HighlightableText(
                        text = title,
                        highlightQuery = highlightQuery,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HighlightableText(
                text = clip.content,
                highlightQuery = highlightQuery,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 6
            )
        }
    }
}

/**
 * 底部操作按钮区域。
 *
 * 三个按钮保持等宽，搜索页复用后不会因为页面来源不同导致操作入口位置变化。
 */
@Composable
private fun CardButtonContainer(
    clip: ClipShowEntity,
    lineColor: Color,
    onPinToggle: (ClipShowEntity) -> Unit,
    onDelete: (ClipShowEntity) -> Unit,
    onCopy: (ClipShowEntity) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
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

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(18.dp)
                .background(lineColor)
        )

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

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(18.dp)
                .background(lineColor)
        )

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

/**
 * 支持搜索关键词高亮的文本。
 *
 * 高亮只处理用户看得见的字段，匹配词由搜索页传入；普通列表页传 null 时不会额外分配高亮片段。
 */
@Composable
private fun HighlightableText(
    text: String,
    highlightQuery: String?,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    val highlightTextColor = MaterialTheme.colorScheme.onSurface
    val highlightedText = remember(text, highlightQuery, highlightColor, highlightTextColor) {
        text.highlightedBy(
            query = highlightQuery,
            highlightStyle = SpanStyle(
                color = highlightTextColor,
                background = highlightColor
            )
        )
    }

    Text(
        text = highlightedText,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * 根据搜索词生成高亮文本。
 *
 * 这里按空白拆词、去重并做大小写不敏感匹配；如果多个词重叠，只给尚未覆盖的位置加样式，
 * 这样能避免重复添加 Span 造成样式边界不可预测。
 */
private fun String.highlightedBy(query: String?, highlightStyle: SpanStyle): AnnotatedString {
    val words = query
        ?.trim()
        ?.split(Regex("\\s+"))
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.distinctBy { it.lowercase() }
        .orEmpty()

    if (isBlank() || words.isEmpty()) {
        return AnnotatedString(this)
    }

    return buildAnnotatedString {
        append(this@highlightedBy)
        val lowerText = this@highlightedBy.lowercase()
        val highlightedRanges = mutableListOf<IntRange>()
        words.forEach { word ->
            val lowerWord = word.lowercase()
            var startIndex = lowerText.indexOf(lowerWord)
            while (startIndex >= 0) {
                val endExclusive = startIndex + lowerWord.length
                val range = startIndex until endExclusive
                val hasOverlap = highlightedRanges.any { existing ->
                    range.first <= existing.last && range.last >= existing.first
                }
                if (!hasOverlap) {
                    addStyle(highlightStyle, startIndex, endExclusive)
                    highlightedRanges += range
                }
                startIndex = lowerText.indexOf(lowerWord, startIndex + lowerWord.length)
            }
        }
    }
}

/** 空状态屏幕。 */
@Composable
private fun EmptyScreen(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(widthDp = 150, heightDp = 300)
@Composable
private fun ClipCardPreview() {
    val clip = ClipShowEntity(
        id = 1L,
        content = "这是一个示例剪贴板内容，用于预览ClipCard组件的显示效果。用于预览ClipCard组件的显示效果。",
        timestamp = System.currentTimeMillis(),
        formattedTime = "刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚",
        appName = "飞书飞书飞书飞书",
        appIconPath = "https://img2.baidu.com/it/u=3546907450,5411894&fm=253&fmt=auto&app=120&f=JPEG?w=500&h=500",
        appColor = MaterialTheme.colorScheme.error,
        isPinned = false,
        link = "https://www.wanandroid.com/",
        linkImgUrl = "https://img0.baidu.com/it/u=2280054277,2128244139&fm=253&fmt=auto&app=138&f=JPEG?w=973&h=304",
        linkTitle = "这是链接的标题这是链接的标题这是链接的标题"
    )

    ClipCard(
        clip = clip,
        highlightQuery = "示例 飞书",
        onPinToggle = {},
        onDelete = {},
        onCopy = {},
        onClick = {},
        onLongClick = {}
    )
}
