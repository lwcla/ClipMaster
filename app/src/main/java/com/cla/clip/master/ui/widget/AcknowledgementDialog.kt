package com.cla.clip.master.ui.widget

import android.content.res.Configuration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.cla.clip.master.R

/** 可关闭的信息确认弹窗，纯展示、不启动任务；业务文案与关闭后行为由调用方决定。 */
@Composable
internal fun AcknowledgementDialog(
    /** 调用方的本地化标题，不绑定具体页面或业务实体。 */
    title: String,
    /** 已确认事实的说明，不能用入队确认文案冒充任务完成。 */
    message: String,
    /** 用户明确结束反馈的按钮文案。 */
    confirmText: String,
    /** 确认按钮、外部点击和返回只请求关闭；任务取消由业务另外决定。 */
    onDismiss: () -> Unit,
    /** 公共样式入口，布局与主题默认沿用 Material。 */
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        // 标题与正文由调用方传入，不读取页面 ViewModel、Repository 或导航。
        title = { Text(title) },
        text = { Text(message) },
        // 与外部点击使用相同关闭回调，确认不再提交一次后台任务。
        confirmButton = { TextButton(onClick = onDismiss) { Text(confirmText) } },
    )
}

/** 窄屏、大字体和深色下检查长说明自然换行；Preview 不执行任务或状态保存。 */
@Preview(widthDp = 240, fontScale = 1.6f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AcknowledgementDialogPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        AcknowledgementDialog(
            title = stringResource(R.string.host_backup_task_created_title),
            message = stringResource(R.string.host_backup_upload_queued),
            confirmText = stringResource(R.string.host_backup_task_created_confirm),
            // Preview 只展示结构，关闭不产生业务副作用。
            onDismiss = {},
        )
    }
}
