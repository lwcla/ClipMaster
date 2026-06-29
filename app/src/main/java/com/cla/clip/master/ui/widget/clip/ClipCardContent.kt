package com.cla.clip.master.ui.widget.clip

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ReportGmailerrorred
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.utils.toSourceAppDisplayName
import com.cla.clip.master.ui.widget.rememberDeletedFormattedTime
import com.cla.clip.master.ui.widget.rememberFoldedFormattedTime
import com.cla.clip.master.ui.widget.rememberFormattedTime
import kotlin.math.max

/** 剪贴数据来源 App 和时间，普通范围展示写入时间，折叠范围展示折叠时间，回收站展示删除时间。 */
@Composable
internal fun SourceAppNameWithTime(
    clip: ClipShowEntity,
    highlightQuery: String?,
    timeMode: ClipCardTimeMode,
) {
    val currentDensity = LocalDensity.current
    val timeText = when (timeMode) {
        ClipCardTimeMode.ClipTime -> clip.rememberFormattedTime()
        ClipCardTimeMode.FoldedTime -> clip.rememberFoldedFormattedTime(
            prefix = stringResource(com.cla.clip.base.general.R.string.base_general_folded_at_prefix)
        )
        ClipCardTimeMode.DeletedTime -> clip.rememberDeletedFormattedTime(
            prefix = stringResource(com.cla.clip.base.general.R.string.base_general_deleted_at_prefix)
        )
    }
    // 来源 App 与时间区域空间很窄，锁定 fontScale 可以避免系统大字体下挤压到不可读。
    CompositionLocalProvider(LocalDensity provides Density(density = currentDensity.density, fontScale = 1f)) {
        val sourceAppName = clip.appName.toSourceAppDisplayName()
        // 图标请求模型把 iconHash 带入缓存 key，避免异步补图覆盖同一路径后仍显示旧缓存。
        val sourceAppIconModel = rememberSourceAppIconModel(clip.appIconPath, clip.appIconHash)
        Layout(
            content = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = sourceAppIconModel,
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
                        text = sourceAppName,
                        highlightQuery = highlightQuery,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }

                Text(
                    text = timeText,
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

/**
 * 剪贴数据的内容显示。
 *
 * 链接预览、链接标题和链接地址优先集中显示在顶部，原始剪贴字符串始终保留三行以内，
 * 让列表 item 在保留必要上下文的同时不会被长文本撑得过高。
 */
@Composable
internal fun ClipContent(clip: ClipShowEntity, highlightQuery: String?) {
    Column {
        LinkPreviewContent(clip, highlightQuery)

        HighlightableText(
            text = clip.content,
            highlightQuery = highlightQuery,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 3
        )
    }
}

/**
 * 链接预览内容区。
 *
 * 只要记录里存在链接、标题或预览图就展示该区域；如果剪贴字符串本身就是链接，
 * 链接地址不再重复显示，避免一个 item 内出现两段完全相同的 URL。
 */
@Composable
private fun LinkPreviewContent(clip: ClipShowEntity, highlightQuery: String?) {
    val link = clip.link?.takeIf { it.isNotBlank() }
    val title = clip.linkTitle?.takeIf { it.isNotBlank() }
    val imageUrl = clip.linkImgUrl?.takeIf { it.isNotBlank() }
    val shouldShowLink = link != null && link != clip.content

    if ((link == null || !shouldShowLink) && title == null && imageUrl == null) {
        return
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (title != null) {
                        HighlightableText(
                            text = title,
                            highlightQuery = highlightQuery,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 2
                        )
                    }

                    if (shouldShowLink) {
                        if (title != null) {
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                        HighlightableText(
                            text = link.orEmpty(),
                            highlightQuery = highlightQuery,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * 侧滑露出的单个操作按钮。
 *
 * 按钮高度由外层 item 决定，图标始终放在点击区域中心；点击区域保持透明，
 * 让侧滑操作区沿用卡片背后的整体底色。
 */
@Composable
internal fun SwipeActionButton(
    painterRes: Int,
    iconContentDescription: String,
    iconTint: Color,
    iconSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(painterRes),
            contentDescription = iconContentDescription,
            tint = iconTint,
            modifier = Modifier.size(iconSize)
        )
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
internal fun EmptyScreen(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
