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
                LocalBackupSection(
                    state = state,
                    isBusy = state.isBusy,
                    onExport = { exportLauncher.launch(backupVm.suggestedBackupFileName()) },
                    onImport = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onChooseDir = { localDirLauncher.launch(null) },
                    onClearDir = backupVm::clearLocalBackupDir,
                )
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
            onConfirm = {
                showRestoreConfirm = false
                backupVm.restoreSelectedBackup()
            },
            onDismiss = { showRestoreConfirm = false }
        )
    }

    if (state.busyOperation == BackupBusyOperation.Restoring) {
        // 恢复进入数据库事务后不支持中途取消；拦截系统返回，避免用户误以为可以安全退出。
        BackHandler(enabled = true) {}
        RestoreProgressDialog()
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onExport, enabled = !isBusy) {
                    Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_export_backup))
                }
                Button(onClick = onImport, enabled = !isBusy) {
                    Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_import_backup))
                }
            }
            Text(
                text = if (state.localBackupDirUri.isBlank()) {
                    androidx.compose.ui.res.stringResource(R.string.base_general_local_backup_dir_not_set)
                } else {
                    androidx.compose.ui.res.stringResource(R.string.base_general_local_backup_dir_set)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onChooseDir, enabled = !isBusy) {
                    Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_local_backup_choose_dir))
                }
                if (state.localBackupDirUri.isNotBlank()) {
                    TextButton(onClick = onClearDir, enabled = !isBusy) {
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

/** 远端备份列表项。 */
@Composable
private fun RemoteBackupCard(
    file: RemoteBackupFile,
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
                    text = file.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val createdAt = file.manifest?.createdAt ?: file.lastModified
                if (createdAt != null) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.base_general_backup_created_at, createdAt.toBackupDisplayTime()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                file.size?.let { size ->
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.base_general_backup_file_size, formatBackupSize(size)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_backup_confirm_restore_title)) },
        text = { Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_backup_confirm_restore_message)) },
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
 * 恢复进行中弹窗。
 *
 * 这里使用不可取消的模态反馈替代标题栏加载动画，因为恢复阶段已经进入写库流程，用户更需要明确知道页面正在处理数据。
 */
@Composable
private fun RestoreProgressDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_backup_restoring_title)) },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.base_general_backup_restoring_message),
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
