package com.cla.clip.master.ui.page.magnet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.R
import com.cla.clip.base.general.magnet.MagnetHighlightSnippet
import com.cla.clip.base.general.magnet.MagnetSearchHighlightFormatter
import com.cla.clip.base.general.magnet.cache.MagnetSearchResult
import com.cla.clip.master.ui.widget.ClipMasterCard

/** 磁力搜索结果卡片；整卡点击复制并尝试打开下载器，右侧按钮提供显式复制和打开动作。 */
@Composable
internal fun MagnetResultCard(
    item: MagnetSearchResult,
    query: String,
    onCopyOnly: () -> Unit,
    onCopyAndOpen: () -> Unit,
) {
    ClipMasterCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onCopyAndOpen,
        contentPadding = PaddingValues(12.dp),
    ) { _ ->
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title.highlightMagnetText(query),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    MagnetChip(text = magnetSourceName(item.sourceId))
                    MagnetChip(text = item.category?.takeIf { it.isNotBlank() } ?: stringResource(R.string.base_general_magnet_uncategorized))
                    MagnetChip(text = formatMagnetSize(item.sizeBytes))
                }
                item.description?.let { description ->
                    MagnetSearchHighlightFormatter.buildSnippet(description, query)?.let { snippet ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = snippet.highlightMagnetSnippet(query),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.width(8.dp))
            Column {
                IconButton(onClick = onCopyOnly) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.base_general_magnet_copy_only)
                    )
                }
                IconButton(onClick = onCopyAndOpen) {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = stringResource(R.string.base_general_magnet_open_external)
                    )
                }
            }
        }
    }
}

@Composable
private fun MagnetChip(text: String) {
    AssistChip(
        onClick = {},
        label = {
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

@Composable
private fun String.highlightMagnetText(query: String): AnnotatedString {
    val ranges = remember(this, query) { MagnetSearchHighlightFormatter.findRanges(this, query) }
    return highlightedAnnotatedString(text = this, ranges = ranges)
}

@Composable
private fun MagnetHighlightSnippet.highlightMagnetSnippet(query: String): AnnotatedString {
    val visibleText = buildString {
        if (prefixEllipsis) append("...")
        append(text)
        if (suffixEllipsis) append("...")
    }
    val offset = if (prefixEllipsis) 3 else 0
    val safeRanges = remember(this, query) { ranges.map { (it.first + offset)..(it.last + offset) } }
    return highlightedAnnotatedString(text = visibleText, ranges = safeRanges)
}

@Composable
private fun highlightedAnnotatedString(text: String, ranges: List<IntRange>): AnnotatedString {
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    val highlightTextColor = MaterialTheme.colorScheme.onSurface
    return remember(text, ranges, highlightColor, highlightTextColor) {
        if (ranges.isEmpty()) return@remember AnnotatedString(text)
        buildAnnotatedString {
            append(text)
            ranges.forEach { range ->
                val start = range.first.coerceIn(0, text.length)
                val endExclusive = (range.last + 1).coerceIn(start, text.length)
                if (start < endExclusive) {
                    addStyle(
                        style = SpanStyle(
                            color = highlightTextColor,
                            background = highlightColor
                        ),
                        start = start,
                        end = endExclusive
                    )
                }
            }
        }
    }
}
