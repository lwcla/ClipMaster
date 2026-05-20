package com.cla.clip.master.ui.page.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.R
import com.cla.clip.base.general.backup.BackupExportResult
import com.cla.clip.base.general.backup.BackupFailure
import com.cla.clip.base.general.backup.BackupProgress
import com.cla.clip.base.general.backup.BackupProgressCategory
import com.cla.clip.base.general.backup.BackupProgressPhase
import com.cla.clip.base.general.backup.BackupRepository
import com.cla.clip.base.general.backup.BackupSource
import com.cla.clip.base.general.backup.RemoteBackupFile
import com.cla.clip.base.general.backup.WebDavClient
import com.cla.clip.base.general.backup.WebDavConfig
import com.cla.clip.base.general.backup.BackupSuccessSummary
import com.cla.clip.base.general.backup.BackupTargetHealth
import com.cla.clip.base.general.backup.BackupTaskStatus
import com.cla.clip.base.general.backup.BackupTempFileStore
import com.cla.clip.base.general.backup.backupReasonCode
import com.cla.clip.base.general.backup.buildBackupFileName
import com.cla.clip.base.general.backup.buildBackupDeviceLabel
import com.cla.clip.base.general.backup.newBackupTaskId
import com.cla.clip.base.general.backup.normalizeWebDavRemoteDir
import com.cla.clip.base.general.backup.toLogFields
import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import com.cla.clip.master.work.BackupAutoScheduler
import com.cla.clip.master.work.BackupTaskGate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 备份与恢复页 ViewModel。
 *
 * ViewModel 负责连接备份仓库、WebDAV 客户端和系统文件 URI；Composable 只负责启动系统文件选择器和展示状态，
 * 避免 UI 层直接持有数据库或网络实现细节。
 */
