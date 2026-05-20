package com.cla.clip.master.work

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cla.clip.base.general.backup.BackupFailure
import com.cla.clip.base.general.backup.BackupKind
import com.cla.clip.base.general.backup.BackupSource
import com.cla.clip.base.general.backup.BackupSuccessSummary
import com.cla.clip.base.general.backup.BackupTaskStatus
import com.cla.clip.base.general.backup.BackupTempFileStore
import com.cla.clip.base.general.backup.WebDavClient
import com.cla.clip.base.general.backup.WebDavConfig
import com.cla.clip.base.general.backup.normalizeWebDavRemoteDir
import com.cla.clip.base.general.backup.BackupRepository
import com.cla.clip.base.general.R
import com.cla.clip.base.general.backup.BackupTargetHealth
import com.cla.clip.base.general.backup.backupReasonCode
import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.base.general.backup.logCode
import com.cla.clip.base.general.backup.toLogFields
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import com.cla.clip.master.ui.page.backup.LocalBackupDirectoryWriter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * 自动备份 Worker。
 *
 * Worker 独立于备份页运行，按当前 `AppSetting` 快照决定是否执行、跳过或退避重试；它只更新本机备份状态，
 * 不弹窗、不展示 Toast，避免后台任务打扰用户。真正的文件内容仍由 `BackupRepository` 统一生成，确保手动和自动备份格式一致。
 */
