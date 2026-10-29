package com.cla.clip.master.ui.widget.clip

import android.graphics.Bitmap
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.cla.clip.master.ui.widget.text.SearchMatchMode
import com.cla.clip.master.ui.widget.text.buildSearchHighlightedText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.master.R
import com.cla.clip.master.ui.widget.sourceapp.AppIconImage
import com.cla.clip.base.general.utils.toSourceAppDisplayName
import com.cla.clip.master.ui.widget.rememberDeletedFormattedTime
import com.cla.clip.master.ui.widget.rememberFoldedFormattedTime
import com.cla.clip.master.ui.widget.rememberFormattedTime
import kotlin.math.max

/** 共享来源行统一默认图标；普通范围展示写入时间，折叠/回收站展示各自业务时间。 */
@Composable
internal fun SourceAppNameWithTime(
    clip: ClipShowEntity,
    /** 本次搜索的关键词与允许高亮字段。 */
    highlight: ClipSearchHighlight?,
    timeMode: ClipCardTimeMode,
    /** 卡片局部会话图像与边框取色同源，展示实体不持图片。 */
    icon: Bitmap?,
) {
    /** 密度与时间模式沿用内容记录，不受图标版本影响。 */
    val currentDensity = LocalDensity.current
    /** 时间语义由页面指定，与异步图像加载无关。 */
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
        /** 名称有效性不依赖图标是否存在。 */
        val sourceAppName = clip.sourceAppName.toSourceAppDisplayName()
        Layout(
            content = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIconImage(
                        icon = icon,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    HighlightableText(
                        text = sourceAppName,
                        highlightQuery = highlight?.queryFor(ClipHighlightField.SourceName)?.takeIf { !clip.sourceAppName.isNullOrBlank() },
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
internal fun ClipContent(
    /** 当前卡片正文及预览。 */ clip: ClipShowEntity,
    /** 与当前结果查询对应的高亮配置。 */ highlight: ClipSearchHighlight?,
) {
    Column {
        LinkPreviewContent(clip, highlight)

        HighlightableText(
            text = clip.content,
            highlightQuery = highlight?.queryForContent(clip.content, clip.link),
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
private fun LinkPreviewContent(
    /** 当前记录已有的预览，不触发额外读取。 */ clip: ClipShowEntity,
    /** 标题和链接各自按当前字段集合决定高亮。 */ highlight: ClipSearchHighlight?,
) {
    /** 已提取且非空的链接地址。 */
    val link = clip.link?.takeIf { it.isNotBlank() }
    /** 真实预览标题，缺失时不伪造标题命中。 */
    val title = clip.linkTitle?.takeIf { it.isNotBlank() }
    /** 已有预览图地址，仍由图片组件按可见组合读取。 */
    val imageUrl = clip.linkImgUrl?.takeIf { it.isNotBlank() }
    /** 原文已经是同一链接时不重复显示。 */
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
                            highlightQuery = highlight?.queryFor(ClipHighlightField.Title),
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
                            highlightQuery = highlight?.queryFor(ClipHighlightField.Link),
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
 * 禁用时只关闭输入和可访问点击，不移除或改变图标，供拖动/动画期间保持布局稳定。
 */
@Composable
internal fun SwipeActionButton(
    painterRes: Int,
    iconContentDescription: String,
    iconTint: Color,
    iconSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 由调用方控制交互忙碌期；默认启用以兼容原有独立使用方式。 */
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick),
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
    /** 原始展示文字，构建器不会修改它。 */ text: String,
    /** null 表示本字段不参与本次高亮。 */ highlightQuery: String?,
    /** 非命中文字继续使用调用方样式。 */ style: TextStyle,
    /** 调用方布局接口。 */ modifier: Modifier = Modifier,
    /** 非命中部分的普通前景色。 */ color: Color = Color.Unspecified,
    /** 保留卡片各区域已有行数和省略规则。 */ maxLines: Int = Int.MAX_VALUE,
) {
    /** 主题提供亮暗适配的红色，命中字串只改颜色与字重。 */
    val highlightTextColor = MaterialTheme.colorScheme.error
    /** 原文、关键词和主题改变时才重算区间，不在每次重组重复扫描。 */
    val highlightedText = remember(text, highlightQuery, highlightTextColor) {
        // 七字段查询采用完整短语包含，高亮也必须保留输入中的空格和连续性。
        buildSearchHighlightedText(text, highlightQuery, SearchMatchMode.Phrase, highlightTextColor)
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
