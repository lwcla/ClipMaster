package com.cla.clip.base.general.backup

import com.cla.clip.base.general.BuildConfig
import com.cla.clip.base.general.utils.logE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 备份恢复仓库。
 *
 * 该类负责备份对外入口、预检摘要和恢复委托；导出分页与 manifest 组装委托给 `BackupSnapshotExporter`，
 * 恢复事务和幂等合并委托给 `BackupSnapshotRestorer`。
 * 页面和 WebDAV 客户端只处理文件读写，不直接理解数据库表结构，避免后续新增表时多处散落备份逻辑。
 */
@Singleton
class BackupRepository @Inject constructor(
    /** 备份快照导出器，负责分页读取、manifest 组装和临时 zip 生成。 */
    private val snapshotExporter: BackupSnapshotExporter,
    /** 备份快照恢复器，负责事务恢复、幂等合并和分类报告。 */
    private val snapshotRestorer: BackupSnapshotRestorer,
    /** 文件型备份包读取器，负责 manifest、checksum 和 JSONL/v1 兼容读取。 */
    private val packageReader: BackupPackageReader,
) {
    companion object {
        /** 日志标签，只记录脱敏状态，不输出剪贴内容或账号密码。 */
        private const val TAG = "BackupRepository"
    }

    /**
     * 全局备份恢复互斥锁。
     *
     * 手动本地导出、WebDAV 上传、恢复写库和后续自动任务共用该锁，保证不会同时生成两个不同快照或并发恢复。
     */
    private val backupMutex = Mutex()

    /**
     * 生成完整备份快照和 manifest。
     *
     * 实际分页读取和 zip 组装委托给 `BackupSnapshotExporter`，仓库入口只负责对外 API 和与恢复共用的互斥边界。
     */
    suspend fun createSnapshot(
        source: BackupSource,
        backupKind: BackupKind = source.defaultBackupKind(),
        now: Long = System.currentTimeMillis(),
        taskId: String = newBackupTaskId("export"),
    ): BackupExportResult = withContext(Dispatchers.IO) {
        backupMutex.withLock {
            snapshotExporter.export(source = source, backupKind = backupKind, now = now, taskId = taskId)
        }
    }

    /**
     * 解析完整备份并生成预检摘要。
     *
     * 该方法只读不写，适合“只预览不恢复”；任何格式、身份或 checksum 问题都会在这里提前暴露。
     */
    suspend fun previewSnapshot(ref: BackupPackageRef): BackupPreview = withContext(Dispatchers.Default) {
        val manifest = packageReader.preview(ref)
        BackupPreview(
            createdAt = manifest.createdAt,
            appVersionName = manifest.appVersionName,
            schemaVersion = manifest.schemaVersion,
            deviceLabel = manifest.deviceLabel,
            backupKind = manifest.backupKind,
            checksumValid = true,
            summary = manifest.summary
        )
    }

    /** 旧字节数组预览兼容入口；仅用于测试或短期过渡，主流程应使用文件引用。 */
    suspend fun previewSnapshot(packageBytes: ByteArray): BackupPreview = withContext(Dispatchers.Default) {
        val snapshot = decodeAndValidateSnapshot(packageBytes)
        BackupPreview(
            createdAt = snapshot.createdAt,
            appVersionName = snapshot.appVersionName,
            schemaVersion = snapshot.schemaVersion,
            deviceLabel = snapshot.deviceLabel,
            backupKind = snapshot.backupKind,
            checksumValid = true,
            summary = snapshot.summary
        )
    }

    /**
     * 恢复完整备份。
     *
     * 写库阶段在 Room transaction 内执行；同一备份重复恢复通过主键/upsert 和“本地较新则跳过”的规则保持幂等。
     */
    suspend fun restoreSnapshot(ref: BackupPackageRef): BackupRestoreReport = withContext(Dispatchers.IO) {
        backupMutex.withLock {
            val manifest = packageReader.preview(ref)
            val timeNormalizer = BackupRestoreTimeNormalizer(
                restoreStartedAt = System.currentTimeMillis(),
                manifestCreatedAt = manifest.createdAt
            )
            if (manifest.schemaVersion <= 1 || manifest.dataFormat == BACKUP_DATA_FORMAT_JSON_ARRAY) {
                val snapshot = ref.requireReadable().readBytes().decodeBackupPackage()
                snapshot.validateForRestore(BuildConfig.APPLICATION_ID)
                return@withContext snapshotRestorer.restoreSnapshot(snapshot, timeNormalizer)
            }
            snapshotRestorer.restorePackage(ref, manifest, timeNormalizer)
        }
    }

    /** 旧字节数组恢复兼容入口；仅用于测试或短期过渡，主流程应使用文件引用。 */
    suspend fun restoreSnapshot(packageBytes: ByteArray): BackupRestoreReport = withContext(Dispatchers.IO) {
        backupMutex.withLock {
            val snapshot = decodeAndValidateSnapshot(packageBytes)
            val timeNormalizer = BackupRestoreTimeNormalizer(
                restoreStartedAt = System.currentTimeMillis(),
                manifestCreatedAt = snapshot.createdAt
            )
            snapshotRestorer.restoreSnapshot(snapshot, timeNormalizer)
        }
    }

    /** 解码并校验快照，统一处理 kotlinx.serialization 抛出的解析异常。 */
    private fun decodeAndValidateSnapshot(packageBytes: ByteArray): BackupSnapshot {
        val snapshot = runCatching { packageBytes.decodeBackupPackage() }
            .getOrElse { throwable ->
                logE(TAG, throwable) {
                    "备份包解析失败 reasonCode=${throwable.backupReasonCode()} type=${throwable::class.simpleName}"
                }
                if (throwable is BackupFailure) throw throwable else throw BackupFailure.ParseFailed(throwable)
            }
        snapshot.validateForRestore(BuildConfig.APPLICATION_ID)
        return snapshot
    }
}
