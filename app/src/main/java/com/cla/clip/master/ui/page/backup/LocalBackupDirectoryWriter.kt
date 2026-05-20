package com.cla.clip.master.ui.page.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.cla.clip.base.general.backup.BackupJson
import com.cla.clip.base.general.backup.BackupKind
import com.cla.clip.base.general.backup.BackupExportResult
import com.cla.clip.base.general.backup.BackupFailure
import com.cla.clip.base.general.backup.BackupManifest
import com.cla.clip.base.general.backup.BackupPackageRef
import com.cla.clip.base.general.backup.BackupRetentionCleanupResult
import com.cla.clip.base.general.backup.backupReasonCode
import com.cla.clip.base.general.backup.backupTaskLogField
import com.cla.clip.base.general.backup.logCode
import com.cla.clip.base.general.backup.parseBackupKindFromFileName
import com.cla.clip.base.general.backup.parseBackupTimestampFromFileName
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 本地备份文件夹写入器。
 *
 * 该类专门处理 SAF 目录授权下的文件创建和字节写入；ViewModel 只负责“先本地、后 WebDAV”的业务流程，
 * 避免把 ContentResolver 副作用散落在页面状态编排中。目录 URI 来自用户主动授权，失效或不可写时统一抛出
 * `BackupFailure.StorageNotWritable`，由 UI 显示可行动提示。
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
     * 写入顺序为 `.tmp` zip、`.tmp` manifest、发布正式 zip、发布正式 manifest；列表扫描只识别正式文件名，
     * 因此写入中途失败不会把临时文件当成可恢复备份展示。这里复用 WebDAV 上传的同一份
     * `BackupExportResult`，确保本地和远端拥有相同快照内容、文件名和 checksum。
     */
    suspend fun writeExport(dirUri: Uri, export: BackupExportResult, taskId: String? = null) = withContext(Dispatchers.IO) {
        logD(TAG) {
            "开始写入本地备份 ${backupTaskLogField(taskId)}fileName=${export.fileName} " +
                "backupKind=${export.manifest.backupKind.logCode()} fileSize=${export.fileSize}"
        }
        val tempBackupName = "${export.fileName}.tmp"
        val tempManifestName = "${export.manifestFileName}.tmp"
        var tempBackupUri: Uri? = null
        var tempManifestUri: Uri? = null
        var publishedBackupUri: Uri? = null
        var publishedManifestUri: Uri? = null
        runCatching {
            tempBackupUri = writeFile(
                dirUri = dirUri,
                fileName = tempBackupName,
                mimeType = "application/zip",
                sourceFile = export.packageFile
            )
            tempManifestUri = writeFile(
                dirUri = dirUri,
                fileName = tempManifestName,
                mimeType = "application/json",
                bytes = export.manifestJson.toByteArray(Charsets.UTF_8)
            )
            publishedBackupUri = publishTempFile(dirUri, tempBackupUri ?: throw BackupFailure.StorageNotWritable(), export.fileName)
            publishedManifestUri = publishTempFile(dirUri, tempManifestUri ?: throw BackupFailure.StorageNotWritable(), export.manifestFileName)
        }.onFailure { throwable ->
            tempBackupUri?.let(::deleteDocument)
            tempManifestUri?.let(::deleteDocument)
            publishedBackupUri?.let(::deleteDocument)
            publishedManifestUri?.let(::deleteDocument)
            throw throwable
        }.getOrThrow()
        logD(TAG) { "本地备份写入成功 ${backupTaskLogField(taskId)}fileName=${export.fileName}" }
    }

    /**
     * 列出用户授权目录中的本应用备份。
     *
     * 只读取文件名、大小和 manifest sidecar；不会打开完整 zip 包，避免备份页展示列表时读取大量用户数据。
     * manifest 损坏时保留文件条目但 manifest 为空，用户仍可通过完整预检判断备份是否可恢复。
     */
    suspend fun listBackups(dirUri: Uri): List<LocalBackupFile> = withContext(Dispatchers.IO) {
        val children = listChildren(dirUri)
        val manifestsByName = children
            .filter { it.name.endsWith(".manifest.json") }
            .associateBy { it.name }
        children
            .filter { it.name.endsWith(".zip") && it.name.startsWith("clip_master_backup_") }
            .map { entry ->
                val manifest = manifestsByName["${entry.name}.manifest.json"]?.let { manifestEntry ->
                    runCatching {
                        readText(manifestEntry.uri)?.let(BackupJson::decodeManifest)
                    }.getOrNull()
                }
                LocalBackupFile(
                    fileName = entry.name,
                    uri = entry.uri,
                    size = entry.size,
                    lastModified = entry.lastModified,
                    manifest = manifest
                )
            }
            .sortedWith(
                compareByDescending<LocalBackupFile> { it.sortCreatedAt }
                    .thenByDescending { it.lastModified ?: 0L }
                    .thenByDescending { it.fileName }
            )
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
     * 清理只作用于 manifest 或文件名可识别的本 App 普通备份，手动/自动备份一起按时间保留最近 `keepCount` 份；
     * 历史 safety、manifest 损坏文件和其它不可识别文件不删除，避免把异常文件误当作普通备份清理。
     */
    suspend fun pruneBackups(
        dirUri: Uri,
        keepCount: Int,
        taskId: String? = null,
    ): BackupRetentionCleanupResult = withContext(Dispatchers.IO) {
        val candidates = listBackups(dirUri)
            .filter { file -> file.isRegularBackup }
            .sortedWith(
                compareByDescending<LocalBackupFile> { it.sortCreatedAt }
                    .thenByDescending { it.lastModified ?: 0L }
                    .thenByDescending { it.fileName }
            )
        val toDelete = candidates.drop(keepCount.coerceAtLeast(0))
        logD(TAG) {
            "开始清理本地旧备份 ${backupTaskLogField(taskId)}target=local keepCount=$keepCount " +
                "candidates=${candidates.size} toDelete=${toDelete.size}"
        }
        var deleted = 0
        toDelete.forEach { file ->
            val zipDeleted = deleteDocument(file.uri)
            if (zipDeleted) {
                findChildUri(dirUri, "${file.fileName}.manifest.json")?.let(::deleteDocument)
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
        BackupRetentionCleanupResult(deletedCount = deleted, deletedKinds = toDelete.mapNotNull { it.manifest?.backupKind }.distinct())
    }

    /** 读取目录下全部子项的轻量元数据。 */
    private fun listChildren(dirUri: Uri): List<LocalBackupEntry> {
        val documentId = DocumentsContract.getTreeDocumentId(dirUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        return runCatching {
            appContext.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                buildList {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIndex) ?: continue
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

    /** 读取小型 manifest 文本；完整 zip 预检不走这里。 */
    private fun readText(uri: Uri): String? {
        return appContext.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
    }

    /** 在 SAF 目录中创建并写入单个小文件，返回文档 URI 供两阶段发布或失败清理使用。 */
    private fun writeFile(dirUri: Uri, fileName: String, mimeType: String, bytes: ByteArray): Uri {
        val fileUri = createOrReplaceFile(dirUri, fileName, mimeType)
        appContext.contentResolver.openOutputStream(fileUri, "wt")?.use { output ->
            output.write(bytes)
        } ?: throw BackupFailure.StorageNotWritable()
        return fileUri
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
     */
    private fun createOrReplaceFile(dirUri: Uri, fileName: String, mimeType: String): Uri {
        return runCatching {
            findChildUri(dirUri, fileName)?.let { existing ->
                DocumentsContract.deleteDocument(appContext.contentResolver, existing)
            }
            val documentId = DocumentsContract.getTreeDocumentId(dirUri)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(dirUri, documentId)
            DocumentsContract.createDocument(appContext.contentResolver, parentUri, mimeType, fileName)
        }.getOrElse { throwable ->
            throw BackupFailure.StorageNotWritable(throwable)
        } ?: throw BackupFailure.StorageNotWritable()
    }

    /**
     * 查找目录下的同名子文件。
     *
     * 查询只读取 displayName 和 documentId，不读取文件内容；找不到或查询失败都交由创建流程继续处理。
     */
    private fun findChildUri(dirUri: Uri, fileName: String): Uri? {
        val documentId = DocumentsContract.getTreeDocumentId(dirUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, documentId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return runCatching {
            appContext.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == fileName) {
                        val childId = cursor.getString(idIndex)
                        return DocumentsContract.buildDocumentUriUsingTree(dirUri, childId)
                    }
                }
            }
            null
        }.getOrNull()
    }

    /** 删除单个 SAF 文档，404 类行为由系统实现决定；失败时返回 false 供清理摘要记录。 */
    private fun deleteDocument(uri: Uri): Boolean {
        return runCatching {
            DocumentsContract.deleteDocument(appContext.contentResolver, uri)
        }.getOrDefault(false)
    }

    /** 本地 SAF 目录中的轻量子文件元数据。 */
    private data class LocalBackupEntry(
        val name: String,
        val uri: Uri,
        val size: Long?,
        val lastModified: Long?,
    )
}

/** 本地备份目录中的备份条目，供列表展示和点击预览使用。 */
data class LocalBackupFile(
    /** 备份 zip 文件名。 */
    val fileName: String,
    /** 备份 zip 文件 URI。 */
    val uri: Uri,
    /** 文件大小，未知时为空。 */
    val size: Long?,
    /** 系统返回的最后修改时间，未知时为空。 */
    val lastModified: Long?,
    /** 对应 sidecar manifest；损坏或缺失时为空。 */
    val manifest: BackupManifest?,
) {
    /** 列表排序使用的创建时间：manifest 优先，手动导出的单 zip 通过文件名时间戳兜底。 */
    val sortCreatedAt: Long
        get() = manifest?.createdAt ?: parseBackupTimestampFromFileName(fileName) ?: 0L

    /** 列表展示使用的有效类型：manifest 优先，缺失时通过旧版 safety 文件名兜底识别。 */
    val effectiveBackupKind: BackupKind?
        get() = manifest?.backupKind ?: parseBackupKindFromFileName(fileName)

    /** 是否是旧版恢复前回滚文件；备份页会隐藏它，避免用户误认为这是普通备份。 */
    val isSafetySnapshot: Boolean
        get() = effectiveBackupKind == BackupKind.Safety

    /** 是否是参与保留份数的普通备份；manifest 缺失但文件名标准时按手动旧备份兼容处理。 */
    val isRegularBackup: Boolean
        get() = when (effectiveBackupKind) {
            BackupKind.Manual,
            BackupKind.Auto -> true
            BackupKind.Safety -> false
            null -> parseBackupTimestampFromFileName(fileName) != null
        }
}
