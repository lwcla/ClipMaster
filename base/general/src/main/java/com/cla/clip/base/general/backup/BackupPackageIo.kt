package com.cla.clip.base.general.backup

import com.cla.clip.base.general.BuildConfig
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.KSerializer

/**
 * 文件型备份包写入器。
 *
 * Writer 只负责 JSONL、zip、manifest、checksum 和临时文件，不读取 Room、不理解业务合并规则；这样 Repository 可以专注
 * 数据导出/恢复编排，避免继续承担压缩包 IO 细节。
 */
@Singleton
class BackupPackageWriter @Inject constructor() {
    companion object {
        /** 日志标签；只记录文件名、大小和数量，不输出用户内容。 */
        private const val TAG = "BackupPackageWriter"

        /** 最终 zip 默认上限，超过时停止发布，避免生成用户难以恢复的大文件。 */
        const val MAX_BACKUP_PACKAGE_BYTES: Long = 512L * 1024L * 1024L
    }

    /** 开始一次文件型导出，调用方随后逐类写入 JSONL 或 JSON 对象。 */
    fun begin(
        taskDir: File,
        taskId: String,
        fileName: String,
    ): BackupPackageBuildSession {
        val dataDir = File(taskDir, "data").apply {
            if (!exists() && !mkdirs()) throw BackupFailure.StorageNotWritable()
        }
        return BackupPackageBuildSession(
            taskId = taskId,
            taskDir = taskDir,
            dataDir = dataDir,
            fileName = fileName
        )
    }

    /** 完成 zip 组装并返回文件型导出结果。 */
    fun finish(
        session: BackupPackageBuildSession,
        manifest: BackupManifest,
    ): BackupPackageFileResult {
        // packageFile 是最终导出的临时 zip，后续本地 SAF 或 WebDAV 只复制这一份文件。
        val packageFile = File(session.taskDir, session.fileName)
        // packageManifest 是写入 zip 包内部的 manifest，fileSize 固定为 0，避免“文件大小字段影响压缩后文件大小”的自引用震荡。
        val packageManifest = manifest.copy(fileSize = 0L)
        runCatching {
            ZipOutputStream(packageFile.outputStream().buffered()).use { zip ->
                zip.putUtf8Entry(PACKAGE_MANIFEST_PATH, BackupJson.encodeManifest(packageManifest))
                session.entries.forEach { entry ->
                    zip.putNextEntry(ZipEntry(entry.path))
                    entry.file.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }.getOrElse { throwable ->
            logE(TAG) {
                "备份 zip 组装失败 taskId=${session.taskId} reasonCode=${throwable.backupReasonCode()} type=${throwable::class.simpleName}"
            }
            throw BackupFailure.StorageNotWritable(throwable)
        }
        // actualPackageSize 是 zip 完成后的真实大小，只写入 sidecar/latest manifest 和导出摘要，不再回写包内 manifest。
        val actualPackageSize = packageFile.length()
        if (actualPackageSize > MAX_BACKUP_PACKAGE_BYTES) throw BackupFailure.FileTooLarge()
        // finalManifest 是本地目录、WebDAV sidecar 和 latest.json 使用的最终 manifest，必须带真实 fileSize 方便列表展示。
        val finalManifest = packageManifest.copy(fileSize = actualPackageSize)
        // manifestJson 是本地目录和 WebDAV sidecar 使用的最终摘要，必须带真实 fileSize。
        val manifestJson = BackupJson.encodeManifest(finalManifest)
        logD(TAG) {
            "备份 zip 组装完成 taskId=${session.taskId} fileName=${session.fileName} fileSize=$actualPackageSize " +
                "packageManifestFileSize=${packageManifest.fileSize} entries=${session.entries.size}"
        }
        return BackupPackageFileResult(
            packageFile = packageFile,
            manifest = finalManifest,
            manifestJson = manifestJson,
            fileName = session.fileName,
            manifestFileName = buildManifestFileName(session.fileName),
            taskDir = session.taskDir
        )
    }
}

/**
 * 一次备份包构建会话。
 *
 * 会话收集每个业务数据文件的临时文件、大小和 checksum；调用方先写完所有数据文件，再由这些结果生成 manifest 并组装 zip。
 */
class BackupPackageBuildSession(
    /** 当前任务 id，仅用于日志和临时文件名。 */
    val taskId: String,
    /** 当前任务临时目录。 */
    val taskDir: File,
    /** 业务数据临时目录。 */
    private val dataDir: File,
    /** 最终 zip 文件名。 */
    val fileName: String,
) {
    /** 已写入的业务数据 entry。 */
    internal val entries = mutableListOf<BackupPackageTempEntry>()

    /** 写入 JSONL 列表文件。 */
    fun <T> writeJsonLines(
        path: String,
        serializer: KSerializer<T>,
        values: Sequence<T>,
    ): BackupPackageFile {
        val file = tempFileFor(path)
        var count = 0
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            values.forEach { value ->
                writer.write(BackupJson.encodeJsonLine(serializer, value))
                writer.newLine()
                count += 1
            }
        }
        return addEntry(path, file, count)
    }

    /** 以可增量写入的 JSONL writer 承载分页导出，避免调用方先构造完整 Sequence。 */
    suspend fun <T> writeJsonLines(
        path: String,
        serializer: KSerializer<T>,
        block: suspend (JsonLineSink<T>) -> Unit,
    ): BackupPackageFile {
        val file = tempFileFor(path)
        var count = 0
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            val sink = JsonLineSink(serializer, writer) { count += 1 }
            block(sink)
        }
        return addEntry(path, file, count)
    }

