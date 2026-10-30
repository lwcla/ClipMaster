package com.cla.clip.master.ui.page.mine

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cla.clip.master.R
import com.cla.clip.master.preview.LinkPreviewSettingsVm
import com.cla.clip.master.preview.LinkPreviewToggleState
import com.cla.clip.master.ui.widget.ExpandableSettingSwitchEntryCard

/** 我的页薄适配层：收集状态并发送开关事件，说明展开和点击隔离统一复用共享卡片。 */
@Composable
internal fun MineLinkPreviewSettingsItem(viewModel: LinkPreviewSettingsVm = hiltViewModel()) {
    /** 当前已提交开关或明确加载/失败状态。 */ val state by viewModel.state.collectAsStateWithLifecycle()
    /** 正在提交时禁用操作，避免快速点击覆盖。 */ val saving by viewModel.saving.collectAsStateWithLifecycle()
    /** 失败提示不以开关回弹代替。 */ val failed by viewModel.saveFailed.collectAsStateWithLifecycle()
    /** 可编辑的已提交值，未知时不允许操作。 */ val current = state as? LinkPreviewToggleState.Ready
    ExpandableSettingSwitchEntryCard(
        title = stringResource(R.string.host_link_preview_title),
        description = stringResource(R.string.host_link_preview_description),
        checked = current?.enabled == true, enabled = current != null && !saving,
        // 沿用同页 Material 图标与主题主色，避免继承正文颜色造成入口风格不一致。
        icon = {
            Icon(
                imageVector = Icons.Default.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        onCheckedChange = viewModel::setEnabled,
        supportingContent = {
            // 错误反馈和重试始终可见，不与静态说明一起折叠，也不触发开关保存。
            if (failed) Text(stringResource(R.string.host_privacy_settings_save_failed), color = MaterialTheme.colorScheme.error)
            if (state == LinkPreviewToggleState.Failed) {
                Text(stringResource(R.string.host_privacy_settings_load_failed), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::reload) { Text(stringResource(R.string.host_privacy_settings_retry)) }
            }
        },
    )
}
