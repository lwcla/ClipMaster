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
import com.cla.clip.base.general.backup.BackupPreview
import com.cla.clip.base.general.backup.BackupRepository
import com.cla.clip.base.general.backup.BackupRestoreReport
import com.cla.clip.base.general.backup.BackupSource
import com.cla.clip.base.general.backup.RemoteBackupFile
import com.cla.clip.base.general.backup.WebDavClient
import com.cla.clip.base.general.backup.WebDavConfig
import com.cla.clip.base.general.backup.buildBackupFileName
import com.cla.clip.base.general.backup.buildBackupDeviceLabel
import com.cla.clip.base.general.backup.normalizeWebDavRemoteDir
import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.base.general.utils.logE
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        _uiState.update { it.copy(webDavEndpoint = value) }
    }

    /** 更新 WebDAV 用户名。 */
    fun updateUsername(value: String) {
        AppSetting.webDavUsername = value
        _uiState.update { it.copy(webDavUsername = value) }
    }

    /** 更新 WebDAV 密码。 */
    fun updatePassword(value: String) {
        AppSetting.webDavPassword = value
        _uiState.update { it.copy(webDavPassword = value) }
    }

    /** 更新 WebDAV 远端目录，保存时先做规范化，失败则保留用户输入并显示错误。 */
    fun updateRemoteDir(value: String) {
        _uiState.update { it.copy(webDavRemoteDir = value) }
        runCatching {
            normalizeWebDavRemoteDir(value)
        }.onSuccess { normalized ->
            AppSetting.webDavRemoteDir = normalized
            _uiState.update { it.copy(webDavRemoteDir = normalized) }
        }.onFailure {
            AppSetting.webDavRemoteDir = value
        }
    }

    /** 更新是否允许 HTTP WebDAV。 */
    fun updateAllowHttp(value: Boolean) {
        AppSetting.webDavAllowInsecureHttp = value
        _uiState.update { it.copy(webDavAllowHttp = value) }
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
            _uiState.update { it.copy(localBackupDirUri = uri.toString(), localBackupDirLabel = label) }
            emitMessage(R.string.base_general_local_backup_dir_save_success)
        }.onFailure { throwable ->
            logE(TAG, throwable) { "updateLocalBackupDir: persist permission failed" }
            emitMessage(R.string.base_general_backup_error_storage)
        }
    }

    /** 清除本地备份目录配置；已授予的系统 URI 权限由系统按生命周期管理，不影响已生成的备份文件。 */
    fun clearLocalBackupDir() {
        AppSetting.localBackupDirUri = ""
        AppSetting.localBackupDirLabel = ""
        _uiState.update { it.copy(localBackupDirUri = "", localBackupDirLabel = "") }
    }

    /** 导出本地备份到用户选择的 URI。 */
    fun exportToUri(uri: Uri) {
        viewModelScope.launch {
            runOperation(BackupBusyOperation.Exporting) {
                val export = backupRepository.createSnapshot(BackupSource.LocalManual)
                writeBytes(uri, export.packageBytes)
                _uiState.update { it.copy(lastExport = export) }
                emitMessage(R.string.base_general_backup_export_success)
            }
        }
    }

    /** 从用户选择的 URI 读取备份并只做预检，不写入数据库。 */
    fun previewFromUri(uri: Uri) {
        viewModelScope.launch {
            runOperation(BackupBusyOperation.PreviewingLocal) {
                val bytes = readBytes(uri)
                val preview = backupRepository.previewSnapshot(bytes)
                _uiState.update {
                    it.copy(
                        selectedBackupBytes = bytes,
                        selectedPreview = preview,
                        selectedRemoteFile = null
                    )
                }
            }
        }
    }

    /** 恢复当前预检过的备份。 */
    fun restoreSelectedBackup() {
        val packageBytes = uiState.value.selectedBackupBytes ?: return
        viewModelScope.launch {
            runOperation(BackupBusyOperation.Restoring) {
                val report = backupRepository.restoreSnapshot(packageBytes)
                _uiState.update { it.copy(lastRestoreReport = report) }
                emitMessage(
                    appContext.getString(
                        R.string.base_general_backup_restore_success,
                        report.insertedCount,
                        report.updatedCount,
                        report.skippedCount
                    )
                )
            }
        }
    }

    /** 测试 WebDAV 连接，并在成功时保存规范化目录。 */
    fun testWebDav() {
        viewModelScope.launch {
            runOperation(BackupBusyOperation.TestingWebDav) {
                val config = currentWebDavConfig()
                webDavClient.testAndPrepare(config)
                _uiState.update { it.copy(webDavRemoteDir = normalizeWebDavRemoteDir(config.remoteDir)) }
                refreshRemoteBackupsInternal()
                emitMessage(R.string.base_general_webdav_test_success)
            }
        }
    }

    /** 手动上传一份 WebDAV 备份。 */
    fun uploadWebDavBackup() {
        viewModelScope.launch {
            runOperation(BackupBusyOperation.UploadingWebDav) {
                val export = backupRepository.createSnapshot(BackupSource.WebDavManual)
                writeConfiguredLocalBackupIfNeeded(export)
                webDavClient.uploadBackup(currentWebDavConfig(), export)
                _uiState.update { it.copy(lastExport = export) }
                emitMessage(R.string.base_general_webdav_upload_success)
                refreshRemoteBackupsInternal()
            }
        }
    }

    /** 刷新远端备份列表。 */
    fun refreshRemoteBackups() {
        viewModelScope.launch {
            runOperation(BackupBusyOperation.RefreshingWebDav) {
                refreshRemoteBackupsInternal()
            }
        }
    }

    /** 下载并预览指定远端备份。 */
    fun previewRemoteBackup(file: RemoteBackupFile) {
        viewModelScope.launch {
            runOperation(BackupBusyOperation.PreviewingRemote) {
                val bytes = webDavClient.downloadBytes(currentWebDavConfig(), file.fileName)
                val preview = backupRepository.previewSnapshot(bytes)
                _uiState.update {
                    it.copy(
                        selectedBackupBytes = bytes,
                        selectedPreview = preview,
                        selectedRemoteFile = file
                    )
                }
            }
        }
    }

    /** 清空当前预览。 */
    fun clearPreview() {
        _uiState.update {
            it.copy(
                selectedBackupBytes = null,
                selectedPreview = null,
                selectedRemoteFile = null,
                lastRestoreReport = null
            )
        }
    }

    /** 刷新远端备份列表的内部实现，调用方负责包装加载状态。 */
    private suspend fun refreshRemoteBackupsInternal() {
        val files = webDavClient.listBackups(currentWebDavConfig())
        _uiState.update { it.copy(remoteBackups = files) }
    }

    /** 如果用户设置了本地备份目录，WebDAV 备份前先写入同一份 zip 快照和 manifest。 */
    private suspend fun writeConfiguredLocalBackupIfNeeded(export: BackupExportResult) {
        val dir = uiState.value.localBackupDirUri.takeIf { it.isNotBlank() } ?: return
        localBackupDirectoryWriter.writeExport(Uri.parse(dir), export)
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

    /** 统一包装长任务加载状态和失败提示，保留具体操作类型给 UI 决定展示方式。 */
    private suspend fun runOperation(operation: BackupBusyOperation, block: suspend () -> Unit) {
        _uiState.update { it.copy(busyOperation = operation) }
        runCatching {
            block()
        }.onFailure { throwable ->
            logE(TAG, throwable) { "backup operation failed type=${throwable::class.simpleName}" }
            emitMessage(mapErrorMessage(throwable))
        }
        _uiState.update { it.copy(busyOperation = BackupBusyOperation.None) }
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
            else -> R.string.base_general_backup_error_unknown
        }
        return appContext.getString(resId)
    }

    /** 从用户授权 URI 读取备份包字节。 */
    private suspend fun readBytes(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes()
        } ?: throw BackupFailure.ParseFailed()
    }

    /** 向用户授权 URI 写入 zip 备份包字节；`t` 表示截断旧内容，避免覆盖同名文件时残留旧字节。 */
    private suspend fun writeBytes(uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        appContext.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(bytes)
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
            }
        )
    }
}

/**
 * 备份页正在执行的长任务类型。
 *
 * UI 需要区分恢复写库和普通网络/文件读取任务：恢复进入事务后不适合让用户取消，因此展示不可取消弹窗；
 * 其他任务只禁用按钮即可，避免用标题栏动画制造突兀的视觉干扰。
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
    /** 当前预览的 zip 备份包字节。 */
    val selectedBackupBytes: ByteArray? = null,
    /** 当前预检摘要。 */
    val selectedPreview: BackupPreview? = null,
    /** 当前预览来自哪个远端文件，本地文件时为 null。 */
    val selectedRemoteFile: RemoteBackupFile? = null,
    /** 最近一次导出结果，只用于页面展示文件名。 */
    val lastExport: BackupExportResult? = null,
    /** 最近一次恢复报告。 */
    val lastRestoreReport: BackupRestoreReport? = null,
) {
    /** 是否正在执行备份、预检、恢复或 WebDAV 操作，用于统一禁用会产生并发冲突的按钮。 */
    val isBusy: Boolean
        get() = busyOperation != BackupBusyOperation.None
}

/** 把毫秒时间格式化为备份页面展示文本。 */
fun Long.toBackupDisplayTime(): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(this))
}