@HiltViewModel
class BackupVm @Inject constructor(
    /** 应用级 Context，用于通过 ContentResolver 读写用户选择的备份文件以及读取字符串资源。 */
    @param:ApplicationContext private val appContext: Context,
    /** 统一备份仓库，负责快照生成、预检和恢复写库。 */
    private val backupRepository: BackupRepository,
    /** WebDAV 客户端，负责远端目录测试、上传、下载和列表。 */
    private val webDavClient: WebDavClient,
    /** 本地备份文件夹写入器，负责 SAF 目录下的文件创建和字节写入。 */
    private val localBackupDirectoryWriter: LocalBackupDirectoryWriter,
    /** 备份临时文件目录管理器，用于导入预览和导出完成后的清理。 */
    private val tempFileStore: BackupTempFileStore,
) : ViewModel() {
    companion object {
        /** 日志标签，只记录失败类型，不记录用户内容或密码。 */
        private const val TAG = "BackupVm"
    }

    /** 页面状态。 */
    private val _uiState = MutableStateFlow(loadInitialState())

    /** 页面只读状态流。 */
    val uiState = _uiState.asStateFlow()

    /** 一次性提示事件，页面通过 Snackbar 展示。 */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** 页面订阅的一次性提示事件。 */
    val messages = _messages.asSharedFlow()

    init {
        tempFileStore.cleanupExpired()
    }

    /**
     * 生成默认备份文件名。
     *
     * ActivityResultContracts.CreateDocument 需要页面发起，ViewModel 只提供建议文件名，避免依赖 Activity。
     */
    fun suggestedBackupFileName(): String {
        val now = System.currentTimeMillis()
        return buildBackupFileName(buildBackupDeviceLabel(AppSetting.pid), now)
    }

    /** 更新 WebDAV 服务地址。 */
    fun updateEndpoint(value: String) {
        AppSetting.webDavEndpoint = value
        AppSetting.webDavHealth = BackupTargetHealth.Unknown
        BackupAutoScheduler.reschedule(appContext)
        _uiState.update { it.copy(webDavEndpoint = value, webDavHealth = BackupTargetHealth.Unknown) }
    }

    /** 更新 WebDAV 用户名。 */
    fun updateUsername(value: String) {
        AppSetting.webDavUsername = value
        AppSetting.webDavHealth = BackupTargetHealth.Unknown
        _uiState.update { it.copy(webDavUsername = value) }
    }

    /** 更新 WebDAV 密码。 */
    fun updatePassword(value: String) {
        AppSetting.webDavPassword = value
        AppSetting.webDavHealth = BackupTargetHealth.Unknown
        _uiState.update { it.copy(webDavPassword = value) }
    }

    /** 更新 WebDAV 远端目录，保存时先做规范化，失败则保留用户输入并显示错误。 */
    fun updateRemoteDir(value: String) {
        _uiState.update { it.copy(webDavRemoteDir = value) }
        runCatching {
            normalizeWebDavRemoteDir(value)
        }.onSuccess { normalized ->
            AppSetting.webDavRemoteDir = normalized
            AppSetting.webDavHealth = BackupTargetHealth.Unknown
            BackupAutoScheduler.reschedule(appContext)
            _uiState.update { it.copy(webDavRemoteDir = normalized, webDavHealth = BackupTargetHealth.Unknown) }
        }.onFailure {
            AppSetting.webDavRemoteDir = value
            AppSetting.webDavHealth = BackupTargetHealth.Unknown
        }
    }

    /** 更新是否允许 HTTP WebDAV。 */
    fun updateAllowHttp(value: Boolean) {
        AppSetting.webDavAllowInsecureHttp = value
        _uiState.update { it.copy(webDavAllowHttp = value) }
        BackupAutoScheduler.reschedule(appContext)
    }

    /** 更新自动备份统一开关；开启时要求至少有一个目标，并立即排队一次生成首个恢复点。 */
    fun updateAutoBackupEnabled(value: Boolean) {
        if (value && !hasAnyBackupTarget()) {
            logW(TAG) { "自动备份开关变更被拒绝 reasonCode=no_available_target requested=$value" }
            emitMessage(R.string.base_general_backup_auto_no_target)
            return
        }
        AppSetting.autoBackupEnabled = value
        if (value) {
            AppSetting.backupDirty = true
            BackupAutoScheduler.reschedule(appContext)
            BackupAutoScheduler.enqueueNow(appContext)
            emitMessage(R.string.base_general_backup_auto_enabled_hint)
        } else {
            BackupAutoScheduler.reschedule(appContext)
        }
        logI(TAG) {
            "自动备份开关已变更 enabled=$value hasLocalTarget=${uiState.value.localBackupDirUri.isNotBlank()} " +
                "webDavHealth=${uiState.value.webDavHealth} dirty=${AppSetting.backupDirty}"
        }
        _uiState.update { it.copy(autoBackupEnabled = value, backupDirty = AppSetting.backupDirty) }
    }

    /** 更新普通备份保留份数，保存后刷新本地/WebDAV 列表以便立即按新配置清理。 */
    fun updateRetentionCount(value: Int) {
        AppSetting.backupRetentionCount = value
        BackupAutoScheduler.reschedule(appContext)
        logD(TAG) { "备份保留份数已变更 value=${AppSetting.backupRetentionCount}" }
        _uiState.update { it.copy(backupRetentionCount = AppSetting.backupRetentionCount) }
        refreshLocalBackups()
        if (uiState.value.webDavHealth == BackupTargetHealth.Available) {
            refreshRemoteBackups()
        }
    }

    /** 更新仅 Wi-Fi 约束。 */
    fun updateOnlyWifi(value: Boolean) {
        AppSetting.autoBackupOnlyWifi = value
        BackupAutoScheduler.reschedule(appContext)
        _uiState.update { it.copy(autoBackupOnlyWifi = value) }
    }

    /** 保存用户选择的本地备份目录授权，并把 URI 同步到页面状态；授权失败时不保存，避免后续重启后写入失败。 */
    fun updateLocalBackupDir(uri: Uri) {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(uri, flags)
        }.onSuccess {
            val label = resolveLocalBackupDirLabel(uri)
            AppSetting.localBackupDirUri = uri.toString()
            AppSetting.localBackupDirLabel = label
            BackupAutoScheduler.reschedule(appContext)
            _uiState.update { it.copy(localBackupDirUri = uri.toString(), localBackupDirLabel = label) }
            refreshLocalBackups()
            logI(TAG) { "本地备份目录已保存 hasUri=true labelLength=${label.length}" }
            emitMessage(R.string.base_general_local_backup_dir_save_success)
        }.onFailure { throwable ->
            logE(TAG) {
                "本地备份目录保存失败 reasonCode=${throwable.backupReasonCode()} type=${throwable::class.simpleName}"
            }
            emitMessage(R.string.base_general_backup_error_storage)
        }
    }

    /** 清除本地备份目录配置；已授予的系统 URI 权限由系统按生命周期管理，不影响已生成的备份文件。 */
    fun clearLocalBackupDir() {
        AppSetting.localBackupDirUri = ""
        AppSetting.localBackupDirLabel = ""
        BackupAutoScheduler.reschedule(appContext)
        _uiState.update { it.copy(localBackupDirUri = "", localBackupDirLabel = "", localBackups = emptyList()) }
        logI(TAG) { "本地备份目录已清除" }
    }

    /** 导出本地备份到用户选择的 URI。 */
    fun exportToUri(uri: Uri) {
        viewModelScope.launch {
            val taskId = newBackupTaskId("manual-local")
            runOperation(BackupBusyOperation.Exporting, taskId = taskId) {
                BackupTaskGate.runExclusive {
                    val startedAt = System.currentTimeMillis()
                    logI(TAG) { "开始手动本地备份 taskId=$taskId operation=manual_local_backup" }
                    val export = backupRepository.createSnapshot(BackupSource.LocalManual, taskId = taskId)
                    try {
                        logD(TAG) {
                            "手动本地备份快照已生成 taskId=$taskId fileName=${export.fileName} fileSize=${export.fileSize} " +
                                "${export.manifest.summary.toLogFields()}"
                        }
                        writeFile(uri, export.packageFile)
                        AppSetting.backupDirty = false
                        _uiState.update { it.copy(backupDirty = false) }
                        logI(TAG) {
                            "手动本地备份成功 taskId=$taskId fileName=${export.fileName} fileSize=${export.fileSize} " +
                                "durationMs=${System.currentTimeMillis() - startedAt}"
                        }
                        emitMessage(R.string.base_general_backup_export_success)
                    } finally {
                        tempFileStore.cleanupTaskDir(export.taskDir, taskId)
                    }
                }
            }
        }
    }

    /** 测试 WebDAV 连接，并在成功时保存规范化目录。 */
    fun testWebDav() {
        viewModelScope.launch {
            val taskId = newBackupTaskId("webdav-test")
            runOperation(
                operation = BackupBusyOperation.TestingWebDav,
                taskId = taskId,
                onFailure = { markWebDavUnavailable(taskId, it) }
            ) {
                val startedAt = System.currentTimeMillis()
                val config = currentWebDavConfig()
                logI(TAG) {
                    "开始测试 WebDAV 连接 taskId=$taskId allowHttp=${config.allowInsecureHttp} " +
                        "hasUsername=${config.username.isNotBlank()} remoteDirLength=${config.remoteDir.length}"
                }
                webDavClient.testAndPrepare(config, taskId)
                AppSetting.webDavHealth = BackupTargetHealth.Available
                AppSetting.webDavHealthCheckedAt = System.currentTimeMillis()
                BackupAutoScheduler.reschedule(appContext)
                _uiState.update { it.copy(webDavRemoteDir = normalizeWebDavRemoteDir(config.remoteDir)) }
                refreshRemoteBackupsInternal(taskId)
                logI(TAG) { "WebDAV 连接测试成功 taskId=$taskId durationMs=${System.currentTimeMillis() - startedAt}" }
                emitMessage(R.string.base_general_webdav_test_success)
            }
        }
    }

    /** 手动上传一份 WebDAV 备份。 */
    fun uploadWebDavBackup() {
        viewModelScope.launch {
            val taskId = newBackupTaskId("manual-webdav")
            runOperation(BackupBusyOperation.UploadingWebDav, taskId = taskId) {
                BackupTaskGate.runExclusive {
                    val startedAt = System.currentTimeMillis()
                    logI(TAG) {
                        "开始手动 WebDAV 备份 taskId=$taskId hasLocalDir=${uiState.value.localBackupDirUri.isNotBlank()} " +
                            "webDavHealth=${uiState.value.webDavHealth}"
                    }
                    val export = backupRepository.createSnapshot(BackupSource.WebDavManual, taskId = taskId)
                    try {
                        logD(TAG) {
                            "手动 WebDAV 备份快照已生成 taskId=$taskId fileName=${export.fileName} fileSize=${export.fileSize} " +
                                "${export.manifest.summary.toLogFields()}"
                        }
                        writeConfiguredLocalBackupIfNeeded(export, taskId)
                        webDavClient.uploadBackup(currentWebDavConfig(), export, taskId)
                        AppSetting.backupDirty = false
                        _uiState.update { it.copy(backupDirty = false) }
                        logI(TAG) {
                            "手动 WebDAV 备份成功 taskId=$taskId fileName=${export.fileName} fileSize=${export.fileSize} " +
                                "durationMs=${System.currentTimeMillis() - startedAt}"
                        }
                        emitMessage(R.string.base_general_webdav_upload_success)
                        refreshRemoteBackupsInternal(taskId)
                    } finally {
                        tempFileStore.cleanupTaskDir(export.taskDir, taskId)
                    }
                }
            }
        }
    }

    /** 刷新远端备份列表。 */
    fun refreshRemoteBackups() {
        viewModelScope.launch {
            val taskId = newBackupTaskId("webdav-list")
            runOperation(
                operation = BackupBusyOperation.RefreshingWebDav,
                taskId = taskId,
                onFailure = { markWebDavUnavailable(taskId, it) }
            ) {
                logD(TAG) { "开始刷新 WebDAV 备份列表 taskId=$taskId" }
                refreshRemoteBackupsInternal(taskId)
            }
        }
    }

    /** 刷新本地备份目录列表，刷新前按保留份数清理普通备份，只读取 manifest 和文件元数据。 */
    fun refreshLocalBackups() {
        val dir = uiState.value.localBackupDirUri.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val taskId = newBackupTaskId("local-list")
            runOperation(BackupBusyOperation.RefreshingLocal, taskId = taskId) {
                val cleanup = localBackupDirectoryWriter.pruneBackups(Uri.parse(dir), AppSetting.backupRetentionCount, taskId)
                val files = localBackupDirectoryWriter.listBackups(Uri.parse(dir))
                _uiState.update { it.copy(localBackups = files) }
                logD(TAG) { "本地备份列表刷新成功 taskId=$taskId count=${files.size} deleted=${cleanup.deletedCount}" }
            }
        }
    }

    /** 刷新远端备份列表的内部实现；刷新前会按保留份数清理普通备份，确保列表与配置一致。 */
    private suspend fun refreshRemoteBackupsInternal(taskId: String? = null) {
        val cleanup = webDavClient.pruneBackups(
            config = currentWebDavConfig(),
            keepCount = AppSetting.backupRetentionCount,
            taskId = taskId
        )
        val files = webDavClient.listBackups(currentWebDavConfig())
        AppSetting.webDavHealth = BackupTargetHealth.Available
        AppSetting.webDavHealthCheckedAt = System.currentTimeMillis()
        _uiState.update {
            it.copy(
                remoteBackups = files,
                webDavHealth = AppSetting.webDavHealth,
                webDavHealthCheckedAt = AppSetting.webDavHealthCheckedAt
            )
        }
        logD(TAG) { "WebDAV 备份列表刷新成功 taskId=$taskId count=${files.size} deleted=${cleanup.deletedCount}" }
    }

    /** 如果用户设置了本地备份目录，WebDAV 备份前先写入同一份 zip 快照和 manifest。 */
    private suspend fun writeConfiguredLocalBackupIfNeeded(export: BackupExportResult, taskId: String) {
        val dir = uiState.value.localBackupDirUri.takeIf { it.isNotBlank() } ?: return
        logD(TAG) { "开始写入 WebDAV 备份的本地镜像 taskId=$taskId fileName=${export.fileName} fileSize=${export.fileSize}" }
        localBackupDirectoryWriter.writeExport(Uri.parse(dir), export, taskId)
        val cleanup = localBackupDirectoryWriter.pruneBackups(Uri.parse(dir), AppSetting.backupRetentionCount, taskId)
        logD(TAG) { "WebDAV 备份的本地镜像写入成功 taskId=$taskId fileName=${export.fileName} deleted=${cleanup.deletedCount}" }
    }

    /** 解析本地备份目录的用户可读展示名，优先使用系统返回的 displayName，失败时用 URI 尾段兜底。 */
    private fun resolveLocalBackupDirLabel(uri: Uri): String {
        return queryDisplayName(uri)
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').ifBlank { null } }.getOrNull()
            ?: uri.lastPathSegment
            ?: uri.toString()
    }

    /** 查询 SAF 目录的 displayName；部分文件管理器不返回名称，调用方需要自行兜底。 */
    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            val documentId = DocumentsContract.getTreeDocumentId(uri)
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
            appContext.contentResolver.query(documentUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()
    }

    /** 当前 WebDAV 配置快照。 */
    private fun currentWebDavConfig(): WebDavConfig {
        val state = uiState.value
        return WebDavConfig(
            endpoint = state.webDavEndpoint,
            username = state.webDavUsername,
            password = state.webDavPassword,
            remoteDir = normalizeWebDavRemoteDir(state.webDavRemoteDir),
            allowInsecureHttp = state.webDavAllowHttp
        )
    }

    /** 判断是否存在任一备份目标；WebDAV 目标只检查基础配置，真实可写性仍由测试连接或 Worker 处理。 */
    private fun hasAnyBackupTarget(): Boolean {
        val state = uiState.value
        return state.localBackupDirUri.isNotBlank() ||
            (state.webDavEndpoint.isNotBlank() && state.webDavHealth == BackupTargetHealth.Available)
    }

    /** 统一包装长任务加载状态和失败提示，保留具体操作类型给 UI 决定展示方式。 */
    private suspend fun runOperation(
        operation: BackupBusyOperation,
        taskId: String? = null,
        onFailure: (Throwable) -> Unit = {},
        block: suspend () -> Unit
    ) {
        _uiState.update { it.copy(busyOperation = operation, backupProgress = operation.toProgress(taskId)) }
        runCatching {
            block()
        }.onFailure { throwable ->
            logE(TAG) {
                "备份页面操作失败 ${com.cla.clip.base.general.backup.backupTaskLogField(taskId)}operation=$operation " +
                    "reasonCode=${throwable.backupReasonCode()} type=${throwable::class.simpleName}"
            }
            onFailure(throwable)
            emitMessage(mapErrorMessage(throwable))
        }
        _uiState.update { it.copy(busyOperation = BackupBusyOperation.None, backupProgress = null) }
    }

    /** 根据当前操作生成轻量进度状态，后续底层进度事件可继续复用同一模型扩展。 */
    private fun BackupBusyOperation.toProgress(taskId: String?): BackupProgress? {
        val id = taskId ?: return null
        val phase = when (this) {
            BackupBusyOperation.Exporting -> BackupProgressPhase.Exporting
            BackupBusyOperation.UploadingWebDav -> BackupProgressPhase.UploadingWebDav
            BackupBusyOperation.Restoring -> BackupProgressPhase.Restoring
            BackupBusyOperation.PreviewingLocal,
            BackupBusyOperation.PreviewingRemote -> BackupProgressPhase.Verifying
            BackupBusyOperation.TestingWebDav,
            BackupBusyOperation.RefreshingWebDav,
            BackupBusyOperation.RefreshingLocal,
            BackupBusyOperation.None -> BackupProgressPhase.Preparing
        }
        return BackupProgress(taskId = id, phase = phase, category = BackupProgressCategory.Overall)
    }

    /** 记录 WebDAV 当前不可用；只缓存健康状态，不输出 URL、账号或密码。 */
    private fun markWebDavUnavailable(taskId: String?, throwable: Throwable) {
        AppSetting.webDavHealth = BackupTargetHealth.Unavailable
        AppSetting.webDavHealthCheckedAt = System.currentTimeMillis()
        BackupAutoScheduler.reschedule(appContext)
        _uiState.update {
            it.copy(
                webDavHealth = AppSetting.webDavHealth,
                webDavHealthCheckedAt = AppSetting.webDavHealthCheckedAt
            )
        }
        logW(TAG) {
            "WebDAV 健康状态已标记为不可用 ${com.cla.clip.base.general.backup.backupTaskLogField(taskId)}" +
                "reasonCode=${throwable.backupReasonCode()} type=${throwable::class.simpleName}"
        }
    }

    /** 向用户展示字符串资源提示。 */
    private fun emitMessage(resId: Int) {
        _messages.tryEmit(appContext.getString(resId))
    }

    /** 向用户展示已格式化提示。 */
    private fun emitMessage(message: String) {
        _messages.tryEmit(message)
    }

    /** 把失败类型映射成可行动的用户提示。 */
    private fun mapErrorMessage(throwable: Throwable): String {
        val resId = when (throwable) {
            is BackupFailure.InvalidFormat -> R.string.base_general_backup_error_invalid_format
            is BackupFailure.AppMismatch -> R.string.base_general_backup_error_app_mismatch
            is BackupFailure.UnsupportedSchema -> R.string.base_general_backup_error_schema
            is BackupFailure.ChecksumMismatch -> R.string.base_general_backup_error_checksum
            is BackupFailure.AuthenticationFailed -> R.string.base_general_backup_error_auth
            is BackupFailure.StorageNotWritable -> R.string.base_general_backup_error_storage
            is BackupFailure.FileTooLarge -> R.string.base_general_backup_error_file_too_large
            is BackupFailure.TempFileUnavailable -> R.string.base_general_backup_error_temp_file_unavailable
            is BackupFailure.InsufficientSpace -> R.string.base_general_backup_error_insufficient_space
            else -> R.string.base_general_backup_error_unknown
        }
        return appContext.getString(resId)
    }

    /** 向用户授权 URI 写入 zip 备份包文件；`t` 表示截断旧内容，避免覆盖同名文件时残留旧字节。 */
    private suspend fun writeFile(uri: Uri, file: File) = withContext(Dispatchers.IO) {
        appContext.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: throw BackupFailure.StorageNotWritable()
    }

    /** 构造初始状态并从 AppSetting 读取本机 WebDAV 配置。 */
    private fun loadInitialState(): BackupUiState {
        return BackupUiState(
            webDavEndpoint = AppSetting.webDavEndpoint,
            webDavUsername = AppSetting.webDavUsername,
            webDavPassword = AppSetting.webDavPassword,
            webDavRemoteDir = AppSetting.webDavRemoteDir,
            webDavAllowHttp = AppSetting.webDavAllowInsecureHttp,
            localBackupDirUri = AppSetting.localBackupDirUri,
            localBackupDirLabel = AppSetting.localBackupDirLabel.ifBlank {
                AppSetting.localBackupDirUri.takeIf { it.isNotBlank() }?.let { resolveLocalBackupDirLabel(Uri.parse(it)) } ?: ""
            },
            autoBackupEnabled = AppSetting.autoBackupEnabled,
            backupRetentionCount = AppSetting.backupRetentionCount,
            autoBackupOnlyWifi = AppSetting.autoBackupOnlyWifi,
            backupDirty = AppSetting.backupDirty,
            lastAutoBackupStatus = AppSetting.lastAutoBackupStatus,
            lastAutoBackupSuccessAt = AppSetting.lastAutoBackupSuccessAt,
            lastAutoBackupFailureReason = AppSetting.lastAutoBackupFailureReason,
            lastAutoBackupSkipReason = AppSetting.lastAutoBackupSkipReason,
            webDavHealth = AppSetting.webDavHealth,
            webDavHealthCheckedAt = AppSetting.webDavHealthCheckedAt,
            lastBackupSuccessSummary = AppSetting.lastBackupSuccessSummary
        )
    }
}

