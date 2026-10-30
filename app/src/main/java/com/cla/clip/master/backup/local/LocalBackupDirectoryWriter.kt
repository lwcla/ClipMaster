package com.cla.clip.master.backup.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.cla.clip.base.general.backup.BackupExportResult
import com.cla.clip.base.general.backup.BackupFailure
import com.cla.clip.base.general.backup.BackupPackageRef
import com.cla.clip.base.general.backup.BackupRetentionCleanupResult
import com.cla.clip.base.general.backup.backupReasonCode
import com.cla.clip.base.general.backup.backupTaskLogField
import com.cla.clip.base.general.backup.logCode
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 本地备份文件夹写入器。
 *
 * 该类专门处理 SAF 目录授权下的文件创建和字节写入；备份协调器负责本地与 WebDAV 的独立发布流程，
 * 避免把 ContentResolver 副作用散落在页面状态编排中。目录 URI 来自用户主动授权，失效或不可写时统一抛出
 * `BackupFailure.StorageNotWritable`；目标位置被同名文件夹占用时单独抛出 `LocalDirectoryConflict`，由 UI 提示处理方式。
 */
class LocalBackupDirectoryWriter @Inject constructor(
    /** 应用级 Context，用于访问 ContentResolver，不持有 Activity，避免生命周期泄漏。 */
    @param:ApplicationContext private val appContext: Context,
) {
    companion object {
        /** 日志标签；只记录文件名、大小、数量和 reasonCode，不输出 SAF URI 原文。 */
        private const val TAG = "LocalBackupDirectoryWriter"
    }

    /**
     * 将一次导出的备份结果写入用户授权的本地目录。
     *
     * 只写 `.zip.tmp` 后发布正式 ZIP，不再生成外部 JSON；列表扫描忽略临时文件。
     * 直接复制 WebDAV 上传使用的同一份 `BackupExportResult.packageFile`，保持包内 manifest/checksum 不变。
     */
    suspend fun writeExport(dirUri: Uri, export: BackupExportResult, taskId: String? = null) = withContext(Dispatchers.IO) {
        logD(TAG) {
            "开始写入本地备份 ${backupTaskLogField(taskId)}fileName=${export.fileName} " +
                "backupKind=${export.manifest.backupKind.logCode()} fileSize=${export.fileSize}"
        }
        /** 临时文件名不以 ZIP 结尾，尚未完成的写入不会进入列表。 */
        val tempBackupName = "${export.fileName}.tmp"
        /** 本次已写完的临时文档句柄，失败时尽力删除。 */
        var tempBackupUri: Uri? = null
        /** 本次发布的正式文档句柄，仅用于后续异常时清理本次产物。 */
        var publishedBackupUri: Uri? = null
        // 只复制 ZIP 字节，不读取或发布导出结果中的独立摘要。
        runCatching {
            tempBackupUri = writeFile(
                dirUri = dirUri,
                fileName = tempBackupName,
                mimeType = "application/zip",
                sourceFile = export.packageFile
            )
            publishedBackupUri = publishTempFile(dirUri, tempBackupUri ?: throw BackupFailure.StorageNotWritable(), export.fileName)
        }.onFailure { throwable ->
            // 仅回收本轮 ZIP，历史 JSON 与其他用户文档不属于清理范围。
            tempBackupUri?.let(::deleteDocument)
            publishedBackupUri?.let(::deleteDocument)
            logE(TAG) {
                "本地备份写入失败 ${backupTaskLogField(taskId)}reasonCode=${throwable.backupReasonCode()} type=${throwable::class.simpleName}"
            }
            throw throwable
        }.getOrThrow()
        logD(TAG) { "本地备份写入成功 ${backupTaskLogField(taskId)}fileName=${export.fileName}" }
    }

    /**
     * 列出用户授权目录中的本应用备份。
     *
     * 只查询目录元数据，不打开 ZIP 或历史摘要；可恢复性由用户选择文件后的包内完整预检决定。
     * 排序及保留身份委托纯元数据规则，避免 IO 层维护两套时间和类型判断。
     */
    suspend fun listBackups(dirUri: Uri): List<LocalBackupFile> = withContext(Dispatchers.IO) {
        listChildren(dirUri)
            // 子项均已排除目录，只在内存映射轻量元数据。
            .map { entry ->
                LocalBackupFile(
                    fileName = entry.name,
                    uri = entry.uri,
                    size = entry.size,
                    lastModified = entry.lastModified,
                )
            }
            .filter { it.metadata.isBackupArchive }
            .sortedBy { it.metadata }
    }

    /** 把本地备份目录中的 zip 复制到私有临时文件，用于列表点击预览和恢复。 */
    suspend fun copyBackupToRef(file: LocalBackupFile, targetFile: java.io.File, taskDir: java.io.File): BackupPackageRef = withContext(Dispatchers.IO) {
        appContext.contentResolver.openInputStream(file.uri)?.use { input ->
            targetFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw BackupFailure.ParseFailed()
        BackupPackageRef(file = targetFile, fileName = file.fileName, taskDir = taskDir)
    }

    /**
     * 按保留份数清理本地普通备份。
     *
     * 只清理标准文件名可识别的普通 ZIP，按列表相同顺序保留最近 `keepCount` 份；
     * 历史 safety、未知文件和外部 JSON 均不删除，不为清理打开 ZIP 或读取历史摘要。
     */
    suspend fun pruneBackups(
        dirUri: Uri,
        keepCount: Int,
        taskId: String? = null,
    ): BackupRetentionCleanupResult = withContext(Dispatchers.IO) {
        /** 列表已经按元数据稳定倒序；过滤后顺序保持不变。 */
        val candidates = listBackups(dirUri)
            .filter { file -> file.isRegularBackup }
        /** 仅处理超过保留上限的普通 ZIP，负数沿用零份边界。 */
        val toDelete = candidates.drop(keepCount.coerceAtLeast(0))
        logD(TAG) {
            "开始清理本地旧备份 ${backupTaskLogField(taskId)}target=local keepCount=$keepCount " +
                "candidates=${candidates.size} toDelete=${toDelete.size}"
        }
        /** 只统计系统确认删除的 ZIP，不统计历史摘要或失败项。 */
        var deleted = 0
        // 删除边界来自正式 ZIP 列表；不再查找或删除同名 sidecar。
        toDelete.forEach { file ->
            /** 单项删除成功才计入汇总，失败保留原条目供后续重试。 */
            val zipDeleted = deleteDocument(file.uri)
            if (zipDeleted) {
                deleted += 1
                logD(TAG) { "本地旧备份删除成功 ${backupTaskLogField(taskId)}target=local fileName=${file.fileName}" }
            } else {
                logE(TAG) {
                    "本地旧备份删除失败 ${backupTaskLogField(taskId)}target=local fileName=${file.fileName} reasonCode=delete_failed"
                }
            }
        }
        logD(TAG) {
            "本地旧备份清理完成 ${backupTaskLogField(taskId)}target=local deleted=$deleted"
        }
        BackupRetentionCleanupResult(deletedCount = deleted, deletedKinds = emptyList())
    }

    /** 读取目录下全部子项的轻量元数据。 */
    private fun listChildren(dirUri: Uri): List<LocalBackupEntry> {
        /** 已获用户授权的树根 ID，不对外打印。 */
        val documentId = DocumentsContract.getTreeDocumentId(dirUri)
        /** 只查询该目录的直接子项，不递归扫描。 */
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, documentId)
        /** MIME 用于排除伪装成 ZIP 名称的目录，避免保留清理递归删除目录内容。 */
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return runCatching {
            appContext.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                /** idIndex 定位文档句柄，nameIndex 定位文件名，mimeIndex 用于排除目录。 */
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                /** sizeIndex/modifiedIndex 允许 Provider 不提供大小与修改时间，缺失时映射为空。 */
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                buildList {
                    while (cursor.moveToNext()) {
                        // 目录不能作为普通文件删除，即使显示名带应用前缀和 ZIP 后缀。
                        if (cursor.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                        /** 无名条目无法识别备份归属，保守跳过。 */
                        val name = cursor.getString(nameIndex) ?: continue
                        /** 文档 ID 只用于构造当前目录授权下的访问 URI。 */
                        val childId = cursor.getString(idIndex)
                        add(
                            LocalBackupEntry(
                                name = name,
                                uri = DocumentsContract.buildDocumentUriUsingTree(dirUri, childId),
                                size = sizeIndex.takeIf { it >= 0 }?.let { cursor.getLong(it) }.takeIf { it != 0L },
                                lastModified = modifiedIndex.takeIf { it >= 0 }?.let { cursor.getLong(it) }.takeIf { it != 0L }
                            )
                        )
                    }
                }
            }.orEmpty()
        }.getOrElse { throwable ->
            logE(TAG) {
                "本地备份目录读取失败 reasonCode=${throwable.backupReasonCode()} type=${throwable::class.simpleName}"
            }
            throw BackupFailure.StorageNotWritable(throwable)
        }
    }

    /** 以文件流写入 SAF 文档，避免大备份包进入内存，并返回临时文档 URI 供后续发布。 */
    private fun writeFile(dirUri: Uri, fileName: String, mimeType: String, sourceFile: java.io.File): Uri {
        val fileUri = createOrReplaceFile(dirUri, fileName, mimeType)
        appContext.contentResolver.openOutputStream(fileUri, "wt")?.use { output ->
            sourceFile.inputStream().use { input -> input.copyTo(output) }
        } ?: throw BackupFailure.StorageNotWritable()
        return fileUri
    }

    /**
     * 将写完整的临时 SAF 文档发布为正式文件名。
     *
     * Android SAF 没有跨 Provider 一致的原子替换能力；这里先删除同名正式文件，再调用 `renameDocument`，至少保证
     * 备份列表只会在临时文件完整写入后看到正式文件，并且 `.tmp` 残留不会参与列表、latest 或保留清理。
     */
    private fun publishTempFile(dirUri: Uri, tempUri: Uri, finalName: String): Uri {
        return runCatching {
            findChildUri(dirUri, finalName)?.let { existing ->
                if (!deleteDocument(existing)) throw BackupFailure.StorageNotWritable()
            }
            DocumentsContract.renameDocument(appContext.contentResolver, tempUri, finalName)
        }.getOrElse { throwable ->
            if (throwable is BackupFailure) throw throwable
            throw BackupFailure.StorageNotWritable(throwable)
        } ?: throw BackupFailure.StorageNotWritable()
    }

    /**
     * 创建或替换目录下的目标文件。
     *
     * SAF 的 DocumentsContract 只接受 displayName，不接受路径片段；备份文件名由协议生成并已经规避路径分隔符。
     * 同名目录或查询失败必须明确失败，不能把目录删除或把查询失败当作文件不存在。
     */
    private fun createOrReplaceFile(dirUri: Uri, fileName: String, mimeType: String): Uri {
        return runCatching {
            findChildUri(dirUri, fileName)?.let { existing ->
                // 旧文件删除失败时终止，避免 Provider 自动改名后发布到非预期文件。
                if (!deleteDocument(existing)) throw BackupFailure.StorageNotWritable()
            }
            /** 当前已授权树的根文档身份，仅用于构造创建请求。 */
            val documentId = DocumentsContract.getTreeDocumentId(dirUri)
            /** 保持所有创建都落在调用方授权的目录内。 */
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(dirUri, documentId)
            DocumentsContract.createDocument(appContext.contentResolver, parentUri, mimeType, fileName)
        }.getOrElse { throwable ->
            // 临时目标的同名目录冲突也要透传，避免保存结果与即时提示丢失专用原因。
            if (throwable is BackupFailure) throw throwable
            throw BackupFailure.StorageNotWritable(throwable)
        } ?: throw BackupFailure.StorageNotWritable()
    }

    /**
     * 查找目录下的同名子文件。
     *
     * 只读取名称、文档 ID 与 MIME，不读取内容。不存在返回 null；同名目录抛出 LocalDirectoryConflict，
     * 未知类型或查询失败抛出 StorageNotWritable，阻止误删目录且不将未知故障猜测为名称冲突。
     */
    private fun findChildUri(dirUri: Uri, fileName: String): Uri? {
        /** 已获授权的树 ID，不解析或输出外部路径。 */
        val documentId = DocumentsContract.getTreeDocumentId(dirUri)
        /** 查询范围只含树根直接子项，不递归扫描。 */
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, documentId)
        /** MIME 是删除前判断是否目录的必要信息，不能仅按名称决定替换。 */
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        return runCatching {
            appContext.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                /** idIndex/nameIndex 定位目标身份，mimeIndex 决定是否允许文件替换。 */
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == fileName) {
                        /** 只有已知非目录类型才可交给调用方删除或替换。 */
                        val mimeType = cursor.getString(mimeIndex)
                        // 仅系统明确报告为目录时给出重命名指引，普通父目录存在不进入此分支。
                        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) throw BackupFailure.LocalDirectoryConflict()
                        if (mimeType.isNullOrBlank()) throw BackupFailure.StorageNotWritable()
                        /** 文档身份仍使用原树授权构造，不扩大访问范围。 */
                        val childId = cursor.getString(idIndex)
                        return DocumentsContract.buildDocumentUriUsingTree(dirUri, childId)
                    }
                }
            } ?: throw BackupFailure.StorageNotWritable()
            null
        }.getOrElse { throwable ->
            // 查询失败不等于不存在；保留稳定失败类型供 UI 和任务结果展示。
            if (throwable is BackupFailure) throw throwable
            throw BackupFailure.StorageNotWritable(throwable)
        }
    }

    /** 删除单个 SAF 文档，404 类行为由系统实现决定；失败时返回 false 供清理摘要记录。 */
    private fun deleteDocument(uri: Uri): Boolean {
        return runCatching {
            DocumentsContract.deleteDocument(appContext.contentResolver, uri)
        }.getOrDefault(false)
    }

    /** 本地 SAF 目录中的轻量子文件元数据。 */
    private data class LocalBackupEntry(
        /** Provider 提供的原始显示名。 */
        val name: String,
        /** 当前已授权树中的文档句柄，仅供本地 IO。 */
        val uri: Uri,
        /** 字节大小，Provider 未提供时为空。 */
        val size: Long?,
        /** 修改时间毫秒值，未知时为空。 */
        val lastModified: Long?,
    )
}
