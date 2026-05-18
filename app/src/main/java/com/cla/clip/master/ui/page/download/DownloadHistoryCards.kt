package com.cla.clip.master.ui.page.download

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cla.clip.base.general.R
import com.cla.clip.base.general.dao.DownloadTaskData
import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.base.general.utils.toRelativeTimeSpanString
import com.cla.clip.master.ui.widget.ClipMasterCard

/** 图片记录横向缩略图尺寸，固定尺寸可避免加载成功/失败时列表高度抖动。 */
private val ImageThumbSize = 72.dp

/**
 * 视频历史卡片。
 *
 * 普通态点击成功且文件存在的视频会打开系统播放器；已删除、失败或下载中时点击不播放，避免用户进入无效 Intent。
 */
@Composable
internal fun VideoHistoryCard(
    item: DownloadHistoryVideoItem,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelected: () -> Unit,
    onEnterSelection: () -> Unit,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
) {
    ClipMasterCard(
        modifier = Modifier
            .fillMaxWidth(),
        onClick = {
            if (selectionMode) {
                onToggleSelected()
            } else if (item.status == DownloadTaskData.STATUS_SUCCESS && item.localExists) {
                onOpen()
            }
        },
        onLongClick = onEnterSelection,
        contentPadding = PaddingValues(10.dp),
    ) { _ ->
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SelectableMediaBox(
                selected = selected,
                selectionMode = selectionMode,
                deleted = item.deletedLocal,
                bitmap = item.thumbnail,
                icon = Icons.Default.Movie
            )

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HistoryChip(text = videoStatusText(item))
                    item.sizeBytes?.let { HistoryChip(text = formatFileSize(it)) }
                    item.durationMs?.let { HistoryChip(text = formatDuration(it)) }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = item.updateTime.toRelativeTimeSpanString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.running) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { item.progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (!selectionMode && (item.deletedLocal || item.status == DownloadTaskData.STATUS_FAILED)) {
                IconButton(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.base_general_download_history_retry))
                }
            } else if (!selectionMode && item.status == DownloadTaskData.STATUS_SUCCESS && item.localExists) {
                IconButton(onClick = onOpen) {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.base_general_download_history_play_video))
                }
            }
        }
    }
}

/**
 * 图片批次历史卡片。
 *
 * 缩略图只展示成功且仍可读取的图片 URI；点击缩略图弹出单张预览，不在 App 内做左右切换或完整相册。
 */
@Composable
internal fun ImageHistoryCard(
    item: DownloadHistoryImageBatch,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelected: () -> Unit,
    onEnterSelection: () -> Unit,
    onRetry: () -> Unit,
    onPreviewImage: (String) -> Unit,
) {
    ClipMasterCard(
        modifier = Modifier
            .fillMaxWidth(),
        onClick = { if (selectionMode) onToggleSelected() },
        onLongClick = onEnterSelection,
        contentPadding = PaddingValues(10.dp),
    ) { _ ->
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.Image,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.updateTime.toRelativeTimeSpanString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!selectionMode && item.deletedLocal) {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.base_general_download_history_retry))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HistoryChip(text = imageStatusText(item))
                HistoryChip(text = stringResource(R.string.base_general_download_history_image_count, item.successCount, item.totalCount))
                if (item.failedCount > 0) HistoryChip(text = stringResource(R.string.base_general_image_failed_count, item.failedCount))
                if (item.filteredCount > 0) HistoryChip(text = stringResource(R.string.base_general_image_filtered_count, item.filteredCount))
                if (item.unreadableCount > 0) HistoryChip(text = stringResource(R.string.base_general_download_history_unreadable_image_count, item.unreadableCount))
            }

            item.outputFolderName()?.let { folderName ->
                Spacer(Modifier.height(6.dp))
                FolderNameText(folderName = folderName)
            }

            Spacer(Modifier.height(8.dp))
            if (item.imageUris.isEmpty()) {
                DeletedPlaceholder(text = stringResource(R.string.base_general_download_history_local_file_deleted))
            } else {
                HistoryImagePreviewGrid(
                    imageUris = item.imageUris,
                    selectionMode = selectionMode,
                    onPreviewImage = onPreviewImage
                )
            }
        }
    }
}

/** 图片批次输出文件夹展示区；文件夹名允许多行，避免同标题下载只靠被省略的 chip 无法区分。 */
@Composable
private fun FolderNameText(folderName: String) {
    Column {
        Text(
            text = stringResource(R.string.base_general_download_history_image_folder),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = folderName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 图片批次横向缩略图。
 *
 * 8 张以内维持单行，便于快速浏览小批次；超过 8 张后每列最多放两张图，减少长批次横向滑动距离。
 * LazyRow 只按列懒加载，因此可以展示全部可读成功图片，同时避免一次性把大批量缩略图全部组合出来。
 */
@Composable
private fun HistoryImagePreviewGrid(
    imageUris: List<String>,
    selectionMode: Boolean,
    onPreviewImage: (String) -> Unit,
) {
    // 超过 8 张才启用双行，避免小批次记录因为第二行留白而显得过重。
    val useTwoRows = imageUris.size > 8
    // LazyRow 的 item 是“列”；单行模式每列 1 张，双行模式每列最多 2 张。
    val columns = remember(imageUris, useTwoRows) {
        if (useTwoRows) imageUris.chunked(2) else imageUris.map { listOf(it) }
    }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 2.dp)
    ) {
        items(columns) { column ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                column.forEach { uri ->
                    HistoryImageThumb(
                        uri = uri,
                        selectionMode = selectionMode,
                        onPreviewImage = onPreviewImage
                    )
                }
                if (useTwoRows && column.size == 1) {
                    Spacer(Modifier.size(ImageThumbSize))
                }
            }
        }
    }
}

