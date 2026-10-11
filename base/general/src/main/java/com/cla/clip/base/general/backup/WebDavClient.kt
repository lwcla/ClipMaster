package com.cla.clip.base.general.backup

import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logW
import com.cla.clip.base.general.backup.webdav.WebDavCallExecutor
import com.cla.clip.base.general.backup.webdav.WebDavHttpFailure
import com.cla.clip.base.general.backup.webdav.webDavEvidence
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
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
 * `WebDavConfig`/`buildWebDavUrl` 完成，日志中只记录低敏身份和状态码，不输出 URL、账号、密码或剪贴内容。
 */
@Singleton
class WebDavClient @Inject constructor(
    /** 复用应用已有 OkHttpClient，避免为 WebDAV 再引入额外网络栈。 */
    okHttpClient: OkHttpClient,
) {
    /** 复用连接池及超时，禁止自动重发；去掉完整 URL 日志，诊断使用本域的低敏事件。 */
    private val okHttpClient = okHttpClient.newBuilder().apply {
        retryOnConnectionFailure(false)
        interceptors().removeAll { it is HttpLoggingInterceptor }
        networkInterceptors().removeAll { it is HttpLoggingInterceptor }
    }.build()
    /** HTTP 生命周期下沉，协程停止可立即取消底层阻塞 IO。 */
    private val calls = WebDavCallExecutor(this.okHttpClient)
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
        // 连接测试允许父级在查询后被其他客户端创建，继续准备子目录；最终探测才确认可写。
        ensureDirectory(config, continueAfterMappedResource = true)
        /** 探测仍保留独立小文件，与正式 ZIP 上传路径无关。 */
        val probeName = ".clip_master_probe.json"
        uploadText(config, probeName, "{}")
        deleteFile(config, probeName)
        logD(TAG) { "WebDAV 测试和目录准备成功 ${backupTaskLogField(taskId)}remoteDirLength=${normalizeWebDavRemoteDir(config.remoteDir).length}" }
    }

    /** 上传文本文件。 */
    suspend fun uploadText(config: WebDavConfig, fileName: String, text: String) = withContext(Dispatchers.IO) {
        /** 一次性正文阻止 503/408 等协议跟进重发；失败由本轮结果记录，不后台补传。 */
        val request = baseRequest(config, buildWebDavUrl(config, fileName))
            .put(text.toRequestBody(JsonMediaType).asSingleAttemptRequestBody())
            .build()
        calls.execute("upload_text", request) { response -> requireSuccess(response.code, response.isSuccessful, "upload_text") }
    }

    /** 下载远端备份包到私有临时文件，避免大文件进入内存。 */
    suspend fun downloadFile(config: WebDavConfig, fileName: String, targetFile: File): File = withContext(Dispatchers.IO) {
        /** 仅下载选中的完整 ZIP，不读取外部摘要。 */
        val request = baseRequest(config, buildWebDavUrl(config, fileName)).get().build()
        calls.execute("download_file", request) { response ->
                requireSuccess(response.code, response.isSuccessful, "download_file")
                targetFile.outputStream().use { output ->
                    response.body.byteStream().use { input -> input.copyTo(output) }
                }
        }
        targetFile
    }

    /** 删除远端文件；404 视为已经不存在，不作为失败。 */
    suspend fun deleteFile(config: WebDavConfig, fileName: String) = withContext(Dispatchers.IO) {
        /** 删除的缺失响应幂等成功，其他状态保留实际 HTTP 原因。 */
        val request = baseRequest(config, buildWebDavUrl(config, fileName))
            .delete()
            .build()
        calls.execute("delete_file", request) { response ->
            if (response.code != 404) requireSuccess(response.code, response.isSuccessful, "delete_file")
        }
    }

    /**
     * 列举远端备份。
     *
     * 只读取目录元数据，不逐包 GET 摘要；恢复时继续下载 ZIP 并执行包内完整预检。
     */
    suspend fun listBackups(config: WebDavConfig): List<RemoteBackupFile> = withContext(Dispatchers.IO) {
        validateWebDavEndpoint(config.endpoint, config.allowInsecureHttp)
        logD(TAG) { "开始读取 WebDAV 备份列表 remoteDirLength=${config.remoteDir.length}" }
        /** 标准前缀和 ZIP 后缀过滤保持既有兼容范围。 */
        val entries = propfind(config, buildWebDavUrl(config))
            .filter { it.fileName.endsWith(".zip") && it.fileName.startsWith("clip_master_backup_") }
        /** 时间来自标准文件名，修改时间和文件名作为稳定兜底。 */
        val files = entries.map { entry ->
            RemoteBackupFile(
                fileName = entry.fileName,
                path = entry.path,
                size = entry.size,
                lastModified = entry.lastModified,
            )
        }.sortedWith(
            compareByDescending<RemoteBackupFile> { it.sortCreatedAt }
                .thenByDescending { it.lastModified ?: 0L }
                .thenByDescending { it.fileName }
        )
        logD(TAG) { "WebDAV 备份列表读取成功 count=${files.size}" }
        files
    }

    /** 先直接 PUT ZIP；404/409 共用一次任务许可，仅实际创建缺失目录后补传同包，不发布外部摘要。 */
    suspend fun uploadBackup(config: WebDavConfig, export: BackupExportResult, taskId: String? = null,
        onStage: (String) -> Unit = {}, claimDirectoryRecovery: suspend () -> Boolean = { true }) = withContext(Dispatchers.IO) {
        /** 原有成功日志的墙钟开始时间；Worker 实际运行耗时另用单调时钟记录。 */
        val startedAt = System.currentTimeMillis()
        logD(TAG) {
            "开始上传 WebDAV 备份 ${backupTaskLogField(taskId)}fileName=${export.fileName} " +
                "backupKind=${export.manifest.backupKind.logCode()} fileSize=${export.fileSize}"
        }
        validateWebDavEndpoint(config.endpoint, config.allowInsecureHttp)
        requireReadablePackage(export.packageFile)
        notifyUploadStage(onStage, "publishing_remote_zip")
        try { uploadFile(config, export.fileName, export.packageFile) }
        catch (failure: BackupFailure.RemoteFailed) {
            currentCoroutineContext().ensureActive()
            /** 只依赖类型化 PUT 状态，不能从异常文字推断目录缺失。 */
            val http = failure.webDavEvidence() as? WebDavHttpFailure
            if (http?.statusCode !in setOf(404, 409)) throw failure
            if (!claimDirectoryRecovery()) {
                logW(TAG) { "目录修复许可不可用 ${backupTaskLogField(taskId)}reasonCode=directory_recovery_already_attempted statusCode=${http?.statusCode}" }
                throw failure
            }
            notifyUploadStage(onStage, "repairing_remote_directory")
            logD(TAG) { "开始唯一目录修复 ${backupTaskLogField(taskId)}repairCount=1 statusCode=${http?.statusCode}" }
            if (!ensureDirectory(config)) throw failure
            currentCoroutineContext().ensureActive()
            requireReadablePackage(export.packageFile)
            notifyUploadStage(onStage, "republishing_remote_zip")
            // 补传位于首次捕获分支内，不再次捕获为修复入口；404/409 也直接传播。
            uploadFile(config, export.fileName, export.packageFile)
        }
        currentCoroutineContext().ensureActive()
        notifyUploadStage(onStage, "remote_files_published")
        logD(TAG) {
            "WebDAV 备份上传成功 ${backupTaskLogField(taskId)}fileName=${export.fileName} " +
                "durationMs=${System.currentTimeMillis() - startedAt}"
        }
    }

    /** 固定阶段观察为尽力诊断；回调异常只给出固定警告，不改变网络请求或备份结果。 */
    private fun notifyUploadStage(observer: (String) -> Unit, stage: String) {
        try { observer(stage) }
        catch (_: Exception) { logW(TAG) { "上传阶段诊断失败 reasonCode=diagnostic_callback_failed" } }
    }

    /**
     * 按保留份数清理远端普通备份。
     *
     * 清理只作用于标准文件名可识别的普通 ZIP；历史 safety 和其它不可识别文件不删除。
     * 不删除历史外部摘要。调用方必须在新备份发布成功后再调用该方法，
     * 保证清理不会早于新恢复点落盘。
     */
    suspend fun pruneBackups(
        config: WebDavConfig,
        keepCount: Int,
        taskId: String? = null,
    ): BackupRetentionCleanupResult = withContext(Dispatchers.IO) {
        /** 仅保留安全可识别的普通 ZIP 候选。 */
        val candidates = listBackups(config)
            .filter { file -> file.isRegularBackup }
            .sortedWith(
                compareByDescending<RemoteBackupFile> { it.sortCreatedAt }
                    .thenByDescending { it.lastModified ?: 0L }
                    .thenByDescending { it.fileName }
            )
        /** 本次只删除超出保留上限的 ZIP，不扩大到其他文件。 */
        val toDelete = candidates.drop(keepCount.coerceAtLeast(0))
        logD(TAG) {
            "开始清理 WebDAV 旧备份 ${backupTaskLogField(taskId)}target=webdav keepCount=$keepCount " +
                "candidates=${candidates.size} toDelete=${toDelete.size}"
        }
        /** 只计真正删除或已缺失的 ZIP。 */
        var deleted = 0
        toDelete.forEach { file ->
            runCatching {
                deleteFile(config, file.fileName)
                deleted += 1
                logD(TAG) { "WebDAV 旧备份删除成功 ${backupTaskLogField(taskId)}target=webdav fileName=${file.fileName}" }
            }.onFailure { throwable ->
                if (throwable is kotlinx.coroutines.CancellationException) throw throwable
                logE(TAG) {
                    "WebDAV 旧备份删除失败 ${backupTaskLogField(taskId)}target=webdav fileName=${file.fileName} " +
                        "reasonCode=${throwable.backupReasonCode()} type=${throwable::class.simpleName}"
                }
            }
        }
        logD(TAG) {
            "WebDAV 旧备份清理完成 ${backupTaskLogField(taskId)}target=webdav deleted=$deleted"
        }
        BackupRetentionCleanupResult(deletedCount = deleted, deletedKinds = emptyList())
    }

    /**
     * 逐级准备缺失目录，返回本请求是否实际创建；完整目录或未完成修复返回 false。
     * continueAfterMappedResource 仅供连接测试继续准备子目录，405 不计作创建且最终由探测验证可写；
     * 正式上传保持默认 false，任一层 405 立即结束，禁止用父级进展允许无进展补传。
     */
    private suspend fun ensureDirectory(config: WebDavConfig, continueAfterMappedResource: Boolean = false): Boolean {
        validateWebDavEndpoint(config.endpoint, config.allowInsecureHttp)
        /** 配置目录只有明确缺失才进入逐级检查。 */
        val remoteDir = normalizeWebDavRemoteDir(config.remoteDir)
        if (webDavDirectoryExists(config, buildWebDavUrl(config))) return false
        /** 只遍历受控路径片段，不越过配置 endpoint。 */
        val segments = remoteDir.trim('/').split('/').filter { it.isNotBlank() }
        /** 逐级累计路径，避免直接 MKCOL 多级缺失父目录。 */
        var partial = ""
        /** 至少成功创建一层才允许补传，避免目录完整时无进展重发。 */
        var created = false
        segments.forEach { segment ->
            partial += "/$segment/"
            /** 当前父级配置及地址只用于请求，不进入诊断日志。 */
            val partialConfig = config.copy(remoteDir = partial)
            val url = buildWebDavUrl(partialConfig)
            if (!webDavDirectoryExists(config, url)) {
                logD(TAG) { "WebDAV 目录不存在，开始创建 segmentLength=${segment.length} partialLength=${partial.length}" }
                /** 当前层是否由本请求实际创建，405 只表示资源已映射，不能计作修复进展。 */
                val createdCurrent = mkcol(config, url)
                // 上传任一层未创建即结束；连接测试继续准备子层，由后续操作或最终探测拒绝不可写资源。
                if (!createdCurrent && !continueAfterMappedResource) return false
                created = created || createdCurrent
            }
        }
        return created
    }

    /**
     * 检查目录是否存在。
     *
     * 只有服务端明确返回 404 时才认为目录缺失；TLS、认证、跳转失败或服务端错误都直接上抛，避免把连接问题误判为
     * “目录不存在”并继续发起 MKCOL。
     */
    private suspend fun webDavDirectoryExists(config: WebDavConfig, url: String): Boolean {
        /** Depth 0 只判断配置目录，不额外列举包。 */
        val request = propfindRequest(config, url, depth = "0")
        return calls.execute("directory_exists", request) { response ->
                when {
                    response.code == 401 || response.code == 403 -> throw BackupFailure.AuthenticationFailed()
                    response.code == 404 -> false
                    response.isSuccessful || response.code == 207 -> true
                    else -> {
                        logW(TAG) { "WebDAV 目录检查失败 statusCode=${response.code} reasonCode=remote_failed" }
                        throw BackupFailure.RemoteFailed(WebDavHttpFailure("directory_exists", response.code))
                    }
                }
        }
    }

    /** 返回本请求是否成功创建目录；405 仅表示资源已映射，不算实际修复进展。 */
    private suspend fun mkcol(config: WebDavConfig, url: String): Boolean {
        /** 连接探测兼容 405，但正式上传不能仅凭该状态盲目补传。 */
        val request = baseRequest(config, url)
            .method("MKCOL", null)
            .build()
        return calls.execute("mkcol", request) { response ->
                if (response.code == 401 || response.code == 403) throw BackupFailure.AuthenticationFailed()
                if (!response.isSuccessful && response.code != 201 && response.code != 405) {
                    logW(TAG) { "WebDAV 目录创建失败 statusCode=${response.code} reasonCode=webdav_directory_create_failed" }
                    throw BackupFailure.RemoteFailed(WebDavHttpFailure("mkcol", response.code))
                }
                response.isSuccessful
        }
    }

    /** 执行 PROPFIND 并解析目录项。 */
    private suspend fun propfind(config: WebDavConfig, url: String): List<WebDavEntry> {
        /** Depth 1 一次取得目录元数据；不读取外部 manifest。 */
        val request = propfindRequest(config, url, depth = "1")
        return calls.execute("propfind", request) { response ->
                requireSuccess(response.code, response.isSuccessful, "propfind")
                parsePropfind(response.body.string())
        }
    }

    /** 固定状态分类用于全部 WebDAV 请求，认证拒绝优先，其他保留低敏 HTTP 证据。 */
    private fun requireSuccess(status: Int, successful: Boolean, operation: String) {
        if (status == 401 || status == 403) throw BackupFailure.AuthenticationFailed()
        if (!successful) throw BackupFailure.RemoteFailed(WebDavHttpFailure(operation, status))
    }

    /** 本地非空检查不访问远端；包被新轮次删除时中止，不能切换包或重新导出。 */
    private fun requireReadablePackage(file: File) {
        if (!file.isFile || !file.canRead() || file.length() <= 0L) throw BackupFailure.TempFileUnavailable()
    }

    /** 构造 PROPFIND 请求；每次新建请求体，避免复用已消费的 RequestBody。 */
    private fun propfindRequest(config: WebDavConfig, url: String, depth: String): Request {
        /** 固定协议属性只读目录元数据，服务端正文不进入日志。 */
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
        return baseRequest(config, url)
            .method("PROPFIND", body)
            .header("Depth", depth)
            .build()
    }

    /** 为 WebDAV 请求添加认证头。 */
    private fun baseRequest(config: WebDavConfig, url: String): Request.Builder {
        /** 认证仅随请求在内存使用，不进入 WorkData、文件描述或日志。 */
        val builder = Request.Builder().url(url)
        if (config.username.isNotBlank() || config.password.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(config.username, config.password))
        }
        return builder
    }

    /** 上传本地文件并提供准确 Content-Length，提升 WebDAV 大文件 PUT 兼容性。 */
    private suspend fun uploadFile(config: WebDavConfig, fileName: String, file: File) = withContext(Dispatchers.IO) {
        /** 正式名称及一次性请求体固定，禁止底层协议跟进重复发送。 */
        val request = baseRequest(config, buildWebDavUrl(config, fileName))
            .put(file.asRequestBody(ZipMediaType))
            .build()
        calls.execute("upload_file", request) { response -> requireSuccess(response.code, response.isSuccessful, "upload_file") }
    }

    /** 解析目录中的文件；排除集合及目录路径，避免把同名目录当作 ZIP 递归清理。 */
    private fun parsePropfind(xml: String): List<WebDavEntry> {
        return runCatching {
            /** 命名空间感知的既有 XML 解析器。 */
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }
            /** 响应正文仅在内存解析，不进入日志。 */
            val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            /** 一个响应对应一个服务端资源。 */
            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            buildList {
                // index 只遍历本次响应，不作为文件身份。
                for (index in 0 until responses.length) {
                    /** 兼容服务端 XML 节点类型差异。 */
                    val element = responses.item(index) as? Element ?: continue
                    /** 完整 href 仅用于资源过滤和内存模型，不写入日志。 */
                    val href = element.firstText("href") ?: continue
                    if (href.endsWith('/') || element.getElementsByTagNameNS("DAV:", "collection").length > 0 ||
                        element.getElementsByTagName("collection").length > 0) continue
                    /** 名称沿用标准 ZIP 识别协议。 */
                    val fileName = href.substringAfterLast('/')
                    if (fileName.isBlank()) continue
                    /** 缺失或非法大小保持未知，不猜测正文长度。 */
                    val size = element.firstText("getcontentlength")?.toLongOrNull()
                    /** 修改时间只供标准文件名解析失败时展示兜底。 */
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

/** 文件 RequestBody，显式返回 contentLength，避免 WebDAV 服务端拒绝未知长度 chunked PUT。 */
private fun File.asRequestBody(mediaType: MediaType): RequestBody {
    return object : RequestBody() {
        /** ZIP MIME 保持既有服务器协议。 */
        override fun contentType(): MediaType = mediaType

        /** 提前返回实际字节数，避免 chunked 请求被部分服务端拒绝。 */
        override fun contentLength(): Long = length()

        /** 正式 ZIP 不能因 503 Retry-After: 0 或其他跟进响应再次发送。 */
        override fun isOneShot(): Boolean = true

        /** 流式发送固定文件；旧轮次文件被删除时显式失败，不切换成新包。 */
        override fun writeTo(sink: okio.BufferedSink) {
            inputStream().use { input ->
                sink.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}

/** 文本 PUT 也限定单次尝试，复用原正文的 MIME、长度和字节，不修改 manifest 协议。 */
private fun RequestBody.asSingleAttemptRequestBody(): RequestBody = object : RequestBody() {
    /** MIME 与原正文一致。 */
    override fun contentType(): MediaType? = this@asSingleAttemptRequestBody.contentType()
    /** 保留显式正文长度，禁止改为未知长度请求。 */
    override fun contentLength(): Long = this@asSingleAttemptRequestBody.contentLength()
    /** OkHttp 跟进层不能再次发送此正文。 */
    override fun isOneShot(): Boolean = true
    /** 直接委托原正文写入，不复制或重编码外部字段。 */
    override fun writeTo(sink: okio.BufferedSink) = this@asSingleAttemptRequestBody.writeTo(sink)
}
