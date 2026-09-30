package com.cla.clip.master.ui.page.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.R
import com.cla.clip.master.R as HostR
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
import com.cla.clip.base.general.backup.validateWebDavEndpoint
import com.cla.clip.base.general.backup.settings.BackupRuntimeRepository
import com.cla.clip.base.general.backup.settings.BackupSettingsRepository
import com.cla.clip.base.general.backup.settings.WebDavConnectionSnapshot
import com.cla.clip.base.general.backup.settings.WebDavCredentialUnavailable
import com.cla.clip.base.general.config.installation.InstallationIdentityRepository
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import com.cla.clip.master.work.BackupAutoScheduler
import com.cla.clip.master.work.BackupTaskGate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
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
    /** 备份恢复 feature 内部请求流，用于把用户选择的恢复目标交给恢复页。 */
    private val restoreRequests: BackupRestoreRequests,
    /** 本机备份配置，所有用户确认的字段均等待 DataStore 提交。 */
    private val backupSettings: BackupSettingsRepository,
    /** 备份任务状态及版本，避免导出期间的新变更被旧结果确认。 */
    private val backupRuntime: BackupRuntimeRepository,
    /** 已提交的安装身份，仅用于生成稳定的备份文件名。 */
    private val installationIdentity: InstallationIdentityRepository,
) : ViewModel() {
    companion object {
        /** 日志标签，只记录失败类型，不记录用户内容或密码。 */
        private const val TAG = "BackupVm"
    }

    /** 页面状态。 */
    private val _uiState = MutableStateFlow(BackupUiState())

    /** 表单有未提交改动时，仓库更新不能覆盖用户正在输入的草稿。 */
    private var webDavDraftDirty = false

    /** 本次安装的已提交身份；设置加载完成前不开放创建备份。 */
    private var installId = ""

    /** 页面可见期间唯一的设置收集任务，读取失败后可由用户明确重试。 */
    private var settingsCollectionJob: Job? = null

    /** 页面只读状态流。 */
    val uiState = _uiState.asStateFlow()

    /** 一次性提示事件，页面通过 Snackbar 展示。 */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** 页面订阅的一次性提示事件。 */
    val messages = _messages.asSharedFlow()

    init {
        tempFileStore.cleanupExpired()
        observeSettings()
    }

    /** 设置首次加载或读取失败重试均复用同一 Flow 收集边界。 */
    fun retrySettingsLoad() {
        if (settingsCollectionJob?.isActive == true) return
        observeSettings()
    }

    /** 读取安装身份及备份配置并回填密码；磁盘错误关闭操作，凭据失效则允许重新输入修复。 */
    private fun observeSettings() {
        settingsCollectionJob = viewModelScope.launch {
            try {
                installId = installationIdentity.getOrCreateInstallId()
                combine(
                    // 仅设置快照变化时按需读取密码，运行状态刷新不重复解密；输入中的草稿始终优先。
                    backupSettings.data.onEach {
                        if (!webDavDraftDirty) {
                            /** 从同份磁盘快照取得目标和密码，读取期间开始编辑则保留用户草稿。 */
                            val connection = readWebDavFormConnection()
                            _uiState.update { current -> current.withWebDavConnection(connection, preserveDraft = webDavDraftDirty) }
                        }
                    },
                    backupRuntime.data,
                ) { settings, runtime -> settings to runtime }
                    .collect { (settings, runtime) ->
                        _uiState.update { current ->
                            current.copy(
                                settingsLoaded = true,
                                settingsLoadFailed = false,
                                webDavEndpoint = if (webDavDraftDirty) current.webDavEndpoint else settings.webDavEndpoint,
                                webDavUsername = if (webDavDraftDirty) current.webDavUsername else settings.webDavUsername,
                                webDavRemoteDir = if (webDavDraftDirty) current.webDavRemoteDir else settings.webDavRemoteDir,
                                webDavAllowHttp = if (webDavDraftDirty) current.webDavAllowHttp else settings.webDavAllowInsecureHttp,
                                localBackupDirUri = settings.localBackupDirUri,
                                localBackupDirLabel = settings.localBackupDirLabel,
                                autoBackupEnabled = settings.autoBackupEnabled,
                                backupPlaintextDisclosureAccepted = settings.backupPlaintextDisclosureAccepted,
                                backupRetentionCount = settings.backupRetentionCount,
                                autoBackupOnlyWifi = settings.autoBackupOnlyWifi,
                                backupDirty = runtime.dirty,
                                lastAutoBackupStatus = runtime.status,
                                lastAutoBackupSuccessAt = runtime.successAt,
                                lastAutoBackupFailureReason = runtime.failureReason,
                                lastAutoBackupSkipReason = runtime.skipReason,
                                webDavHealth = if (webDavDraftDirty) BackupTargetHealth.Unknown else runtime.webDavHealth,
                                webDavHealthCheckedAt = runtime.webDavHealthCheckedAt,
                                lastBackupSuccessSummary = runtime.successSummary,
                            )
                        }
                    }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                _uiState.update { it.copy(settingsLoaded = false, settingsLoadFailed = true) }
                logE(TAG) { "备份设置加载失败 reasonCode=${throwable.backupReasonCode()} type=${throwable::class.simpleName}" }
                emitMessage(mapErrorMessage(throwable))
            }
        }
    }

    /** 在仓库 IO 边界读取回填凭据；解密失败保留密文和配置，提供可编辑空密码及明确提示。 */
    private suspend fun readWebDavFormConnection(): WebDavConnectionSnapshot {
        return try {
            backupSettings.loadWebDavConnection()
        } catch (failure: WebDavCredentialUnavailable) {
            // 不将凭据失效升级为全部设置读取失败，否则用户无法输入新密码修复。
            logW(TAG) { "备份页密码回填失败 reasonCode=webdav_credential_unavailable type=${failure::class.simpleName}" }
            emitMessage(HostR.string.host_webdav_password_unavailable)
            WebDavConnectionSnapshot(backupSettings.snapshot(), null)
        }
    }

    /**
     * 生成默认备份文件名。
     *
     * ActivityResultContracts.CreateDocument 需要页面发起，ViewModel 只提供建议文件名，避免依赖 Activity。
     */
    fun suggestedBackupFileName(): String {
        /** 创建文件选择器所需的时间戳，身份已在设置可操作前加载并提交。 */
        val now = System.currentTimeMillis()
        return buildBackupFileName(buildBackupDeviceLabel(installId), now)
    }

    /** 打开系统文件选择器返回的备份文件，真实预检由恢复页 ViewModel 消费请求后执行。 */
    fun requestRestoreFromUri(uri: Uri) {
        restoreRequests.set(BackupRestoreRequest.LocalFile(uri))
    }

    /** 打开本地备份文件夹列表中的备份条目。 */
    fun requestRestoreFromLocalBackup(file: LocalBackupFile) {
        restoreRequests.set(BackupRestoreRequest.LocalDirectory(file))
    }

    /** 打开 WebDAV 远端备份列表中的备份条目。 */
    fun requestRestoreFromRemoteBackup(file: RemoteBackupFile) {
        restoreRequests.set(BackupRestoreRequest.WebDav(file))
    }

    /** 更新 WebDAV 服务地址。 */
    fun updateEndpoint(value: String) {
        webDavDraftDirty = true
        _uiState.update { it.copy(webDavEndpoint = value, webDavHealth = BackupTargetHealth.Unknown) }
    }

    /** 更新 WebDAV 用户名。 */
    fun updateUsername(value: String) {
        webDavDraftDirty = true
        _uiState.update { it.copy(webDavUsername = value) }
    }

    /** 更新密码输入框当前值，用户删空文本就提交空密码；显隐切换不会进入此回调。 */
    fun updatePassword(value: String) {
        webDavDraftDirty = true
        _uiState.update { it.copy(webDavPassword = value) }
    }

    /** 更新 WebDAV 远端目录，保存时先做规范化，失败则保留用户输入并显示错误。 */
    fun updateRemoteDir(value: String) {
        webDavDraftDirty = true
        _uiState.update { it.copy(webDavRemoteDir = value) }
    }

    /** 更新是否允许 HTTP WebDAV。 */
    fun updateAllowHttp(value: Boolean) {
        webDavDraftDirty = true
        _uiState.update { it.copy(webDavAllowHttp = value) }
    }

    /** 更新自动备份统一开关；开启时要求至少有一个目标，并立即排队一次生成首个恢复点。 */
    fun updateAutoBackupEnabled(value: Boolean) {
        viewModelScope.launch {
            runOperation(BackupBusyOperation.SavingSettings) {
                if (value && !hasAnyBackupTarget()) {
                    logW(TAG) { "自动备份开关变更被拒绝 reasonCode=no_available_target requested=$value" }
                    emitMessage(R.string.base_general_backup_auto_no_target)
                    return@runOperation
                }
                backupSettings.setAutoBackupEnabled(value)
                BackupAutoScheduler.reschedule(appContext)
                if (value) {
                    BackupAutoScheduler.enqueueNow(appContext)
                    emitMessage(R.string.base_general_backup_auto_enabled_hint)
                }
                logI(TAG) { "自动备份开关已变更 enabled=$value" }
            }
        }
    }

    /** 等待未加密备份披露确认落盘后再继续原动作；失败时保持原动作未执行。 */
    fun acceptBackupPlaintextDisclosure(onCommitted: () -> Unit) {
        viewModelScope.launch {
            runOperation(BackupBusyOperation.SavingSettings) {
                backupSettings.setPlaintextDisclosureAccepted(true)
                logI(TAG) { "用户已确认未加密备份风险" }
                onCommitted()
            }
        }
    }

    /** 更新普通备份保留份数，保存后刷新本地/WebDAV 列表以便立即按新配置清理。 */
    fun updateRetentionCount(value: Int) {
        viewModelScope.launch {
            runOperation(BackupBusyOperation.SavingSettings) {
                backupSettings.setRetentionCount(value)
                BackupAutoScheduler.reschedule(appContext)
                logD(TAG) { "备份保留份数已变更 value=${value.coerceIn(1, 20)}" }
                refreshLocalBackups()
                if (uiState.value.webDavHealth == BackupTargetHealth.Available) refreshRemoteBackups()
            }
        }
    }

    /** 更新仅 Wi-Fi 约束。 */
    fun updateOnlyWifi(value: Boolean) {
        viewModelScope.launch {
            runOperation(BackupBusyOperation.SavingSettings) {
                backupSettings.setOnlyWifi(value)
                BackupAutoScheduler.reschedule(appContext)
            }
        }
    }

    /** 保存用户选择的本地备份目录授权，并把 URI 同步到页面状态；授权失败时不保存，避免后续重启后写入失败。 */
    fun updateLocalBackupDir(uri: Uri) {
        /** 只有系统授权成功后才允许提交持久化目录。 */
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        viewModelScope.launch {
            runOperation(BackupBusyOperation.SavingSettings) {
                appContext.contentResolver.takePersistableUriPermission(uri, flags)
                /** 本机展示名只作 UI 提示，不进入便携备份。 */
                val label = resolveLocalBackupDirLabel(uri)
                backupSettings.setLocalBackupDirectory(uri.toString(), label)
                _uiState.update { it.copy(localBackupDirUri = uri.toString(), localBackupDirLabel = label) }
                BackupAutoScheduler.reschedule(appContext)
                refreshLocalBackups()
                logI(TAG) { "本地备份目录已保存 hasUri=true labelLength=${label.length}" }
                emitMessage(R.string.base_general_local_backup_dir_save_success)
            }
        }
    }

    /** 清除本地备份目录配置；已授予的系统 URI 权限由系统按生命周期管理，不影响已生成的备份文件。 */
    fun clearLocalBackupDir() {
        viewModelScope.launch {
            runOperation(BackupBusyOperation.SavingSettings) {
                backupSettings.setLocalBackupDirectory("", "")
                BackupAutoScheduler.reschedule(appContext)
                _uiState.update { it.copy(localBackupDirUri = "", localBackupDirLabel = "", localBackups = emptyList()) }
                logI(TAG) { "本地备份目录已清除" }
            }
        }
    }

    /** 导出本地备份到用户选择的 URI。 */
    fun exportToUri(uri: Uri) {
        viewModelScope.launch {
            /** 本次手动导出任务身份，只用于低敏日志和临时目录清理。 */
            val taskId = newBackupTaskId("manual-local")
            runOperation(BackupBusyOperation.Exporting, taskId = taskId) {
                BackupTaskGate.runExclusive {
                    /** 本次导出开始时间，只用于诊断耗时。 */
                    val startedAt = System.currentTimeMillis()
                    /** 导出开始时捕获的业务版本，后续变更不能由这份备份确认。 */
                    val revision = backupRuntime.captureRevision()
                    /** 文件选择器导出不等于已配置的本地目录或 WebDAV；存在任一配置目标时保留 dirty。 */
                    val targetSettings = backupSettings.snapshot()
                    /** 用户明确配置的自动备份目标是否仍需要独立成功。 */
                    val hasConfiguredTarget = targetSettings.webDavEndpoint.isNotBlank() || targetSettings.localBackupDirUri.isNotBlank()
                    logI(TAG) { "开始手动本地备份 taskId=$taskId operation=manual_local_backup" }
                    /** 与版本 R 对应的 ZIP 快照和临时文件。 */
                    val export = backupRepository.createSnapshot(BackupSource.LocalManual, taskId = taskId)
                    try {
                        logD(TAG) {
                            "手动本地备份快照已生成 taskId=$taskId fileName=${export.fileName} fileSize=${export.fileSize} " +
                                "${export.manifest.summary.toLogFields()}"
                        }
                        writeFile(uri, export.packageFile)
                        /** 文件已写入系统 URI，确认版本失败只保留 dirty，不能让用户误以为文件未生成。 */
                        var acknowledgementFailed = false
                        if (!hasConfiguredTarget) {
                            try {
                                backupRuntime.acknowledgeRevision(revision)
                            } catch (failure: Throwable) {
                                if (failure is CancellationException) throw failure
                                acknowledgementFailed = true
                                logW(TAG) { "手动本地备份版本确认失败 taskId=$taskId reasonCode=${failure.backupReasonCode()}" }
                            }
                        }
                        logI(TAG) {
                            "手动本地备份成功 taskId=$taskId fileName=${export.fileName} fileSize=${export.fileSize} " +
                                "durationMs=${System.currentTimeMillis() - startedAt}"
                        }
                        emitMessage(
                            if (acknowledgementFailed) HostR.string.host_backup_export_sync_failed
                            else R.string.base_general_backup_export_success
                        )
                    } finally {
                        tempFileStore.cleanupTaskDir(export.taskDir, taskId)
                    }
                }
            }
        }
    }

    /** 先提交合法草稿，再测试 WebDAV 连接并记录健康状态；草稿提交失败时不发起网络。 */
    fun testWebDav() {
        viewModelScope.launch {
            /** 连接测试任务身份，不含端点或账号。 */
            val taskId = newBackupTaskId("webdav-test")
            runOperation(
                operation = BackupBusyOperation.TestingWebDav,
                taskId = taskId,
                onFailure = { markWebDavUnavailable(taskId, it) }
            ) {
                /** 连接测试开始时间，只用于诊断耗时。 */
                val startedAt = System.currentTimeMillis()
                commitWebDavDraftIfNeeded()
                /** 草稿提交后的一致 WebDAV 网络配置。 */
                val config = currentWebDavConfig()
                logI(TAG) {
                    "开始测试 WebDAV 连接 taskId=$taskId allowHttp=${config.allowInsecureHttp} " +
                        "hasUsername=${config.username.isNotBlank()} remoteDirLength=${config.remoteDir.length}"
                }
                webDavClient.testAndPrepare(config, taskId)
                backupRuntime.setWebDavHealth(BackupTargetHealth.Available, System.currentTimeMillis())
                BackupAutoScheduler.reschedule(appContext)
                logI(TAG) { "WebDAV 连接测试成功 taskId=$taskId durationMs=${System.currentTimeMillis() - startedAt}" }
                emitMessage(R.string.base_general_webdav_test_success)
                // 连接测试成功已写入健康状态，列表刷新属于附加读取，不反转已成功的连接结论。
                try {
                    refreshRemoteBackupsInternal(taskId)
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    logW(TAG) { "连接测试后列表刷新失败 taskId=$taskId reasonCode=${failure.backupReasonCode()}" }
                }
            }
        }
    }

    /** 手动上传一份 WebDAV 备份。 */
    fun uploadWebDavBackup() {
        viewModelScope.launch {
            /** 手动上传任务身份，连接日志不暴露配置明文。 */
            val taskId = newBackupTaskId("manual-webdav")
            runOperation(BackupBusyOperation.UploadingWebDav, taskId = taskId) {
                BackupTaskGate.runExclusive {
                    commitWebDavDraftIfNeeded()
                    /** 上传前先构造可用凭据，避免本地镜像写成后才发现密钥失效。 */
                    val config = currentWebDavConfig()
                    // 端点协议校验同样在本地镜像写入前完成，防止非法地址造成部分目标副作用。
                    validateWebDavEndpoint(config.endpoint, config.allowInsecureHttp)
                    /** 上传开始时间，只用于诊断耗时。 */
                    val startedAt = System.currentTimeMillis()
                    /** 此次导出覆盖的最大业务版本；后续编辑仍保持 dirty。 */
                    val revision = backupRuntime.captureRevision()
                    logI(TAG) {
                        "开始手动 WebDAV 备份 taskId=$taskId hasLocalDir=${uiState.value.localBackupDirUri.isNotBlank()} " +
                            "webDavHealth=${uiState.value.webDavHealth}"
                    }
                    /** 本地镜像与远端上传共用的一份 ZIP 快照。 */
                    val export = backupRepository.createSnapshot(BackupSource.WebDavManual, taskId = taskId)
                    try {
                        logD(TAG) {
                            "手动 WebDAV 备份快照已生成 taskId=$taskId fileName=${export.fileName} fileSize=${export.fileSize} " +
                                "${export.manifest.summary.toLogFields()}"
                        }
                        writeConfiguredLocalBackupIfNeeded(export, taskId)
                        webDavClient.uploadBackup(config, export, taskId)
                        /** 两个目标均已写入后才确认版本；确认失败保留 dirty 并报告部分完成。 */
                        var acknowledgementFailed = false
                        try {
                            backupRuntime.acknowledgeRevision(revision)
                        } catch (failure: Throwable) {
                            if (failure is CancellationException) throw failure
                            acknowledgementFailed = true
                            logW(TAG) { "手动 WebDAV 备份版本确认失败 taskId=$taskId reasonCode=${failure.backupReasonCode()}" }
                        }
                        logI(TAG) {
                            "手动 WebDAV 备份成功 taskId=$taskId fileName=${export.fileName} fileSize=${export.fileSize} " +
                                "durationMs=${System.currentTimeMillis() - startedAt}"
                        }
                        emitMessage(
                            if (acknowledgementFailed) HostR.string.host_backup_upload_sync_failed
                            else R.string.base_general_webdav_upload_success
                        )
                        // 备份已经完整上传并确认版本，列表刷新失败只能提示刷新失败，不能误报上传失败。
                        try {
                            refreshRemoteBackupsInternal(taskId)
                        } catch (failure: Throwable) {
                            if (failure is CancellationException) throw failure
                            logW(TAG) { "手动上传后列表刷新失败 taskId=$taskId reasonCode=${failure.backupReasonCode()}" }
                        }
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
                commitWebDavDraftIfNeeded()
                logD(TAG) { "开始刷新 WebDAV 备份列表 taskId=$taskId" }
                refreshRemoteBackupsInternal(taskId)
            }
        }
    }

    /** 刷新本地备份目录列表，刷新前按保留份数清理普通备份，只读取 manifest 和文件元数据。 */
    fun refreshLocalBackups() {
        /** 页面当前已提交目录可能仍在 Flow 首次加载，空值无需发起 IO。 */
        val dir = uiState.value.localBackupDirUri.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val taskId = newBackupTaskId("local-list")
            runOperation(BackupBusyOperation.RefreshingLocal, taskId = taskId) {
                /** 数据层已提交的保留份数，避免使用草稿或旧同步门面。 */
                val keepCount = backupSettings.snapshot().backupRetentionCount
                val cleanup = localBackupDirectoryWriter.pruneBackups(Uri.parse(dir), keepCount, taskId)
                val files = localBackupDirectoryWriter.listBackups(Uri.parse(dir))
                _uiState.update { it.copy(localBackups = files) }
                logD(TAG) { "本地备份列表刷新成功 taskId=$taskId count=${files.size} deleted=${cleanup.deletedCount}" }
            }
        }
    }

    /** 刷新远端备份列表的内部实现；刷新前会按保留份数清理普通备份，确保列表与配置一致。 */
    private suspend fun refreshRemoteBackupsInternal(taskId: String? = null) {
        /** 同一份磁盘快照提供 WebDAV 配置、凭据和保留份数。 */
        val connection = backupSettings.loadWebDavConnection()
        val config = connection.toWebDavConfig()
        val cleanup = webDavClient.pruneBackups(
            config = config,
            keepCount = connection.settings.backupRetentionCount,
            taskId = taskId
        )
        val files = webDavClient.listBackups(config)
        /** 连通性记录在持久化成功后由 Flow 刷新页面状态。 */
        val checkedAt = System.currentTimeMillis()
        backupRuntime.setWebDavHealth(BackupTargetHealth.Available, checkedAt)
        _uiState.update {
            it.copy(
                remoteBackups = files,
                webDavHealth = BackupTargetHealth.Available,
                webDavHealthCheckedAt = checkedAt
            )
        }
        logD(TAG) { "WebDAV 备份列表刷新成功 taskId=$taskId count=${files.size} deleted=${cleanup.deletedCount}" }
    }

    /** 如果用户设置了本地备份目录，WebDAV 备份前先写入同一份 zip 快照和 manifest。 */
    private suspend fun writeConfiguredLocalBackupIfNeeded(export: BackupExportResult, taskId: String) {
        /** 目标目录和保留策略来自已提交的同一配置快照。 */
        val settings = backupSettings.snapshot()
        val dir = settings.localBackupDirUri.takeIf { it.isNotBlank() } ?: return
        logD(TAG) { "开始写入 WebDAV 备份的本地镜像 taskId=$taskId fileName=${export.fileName} fileSize=${export.fileSize}" }
        localBackupDirectoryWriter.writeExport(Uri.parse(dir), export, taskId)
        val cleanup = localBackupDirectoryWriter.pruneBackups(Uri.parse(dir), settings.backupRetentionCount, taskId)
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

    /** 按表单当前值成组提交并回填结果；不拦截账号变更，失败时保留完整草稿供重试。 */
    private suspend fun commitWebDavDraftIfNeeded() {
        if (!webDavDraftDirty) return
        /** 本次 UI 内存草稿，只在当前页面生命周期内保留。 */
        val draft = uiState.value
        // 当前输入框密码（包括空字符串）直接替换保存值，认证是否成功由实际连接决定。
        backupSettings.saveWebDav(draft.toWebDavSettingsInput())
        /** 保存后读取真实已提交的密码，包括用户清空后保存的空字符串。 */
        val connection = readWebDavFormConnection()
        webDavDraftDirty = false
        _uiState.update { it.withWebDavConnection(connection, preserveDraft = false) }
        BackupAutoScheduler.reschedule(appContext)
    }

    /** 单次已提交 WebDAV 设置构造网络参数；密码只在网络任务执行时按需解密。 */
    private suspend fun currentWebDavConfig(): WebDavConfig = backupSettings.loadWebDavConnection().toWebDavConfig()

    /** 从同一磁盘快照派生网络参数，避免配置与密文发生跨事务混用。 */
    private fun WebDavConnectionSnapshot.toWebDavConfig(): WebDavConfig {
        return WebDavConfig(
            endpoint = settings.webDavEndpoint,
            username = settings.webDavUsername,
            password = password.orEmpty(),
            remoteDir = normalizeWebDavRemoteDir(settings.webDavRemoteDir),
            allowInsecureHttp = settings.webDavAllowInsecureHttp
        )
    }

    /** 判断是否存在任一备份目标；WebDAV 目标只检查基础配置，真实可写性仍由测试连接或 Worker 处理。 */
    private fun hasAnyBackupTarget(): Boolean {
        val state = uiState.value
        return state.localBackupDirUri.isNotBlank() ||
            (state.webDavEndpoint.isNotBlank() && state.webDavHealth == BackupTargetHealth.Available)
    }

    /** 统一包装页面任务状态和失败提示；取消继续传播，避免把用户离开误报为业务失败。 */
    private suspend fun runOperation(
        operation: BackupBusyOperation,
        taskId: String? = null,
        onFailure: suspend (Throwable) -> Unit = {},
        block: suspend () -> Unit
    ) {
        _uiState.update { it.copy(busyOperation = operation, backupProgress = operation.toProgress(taskId)) }
        try {
            block()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            logE(TAG) {
                "备份页面操作失败 ${com.cla.clip.base.general.backup.backupTaskLogField(taskId)}operation=$operation " +
                    "reasonCode=${throwable.backupReasonCode()} type=${throwable::class.simpleName}"
            }
            runCatching { onFailure(throwable) }
            emitMessage(mapErrorMessage(throwable))
        } finally {
            _uiState.update { it.copy(busyOperation = BackupBusyOperation.None, backupProgress = null) }
        }
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
            BackupBusyOperation.SavingSettings,
            BackupBusyOperation.None -> BackupProgressPhase.Preparing
        }
        return BackupProgress(taskId = id, phase = phase, category = BackupProgressCategory.Overall)
    }

    /** 记录 WebDAV 当前不可用；只缓存健康状态，不输出 URL、账号或密码。 */
    private suspend fun markWebDavUnavailable(taskId: String?, throwable: Throwable) {
        // 草稿校验或加密失败发生在连接尝试之前，不能污染已提交目标的健康状态。
        if (webDavDraftDirty) return
        /** 当前失败时间只用于本机健康缓存，不写入便携备份。 */
        val checkedAt = System.currentTimeMillis()
        backupRuntime.setWebDavHealth(BackupTargetHealth.Unavailable, checkedAt)
        BackupAutoScheduler.reschedule(appContext)
        _uiState.update {
            it.copy(
                webDavHealth = BackupTargetHealth.Unavailable,
                webDavHealthCheckedAt = checkedAt
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
            is WebDavCredentialUnavailable -> HostR.string.host_webdav_password_unavailable
            is BackupFailure.AuthenticationFailed -> R.string.base_general_backup_error_auth
            is IllegalArgumentException -> HostR.string.host_webdav_config_invalid
            is BackupFailure.StorageNotWritable -> R.string.base_general_backup_error_storage
            is BackupFailure.RemoteFailed -> R.string.base_general_backup_error_remote
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

}

/**
 * 备份页正在执行的长任务类型。
 *
 * 备份页和独立恢复页复用同一组任务枚举，便于进度文案和日志字段保持一致。
 */
enum class BackupBusyOperation {
    /** 当前没有长任务。 */
    None,
    /** 正在等待本机 DataStore 配置提交。 */
    SavingSettings,
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

/** 备份页 UI 状态；凭据草稿只存在页面内存，密码表示输入框当前值，不写入状态恢复或备份。 */
data class BackupUiState(
    /** 配置和安装身份均读取成功后才允许触发副作用。 */
    val settingsLoaded: Boolean = false,
    /** 读盘异常与新安装缺键不同，页面提供显式重试入口。 */
    val settingsLoadFailed: Boolean = false,
    /** 当前长任务类型，`None` 表示页面可自由操作。 */
    val busyOperation: BackupBusyOperation = BackupBusyOperation.None,
    /** WebDAV 服务地址。 */
    val webDavEndpoint: String = "",
    /** WebDAV 用户名。 */
    val webDavUsername: String = "",
    /** 已保存密码回填或用户输入的明文，仅保留在页面内存；共享输入组件默认遮蔽，不写入状态恢复或备份。 */
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
    /** 是否已确认本地 ZIP 与 WebDAV 备份不会加密；仅用于决定首次生成前是否展示披露。 */
    val backupPlaintextDisclosureAccepted: Boolean = false,
    /** 普通备份保留份数。 */
    val backupRetentionCount: Int = 5,
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
