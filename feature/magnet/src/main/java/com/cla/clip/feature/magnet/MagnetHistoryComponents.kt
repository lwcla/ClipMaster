package com.cla.clip.feature.magnet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cla.clip.feature.magnet.R
import com.cla.clip.base.general.R as BaseR
import com.cla.clip.feature.magnet.data.MagnetSearchHistoryData
import com.cla.clip.feature.magnet.MagnetSearchHighlightFormatter
import com.cla.clip.base.general.widget.DeleteIconButton

/** 磁力搜索历史覆盖面板，语义独立于剪贴搜索历史，避免两个 DAO 类型相互耦合。 */
@Composable
internal fun MagnetHistoryPanel(
    histories: List<MagnetSearchHistoryData>,
    query: String,
    onHistoryClick: (MagnetSearchHistoryData) -> Unit,
    onDeleteHistory: (Long) -> Unit,
    onClearHistories: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        color = MaterialTheme.colorScheme.surface
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 24.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(BaseR.string.base_general_search_history),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onClearHistories) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(BaseR.string.base_general_clear_search_history))
                    }
                }
            }

            items(
                items = histories,
                key = { it.id }
            ) { history ->
                MagnetHistoryRow(
                    history = history,
                    query = query,
                    onClick = { onHistoryClick(history) },
                    onDelete = { onDeleteHistory(history.id) }
                )
            }
        }
    }
}

@Composable
private fun MagnetHistoryRow(
    history: MagnetSearchHistoryData,
    query: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = history.query.highlightMagnetHistoryMatch(query),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        )
        DeleteIconButton(
            contentDescription = stringResource(BaseR.string.base_general_delete_search_history),
            onClick = onDelete
        )
    }
}

/** 清空磁力搜索历史确认框，只影响磁力搜索历史。 */
@Composable
internal fun ClearMagnetHistoryDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(BaseR.string.base_general_clear_search_history_title)) },
        text = { Text(stringResource(R.string.magnet_feature_clear_magnet_search_history_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(BaseR.string.base_general_clear_search_history))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(BaseR.string.base_general_cancel))
            }
        }
    )
}

@Composable
private fun String.highlightMagnetHistoryMatch(query: String): AnnotatedString {
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    val highlightTextColor = MaterialTheme.colorScheme.onSurface
    return remember(this, query, highlightColor, highlightTextColor) {
        val ranges = MagnetSearchHighlightFormatter.findRanges(this, query)
        if (ranges.isEmpty()) return@remember AnnotatedString(this)
        buildAnnotatedString {
            append(this@highlightMagnetHistoryMatch)
            ranges.forEach { range ->
                addStyle(
                    style = SpanStyle(
                        color = highlightTextColor,
                        background = highlightColor
                    ),
                    start = range.first,
                    end = range.last + 1
                )
            }
        }
    }
}
