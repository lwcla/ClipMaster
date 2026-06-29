package com.cla.clip.master.ui.dialog

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.utils.showName

/**
 * 剪贴删除选择弹窗。
 *
 * 其他剪贴页面删除时统一复用该组件，避免“移入回收站 / 彻底删除 / 不可恢复提示”的文案和危险按钮样式分叉。
 */
@Composable
fun ClipDeleteChoiceDialog(
    clip: ClipShowEntity?,
    onDismiss: () -> Unit,
    onMoveToRecycleBin: (ClipShowEntity) -> Unit,
    onDeletePermanently: (ClipShowEntity) -> Unit,
) {
    if (clip == null) {
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(com.cla.clip.base.general.R.string.base_general_delete_clip_title)) },
        text = {
            Text(
                stringResource(
                    com.cla.clip.base.general.R.string.base_general_delete_clip_choice_message,
                    clip.content.showName
                )
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onMoveToRecycleBin(clip)
            }) {
                Text(stringResource(com.cla.clip.base.general.R.string.base_general_move_to_recycle_bin))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                onDeletePermanently(clip)
            }) {
                Text(
                    text = stringResource(com.cla.clip.base.general.R.string.base_general_delete_permanently),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    )
}

/**
 * 批量剪贴删除选择弹窗。
 *
 * 批量场景只展示数量，不展示任何剪贴内容，避免弹窗泄露隐私或被长文本撑开。
 */
@Composable
fun ClipBatchDeleteChoiceDialog(
    selectedCount: Int,
    visible: Boolean,
    onDismiss: () -> Unit,
    onMoveToRecycleBin: () -> Unit,
    onDeletePermanently: () -> Unit,
) {
    if (!visible) {
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(com.cla.clip.base.general.R.string.base_general_delete_clip_title)) },
        text = {
            Text(
                stringResource(
                    com.cla.clip.base.general.R.string.base_general_delete_selected_clip_choice_message,
                    selectedCount
                )
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onMoveToRecycleBin()
            }) {
                Text(stringResource(com.cla.clip.base.general.R.string.base_general_move_to_recycle_bin))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                onDeletePermanently()
            }) {
                Text(
                    text = stringResource(com.cla.clip.base.general.R.string.base_general_delete_permanently),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    )
}

/**
 * 兼容旧调用点的单动作删除弹窗。
 *
 * 新剪贴页面应优先使用 `ClipDeleteChoiceDialog`；该组件保留给尚未迁移或非回收站语义的简单确认场景。
 */
@Composable
fun DeleteDialog(
    clip: ClipShowEntity?,
    onDismiss: () -> Unit,
    onConfirmDelete: (ClipShowEntity) -> Unit
) {
    if (clip == null) {
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(com.cla.clip.base.general.R.string.base_general_prompt)) },
        text = { Text(stringResource(com.cla.clip.base.general.R.string.base_general_sure_to_delete, clip.content.showName)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirmDelete(clip)
            }) {
                Text(stringResource(com.cla.clip.base.general.R.string.base_general_sure))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.cla.clip.base.general.R.string.base_general_cancel))
            }
        }
    )
}
