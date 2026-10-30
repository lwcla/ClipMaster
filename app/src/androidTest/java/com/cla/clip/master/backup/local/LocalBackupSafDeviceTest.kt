package com.cla.clip.master.backup.local

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cla.clip.base.general.backup.*
import com.cla.clip.base.general.dao.AppDatabase
import com.cla.clip.base.general.dao.ClipData
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 显式独立安装、真实系统 SAF 授权和合成数据的设备验收；绝不在正式包中运行。 */
@RunWith(AndroidJUnit4::class)
class LocalBackupSafDeviceTest {
    /** 当前被测安装，运行前必须校验包名与目录边界。 */
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    /** 只记录本用例创建的精确文档句柄，结束时定点回收，禁止清空整个授权目录。 */
    private val createdDocuments = mutableListOf<Uri>()

    /** 系统选择器已经授予的专属树；不申请 shell 特权或伪造 URI 授权。 */
    private fun testTree(): Uri {
        // 普通常规设备套件跳过该专项，只有显式隔离安装才允许执行真实写入测试。
        assumeTrue("isolated_install_required", context.packageName == "com.cla.clip.master.backuptest")
        return context.contentResolver.persistedUriPermissions.single {
            it.isReadPermission && it.isWritePermission &&
                DocumentsContract.getTreeDocumentId(it.uri) == "primary:Documents/ClipMasterBackupDeviceCheck_20261030"
        }.uri
    }

