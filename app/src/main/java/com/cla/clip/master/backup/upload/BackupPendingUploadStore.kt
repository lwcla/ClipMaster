package com.cla.clip.master.backup.upload

import android.content.Context
import com.cla.clip.base.general.backup.BackupExportResult
import com.cla.clip.base.general.backup.BackupJson
import com.cla.clip.base.general.backup.BackupFailure
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 本地与云端共用只读完整包，消费方不清理，下一轮开始统一删除。 */
@Singleton
class BackupPendingUploadStore internal constructor(
    /** 唯一私有根目录；测试可注入合成目录。 */
    private val root: File,
) {
    /** Android 装配选择系统自动备份排除目录，不含凭据或跨安装运行态。 */
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, "backup_pending_upload"))

    /** 完整复制 ZIP 和 sidecar 后返回；调用方再发布 ready，失败只清理本轮半包，不恢复旧包。 */
    fun prepare(artifactId: String, export: BackupExportResult): BackupExportResult {
        /** 新包独占目录；失败只能删除此目录。 */
        val directory = directory(artifactId)
        check(!directory.exists()) { "artifact_already_exists" }
        check(directory.mkdirs()) { "pending_directory_unavailable" }
        try {
            if (directory.usableSpace < export.fileSize * 2L) throw BackupFailure.InsufficientSpace()
            /** 同一份 ZIP 的字节副本，不重新生成业务快照。 */
            val payload = File(directory, export.fileName)
            export.packageFile.copyTo(payload)
            File(directory, "manifest.json").writeText(export.manifestJson)
            File(directory, "filename").writeText(export.fileName)
            return export.copy(packageFile = payload, taskDir = directory)
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            throw failure
        }
    }

    /** 读取当前私有同包；缺失、空包及描述损坏统一返回稳定文件失败，不重新导出。 */
    fun read(artifactId: String): BackupExportResult {
        /** 受控根目录下的任务目录。 */
        val directory = directory(artifactId)
        try {
            /** 生成时记录的文件名，不能逃逸任务目录。 */
            val filename = File(directory, "filename").readText()
            require(filename.matches(Regex("[A-Za-z0-9_.-]+\\.zip"))) { "invalid_pending_filename" }
            /** 私有小型描述不参与 WebDAV 独立发布，仍用于重建同包导出模型。 */
            val json = File(directory, "manifest.json").readText()
            /** 待上传字节必须可读且非空，不能重新导出兜底。 */
            val payload = File(directory, filename)
            check(payload.isFile && payload.canRead() && payload.length() > 0L) { "pending_payload_missing" }
            return BackupExportResult(payload, BackupJson.decodeManifest(json), json, filename, "$filename.manifest.json", directory)
        } catch (failure: Exception) {
            // 读取和解析失败有明确本地文件证据；不保存原始路径或描述到用户原因和日志。
            throw BackupFailure.TempFileUnavailable(failure)
        }
    }

    /** 生成失败或下一轮淘汰时删除指定包；根路径校验保证不触及用户正式备份。 */
    fun remove(artifactId: String) {
        /** 经规范化和身份校验的唯一目录。 */
        val directory = directory(artifactId)
        check(!directory.exists() || directory.deleteRecursively()) { "pending_cleanup_failed" }
    }

    /** 下一轮/旧版本迁移清理内部目录，消费者结束不调用；当前完整包不按年龄淘汰。 */
    fun cleanupExcept(artifactId: String?) {
        root.listFiles()?.filter { it.name != artifactId }?.forEach { child ->
            // 子目录只能由本存储生成；安全身份规则排除根和上级目录。
            remove(child.name)
        }
    }

    /** 私有身份只能包含低敏字母、数字及连接符，规范化后必须位于根内。 */
    private fun directory(artifactId: String): File {
        require(artifactId.matches(Regex("[A-Za-z0-9_-]+"))) { "invalid_artifact_id" }
        /** 规范化的绝对文件目录，防止任何上级路径清理。 */
        val directory = File(root, artifactId).canonicalFile
        require(directory.parentFile == root.canonicalFile) { "artifact_outside_root" }
        return directory
    }
}
