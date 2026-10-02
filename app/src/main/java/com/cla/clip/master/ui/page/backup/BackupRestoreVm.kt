package com.cla.clip.master.ui.page.backup

import com.cla.clip.master.backup.local.LocalBackupDirectoryWriter
import com.cla.clip.master.backup.local.LocalBackupFile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.R
import com.cla.clip.master.R as HostR
import com.cla.clip.base.general.backup.BackupFailure
import com.cla.clip.base.general.backup.BackupPackageRef
import com.cla.clip.base.general.backup.BackupProgress
import com.cla.clip.base.general.backup.BackupProgressCategory
import com.cla.clip.base.general.backup.BackupProgressPhase
import com.cla.clip.base.general.backup.BackupPreview
import com.cla.clip.base.general.backup.BackupRepository
import com.cla.clip.base.general.backup.BackupTempFileStore
import com.cla.clip.base.general.backup.WebDavClient
import com.cla.clip.base.general.backup.WebDavConfig
import com.cla.clip.base.general.backup.backupReasonCode
import com.cla.clip.base.general.backup.logCode
import com.cla.clip.base.general.backup.newBackupTaskId
import com.cla.clip.base.general.backup.normalizeWebDavRemoteDir
import com.cla.clip.base.general.backup.toLogFields
import com.cla.clip.base.general.backup.settings.BackupSettingsRepository
import com.cla.clip.base.general.backup.settings.WebDavCredentialUnavailable
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import com.cla.clip.master.work.BackupTaskGate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 独立恢复流程页 ViewModel。
 *
 * 只负责一次备份读取、预览和恢复流程；入口请求来自备份恢复 feature 内部请求流，页面关闭时会清理临时文件和引用。
 */
