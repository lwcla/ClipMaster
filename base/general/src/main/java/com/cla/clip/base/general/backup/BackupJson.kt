package com.cla.clip.base.general.backup

import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.text.ParseException
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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

    /** 编码剪贴记录数据文件。 */
    fun encodeClips(values: List<BackupClip>): String = json.encodeToString(values)

    /** 解码剪贴记录数据文件。 */
    fun decodeClips(text: String): List<BackupClip> = json.decodeFromString(text)

    /** 编码来源 App 数据文件。 */
    fun encodeSourceApps(values: List<BackupSourceApp>): String = json.encodeToString(values)

    /** 解码来源 App 数据文件。 */
    fun decodeSourceApps(text: String): List<BackupSourceApp> = json.decodeFromString(text)

    /** 编码链接预览数据文件。 */
    fun encodeLinkPreviews(values: List<BackupLinkPreview>): String = json.encodeToString(values)

    /** 解码链接预览数据文件。 */
    fun decodeLinkPreviews(text: String): List<BackupLinkPreview> = json.decodeFromString(text)

    /** 编码搜索历史数据文件。 */
    fun encodeSearchHistories(values: List<BackupSearchHistory>): String = json.encodeToString(values)

    /** 解码搜索历史数据文件。 */
    fun decodeSearchHistories(text: String): List<BackupSearchHistory> = json.decodeFromString(text)

    /** 编码设置数据文件。 */
    fun encodeSettings(value: BackupSettings): String = json.encodeToString(value)

    /** 解码设置数据文件。 */
    fun decodeSettings(text: String): BackupSettings = json.decodeFromString(text)

    /** 编码视频下载元数据文件。 */
    fun encodeVideoDownloads(values: List<BackupVideoDownload>): String = json.encodeToString(values)

    /** 解码视频下载元数据文件。 */
    fun decodeVideoDownloads(text: String): List<BackupVideoDownload> = json.decodeFromString(text)

    /** 编码图片批次数据文件。 */
    fun encodeImageBatches(values: List<BackupImageBatch>): String = json.encodeToString(values)

    /** 解码图片批次数据文件。 */
    fun decodeImageBatches(text: String): List<BackupImageBatch> = json.decodeFromString(text)

    /** 编码图片项数据文件。 */
    fun encodeImageItems(values: List<BackupImageItem>): String = json.encodeToString(values)

    /** 解码图片项数据文件。 */
    fun decodeImageItems(text: String): List<BackupImageItem> = json.decodeFromString(text)

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

/** 根据数据区生成 v1 备份 checksum；仅旧内存式兼容路径使用，新导出走 `BackupPackageWriter`。 */
fun BackupData.calculateChecksum(): String {
    return dataFiles().calculatePackageChecksum()
}

/** 验证快照格式、应用身份、schemaVersion、承载格式和 checksum，失败时抛出可映射到用户提示的错误。 */
fun BackupSnapshot.validateForRestore(expectedApplicationId: String) {
    if (format != BACKUP_FORMAT) throw BackupFailure.InvalidFormat()
    if (applicationId != expectedApplicationId) throw BackupFailure.AppMismatch()
    if (schemaVersion > BACKUP_SCHEMA_VERSION) throw BackupFailure.UnsupportedSchema()
    if (encryption != BACKUP_ENCRYPTION_NONE || compression != BACKUP_COMPRESSION_ZIP) throw BackupFailure.InvalidFormat()
    if (schemaVersion <= 1 && data.calculateChecksum() != checksum) throw BackupFailure.ChecksumMismatch()
}