    /** 写入单个 JSON 对象文件，例如 settings。 */
    fun writeJsonObject(path: String, text: String): BackupPackageFile {
        val file = tempFileFor(path)
        file.writeText(text, Charsets.UTF_8)
        return addEntry(path, file, 1)
    }

    /** 把临时文件加入 zip entry 清单并计算 size/checksum。 */
    private fun addEntry(path: String, file: File, count: Int): BackupPackageFile {
        val packageFile = BackupPackageFile(path = path, size = file.length(), checksum = file.sha256Hex())
        entries += BackupPackageTempEntry(path = path, file = file, packageFile = packageFile, count = count)
        return packageFile
    }

    /** 根据 zip 内路径创建对应临时文件名。 */
    private fun tempFileFor(path: String): File {
        return File(dataDir, path.replace('/', '_')).apply {
            parentFile?.mkdirs()
        }
    }
}

/** JSONL 行写入 sink，封装统一编码规则和计数回调。 */
class JsonLineSink<T>(
    private val serializer: KSerializer<T>,
    private val writer: BufferedWriter,
    private val onWrite: () -> Unit,
) {
    /** 写入单条记录，固定 UTF-8 + LF；记录内容不会进入日志。 */
    fun write(value: T) {
        writer.write(BackupJson.encodeJsonLine(serializer, value))
        writer.newLine()
        onWrite()
    }
}

/** 构建期业务数据临时 entry。 */
internal data class BackupPackageTempEntry(
    val path: String,
    val file: File,
    val packageFile: BackupPackageFile,
    val count: Int,
)

/** 文件型备份包导出结果，替代旧的整包 ByteArray。 */
data class BackupPackageFileResult(
    /** 完整 zip 备份包临时文件。 */
    val packageFile: File,
    /** 列表 sidecar manifest。 */
    val manifest: BackupManifest,
    /** manifest JSON。 */
    val manifestJson: String,
    /** 快照文件名。 */
    val fileName: String,
    /** manifest 文件名。 */
    val manifestFileName: String,
    /** 本次任务临时目录，用于调用方完成写入后清理。 */
    val taskDir: File,
) {
    /** 备份包大小，单位字节。 */
    val fileSize: Long
        get() = packageFile.length()
}

/** 待恢复/预览的备份包引用，避免 UI state 保存完整 zip 字节。 */
data class BackupPackageRef(
    /** 应用私有临时 zip 文件。 */
    val file: File,
    /** 用户可见或日志可用的文件名。 */
    val fileName: String,
    /** 该引用所属临时目录；清空预览时可删除。 */
    val taskDir: File? = null,
) {
    /** 确保临时文件仍然存在且可读。 */
    fun requireReadable(): File {
        if (!file.exists() || !file.canRead()) throw BackupFailure.TempFileUnavailable()
        return file
    }
}

/**
 * 文件型备份包读取器。
 *
 * Reader 负责 manifest、checksum 和 JSONL/旧 JSON 数组兼容读取；业务恢复规则仍由 Repository 决定。
 */
@Singleton
class BackupPackageReader @Inject constructor() {
    /** 只读取 manifest 并流式校验完整性，适合预览阶段。 */
    fun preview(ref: BackupPackageRef): BackupManifest {
        return ref.requireReadable().validateBackupPackageFile(BuildConfig.APPLICATION_ID)
    }

    /** 打开 zip 中的指定 entry 逐行读取 JSONL。 */
    suspend fun <T> readJsonLines(
        ref: BackupPackageRef,
        path: String,
        serializer: KSerializer<T>,
        onItem: suspend (T) -> Unit,
    ) {
        ZipFile(ref.requireReadable()).use { zip ->
            val entry = zip.getEntry(path) ?: throw BackupFailure.ChecksumMismatch()
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
                var lineNo = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    lineNo += 1
                    if (line.isBlank()) throw BackupFailure.ParseFailed()
                    runCatching {
                        onItem(BackupJson.decodeJsonLine(serializer, line))
                    }.getOrElse { throwable ->
                        logE(TAG) {
                            "JSONL 记录解析失败 fileName=${ref.fileName} path=$path line=$lineNo reasonCode=${throwable.backupReasonCode()} type=${throwable::class.simpleName}"
                        }
                        if (throwable is BackupFailure) throw throwable else throw BackupFailure.ParseFailed(throwable)
                    }
                }
            }
        }
    }

    /** 读取单个 JSON 对象 entry。 */
    fun readText(ref: BackupPackageRef, path: String): String {
        return ZipFile(ref.requireReadable()).use { zip ->
            val entry = zip.getEntry(path) ?: throw BackupFailure.ChecksumMismatch()
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    /** 复制文件流，统一处理本地 SAF、WebDAV 下载和系统文件导入。 */
    fun copyToOutput(file: File, output: OutputStream) {
        file.inputStream().use { input -> input.copyTo(output) }
    }

    companion object {
        /** 日志标签，只记录文件名、entry 和行号，不记录 JSONL 行内容。 */
        private const val TAG = "BackupPackageReader"
    }
}
