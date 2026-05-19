package com.cla.clip.master.ui.page.backup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cla.clip.base.general.R
import com.cla.clip.base.general.backup.BackupKind
import com.cla.clip.base.general.backup.BackupTargetHealth
import com.cla.clip.base.general.backup.BackupTaskStatus
import com.cla.clip.base.general.backup.RemoteBackupFile
import com.cla.clip.master.ui.widget.ClipMasterCard
import com.cla.clip.master.ui.widget.TitleBar

/**
 * 备份与恢复页面。
 *
 * 页面只负责系统文件选择器、恢复确认弹窗和基础表单展示；备份生成、WebDAV 访问、预检和恢复写库都委托给 ViewModel，
 * 避免 Composable 直接触碰数据库、网络或 ContentResolver 细节。
 */
@Composable
fun BackupPage(
    onBack: () -> Unit,
    backupVm: BackupVm = hiltViewModel(),
) {
    val state by backupVm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showRestoreConfirm by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
        onResult = { uri -> uri?.let { backupVm.exportToUri(it) } }
    )
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let { backupVm.previewFromUri(it) } }
    )
    val localDirLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri -> uri?.let { backupVm.updateLocalBackupDir(it) } }
    )

    LaunchedEffect(backupVm) {
        backupVm.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(state.localBackupDirUri) {
        if (state.localBackupDirUri.isNotBlank()) {
            backupVm.refreshLocalBackups()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TitleBar(
                title = androidx.compose.ui.res.stringResource(R.string.base_general_backup_restore),
                onBack = onBack
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                AutoBackupSection(
                    state = state,
                    onEnabledChange = backupVm::updateAutoBackupEnabled,
                    onRetentionChange = backupVm::updateRetentionCount,
                    onOnlyWifiChange = backupVm::updateOnlyWifi,
                )
            }

            item {
                LocalBackupSection(
                    state = state,
                    isBusy = state.isBusy,
                    onExport = { exportLauncher.launch(backupVm.suggestedBackupFileName()) },
                    onImport = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onChooseDir = { localDirLauncher.launch(null) },
                    onClearDir = backupVm::clearLocalBackupDir,
                    onRefreshLocal = backupVm::refreshLocalBackups,
                )
            }

            if (state.localBackups.isNotEmpty()) {
                items(
                    items = state.localBackups,
                    key = { it.fileName }
                ) { file ->
                    LocalBackupCard(
                        file = file,
                        isBusy = state.isBusy,
                        onPreview = { backupVm.previewLocalBackup(file) }
                    )
                }
            }

            item {
                WebDavConfigSection(
                    state = state,
                    onEndpointChange = backupVm::updateEndpoint,
                    onUsernameChange = backupVm::updateUsername,
                    onPasswordChange = backupVm::updatePassword,
                    onRemoteDirChange = backupVm::updateRemoteDir,
                    onAllowHttpChange = backupVm::updateAllowHttp,
                    onTest = backupVm::testWebDav,
                    onUpload = backupVm::uploadWebDavBackup,
                    onRefresh = backupVm::refreshRemoteBackups
                )
            }

            if (state.remoteBackups.isEmpty()) {
                item {
                    EmptyRemoteBackups()
                }
            } else {
                items(
                    items = state.remoteBackups,
                    key = { it.fileName }
                ) { file ->
                    RemoteBackupCard(
                        file = file,
                        isBusy = state.isBusy,
                        onPreview = { backupVm.previewRemoteBackup(file) }
                    )
                }
            }

            item {
                PreviewSection(
                    state = state,
                    onRestore = { showRestoreConfirm = true },
                    onClear = backupVm::clearPreview
                )
            }
        }
    }

    if (showRestoreConfirm) {
        RestoreConfirmDialog(
            isSafetySnapshot = state.selectedPreview?.backupKind == BackupKind.Safety,
            onConfirm = {
                showRestoreConfirm = false
                backupVm.restoreSelectedBackup()
            },
            onDismiss = { showRestoreConfirm = false }
        )
    }

    if (state.busyOperation.isModalProgress) {
        // 备份和恢复都涉及文件/网络/数据库写入；拦截系统返回，避免用户误以为可以安全中断长任务。
        BackHandler(enabled = true) {}
        BackupTaskProgressDialog(operation = state.busyOperation)
    }
}

