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
import androidx.work.workDataOf
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
import com.cla.clip.base.general.backup.settings.BackupRuntimeRepository
import com.cla.clip.base.general.backup.settings.BackupSettingsRepository
import com.cla.clip.base.general.backup.settings.BackupSettingsSnapshot
import com.cla.clip.base.general.backup.settings.WebDavConnectionSnapshot
import com.cla.clip.base.general.backup.settings.WebDavCredentialUnavailable
import com.cla.clip.base.general.backup.logCode
import com.cla.clip.base.general.backup.toLogFields
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import com.cla.clip.master.ui.page.backup.LocalBackupDirectoryWriter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * 自动备份 Worker。
 *
 * Worker 独立于备份页运行，按已提交的 DataStore 快照决定是否执行、跳过或退避重试；它只更新本机备份状态，
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
    /** 本机目标、保留策略及自动开关的唯一持久化入口。 */
    private val backupSettingsRepository: BackupSettingsRepository,
    /** 备份任务结果与待备份版本的持久化入口。 */
    private val backupRuntimeRepository: BackupRuntimeRepository,
) : CoroutineWorker(appContext, params) {

    companion object {
        /** 日志标签，仅记录状态和数量，不输出用户内容或完整配置。 */
        private const val TAG = "BackupAutoWorker"
        /** 周期任务即使没有 dirty 也做一次全量兜底，覆盖跨库标记中断窗口。 */
        internal const val INPUT_FORCE_FULL_BACKUP = "force_full_backup"
    }

    /** 执行一次自动备份；失败类型决定是 WorkManager 重试还是直接停止。 */
    override suspend fun doWork(): Result {
        /** 当前任务的已提交目标配置；读取失败交给 WorkManager 重试，不以默认值覆盖。 */
        val initialSettings = try {
            backupSettingsRepository.snapshot()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            logE(TAG) { "自动备份配置读取失败 reasonCode=settings_store_failed type=${throwable::class.simpleName}" }
            return Result.retry()
        }
        /** 当前待备份版本；导出前再次捕获，以便只确认本次包含的变化。 */
        val runtime = try {
            backupRuntimeRepository.snapshot()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            logE(TAG) { "自动备份状态读取失败 reasonCode=runtime_store_failed type=${throwable::class.simpleName}" }
            return Result.retry()
        }
        val taskId = com.cla.clip.base.general.backup.newBackupTaskId("auto")
        val startedAt = System.currentTimeMillis()
        if (!initialSettings.autoBackupEnabled) {
            return recordSkipped(taskId, R.string.base_general_backup_auto_skip_disabled, "auto_disabled")
        }
        /** 同一次读取取得远端目标和认证信息，避免并发配置更改时错配凭据。 */
        val connection = try {
            backupSettingsRepository.loadWebDavConnection()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            /** 设备密钥不可用需要用户重新输入，普通存储故障仍按可重试错误处理。 */
            val failure = if (throwable is WebDavCredentialUnavailable) BackupFailure.AuthenticationFailed() else throwable
            return handleFailure(taskId, failure, startedAt)
        }
        /** 整个任务使用与密码匹配的同一份目标配置。 */
        val settings = connection.settings
        if (!settings.autoBackupEnabled) {
            return recordSkipped(taskId, R.string.base_general_backup_auto_skip_disabled, "auto_disabled")
        }
        if (!runtime.dirty && !inputData.getBoolean(INPUT_FORCE_FULL_BACKUP, false)) {
            return recordSkipped(taskId, R.string.base_general_backup_auto_skip_clean, "dirty_false")
        }

        /** 已获系统授权的本机目录，空值表示此目标未配置。 */
        val localDir = settings.localBackupDirUri.takeIf { it.isNotBlank() }
        /** 已配置的远端始终计入目标，不能因健康缓存未知而错误确认全部备份成功。 */
        val webDavConfig = try {
            currentWebDavConfigOrNull(connection)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            /** 无法解密已有密码属于待用户修复的认证失败，不能无限后台重试。 */
            val failure = if (throwable is WebDavCredentialUnavailable) BackupFailure.AuthenticationFailed() else throwable
            return handleFailure(taskId, failure, startedAt)
        }
        if (localDir == null && webDavConfig == null) {
            return recordSkipped(taskId, R.string.base_general_backup_auto_skip_no_target, "no_available_target")
        }

        return BackupTaskGate.runExclusive {
            try {
                backupRuntimeRepository.recordStatus(BackupTaskStatus.Running)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                logW(TAG) { "自动备份运行状态提交失败 taskId=$taskId reasonCode=runtime_store_failed type=${failure::class.simpleName}" }
                return@runExclusive Result.retry()
            }
            /** 导出前的已提交版本，后续并发变化不能由这份快照确认。 */
            val exportedRevision = try {
                backupRuntimeRepository.captureRevision()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                logW(TAG) { "自动备份版本捕获失败 taskId=$taskId reasonCode=runtime_store_failed type=${failure::class.simpleName}" }
                return@runExclusive Result.retry()
            }
            logI(TAG) {
                "开始自动备份 taskId=$taskId operation=auto_backup hasLocalTarget=${localDir != null} " +
                    "hasWebDavTarget=${webDavConfig != null} retention=${settings.backupRetentionCount} " +
                    "onlyWifi=${settings.autoBackupOnlyWifi}"
            }

            val source = if (webDavConfig != null) BackupSource.WebDavAuto else BackupSource.LocalAuto
            val export = try {
                backupRepository.createSnapshot(source = source, backupKind = BackupKind.Auto, taskId = taskId)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
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
                val retention = settings.backupRetentionCount

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
                        if (throwable is CancellationException) throw throwable
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
                        if (throwable is CancellationException) throw throwable
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
                    exportedRevision = exportedRevision,
                    startedAt = startedAt
                )
            } finally {
                tempFileStore.cleanupTaskDir(export.taskDir, taskId)
            }
        }
    }

    /** 根据当前 WebDAV 配置构造目标；配置不完整时返回 null，让本地自动备份仍可运行。 */
    private fun currentWebDavConfigOrNull(connection: WebDavConnectionSnapshot): WebDavConfig? {
        /** 与明文密码同一次读取的远端设置。 */
        val settings = connection.settings
        /** 已提交的远端根地址；空值表示无需远端目标。 */
        val endpoint = settings.webDavEndpoint
        if (endpoint.isBlank()) return null
        return WebDavConfig(
            endpoint = endpoint,
            username = settings.webDavUsername,
            password = connection.password.orEmpty(),
            remoteDir = normalizeWebDavRemoteDir(settings.webDavRemoteDir),
            allowInsecureHttp = settings.webDavAllowInsecureHttp,
        )
    }

    /** 记录跳过状态；跳过不是失败，不触发 WorkManager 重试。 */
    private suspend fun recordSkipped(taskId: String, reasonResId: Int, reasonCode: String): Result {
        /** 仅用于本机展示的脱敏跳过说明。 */
        val reason = applicationContext.getString(reasonResId)
        try {
            backupRuntimeRepository.recordStatus(BackupTaskStatus.Skipped, skipReason = reason)
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            logW(TAG) { "自动备份跳过状态提交失败 taskId=$taskId reasonCode=runtime_store_failed type=${failure::class.simpleName}" }
            return Result.retry()
        }
        logD(TAG) { "自动备份已跳过 taskId=$taskId reasonCode=$reasonCode" }
        return Result.success()
    }

    /** 根据失败是否可能自动恢复决定重试或结束。 */
    private suspend fun handleFailure(taskId: String, throwable: Throwable, startedAt: Long): Result {
        /** 可由 WorkManager 重试的临时失败，不包含明确的配置或校验失败。 */
        val retryable = throwable is BackupFailure.RemoteFailed || throwable !is BackupFailure
        /** 本次失败后展示的稳定任务状态。 */
        val status = if (retryable) BackupTaskStatus.RetryScheduled else BackupTaskStatus.Failed
        try {
            backupRuntimeRepository.recordStatus(status, failureReason = throwable.toBackupFailureReason())
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            logW(TAG) { "自动备份失败状态提交失败 taskId=$taskId reasonCode=runtime_store_failed type=${failure::class.simpleName}" }
            return Result.retry()
        }
        val durationMs = System.currentTimeMillis() - startedAt
        val reasonCode = throwable.backupReasonCode()
        if (retryable) {
            logW(TAG) {
                "自动备份失败，已安排重试 taskId=$taskId status=$status reasonCode=$reasonCode durationMs=$durationMs"
            }
        } else {
            logE(TAG) {
                "自动备份失败 taskId=$taskId status=$status reasonCode=$reasonCode durationMs=$durationMs"
            }
        }
        return if (retryable) Result.retry() else Result.failure()
    }

    /** 汇总本地和 WebDAV 目标结果，保留部分成功状态并只在完全失败时进入重试/失败。 */
    private suspend fun finishTargetResults(
        taskId: String,
        exportSummary: BackupSuccessSummary,
        failures: List<Throwable>,
        localSuccess: Boolean,
        webDavSuccess: Boolean,
        localTargetConfigured: Boolean,
        webDavTargetConfigured: Boolean,
        exportedRevision: Long,
        startedAt: Long,
    ): Result {
        val anySuccess = localSuccess || webDavSuccess
        if (anySuccess) {
            val allConfiguredTargetsSucceeded = (!localTargetConfigured || localSuccess) && (!webDavTargetConfigured || webDavSuccess)
            val hasRetryableFailure = failures.any { failure ->
                failure is BackupFailure.RemoteFailed || failure !is BackupFailure
            }
            /** 完成后的状态；部分成功仍保留待备份版本。 */
            val status = if (allConfiguredTargetsSucceeded) BackupTaskStatus.Success else BackupTaskStatus.PartialSuccess
            try {
                backupRuntimeRepository.recordSuccess(
                    status = status,
                    summary = exportSummary,
                    failureReason = failures.firstOrNull()?.toBackupFailureReason().orEmpty(),
                    acknowledgedRevision = exportedRevision.takeIf { allConfiguredTargetsSucceeded },
                )
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                logW(TAG) { "自动备份目标已写入但状态提交失败 taskId=$taskId reasonCode=runtime_store_failed type=${failure::class.simpleName}" }
                return Result.retry()
            }
            /** 重新读取最终版本；读取失败只影响诊断，不把已写入目标误报为失败。 */
            val dirty = try {
                backupRuntimeRepository.snapshot().dirty
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                logW(TAG) { "自动备份状态复核失败 taskId=$taskId reasonCode=runtime_snapshot_failed type=${failure::class.simpleName}" }
                true
            }
            val durationMs = System.currentTimeMillis() - startedAt
            val firstReasonCode = failures.firstOrNull()?.backupReasonCode().orEmpty()
            val message = "自动备份结束 taskId=$taskId status=$status " +
                "localSuccess=$localSuccess webDavSuccess=$webDavSuccess localDeleted=${exportSummary.localRetentionDeleted} " +
                "webDavDeleted=${exportSummary.webDavRetentionDeleted} dirty=$dirty " +
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

    /** 从 Hilt 获取本机设置域仓库，调度器不保存跨进程可过期的镜像。 */
    private fun repositories(context: Context): BackupSchedulingEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, BackupSchedulingEntryPoint::class.java)

    /** 注册或取消每日自动备份任务。 */
    suspend fun reschedule(context: Context) {
        /** 已提交的目标和策略设置。 */
        val settings = repositories(context).backupSettingsRepository().snapshot()
        val workManager = WorkManager.getInstance(context)
        if (!settings.autoBackupEnabled) {
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_NAME)
            workManager.cancelUniqueWork(UNIQUE_DIRTY_NAME)
            logD(TAG) { "自动备份调度已取消 reasonCode=auto_disabled" }
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupAutoWorker>(24, TimeUnit.HOURS)
            .setConstraints(buildConstraints(settings))
            .setInputData(workDataOf(BackupAutoWorker.INPUT_FORCE_FULL_BACKUP to true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        logD(TAG) {
            "自动备份周期任务已更新 needsNetwork=${settings.webDavEndpoint.isNotBlank()} " +
                "onlyWifi=${settings.autoBackupOnlyWifi}"
        }
    }

    /** 标记数据已变化，并按延迟窗口入队一次性自动备份。 */
    suspend fun markDirtyAndSchedule(context: Context) {
        /** 业务数据已成功写入后的版本递增，失败时不得继续调度。 */
        repositories(context).backupRuntimeRepository().markChanged()
        /** 以最新已提交配置决定是否入队。 */
        val settings = repositories(context).backupSettingsRepository().snapshot()
        logD(TAG) { "备份待同步版本已递增 delayMinutes=$DIRTY_DELAY_MINUTES autoEnabled=${settings.autoBackupEnabled}" }
        scheduleDirtyBackup(context)
    }

    /** 业务已提交且版本已记录时只尝试调度；失败返回 false，不能把成功写入误报为失败。 */
    suspend fun scheduleAfterCommittedChange(context: Context): Boolean {
        return try {
            scheduleDirtyBackup(context)
            true
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            logW(TAG) { "已提交业务变更但自动备份调度失败 reasonCode=schedule_failed type=${failure::class.simpleName}" }
            false
        }
    }

    /** 立即排队一次自动备份，通常用于用户刚开启自动备份后生成首个恢复点。 */
    suspend fun enqueueNow(context: Context) {
        /** 用最新提交的开关判断是否立即排队。 */
        val settings = repositories(context).backupSettingsRepository().snapshot()
        logD(TAG) { "自动备份立即入队 autoEnabled=${settings.autoBackupEnabled}" }
        scheduleDirtyBackup(context, delayMinutes = 0)
    }

    /** 入队 dirty 一次性备份；未开启自动备份时只保留 dirty 标记。 */
    private suspend fun scheduleDirtyBackup(context: Context, delayMinutes: Long = DIRTY_DELAY_MINUTES) {
        /** 一次性任务的最新目标配置。 */
        val settings = repositories(context).backupSettingsRepository().snapshot()
        if (!settings.autoBackupEnabled) {
            logD(TAG) { "待同步自动备份任务已跳过 reasonCode=auto_disabled delayMinutes=$delayMinutes" }
            return
        }
        val request = OneTimeWorkRequestBuilder<BackupAutoWorker>()
            .setConstraints(buildConstraints(settings))
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_DIRTY_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
        logD(TAG) {
            "待同步自动备份任务已入队 delayMinutes=$delayMinutes needsNetwork=${settings.webDavEndpoint.isNotBlank()} " +
                "onlyWifi=${settings.autoBackupOnlyWifi}"
        }
    }

    /** 根据当前设置构建 WorkManager 约束；只要配置了 WebDAV 就要求网络。 */
    private fun buildConstraints(settings: BackupSettingsSnapshot): Constraints {
        /** 远端健康缓存不影响目标数量，避免 unknown 时错误确认备份版本。 */
        val needsNetwork = settings.webDavEndpoint.isNotBlank()
        /** 根据用户 Wi-Fi 策略选择 WorkManager 网络类型。 */
        val networkType = when {
            !needsNetwork -> NetworkType.NOT_REQUIRED
            settings.autoBackupOnlyWifi -> NetworkType.UNMETERED
            else -> NetworkType.CONNECTED
        }
        return Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()
    }
}

/** 对象式调度入口读取 Hilt 单例仓库，不自行创建第二份 DataStore。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackupSchedulingEntryPoint {
    /** 本机目标设置仓库。 */
    fun backupSettingsRepository(): BackupSettingsRepository
    /** 待备份版本与运行状态仓库。 */
    fun backupRuntimeRepository(): BackupRuntimeRepository
}
