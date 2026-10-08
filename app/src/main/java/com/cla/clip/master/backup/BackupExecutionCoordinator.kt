package com.cla.clip.master.backup

import android.content.Context
import android.net.Uri
import com.cla.clip.base.general.backup.*
import com.cla.clip.base.general.backup.settings.*
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logW
import com.cla.clip.master.backup.local.LocalBackupDirectoryWriter
import com.cla.clip.master.backup.upload.BackupPendingUploadStore
import com.cla.clip.master.backup.upload.BackupUploadTargetFingerprint
import com.cla.clip.master.backup.diagnostics.*
import com.cla.clip.master.work.BackupAutoScheduler
import com.cla.clip.master.work.BackupTaskGate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 生成负责完整包和分派；两目标独立消费同包，不持有生成/恢复锁。 */
@Singleton
class BackupExecutionCoordinator @Inject internal constructor(
    /** 系统文件和任务队列所需的应用上下文。 */
    @param:ApplicationContext private val context: Context,
    /** 复用分页导出和恢复一致性边界。 */
    private val repository: BackupRepository,
    /** 已提交设置与实际执行时读取的凭据。 */
    private val settingsRepository: BackupSettingsRepository,
    /** 安装内轮次和分目标结果的事务入口。 */
    private val runtime: BackupRuntimeRepository,
    /** 既有 SAF 正式发布和保留能力。 */
    private val localWriter: LocalBackupDirectoryWriter,
    /** 两目标共用的完整只读包，下一轮统一删除。 */
    private val pendingStore: BackupPendingUploadStore,
    /** 独立导出缓存，不包括恢复预览和当前只读包。 */
    private val temporaryStore: BackupTempFileStore,
    /** 只直接发布正式 ZIP 与独立 manifest。 */
    private val webDavClient: WebDavClient,
    /** 低敏阶段留证，不影响业务结果。 */
    private val diagnostics: BackupDiagnostics,
) {
    companion object {
        /** 只提交短队列操作，不等待目标 IO；可替换边界用于验证两目标分派。 */
        internal suspend fun dispatchTargets(targets: List<BackupResultTarget>, enqueue: suspend (BackupResultTarget) -> Unit,
            recordFailure: suspend (BackupResultTarget, Throwable) -> Unit) {
            /** 延后传播结果写盘错误，确保另一目标仍能登记；不吞掉协程取消。 */
            var persistenceFailure: Throwable? = null
            // index 定位尚未分派的后续目标，target 表示本次短队列操作的目标。
            for ((index, target) in targets.withIndex()) {
                try { enqueue(target) }
                catch (failure: Throwable) {
                    try { recordFailure(target, failure) }
                    catch (storeFailure: Throwable) {
                        if (storeFailure is CancellationException) throw storeFailure
                        if (persistenceFailure == null) persistenceFailure = storeFailure
                    }
                    if (failure is CancellationException) {
                        // 已成功入队的消费者保持独立；只结束尚未安排的目标，仍由条件事务保护新轮次。
                        // remaining 仅枚举当前取消点之后尚未尝试入队的目标。
                        for (remaining in targets.drop(index + 1)) {
                            try { recordFailure(remaining, failure) }
                            catch (storeFailure: Throwable) {
                                if (persistenceFailure == null) persistenceFailure = storeFailure
                            }
                        }
                        throw failure
                    }
                }
            }
            persistenceFailure?.let { throw it }
        }
    }
    /** 系统文件导出保持独立完成契约，不参与后台轮次淘汰。 */
    private val localCompletion = BackupPublicationCompletion { _, failure ->
        runtime.recordTargetResult(BackupResultTarget.Local, System.currentTimeMillis(), failure?.backupReasonCode().orEmpty())
    }

    /** 生成/恢复锁内登记新轮次、删除旧包、生成完整包并独立分派目标。 */
    internal suspend fun generate(automatic: Boolean, diagnostic: BackupDiagnosticTrace? = null) = BackupTaskGate.runExclusive {
        withContext(Dispatchers.IO) {
            /** 来源决定自动开关边界，页面关闭不改变后台任务。 */
            val source = if (automatic) "auto" else "manual"
            /** 同一 Worker 的阶段与轮次串联。 */
            val trace = diagnostic ?: diagnostics.start(source = source)
            /** 本轮固定目标，密码留到实际上传读取。 */
            val settings = settingsRepository.snapshot()
            if (automatic && !settings.autoBackupEnabled) { trace.stage("auto_disabled"); return@withContext }
            /** 两目标仅由实际配置选择。 */
            val hasLocal = settings.localBackupDirUri.isNotBlank()
            val hasRemote = settings.webDavEndpoint.isNotBlank()
            if (!hasLocal && !hasRemote) { trace.stage("no_targets"); return@withContext }
            /** UUID 保证进程重建与密集触发也不会共用文件身份。 */
            val roundId = UUID.randomUUID().toString()
            /** 未选择目标明确终止，不生成虚假的失败结果。 */
            val pending = BackupPendingUpload(roundId,
                if (hasRemote) BackupUploadTargetFingerprint.of(settings) else "", source,
                if (hasRemote) "generating" else "unselected", System.currentTimeMillis(),
                if (hasLocal) BackupUploadTargetFingerprint.local(settings) else "",
                if (hasLocal) "generating" else "unselected")
            trace.bind(roundId, source)
            // 身份事务失败不清理旧文件；成功后旧任务即失去结果提交权。
            runtime.replacePending(pending)
            trace.stage("round_started")
            try {
                try { BackupAutoScheduler.cancelTargets(context) }
                catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    logW("BackupCoordinator") { "取消旧任务失败 reasonCode=cancel_failed type=${failure::class.simpleName}" }
                }
                try { pendingStore.cleanupExcept(null) }
                catch (failure: Throwable) {
                    logW("BackupCoordinator") { "旧内部包清理失败 reasonCode=package_cleanup_failed type=${failure::class.simpleName}" }
                }
                try { temporaryStore.cleanupBackgroundExports() }
                catch (failure: Throwable) {
                    logW("BackupCoordinator") { "旧生成中间文件清理失败 reasonCode=export_cleanup_failed type=${failure::class.simpleName}" }
                }
                trace.stage("creating_snapshot")
                /** 完整复制成功后才将两个目标推进为 ready。 */
                val export = repository.createSnapshot(
                    source = if (hasRemote) { if (automatic) BackupSource.WebDavAuto else BackupSource.WebDavManual } else BackupSource.LocalAuto,
                    backupKind = if (automatic) BackupKind.Auto else BackupKind.Manual, taskId = roundId)
                try { pendingStore.prepare(roundId, export) }
                finally { temporaryStore.cleanupTaskDir(export.taskDir, roundId) }
                runtime.markRoundReady(roundId)
                trace.stage("package_ready")
            } catch (failure: Throwable) {
                /** 中断也结束选中目标，禁止系统重调度消费半包。 */
                val reason = if (failure is CancellationException) "generation_interrupted" else failure.backupReasonCode()
                withContext(NonCancellable) {
                    // target 仅枚举本轮实际选择的目标，不修改未配置目标的历史结果。
                    for (target in selectedTargets(hasLocal, hasRemote)) commit(roundId, target, reason)
                    try { pendingStore.remove(roundId) } catch (_: Exception) { trace.event("package_cleanup_failed") }
                }
                trace.event("generation_failed", errorType = failure.javaClass.simpleName, reasonCode = reason)
                throw failure
            }
            // 独立提交短入队，不等待消费 IO；单目标失败不阻止另一个入队。
            dispatchTargets(selectedTargets(hasLocal, hasRemote), enqueue = { target ->
                    runtime.updateTargetPhase(roundId, target, "queued")
                    trace.stage(if (target == BackupResultTarget.Local) "enqueueing_local" else "enqueueing_upload")
                    BackupAutoScheduler.enqueueTarget(context, roundId, target)
                    trace.event("target_enqueued")
                }, recordFailure = { target, failure ->
                    /** 已知生成中断与真实队列失败分开，未入队目标不会永久显示等待。 */
                    val reason = if (failure is CancellationException) "generation_interrupted" else "schedule_failed"
                    withContext(NonCancellable) { commit(roundId, target, reason) }
                    trace.event("schedule_failed", errorType = failure.javaClass.simpleName, reasonCode = reason)
                })
            trace.event("generation_finished")
        }
    }

    /** 系统文件选择器导出保持独立；取消选择器不调用此方法。 */
    suspend fun exportLocal(uri: Uri) = BackupTaskGate.runExclusive {
        withContext(Dispatchers.IO) {
            /** 独立导出身份，不触碰后台只读包。 */
            val taskId = newBackupTaskId("manual-local")
            localCompletion.publish {
                /** 使用现有分页导出和协议校验。 */
                val export = repository.createSnapshot(BackupSource.LocalManual, taskId = taskId)
                try {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        export.packageFile.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw BackupFailure.StorageNotWritable()
                } finally { temporaryStore.cleanupTaskDir(export.taskDir, taskId) }
            }
        }
    }

    /** 本地消费固定包，不等待云端或进入生成/恢复锁。 */
    internal suspend fun publishLocal(roundId: String, trace: BackupDiagnosticTrace) =
        publishTarget(roundId, BackupResultTarget.Local, trace) { -256 }

    /** 云端消费固定包；旧 artifact_id 输入由 Worker 直接跳过。 */
    internal suspend fun upload(roundId: String, trace: BackupDiagnosticTrace, interruptedReason: () -> Int = { -256 }) =
        publishTarget(roundId, BackupResultTarget.WebDav, trace, interruptedReason)

    /** 开始固定身份与目标，副作用完成后事务比较 ID 保存结果。 */
    private suspend fun publishTarget(roundId: String, target: BackupResultTarget, trace: BackupDiagnosticTrace,
        interruptedReason: () -> Int) = withContext(Dispatchers.IO) {
        /** 开始前一次快照检查；传输中不切换文件或持有状态锁。 */
        val snapshot = runtime.snapshot()
        /** 两目标共享文件身份，各自阶段独立。 */
        val pending = snapshot.pendingUpload
        /** 本地或云端阶段，重复请求不能再次发布。 */
        val phase = if (target == BackupResultTarget.Local) pending?.localPhase else pending?.phase
        if (snapshot.currentRoundId != roundId || pending?.artifactId != roundId) {
            trace.event("target_skipped", reasonCode = "superseded"); return@withContext
        }
        trace.bind(roundId, pending.source)
        if (!BackupSchedulingPolicy.canSchedule(phase.orEmpty())) {
            // 重执行运行中阶段时结果未知，沿用结束而不重传契约。
            if (phase == "uploading" || phase == "publishing") commit(roundId, target, "upload_interrupted")
            trace.event("target_skipped", reasonCode = "already_finished"); return@withContext
        }
        try {
            /** 云端实际请求时同次读取配置与密码，本地不解密。 */
            val connection = if (target == BackupResultTarget.WebDav) settingsRepository.loadWebDavConnection() else null
            /** 固定本次目的地，禁止向新目标发送旧包。 */
            val settings = connection?.settings ?: settingsRepository.snapshot()
            /** 比较授权摘要或目的地摘要，禁止将这些字段记入日志。 */
            val targetMatches = if (target == BackupResultTarget.Local)
                settings.localBackupDirUri.isNotBlank() && pending.localTargetFingerprint == BackupUploadTargetFingerprint.local(settings)
            else settings.webDavEndpoint.isNotBlank() && pending.targetFingerprint == BackupUploadTargetFingerprint.of(settings)
            if (!targetMatches || (pending.source == "auto" && !settings.autoBackupEnabled)) {
                runtime.updateTargetPhase(roundId, target, "cancelled")
                trace.event("target_skipped", reasonCode = "configuration_changed"); return@withContext
            }
            runtime.updateTargetPhase(roundId, target, if (target == BackupResultTarget.Local) "publishing" else "uploading")
            trace.stage(if (target == BackupResultTarget.Local) "publishing_local" else "reading_upload_package")
            /** 只读取本请求目录，旧包被删允许失败，不重读业务数据库。 */
            val export = pendingStore.read(roundId)
            /** 凭据仅进入内存请求，不进入 WorkData 或包描述。 */
            val config = connection?.let {
                WebDavConfig(settings.webDavEndpoint, settings.webDavUsername, it.password.orEmpty(),
                    normalizeWebDavRemoteDir(settings.webDavRemoteDir), settings.webDavAllowInsecureHttp)
            }
            if (target == BackupResultTarget.Local) localWriter.writeExport(Uri.parse(settings.localBackupDirUri), export)
            else webDavClient.uploadBackup(requireNotNull(config), export, roundId, trace::stage)
            trace.stage("committing_result")
            if (!commit(roundId, target)) { trace.event("result_skipped", reasonCode = "superseded"); return@withContext }
            trace.event("target_result_saved")
            // 成功实际落盘才清理历史备份，清理失败不能反转成功。
            try {
                trace.stage(if (target == BackupResultTarget.Local) "pruning_local" else "pruning_remote")
                if (target == BackupResultTarget.Local)
                    localWriter.pruneBackups(Uri.parse(settings.localBackupDirUri), settings.backupRetentionCount, roundId)
                else webDavClient.pruneBackups(requireNotNull(config), settings.backupRetentionCount, roundId)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                trace.event("retention_cleanup_failed", errorType = failure.javaClass.simpleName, reasonCode = "retention_cleanup_failed")
                logW("BackupCoordinator") { "历史备份清理失败 target=$target reasonCode=retention_cleanup_failed" }
            }
        } catch (failure: Throwable) {
            /** 状态写盘故障不能将已经发布的文件伪造成发布失败。 */
            val reason = when (failure) {
                is BackupResultPersistenceFailure -> "result_store_failed"
                is CancellationException -> if (target == BackupResultTarget.Local) "local_interrupted"
                    else BackupStopReasonMapper.interruptionCode(interruptedReason())
                else -> failure.backupReasonCode()
            }
            trace.event("target_failed", errorType = failure.javaClass.simpleName, reasonCode = reason)
            withContext(NonCancellable) {
                if (failure is BackupResultPersistenceFailure) runtime.updateTargetPhase(roundId, target, "result_store_failed")
                else commit(roundId, target, reason)
            }
            logE("BackupCoordinator") { "目标执行结束 target=$target reasonCode=$reason" }
            throw failure
        }
    }

    /** 条件结果提交保持原有存储失败契约，消费方永远不删除共享包。 */
    private suspend fun commit(roundId: String, target: BackupResultTarget, reason: String = ""): Boolean =
        BackupResultPersistence.commit(reason.isBlank()) {
            runtime.recordTargetResultIfCurrentRound(roundId, target, System.currentTimeMillis(), reason)
        }

    /** 升级只释放旧请求；启动和配置变化不补传、不删除当前完整包。 */
    suspend fun reconcile() = BackupTaskGate.runExclusive {
        withContext(Dispatchers.IO) {
            /** 没有新轮次键的旧身份不能冒领当前文件。 */
            val snapshot = runtime.snapshot()
            if (snapshot.currentRoundId.isBlank()) {
                snapshot.pendingUpload?.let { old ->
                    BackupAutoScheduler.cancelLegacyUpload(context, old.artifactId)
                    runtime.clearPending(old.artifactId)
                }
                try { pendingStore.cleanupExcept(null) }
                catch (_: Exception) { logW("BackupCoordinator") { "旧内部包清理失败 reasonCode=package_cleanup_failed" } }
            }
        }
    }

    /** 空目标不产生结果，独立分派之间没有任务依赖。 */
    private fun selectedTargets(local: Boolean, remote: Boolean): List<BackupResultTarget> = buildList {
        if (local) add(BackupResultTarget.Local)
        if (remote) add(BackupResultTarget.WebDav)
    }
}
