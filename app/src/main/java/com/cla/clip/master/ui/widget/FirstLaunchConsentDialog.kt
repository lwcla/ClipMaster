package com.cla.clip.master.ui.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.cla.clip.master.R
import com.cla.clip.master.ui.theme.ClipMasterThemeTokens
import com.cla.clip.master.ui.theme.ClipMaterTheme

/** 首次说明的纯展示组件；关闭和返回使用暂不开启回调，不自行申请权限或写设置。 */
@Composable
internal fun FirstLaunchConsentDialog(
    saving: Boolean,
    saveFailed: Boolean,
    onAccept: () -> Unit,
    onDefer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    /** 内容较长时允许滚动，按钮固定可见，支持小屏及大字体。 */
    val scroll = rememberScrollState()
    /** 段落间距沿用应用主题。 */
    val spacing = ClipMasterThemeTokens.tokens.spacing
    AlertDialog(
        modifier = modifier,
        onDismissRequest = { if (!saving) onDefer() },
        title = { Text(stringResource(R.string.host_first_launch_title)) },
        text = {
            Column(Modifier.verticalScroll(scroll)) {
                Text(stringResource(R.string.host_first_launch_clipboard))
                Spacer(Modifier.height(spacing.medium))
                Text(stringResource(R.string.host_first_launch_preview))
                Spacer(Modifier.height(spacing.medium))
                Text(stringResource(R.string.host_first_launch_notifications))
                Spacer(Modifier.height(spacing.medium))
                Text(stringResource(R.string.host_first_launch_defer_note), style = MaterialTheme.typography.bodyMedium)
                if (saveFailed) {
                    Spacer(Modifier.height(spacing.small))
                    Text(stringResource(R.string.host_privacy_settings_save_failed), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onAccept, enabled = !saving) { Text(stringResource(R.string.host_first_launch_accept)) } },
        dismissButton = { TextButton(onClick = onDefer, enabled = !saving) { Text(stringResource(R.string.host_first_launch_defer)) } },
    )
}

/** 无平台依赖的弹窗预览，覆盖默认和大字失败提示。 */
@Preview(showBackground = true)
@Preview(showBackground = true, fontScale = 1.5f, widthDp = 320)
@Composable
private fun FirstLaunchConsentDialogPreview() {
    ClipMaterTheme { FirstLaunchConsentDialog(false, true, {}, {}) }
}
