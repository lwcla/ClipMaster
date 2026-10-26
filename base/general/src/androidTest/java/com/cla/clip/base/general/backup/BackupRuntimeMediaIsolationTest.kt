package com.cla.clip.base.general.backup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cla.clip.base.general.config.clip.ClipSettingsRepository
import com.cla.clip.base.general.config.datastore.HostSettingsStore
import com.cla.clip.base.general.config.installation.InstallationIdentityRepository
import com.cla.clip.base.general.dao.AppDatabase
import com.cla.clip.base.general.dao.ClipData
import com.cla.clip.base.general.dao.DownloadTaskData
import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.base.general.dao.ImageExtractItemData
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 仅在隔离模拟器运行：内存 Room 和专属临时 DataStore 不读取安装中的业务库。 */
@RunWith(AndroidJUnit4::class)
class BackupRuntimeMediaIsolationTest {
    /** 真实导出和重复恢复保留用户数据，既不导入源下载任务，也不覆盖目标活动任务。 */
    @Test
    fun roundTripPreservesUserDataAndLeavesRuntimeMediaUntouched() = runBlocking {
        // Context 只用于内存 Room 和测试独占缓存目录，不获取应用注入容器。
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 随机目录把本测试文件与应用正式设置、缓存和其他测试隔离。
        val directory = File(context.cacheDir, "backup-isolation-${UUID.randomUUID()}").apply { mkdirs() }
        // 设置 IO 在独立 Job 下运行，结束时先等待关闭再清理文件。
        val settingsJob = SupervisorJob()
        // 源和目标设置共享测试调度范围，但各自使用不同介质。
        val settingsScope = CoroutineScope(Dispatchers.IO + settingsJob)
        // 源数据库只保存本测试的合成用户数据与任务数据。
        val source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        // 目标数据库模拟全新安装，不关联实际用户数据库。
        val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            // 源设置介质独立，避免恢复测试修改应用设置。
            val sourceStore = HostSettingsStore(PreferenceDataStoreFactory.create(scope = settingsScope) {
                File(directory, "source.preferences_pb")
            })
            // 目标设置介质用于验证设置仍能恢复。
            val targetStore = HostSettingsStore(PreferenceDataStoreFactory.create(scope = settingsScope) {
                File(directory, "target.preferences_pb")
            })
            // 源设置显式保存非默认值，保证导出/恢复确实访问设置白名单。
            val sourceSettings = ClipSettingsRepository(sourceStore)
            sourceSettings.setRecycleBinRetentionDays(90)
            // 目标设置初始为空，恢复后应与源值相同。
            val targetSettings = ClipSettingsRepository(targetStore)
            source.clipDao().upsertClipsForBackup(listOf(ClipData(id = 41, content = "synthetic clip",
                timestamp = 100, link = null, searchText = "synthetic clip")))
            seedRuntimeMedia(source, "source", DownloadTaskData.STATUS_SUCCESS)
            // 使用真实分页导出器，检测媒体表是否仍被纳入导出链路。
            val exporter = BackupSnapshotExporter(source, BackupPackageWriter(), BackupTempFileStore(directory),
                sourceSettings, InstallationIdentityRepository(sourceStore), emptySet())
            // 文件完全由合成数据生成，不选择已有备份。
            val exported = exporter.export(BackupSource.LocalManual, BackupKind.Manual, 1_000, "isolation")
            assertEquals(1, exported.manifest.summary.clipCount)
            assertEquals(5, exported.manifest.files.size)
            assertFalse(exported.manifestJson.contains("video_download_count"))
            assertFalse(exported.manifest.files.any { it.path.contains("video") || it.path.contains("image") })
            // 预览和恢复共用真实 Reader，格式与 checksum 均走生产校验。
            val reader = BackupPackageReader()
            // 私有引用只指向当前测试的导出文件。
            val ref = BackupPackageRef(exported.packageFile, exported.fileName, exported.taskDir)
            // 恢复器目标与导出器源分离，能暴露误导入任务行的问题。
            val restorer = BackupSnapshotRestorer(target, reader, targetSettings, emptySet())
            // 完整预检先于写库。
            val manifest = reader.preview(ref)
            // 固定时间边界保证重复恢复结果可比较。
            val normalizer = BackupRestoreTimeNormalizer(restoreStartedAt = 2_000, manifestCreatedAt = manifest.createdAt)
            // 首次恢复应导入剪贴，但三张任务表保持空库。
            val first = restorer.restorePackage(ref, manifest, normalizer)
            assertEquals(1, first.insertedCount)
            assertEquals(90, targetSettings.snapshot().recycleBinRetentionDays)
            assertEquals("synthetic clip", target.clipDao().loadClipsByIdsForBackup(listOf(41)).single().content)
            listOf("download_tasks", "image_extract_batches", "image_extract_items").forEach { table ->
                // 表名来自固定测试集合，读取实际行数以覆盖整个表而非某个已知 id。
                target.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(table, 0, cursor.getInt(0))
                }
            }
            seedRuntimeMedia(target, "target", DownloadTaskData.STATUS_DOWNLOADING)
            // 活动任务使用与源任务相同主键，覆盖旧恢复合并意外写入的高风险情形。
            val videoBefore = target.downloadDao().getTask(1L)
            // 图片父子关系和输出引用也必须完整保留。
            val batchBefore = target.imageExtractDao().getBatch(1L)
            // 逐项比较，防止只保护批次而漏改图片输出 URI。
            val itemsBefore = target.imageExtractDao().getItems(1L)
            // 第二次恢复必须幂等，报告不能出现媒体类别。
            val repeated = restorer.restorePackage(ref, manifest, normalizer)
            assertEquals(0, repeated.insertedCount)
            assertEquals(0, repeated.updatedCount)
            assertEquals(1, repeated.skippedCount)
            assertEquals(setOf("source_apps", "link_previews", "clips", "search_histories"),
                repeated.categoryReports.map { it.category }.toSet())
            assertEquals(videoBefore, target.downloadDao().getTask(1L))
            assertEquals(batchBefore, target.imageExtractDao().getBatch(1L))
            assertEquals(itemsBefore, target.imageExtractDao().getItems(1L))
        } finally {
            source.close()
            target.close()
            settingsJob.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    /** 写入两端相同主键但不同内容的合成任务，不启动 Worker 或创建公共媒体文件。 */
    private suspend fun seedRuntimeMedia(database: AppDatabase, label: String, videoStatus: String) {
        database.downloadDao().insertTask(DownloadTaskData(id = 1, videoUrl = "https://example.invalid/$label.mp4",
            fileName = "$label-video", status = videoStatus, progress = 37, createTime = 100, updateTime = 200,
            savePath = "content://test/$label/video", pendingOutputUri = "content://test/$label/pending"))
        database.imageExtractDao().insertBatch(ImageExtractBatchData(id = 1, pageUrl = "https://example.invalid/$label",
            pageName = "$label-images", totalCount = 1, status = ImageExtractBatchData.STATUS_DOWNLOADING,
            outputDir = "$label-folder", createTime = 100, updateTime = 200))
        database.imageExtractDao().insertItems(listOf(ImageExtractItemData(id = 1, batchId = 1,
            url = "https://example.invalid/$label.png", displayOrder = 0,
            outputUri = "content://test/$label/image", finalName = "$label.png")))
    }
}
