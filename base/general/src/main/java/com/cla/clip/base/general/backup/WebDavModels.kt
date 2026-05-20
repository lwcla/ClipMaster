package com.cla.clip.base.general.backup

import java.net.URI

/** WebDAV 默认远端目录；普通用户无需理解路径即可直接使用。 */
const val DEFAULT_WEBDAV_BACKUP_DIR = "/ClipMaster/backups/"

/**
 * WebDAV 配置。
 *
 * 密码只保存在本机配置，不进入 `BackupSnapshot`；UI 保存前需要提示 HTTP 明文风险。
 */
data class WebDavConfig(
    /** WebDAV 服务根地址，例如 `https://example.com/dav`。 */
    val endpoint: String,
    /** 用户名，可能为空，取决于服务端是否允许匿名访问。 */
    val username: String,
    /** 密码或应用专用密码；不会进入备份包。 */
    val password: String,
    /** 用户配置的远端目录，保存前会规范化。 */
    val remoteDir: String = DEFAULT_WEBDAV_BACKUP_DIR,
    /** 是否允许 HTTP；默认 false，避免明文传输剪贴内容。 */
    val allowInsecureHttp: Boolean = false,
)

/**
 * WebDAV 远端备份文件信息。
 *
 * 列表优先来自 manifest sidecar；manifest 不可用时可以只展示文件名和服务器修改时间兜底。
 */
data class RemoteBackupFile(
    /** 快照文件名。 */
    val fileName: String,
    /** 完整远端路径。 */
    val path: String,
    /** 文件大小，未知时为 null。 */
    val size: Long?,
    /** 服务端 Last-Modified，未知时为 null。 */
    val lastModified: Long?,
    /** 对应 manifest，损坏或缺失时为 null。 */
    val manifest: BackupManifest?,
) {
    /** 列表排序使用的创建时间：manifest 优先，缺失时从标准备份文件名解析时间戳。 */
    val sortCreatedAt: Long
        get() = manifest?.createdAt ?: parseBackupTimestampFromFileName(fileName) ?: 0L

    /** 列表展示使用的有效类型：manifest 优先，缺失时通过安全快照文件名兜底识别。 */
    val effectiveBackupKind: BackupKind?
        get() = manifest?.backupKind ?: parseBackupKindFromFileName(fileName)
}

/**
 * 规范化 WebDAV 远端目录。
 *
 * 规则：空路径回退默认值，自动补前导和末尾 `/`，合并重复 `/`，禁止 `..` 防止越权写到用户未预期目录。
 */
fun normalizeWebDavRemoteDir(input: String?): String {
    val raw = input?.trim().orEmpty().ifBlank { DEFAULT_WEBDAV_BACKUP_DIR }
    val normalized = raw
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotBlank() }
    if (normalized.any { it == ".." }) {
        throw IllegalArgumentException("remote_dir_parent_segment_not_allowed")
    }
    if (normalized.isEmpty()) return DEFAULT_WEBDAV_BACKUP_DIR
    return normalized.joinToString(prefix = "/", postfix = "/", separator = "/")
}

/**
 * 校验 WebDAV 端点协议。
 *
 * 默认只接受 HTTPS；HTTP 需要用户显式开启高级/调试选项，避免明文传输备份里的剪贴内容。
 */
fun validateWebDavEndpoint(endpoint: String, allowInsecureHttp: Boolean): URI {
    val uri = runCatching { URI(endpoint.trim()) }.getOrElse { throw IllegalArgumentException("invalid_webdav_endpoint") }
    val scheme = uri.scheme?.lowercase()
    if (scheme != "https" && !(allowInsecureHttp && scheme == "http")) {
        throw IllegalArgumentException("webdav_https_required")
    }
    if (uri.host.isNullOrBlank()) {
        throw IllegalArgumentException("invalid_webdav_endpoint")
    }
    return uri
}

/** 拼接 WebDAV 根地址与规范化远端目录/文件名，避免调用方手写路径规则。 */
fun buildWebDavUrl(config: WebDavConfig, fileName: String? = null): String {
    val endpoint = config.endpoint.trim().trimEnd('/')
    val remoteDir = normalizeWebDavRemoteDir(config.remoteDir).trim('/')
    val suffix = fileName?.trim('/')?.takeIf { it.isNotBlank() }
    return buildString {
        append(endpoint)
        append('/')
        append(remoteDir)
        append('/')
        if (suffix != null) append(suffix)
    }
}