/**
 * 备份页正在执行的长任务类型。
 *
 * 备份页和独立恢复页复用同一组任务枚举，便于进度文案和日志字段保持一致。
 */
enum class BackupBusyOperation {
    /** 当前没有长任务。 */
    None,
    /** 正在导出本地备份。 */
    Exporting,
    /** 正在读取本地文件并预览。 */
    PreviewingLocal,
    /** 正在把预检过的备份合并恢复到 Room。 */
    Restoring,
    /** 正在测试 WebDAV 连接并准备目录。 */
    TestingWebDav,
    /** 正在上传 WebDAV 备份。 */
    UploadingWebDav,
    /** 正在刷新 WebDAV 备份列表。 */
    RefreshingWebDav,
    /** 正在刷新本地备份目录列表。 */
    RefreshingLocal,
    /** 正在下载远端备份并预览。 */
    PreviewingRemote,
}

/** 备份页 UI 状态。 */
data class BackupUiState(
    /** 当前长任务类型，`None` 表示页面可自由操作。 */
    val busyOperation: BackupBusyOperation = BackupBusyOperation.None,
    /** WebDAV 服务地址。 */
    val webDavEndpoint: String = "",
    /** WebDAV 用户名。 */
    val webDavUsername: String = "",
    /** WebDAV 密码。 */
    val webDavPassword: String = "",
    /** WebDAV 远端目录。 */
    val webDavRemoteDir: String = "/ClipMaster/backups/",
    /** 是否允许 HTTP。 */
    val webDavAllowHttp: Boolean = false,
    /** 本地备份文件夹授权 URI；为空表示未设置。 */
    val localBackupDirUri: String = "",
    /** 本地备份文件夹展示路径或名称；只用于 UI 展示，不参与实际写入。 */
    val localBackupDirLabel: String = "",
    /** 远端备份列表。 */
    val remoteBackups: List<RemoteBackupFile> = emptyList(),
    /** 本地备份目录列表。 */
    val localBackups: List<LocalBackupFile> = emptyList(),
    /** 自动备份统一开关。 */
    val autoBackupEnabled: Boolean = false,
    /** 普通备份保留份数。 */
    val backupRetentionCount: Int = AppSetting.DEFAULT_BACKUP_RETENTION_COUNT,
    /** WebDAV 自动备份仅 Wi-Fi。 */
    val autoBackupOnlyWifi: Boolean = false,
    /** 当前是否存在未备份变化。 */
    val backupDirty: Boolean = true,
    /** 最近自动备份状态。 */
    val lastAutoBackupStatus: BackupTaskStatus = BackupTaskStatus.Idle,
    /** 最近自动备份成功时间。 */
    val lastAutoBackupSuccessAt: Long = 0,
    /** 最近自动备份失败原因。 */
    val lastAutoBackupFailureReason: String = "",
    /** 最近自动备份跳过原因。 */
    val lastAutoBackupSkipReason: String = "",
    /** WebDAV 健康状态缓存。 */
    val webDavHealth: BackupTargetHealth = BackupTargetHealth.Unknown,
    /** WebDAV 最近健康检查时间。 */
    val webDavHealthCheckedAt: Long = 0,
    /** 最近一次成功自动备份摘要。 */
    val lastBackupSuccessSummary: BackupSuccessSummary? = null,
    /** 当前备份/恢复阶段进度；第一版展示阶段，后续可接入类别和数量节流更新。 */
    val backupProgress: BackupProgress? = null,
) {
    /** 是否正在执行备份、预检、恢复或 WebDAV 操作，用于统一禁用会产生并发冲突的按钮。 */
    val isBusy: Boolean
        get() = busyOperation != BackupBusyOperation.None
}