/** 自动备份配置和状态区。 */
@Composable
private fun AutoBackupSection(
    state: BackupUiState,
    onEnabledChange: (Boolean) -> Unit,
    onRetentionChange: (Int) -> Unit,
    onOnlyWifiChange: (Boolean) -> Unit,
) {
    ClipMasterCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.base_general_backup_auto_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = autoBackupStatusText(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.autoBackupEnabled,
                    onCheckedChange = onEnabledChange,
                    enabled = !state.isBusy
                )
            }
            RetentionControl(
                count = state.backupRetentionCount,
                enabled = !state.isBusy,
                onChange = onRetentionChange
            )
            ToggleRow(
                title = androidx.compose.ui.res.stringResource(R.string.base_general_backup_auto_only_wifi),
                checked = state.autoBackupOnlyWifi,
                enabled = !state.isBusy,
                onCheckedChange = onOnlyWifiChange
            )
            state.lastBackupSuccessSummary?.let { summary ->
                Text(
                    text = androidx.compose.ui.res.stringResource(
                        R.string.base_general_backup_last_success_summary,
                        summary.createdAt.toBackupDisplayTime(),
                        summary.summary.clipCount,
                        formatBackupSize(summary.fileSize),
                        summary.localRetentionDeleted,
                        summary.webDavRetentionDeleted
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 保留份数加减控件，限制在 1-20。 */
@Composable
private fun RetentionControl(
    count: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.base_general_backup_retention_count, count),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedButton(onClick = { onChange(count - 1) }, enabled = enabled && count > 1) {
            Text(text = "-")
        }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(onClick = { onChange(count + 1) }, enabled = enabled && count < 20) {
            Text(text = "+")
        }
    }
}

/** 简单开关行，用于自动备份约束设置。 */
@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** 本地备份操作区，提供导出和选择文件预检入口。 */
@Composable
private fun LocalBackupSection(
    state: BackupUiState,
    isBusy: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onChooseDir: () -> Unit,
    onClearDir: () -> Unit,
    onRefreshLocal: () -> Unit,
) {
    ClipMasterCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.base_general_local_backup),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.base_general_backup_plaintext_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onExport, enabled = !isBusy, modifier = Modifier.weight(1f)) {
                    Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_export_backup))
                }
                Button(onClick = onImport, enabled = !isBusy, modifier = Modifier.weight(1f)) {
                    Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_import_backup))
                }
            }
            Text(
                text = if (state.localBackupDirUri.isBlank()) {
                    androidx.compose.ui.res.stringResource(R.string.base_general_local_backup_dir_not_set)
                } else {
                    androidx.compose.ui.res.stringResource(
                        R.string.base_general_local_backup_dir_set,
                        state.localBackupDirLabel.ifBlank { state.localBackupDirUri }
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onChooseDir, enabled = !isBusy, modifier = Modifier.weight(1f)) {
                    Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_local_backup_choose_dir))
                }
                if (state.localBackupDirUri.isNotBlank()) {
                    OutlinedButton(onClick = onRefreshLocal, enabled = !isBusy, modifier = Modifier.widthIn(min = 48.dp)) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                    }
                    TextButton(onClick = onClearDir, enabled = !isBusy, modifier = Modifier.weight(1f)) {
                        Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_local_backup_clear_dir))
                    }
                }
            }
        }
    }
}

