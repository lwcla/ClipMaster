package com.cla.clip.base.general.backup

import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logW
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 轻量 WebDAV 客户端。
 *
 * 只实现备份恢复需要的 PROPFIND、MKCOL、PUT、GET、DELETE；路径规范化和 HTTPS 校验由调用方通过
 * `WebDavConfig`/`buildWebDavUrl` 完成，日志中只记录文件名和状态码，不输出账号、密码或剪贴内容。
 */
@Singleton
class WebDavClient @Inject constructor(
    /** 复用应用已有 OkHttpClient，避免为 WebDAV 再引入额外网络栈。 */
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        /** 日志标签。 */
        private const val TAG = "WebDavClient"

        /** WebDAV PROPFIND XML 请求体 MIME 类型。 */
        private val XmlMediaType = "application/xml; charset=utf-8".toMediaType()

        /** JSON 文件上传 MIME 类型。 */
        private val JsonMediaType = "application/json; charset=utf-8".toMediaType()

        /** zip 备份包上传 MIME 类型。 */
        private val ZipMediaType = "application/zip".toMediaType()
    }

    /**
     * 测试连接并确保远端目录可写。
     *
     * 目录不存在时会逐级 MKCOL 创建；创建后上传并删除一个空探测文件，确认用户有写权限。
     */
    suspend fun testAndPrepare(config: WebDavConfig, taskId: String? = null) = withContext(Dispatchers.IO) {
        validateWebDavEndpoint(config.endpoint, config.allowInsecureHttp)
        logD(TAG) {
            "开始测试 WebDAV 并准备目录 ${backupTaskLogField(taskId)}allowHttp=${config.allowInsecureHttp} " +
                "hasUsername=${config.username.isNotBlank()} remoteDirLength=${config.remoteDir.length}"
        }
        ensureDirectory(config)
        val probeName = ".clip_master_probe.json"
        uploadText(config, probeName, "{}")
        deleteFile(config, probeName)
        logD(TAG) { "WebDAV 测试和目录准备成功 ${backupTaskLogField(taskId)}remoteDirLength=${normalizeWebDavRemoteDir(config.remoteDir).length}" }
    }

    /** 上传文本文件。 */
    suspend fun uploadText(config: WebDavConfig, fileName: String, text: String) = withContext(Dispatchers.IO) {
        val request = baseRequest(config, buildWebDavUrl(config, fileName))
            .put(text.toRequestBody(JsonMediaType))
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw BackupFailure.AuthenticationFailed()
            if (!response.isSuccessful && response.code != 201 && response.code != 204) {
                throw BackupFailure.StorageNotWritable()
            }
        }
    }

    /** 上传二进制备份包。 */
    suspend fun uploadBytes(config: WebDavConfig, fileName: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val request = baseRequest(config, buildWebDavUrl(config, fileName))
            .put(bytes.toRequestBody(ZipMediaType))
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw BackupFailure.AuthenticationFailed()
            if (!response.isSuccessful && response.code != 201 && response.code != 204) {
                throw BackupFailure.StorageNotWritable()
            }
        }
    }

    /** 下载文本文件。 */
    suspend fun downloadText(config: WebDavConfig, fileName: String): String = withContext(Dispatchers.IO) {
        val request = baseRequest(config, buildWebDavUrl(config, fileName)).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw BackupFailure.AuthenticationFailed()
            if (!response.isSuccessful) throw BackupFailure.RemoteFailed()
            response.body.string()
        }
    }

    /** 下载二进制备份包。 */
    suspend fun downloadBytes(config: WebDavConfig, fileName: String): ByteArray = withContext(Dispatchers.IO) {
        val request = baseRequest(config, buildWebDavUrl(config, fileName)).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw BackupFailure.AuthenticationFailed()
            if (!response.isSuccessful) throw BackupFailure.RemoteFailed()
            response.body.bytes()
        }
    }

    /** 删除远端文件；404 视为已经不存在，不作为失败。 */
    suspend fun deleteFile(config: WebDavConfig, fileName: String) = withContext(Dispatchers.IO) {
        val request = baseRequest(config, buildWebDavUrl(config, fileName))
            .delete()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext
            if (response.code == 401 || response.code == 403) throw BackupFailure.AuthenticationFailed()
            if (!response.isSuccessful && response.code != 204) throw BackupFailure.RemoteFailed()
        }
    }

    /**
     * 列举远端备份。
     *
     * 先读取目录下文件，再尝试读取 manifest sidecar；manifest 损坏时保留快照文件，让用户仍可进入完整预检。
     */
    suspend fun listBackups(config: WebDavConfig): List<RemoteBackupFile> = withContext(Dispatchers.IO) {
        validateWebDavEndpoint(config.endpoint, config.allowInsecureHttp)
        logD(TAG) { "开始读取 WebDAV 备份列表 remoteDirLength=${config.remoteDir.length}" }
        val entries = propfind(config, buildWebDavUrl(config))
            .filter { it.fileName.endsWith(".zip") && it.fileName.startsWith("clip_master_backup_") }
        var manifestMissingOrBroken = 0
        val files = entries.map { entry ->
            val manifest = runCatching {
                BackupJson.decodeManifest(downloadText(config, buildManifestFileName(entry.fileName)))
            }.onFailure {
                manifestMissingOrBroken += 1
            }.getOrNull()
            RemoteBackupFile(
                fileName = entry.fileName,
                path = entry.path,
                size = entry.size,
                lastModified = entry.lastModified,
                manifest = manifest
            )
        }.sortedWith(
            compareByDescending<RemoteBackupFile> { it.manifest?.createdAt ?: 0L }
                .thenByDescending { it.lastModified ?: 0L }
                .thenByDescending { it.fileName }
        )
        logD(TAG) { "WebDAV 备份列表读取成功 count=${files.size} manifestMissingOrBroken=$manifestMissingOrBroken" }
        files
    }

    /** 上传完整备份包和 manifest，并更新 latest.json。 */
    suspend fun uploadBackup(config: WebDavConfig, export: BackupExportResult, taskId: String? = null) = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        logD(TAG) {
            "开始上传 WebDAV 备份 ${backupTaskLogField(taskId)}fileName=${export.fileName} " +
                "backupKind=${export.snapshot.backupKind.logCode()} fileSize=${export.packageBytes.size}"
        }
        ensureDirectory(config)
        val tempSnapshot = "${export.fileName}.tmp"
        val tempManifest = "${export.manifestFileName}.tmp"
        logD(TAG) { "开始上传 WebDAV 临时备份文件 ${backupTaskLogField(taskId)}fileName=${export.fileName}" }
        uploadBytes(config, tempSnapshot, export.packageBytes)
        uploadText(config, tempManifest, export.manifestJson)
        val uploaded = downloadBytes(config, tempSnapshot)
        uploaded.decodeBackupPackage().validateForRestore(export.snapshot.applicationId)
        logD(TAG) { "WebDAV 临时备份文件校验通过 ${backupTaskLogField(taskId)}fileName=${export.fileName} tempSize=${uploaded.size}" }
        uploadBytes(config, export.fileName, uploaded)
        uploadText(config, export.manifestFileName, export.manifestJson)
        uploadText(config, "latest.json", export.manifestJson)
        deleteFile(config, tempSnapshot)
        deleteFile(config, tempManifest)
        logD(TAG) {
            "WebDAV 备份上传成功 ${backupTaskLogField(taskId)}fileName=${export.fileName} " +
                "durationMs=${System.currentTimeMillis() - startedAt}"
        }
    }

    /**
     * 按保留份数清理远端自动备份。
     *
     * 只删除 manifest 明确标记为指定 `backupKind` 且设备标识匹配的备份，避免多设备共用目录时误删别的恢复点。
     * 调用方必须在新备份发布成功后再调用该方法，保证清理不会早于新恢复点落盘。
     */
    suspend fun pruneBackups(
        config: WebDavConfig,
        keepCount: Int,
        backupKind: BackupKind,
        deviceLabel: String,
        taskId: String? = null,
    ): BackupRetentionCleanupResult = withContext(Dispatchers.IO) {
        val candidates = listBackups(config)
            .filter { file ->
                file.manifest?.backupKind == backupKind && file.manifest.deviceLabel == deviceLabel
            }
            .sortedWith(
                compareByDescending<RemoteBackupFile> { it.manifest?.createdAt ?: 0L }
                    .thenByDescending { it.lastModified ?: 0L }
                    .thenByDescending { it.fileName }
            )
        val toDelete = candidates.drop(keepCount.coerceAtLeast(0))
        logD(TAG) {
            "开始清理 WebDAV 旧备份 ${backupTaskLogField(taskId)}target=webdav backupKind=${backupKind.logCode()} " +
                "keepCount=$keepCount candidates=${candidates.size} toDelete=${toDelete.size}"
        }
        var deleted = 0
        toDelete.forEach { file ->
            runCatching {
                deleteFile(config, file.fileName)
                runCatching { deleteFile(config, buildManifestFileName(file.fileName)) }
                deleted += 1
                logD(TAG) { "WebDAV 旧备份删除成功 ${backupTaskLogField(taskId)}target=webdav fileName=${file.fileName}" }
            }.onFailure { throwable ->
                logE(TAG) {
                    "WebDAV 旧备份删除失败 ${backupTaskLogField(taskId)}target=webdav fileName=${file.fileName} " +
                        "reasonCode=${throwable.backupReasonCode()} type=${throwable::class.simpleName}"
                }
            }
        }
        logD(TAG) {
            "WebDAV 旧备份清理完成 ${backupTaskLogField(taskId)}target=webdav backupKind=${backupKind.logCode()} deleted=$deleted"
        }
        BackupRetentionCleanupResult(deletedCount = deleted, deletedKinds = toDelete.mapNotNull { it.manifest?.backupKind }.distinct())
    }

    /** 确保远端目录存在，按路径片段逐级创建以兼容只支持父目录已存在的 WebDAV 服务端。 */
    private suspend fun ensureDirectory(config: WebDavConfig) {
        validateWebDavEndpoint(config.endpoint, config.allowInsecureHttp)
        val segments = normalizeWebDavRemoteDir(config.remoteDir).trim('/').split('/').filter { it.isNotBlank() }
        var partial = ""
        segments.forEach { segment ->
            partial += "/$segment/"
            val partialConfig = config.copy(remoteDir = partial)
            val url = buildWebDavUrl(partialConfig)
            val exists = runCatching { propfind(config, url) }.isSuccess
            if (!exists) {
                logD(TAG) { "WebDAV 目录不存在，开始创建 segmentLength=${segment.length} partialLength=${partial.length}" }
                mkcol(config, url)
            }
        }
    }

    /** 创建目录；405 通常代表目录已存在，视为成功。 */
    private suspend fun mkcol(config: WebDavConfig, url: String) {
        val request = baseRequest(config, url)
            .method("MKCOL", null)
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw BackupFailure.AuthenticationFailed()
            if (!response.isSuccessful && response.code != 201 && response.code != 405) {
                logW(TAG) { "WebDAV 目录创建失败 statusCode=${response.code} reasonCode=storage_not_writable" }
                throw BackupFailure.StorageNotWritable()
            }
        }
    }

    /** 执行 PROPFIND 并解析目录项。 */
    private fun propfind(config: WebDavConfig, url: String): List<WebDavEntry> {
        val body = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:">
                <d:prop>
                    <d:getcontentlength />
                    <d:getlastmodified />
                    <d:resourcetype />
                </d:prop>
            </d:propfind>
        """.trimIndent().toRequestBody(XmlMediaType)
        val request = baseRequest(config, url)
            .method("PROPFIND", body)
            .header("Depth", "1")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw BackupFailure.AuthenticationFailed()
            if (!response.isSuccessful && response.code != 207) throw BackupFailure.RemoteFailed()
            return parsePropfind(response.body.string())
        }
    }

    /** 为 WebDAV 请求添加认证头。 */
    private fun baseRequest(config: WebDavConfig, url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        if (config.username.isNotBlank() || config.password.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(config.username, config.password))
        }
        return builder
    }

    /** 解析 PROPFIND XML。 */
    private fun parsePropfind(xml: String): List<WebDavEntry> {
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }
            val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            buildList {
                for (index in 0 until responses.length) {
                    val element = responses.item(index) as? Element ?: continue
                    val href = element.firstText("href") ?: continue
                    val fileName = href.trimEnd('/').substringAfterLast('/')
                    if (fileName.isBlank()) continue
                    val size = element.firstText("getcontentlength")?.toLongOrNull()
                    val lastModified = element.firstText("getlastmodified")?.parseHttpDateMillis()
                    add(WebDavEntry(fileName = fileName, path = href, size = size, lastModified = lastModified))
                }
            }
        }.getOrElse { throwable ->
            logE(TAG) { "WebDAV 目录响应解析失败 reasonCode=${throwable.backupReasonCode()} type=${throwable::class.simpleName}" }
            emptyList()
        }
    }

    /** 内部 WebDAV 目录项。 */
    private data class WebDavEntry(
        val fileName: String,
        val path: String,
        val size: Long?,
        val lastModified: Long?,
    )
}

/** 读取指定 XML 子节点文本，兼容 namespace 前缀差异。 */
private fun Element.firstText(localName: String): String? {
    val nsNodes = getElementsByTagNameNS("DAV:", localName)
    if (nsNodes.length > 0) return nsNodes.item(0)?.textContent
    val plainNodes = getElementsByTagName(localName)
    if (plainNodes.length > 0) return plainNodes.item(0)?.textContent
    return null
}

/** 解析 WebDAV Last-Modified HTTP 日期。 */
private fun String.parseHttpDateMillis(): Long? {
    return runCatching {
        val formatter = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("GMT")
        formatter.parse(this)?.time
    }.getOrNull()
}
