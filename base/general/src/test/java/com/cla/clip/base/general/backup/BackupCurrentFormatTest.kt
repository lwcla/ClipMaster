package com.cla.clip.base.general.backup

import com.cla.clip.base.general.BuildConfig
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** 格式 5 只备份跨安装数据；使用合成文件验证入口协议，不接触用户数据库。 */
class BackupCurrentFormatTest {
    /** 新格式只要求五个宿主文件，可选模块文件仍参与校验与往返。 */
    @Test
    fun acceptsVersionFiveWithoutMediaEntries() = withPackage(schemaVersion = 5) { file ->
        // 校验结果来自真实 ZIP 读取，避免只断言 Writer 的内存模型。
        val manifest = file.validateBackupPackageFile(BuildConfig.APPLICATION_ID)
        assertEquals(5, manifest.schemaVersion)
        assertEquals(
            setOf("data/clips.jsonl", "data/source_apps.jsonl", "data/link_previews.jsonl",
                "data/search_histories.jsonl", "data/settings.json", "data/test_feature.jsonl"),
            manifest.files.map { it.path }.toSet()
        )
        ZipFile(file).use { zip ->
            assertEquals(7, zip.size())
        }
        // 摘要的外部协议不再保留被移除的三种媒体计数。
        val encodedManifest = BackupJson.encodeManifest(manifest)
        assertFalse(encodedManifest.contains("video_download_count"))
        assertFalse(encodedManifest.contains("image_batch_count"))
        assertFalse(encodedManifest.contains("image_item_count"))
    }

    /** 旧格式在文件数据恢复前拒绝，不为开发阶段维护迁移入口。 */
    @Test
    fun rejectsOlderSchema() = withPackage(schemaVersion = 4) { file ->
        assertThrows(BackupFailure.UnsupportedSchema::class.java) {
            file.validateBackupPackageFile(BuildConfig.APPLICATION_ID)
        }
    }

    /** 未知新格式同样不能使用当前恢复器猜测处理。 */
    @Test
    fun rejectsNewerSchema() = withPackage(schemaVersion = 6) { file ->
        assertThrows(BackupFailure.UnsupportedSchema::class.java) {
            file.validateBackupPackageFile(BuildConfig.APPLICATION_ID)
        }
    }

    /** 即使版本正确，旧数组编码也不能进入当前 JSONL 恢复链路。 */
    @Test
    fun rejectsArrayEncoding() = withPackage(schemaVersion = 5, dataFormat = "json_array") { file ->
        assertThrows(BackupFailure.InvalidFormat::class.java) {
            file.validateBackupPackageFile(BuildConfig.APPLICATION_ID)
        }
    }

    /** 文件清单被篡改后仍必须报完整性错误，不能因协议收缩跳过 checksum。 */
    @Test
    fun rejectsCorruptChecksum() = withPackage(schemaVersion = 5, checksum = "invalid") { file ->
        assertThrows(BackupFailure.ChecksumMismatch::class.java) {
            file.validateBackupPackageFile(BuildConfig.APPLICATION_ID)
        }
    }

    /** 当前必需文件缺失时拒绝导入，即使剩余文件及总摘要都一致。 */
    @Test
    fun rejectsMissingRequiredEntry() = withPackage(schemaVersion = 5, includeSettings = false) { file ->
        assertThrows(BackupFailure.ChecksumMismatch::class.java) {
            file.validateBackupPackageFile(BuildConfig.APPLICATION_ID)
        }
    }

    /** JSONL 的空行与非法对象通过统一解析错误报告，避免静默跳过损坏数据。 */
    @Test
    fun rejectsMalformedJsonLine() {
        assertThrows(BackupFailure.ParseFailed::class.java) {
            BackupJson.decodeJsonLine(BackupClip.serializer(), "{broken")
        }
        assertThrows(BackupFailure.ParseFailed::class.java) {
            BackupJson.decodeJsonLine(BackupClip.serializer(), " ")
        }
    }

    /** 构造独立临时 ZIP 并总是清理；参数只描述待检验的外部协议变化。 */
    private fun withPackage(
        schemaVersion: Int,
        dataFormat: String = "jsonl",
        checksum: String? = null,
        includeSettings: Boolean = true,
        assertion: (File) -> Unit,
    ) {
        // 临时目录只承载本测试合成文件，不读取应用或设备已有数据。
        val directory = kotlin.io.path.createTempDirectory("backup-v5-test-").toFile()
        try {
            // 复用真实 Writer，使校验覆盖实际文件格式。
            val writer = BackupPackageWriter()
            // 每个测试在自己的目录生成同名包，相互隔离。
            val session = writer.begin(directory, "format-test", "test.zip")
            // 宿主列表以空 JSONL 表达合法空库，模块条目使用合成记录。
            val files = buildList {
                add(session.writeJsonLines("data/clips.jsonl", BackupClip.serializer(), emptySequence()))
                add(session.writeJsonLines("data/source_apps.jsonl", BackupSourceApp.serializer(), emptySequence()))
                add(session.writeJsonLines("data/link_previews.jsonl", BackupLinkPreview.serializer(), emptySequence()))
                add(session.writeJsonLines("data/search_histories.jsonl", BackupSearchHistory.serializer(), emptySequence()))
                if (includeSettings) add(session.writeJsonObject("data/settings.json", BackupJson.encodeSettings(BackupSettings())))
                add(session.writeJsonLines("data/test_feature.jsonl", BackupSearchHistory.serializer(),
                    sequenceOf(BackupSearchHistory(1, "test", "test", false, 100))))
            }
            // 显式写入版本，确保测试不会随生产常量一起变化而失去回归意义。
            val manifest = BackupManifest(
                applicationId = BuildConfig.APPLICATION_ID,
                schemaVersion = schemaVersion,
                createdAt = 100,
                appVersionCode = BuildConfig.VERSION_CODE,
                appVersionName = BuildConfig.VERSION_NAME,
                deviceLabel = "test-install",
                source = BackupSource.LocalManual,
                snapshotFileName = "test.zip",
                checksum = checksum ?: files.calculateManifestChecksum(),
                files = files,
                dataFormat = dataFormat,
                summary = BackupSummary(),
            )
            assertion(writer.finish(session, manifest).packageFile)
        } finally {
            directory.deleteRecursively()
        }
    }
}