/** WebDAV 配置和手动操作区。 */
@Composable
private fun WebDavConfigSection(
    state: BackupUiState,
    onEndpointChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onRemoteDirChange: (String) -> Unit,
    onAllowHttpChange: (Boolean) -> Unit,
    onTest: () -> Unit,
    onUpload: () -> Unit,
    onRefresh: () -> Unit,
) {
    ClipMasterCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.base_general_webdav_backup),
                style = MaterialTheme.typography.titleMedium
            )
            OutlinedTextField(
                value = state.webDavEndpoint,
                onValueChange = onEndpointChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_webdav_endpoint)) }
            )
            OutlinedTextField(
                value = state.webDavUsername,
                onValueChange = onUsernameChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_webdav_username)) }
            )
            OutlinedTextField(
                value = state.webDavPassword,
                onValueChange = onPasswordChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_webdav_password)) }
            )
            OutlinedTextField(
                value = state.webDavRemoteDir,
                onValueChange = onRemoteDirChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_webdav_remote_dir)) }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.base_general_webdav_allow_http),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = state.webDavAllowHttp,
                    onCheckedChange = onAllowHttpChange,
                    enabled = !state.isBusy
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onTest,
                    enabled = !state.isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_webdav_test))
                }
                Button(
                    onClick = onUpload,
                    enabled = !state.isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.CloudUpload, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_webdav_upload))
                }
            }
            OutlinedButton(
                onClick = onRefresh,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_webdav_refresh))
            }
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.base_general_webdav_list_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = webDavHealthText(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 远端备份为空时展示的轻量状态。 */
@Composable
private fun EmptyRemoteBackups() {
    Text(
        text = androidx.compose.ui.res.stringResource(R.string.base_general_webdav_list_empty),
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 本地备份列表项。 */
@Composable
private fun LocalBackupCard(
    file: LocalBackupFile,
    isBusy: Boolean,
    onPreview: () -> Unit,
) {
    BackupFileCard(
        fileName = file.fileName,
        createdAt = file.manifest?.createdAt ?: file.lastModified,
        size = file.size,
        backupKind = file.manifest?.backupKind,
        isBusy = isBusy,
        onPreview = onPreview
    )
}

/** 远端备份列表项。 */
@Composable
private fun RemoteBackupCard(
    file: RemoteBackupFile,
    isBusy: Boolean,
    onPreview: () -> Unit,
) {
    BackupFileCard(
        fileName = file.fileName,
        createdAt = file.manifest?.createdAt ?: file.lastModified,
        size = file.size,
        backupKind = file.manifest?.backupKind,
        isBusy = isBusy,
        onPreview = onPreview
    )
}

/** 本地和远端备份共用卡片。 */
@Composable
private fun BackupFileCard(
    fileName: String,
    createdAt: Long?,
    size: Long?,
    backupKind: BackupKind?,
    isBusy: Boolean,
    onPreview: () -> Unit,
) {
    ClipMasterCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        onClick = onPreview,
        enabled = !isBusy
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (createdAt != null) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.base_general_backup_created_at, createdAt.toBackupDisplayTime()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                size?.let { bytes ->
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.base_general_backup_file_size, formatBackupSize(bytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = backupKind.labelText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 当前预检摘要和恢复入口。 */
@Composable
private fun PreviewSection(
    state: BackupUiState,
    onRestore: () -> Unit,
    onClear: () -> Unit,
) {
    ClipMasterCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.base_general_preview_backup),
                style = MaterialTheme.typography.titleMedium
            )
            val preview = state.selectedPreview
            if (preview == null) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.base_general_backup_no_preview),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = androidx.compose.ui.res.stringResource(
                        R.string.base_general_backup_preview_summary,
                        preview.createdAt.toBackupDisplayTime(),
                        preview.appVersionName,
                        preview.schemaVersion,
                        preview.summary.clipCount,
                        preview.summary.searchHistoryCount,
                        preview.summary.videoDownloadCount,
                        preview.summary.imageBatchCount
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                state.lastRestoreReport?.let { report ->
                    HorizontalDivider()
                    Text(
                        text = androidx.compose.ui.res.stringResource(
                            R.string.base_general_backup_restore_success,
                            report.insertedCount,
                            report.updatedCount,
                            report.skippedCount
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    report.safetySnapshot?.let { safety ->
                        Text(
                            text = androidx.compose.ui.res.stringResource(
                                R.string.base_general_backup_safety_snapshot_report,
                                safety.fileName,
                                safety.locationLabel
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRestore, enabled = !state.isBusy) {
                        Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_restore_backup))
                    }
                    TextButton(onClick = onClear, enabled = !state.isBusy) {
                        Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_cancel))
                    }
                }
            }
        }
    }
}

/** 恢复确认弹窗，明确恢复采用合并策略而不是清空覆盖。 */
@Composable
private fun RestoreConfirmDialog(
    isSafetySnapshot: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val messageRes = if (isSafetySnapshot) {
        R.string.base_general_backup_confirm_restore_safety_message
    } else {
        R.string.base_general_backup_confirm_restore_message
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_backup_confirm_restore_title)) },
        text = { Text(text = androidx.compose.ui.res.stringResource(messageRes)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_restore_backup))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_cancel))
            }
        }
    )
}

/**
 * 备份/恢复进行中弹窗。
 *
 * 这里使用不可取消的模态反馈替代标题栏加载动画，因为备份会写文件/上传网络，恢复会写数据库，用户需要明确知道页面正在处理数据。
 */
