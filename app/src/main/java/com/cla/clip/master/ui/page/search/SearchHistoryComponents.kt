package com.cla.clip.master.ui.page.search

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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.cla.clip.base.general.dao.SearchHistoryData
import java.util.Locale

/**
 * 搜索历史覆盖面板。
 *
 * 面板由 SearchPage 的 History 模式控制显示；它只负责渲染历史列表和回调用户操作，
 * 不直接绑定搜索框焦点，避免键盘、主题切换或滚动状态变化误关闭历史模式。
 */
@Composable
internal fun SearchHistoryPanel(
    listState: LazyListState,
    histories: List<SearchHistoryData>,
    query: String,
    contentPadding: PaddingValues = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 24.dp),
    onHistoryClick: (SearchHistoryData) -> Unit,
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
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(com.cla.clip.base.general.R.string.base_general_search_history),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onClearHistories) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(com.cla.clip.base.general.R.string.base_general_clear_search_history))
                    }
                }
            }

            items(
                items = histories,
                key = { it.id }
            ) { history ->
                SearchHistoryRow(
                    history = history,
                    query = query,
                    onClick = { onHistoryClick(history) },
                    onDelete = { onDeleteHistory(history.id) }
                )
            }
        }
    }
}

/**
 * 单条搜索历史。
 *
 * 点击整行会恢复关键词并保存更新时间；删除按钮只删除历史本身，不触发搜索框清空或筛选重置。
 */
@Composable
private fun SearchHistoryRow(
    history: SearchHistoryData,
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
            text = history.query.highlightHistoryMatch(query),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        )
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(com.cla.clip.base.general.R.string.base_general_delete_search_history),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 清空当前范围搜索历史确认框。
 *
 * 清空动作只作用于当前普通/折叠搜索范围；确认框避免用户误删一组仍有使用价值的历史提示。
 */
@Composable
internal fun ClearSearchHistoryDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(com.cla.clip.base.general.R.string.base_general_clear_search_history_title)) },
        text = { Text(stringResource(com.cla.clip.base.general.R.string.base_general_clear_search_history_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(com.cla.clip.base.general.R.string.base_general_clear_search_history))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.cla.clip.base.general.R.string.base_general_cancel))
            }
        }
    )
}

/**
 * 为历史项高亮当前输入命中的片段。
 *
 * 历史匹配按规范化后的包含关系执行，这里只做可见文本上的大小写不敏感高亮；查询为空时不加样式。
 */
@Composable
private fun String.highlightHistoryMatch(query: String): AnnotatedString {
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    val highlightTextColor = MaterialTheme.colorScheme.onSurface
    return remember(this, query, highlightColor, highlightTextColor) {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        if (isBlank() || normalizedQuery.isBlank()) {
            return@remember AnnotatedString(this)
        }

        val normalizedText = lowercase(Locale.ROOT)
        val highlightedRanges = mutableListOf<IntRange>()
        buildAnnotatedString {
            append(this@highlightHistoryMatch)
            var startIndex = normalizedText.indexOf(normalizedQuery)
            while (startIndex >= 0) {
                val endExclusive = startIndex + normalizedQuery.length
                val range = startIndex until endExclusive
                val hasOverlap = highlightedRanges.any { existing ->
                    range.first <= existing.last && range.last >= existing.first
                }
                if (!hasOverlap) {
                    addStyle(
                        style = SpanStyle(
                            color = highlightTextColor,
                            background = highlightColor
                        ),
                        start = startIndex,
                        end = endExclusive
                    )
                    highlightedRanges += range
                }
                startIndex = normalizedText.indexOf(normalizedQuery, startIndex + normalizedQuery.length)
            }
        }
    }
}
