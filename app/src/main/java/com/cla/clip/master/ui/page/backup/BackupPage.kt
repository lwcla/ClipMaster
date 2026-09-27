package com.cla.clip.master.ui.page.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.focus.onFocusChanged
import com.cla.clip.master.ad.*
import com.cla.clip.feature.ad.api.AdPlacement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cla.clip.base.general.R
import com.cla.clip.base.general.backup.BackupKind
import com.cla.clip.base.general.backup.BackupProgress
import com.cla.clip.base.general.backup.BackupProgressPhase
import com.cla.clip.base.general.backup.BackupTargetHealth
import com.cla.clip.base.general.backup.BackupTaskStatus
import com.cla.clip.base.general.backup.RemoteBackupFile
import com.cla.clip.master.ui.navigation.BackupRestoreRoute
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.widget.ClipMasterCard
import com.cla.clip.master.ui.widget.SecondaryPageScaffold

/**
 * 备份与恢复页面。
 *
 * 页面只负责系统文件选择器、恢复流程页和基础表单展示；备份生成、WebDAV 访问、预检和恢复写库都委托给 ViewModel，
 * 避免 Composable 直接触碰数据库、网络或 ContentResolver 细节。
 */
@Composable
fun BackupPage(
    onBack: () -> Unit,
    onNavigate: (Route) -> Unit,
    backupVm: BackupVm = hiltViewModel(),
) {
    val state by backupVm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // 列表只展示会参与保留份数的普通备份；历史 safety 或异常文件隐藏，避免数量超过用户设置造成误解。
    val normalLocalBackups = state.localBackups.filter { it.isRegularBackup }
    val normalRemoteBackups = state.remoteBackups.filter { it.isRegularBackup }
    val pageActionsEnabled = !state.isBusy

    // 待确认的备份创建动作只保留在当前页面生命周期内；持久化的仅是用户是否已经确认风险。
    var pendingDisclosureAction by remember { mutableStateOf<BackupPlaintextDisclosureAction?>(null) }

    // 页面广告只保存随机会话，不接收 WebDAV 或备份条目内容。
    val adSession = rememberPageAdSession("backup_overview")
    // 表单编辑及系统选择器状态只留在内存，不写入配置或备份。
    var editingCredentials by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    // 广告与首张正文卡共享稳定条目，避免零高广告被 LazyColumn 跳过；不参与备份记录计数。
    val listState = rememberLazyListState()
    // 只测量首卡正文，广告尾部可见时由下一张真实正文卡承接阅读锚点。
    var autoContentHeight by remember { mutableIntStateOf(0) }
    val bodyKeys = listOf<Any>("auto", "local") + normalLocalBackups.map { it.lazyListKey() } +
        listOf("webdav") + if (normalRemoteBackups.isEmpty()) listOf("remote_empty") else normalRemoteBackups.map { it.lazyListKey() }
    val adBlocked = editingCredentials || pickerOpen || pendingDisclosureAction != null || state.busyOperation.isModalProgress
    SideEffect { adSession.suppressed = adBlocked }
    rememberListAdLayoutCoordinator(adSession, listState, bodyKeys, blocked = adBlocked,
        contentHeights = if (autoContentHeight > 0) mapOf("auto" to autoContentHeight) else emptyMap(),
        keepStartAtTop = true)

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
        onResult = { uri -> pickerOpen = false; uri?.let { backupVm.exportToUri(it) } }
    )
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            pickerOpen = false
            uri?.let {
                backupVm.requestRestoreFromUri(it)
                onNavigate(BackupRestoreRoute)
            }
        }
    )
    val localDirLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri -> pickerOpen = false; uri?.let { backupVm.updateLocalBackupDir(it) } }
    )

    // 所有会生成未加密备份包的入口统一经过这里，action 表示确认后应继续执行的原始动作。
    val requestBackupCreationAction: (BackupPlaintextDisclosureAction) -> Unit = { action ->
        if (action.requiresDisclosure(state.backupPlaintextDisclosureAccepted)) {
            pendingDisclosureAction = action
        } else {
            when (action) {
                BackupPlaintextDisclosureAction.ManualLocal -> {
                    pickerOpen = true
                    exportLauncher.launch(backupVm.suggestedBackupFileName())
                }
                BackupPlaintextDisclosureAction.ManualWebDav -> backupVm.uploadWebDavBackup()
                BackupPlaintextDisclosureAction.EnableAuto -> backupVm.updateAutoBackupEnabled(true)
            }
        }
    }

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

    SecondaryPageScaffold(
        modifier = Modifier.adInteractionGuard(adSession),
        title = androidx.compose.ui.res.stringResource(R.string.base_general_backup_restore),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.Top
        ) {
            item(key = "auto") {
                // 广告放在正文尾部并共享稳定容器，加载前后既保留组合身份，也不移动首卡正文锚点。
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 此测量容器不含广告；高度仅用于本页内存锚点，不参与请求和日志。
                    Column(modifier = Modifier.onSizeChanged { autoContentHeight = it.height }) {
                        AutoBackupSection(
                            state = state,
                            // enabled 表示用户本次希望切换到的自动备份开关状态。
                            onEnabledChange = { enabled ->
                                if (enabled) {
                                    requestBackupCreationAction(BackupPlaintextDisclosureAction.EnableAuto)
                                } else {
                                    backupVm.updateAutoBackupEnabled(false)
                                }
                            },
                            onRetentionChange = backupVm::updateRetentionCount,
                            onOnlyWifiChange = backupVm::updateOnlyWifi,
                        )
                    }
                    PageAdSlot(session = adSession, placement = AdPlacement.BackupOverviewNative,
                        // 表单与弹窗仅阻止新增请求及交互，不销毁已有素材。
                        requestEnabled = !adBlocked,
                        // 与本页普通卡片共用左右 16dp 外边距，素材内边距由广告布局管理。
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                }
            }

            item(key = "local") {
                LocalBackupSection(
                    state = state,
                    isBusy = !pageActionsEnabled,
                    onExport = {
                        requestBackupCreationAction(BackupPlaintextDisclosureAction.ManualLocal)
                    },
                    onImport = { pickerOpen = true; importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onChooseDir = { pickerOpen = true; localDirLauncher.launch(null) },
                    onRefreshLocal = backupVm::refreshLocalBackups,
                )
            }

            if (normalLocalBackups.isNotEmpty()) {
                items(
                    items = normalLocalBackups,
                    key = { it.lazyListKey() }
                ) { file ->
                    LocalBackupCard(
                        file = file,
                        isBusy = !pageActionsEnabled,
                        onPreview = {
                            backupVm.requestRestoreFromLocalBackup(file)
                            onNavigate(BackupRestoreRoute)
                        }
                    )
                }
            }

            item(key = "webdav") {
                WebDavConfigSection(
                    state = state,
                    onEndpointChange = backupVm::updateEndpoint,
                    onUsernameChange = backupVm::updateUsername,
                    onPasswordChange = backupVm::updatePassword,
                    onRemoteDirChange = backupVm::updateRemoteDir,
                    onAllowHttpChange = backupVm::updateAllowHttp,
                    onTest = backupVm::testWebDav,
                    onUpload = {
                        requestBackupCreationAction(BackupPlaintextDisclosureAction.ManualWebDav)
                    },
                    onRefresh = backupVm::refreshRemoteBackups,
                    actionsEnabled = pageActionsEnabled,
                    onEditingChanged = { editingCredentials = it },
                )
            }

            if (normalRemoteBackups.isEmpty()) {
                item(key = "remote_empty") {
                    EmptyRemoteBackups()
                }
            } else {
                items(
                    items = normalRemoteBackups,
                    key = { it.lazyListKey() }
                ) { file ->
                    RemoteBackupCard(
                        file = file,
                        isBusy = !pageActionsEnabled,
                        onPreview = {
                            backupVm.requestRestoreFromRemoteBackup(file)
                            onNavigate(BackupRestoreRoute)
                        }
                    )
                }
            }
        }
    }

    if (state.busyOperation.isModalProgress) {
        // 备份写入涉及文件/网络副作用；弹窗期间拦截返回，避免用户误以为可以安全中断长任务。
        BackupTaskProgressDialog(operation = state.busyOperation, progress = state.backupProgress)
    }

    // action 是用户确认披露后需要继续执行的一次性动作，不持久化目标或页面状态。
    pendingDisclosureAction?.let { action ->
        BackupPlaintextDisclosureDialog(
            onDismiss = { pendingDisclosureAction = null },
            onConfirm = {
                backupVm.acceptBackupPlaintextDisclosure()
                pendingDisclosureAction = null
                when (action) {
                    BackupPlaintextDisclosureAction.ManualLocal -> {
                        pickerOpen = true
                    exportLauncher.launch(backupVm.suggestedBackupFileName())
                    }
                    BackupPlaintextDisclosureAction.ManualWebDav -> backupVm.uploadWebDavBackup()
                    BackupPlaintextDisclosureAction.EnableAuto -> backupVm.updateAutoBackupEnabled(true)
                }
            },
        )
    }
}

