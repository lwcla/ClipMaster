package com.cla.clip.master.ui.dialog

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.utils.showName

/** 删除弹窗 */
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