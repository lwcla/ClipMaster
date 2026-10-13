package com.cla.clip.master.ui.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cla.clip.master.R
import com.cla.clip.master.logging.*
import com.cla.clip.master.ui.theme.ClipMasterTheme
import com.cla.clip.master.ui.theme.BrandPrimaryLight
import com.cla.clip.master.ui.theme.BrandPrimaryDark

/** 日志的纯展示适配：蓝色说明仅打开目录，只有 Switch 改变采集意图。 */
@Composable
internal fun LogSettingsEntryCard(
    state: LogSettingsEntryState,
    onCheckedChange: (Boolean) -> Unit,
    onOpenFolder: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    /** 已提交开关；加载/失败不能伪装成可编辑的默认关闭。 */
    val toggle = state.toggle as? LogToggleState.Ready
    /** 用户要求蓝色，不随 Material 动态壁纸主色变绿；复用已有亮/暗品牌 token。 */
    val linkColor = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) BrandPrimaryDark else BrandPrimaryLight
    /** 数量的加载/错误仍属于文件入口，不与开关许可混合。 */
    val fileCount = state.files
    /** 未知设置不绘制 unchecked Switch；读屏只获知加载/状态未知。 */
    val statusContent: (@Composable () -> Unit)? = when (state.toggle) {
        is LogToggleState.Ready -> null
        LogToggleState.Loading -> {
            { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
        }
        LogToggleState.Failed -> {
            { Icon(Icons.Default.HelpOutline, contentDescription = stringResource(R.string.host_log_settings_failed),
                tint = MaterialTheme.colorScheme.error) }
        }
    }
    SettingSwitchEntryCard(
        title = stringResource(R.string.host_log_title),
        checked = toggle?.enabled ?: false,
        enabled = toggle != null && !state.updating,
        icon = { Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        trailingContent = statusContent,
        descriptionContent = {
            Column(Modifier.clickable(onClickLabel = stringResource(R.string.host_log_open_folder), onClick = onOpenFolder)) {
                Text(stringResource(R.string.host_log_directory), color = linkColor,
                    style = MaterialTheme.typography.bodyMedium)
                Text(when (fileCount) {
                    is LogFileCountState.Ready -> stringResource(R.string.host_log_file_count, fileCount.count)
                    LogFileCountState.Loading -> stringResource(R.string.host_log_count_loading)
                    LogFileCountState.Failed -> stringResource(R.string.host_log_count_failed)
                }, color = linkColor, style = MaterialTheme.typography.bodyMedium)
            }
            when (state.toggle) {
                LogToggleState.Loading -> Text(stringResource(R.string.host_log_settings_loading),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LogToggleState.Failed -> Text(stringResource(R.string.host_log_settings_failed),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                is LogToggleState.Ready -> Unit
            }
            if (state.capture.phase == "failed" && toggle?.enabled == true) {
                Text(stringResource(R.string.host_log_capture_failed), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (state.toggle == LogToggleState.Failed || state.files == LogFileCountState.Failed) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.host_log_retry)) }
            }
        },
    )
}

/** 窄屏样本只用合成数量，验证目录换行和独立开关。 */
@Preview(widthDp = 320)
@Composable
private fun LogSettingsEntryCardPreview() {
    ClipMasterTheme {
        LogSettingsEntryCard(LogSettingsEntryState(LogToggleState.Ready(false), LogFileCountState.Ready(21)), {}, {}, {})
    }
}

/** 深色/失败样本保护错误不是 0 和关闭后仍可浏览的视觉。 */
@Preview(widthDp = 320, fontScale = 1.5f)
@Composable
private fun LogSettingsEntryCardDarkPreview() {
    ClipMasterTheme(darkTheme = true) {
        LogSettingsEntryCard(LogSettingsEntryState(LogToggleState.Failed, LogFileCountState.Ready(3)), {}, {}, {})
    }
}