@HiltWorker
class BackupAutoWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    /** 统一备份仓库，负责生成 zip 快照和 manifest。 */
    private val backupRepository: BackupRepository,
    /** 本地目录写入器，负责 SAF 授权目录下的自动备份写入和保留清理。 */
    private val localBackupDirectoryWriter: LocalBackupDirectoryWriter,
    /** WebDAV 客户端，负责远端上传和保留清理。 */
    private val webDavClient: WebDavClient,
    /** 临时文件目录管理器，自动备份完成后清理导出临时文件。 */
    private val tempFileStore: BackupTempFileStore,
) : CoroutineWorker(appContext, params) {

    companion object {
        /** 日志标签，仅记录状态和数量，不输出用户内容或完整配置。 */
        private const val TAG = "BackupAutoWorker"
    }

    /** 执行一次自动备份；失败类型决定是 WorkManager 重试还是直接停止。 */
    override suspend fun doWork(): Result {
        val taskId = com.cla.clip.base.general.backup.newBackupTaskId("auto")
        val startedAt = System.currentTimeMillis()
        if (!AppSetting.autoBackupEnabled) {
            recordSkipped(taskId, R.string.base_general_backup_auto_skip_disabled, "auto_disabled")
            return Result.success()
        }
        if (!AppSetting.backupDirty) {
            recordSkipped(taskId, R.string.base_general_backup_auto_skip_clean, "dirty_false")
            return Result.success()
        }

        val localDir = AppSetting.localBackupDirUri.takeIf { it.isNotBlank() }
        val webDavConfig = currentWebDavConfigOrNull()
        if (localDir == null && webDavConfig == null) {
            recordSkipped(taskId, R.string.base_general_backup_auto_skip_no_target, "no_available_target")
            return Result.success()
        }

        return BackupTaskGate.runExclusive {
            AppSetting.lastAutoBackupStatus = BackupTaskStatus.Running
            AppSetting.lastAutoBackupFailureReason = ""
            AppSetting.lastAutoBackupSkipReason = ""
            logI(TAG) {
                "开始自动备份 taskId=$taskId operation=auto_backup hasLocalTarget=${localDir != null} " +
                    "hasWebDavTarget=${webDavConfig != null} retention=${AppSetting.backupRetentionCount} " +
                    "onlyWifi=${AppSetting.autoBackupOnlyWifi}"
            }

            val source = if (webDavConfig != null) BackupSource.WebDavAuto else BackupSource.LocalAuto
            val export = try {
                backupRepository.createSnapshot(source = source, backupKind = BackupKind.Auto, taskId = taskId)
            } catch (throwable: Throwable) {
                logE(TAG) {
                    "自动备份快照生成失败 taskId=$taskId reasonCode=${throwable.backupReasonCode()} type=${throwable::class.simpleName}"
                }
                return@runExclusive handleFailure(taskId, throwable, startedAt)
            }
            try {
                logD(TAG) {
                    "自动备份快照已生成 taskId=$taskId source=${export.manifest.source.logCode()} " +
                        "backupKind=${export.manifest.backupKind.logCode()} fileName=${export.fileName} " +
                        "fileSize=${export.fileSize} ${export.manifest.summary.toLogFields()}"
                }

                var localSuccess = false
                var webDavSuccess = false
                var localDeleted = 0
                var webDavDeleted = 0
                val failures = mutableListOf<Throwable>()
                val retention = AppSetting.backupRetentionCount

                if (localDir != null) {
                    runCatching {
                        logD(TAG) { "开始写入自动本地备份 taskId=$taskId fileName=${export.fileName} fileSize=${export.fileSize}" }
                        localBackupDirectoryWriter.writeExport(Uri.parse(localDir), export)
                        localSuccess = true
                        logD(TAG) { "自动本地备份写入成功 taskId=$taskId fileName=${export.fileName}" }
                        localDeleted = localBackupDirectoryWriter
                            .pruneBackups(Uri.parse(localDir), retention, taskId)
                            .deletedCount
                    }.onFailure { throwable ->
                        failures += throwable
                        logE(TAG) {
                            "自动本地备份写入失败 taskId=$taskId reasonCode=${throwable.backupReasonCode()} type=${throwable::class.simpleName}"
                        }
                    }
                }

                if (webDavConfig != null) {
                    runCatching {
                        logD(TAG) { "开始上传自动 WebDAV 备份 taskId=$taskId fileName=${export.fileName} fileSize=${export.fileSize}" }
                        webDavClient.uploadBackup(webDavConfig, export, taskId)
                        webDavSuccess = true
                        logD(TAG) { "自动 WebDAV 备份上传成功 taskId=$taskId fileName=${export.fileName}" }
                        webDavDeleted = webDavClient
                            .pruneBackups(webDavConfig, retention, taskId)
                            .deletedCount
                    }.onFailure { throwable ->
                        failures += throwable
                        logE(TAG) {
                            "自动 WebDAV 备份上传失败 taskId=$taskId reasonCode=${throwable.backupReasonCode()} type=${throwable::class.simpleName}"
                        }
                    }
                }

                finishTargetResults(
                    taskId = taskId,
                    exportSummary = BackupSuccessSummary(
                        createdAt = export.manifest.createdAt,
                        fileName = export.fileName,
                        fileSize = export.fileSize,
                        source = export.manifest.source,
                        backupKind = export.manifest.backupKind,
                        summary = export.manifest.summary,
                        localSuccess = localSuccess,
                        webDavSuccess = webDavSuccess,
                        localRetentionDeleted = localDeleted,
                        webDavRetentionDeleted = webDavDeleted
                    ),
                    failures = failures,
                    localSuccess = localSuccess,
                    webDavSuccess = webDavSuccess,
                    localTargetConfigured = localDir != null,
                    webDavTargetConfigured = webDavConfig != null,
                    startedAt = startedAt
                )
            } finally {
                tempFileStore.cleanupTaskDir(export.taskDir, taskId)
            }
        }
    }

    /** 根据当前 WebDAV 配置构造目标；配置不完整时返回 null，让本地自动备份仍可运行。 */
    private fun currentWebDavConfigOrNull(): WebDavConfig? {
        val endpoint = AppSetting.webDavEndpoint
        if (endpoint.isBlank()) return null
        if (AppSetting.webDavHealth != BackupTargetHealth.Available) return null
        return runCatching {
            WebDavConfig(
                endpoint = endpoint,
                username = AppSetting.webDavUsername,
                password = AppSetting.webDavPassword,
                remoteDir = normalizeWebDavRemoteDir(AppSetting.webDavRemoteDir),
                allowInsecureHttp = AppSetting.webDavAllowInsecureHttp
            )
        }.getOrNull()
    }

    /** 记录跳过状态；跳过不是失败，不触发 WorkManager 重试。 */
    private fun recordSkipped(taskId: String, reasonResId: Int, reasonCode: String) {
        val reason = applicationContext.getString(reasonResId)
        AppSetting.lastAutoBackupStatus = BackupTaskStatus.Skipped
        AppSetting.lastAutoBackupSkipReason = reason
        AppSetting.lastAutoBackupFailureReason = ""
        logD(TAG) { "自动备份已跳过 taskId=$taskId reasonCode=$reasonCode" }
    }

    /** 根据失败是否可能自动恢复决定重试或结束。 */
    private fun handleFailure(taskId: String, throwable: Throwable, startedAt: Long): Result {
        val retryable = throwable is BackupFailure.RemoteFailed || throwable !is BackupFailure
        AppSetting.lastAutoBackupFailureReason = throwable.toBackupFailureReason()
        AppSetting.lastAutoBackupStatus = if (retryable) BackupTaskStatus.RetryScheduled else BackupTaskStatus.Failed
        val durationMs = System.currentTimeMillis() - startedAt
        val reasonCode = throwable.backupReasonCode()
        if (retryable) {
            logW(TAG) {
                "自动备份失败，已安排重试 taskId=$taskId status=${AppSetting.lastAutoBackupStatus} reasonCode=$reasonCode durationMs=$durationMs"
            }
        } else {
            logE(TAG) {
                "自动备份失败 taskId=$taskId status=${AppSetting.lastAutoBackupStatus} reasonCode=$reasonCode durationMs=$durationMs"
            }
        }
        return if (retryable) Result.retry() else Result.failure()
    }

    /** 汇总本地和 WebDAV 目标结果，保留部分成功状态并只在完全失败时进入重试/失败。 */
    private fun finishTargetResults(
        taskId: String,
        exportSummary: BackupSuccessSummary,
        failures: List<Throwable>,
        localSuccess: Boolean,
        webDavSuccess: Boolean,
        localTargetConfigured: Boolean,
        webDavTargetConfigured: Boolean,
        startedAt: Long,
    ): Result {
        val anySuccess = localSuccess || webDavSuccess
        if (anySuccess) {
            val allConfiguredTargetsSucceeded = (!localTargetConfigured || localSuccess) && (!webDavTargetConfigured || webDavSuccess)
            val hasRetryableFailure = failures.any { failure ->
                failure is BackupFailure.RemoteFailed || failure !is BackupFailure
            }
            AppSetting.lastAutoBackupStatus = if (allConfiguredTargetsSucceeded) BackupTaskStatus.Success else BackupTaskStatus.PartialSuccess
            AppSetting.lastAutoBackupSuccessAt = exportSummary.createdAt
            AppSetting.backupDirty = !allConfiguredTargetsSucceeded && hasRetryableFailure
            AppSetting.lastBackupSuccessSummary = exportSummary
            AppSetting.lastAutoBackupFailureReason = failures.firstOrNull()?.toBackupFailureReason().orEmpty()
            val durationMs = System.currentTimeMillis() - startedAt
            val firstReasonCode = failures.firstOrNull()?.backupReasonCode().orEmpty()
            val message = "自动备份结束 taskId=$taskId status=${AppSetting.lastAutoBackupStatus} " +
                "localSuccess=$localSuccess webDavSuccess=$webDavSuccess localDeleted=${exportSummary.localRetentionDeleted} " +
                "webDavDeleted=${exportSummary.webDavRetentionDeleted} dirty=${AppSetting.backupDirty} " +
                "reasonCode=$firstReasonCode durationMs=$durationMs ${exportSummary.summary.toLogFields()}"
            if (allConfiguredTargetsSucceeded) {
                logI(TAG) { message }
            } else {
                logW(TAG) { message }
            }
            return if (!allConfiguredTargetsSucceeded && hasRetryableFailure) Result.retry() else Result.success()
        }
        return handleFailure(taskId, failures.firstOrNull() ?: BackupFailure.StorageNotWritable(), startedAt)
    }

    /** 把异常映射为脱敏、可行动的本机状态文案。 */
    private fun Throwable.toBackupFailureReason(): String {
        val resId = when (this) {
            is BackupFailure.AuthenticationFailed -> R.string.base_general_backup_auto_failure_auth
            is BackupFailure.StorageNotWritable -> R.string.base_general_backup_auto_failure_storage
            is BackupFailure.RemoteFailed -> R.string.base_general_backup_auto_failure_remote
            is BackupFailure.ChecksumMismatch -> R.string.base_general_backup_auto_failure_checksum
            is BackupFailure.ParseFailed -> R.string.base_general_backup_auto_failure_parse
            is BackupFailure.FileTooLarge -> R.string.base_general_backup_error_file_too_large
            else -> R.string.base_general_backup_auto_failure_unknown
        }
        return applicationContext.getString(resId)
    }
}