/** 单张历史图片缩略图，点击只预览当前图片，不进入左右切换相册。 */
@Composable
private fun HistoryImageThumb(
    uri: String,
    selectionMode: Boolean,
    onPreviewImage: (String) -> Unit,
) {
    AsyncImage(
        model = uri,
        contentDescription = null,
        modifier = Modifier
            .size(ImageThumbSize)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = !selectionMode) { onPreviewImage(uri) },
        contentScale = ContentScale.Crop,
        error = rememberVectorPainter(Icons.Default.BrokenImage),
        placeholder = rememberVectorPainter(Icons.Default.Image)
    )
}

/** 可选中的视频缩略图区域，统一处理首帧、已删除和选择覆盖层。 */
@Composable
private fun SelectableMediaBox(
    selected: Boolean,
    selectionMode: Boolean,
    deleted: Boolean,
    bitmap: Bitmap?,
    icon: ImageVector,
) {
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            deleted -> DeletedPlaceholder(text = stringResource(R.string.base_general_download_history_deleted_short))

            else -> Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (selectionMode) {
            Surface(
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
                modifier = Modifier.fillMaxSize()
            ) {}
            Icon(
                imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            )
        }
    }
}

/** 历史列表标签，用于展示状态、数量、体积和时长等短信息。 */
@Composable
private fun HistoryChip(text: String) {
    AssistChip(onClick = {}, label = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) })
}

/** 本地文件不可读占位，既用于视频首帧区域，也用于图片批次缩略图区。 */
@Composable
internal fun DeletedPlaceholder(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 将视频任务状态映射为用户可见文案；失败态优先展示具体错误。 */
@Composable
private fun videoStatusText(item: DownloadHistoryVideoItem): String {
    return when {
        item.deletedLocal -> stringResource(R.string.base_general_download_history_local_file_deleted)
        item.status == DownloadTaskData.STATUS_SUCCESS -> stringResource(R.string.base_general_download_completed)
        item.status == DownloadTaskData.STATUS_FAILED -> item.errorMsg ?: stringResource(R.string.base_general_download_failed)
        item.status == DownloadTaskData.STATUS_MERGING -> stringResource(R.string.base_general_merge_progress, item.progress)
        else -> stringResource(R.string.base_general_download_progress, item.progress)
    }
}

/** 将图片批次状态映射为用户可见文案。 */
@Composable
private fun imageStatusText(item: DownloadHistoryImageBatch): String {
    return when {
        item.deletedLocal -> stringResource(R.string.base_general_download_history_local_file_deleted)
        item.status == ImageExtractBatchData.STATUS_SUCCESS -> stringResource(R.string.base_general_download_completed)
        item.status == ImageExtractBatchData.STATUS_PARTIAL_SUCCESS -> stringResource(R.string.base_general_download_history_partial_success)
        item.status == ImageExtractBatchData.STATUS_FILTERED -> stringResource(R.string.base_general_download_history_filtered)
        item.status == ImageExtractBatchData.STATUS_DOWNLOADING -> stringResource(R.string.base_general_image_download_progress, item.successCount + item.failedCount + item.filteredCount, item.totalCount)
        else -> stringResource(R.string.base_general_download_failed)
    }
}

/** 从图片批次输出目录中提取最后一级文件夹名，用于区分同标题多次下载的不同保存位置。 */
private fun DownloadHistoryImageBatch.outputFolderName(): String? {
    return outputDir
        ?.trim()
        ?.replace('\\', '/')
        ?.trim('/')
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
}

/** 格式化文件大小，保持和图片提取页相同的 B/KB/MB/GB 粒度。 */
@Composable
private fun formatFileSize(bytes: Long): String {
    val value = bytes.toDouble()
    return when {
        bytes < 1024L -> stringResource(R.string.base_general_file_size_bytes, bytes)
        bytes < 1024L * 1024L -> stringResource(R.string.base_general_file_size_kb, value / 1024.0)
        bytes < 1024L * 1024L * 1024L -> stringResource(R.string.base_general_file_size_mb, value / 1024.0 / 1024.0)
        else -> stringResource(R.string.base_general_file_size_gb, value / 1024.0 / 1024.0 / 1024.0)
    }
}

/** 格式化视频时长，短视频展示分秒，超过一小时展示时分秒。 */
private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
