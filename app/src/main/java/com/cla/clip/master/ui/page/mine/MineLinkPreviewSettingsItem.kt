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
import com.cla.clip.master.ui.widget.SettingSwitchEntryCard

/** 我的页薄适配层：只收集状态、资源化说明和发送事件，视觉骨架复用共享设置卡片。 */
@Composable
internal fun MineLinkPreviewSettingsItem(viewModel: LinkPreviewSettingsVm = hiltViewModel()) {
    /** 当前已提交开关或明确加载/失败状态。 */ val state by viewModel.state.collectAsStateWithLifecycle()
    /** 正在提交时禁用操作，避免快速点击覆盖。 */ val saving by viewModel.saving.collectAsStateWithLifecycle()
    /** 失败提示不以开关回弹代替。 */ val failed by viewModel.saveFailed.collectAsStateWithLifecycle()
    /** 可编辑的已提交值，未知时不允许操作。 */ val current = state as? LinkPreviewToggleState.Ready
    SettingSwitchEntryCard(
        title = stringResource(R.string.host_link_preview_title),
        checked = current?.enabled == true, enabled = current != null && !saving,
        icon = { Icon(Icons.Default.Language, contentDescription = null) },
        onCheckedChange = viewModel::setEnabled,
        onEntryClick = if (current != null && !saving) ({ viewModel.setEnabled(!current.enabled) }) else null,
        descriptionContent = {
            Text(stringResource(R.string.host_link_preview_description), style = MaterialTheme.typography.bodyMedium)
            if (failed) Text(stringResource(R.string.host_privacy_settings_save_failed), color = MaterialTheme.colorScheme.error)
            if (state == LinkPreviewToggleState.Failed) {
                Text(stringResource(R.string.host_privacy_settings_load_failed), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::reload) { Text(stringResource(R.string.host_privacy_settings_retry)) }
            }
        },
    )
}