/** 首次创建备份前展示的未加密风险披露；确认状态由 ViewModel 持久化。 */
@Composable
private fun BackupPlaintextDisclosureDialog(
    /** 用户取消披露时只关闭对话框，不继续原动作。 */
    onDismiss: () -> Unit,
    /** 用户确认披露后持久化确认状态并继续原动作。 */
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_backup_plaintext_disclosure_title))
        },
        text = {
            Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_backup_plaintext_disclosure_message))
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_backup_plaintext_disclosure_confirm))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_cancel))
            }
        },
    )
}

/** LazyColumn 的 key 在整条列表内必须唯一；同名备份可能同时存在于本地镜像和 WebDAV 远端。 */
private fun LocalBackupFile.lazyListKey(): String = "local:${uri}:${fileName}"

/** LazyColumn 的 key 在整条列表内必须唯一；远端条目用路径区分同名备份来源。 */
private fun RemoteBackupFile.lazyListKey(): String = "remote:${path}:${fileName}"

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
                    OutlinedButton(onClick = onRefreshLocal, enabled = !isBusy, modifier = Modifier.weight(1f)) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_local_backup_refresh))
                    }
                }
            }
            if (state.localBackupDirUri.isNotBlank()) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.base_general_local_backup_list_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** WebDAV 配置和手动操作区。 */
