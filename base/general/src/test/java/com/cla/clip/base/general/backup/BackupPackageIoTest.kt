package com.cla.clip.base.general.backup

import com.cla.clip.base.general.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份包底层协议测试。
 *
 * 这里只覆盖纯文件协议，不依赖 Room、Hilt 或 Android 运行时；目标是确保 v2 JSONL zip、manifest checksum
 * 和 zip entry 路径安全边界在普通 JVM 单元测试中也能被快速验证。
 */
class BackupPackageIoTest {
    /** v2 Writer 生成的 zip 必须能通过 manifest、数据格式、文件清单和 checksum 校验。 */
    @Test
    fun writerCreatesValidV2JsonlPackage() {
        val taskDir = createTempDir(prefix = "backup-package-test-")
        try {
            val writer = BackupPackageWriter()
            val session = writer.begin(taskDir, taskId = "unit-test", fileName = "clip_master_backup_test.zip")
            val clipFile = session.writeJsonLines(
                path = CLIPS_JSONL_PATH,
                serializer = BackupClip.serializer(),
                values = sequenceOf(
                    BackupClip(
                        id = 1L,
                        content = "hello",
                        timestamp = 100L,
                        sourceAppPackage = "com.example"
                    )
                )
            )
            val files = listOf(
                clipFile,
                session.writeJsonLines(SOURCE_APPS_JSONL_PATH, BackupSourceApp.serializer(), emptySequence()),
                session.writeJsonLines(LINK_PREVIEWS_JSONL_PATH, BackupLinkPreview.serializer(), emptySequence()),
                session.writeJsonLines(SEARCH_HISTORIES_JSONL_PATH, BackupSearchHistory.serializer(), emptySequence()),
                session.writeJsonObject(SETTINGS_PATH, BackupJson.encodeSettings(BackupSettings())),
                session.writeJsonLines(VIDEO_DOWNLOADS_JSONL_PATH, BackupVideoDownload.serializer(), emptySequence()),
                session.writeJsonLines(IMAGE_BATCHES_JSONL_PATH, BackupImageBatch.serializer(), emptySequence()),
                session.writeJsonLines(IMAGE_ITEMS_JSONL_PATH, BackupImageItem.serializer(), emptySequence()),
                session.writeJsonLines("data/unit_feature.jsonl", BackupSearchHistory.serializer(), emptySequence())
            )
            val manifest = BackupManifest(
                applicationId = BuildConfig.APPLICATION_ID,
                schemaVersion = BACKUP_SCHEMA_VERSION,
                createdAt = 1234L,
                appVersionCode = BuildConfig.VERSION_CODE,
                appVersionName = BuildConfig.VERSION_NAME,
                deviceLabel = "install-test",
                source = BackupSource.LocalManual,
                backupKind = BackupKind.Manual,
                snapshotFileName = "clip_master_backup_test.zip",
                checksum = files.calculateManifestChecksum(),
                files = files,
                dataFormat = BACKUP_DATA_FORMAT_JSONL,
                summary = BackupSummary(clipCount = 1)
            )

            val result = writer.finish(session, manifest)
            val validated = result.packageFile.validateBackupPackageFile(BuildConfig.APPLICATION_ID)

            assertEquals(BACKUP_SCHEMA_VERSION, validated.schemaVersion)
            assertEquals(BACKUP_DATA_FORMAT_JSONL, validated.dataFormat)
            assertEquals(1, validated.summary.clipCount)
            assertEquals(result.packageFile.length(), validated.fileSize)
            assertTrue(validated.files.map { it.path }.containsAll(RequiredJsonlDataPaths))
            assertTrue(validated.files.map { it.path }.contains("data/unit_feature.jsonl"))
        } finally {
            taskDir.deleteRecursively()
        }
    }

    /** zip 中只要出现绝对路径或父目录跳转，就必须拒绝导入，避免未来解压型能力踩到 zip slip 风险。 */
    @Test
    fun validateRejectsUnsafeZipEntryPath() {
        val file = File.createTempFile("backup-unsafe-entry", ".zip")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("../evil.json"))
                zip.write("{}\n".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            assertThrows(BackupFailure.InvalidFormat::class.java) {
                file.validateBackupPackageFile(BuildConfig.APPLICATION_ID)
            }
        } finally {
            file.delete()
        }
    }

    /** 手动导出的单 zip 可能没有 sidecar manifest，列表需要能从文件名时间戳恢复新旧排序。 */
    @Test
    fun parseBackupTimestampFromFileNameReturnsCreatedAtFallback() {
        val fileName = "clip_master_backup_install-test_20260519_213045.zip"
        val expected = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
            isLenient = false
        }.parse("20260519_213045")?.time

        assertEquals(expected, parseBackupTimestampFromFileName(fileName))
    }

    /** 历史版本 safety 文件仍要能被识别，方便新列表隐藏旧回滚文件，不再把它当普通备份展示。 */
    @Test
    fun parseBackupKindFromFileNameDetectsLegacySafetySnapshot() {
        val fileName = "clip_master_backup_install-test_safety_20260519_213045.zip"

        assertEquals(BackupKind.Safety, parseBackupKindFromFileName(fileName))
        assertEquals(null, parseBackupKindFromFileName("clip_master_backup_install-test_20260519_213045.zip"))
    }
}
