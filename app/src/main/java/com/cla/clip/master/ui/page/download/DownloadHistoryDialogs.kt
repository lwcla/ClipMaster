package com.cla.clip.master.ui.page.download

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cla.clip.base.general.R

/** 底部多选操作条，固定只承载删除动作，清空分类仍在标题栏右侧。 */
@Composable
internal fun SelectionActionBar(
    selectedCount: Int,
    onDelete: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.base_general_download_history_selected_count, selectedCount),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.base_general_delete))
                }
            }
        }
    }
}

/** 删除方式选择弹窗，明确区分“仅删除记录”和“删除记录和本地文件”。 */
@Composable
internal fun DeleteModeDialog(
    request: DeleteRequestUi,
    onDismiss: () -> Unit,
    onDeleteRecordOnly: () -> Unit,
    onDeleteRecordAndFiles: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.base_general_download_history_delete_title)) },
        text = {
            Column {
                Text(
                    text = when (request.kind) {
                        DeleteRequestKind.Selected -> stringResource(R.string.base_general_download_history_delete_selected_message, request.count)
                        DeleteRequestKind.ClearTab -> stringResource(R.string.base_general_download_history_clear_message, request.count)
                    }
                )
                if (request.hasRunning) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.base_general_download_history_delete_running_tip),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDeleteRecordAndFiles) {
                Text(stringResource(R.string.base_general_download_history_delete_records_and_files))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDeleteRecordOnly) {
                    Text(stringResource(R.string.base_general_download_history_delete_records_only))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.base_general_cancel))
                }
            }
        }
    )
}

/**
 * 单张图片预览底部弹窗。
 *
 * 图片按弹窗宽度完整排版，并把图片区域做成纵向滚动容器；高图不会被固定高度裁切，用户可以继续向下滑查看完整内容。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImagePreviewBottomSheet(
    uri: String,
    onDismiss: () -> Unit,
) {
    // 跳过半展开态，打开后直接给图片预览尽量多的垂直空间；真正超出屏幕的部分交给图片区域滚动。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 每次打开预览都使用独立滚动状态，避免上一张高图的滚动位置影响下一张图片。
    val imageScrollState = rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.base_general_image_preview),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.base_general_sure))
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(imageScrollState),
                contentAlignment = Alignment.TopCenter
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                    error = rememberVectorPainter(Icons.Default.BrokenImage),
                    placeholder = rememberVectorPainter(Icons.Default.Image)
                )
            }
        }
    }
}

/** 删除弹窗的来源，决定确认后调用删除选中记录还是清空当前分类。 */
internal enum class DeleteRequestKind {
    /** 删除当前多选选中的记录。 */
    Selected,

    /** 清空当前 Tab 下全部记录。 */
    ClearTab
}

/** 删除弹窗 UI 参数，集中记录数量和是否包含进行中任务。 */
internal data class DeleteRequestUi(
    /** 删除动作来源。 */
    val kind: DeleteRequestKind,

    /** 本次会影响的记录数量。 */
    val count: Int,

    /** 是否包含正在下载的记录；包含时弹窗提示会先停止下载任务。 */
    val hasRunning: Boolean,
)
