package com.cla.clip.base.general.backup

import com.cla.clip.base.general.BuildConfig
import kotlinx.coroutines.runBlocking
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
 * 这里只覆盖纯文件协议，不依赖 Room、Hilt 或 Android 运行时；目标是确保 v5 JSONL zip、manifest checksum
 * 和 zip entry 路径安全边界在普通 JVM 单元测试中也能被快速验证。
 */
class BackupPackageIoTest {
    /** v5 ZIP 单独复制后仍可预检并读取业务数据，保护本地移除外部摘要后的恢复入口。 */
    @Test
    fun writerCreatesValidV5JsonlPackage() {
        // 此用例的合成备份目录，结束后只清理该目录。
        val taskDir = createTempDir(prefix = "backup-package-test-")
        try {
            // 真实协议写入器，覆盖最终 ZIP 文件而非模拟结果。
            val writer = BackupPackageWriter()
            // 当前临时目录中的独立写入会话。
            val session = writer.begin(taskDir, taskId = "unit-test", fileName = "clip_master_backup_test.zip")
            // 唯一合成剪贴项的 JSONL 文件描述。
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
            // 五个宿主条目与一个可选模块条目，不含媒体数据。
            val files = listOf(
                clipFile,
                session.writeJsonLines(SOURCE_APPS_JSONL_PATH, BackupSourceApp.serializer(), emptySequence()),
                session.writeJsonLines(LINK_PREVIEWS_JSONL_PATH, BackupLinkPreview.serializer(), emptySequence()),
                session.writeJsonLines(SEARCH_HISTORIES_JSONL_PATH, BackupSearchHistory.serializer(), emptySequence()),
                session.writeJsonObject(SETTINGS_PATH, BackupJson.encodeSettings(BackupSettings())),
                session.writeJsonLines("data/unit_feature.jsonl", BackupSearchHistory.serializer(), emptySequence())
            )
            // 格式 5 的实际文件清单和摘要。
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

            // 完成打包后的文件，只把 ZIP 发布到独立空目录。
            val result = writer.finish(session, manifest)
            // 模拟用户只保存 ZIP；目录中没有外部 manifest 或生成中间文件。
            val publishedDir = File(taskDir, "published").apply { mkdir() }
            // 复制原始字节，与本地目录和 WebDAV 发布同包的契约一致。
            val publishedZip = result.packageFile.copyTo(File(publishedDir, result.fileName))
            // 与本地恢复相同的文件引用，只指向独立 ZIP。
            val ref = BackupPackageRef(publishedZip, result.fileName, publishedDir)
            // 真实包读取器执行恢复前预检和 JSONL 读取，不使用导出结果中的摘要。
            val reader = BackupPackageReader()
            // 通过版本、大小、路径和摘要校验的协议对象。
            val validated = reader.preview(ref)
            // 实际读回的合成剪贴数据，确认包外元数据缺失不影响内容读取。
            val restoredClips = mutableListOf<BackupClip>()
            // 仅驱动流式读取入口；不连接数据库或 Android 系统。
            runBlocking {
                reader.readJsonLines(ref, CLIPS_JSONL_PATH, BackupClip.serializer()) { clip ->
                    restoredClips += clip
                }
            }

            assertEquals(listOf(result.fileName), publishedDir.list()?.toList())
            assertEquals(listOf("hello"), restoredClips.map { it.content })
            assertEquals(BACKUP_SCHEMA_VERSION, validated.schemaVersion)
            assertEquals(BACKUP_DATA_FORMAT_JSONL, validated.dataFormat)
            assertEquals(1, validated.summary.clipCount)
            assertEquals(0L, validated.fileSize)
            assertEquals(result.packageFile.length(), result.manifest.fileSize)
            assertEquals(result.packageFile.length(), BackupJson.decodeManifest(result.manifestJson).fileSize)
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
