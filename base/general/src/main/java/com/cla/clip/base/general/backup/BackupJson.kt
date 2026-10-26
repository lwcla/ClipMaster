package com.cla.clip.base.general.backup

import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.ParseException
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 备份 JSON 编解码入口。
 *
 * 备份包属于跨安装外部协议，统一使用这里的 Json 配置可以避免不同调用方因为 prettyPrint、默认值或未知字段处理差异
 * 导致 checksum 不一致。`ignoreUnknownKeys` 用于未来字段向前兼容，`encodeDefaults` 用于让协议字段显式落盘。
 */
object BackupJson {
    /** 完整备份包和 manifest 共用的 JSON 配置。 */
    val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
        explicitNulls = false
    }

    /** 编码设置数据文件。 */
    fun encodeSettings(value: BackupSettings): String = json.encodeToString(value)

    /** 解码设置数据文件。 */
    fun decodeSettings(text: String): BackupSettings = json.decodeFromString(text)

    /** 编码 manifest sidecar。 */
    fun encodeManifest(manifest: BackupManifest): String {
        return json.encodeToString(manifest)
    }

    /** 解码 manifest sidecar。 */
    fun decodeManifest(text: String): BackupManifest {
        return json.decodeFromString(text)
    }

    /** 编码最近一次自动备份成功摘要，用于本机配置展示。 */
    fun encodeSuccessSummary(summary: BackupSuccessSummary): String {
        return json.encodeToString(summary)
    }

    /** 解码最近一次自动备份成功摘要；失败时由调用方决定回退为空。 */
    fun decodeSuccessSummary(text: String): BackupSuccessSummary {
        return json.decodeFromString(text)
    }
}

/**
 * 计算 SHA-256 十六进制摘要。
 *
 * 备份中只保存摘要字符串，不保存用户内容；日志和 UI 也只展示校验是否通过，不展示原文。
 */
fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

/** 生成脱敏安装标识，避免文件名或备份列表直接暴露真实设备名。 */
fun buildBackupDeviceLabel(pid: String): String {
    val shortId = pid.filter { it.isLetterOrDigit() }.take(8).ifBlank { "unknown" }
    return "install-$shortId"
}

/** 生成备份文件名时间戳，使用纯数字格式避免 WebDAV 路径转义复杂化。 */
fun buildBackupTimestamp(createdAt: Long): String {
    val formatter = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
    formatter.timeZone = java.util.TimeZone.getDefault()
    return formatter.format(java.util.Date(createdAt))
}

/** 生成本应用备份快照文件名；新流程只生成手动或自动普通备份，`Safety` 分支仅兼容旧版调用。 */
fun buildBackupFileName(deviceLabel: String, createdAt: Long, backupKind: BackupKind = BackupKind.Manual): String {
    val safeDevice = deviceLabel.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val kindSegment = if (backupKind == BackupKind.Safety) "_safety" else ""
    return "clip_master_backup_${safeDevice}${kindSegment}_${buildBackupTimestamp(createdAt)}.zip"
}

/** 从备份文件名解析创建时间；manifest 缺失时用于列表排序和展示兜底。 */
fun parseBackupTimestampFromFileName(fileName: String): Long? {
    val timestamp = Regex("""_(\d{8}_\d{6})\.zip$""")
        .find(fileName)
        ?.groupValues
        ?.getOrNull(1)
        ?: return null
    return try {
        java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getDefault()
            isLenient = false
        }.parse(timestamp)?.time
    } catch (_: ParseException) {
        null
    }
}

/** 从标准备份文件名解析备份类型；manifest 缺失时用于识别并隐藏旧版 safety 文件。 */
fun parseBackupKindFromFileName(fileName: String): BackupKind? {
    return if (Regex("""^clip_master_backup_.+_safety_\d{8}_\d{6}\.zip$""").matches(fileName)) {
        BackupKind.Safety
    } else {
        null
    }
}

/** 根据触发来源推导默认备份类型；新流程不再创建恢复前 safety 文件。 */
fun BackupSource.defaultBackupKind(): BackupKind {
    return when (this) {
        BackupSource.LocalManual,
        BackupSource.WebDavManual -> BackupKind.Manual
        BackupSource.LocalAuto,
        BackupSource.WebDavAuto -> BackupKind.Auto
    }
}

/** 生成 manifest sidecar 文件名。 */
fun buildManifestFileName(snapshotFileName: String): String {
    return "$snapshotFileName.manifest.json"
}

/** zip 包内 manifest 固定路径。 */
const val PACKAGE_MANIFEST_PATH = "manifest.json"

/** 剪贴记录的 JSONL 路径。 */
const val CLIPS_JSONL_PATH = "data/clips.jsonl"

/** 来源 App 缓存的 JSONL 路径。 */
const val SOURCE_APPS_JSONL_PATH = "data/source_apps.jsonl"

/** 链接预览缓存的 JSONL 路径。 */
const val LINK_PREVIEWS_JSONL_PATH = "data/link_previews.jsonl"

/** 搜索历史的 JSONL 路径。 */
const val SEARCH_HISTORIES_JSONL_PATH = "data/search_histories.jsonl"

/** 可跨安装恢复的设置白名单路径。 */
const val SETTINGS_PATH = "data/settings.json"

