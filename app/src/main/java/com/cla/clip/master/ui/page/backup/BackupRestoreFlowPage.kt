package com.cla.clip.master.ui.page.backup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.R
import com.cla.clip.master.ui.widget.TitleBar

/**
 * 备份恢复流程页。
 *
 * 页面承载读取、预览、恢复中、结果和失败状态；文件读写、WebDAV 下载和 Room 恢复仍由 ViewModel 编排。
 */
@Composable
internal fun BackupRestoreFlowPage(
    state: BackupRestoreFlowState,
    onBack: () -> Unit,
    onForceBack: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showExitConfirm by remember(state.logCode) { mutableStateOf(false) }

    fun requestBack() {
        if (state.requiresExitConfirm) {
            showExitConfirm = true
        } else {
            onBack()
        }
    }

    BackHandler(enabled = true) {
        requestBack()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TitleBar(
                title = stringResource(R.string.base_general_backup_restore_flow_title),
                onBack = { requestBack() }
            )
        },
        bottomBar = {
            BackupRestoreFlowActions(
                state = state,
                onBack = onBack,
                onRestore = onRestore
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            BackupRestoreFlowContent(state = state)
        }
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text(text = stringResource(R.string.base_general_backup_restore_exit_title)) },
            text = {
                Text(
                    text = if (state is BackupRestoreFlowState.Restoring) {
                        stringResource(R.string.base_general_backup_restore_exit_restoring_message)
                    } else {
                        stringResource(R.string.base_general_backup_restore_exit_reading_message)
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitConfirm = false
                        onForceBack()
                    }
                ) {
                    Text(text = stringResource(R.string.base_general_backup_restore_exit_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) {
                    Text(text = stringResource(R.string.base_general_cancel))
                }
            }
        )
    }
}

private val BackupRestoreFlowState.requiresExitConfirm: Boolean
    get() = this is BackupRestoreFlowState.Reading || this is BackupRestoreFlowState.Restoring
