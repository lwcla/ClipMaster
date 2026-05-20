package com.cla.clip.master.ui.page.backup

import android.content.Context
import com.cla.clip.base.general.R
import com.cla.clip.base.general.backup.BackupExportResult
import com.cla.clip.base.general.backup.BackupFailure
import com.cla.clip.base.general.backup.BackupKind
import com.cla.clip.base.general.backup.BackupSafetySnapshotResult
import com.cla.clip.base.general.backup.BackupJson
import com.cla.clip.base.general.backup.backupReasonCode
import com.cla.clip.base.general.backup.backupTaskLogField
import com.cla.clip.base.general.backup.logCode
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 应用私有安全快照存储。
 *
 * 当用户尚未设置本地备份目录时，恢复前安全快照写到应用私有 files 目录；它能在本次安装内作为回滚点，
 * 但不会承诺卸载后仍存在，因此 UI 报告必须明确展示保存位置。
 */
class PrivateSafetySnapshotStore @Inject constructor(
    /** 应用级 Context，用于访问 filesDir，不持有页面生命周期。 */
    @param:ApplicationContext private val appContext: Context,
) {
    companion object {
        /** 日志标签；只记录私有目录安全快照数量和文件名，不输出真实文件绝对路径。 */
        private const val TAG = "PrivateSafetySnapshotStore"

        /** 私有安全快照目录名。 */
        private const val SAFETY_DIR_NAME = "backup_safety_snapshots"

        /** 私有安全快照固定保留份数，避免恢复多次后占用无限增长。 */
        private const val SAFETY_RETENTION_COUNT = 3
    }

    /** 写入私有安全快照并清理旧 safety 文件。 */
    suspend fun writeSafetySnapshot(export: BackupExportResult, taskId: String? = null): BackupSafetySnapshotResult = withContext(Dispatchers.IO) {
        logD(TAG) {
            "开始写入私有安全快照 ${backupTaskLogField(taskId)}target=private backupKind=${export.manifest.backupKind.logCode()} " +
                "fileName=${export.fileName} fileSize=${export.fileSize}"
        }
        val dir = File(appContext.filesDir, SAFETY_DIR_NAME).apply {
            if (!exists() && !mkdirs()) throw BackupFailure.StorageNotWritable()
        }
        val snapshotFile = File(dir, export.fileName)
        val manifestFile = File(dir, export.manifestFileName)
        runCatching {
            export.packageFile.copyTo(snapshotFile, overwrite = true)
            manifestFile.writeText(export.manifestJson, Charsets.UTF_8)
        }.getOrElse { throwable ->
            logE(TAG) {
                "私有安全快照写入失败 ${backupTaskLogField(taskId)}target=private reasonCode=${throwable.backupReasonCode()} type=${throwable::class.simpleName}"
            }
            throw BackupFailure.StorageNotWritable(throwable)
        }
        val deleted = pruneSafetySnapshots(dir, taskId)
        logD(TAG) {
            "私有安全快照写入成功 ${backupTaskLogField(taskId)}target=private fileName=${export.fileName} " +
                "fileSize=${export.fileSize} safetyDeleted=$deleted"
        }
        BackupSafetySnapshotResult(
            fileName = export.fileName,
            locationLabel = appContext.getString(R.string.base_general_backup_private_safety_location),
            fileSize = export.fileSize
        )
    }

    /** 按 manifest 创建时间清理旧私有安全快照，只删除明确标记为 safety 的文件。 */
    private fun pruneSafetySnapshots(dir: File, taskId: String?): Int {
        val candidates = dir.listFiles()
            .orEmpty()
            .filter { it.name.endsWith(".zip") && it.name.startsWith("clip_master_backup_") }
            .mapNotNull { file ->
                val manifestFile = File(dir, "${file.name}.manifest.json")
                val manifest = runCatching {
                    BackupJson.decodeManifest(manifestFile.readText(Charsets.UTF_8))
                }.getOrNull()
                if (manifest?.backupKind == BackupKind.Safety) file to manifest else null
            }
            .sortedByDescending { (_, manifest) -> manifest.createdAt }
        var deleted = 0
        val toDelete = candidates.drop(SAFETY_RETENTION_COUNT)
        logD(TAG) {
            "开始清理私有安全快照 ${backupTaskLogField(taskId)}target=private candidates=${candidates.size} toDelete=${toDelete.size}"
        }
        toDelete.forEach { (file, _) ->
            if (file.delete()) {
                File(dir, "${file.name}.manifest.json").delete()
                deleted += 1
                logD(TAG) { "私有安全快照删除成功 ${backupTaskLogField(taskId)}target=private fileName=${file.name}" }
            } else {
                logE(TAG) { "私有安全快照删除失败 ${backupTaskLogField(taskId)}target=private fileName=${file.name} reasonCode=delete_failed" }
            }
        }
        logD(TAG) { "私有安全快照清理完成 ${backupTaskLogField(taskId)}target=private deleted=$deleted" }
        return deleted
    }
}
