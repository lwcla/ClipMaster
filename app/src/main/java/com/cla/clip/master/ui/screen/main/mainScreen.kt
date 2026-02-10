package com.cla.clip.master.ui.screen.main

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cla.clip.base.general.entity.ClipData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主屏幕的入口Composable。
 *
 * @param viewModel Hilt自动注入的MainViewModel实例。
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

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
    pinnedClips: List<ClipData>,
    latestClips: List<ClipData>,
    onPinToggle: (ClipData) -> Unit,
    onDelete: (ClipData) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 置顶区域
        if (pinnedClips.isNotEmpty()) {
            item {
                Text(
                    text = "Pinned",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(pinnedClips, key = { it.id }) { clip ->
                ClipCard(
                    clip = clip,
                    onPinToggle = onPinToggle,
                    onDelete = onDelete
                )
            }
        }

        // 最新区域
        if (latestClips.isNotEmpty()) {
            item {
                Text(
                    text = "Latest",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }
            items(latestClips, key = { it.id }) { clip ->
                ClipCard(
                    clip = clip,
                    onPinToggle = onPinToggle,
                    onDelete = onDelete
                )
            }
        }
    }
}

/**
 * 显示单个剪贴板内容的卡片。
 */
@Composable
private fun ClipCard(
    clip: ClipData,
    onPinToggle: (ClipData) -> Unit,
    onDelete: (ClipData) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = {
//             getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = android.content.ClipData.newPlainText("ClipMaster", clip.content)
            clipboard.setPrimaryClip(clipData)
            Toast.makeText(context, "已复制:${clip.content.take(10)}...", Toast.LENGTH_SHORT).show()
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = clip.content,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 时间戳和来源
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(clip.timestamp)),
                        style = MaterialTheme.typography.bodySmall
                    )
                    clip.sourceAppName?.let {
                        Text(
                            text = "from: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 操作按钮
                IconButton(onClick = { onPinToggle(clip) }) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "Pin",
                        tint = if (clip.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { onDelete(clip) }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete"
                    )
                }
            }
        }
    }
}

/**
 * 加载状态屏幕。
 */
@Composable
private fun LoadingScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
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