@HiltViewModel
class BackupRestoreVm @Inject constructor(
    /** 应用级 Context，用于复制系统文件选择器返回的备份文件并读取字符串资源。 */
    @param:ApplicationContext private val appContext: Context,
    /** 统一备份仓库，负责预检和恢复写库。 */
    private val backupRepository: BackupRepository,
    /** WebDAV 客户端，负责远端备份下载。 */
    private val webDavClient: WebDavClient,
    /** 本地备份目录写入器，复用其中的 SAF 文件复制能力。 */
    private val localBackupDirectoryWriter: LocalBackupDirectoryWriter,
    /** 备份临时文件目录管理器，恢复流程结束时必须清理。 */
    private val tempFileStore: BackupTempFileStore,
    /** 恢复远端文件时读取已提交的设备绑定 WebDAV 配置与密码。 */
    private val backupSettings: BackupSettingsRepository,
) : ViewModel() {
    companion object {
        /** 日志标签，只记录任务状态和 reasonCode，不输出用户内容或路径。 */
        private const val TAG = "BackupRestoreVm"
    }

    /** 恢复页独立状态，初始请求检查结束前不执行 Hidden 返回。 */
    private val _uiState = MutableStateFlow(BackupRestoreUiState())

    /** 恢复流程页状态。 */
    val uiState = _uiState.asStateFlow()

    /** 当前恢复流程使用的临时目录；读取失败尚未形成 `BackupPackageRef` 时也需要靠它清理。 */
    private var restoreFlowTaskDir: File? = null

    /** 当前读取或恢复任务，用于用户二次确认返回后取消并清理临时状态。 */
    private var restoreFlowJob: Job? = null

    /** 已接管的请求 id，避免页面重组重复启动读取。 */
    private var consumedRequestId: Long? = null

    /** 父图显式交付的会话，不在 init 或构造注入中取得全局通信对象。 */
    private var flowSession: BackupFlowSession? = null

    /** 绑定只订阅轻量内存回显，恢复页被媒体页覆盖时仍能同步完成门禁。 */
    internal fun bindSession(session: BackupFlowSession): Boolean {
        if (flowSession === session) return true
        if (flowSession != null) {
            logW(TAG) { "恢复页会话绑定已拒绝 reasonCode=session_mismatch" }
            return false
        }
        flowSession = session
        viewModelScope.launch {
            // 跨页同步属于当前图流程，不执行 IO，不受恢复页是否被媒体页覆盖影响。
            session.mediaState.collect { media ->
                if (media.restoreTaskId == currentRestoreFlowTaskId()) {
                    _uiState.update { it.copy(mediaRelocationEntryState = media.entryState, lastTerminalMediaRelocationSummary = media.lastTerminalSummary) }
                }
            }
        }
        return true
    }

    /** 首次可见时接管已提交的请求；重建复用 VM 不重复读取，缺失请求交给导航降级。 */
    internal fun startPendingRestore() {
        if (_uiState.value.requestChecked) return
        /** 同步接管只能读取当前图槽位；没有绑定时保留初始等待。 */
        val session = flowSession ?: return
        /** 当前唯一待接管目标；接管确认后不再长期保留于槽位。 */
        val request = session.requests.value
        if (request != null) {
            tempFileStore.cleanupExpired()
            startFromRequest(request)
            // Reading 状态已建立再确认接管，不能先清空槽位再尝试开始。
            if (!session.acknowledgeRequest(request.requestId)) {
                restoreFlowJob?.cancel()
                clearPreview()
            }
        }
        _uiState.update { it.copy(requestChecked = true) }
    }

    /** 接管备份页传来的恢复请求，并立即进入读取状态。 */
    private fun startFromRequest(request: BackupRestoreRequest) {
        if (consumedRequestId == request.requestId) return
        consumedRequestId = request.requestId
        /** 来源只决定读取阶段，不把真实地址暴露给导航。 */
        val sourceType = request.openSource
        /** 预检使用请求初始身份，恢复成功后另登记 Result 身份。 */
        val taskId = request.initialTaskId
        startRestoreFlowReading(
            taskId = taskId,
            sourceType = sourceType,
            readPhase = sourceType.defaultReadPhase()
        )
        when (request) {
            is BackupRestoreRequest.LocalFile -> previewFromUri(request.uri, taskId)
            is BackupRestoreRequest.LocalDirectory -> previewLocalBackup(request.file, taskId)
            is BackupRestoreRequest.WebDav -> previewRemoteBackup(request.file, taskId)
        }
    }

    /** 恢复当前预检过的备份。 */
    fun restoreSelectedBackup() {
        /** 预检阶段确认过的本地临时包引用，重试时复用同一份输入。 */
        val packageRef = uiState.value.selectedBackupRef ?: return
        /** 当前入口类型只用于流程展示和低敏日志。 */
        val sourceType = uiState.value.restoreFlow.sourceTypeOrUnknown()
        restoreFlowJob?.cancel()
        restoreFlowJob = viewModelScope.launch {
            /** 本次恢复尝试的任务标识，不携带备份内容或路径。 */
            val taskId = newBackupTaskId("restore")
            transitionRestoreFlow(
                nextState = BackupRestoreFlowState.Restoring(
                    taskId = taskId,
                    sourceType = sourceType,
                    progress = BackupBusyOperation.Restoring.toProgress(taskId)
                ),
                taskId = taskId,
                reasonCode = "restore_started"
            )
            runRestoreFlowOperation(
                operation = BackupBusyOperation.Restoring,
                taskId = taskId,
                sourceType = sourceType,
                failureStage = BackupRestoreFailureStage.Restoring
            ) {
                BackupTaskGate.runExclusive {
                    /** 本次恢复耗时统计起点。 */
                    val startedAt = System.currentTimeMillis()
                    /** 用户在进入结果页前看到的已校验清单摘要。 */
                    val preview = uiState.value.selectedPreview
                    /** 提交前确保临时包仍可读取，避免已清理引用误入恢复器。 */
                    val readable = packageRef.requireReadable()
                    logI(TAG) {
                        "开始恢复备份 taskId=$taskId backupKind=${preview?.backupKind?.logCode()} " +
                            "fileSize=${readable.length()} ${preview?.summary?.toLogFields().orEmpty()}"
                    }
                    /** 宿主、功能库和设置阶段分开提交；部分失败要保留已确认的报告。 */
                    var partialRestoreStage: BackupPartialRestoreStage? = null
                    /** 未知异常仍由完整失败流程处理，不能凭猜测报告部分成功。 */
                    val report = try {
                        backupRepository.restoreSnapshot(packageRef)
                    } catch (failure: Throwable) {
                        if (failure is CancellationException) throw failure
                        /** 只有明确携带已提交报告的阶段异常可进入结果页。 */
                        val partial = failure.toPartialRestoreOutcomeOrNull() ?: throw failure
                        partialRestoreStage = partial.stage
                        partial.report
                    }
                    if (currentRestoreFlowTaskId() != taskId) {
                        logW(TAG) { "备份恢复结果已忽略 taskId=$taskId reasonCode=flow_closed" }
                        return@runExclusive
                    }
                    /** 已提交阶段最终结束时间，用于结果页展示。 */
                    val completedAt = System.currentTimeMillis()
                    transitionRestoreFlow(
                        nextState = BackupRestoreFlowState.Result(
                            taskId = taskId,
                            sourceType = sourceType,
                            fileName = packageRef.fileName,
                            preview = preview,
                            report = report,
                            completedAt = completedAt,
                            settingsRestoreFailed = partialRestoreStage == BackupPartialRestoreStage.Settings,
                            featureRestoreFailed = partialRestoreStage == BackupPartialRestoreStage.Feature,
                        ),
                        taskId = taskId,
                        reasonCode = when (partialRestoreStage) {
                            BackupPartialRestoreStage.Feature -> "restore_partial_feature_failed"
                            BackupPartialRestoreStage.Settings -> "restore_partial_settings_failed"
                            null -> "restore_success"
                        }
                    )
                    // 只有完整结果可进入媒体关联，任务身份使用本次恢复的 Result.taskId。
                    if (partialRestoreStage == null) {
                        consumedRequestId?.let { requestId -> flowSession?.registerRestoreTask(requestId, taskId) }
                    }
                    logI(TAG) {
                        "备份恢复结束 taskId=$taskId partialStage=$partialRestoreStage " +
                            "inserted=${report.insertedCount} updated=${report.updatedCount} " +
                            "skipped=${report.skippedCount} durationMs=${System.currentTimeMillis() - startedAt}"
                    }
                }
            }
        }
    }

    /** 普通退出返回是否允许导航；会话 Running 是最终门禁，不依赖 UI collector 更新速度。 */
    fun dismissRestoreFlow(): Boolean {
        if (flowSession?.mediaState?.value?.entryState?.isRunning == true) return false
        when (uiState.value.restoreFlow) {
            is BackupRestoreFlowState.Reading,
            is BackupRestoreFlowState.Restoring -> return false
            else -> clearPreview()
        }
        return true
    }

    /** 用户二次确认后取消页面任务；返回许可用于防止会话 Running 时仍执行弹栈。 */
    fun forceCloseRestoreFlow(): Boolean {
        /** 当前阶段和任务身份仅用于取消及低敏日志。 */
        val state = uiState.value.restoreFlow
        if (flowSession?.mediaState?.value?.entryState?.isRunning == true) {
            logW(TAG) { "媒体关联运行中，忽略页面内退出请求 state=${state.logCode}" }
            return false
        }
        if (state is BackupRestoreFlowState.Hidden) return false
        /** 二次确认的当前任务只用于取消清理诊断。 */
        val taskId = currentRestoreFlowTaskId()
        restoreFlowJob?.cancel()
        logW(TAG) {
            "用户确认退出备份恢复流程页 taskId=$taskId state=${state.logCode} sourceType=${state.sourceTypeOrUnknown().logCode}"
        }
        clearPreview()
        _uiState.update { it.copy(busyOperation = BackupBusyOperation.None, backupProgress = null) }
        return true
    }

    /** 从用户选择的 URI 读取备份并只做预检，不写入数据库。 */
    private fun previewFromUri(uri: Uri, taskId: String) {
        restoreFlowJob?.cancel()
        restoreFlowJob = viewModelScope.launch {
            runRestoreFlowOperation(
                operation = BackupBusyOperation.PreviewingLocal,
                taskId = taskId,
                sourceType = BackupRestoreOpenSource.LocalFile,
                failureStage = BackupRestoreFailureStage.Reading
            ) {
                val ref = copyUriToPackageRef(uri, taskId)
                val preview = backupRepository.previewSnapshot(ref)
                showRestoreFlowPreview(taskId, BackupRestoreOpenSource.LocalFile, ref, preview)
            }
        }
    }

    /** 从本地备份目录列表读取并预览。 */
    private fun previewLocalBackup(file: LocalBackupFile, taskId: String) {
        restoreFlowJob?.cancel()
        restoreFlowJob = viewModelScope.launch {
            runRestoreFlowOperation(
                operation = BackupBusyOperation.PreviewingLocal,
                taskId = taskId,
                sourceType = BackupRestoreOpenSource.LocalDirectory,
                failureStage = BackupRestoreFailureStage.Reading
            ) {
                val taskDir = createRestoreImportDir(taskId)
                val ref = localBackupDirectoryWriter.copyBackupToRef(
                    file = file,
                    targetFile = File(taskDir, file.fileName),
                    taskDir = taskDir
                )
                val preview = backupRepository.previewSnapshot(ref)
                showRestoreFlowPreview(taskId, BackupRestoreOpenSource.LocalDirectory, ref, preview)
            }
        }
    }

    /** 下载并预览指定远端备份。 */
    private fun previewRemoteBackup(file: com.cla.clip.base.general.backup.RemoteBackupFile, taskId: String) {
        restoreFlowJob?.cancel()
        restoreFlowJob = viewModelScope.launch {
            runRestoreFlowOperation(
                operation = BackupBusyOperation.PreviewingRemote,
                taskId = taskId,
                sourceType = BackupRestoreOpenSource.WebDav,
                failureStage = BackupRestoreFailureStage.Reading
            ) {
                val taskDir = createRestoreImportDir(taskId)
                val localFile = File(taskDir, file.fileName)
                webDavClient.downloadFile(currentWebDavConfig(), file.fileName, localFile)
                val ref = BackupPackageRef(file = localFile, fileName = file.fileName, taskDir = taskDir)
                val preview = backupRepository.previewSnapshot(ref)
                showRestoreFlowPreview(taskId, BackupRestoreOpenSource.WebDav, ref, preview)
            }
        }
    }

    /** 清空当前预览和临时文件。 */
    private fun clearPreview() {
        /** 保留待清理包及当前请求所有权，先释放会话后再归零 UI。 */
        val oldRef = _uiState.value.selectedBackupRef
        consumedRequestId?.let { requestId -> flowSession?.finishRestore(requestId) }
        restoreFlowJob = null
        _uiState.update {
            it.copy(
                selectedBackupRef = null,
                selectedPreview = null,
                backupProgress = null,
                busyOperation = BackupBusyOperation.None,
                restoreFlow = BackupRestoreFlowState.Hidden,
                mediaRelocationEntryState = MediaRelocationEntryState.NotStarted,
                lastTerminalMediaRelocationSummary = null
            )
        }
        tempFileStore.cleanupTaskDir(oldRef?.taskDir ?: restoreFlowTaskDir)
        restoreFlowTaskDir = null
        consumedRequestId = null
    }

    /** 进入读取状态；先清理旧临时文件，确保一次流程只服务一个备份。 */
    private fun startRestoreFlowReading(
        taskId: String,
        sourceType: BackupRestoreOpenSource,
        readPhase: BackupRestoreReadPhase,
    ) {
        val oldRef = _uiState.value.selectedBackupRef
        val oldTaskDir = restoreFlowTaskDir
        val nextState = BackupRestoreFlowState.Reading(
            taskId = taskId,
            sourceType = sourceType,
            readPhase = readPhase,
            progress = BackupBusyOperation.PreviewingLocal.toProgress(taskId)
        )
        _uiState.update {
            it.copy(
                selectedBackupRef = null,
                selectedPreview = null,
                restoreFlow = nextState,
                mediaRelocationEntryState = MediaRelocationEntryState.NotStarted,
                lastTerminalMediaRelocationSummary = null
            )
        }
        tempFileStore.cleanupTaskDir(oldRef?.taskDir ?: oldTaskDir)
        restoreFlowTaskDir = null
        logRestoreFlowTransition(BackupRestoreFlowState.Hidden, nextState, taskId, sourceType, "read_started")
    }

    /** 预检成功后切换到预览状态，保留临时文件直到用户恢复或关闭流程页。 */
    private fun showRestoreFlowPreview(
        taskId: String,
        sourceType: BackupRestoreOpenSource,
        ref: BackupPackageRef,
        preview: BackupPreview,
    ) {
        if (currentRestoreFlowTaskId() != taskId) {
            logW(TAG) { "备份预览结果已忽略 taskId=$taskId reasonCode=flow_closed sourceType=${sourceType.logCode}" }
            tempFileStore.cleanupTaskDir(ref.taskDir, taskId)
            return
        }
        val nextState = BackupRestoreFlowState.Preview(
            taskId = taskId,
            sourceType = sourceType,
            preview = preview,
            fileName = ref.fileName,
            fileSize = ref.requireReadable().length()
        )
        _uiState.update {
            it.copy(
                selectedBackupRef = ref,
                selectedPreview = preview,
                restoreFlow = nextState
            )
        }
        restoreFlowTaskDir = ref.taskDir
        logRestoreFlowTransition(
            BackupRestoreFlowState.Reading(taskId, sourceType, sourceType.defaultReadPhase(), null),
            nextState,
            taskId,
            sourceType,
            "preview_ready"
        )
    }

    /** 恢复流程页专用长任务包装，失败时停留在同一个页面内展示可行动原因。 */
    private suspend fun runRestoreFlowOperation(
        operation: BackupBusyOperation,
        taskId: String,
        sourceType: BackupRestoreOpenSource,
        failureStage: BackupRestoreFailureStage,
        block: suspend () -> Unit
    ) {
        _uiState.update { it.copy(busyOperation = operation, backupProgress = operation.toProgress(taskId)) }
        runCatching {
            block()
        }.onSuccess {
            clearRestoreBusyIfCurrent(operation, taskId)
        }.onFailure { throwable ->
            if (throwable is CancellationException && currentRestoreFlowTaskId() == null) {
                logW(TAG) {
                    "备份恢复流程页任务已取消 taskId=$taskId operation=$operation sourceType=${sourceType.logCode} " +
                        "stage=${failureStage.logCode}"
                }
                return
            }
            if (currentRestoreFlowTaskId() != taskId) {
                logW(TAG) {
                    "备份恢复流程页忽略过期任务结果 taskId=$taskId operation=$operation sourceType=${sourceType.logCode} " +
                        "stage=${failureStage.logCode} reasonCode=${throwable.backupReasonCode()}"
                }
                return
            }
            if (throwable is CancellationException) {
                logW(TAG) {
                    "备份恢复流程页任务已取消 taskId=$taskId operation=$operation sourceType=${sourceType.logCode} " +
                        "stage=${failureStage.logCode}"
                }
                clearRestoreBusyIfCurrent(operation, taskId)
                return
            }
            val reasonCode = throwable.backupReasonCode()
            logE(TAG) {
                "备份恢复流程页 操作失败 taskId=$taskId operation=$operation sourceType=${sourceType.logCode} " +
                    "stage=${failureStage.logCode} reasonCode=$reasonCode type=${throwable::class.simpleName}"
            }
            val errorState = BackupRestoreFlowState.Error(
                taskId = taskId,
                sourceType = sourceType,
                stage = failureStage,
                reasonCode = reasonCode,
                message = mapErrorMessage(throwable)
            )
            transitionRestoreFlow(errorState, taskId, reasonCode)
            clearRestoreBusyIfCurrent(operation, taskId)
        }
    }

    /** 仅清理当前恢复流程任务的忙碌态，避免旧协程取消回调覆盖新任务状态。 */
    private fun clearRestoreBusyIfCurrent(operation: BackupBusyOperation, taskId: String) {
        if (_uiState.value.busyOperation == operation && currentRestoreFlowTaskId() == taskId) {
            _uiState.update { it.copy(busyOperation = BackupBusyOperation.None, backupProgress = null) }
        }
    }

    /** 恢复流程页状态切换，集中记录低敏日志并避免多个 Boolean 组合造成状态不一致。 */
    private fun transitionRestoreFlow(
        nextState: BackupRestoreFlowState,
        taskId: String,
        reasonCode: String,
    ) {
        val previous = _uiState.value.restoreFlow
        _uiState.update { it.copy(restoreFlow = nextState) }
        logRestoreFlowTransition(previous, nextState, taskId, nextState.sourceTypeOrUnknown(), reasonCode)
    }

    /** 记录恢复流程页状态变化；只输出状态、来源和 reasonCode，不输出路径、URI 或备份内容。 */
    private fun logRestoreFlowTransition(
        fromState: BackupRestoreFlowState,
        toState: BackupRestoreFlowState,
        taskId: String,
        sourceType: BackupRestoreOpenSource,
        reasonCode: String,
    ) {
        logD(TAG) {
            "备份恢复流程页 状态切换 taskId=$taskId fromState=${fromState.logCode} toState=${toState.logCode} " +
                "sourceType=${sourceType.logCode} reasonCode=$reasonCode"
        }
    }

    /** 根据当前操作生成轻量进度状态，后续底层进度事件可继续复用同一模型扩展。 */
    private fun BackupBusyOperation.toProgress(taskId: String?): BackupProgress? {
        val id = taskId ?: return null
        val phase = when (this) {
            BackupBusyOperation.Restoring -> BackupProgressPhase.Restoring
            BackupBusyOperation.PreviewingLocal,
            BackupBusyOperation.PreviewingRemote -> BackupProgressPhase.Verifying
            BackupBusyOperation.Exporting,
            BackupBusyOperation.UploadingWebDav,
            BackupBusyOperation.TestingWebDav,
            BackupBusyOperation.RefreshingWebDav,
            BackupBusyOperation.RefreshingLocal,
            BackupBusyOperation.SavingSettings,
            BackupBusyOperation.None -> BackupProgressPhase.Preparing
        }
        return BackupProgress(taskId = id, phase = phase, category = BackupProgressCategory.Overall)
    }

    /** 恢复流程页来源缺省读取阶段，便于日志记录时构造上一状态，不参与业务判断。 */
    private fun BackupRestoreOpenSource.defaultReadPhase(): BackupRestoreReadPhase {
        return when (this) {
            BackupRestoreOpenSource.WebDav -> BackupRestoreReadPhase.DownloadingAndVerifying
            BackupRestoreOpenSource.LocalFile,
            BackupRestoreOpenSource.LocalDirectory,
            BackupRestoreOpenSource.Unknown -> BackupRestoreReadPhase.CopyingAndVerifying
        }
    }

    /** 当前恢复流程页任务标识；只用于过滤旧协程回调，不参与业务合并逻辑。 */
    private fun currentRestoreFlowTaskId(): String? {
        return when (val state = _uiState.value.restoreFlow) {
            is BackupRestoreFlowState.Reading -> state.taskId
            is BackupRestoreFlowState.Preview -> state.taskId
            is BackupRestoreFlowState.Restoring -> state.taskId
            is BackupRestoreFlowState.Result -> state.taskId
            is BackupRestoreFlowState.Error -> state.taskId
            BackupRestoreFlowState.Hidden -> null
        }
    }

    /** 当前 WebDAV 配置快照；只读取本机配置，不输出 endpoint、账号或密码。 */
    private suspend fun currentWebDavConfig(): WebDavConfig {
        /** 同一次 DataStore 读取同时提供连接字段和对应密文的解密结果。 */
        val connection = backupSettings.loadWebDavConnection()
        return WebDavConfig(
            endpoint = connection.settings.webDavEndpoint,
            username = connection.settings.webDavUsername,
            password = connection.password.orEmpty(),
            remoteDir = normalizeWebDavRemoteDir(connection.settings.webDavRemoteDir),
            allowInsecureHttp = connection.settings.webDavAllowInsecureHttp
        )
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
            is BackupFailure.StorageNotWritable -> R.string.base_general_backup_error_storage
            is BackupFailure.RemoteFailed -> R.string.base_general_backup_error_remote
            is BackupFailure.FileTooLarge -> R.string.base_general_backup_error_file_too_large
            is BackupFailure.TempFileUnavailable -> R.string.base_general_backup_error_temp_file_unavailable
            is BackupFailure.InsufficientSpace -> R.string.base_general_backup_error_insufficient_space
            else -> R.string.base_general_backup_error_unknown
        }
        return appContext.getString(resId)
    }

    /** 将用户选择的外部备份复制到私有临时文件，避免页面间状态持有完整字节数组。 */
    private suspend fun copyUriToPackageRef(uri: Uri, taskId: String): BackupPackageRef = withContext(Dispatchers.IO) {
        val taskDir = createRestoreImportDir(taskId)
        val fileName = queryDisplayName(uri) ?: "selected_backup.zip"
        val target = File(taskDir, fileName)
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw BackupFailure.ParseFailed()
        BackupPackageRef(file = target, fileName = fileName, taskDir = taskDir)
    }

    /** 查询 SAF 文件的 displayName；部分文件管理器不返回名称，调用方需要自行兜底。 */
    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()
    }

    /** 创建恢复流程页临时目录并记录，确保读取失败时也能清理未形成引用的目录。 */
    private fun createRestoreImportDir(taskId: String): File {
        val taskDir = tempFileStore.createImportDir(taskId)
        restoreFlowTaskDir = taskDir
        return taskDir
    }

    /** 恢复目的地释放时取消页面任务并释放会话所有权，不清理其他新请求。 */
    override fun onCleared() {
        /** 当前任务用于低敏清理诊断，包引用仅在本 VM 内使用。 */
        val taskId = currentRestoreFlowTaskId()
        consumedRequestId?.let { requestId -> flowSession?.finishRestore(requestId) }
        restoreFlowJob?.cancel()
        tempFileStore.cleanupTaskDir(_uiState.value.selectedBackupRef?.taskDir ?: restoreFlowTaskDir, taskId)
        super.onCleared()
    }
}