/**
 * 自动备份调度入口。
 *
 * 所有配置页、数据写入点和 Application 启动都通过这里调度，避免备份页成为后台自动备份的隐式前置条件。
 */
object BackupAutoScheduler {
    /** 调度器日志标签，和 Worker 执行日志分开，便于区分“入队”和“执行”。 */
    private const val TAG = "BackupAutoScheduler"

    /** 每日兜底周期任务唯一名称。 */
    private const val UNIQUE_PERIODIC_NAME = "backup_auto_periodic"

    /** 数据变更后的延迟一次性任务唯一名称。 */
    private const val UNIQUE_DIRTY_NAME = "backup_auto_dirty"

    /** dirty 触发备份延迟，给连续剪贴或批量删除留出合并窗口。 */
    private const val DIRTY_DELAY_MINUTES = 10L

    /** 注册或取消每日自动备份任务。 */
    fun reschedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        if (!AppSetting.autoBackupEnabled) {
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_NAME)
            workManager.cancelUniqueWork(UNIQUE_DIRTY_NAME)
            logD(TAG) { "自动备份调度已取消 reasonCode=auto_disabled" }
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupAutoWorker>(24, TimeUnit.HOURS)
            .setConstraints(buildConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        logD(TAG) {
            "自动备份周期任务已更新 needsNetwork=${AppSetting.webDavEndpoint.isNotBlank() && AppSetting.webDavHealth == BackupTargetHealth.Available} " +
                "onlyWifi=${AppSetting.autoBackupOnlyWifi}"
        }
    }