@Composable
private fun WebDavConfigSection(
    state: BackupUiState,
    /** 子树任意凭据字段聚焦时暂停广告，不向广告层传递字段值。 */
    onEditingChanged: (Boolean) -> Unit,
    onEndpointChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onRemoteDirChange: (String) -> Unit,
    onAllowHttpChange: (Boolean) -> Unit,
    onTest: () -> Unit,
    onUpload: () -> Unit,
    onRefresh: () -> Unit,
    actionsEnabled: Boolean,
) {
    ClipMasterCard(
        modifier = Modifier
            .onFocusChanged { onEditingChanged(it.hasFocus) }.focusGroup().fillMaxWidth()
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
                    enabled = actionsEnabled
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onTest,
                    enabled = actionsEnabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_webdav_test))
                }
                Button(
                    onClick = onUpload,
                    enabled = actionsEnabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.CloudUpload, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = androidx.compose.ui.res.stringResource(R.string.base_general_webdav_upload))
                }
            }
            OutlinedButton(
                onClick = onRefresh,
                enabled = actionsEnabled,
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
        createdAt = file.manifest?.createdAt ?: file.sortCreatedAt.takeIf { it > 0L } ?: file.lastModified,
        size = file.size,
        backupKind = file.effectiveBackupKind,
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
        createdAt = file.manifest?.createdAt ?: file.sortCreatedAt.takeIf { it > 0L } ?: file.lastModified,
        size = file.size,
        backupKind = file.effectiveBackupKind,
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

/**
 * 备份/恢复进行中弹窗。
 *
 * 这里使用不可取消的模态反馈替代标题栏加载动画，因为备份会写文件/上传网络，恢复会写数据库，用户需要明确知道页面正在处理数据。
 */
@Composable
private fun BackupTaskProgressDialog(operation: BackupBusyOperation, progress: BackupProgress?) {
    val titleRes = when (operation) {
        BackupBusyOperation.Exporting,
        BackupBusyOperation.UploadingWebDav -> R.string.base_general_backup_backing_up_title
        BackupBusyOperation.Restoring -> R.string.base_general_backup_restoring_title
        else -> R.string.base_general_backup_backing_up_title
    }
    val messageRes = when (progress?.phase) {
        BackupProgressPhase.Exporting -> R.string.base_general_backup_progress_exporting
        BackupProgressPhase.UploadingWebDav -> R.string.base_general_backup_progress_uploading
        BackupProgressPhase.Restoring -> R.string.base_general_backup_progress_restoring
        BackupProgressPhase.Verifying -> R.string.base_general_backup_progress_verifying
        BackupProgressPhase.Packaging -> R.string.base_general_backup_progress_packaging
        BackupProgressPhase.WritingLocal -> R.string.base_general_backup_progress_writing_local
        BackupProgressPhase.Downloading -> R.string.base_general_backup_progress_downloading
        BackupProgressPhase.Completed -> R.string.base_general_backup_progress_completed
        BackupProgressPhase.Preparing,
        null -> when (operation) {
        BackupBusyOperation.Exporting -> R.string.base_general_backup_exporting_message
        BackupBusyOperation.UploadingWebDav -> R.string.base_general_backup_uploading_message
        BackupBusyOperation.Restoring -> R.string.base_general_backup_restoring_message
        else -> R.string.base_general_backup_exporting_message
        }
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
fun formatBackupSize(size: Long): String {
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
        // safety 只用于识别并隐藏旧版回滚文件；正常列表不会展示这个分支，保留兜底避免异常数据崩溃。
        BackupKind.Safety,
        null -> androidx.compose.ui.res.stringResource(R.string.base_general_backup_kind_unknown)
    }
}

/** 需要用不可取消弹窗明确展示的备份/恢复长任务。 */
private val BackupBusyOperation.isModalProgress: Boolean
    get() = this == BackupBusyOperation.Exporting ||
        this == BackupBusyOperation.UploadingWebDav
