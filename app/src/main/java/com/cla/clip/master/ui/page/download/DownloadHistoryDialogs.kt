package com.cla.clip.master.ui.page.download

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.R
import com.cla.clip.master.ui.widget.SelectionActionBar as SharedSelectionActionBar

/** 底部多选操作条，固定只承载删除动作，清空分类仍在标题栏右侧。 */
@Composable
internal fun SelectionActionBar(
    selectedCount: Int,
    onDelete: () -> Unit,
) {
    SharedSelectionActionBar(
        selectedText = stringResource(R.string.base_general_download_history_selected_count, selectedCount),
        actionText = stringResource(R.string.base_general_delete),
        onAction = onDelete,
    )
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
                    text = if (request.allowDeleteFiles) {
                        when (request.kind) {
                            DeleteRequestKind.Selected -> stringResource(R.string.base_general_download_history_delete_selected_message, request.count)
                            DeleteRequestKind.ClearTab -> stringResource(R.string.base_general_download_history_clear_message, request.count)
                        }
                    } else {
                        when (request.kind) {
                            DeleteRequestKind.Selected -> stringResource(R.string.base_general_download_history_delete_selected_magnet_message, request.count)
                            DeleteRequestKind.ClearTab -> stringResource(R.string.base_general_download_history_clear_magnet_message, request.count)
                        }
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
            if (request.allowDeleteFiles) {
                Button(onClick = onDeleteRecordAndFiles) {
                    Text(stringResource(R.string.base_general_download_history_delete_records_and_files))
                }
            } else {
                Button(onClick = onDeleteRecordOnly) {
                    Text(stringResource(R.string.base_general_download_history_delete_records_only))
                }
            }
        },
        dismissButton = {
            Row {
                if (request.allowDeleteFiles) {
                    TextButton(onClick = onDeleteRecordOnly) {
                        Text(stringResource(R.string.base_general_download_history_delete_records_only))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.base_general_cancel))
                }
            }
        }
    )
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

    /** 是否提供删除本地文件选项；磁力记录没有本地文件关联，只允许删除记录。 */
    val allowDeleteFiles: Boolean = true,
)
