package com.cla.clip.feature.torrent.search.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cla.clip.feature.torrent.search.R

/** 三类本地数据页面共用的清空确认，不依赖 ViewModel、导航或 Repository。 */
@Composable
internal fun ClearLocalDataConfirmationDialog(
    /** 当前数据类型对应的资源化标题。 */
    title: String,
    /** 明确永久清空后果的资源化正文。 */
    message: String,
    /** 当前数据类型对应的确认按钮文案。 */
    confirmLabel: String,
    /** 用户确认后由页面层连接到对应 ViewModel。 */
    onConfirm: () -> Unit,
    /** 用户取消或点击弹窗外部时关闭确认。 */
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.torrent_search_button_cancel)) } },
    )
}
