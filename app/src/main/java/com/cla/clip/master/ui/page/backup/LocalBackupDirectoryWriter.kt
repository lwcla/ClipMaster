package com.cla.clip.master.ui.page.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.cla.clip.base.general.backup.BackupExportResult
import com.cla.clip.base.general.backup.BackupFailure
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
    /**
     * 将一次导出的备份结果写入用户授权的本地目录。
     *
     * 写入顺序为完整 zip 备份包、manifest sidecar；二者都写入成功才视为本地备份成功。这里复用 WebDAV 上传的同一份
     * `BackupExportResult`，确保本地和远端拥有相同快照内容、文件名和 checksum。
     */
    suspend fun writeExport(dirUri: Uri, export: BackupExportResult) = withContext(Dispatchers.IO) {
        writeFile(
            dirUri = dirUri,
            fileName = export.fileName,
            mimeType = "application/zip",
            bytes = export.packageBytes
        )
        writeFile(
            dirUri = dirUri,
            fileName = export.manifestFileName,
            mimeType = "application/json",
            bytes = export.manifestJson.toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * 在 SAF 目录中创建并写入单个文件。
     *
     * 同名文件可能被不同系统文件管理器以不同策略处理；这里先尝试删除旧文件，再创建新文件，减少用户重复备份时
     * 出现“同名 (1)”文件或旧尾部字节残留的概率。
     */
    private fun writeFile(dirUri: Uri, fileName: String, mimeType: String, bytes: ByteArray) {
        val fileUri = createOrReplaceFile(dirUri, fileName, mimeType)
        appContext.contentResolver.openOutputStream(fileUri, "wt")?.use { output ->
            output.write(bytes)
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
}
