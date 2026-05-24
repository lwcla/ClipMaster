package com.cla.clip.master.ui.page.backup

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.R
import com.cla.clip.base.general.backup.BackupFailure
import com.cla.clip.base.general.backup.backupReasonCode
import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import com.cla.clip.master.media.DownloadedMediaRelocator
import com.cla.clip.master.media.MediaRelocationCategoryReport
import com.cla.clip.master.media.MediaRelocationPreparation
import com.cla.clip.master.media.MediaRelocationProgress
import com.cla.clip.master.media.MediaRelocationReason
import com.cla.clip.master.media.MediaRelocationReport
import com.cla.clip.master.media.MediaRelocationStage
import com.cla.clip.master.work.BackupAutoScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 恢复本地媒体关联独立页 ViewModel。
 *
 * 负责预估、权限复查、正式扫描和结果发送；恢复页只通过 `BackupMediaRelocationEvents` 接收入口回显状态。
 */
@HiltViewModel
class BackupMediaRelocationVm @Inject constructor(
    /** 应用级 Context，用于读取错误文案和重新调度自动备份。 */
    @param:ApplicationContext private val appContext: Context,
    /** 下载媒体重新定位协作者，封装 MediaStore、旧系统路径和数据库写回。 */
    private val mediaRelocator: DownloadedMediaRelocator,
    /** feature 内部不可重放事件流，用于把入口状态和终态摘要回显到恢复页。 */
    private val events: BackupMediaRelocationEvents,
) : ViewModel() {
    companion object {
        private const val TAG = "BackupMediaRelocationVm"
    }

    private val _uiState = MutableStateFlow<MediaRelocationUiState>(MediaRelocationUiState.Idle)

    val uiState = _uiState.asStateFlow()

    private var restoreTaskId: String? = null
    private var mediaRelocationJob: Job? = null
    private var estimateStarted = false
    private var terminalEventSent = false
    private var runningEventSent = false
    private var closeRestoreFlowOnBack = false

    /** 成功或无须处理终态后，返回动作应关闭恢复链路并回到“我的”页。 */
    val shouldCloseRestoreFlowOnBack: Boolean
        get() = closeRestoreFlowOnBack && !_uiState.value.isRunning

    /**
     * 接收路由中的恢复任务 id，并在首次进入页面时启动一次预估。
     *
     * 重组、横竖屏重建和权限页返回都会复用同一个 ViewModel，不重复启动准备任务。
     */
    fun start(restoreTaskId: String) {
        if (this.restoreTaskId == null) {
            this.restoreTaskId = restoreTaskId
        }
        if (this.restoreTaskId != restoreTaskId) {
            logW(TAG) {
                "媒体关联页忽略不匹配路由 restoreTaskId=$restoreTaskId currentRestoreTaskId=${this.restoreTaskId} " +
                    "reasonCode=restore_task_mismatch"
            }
            return
        }
        if (estimateStarted) return
        estimateStarted = true
        estimateMediaRelocation()
    }

    /** 用户手动重新预估；只允许在非运行态触发，避免重复扫描。 */
    fun restart() {
        if (_uiState.value.isRunning || mediaRelocationJob?.isActive == true) return
        closeRestoreFlowOnBack = false
        terminalEventSent = false
        runningEventSent = false
        estimateStarted = true
        estimateMediaRelocation()
    }

    /** 权限请求返回后继续等待用户确认；权限拒绝时不进入正式扫描。 */
    fun onPermissionResult(grants: Map<String, Boolean>) {
        val state = _uiState.value as? MediaRelocationUiState.PermissionRequired ?: return
        val mediaPermissionDenied = state.preparation.requiredPermissions.any { permission ->
            isRequiredFullMediaPermission(permission) && grants[permission] != true
        }
        if (!mediaPermissionDenied) {
            verifyFullMediaPermissionAfterGrant(state.preparation)
            return
        }
        showPermissionRequired(state.preparation, withDeniedReport = true, sendTerminal = true)
    }

    /** 用户确认预估后正式扫描；开始后页面内不可中断，返回只提示等待。 */
    fun startScan(preparation: MediaRelocationPreparation) {
        if (mediaRelocationJob?.isActive == true || _uiState.value.isRunning) return
        val taskId = restoreTaskId ?: return
        closeRestoreFlowOnBack = false
        terminalEventSent = false
        runningEventSent = false
        mediaRelocationJob = viewModelScope.launch {
            sendRunning(taskId)
            _uiState.update {
                MediaRelocationUiState.Running(
                    MediaRelocationProgress(
                        stage = MediaRelocationStage.VerifyingExisting,
                        processedVideos = 0,
                        totalVideos = preparation.estimate.videoCount,
                        processedImageBatches = 0,
                        totalImageBatches = preparation.estimate.imageBatchCount,
                        processedImageItems = 0,
                        totalImageItems = preparation.estimate.imageItemCount,
                        relocatedCount = 0,
                        report = MediaRelocationReport()
                    )
                )
            }
            runCatching {
                mediaRelocator.relocate(preparation.estimate) { progress ->
                    _uiState.update { MediaRelocationUiState.Running(progress) }
                }
            }.onSuccess { report ->
                if (report.totalRelocated > 0) {
                    BackupAutoScheduler.markDirtyAndSchedule(appContext)
                }
                val summary = MediaRelocationSummary(MediaRelocationSummaryType.Completed, report)
                _uiState.update { MediaRelocationUiState.Result(preparation.estimate, report) }
                sendTerminal(taskId, summary)
            }.onFailure { throwable ->
                if (throwable is CancellationException) {
                    throw throwable
                }
                logE(TAG, throwable) { "媒体关联扫描失败 restoreTaskId=$taskId reasonCode=${throwable.backupReasonCode()}" }
                val summary = MediaRelocationSummary(MediaRelocationSummaryType.Failed, currentReportOrEmpty())
                _uiState.update { MediaRelocationUiState.Error(mapErrorMessage(throwable)) }
                sendTerminal(taskId, summary)
            }
        }
    }

    private fun estimateMediaRelocation() {
        if (mediaRelocationJob?.isActive == true) return
        val taskId = restoreTaskId ?: return
        closeRestoreFlowOnBack = false
        terminalEventSent = false
        runningEventSent = false
        mediaRelocationJob = viewModelScope.launch {
            _uiState.update { MediaRelocationUiState.Estimating }
            sendIncomplete(taskId)
            runCatching {
                val estimate = mediaRelocator.estimate()
                val preparation = mediaRelocator.prepare(estimate)
                when {
                    estimate.totalCount == 0 || !preparation.needsScan -> {
                        val report = preparation.toExistingReadableReport()
                        val summary = MediaRelocationSummary(MediaRelocationSummaryType.NoWork, report)
                        logI(TAG) {
                            "媒体关联无需扫描 restoreTaskId=$taskId totalCount=${estimate.totalCount} " +
                                "existingVideo=${preparation.existingReadableVideoCount} existingImage=${preparation.existingReadableImageCount}"
                        }
                        _uiState.update { MediaRelocationUiState.NoWork(preparation) }
                        sendTerminal(taskId, summary)
                    }
                    preparation.requiredPermissions.isNotEmpty() -> {
                        logD(TAG) {
                            "媒体关联需要权限 restoreTaskId=$taskId permissionCount=${preparation.requiredPermissions.size} " +
                                "needsVideo=${preparation.needsVideoScan} needsImage=${preparation.needsImageScan}"
                        }
                        _uiState.update { MediaRelocationUiState.PermissionRequired(preparation) }
                    }
                    else -> {
                        _uiState.update { MediaRelocationUiState.ReadyToConfirm(preparation) }
                    }
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) {
                    throw throwable
                }
                logE(TAG, throwable) { "媒体关联预估失败 restoreTaskId=$taskId reasonCode=${throwable.backupReasonCode()}" }
                val summary = MediaRelocationSummary(MediaRelocationSummaryType.Failed)
                _uiState.update { MediaRelocationUiState.Error(mapErrorMessage(throwable)) }
                sendTerminal(taskId, summary)
            }
        }
    }

    /**
     * Android 14+ 用户可能只选择部分照片或视频，运行时权限回调仍可能包含媒体权限授予结果。
     * 媒体重新定位需要按目录批量扫描，必须重新探测候选可见性；仍需要授权时视为权限不足，不进入扫描。
     */
    private fun verifyFullMediaPermissionAfterGrant(preparation: MediaRelocationPreparation) {
        if (mediaRelocationJob?.isActive == true) return
        val taskId = restoreTaskId ?: return
        mediaRelocationJob = viewModelScope.launch {
            _uiState.update { MediaRelocationUiState.PermissionChecking(preparation) }
            runCatching {
                mediaRelocator.prepare(preparation.estimate)
            }.onSuccess { refreshed ->
                if (refreshed.requiredPermissions.isNotEmpty()) {
                    logW(TAG) {
                        "媒体关联权限不足，可能为部分媒体访问 restoreTaskId=$taskId " +
                            "reasonCode=${MediaRelocationReason.PERMISSION_DENIED} " +
                            "permissionCount=${refreshed.requiredPermissions.size} " +
                            "permissionRequiredVideo=${refreshed.permissionRequiredVideoCount} " +
                            "permissionRequiredImageItems=${refreshed.permissionRequiredImageItemCount}"
                    }
                    showPermissionRequired(refreshed, withDeniedReport = true, sendTerminal = true)
                } else {
                    sendIncomplete(taskId)
                    _uiState.update { MediaRelocationUiState.ReadyToConfirm(refreshed) }
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) {
                    throw throwable
                }
                logE(TAG, throwable) { "媒体关联权限后复查失败 restoreTaskId=$taskId reasonCode=${throwable.backupReasonCode()}" }
                val summary = MediaRelocationSummary(MediaRelocationSummaryType.Failed, currentReportOrEmpty())
                _uiState.update { MediaRelocationUiState.Error(mapErrorMessage(throwable)) }
                sendTerminal(taskId, summary)
            }
        }
    }

    private fun showPermissionRequired(
        preparation: MediaRelocationPreparation,
        withDeniedReport: Boolean,
        sendTerminal: Boolean,
    ) {
        val taskId = restoreTaskId ?: return
        if (sendTerminal) {
            terminalEventSent = false
        }
        val report = MediaRelocationReport(
            video = MediaRelocationCategoryReport(
                existingReadable = preparation.existingReadableVideoCount,
                permissionDenied = preparation.permissionRequiredVideoCount
            ),
            image = MediaRelocationCategoryReport(
                existingReadable = preparation.existingReadableImageCount,
                permissionDenied = preparation.permissionRequiredImageItemCount
            )
        )
        logW(TAG) {
            "媒体关联权限被拒绝 restoreTaskId=$taskId reasonCode=${MediaRelocationReason.PERMISSION_DENIED} " +
                "needsVideoPermission=${preparation.needsVideoPermission} " +
                "needsImagePermission=${preparation.needsImagePermission} " +
                "permissionRequiredVideo=${preparation.permissionRequiredVideoCount} " +
                "permissionRequiredImageItems=${preparation.permissionRequiredImageItemCount}"
        }
        _uiState.update {
            MediaRelocationUiState.PermissionRequired(
                preparation = preparation,
                report = report.takeIf { withDeniedReport }
            )
        }
        if (sendTerminal) {
            sendTerminal(taskId, MediaRelocationSummary(MediaRelocationSummaryType.PermissionDenied, report))
        }
    }

    private fun isRequiredFullMediaPermission(permission: String): Boolean {
        return permission != android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    }

    private suspend fun sendIncomplete(restoreTaskId: String) {
        events.emitIncomplete(restoreTaskId)
    }

    private suspend fun sendRunning(restoreTaskId: String) {
        if (runningEventSent) return
        runningEventSent = true
        events.emitRunning(restoreTaskId)
    }

    private fun sendTerminal(restoreTaskId: String, summary: MediaRelocationSummary) {
        if (terminalEventSent) return
        terminalEventSent = true
        closeRestoreFlowOnBack = summary.type.shouldCloseRestoreFlowOnBack
        viewModelScope.launch {
            events.emitTerminal(restoreTaskId, summary)
        }
    }

    private fun currentReportOrEmpty(): MediaRelocationReport {
        return when (val state = _uiState.value) {
            is MediaRelocationUiState.NoWork -> state.preparation.toExistingReadableReport()
            is MediaRelocationUiState.PermissionRequired -> state.report ?: state.preparation.toExistingReadableReport()
            is MediaRelocationUiState.Running -> state.progress.report
            is MediaRelocationUiState.Result -> state.report
            MediaRelocationUiState.Idle,
            MediaRelocationUiState.Estimating,
            is MediaRelocationUiState.PermissionChecking,
            is MediaRelocationUiState.ReadyToConfirm,
            is MediaRelocationUiState.Error -> MediaRelocationReport()
        }
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
            is BackupFailure.RemoteFailed -> R.string.base_general_backup_error_remote
            is BackupFailure.FileTooLarge -> R.string.base_general_backup_error_file_too_large
            is BackupFailure.TempFileUnavailable -> R.string.base_general_backup_error_temp_file_unavailable
            is BackupFailure.InsufficientSpace -> R.string.base_general_backup_error_insufficient_space
            else -> R.string.base_general_backup_error_unknown
        }
        return appContext.getString(resId)
    }

    override fun onCleared() {
        val taskId = restoreTaskId
        if (taskId != null && _uiState.value.isRunning && !terminalEventSent) {
            val summary = MediaRelocationSummary(MediaRelocationSummaryType.Interrupted, currentReportOrEmpty())
            events.tryEmitInterrupted(taskId, summary)
        }
        mediaRelocationJob?.cancel()
        super.onCleared()
    }
}