/** 根据完整快照和实际文件名/大小生成列表 sidecar manifest。 */
fun BackupSnapshot.toManifest(snapshotFileName: String, fileSize: Long): BackupManifest {
    return BackupManifest(
        format = format,
        applicationId = applicationId,
        schemaVersion = schemaVersion,
        encryption = encryption,
        compression = compression,
        createdAt = createdAt,
        appVersionCode = appVersionCode,
        appVersionName = appVersionName,
        deviceLabel = deviceLabel,
        source = source,
        backupKind = backupKind,
        snapshotFileName = snapshotFileName,
        fileSize = fileSize,
        checksum = checksum,
        files = data.dataFiles().toPackageFiles(),
        dataFormat = if (schemaVersion >= 2) BACKUP_DATA_FORMAT_JSONL else BACKUP_DATA_FORMAT_JSON_ARRAY,
        summary = summary
    )
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

/** zip 包内剪贴数据路径。 */
const val CLIPS_PATH = "data/clips.json"

/** v2 zip 包内剪贴 JSONL 数据路径。 */
const val CLIPS_JSONL_PATH = "data/clips.jsonl"

/** zip 包内来源 App 数据路径。 */
const val SOURCE_APPS_PATH = "data/source_apps.json"

/** v2 zip 包内来源 App JSONL 数据路径。 */
const val SOURCE_APPS_JSONL_PATH = "data/source_apps.jsonl"

/** zip 包内链接预览数据路径。 */
const val LINK_PREVIEWS_PATH = "data/link_previews.json"

/** v2 zip 包内链接预览 JSONL 数据路径。 */
const val LINK_PREVIEWS_JSONL_PATH = "data/link_previews.jsonl"

/** zip 包内搜索历史数据路径。 */
const val SEARCH_HISTORIES_PATH = "data/search_histories.json"

/** v2 zip 包内搜索历史 JSONL 数据路径。 */
const val SEARCH_HISTORIES_JSONL_PATH = "data/search_histories.jsonl"

/** zip 包内设置数据路径。 */
const val SETTINGS_PATH = "data/settings.json"

/** zip 包内视频下载元数据路径。 */
const val VIDEO_DOWNLOADS_PATH = "data/video_downloads.json"

/** v2 zip 包内视频下载 JSONL 数据路径。 */
const val VIDEO_DOWNLOADS_JSONL_PATH = "data/video_downloads.jsonl"

/** zip 包内图片批次数据路径。 */
const val IMAGE_BATCHES_PATH = "data/image_batches.json"

/** v2 zip 包内图片批次 JSONL 数据路径。 */
const val IMAGE_BATCHES_JSONL_PATH = "data/image_batches.jsonl"

/** zip 包内图片项数据路径。 */
const val IMAGE_ITEMS_PATH = "data/image_items.json"

/** v2 zip 包内图片项 JSONL 数据路径。 */
const val IMAGE_ITEMS_JSONL_PATH = "data/image_items.jsonl"

/** 必需业务数据文件路径，排序固定后用于汇总 checksum 和恢复校验。 */
private val RequiredDataPaths = listOf(
    CLIPS_PATH,
    SOURCE_APPS_PATH,
    LINK_PREVIEWS_PATH,
    SEARCH_HISTORIES_PATH,
    SETTINGS_PATH,
    VIDEO_DOWNLOADS_PATH,
    IMAGE_BATCHES_PATH,
    IMAGE_ITEMS_PATH
)

/** v2 必需业务数据文件路径，排序固定后用于 checksum 和恢复校验。 */
val RequiredJsonlDataPaths = listOf(
    CLIPS_JSONL_PATH,
    SOURCE_APPS_JSONL_PATH,
    LINK_PREVIEWS_JSONL_PATH,
    SEARCH_HISTORIES_JSONL_PATH,
    SETTINGS_PATH,
    VIDEO_DOWNLOADS_JSONL_PATH,
    IMAGE_BATCHES_JSONL_PATH,
    IMAGE_ITEMS_JSONL_PATH
)

/** 备份包内文件，内容已经是规范 JSON 文本。 */
data class BackupPackageEntry(
    /** zip 内相对路径。 */
    val path: String,
    /** 文件 JSON 文本。 */
    val text: String,
) {
    /** UTF-8 字节内容，zip 写入和大小计算共用，避免重复编码差异。 */
    val bytes: ByteArray = text.toByteArray(Charsets.UTF_8)
}

/** 生成备份数据文件列表，顺序稳定才能保证汇总 checksum 稳定。 */
fun BackupData.dataFiles(): List<BackupPackageEntry> {
    return listOf(
        BackupPackageEntry(CLIPS_PATH, BackupJson.encodeClips(clips)),
        BackupPackageEntry(SOURCE_APPS_PATH, BackupJson.encodeSourceApps(sourceApps)),
        BackupPackageEntry(LINK_PREVIEWS_PATH, BackupJson.encodeLinkPreviews(linkPreviews)),
        BackupPackageEntry(SEARCH_HISTORIES_PATH, BackupJson.encodeSearchHistories(searchHistories)),
        BackupPackageEntry(SETTINGS_PATH, BackupJson.encodeSettings(settings)),
        BackupPackageEntry(VIDEO_DOWNLOADS_PATH, BackupJson.encodeVideoDownloads(videoDownloads)),
        BackupPackageEntry(IMAGE_BATCHES_PATH, BackupJson.encodeImageBatches(imageBatches)),
        BackupPackageEntry(IMAGE_ITEMS_PATH, BackupJson.encodeImageItems(imageItems))
    )
}

/** 将数据文件转换为 manifest 中的文件清单。 */
fun List<BackupPackageEntry>.toPackageFiles(): List<BackupPackageFile> {
    return map { entry ->
        BackupPackageFile(
            path = entry.path,
            size = entry.bytes.size.toLong(),
            checksum = entry.text.sha256Hex()
        )
    }
}

/** 根据每个数据文件的路径、大小和 checksum 生成汇总 checksum。 */
fun List<BackupPackageEntry>.calculatePackageChecksum(): String {
    return toPackageFiles()
        .sortedBy { it.path }
        .joinToString(separator = "\n") { file -> "${file.path}:${file.size}:${file.checksum}" }
        .sha256Hex()
}

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

/** 将文件化备份包写成 zip 字节；包内 manifest 会先以 fileSize=0 写入，sidecar manifest 另行记录真实 zip 大小。 */
fun BackupSnapshot.encodeToPackageBytes(): ByteArray {
    val manifest = toManifest(snapshotFileName = "", fileSize = 0)
    val dataEntries = data.dataFiles()
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        zip.putUtf8Entry(PACKAGE_MANIFEST_PATH, BackupJson.encodeManifest(manifest))
        dataEntries.forEach { entry ->
            zip.putNextEntry(ZipEntry(entry.path))
            zip.write(entry.bytes)
            zip.closeEntry()
        }
    }
    return output.toByteArray()
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
    val manifest = readBackupManifestFromZip()
    manifest.validatePackageMetadata()
    if (manifest.applicationId != expectedApplicationId) throw BackupFailure.AppMismatch()
    val requiredPaths = manifest.requiredPathsForFormat()
    val files = manifest.files.associateBy { it.path }
    if (manifest.files.any { !it.path.isSafePackagePath() }) throw BackupFailure.InvalidFormat()
    runCatching {
        ZipFile(this).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory && !entry.name.isSafePackagePath()) throw BackupFailure.InvalidFormat()
            }
            if (!files.keys.containsAll(requiredPaths)) throw BackupFailure.ChecksumMismatch()
            requiredPaths.forEach { path ->
                val expected = files[path] ?: throw BackupFailure.ChecksumMismatch()
                val entry = zip.getEntry(path) ?: throw BackupFailure.ChecksumMismatch()
                val tempDigest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        tempDigest.update(buffer, 0, read)
                    }
                }
                if (total != expected.size) throw BackupFailure.ChecksumMismatch()
                val actual = tempDigest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
                if (actual != expected.checksum) throw BackupFailure.ChecksumMismatch()
            }
        }
    }.getOrElse { throwable ->
        if (throwable is BackupFailure) throw throwable else throw BackupFailure.ParseFailed(throwable)
    }
    if (requiredPaths.map { files.getValue(it) }.calculateManifestChecksum() != manifest.checksum) {
        throw BackupFailure.ChecksumMismatch()
    }
    return manifest
}