    /** 标记数据已变化，并按延迟窗口入队一次性自动备份。 */
    fun markDirtyAndSchedule(context: Context) {
        AppSetting.backupDirty = true
        logD(TAG) { "备份待同步状态已标记 dirty=true delayMinutes=$DIRTY_DELAY_MINUTES autoEnabled=${AppSetting.autoBackupEnabled}" }
        scheduleDirtyBackup(context)
    }

    /** 立即排队一次自动备份，通常用于用户刚开启自动备份后生成首个恢复点。 */
    fun enqueueNow(context: Context) {
        logD(TAG) { "自动备份立即入队 autoEnabled=${AppSetting.autoBackupEnabled}" }
        scheduleDirtyBackup(context, delayMinutes = 0)
    }

    /** 入队 dirty 一次性备份；未开启自动备份时只保留 dirty 标记。 */
    private fun scheduleDirtyBackup(context: Context, delayMinutes: Long = DIRTY_DELAY_MINUTES) {
        if (!AppSetting.autoBackupEnabled) {
            logD(TAG) { "待同步自动备份任务已跳过 reasonCode=auto_disabled delayMinutes=$delayMinutes" }
            return
        }
        val request = OneTimeWorkRequestBuilder<BackupAutoWorker>()
            .setConstraints(buildConstraints())
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_DIRTY_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
        logD(TAG) {
            "待同步自动备份任务已入队 delayMinutes=$delayMinutes needsNetwork=${AppSetting.webDavEndpoint.isNotBlank() && AppSetting.webDavHealth == BackupTargetHealth.Available} " +
                "onlyWifi=${AppSetting.autoBackupOnlyWifi}"
        }
    }

    /** 根据当前设置构建 WorkManager 约束；WebDAV 配置存在时需要网络，本地-only 时不强制网络。 */
    private fun buildConstraints(): Constraints {
        val needsNetwork = AppSetting.webDavEndpoint.isNotBlank() && AppSetting.webDavHealth == BackupTargetHealth.Available
        val networkType = when {
            !needsNetwork -> NetworkType.NOT_REQUIRED
            AppSetting.autoBackupOnlyWifi -> NetworkType.UNMETERED
            else -> NetworkType.CONNECTED
        }
        return Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()
    }
}