/** 格式 5 的宿主必需文件；功能模块自行声明条目，下载运行态不进入该集合。 */
val RequiredJsonlDataPaths = listOf(
    CLIPS_JSONL_PATH,
    SOURCE_APPS_JSONL_PATH,
    LINK_PREVIEWS_JSONL_PATH,
    SEARCH_HISTORIES_JSONL_PATH,
    SETTINGS_PATH,
)

/** 根据 manifest 文件清单生成汇总 checksum；v2 文件型导出和导入校验共用该规则。 */
fun List<BackupPackageFile>.calculateManifestChecksum(): String {
    return sortedBy { it.path }
        .joinToString(separator = "\n") { file -> "${file.path}:${file.size}:${file.checksum}" }
        .sha256Hex()
}

/** 编码单条 JSONL 记录，不附加换行，调用方负责按 LF 写入。 */
fun <T> BackupJson.encodeJsonLine(serializer: KSerializer<T>, value: T): String {
    return json.encodeToString(serializer, value)
}

/** 解码单条 JSONL 记录，空行和解析失败都视为备份损坏。 */
fun <T> BackupJson.decodeJsonLine(serializer: KSerializer<T>, line: String): T {
    if (line.isBlank()) throw BackupFailure.ParseFailed()
    return runCatching { json.decodeFromString(serializer, line) }
        .getOrElse { throw BackupFailure.ParseFailed(it) }
}

/** 计算文件 SHA-256 十六进制摘要，按字节流处理避免大文件读入内存。 */
fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}

/** 从文件型 zip 备份包读取 manifest，不读取业务 entry 内容。 */
fun File.readBackupManifestFromZip(): BackupManifest {
    return runCatching {
        ZipFile(this).use { zip ->
            val entry = zip.getEntry(PACKAGE_MANIFEST_PATH) ?: throw BackupFailure.InvalidFormat()
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
                BackupJson.decodeManifest(reader.readText())
            }
        }
    }.getOrElse { throwable ->
        if (throwable is BackupFailure) throw throwable else throw BackupFailure.ParseFailed(throwable)
    }
}

/** 校验文件型 zip 备份包，并返回 manifest；业务内容按 entry 流式读取，不整体加载到内存。 */
fun File.validateBackupPackageFile(expectedApplicationId: String): BackupManifest {
    // 从 ZIP 读取协议，版本检查先于所有业务读取与写库。
    val manifest = readBackupManifestFromZip()
    manifest.validatePackageMetadata()
    if (manifest.applicationId != expectedApplicationId) throw BackupFailure.AppMismatch()
    // 只接受当前格式的宿主文件，模块额外条目照常参与完整性校验。
    val requiredPaths = RequiredJsonlDataPaths
    // 按路径定位 manifest 中的文件完整性声明。
    val files = manifest.files.associateBy { it.path }
    if (manifest.files.any { !it.path.isSafePackagePath() }) throw BackupFailure.InvalidFormat()
    runCatching {
        ZipFile(this).use { zip ->
            // 遍历全部 ZIP 路径，额外模块条目也不得越界。
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                // 当前 ZIP 文件条目，只读取字节流，不解压到外部路径。
                val entry = entries.nextElement()
                if (!entry.isDirectory && !entry.name.isSafePackagePath()) throw BackupFailure.InvalidFormat()
            }
            if (!files.keys.containsAll(requiredPaths)) throw BackupFailure.ChecksumMismatch()
            files.values.forEach { expected ->
                // 待校验的相对文件路径。
                val path = expected.path
                // 当前 ZIP 文件条目，只读取字节流，不解压到外部路径。
                val entry = zip.getEntry(path) ?: throw BackupFailure.ChecksumMismatch()
                // 当前文件的增量 SHA-256，避免一次性读入内存。
                val tempDigest = MessageDigest.getInstance("SHA-256")
                // 实际读取字节数，用于发现截断或替换。
                var total = 0L
                zip.getInputStream(entry).use { input ->
                    // 固定大小缓冲区，不随备份数据量增长。
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        // 当前读取长度，负一表示文件结束。
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        tempDigest.update(buffer, 0, read)
                    }
                }
                if (total != expected.size) throw BackupFailure.ChecksumMismatch()
                // 实际字节摘要，必须与 manifest 完全一致。
                val actual = tempDigest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
                if (actual != expected.checksum) throw BackupFailure.ChecksumMismatch()
            }
        }
    }.getOrElse { throwable ->
        if (throwable is BackupFailure) throw throwable else throw BackupFailure.ParseFailed(throwable)
    }
    if (manifest.files.calculateManifestChecksum() != manifest.checksum) {
        throw BackupFailure.ChecksumMismatch()
    }
    return manifest
}

/** 包内路径只能是相对路径，防止未来支持解压到目录时出现 zip slip 风险。 */
private fun String.isSafePackagePath(): Boolean {
    return isNotBlank() && !startsWith("/") && !contains("\\") && split('/').none { it == ".." || it.isBlank() }
}

/** 恢复入口只接受当前版本及 JSONL；开发阶段不保留旧格式兼容分支。 */
fun BackupManifest.validatePackageMetadata() {
    if (format != BACKUP_FORMAT) throw BackupFailure.InvalidFormat()
    if (schemaVersion != BACKUP_SCHEMA_VERSION) throw BackupFailure.UnsupportedSchema()
    if (dataFormat != BACKUP_DATA_FORMAT_JSONL) throw BackupFailure.InvalidFormat()
    if (encryption != BACKUP_ENCRYPTION_NONE || compression != BACKUP_COMPRESSION_ZIP) throw BackupFailure.InvalidFormat()
}