@Composable
private fun BackupTaskProgressDialog(operation: BackupBusyOperation) {
    val titleRes = when (operation) {
        BackupBusyOperation.Exporting,
        BackupBusyOperation.UploadingWebDav -> R.string.base_general_backup_backing_up_title
        BackupBusyOperation.Restoring -> R.string.base_general_backup_restoring_title
        else -> R.string.base_general_backup_backing_up_title
    }
    val messageRes = when (operation) {
        BackupBusyOperation.Exporting -> R.string.base_general_backup_exporting_message
        BackupBusyOperation.UploadingWebDav -> R.string.base_general_backup_uploading_message
        BackupBusyOperation.Restoring -> R.string.base_general_backup_restoring_message
        else -> R.string.base_general_backup_exporting_message
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(text = androidx.compose.ui.res.stringResource(titleRes)) },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    text = androidx.compose.ui.res.stringResource(messageRes),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {}
    )
}

/** 备份文件大小展示，避免列表直接显示原始字节数。 */
private fun formatBackupSize(size: Long): String {
    return when {
        size >= 1024L * 1024L -> String.format(java.util.Locale.getDefault(), "%.1f MB", size / 1024f / 1024f)
        size >= 1024L -> String.format(java.util.Locale.getDefault(), "%.1f KB", size / 1024f)
        else -> "$size B"
    }
}

/** 自动备份状态展示文案，区分跳过和失败，避免用户误判。 */
@Composable
private fun autoBackupStatusText(state: BackupUiState): String {
    return when (state.lastAutoBackupStatus) {
        BackupTaskStatus.Idle -> androidx.compose.ui.res.stringResource(R.string.base_general_backup_auto_status_idle)
        BackupTaskStatus.Running -> androidx.compose.ui.res.stringResource(R.string.base_general_backup_auto_status_running)
        BackupTaskStatus.Success -> androidx.compose.ui.res.stringResource(
            R.string.base_general_backup_auto_status_success,
            state.lastAutoBackupSuccessAt.takeIf { it > 0 }?.toBackupDisplayTime().orEmpty()
        )
        BackupTaskStatus.PartialSuccess -> androidx.compose.ui.res.stringResource(
            R.string.base_general_backup_auto_status_partial,
            state.lastAutoBackupFailureReason.ifBlank { androidx.compose.ui.res.stringResource(R.string.base_general_backup_error_unknown) }
        )
        BackupTaskStatus.Skipped -> androidx.compose.ui.res.stringResource(
            R.string.base_general_backup_auto_status_skipped,
            state.lastAutoBackupSkipReason.ifBlank { androidx.compose.ui.res.stringResource(R.string.base_general_backup_auto_skip_unknown) }
        )
        BackupTaskStatus.RetryScheduled -> androidx.compose.ui.res.stringResource(
            R.string.base_general_backup_auto_status_retry,
            state.lastAutoBackupFailureReason.ifBlank { androidx.compose.ui.res.stringResource(R.string.base_general_backup_error_unknown) }
        )
        BackupTaskStatus.Failed -> androidx.compose.ui.res.stringResource(
            R.string.base_general_backup_auto_status_failed,
            state.lastAutoBackupFailureReason.ifBlank { androidx.compose.ui.res.stringResource(R.string.base_general_backup_error_unknown) }
        )
    }
}

/** WebDAV 健康状态展示文案，只读缓存，不触发网络请求。 */
@Composable
private fun webDavHealthText(state: BackupUiState): String {
    val time = state.webDavHealthCheckedAt.takeIf { it > 0 }?.toBackupDisplayTime().orEmpty()
    return when (state.webDavHealth) {
        BackupTargetHealth.Unknown -> androidx.compose.ui.res.stringResource(R.string.base_general_webdav_health_unknown)
        BackupTargetHealth.Available -> androidx.compose.ui.res.stringResource(R.string.base_general_webdav_health_available, time)
        BackupTargetHealth.Unavailable -> androidx.compose.ui.res.stringResource(R.string.base_general_webdav_health_unavailable, time)
    }
}

/** 备份类型展示文案。 */
@Composable
private fun BackupKind?.labelText(): String {
    return when (this) {
        BackupKind.Manual -> androidx.compose.ui.res.stringResource(R.string.base_general_backup_kind_manual)
        BackupKind.Auto -> androidx.compose.ui.res.stringResource(R.string.base_general_backup_kind_auto)
        BackupKind.Safety -> androidx.compose.ui.res.stringResource(R.string.base_general_backup_kind_safety)
        null -> androidx.compose.ui.res.stringResource(R.string.base_general_backup_kind_unknown)
    }
}

/** 需要用不可取消弹窗明确展示的备份/恢复长任务。 */
private val BackupBusyOperation.isModalProgress: Boolean
    get() = this == BackupBusyOperation.Exporting ||
        this == BackupBusyOperation.UploadingWebDav ||
        this == BackupBusyOperation.Restoring
