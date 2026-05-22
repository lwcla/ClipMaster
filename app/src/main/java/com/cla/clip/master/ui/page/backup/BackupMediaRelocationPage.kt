package com.cla.clip.master.ui.page.backup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cla.clip.base.general.R
import com.cla.clip.master.media.MediaRelocationPreparation
import com.cla.clip.master.ui.widget.TitleBar

/**
 * 恢复本地媒体关联独立页。
 *
 * 页面只承载当前媒体关联任务的交互和展示；扫描副作用由 `BackupMediaRelocationVm` 编排。
 */
@Composable
internal fun BackupMediaRelocationPage(
    restoreTaskId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    relocationVm: BackupMediaRelocationVm = hiltViewModel(),
) {
    val state by relocationVm.uiState.collectAsStateWithLifecycle()
    var showRunningNotice by remember { mutableStateOf(false) }
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = relocationVm::onPermissionResult
    )

    LaunchedEffect(restoreTaskId) {
        relocationVm.start(restoreTaskId)
    }

    fun requestBack() {
        if (state.isRunning) {
            showRunningNotice = true
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
                title = stringResource(R.string.base_general_backup_media_relocation_title),
                onBack = { requestBack() }
            )
        },
        bottomBar = {
            BackupMediaRelocationActions(
                state = state,
                onBack = { requestBack() },
                onRestart = relocationVm::restart,
                onRequestMediaPermission = { preparation ->
                    mediaPermissionLauncher.launch(preparation.requiredPermissions.toTypedArray())
                },
                onStartScan = relocationVm::startScan
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
            BackupMediaRelocationBody(state)
        }
    }

    if (showRunningNotice) {
        AlertDialog(
            onDismissRequest = { showRunningNotice = false },
            title = { Text(text = stringResource(R.string.base_general_backup_media_relocation_title)) },
            text = { Text(text = stringResource(R.string.base_general_backup_media_relocation_running_back_hint)) },
            confirmButton = {
                TextButton(onClick = { showRunningNotice = false }) {
                    Text(text = stringResource(R.string.base_general_backup_media_relocation_known))
                }
            }
        )
    }
}

@Composable
private fun BackupMediaRelocationBody(state: MediaRelocationUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.base_general_backup_media_relocation_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.base_general_backup_media_relocation_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    when (state) {
        MediaRelocationUiState.Idle,
        MediaRelocationUiState.Estimating -> ProgressRow(
            text = stringResource(R.string.base_general_backup_media_relocation_estimating)
        )
        is MediaRelocationUiState.PermissionChecking -> {
            MediaRelocationEstimateRows(state.preparation)
            ProgressRow(text = stringResource(R.string.base_general_backup_media_relocation_permission_checking))
        }
        is MediaRelocationUiState.NoWork -> {
            MediaRelocationSummaryText(
                MediaRelocationSummary(
                    type = MediaRelocationSummaryType.NoWork,
                    report = state.preparation.toExistingReadableReport()
                )
            )
            MediaRelocationResultRows(state.preparation.toExistingReadableReport())
        }
        is MediaRelocationUiState.ReadyToConfirm -> {
            MediaRelocationEstimateRows(state.preparation)
        }
        is MediaRelocationUiState.PermissionRequired -> {
            MediaRelocationEstimateRows(state.preparation)
            Text(
                text = stringResource(R.string.base_general_backup_media_relocation_permission),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.report?.let {
                MediaRelocationSummaryText(MediaRelocationSummary(MediaRelocationSummaryType.PermissionDenied, it))
                MediaRelocationResultRows(it)
            }
        }
        is MediaRelocationUiState.Running -> {
            MediaRelocationProgressRows(state.progress) { ProgressRow(it) }
        }
        is MediaRelocationUiState.Result -> {
            MediaRelocationSummaryText(MediaRelocationSummary(MediaRelocationSummaryType.Completed, state.report))
            MediaRelocationResultRows(state.report)
        }
        is MediaRelocationUiState.Error -> Text(
            text = state.message.ifBlank { stringResource(R.string.base_general_backup_media_relocation_error) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun BackupMediaRelocationActions(
    state: MediaRelocationUiState,
    onBack: () -> Unit,
    onRestart: () -> Unit,
    onRequestMediaPermission: (MediaRelocationPreparation) -> Unit,
    onStartScan: (MediaRelocationPreparation) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .navigationBarsPadding(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (state) {
            MediaRelocationUiState.Idle,
            MediaRelocationUiState.Estimating,
            is MediaRelocationUiState.PermissionChecking,
            is MediaRelocationUiState.Running -> Unit
            is MediaRelocationUiState.ReadyToConfirm -> {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.base_general_cancel))
                }
                Spacer(modifier = Modifier.size(8.dp))
                Button(onClick = { onStartScan(state.preparation) }) {
                    Text(stringResource(R.string.base_general_backup_media_relocation_confirm))
                }
            }
            is MediaRelocationUiState.PermissionRequired -> {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.base_general_cancel))
                }
                Spacer(modifier = Modifier.size(8.dp))
                Button(onClick = { onRequestMediaPermission(state.preparation) }) {
                    Text(stringResource(R.string.base_general_backup_media_relocation_request_permission))
                }
            }
            is MediaRelocationUiState.NoWork,
            is MediaRelocationUiState.Result,
            is MediaRelocationUiState.Error -> {
                TextButton(onClick = onRestart) {
                    Text(stringResource(R.string.base_general_backup_media_relocation_restart))
                }
                Spacer(modifier = Modifier.size(8.dp))
                Button(onClick = onBack) {
                    Text(stringResource(R.string.base_general_backup_flow_done))
                }
            }
        }
    }
}

@Composable
private fun ProgressRow(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}