    /** 真机完整闭环：只发布 ZIP，正确排序与清理，从 SAF 复制后恢复数据和设置并验证幂等。 */
    @Test fun realProviderPublishesListsPrunesAndRestoresZip() = runBlocking {
        /** 测试只访问已隔离授权目录。 */
        val tree = testTree()
        assertTrue("dedicated test directory must be empty", children(tree).isEmpty())
        /** 本次独占私有工作区；失败也不清理应用正式目录。 */
        val directory = File(context.cacheDir, "saf-device-${UUID.randomUUID()}").apply { mkdirs() }
        /** 设置协程先取消并等待，再清理测试文件。 */
        val settingsJob = SupervisorJob()
        /** 测试的设置 IO 范围独立于 Application 生命周期。 */
        val scope = CoroutineScope(Dispatchers.IO + settingsJob)
        /** 源库只含合成用户数据。 */
        val source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        /** 恢复库与源库独立，证明实际发生写入而非读取已有数据。 */
        val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            /** 两个设置介质分别模拟源设备与全新目标。 */
            val sourceStore = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "source.preferences_pb") }
            val targetStore = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "target.preferences_pb") }
            /** 真实领域设置仓库，测试构造桥只提供独立依赖。 */
            val sourceSettings = DeviceBackupSettingsFactory.clipSettings(sourceStore)
            val targetSettings = DeviceBackupSettingsFactory.clipSettings(targetStore)
            sourceSettings.setRecycleBinRetentionDays(90)
            source.clipDao().upsertClipsForBackup(listOf(ClipData(id = 41001L, content = "device backup synthetic clip",
                timestamp = 100L, link = null, searchText = "device backup synthetic clip")))
            /** 为临时包管理器替换 cacheDir，避免影响应用自己的备份缓存。 */
            val testContext = object : ContextWrapper(context) {
                /** 所有测试生成中间文件限制在当前私有测试目录。 */
                override fun getCacheDir(): File = directory
            }
            /** 真实分页导出器与包协议，不使用假 ZIP。 */
            val exporter = BackupSnapshotExporter(source, BackupPackageWriter(), BackupTempFileStore(testContext),
                sourceSettings, DeviceBackupSettingsFactory.installation(sourceStore), emptySet())
            /** 正常 SAF 写入器，使用系统已授权的目标上下文。 */
            val writer = LocalBackupDirectoryWriter(context)
            /** 从旧到新生成三个真实快照，文件名时间与写入顺序故意相反。 */
            val exports = (1..3).map { index ->
                exporter.export(BackupSource.LocalManual, BackupKind.Manual, 1_793_318_400_000L + index * 60_000L,
                    "device-$index-${UUID.randomUUID()}")
            }
            // 新包先写、旧包后写，修改时间不能反转文件名创建时间排序。
            exports.reversed().forEach { export ->
                writer.writeExport(tree, export, "device-test")
                createdDocuments += children(tree).getValue(export.fileName).first
            }
            /** 发布完成后的真实目录，应只有三个正式 ZIP，无 JSON 和临时残留。 */
            val published = children(tree)
            assertEquals(exports.map { it.fileName }.toSet(), published.keys)
            assertTrue(published.keys.all { it.endsWith(".zip") && !it.contains(".tmp") })
            /** 列表顺序必须与文件名时间一致，不依赖 Provider 返回次序。 */
            val listed = writer.listBackups(tree)
            assertEquals(exports.reversed().map { it.fileName }, listed.map { it.fileName })
            assertTrue(listed.all { it.size != null && it.size!! > 0L && it.isRegularBackup })
            /** 历史损坏摘要必须忽略；不影响可恢复 ZIP 的排序与读取。 */
            val legacyName = "${exports.last().fileName}.manifest.json"
            create(tree, "application/json", legacyName, "invalid legacy summary")
            /** 未识别 ZIP 与旧 safety 都不参与保留删除。 */
            val unknownName = "clip_master_backup_unknown.zip"
            val safetyName = "clip_master_backup_device_safety_20200101_000000.zip"
            create(tree, "application/zip", unknownName, "unknown")
            create(tree, "application/zip", safetyName, "old safety")
            /** 名称像普通 ZIP 的目录也必须保留，其内部哨兵用于发现递归误删。 */
            val directoryName = "clip_master_backup_directory_20200101_000000.zip"
            val protectedDirectory = create(tree, DocumentsContract.Document.MIME_TYPE_DIR, directoryName)
            assertNotNull(DocumentsContract.createDocument(context.contentResolver, protectedDirectory, "text/plain", "sentinel.txt"))
            /** 真实保留清理仅删一份最旧普通备份，不按所有 ZIP 数量错误计算。 */
            val cleanup = writer.pruneBackups(tree, 2, "device-test")
            assertEquals(1, cleanup.deletedCount)
            /** 清理后的目录既保留两份新 ZIP，也保留全部排除项。 */
            val remaining = children(tree)
            assertEquals(setOf(exports[1].fileName, exports[2].fileName, legacyName, unknownName, safetyName, directoryName), remaining.keys)
            assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, remaining.getValue(directoryName).second)
            assertEquals(exports.reversed().take(2).map { it.fileName }, writer.listBackups(tree).filter { it.isRegularBackup }.map { it.fileName })
            /** 选择最新有效 ZIP，复制阶段与恢复页面完全相同。 */
            val selected = writer.listBackups(tree).first { it.fileName == exports.last().fileName }
            val ref = writer.copyBackupToRef(selected, File(directory, "restore.zip"), directory)
            assertArrayEquals(exports.last().packageFile.readBytes(), ref.file.readBytes())
            /** 真实仓库以独立目标库执行预检、事务写库和设置恢复。 */
            val reader = BackupPackageReader()
            val repository = BackupRepository(exporter, BackupSnapshotRestorer(target, reader, targetSettings, emptySet()), reader)
            assertTrue(repository.previewSnapshot(ref).checksumValid)
            assertEquals(1, repository.restoreSnapshot(ref).insertedCount)
            assertEquals("device backup synthetic clip", target.clipDao().loadClipsByIdsForBackup(listOf(41001L)).single().content)
            assertEquals(90, targetSettings.snapshot().recycleBinRetentionDays)
            /** 同一 ZIP 重复恢复不能制造新增或无效更新。 */
            val repeated = repository.restoreSnapshot(ref)
            assertEquals(0, repeated.insertedCount)
            assertEquals(0, repeated.updatedCount)
            assertEquals(1, repeated.skippedCount)
            // 仅保留私有合成夹具，供随后的真实页面恢复验收；不留下 SAF 产物干扰下一次测试。
            ref.file.copyTo(File(context.filesDir, "backup-device-ui.zip"), overwrite = true)
            File(context.filesDir, "backup-device-ui-filename").writeText(selected.fileName)
        } finally {
            source.close()
            target.close()
            settingsJob.cancelAndJoin()
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            directory.deleteRecursively()
            cleanupCreatedDocuments(tree)
        }
    }

    /** 正式 ZIP 名和临时名都可能碰到用户目录；应保留内容并返回可供两页显示的独立原因。 */
    @Test fun nameCollisionsNeverDeleteExistingDirectories() = runBlocking {
        /** 同一专属授权树，目录均由本用例创建。 */
        val tree = testTree()
        /** 只验证发布契约，合成包正文无需再次覆盖协议解析。 */
        val payload = File(context.cacheDir, "device-collision-${UUID.randomUUID()}.zip").apply { writeText("synthetic") }
        try {
            // 两种碰撞分别覆盖正式发布和临时创建，不能只保护保留清理阶段。
            for (suffix in listOf("", ".tmp")) {
                /** 当前用例独有的标准文件名。 */
                val name = "clip_master_backup_collision-${UUID.randomUUID()}_20261030_120000.zip"
                /** 明确创建同名目录及其内部哨兵。 */
                val collision = create(tree, DocumentsContract.Document.MIME_TYPE_DIR, name + suffix)
                val sentinel = DocumentsContract.createDocument(context.contentResolver, collision, "text/plain", "sentinel.txt")!!
                /** 由真实 Reader 无需消费的导出元信息，失败必须发生在 IO 发布边界。 */
                val manifest = BackupManifest(applicationId = context.packageName, schemaVersion = BACKUP_SCHEMA_VERSION, createdAt = 1L,
                    appVersionCode = 1, appVersionName = "test", deviceLabel = "synthetic", source = BackupSource.LocalManual,
                    snapshotFileName = name, checksum = "synthetic", summary = BackupSummary())
                val export = BackupExportResult(payload, manifest, BackupJson.encodeManifest(manifest), name,
                    "$name.manifest.json", context.cacheDir)
                /** 在真实设备上验证原流程是否误删；异常类型由生产边界负责。 */
                val failure = runCatching { LocalBackupDirectoryWriter(context).writeExport(tree, export, "device-collision") }.exceptionOrNull()
                assertTrue("directory collision must keep its specific failure", failure is BackupFailure.LocalDirectoryConflict)
                assertEquals("local_directory_conflict", failure!!.backupReasonCode())
                assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, children(tree).getValue(name + suffix).second)
                context.contentResolver.query(sentinel, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)!!.use {
                    assertTrue("directory child must survive", it.moveToFirst())
                }
            }
        } finally {
            payload.delete()
            cleanupCreatedDocuments(tree)
        }
    }

    /** 在授权树根创建测试项；仅合成小文本使用正文参数，目录不写字节。 */
    private fun create(tree: Uri, mime: String, name: String, contents: String? = null): Uri {
        /** 使用原树授权构造根文档，不请求其他路径权限。 */
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        /** 返回的文档 URI 用于后续定点检查。 */
        val created = requireNotNull(DocumentsContract.createDocument(context.contentResolver, parent, mime, name))
        createdDocuments += created
        if (contents != null) context.contentResolver.openOutputStream(created, "wt")!!.use { it.write(contents.toByteArray()) }
        return created
    }

    /** 精确删除本用例创建的文档，已被保留清理删除的句柄允许不存在；清理后必须可重复运行。 */
    private fun cleanupCreatedDocuments(tree: Uri) {
        // 句柄都来自本用例 createDocument 或成功发布后的定点查询，不按名称扫描删除其他文件。
        createdDocuments.asReversed().forEach { uri ->
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        }
        /** 检查尚存的本用例句柄，避免静默遗留污染后续测试；不输出 URI 正文。 */
        val leftovers = children(tree).values.map { it.first }.intersect(createdDocuments.toSet())
        assertTrue("test document cleanup failed", leftovers.isEmpty())
        createdDocuments.clear()
    }

    /** 只枚举专属授权目录的名称、URI、MIME，不读取正文或用户数据库。 */
    private fun children(tree: Uri): Map<String, Pair<Uri, String>> {
        /** 系统 Provider 的真实直接子项查询。 */
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return buildMap {
            context.contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)!!.use { cursor ->
                while (cursor.moveToNext()) put(cursor.getString(1),
                    DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)) to cursor.getString(2))
            }
        }
    }
}
