package com.cla.clip.master.backup

import android.content.Context
import android.net.Uri
import com.cla.clip.base.general.backup.*
import com.cla.clip.base.general.backup.settings.*
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logW
import com.cla.clip.master.backup.local.LocalBackupDirectoryWriter
import com.cla.clip.master.backup.upload.BackupPendingUploadStore
import com.cla.clip.master.backup.upload.BackupUploadCompletion
import com.cla.clip.master.backup.upload.BackupPendingHandoff
import com.cla.clip.master.backup.upload.BackupUploadTargetFingerprint
import com.cla.clip.master.work.BackupAutoScheduler
import com.cla.clip.master.work.BackupTaskGate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 手动与每日共用同包流程；入口只接线，不把网络等待放进本地生成阶段。 */
@Singleton
class BackupExecutionCoordinator @Inject constructor(
    /** 应用上下文，仅操作系统文件与任务队列。 */
    @param:ApplicationContext private val context: Context,
    /** 现有流式导出与协议实现。 */
    private val repository: BackupRepository,
    /** 同一份已提交目标策略。 */
    private val settingsRepository: BackupSettingsRepository,
    /** 分目标结果与待上传文件身份。 */
    private val runtime: BackupRuntimeRepository,
    /** 正式本地目录发布和保留管理。 */
    private val localWriter: LocalBackupDirectoryWriter,
    /** 私有包存储，不受导出缓存清理影响。 */
    private val pendingStore: BackupPendingUploadStore,
    /** 仅用于清理生成阶段缓存。 */
    private val temporaryStore: BackupTempFileStore,
    /** 既有远端校验发布能力。 */
    private val webDavClient: WebDavClient,
) {
    /** 复用可独立验证的终态提交与文件释放规则，调用始终在任务锁内。 */
    private val completion = BackupUploadCompletion(
        read = { runtime.snapshot().pendingUpload },
        commit = { artifactId, completedAt, reasonCode -> runtime.finishPending(artifactId, completedAt, reasonCode) },
        remove = { artifactId -> pendingStore.remove(artifactId) },
        clear = { artifactId -> runtime.clearPending(artifactId) },
    )

    /** 本地发布与结果写盘使用可回归的分阶段契约，不把存储故障写成备份失败。 */
    private val localCompletion = BackupPublicationCompletion { _, failure ->
        runtime.recordTargetResult(BackupResultTarget.Local, System.currentTimeMillis(), failure?.backupReasonCode().orEmpty())
    }

    /** 生成一份最新快照，先尝试本地发布，再交接同一包；全程只持有一次任务锁。 */
    suspend fun generate(automatic: Boolean) = BackupTaskGate.runExclusive {
        withContext(Dispatchers.IO) {
            /** 生成时只读设置，不提前解密凭据，确保本地阶段独立。 */
            val settings = settingsRepository.snapshot()
            if (automatic && !settings.autoBackupEnabled) return@withContext
            /** 空目录不计为正式本地目标。 */
            val hasLocal = settings.localBackupDirUri.isNotBlank()
            /** 已配置地址才计划远端上传，认证留给上传阶段。 */
            val hasRemote = settings.webDavEndpoint.isNotBlank()
            if (!hasLocal && !hasRemote) return@withContext
            /** 文件身份与诊断关联，不是业务数据版本。 */
            val taskId = newBackupTaskId(if (automatic) "daily" else "manual-webdav")
            logI("BackupCoordinator") { "开始生成 taskId=$taskId source=${if (automatic) "auto" else "manual"} hasLocal=$hasLocal hasRemote=$hasRemote" }
            /** 同一份 ZIP 供本地与远端使用。 */
            val export = try {
                repository.createSnapshot(
                    source = if (hasRemote) { if (automatic) BackupSource.WebDavAuto else BackupSource.WebDavManual }
                        else BackupSource.LocalAuto,
                    backupKind = if (automatic) BackupKind.Auto else BackupKind.Manual, taskId = taskId)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                if (hasLocal) runtime.recordTargetResult(BackupResultTarget.Local, System.currentTimeMillis(), failure.backupReasonCode())
                if (hasRemote) runtime.recordTargetResult(BackupResultTarget.WebDav, System.currentTimeMillis(), failure.backupReasonCode())
                logE("BackupCoordinator") { "生成失败 taskId=$taskId reasonCode=${failure.backupReasonCode()}" }
                throw failure
            }
            /** 本地结果写盘故障延后报告，仍尝试同包远端交接。 */
            var resultStoreFailure: BackupResultPersistenceFailure? = null
            try {
                if (hasLocal) {
                    /** 完整发布才允许保留清理，不依赖状态写盘是否成功。 */
                    var localPublished = false
                    try {
                        localCompletion.publish {
                            localWriter.writeExport(Uri.parse(settings.localBackupDirUri), export)
                            localPublished = true
                            logI("BackupCoordinator") { "本地发布成功 taskId=$taskId fileSize=${export.fileSize}" }
                        }
                    } catch (failure: Throwable) {
                        if (failure is CancellationException) throw failure
                        if (failure is BackupResultPersistenceFailure) {
                            resultStoreFailure = failure
                            logE("BackupCoordinator") { "本地结果保存失败 taskId=$taskId reasonCode=result_store_failed published=$localPublished" }
                        } else {
                            logE("BackupCoordinator") { "本地发布失败 taskId=$taskId reasonCode=${failure.backupReasonCode()}" }
                        }
                    }
                    // 保留清理失败不能反转已经完整发布的成功结果。
                    if (localPublished) {
                        try { localWriter.pruneBackups(Uri.parse(settings.localBackupDirUri), settings.backupRetentionCount, taskId) }
                        catch (failure: Throwable) {
                            if (failure is CancellationException) throw failure
                            logW("BackupCoordinator") { "本地保留清理失败 taskId=$taskId reasonCode=${failure.backupReasonCode()}" }
                        }
                    }
                }
                if (hasRemote) {
                    try {
                        /** 旧等待包仅在新包完整生成并成功交接后才释放。 */
                        val previous = runtime.snapshot().pendingUpload
                        pendingStore.prepare(taskId, export)
                        /** 当前指针在单份 DataStore 事务中替换。 */
                        val pending = BackupPendingUpload(taskId, BackupUploadTargetFingerprint.of(settings),
                            if (automatic) "auto" else "manual", "ready", export.manifest.createdAt)
                        /** 入队失败回滚仍有效的旧等待身份，旧包只在新登记成功后释放。 */
                        val handoff = BackupPendingHandoff(
                            replace = { replacement -> runtime.replacePending(replacement) },
                            enqueue = { fresh -> schedule(fresh, settings.autoBackupOnlyWifi) },
                            release = { old ->
                                try { discard(old.artifactId) } catch (failure: Throwable) {
                                    if (failure is CancellationException) throw failure
                                    logW("BackupCoordinator") { "旧等待包清理失败 taskId=${old.artifactId} reasonCode=${failure.backupReasonCode()}" }
                                }
                            },
                            discard = { fresh -> discard(fresh.artifactId) },
                        )
                        handoff.accept(pending, previous)
                    } catch (failure: Throwable) {
                        if (failure is CancellationException || failure is BackupResultPersistenceFailure) throw failure
                        // 准备失败保留旧包；入队失败由 schedule 原子结束并清理当前包。
                        /** 指针已切换的交接失败必须先结束新包，不能在下次启动重新上传。 */
                        val current = runtime.snapshot().pendingUpload
                        if (current?.artifactId == taskId) {
                            if (!current.terminal) runtime.finishPending(taskId, System.currentTimeMillis(), "handoff_failed")
                            cleanupTerminal(taskId)
                        } else if (failure !is BackupUploadScheduleFailure) {
                            runtime.recordTargetResult(BackupResultTarget.WebDav, System.currentTimeMillis(), failure.backupReasonCode())
                        }
                        logE("BackupCoordinator") { "同包交接失败 taskId=$taskId reasonCode=${failure.backupReasonCode()}" }
                        throw failure
                    }
                }
                logI("BackupCoordinator") { "生成阶段结束 taskId=$taskId hasLocal=$hasLocal hasRemote=$hasRemote fileSize=${export.fileSize}" }
                resultStoreFailure?.let { throw it }
            } finally {
                // 待上传文件已复制到独立目录，绝不通过生成缓存 finally 删除。
                temporaryStore.cleanupTaskDir(export.taskDir, taskId)
            }
        }
    }

    /** 手动系统文件导出只更新本地结果，取消选择器不会调用此方法。 */
    suspend fun exportLocal(uri: Uri) = BackupTaskGate.runExclusive {
        withContext(Dispatchers.IO) {
            /** 本次本地导出的诊断身份。 */
            val taskId = newBackupTaskId("manual-local")
            logI("BackupCoordinator") { "开始手动文件导出 taskId=$taskId" }
            localCompletion.publish {
                /** 导出协议与自动生成完全复用，不确认任何保存版本。 */
                val export = repository.createSnapshot(BackupSource.LocalManual, taskId = taskId)
                try {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        export.packageFile.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw BackupFailure.StorageNotWritable()
                    logI("BackupCoordinator") { "手动文件导出成功 taskId=$taskId fileSize=${export.fileSize}" }
                } finally { temporaryStore.cleanupTaskDir(export.taskDir, taskId) }
            }
        }
    }

    /** 上传指定文件身份，读取执行时匹配目标的凭据；不生成新的快照。 */
    suspend fun upload(artifactId: String) = BackupTaskGate.runExclusive {
        withContext(Dispatchers.IO) {
            /** 当前唯一包，不匹配的旧任务静默退出。 */
            val pending = runtime.snapshot().pendingUpload ?: return@withContext
            if (pending.artifactId != artifactId || pending.terminal) return@withContext
            if (!BackupSchedulingPolicy.canSchedule(pending.phase)) {
                if (pending.phase == "uploading") cancelUncertain(pending)
                else completion.finish(artifactId, System.currentTimeMillis(), "upload_interrupted")
                return@withContext
            }
            try {
                // 在配置和网络读取前结束等待阶段；配置读取失败也不能留作下次启动重传。
                runtime.updatePendingPhase(artifactId, "uploading")
                /** 上传前同份已提交设置；自动关闭或目标变更只取消，不误报备份失败。 */
                val settings = settingsRepository.snapshot()
                if ((pending.source == "auto" && !settings.autoBackupEnabled) || settings.webDavEndpoint.isBlank() ||
                    pending.targetFingerprint != BackupUploadTargetFingerprint.of(settings)) {
                    cancel(pending)
                    return@withContext
                }
                /** 同一磁盘快照提供目的地与当前匹配密码。 */
                val connection = settingsRepository.loadWebDavConnection()
                if ((pending.source == "auto" && !connection.settings.autoBackupEnabled) ||
                    pending.targetFingerprint != BackupUploadTargetFingerprint.of(connection.settings)) {
                    cancel(pending)
                    return@withContext
                }
                /** 网络配置只存内存，不进入 WorkData 或私有 manifest。 */
                val config = WebDavConfig(connection.settings.webDavEndpoint, connection.settings.webDavUsername,
                    connection.password.orEmpty(), normalizeWebDavRemoteDir(connection.settings.webDavRemoteDir), connection.settings.webDavAllowInsecureHttp)
                /** 从等待目录取回生成时原始字节，缺失即失败。 */
                val export = pendingStore.read(artifactId)
                /** 文件清理后 File.length 会归零，因此提前保留成功日志需要的大小。 */
                val fileSize = export.fileSize
                webDavClient.uploadBackup(config, export, artifactId)
                completion.finish(artifactId, System.currentTimeMillis())
                logI("BackupCoordinator") { "同包上传成功 taskId=$artifactId fileSize=$fileSize" }
                try { webDavClient.pruneBackups(config, connection.settings.backupRetentionCount, artifactId) }
                catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    logW("BackupCoordinator") { "远端保留清理失败 taskId=$artifactId reasonCode=${failure.backupReasonCode()}" }
                }
            } catch (failure: Throwable) {
                if (failure is BackupResultPersistenceFailure) {
                    // 不重传、不把已发布成功改写为失败；尽力保存仅清理的终态。
                    withContext(NonCancellable) {
                        try { runtime.updatePendingPhase(artifactId, "result_store_failed") }
                        catch (storeFailure: Throwable) {
                            logW("BackupCoordinator") { "结果未保存终态登记失败 taskId=$artifactId reasonCode=result_store_failed type=${storeFailure::class.simpleName}" }
                        }
                    }
                    logE("BackupCoordinator") { "远端结果保存失败 taskId=$artifactId reasonCode=result_store_failed published=${failure.publicationSucceeded}" }
                    throw failure
                }
                // 取消与真实失败均先结束身份；网络约束导致的中断不请求 WorkManager 重试。
                withContext(NonCancellable) {
                    if (runtime.snapshot().pendingUpload?.terminal != true) {
                        completion.finish(artifactId, System.currentTimeMillis(),
                            if (failure is CancellationException) "upload_interrupted" else failure.backupReasonCode())
                    }
                    cleanupTerminal(artifactId)
                }
                logE("BackupCoordinator") { "同包上传结束 taskId=$artifactId reasonCode=${failure.backupReasonCode()}" }
                throw failure
            }
        }
    }

    /** 启动或配置变化时修复尚未入队包、取消失效目标并清除孤儿；不会重新生成备份。 */
    suspend fun reconcile(refreshConstraints: Boolean = false) = BackupTaskGate.runExclusive {
        withContext(Dispatchers.IO) {
            /** 真实文件所有权快照，读取失败不能当作无包。 */
            val pending = runtime.snapshot().pendingUpload
            if (pending == null) { pendingStore.cleanupExcept(null); return@withContext }
            /** 当前目标及开关；手动包不受自动开关影响。 */
            val settings = settingsRepository.snapshot()
            when {
                pending.terminal -> cleanupTerminal(pending.artifactId)
                (pending.source == "auto" && !settings.autoBackupEnabled) || settings.webDavEndpoint.isBlank() ||
                    pending.targetFingerprint != BackupUploadTargetFingerprint.of(settings) -> cancel(pending)
                BackupSchedulingPolicy.canSchedule(pending.phase) -> schedule(pending, settings.autoBackupOnlyWifi, refreshConstraints)
                pending.phase == "uploading" -> cancelUncertain(pending)
                else -> {
                    completion.finish(pending.artifactId, System.currentTimeMillis(), "upload_interrupted")
                }
            }
            pendingStore.cleanupExcept(runtime.snapshot().pendingUpload?.artifactId)
        }
    }

    /** 调度开始先标记，明确入队失败原子终止；只有 ready 的崩溃交接窗口允许修复。 */
    private suspend fun schedule(pending: BackupPendingUpload, onlyWifi: Boolean, refreshConstraints: Boolean = false) {
        runtime.updatePendingPhase(pending.artifactId, "enqueuing")
        try {
            BackupAutoScheduler.enqueueUpload(context, pending, onlyWifi, refreshConstraints)
            runtime.updatePendingPhase(pending.artifactId, "queued")
            logI("BackupCoordinator") { "同包上传已入队 taskId=${pending.artifactId} source=${pending.source} onlyWifi=$onlyWifi" }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            completion.finish(pending.artifactId, System.currentTimeMillis(), "schedule_failed")
            throw BackupUploadScheduleFailure(failure)
        }
    }

    /** 取消等待包只改变身份，不覆盖历史完成结果。 */
    private suspend fun cancel(pending: BackupPendingUpload) {
        runtime.updatePendingPhase(pending.artifactId, "cancelled")
        BackupAutoScheduler.cancelUpload(context, pending.artifactId)
        cleanupTerminal(pending.artifactId)
    }

    /** 中断上传可能已经发布但未保存结果，不能根据未知结论伪造失败或重新上传。 */
    private suspend fun cancelUncertain(pending: BackupPendingUpload) {
        logW("BackupCoordinator") { "中断上传结论未知 taskId=${pending.artifactId} reasonCode=result_store_unknown" }
        cancel(pending)
    }

    /** 在任务锁内取消并删除非当前包；清理只释放该身份，不影响恢复的旧引用。 */
    private suspend fun discard(artifactId: String) {
        BackupAutoScheduler.cancelUpload(context, artifactId)
        pendingStore.remove(artifactId)
        runtime.clearPending(artifactId)
    }

    /** 删除前再次检查身份与终态；文件删除失败保留终态供下次仅清理。 */
    private suspend fun cleanupTerminal(artifactId: String) {
        completion.cleanup(artifactId)
    }
}

/** 入队失败已提交终态，不应被外层交接错误覆盖原始稳定原因。 */
private class BackupUploadScheduleFailure(cause: Throwable) : Exception("schedule_failed", cause)