/** 从 zip 备份包解析快照，并校验 manifest 与数据文件完整性。 */
fun ByteArray.decodeBackupPackage(): BackupSnapshot {
    val entries = unzipUtf8Entries()
    val manifestText = entries[PACKAGE_MANIFEST_PATH] ?: throw BackupFailure.InvalidFormat()
    val manifest = runCatching { BackupJson.decodeManifest(manifestText) }
        .getOrElse { throw BackupFailure.ParseFailed(it) }
    manifest.validatePackageMetadata()
    validatePackageFiles(manifest, entries)
    val data = BackupData(
        clips = entries.decodeRequired(CLIPS_PATH, BackupJson::decodeClips),
        sourceApps = entries.decodeRequired(SOURCE_APPS_PATH, BackupJson::decodeSourceApps),
        linkPreviews = entries.decodeRequired(LINK_PREVIEWS_PATH, BackupJson::decodeLinkPreviews),
        searchHistories = entries.decodeRequired(SEARCH_HISTORIES_PATH, BackupJson::decodeSearchHistories),
        settings = entries.decodeRequired(SETTINGS_PATH, BackupJson::decodeSettings),
        videoDownloads = entries.decodeRequired(VIDEO_DOWNLOADS_PATH, BackupJson::decodeVideoDownloads),
        imageBatches = entries.decodeRequired(IMAGE_BATCHES_PATH, BackupJson::decodeImageBatches),
        imageItems = entries.decodeRequired(IMAGE_ITEMS_PATH, BackupJson::decodeImageItems)
    )
    val snapshot = BackupSnapshot(
        format = manifest.format,
        applicationId = manifest.applicationId,
        schemaVersion = manifest.schemaVersion,
        encryption = manifest.encryption,
        compression = manifest.compression,
        createdAt = manifest.createdAt,
        appVersionCode = manifest.appVersionCode,
        appVersionName = manifest.appVersionName,
        deviceLabel = manifest.deviceLabel,
        source = manifest.source,
        backupKind = manifest.backupKind,
        checksum = manifest.checksum,
        summary = manifest.summary,
        data = data
    )
    if (snapshot.data.calculateChecksum() != manifest.checksum) throw BackupFailure.ChecksumMismatch()
    return snapshot
}