/** 独立恢复页 UI 状态。 */
data class BackupRestoreUiState(
    /** 首次可见时完成请求检查后才允许 Hidden 状态导航退出。 */
    val requestChecked: Boolean = false,
    /** 当前长任务类型，`None` 表示页面可自由操作。 */
    val busyOperation: BackupBusyOperation = BackupBusyOperation.None,
    /** 当前恢复阶段进度；第一版展示阶段，后续可接入类别和数量节流更新。 */
    val backupProgress: BackupProgress? = null,
    /** 当前预览的 zip 备份包文件引用。 */
    val selectedBackupRef: BackupPackageRef? = null,
    /** 当前预检摘要。 */
    val selectedPreview: BackupPreview? = null,
    /** 备份恢复流程页状态。 */
    val restoreFlow: BackupRestoreFlowState = BackupRestoreFlowState.Hidden,
    /** 恢复完成页媒体关联入口状态；真实扫描由独立媒体关联页承载。 */
    val mediaRelocationEntryState: MediaRelocationEntryState = MediaRelocationEntryState.NotStarted,
    /** 最近一次媒体关联终态结构化摘要；新一轮非终态不清空它。 */
    val lastTerminalMediaRelocationSummary: MediaRelocationSummary? = null,
)

/** 恢复请求对应的打开来源，用于接管请求时先同步展示读取中状态。 */
private val BackupRestoreRequest.openSource: BackupRestoreOpenSource
    get() = when (this) {
        is BackupRestoreRequest.LocalFile -> BackupRestoreOpenSource.LocalFile
        is BackupRestoreRequest.LocalDirectory -> BackupRestoreOpenSource.LocalDirectory
        is BackupRestoreRequest.WebDav -> BackupRestoreOpenSource.WebDav
    }

/** 恢复请求对应的初始 taskId，确保进入页面后不出现短暂空白状态。 */
private val BackupRestoreRequest.initialTaskId: String
    get() = when (this) {
        is BackupRestoreRequest.LocalFile -> newBackupTaskId("preview-local")
        is BackupRestoreRequest.LocalDirectory -> newBackupTaskId("preview-local-list")
        is BackupRestoreRequest.WebDav -> newBackupTaskId("preview-remote")
    }
