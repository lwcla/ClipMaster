package com.cla.clip.master.backup.upload

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import com.cla.clip.base.general.backup.*

/** 私有待上传包生命周期回归；不依赖 Android 或真实网络。 */
class BackupPendingUploadStoreTest {
    /** 合成文件目录。 */
    @get:Rule val folder = TemporaryFolder(File("build"))

    /** 等待包使用导出时的相同字节，清除生成缓存后仍可读取。 */
    @Test fun preparedPackageSurvivesExportCacheDeletion() {
        /** 私有待上传存储。 */
        val store = BackupPendingUploadStore(folder.newFolder("private"))
        /** 一份合成导出，不接触业务数据库。 */
        val export = syntheticExport(folder.newFolder("export"))
        /** 原始字节用于核对没有重新生成或修改。 */
        val bytes = export.packageFile.readBytes()
        store.prepare("current", export)
        export.taskDir.deleteRecursively()
        /** 生成缓存删除后的持久等待包。 */
        val waiting = store.read("current")
        assertArrayEquals(bytes, waiting.packageFile.readBytes())
        assertEquals(export.manifest, waiting.manifest)
    }

    /** 新轮次开始已删除旧包；新包生成失败不复活旧字节。 */
    @Test fun failedPreparationDoesNotRestoreDeletedPreviousPackage() {
        /** 两个身份共用的私有根目录。 */
        val root = folder.newFolder("private")
        /** 被测文件存储。 */
        val store = BackupPendingUploadStore(root)
        /** 同一合成来源先准备旧包，再制造新包的源文件缺失。 */
        val export = syntheticExport(folder.newFolder("export"))
        store.prepare("old", export)
        store.cleanupExcept(null)
        export.packageFile.delete()
        assertThrows(java.io.IOException::class.java) { store.prepare("new", export) }
        assertFalse(File(root, "old").exists())
        assertFalse(File(root, "new").exists())
    }

    /** 构造最小合法 sidecar 和任意合成 payload，只验证接管而非重复测试 ZIP 协议。 */
    private fun syntheticExport(directory: File): BackupExportResult {
        /** 固定合成文件名。 */
        val filename = "synthetic.zip"
        /** 任意二进制字节可发现 copy 与文本重写的差异。 */
        val payload = File(directory, filename).apply { writeBytes(byteArrayOf(0, 1, -1, 10)) }
        /** 使用既有 manifest 序列化协议。 */
        val manifest = BackupManifest(applicationId = "synthetic", schemaVersion = BACKUP_SCHEMA_VERSION, createdAt = 100L, appVersionCode = 1,
            appVersionName = "test", deviceLabel = "synthetic", source = BackupSource.WebDavAuto,
            snapshotFileName = filename, checksum = "synthetic", summary = BackupSummary())
        return BackupExportResult(payload, manifest, BackupJson.encodeManifest(manifest), filename,
            "$filename.manifest.json", directory)
    }

    /** 等待超过一天不按时间淘汰，仅清理未被当前指针持有的文件。 */
    @Test fun waitingPackageSurvivesCleanupAndReplacementRemovesOldOnly() {
        /** 固定根目录，模拟 noBackupFilesDir 内的私有域。 */
        val root = folder.newFolder("pending")
        /** 测试文件存储。 */
        val store = BackupPendingUploadStore(root)
        File(root, "old").mkdirs()
        File(root, "new").mkdirs()
        File(root, "new/payload").writeText("same-bytes")
        File(root, "new").setLastModified(1L)
        store.cleanupExcept("new")
        assertFalse(File(root, "old").exists())
        assertEquals("same-bytes", File(root, "new/payload").readText())
        assertThrows(IllegalArgumentException::class.java) { store.remove("../outside") }
    }
}