/** 解压 zip 包并读取 UTF-8 文本 entry，同时拒绝绝对路径和父目录跳转。 */
private fun ByteArray.unzipUtf8Entries(): Map<String, String> {
    return runCatching {
        ZipInputStream(inputStream()).use { zip ->
            buildMap {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val path = entry.name
                    if (!entry.isDirectory && !path.isSafePackagePath()) throw BackupFailure.InvalidFormat()
                    if (!entry.isDirectory) {
                        put(path, zip.readBytes().toString(Charsets.UTF_8))
                    }
                    zip.closeEntry()
                }
            }
        }
    }.getOrElse { throw BackupFailure.ParseFailed(it) }
}

/** 包内路径只能是相对路径，防止未来支持解压到目录时出现 zip slip 风险。 */
private fun String.isSafePackagePath(): Boolean {
    return isNotBlank() && !startsWith("/") && !contains("\\") && split('/').none { it == ".." || it.isBlank() }
}

/** 校验 manifest 基础协议字段。 */
fun BackupManifest.validatePackageMetadata() {
    if (format != BACKUP_FORMAT) throw BackupFailure.InvalidFormat()
    if (schemaVersion > BACKUP_SCHEMA_VERSION) throw BackupFailure.UnsupportedSchema()
    if (encryption != BACKUP_ENCRYPTION_NONE || compression != BACKUP_COMPRESSION_ZIP) throw BackupFailure.InvalidFormat()
}

/** 根据 manifest 兼容判断必需 entry 路径；v1 缺失 dataFormat 时按 JSON 数组路径处理。 */
fun BackupManifest.requiredPathsForFormat(): List<String> {
    return if (schemaVersion >= 2 || dataFormat == BACKUP_DATA_FORMAT_JSONL) {
        RequiredJsonlDataPaths
    } else {
        RequiredDataPaths
    }
}

/** 根据 manifest 校验每个数据文件的存在性、大小和 checksum。 */
private fun validatePackageFiles(manifest: BackupManifest, entries: Map<String, String>) {
    val files = manifest.files.associateBy { it.path }
    if (!files.keys.containsAll(RequiredDataPaths)) throw BackupFailure.ChecksumMismatch()
    RequiredDataPaths.forEach { path ->
        val expected = files[path] ?: throw BackupFailure.ChecksumMismatch()
        val text = entries[path] ?: throw BackupFailure.ChecksumMismatch()
        if (text.toByteArray(Charsets.UTF_8).size.toLong() != expected.size) throw BackupFailure.ChecksumMismatch()
        if (text.sha256Hex() != expected.checksum) throw BackupFailure.ChecksumMismatch()
    }
    val actualPackageChecksum = RequiredDataPaths
        .sorted()
        .joinToString(separator = "\n") { path ->
            val file = files.getValue(path)
            "${file.path}:${file.size}:${file.checksum}"
        }
        .sha256Hex()
    if (actualPackageChecksum != manifest.checksum) throw BackupFailure.ChecksumMismatch()
}

/** 解码必需数据文件，缺失或格式异常时转成统一可映射错误。 */
private fun <T> Map<String, String>.decodeRequired(path: String, decoder: (String) -> T): T {
    val text = this[path] ?: throw BackupFailure.ChecksumMismatch()
    return runCatching { decoder(text) }.getOrElse { throw BackupFailure.ParseFailed(it) }
}
