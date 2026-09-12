package com.cla.clip.feature.torrent.search.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 旧磁力数据检测与精确清理的文件白名单回归。 */
class LegacyMagnetDataCleanerTest {
    /** folder 为每个用例提供自动回收的隔离目录。 */
    @get:Rule
    val folder = TemporaryFolder()

    /** 清理只能删除旧数据库、Room sidecar 和 Academic Torrents 缓存固定目标。 */
    @Test
    fun detectsAndDeletesOnlyExactLegacyTargets() {
        /** databaseDir 模拟应用 databases 目录。 */
        val databaseDir = folder.newFolder("databases")
        /** cacheDir 模拟应用 cache 目录。 */
        val cacheDir = folder.newFolder("cache")
        /** legacyDatabase 是旧用户数据库固定主文件。 */
        val legacyDatabase = File(databaseDir, "magnet_feature_database.db").apply { writeText("db") }
        /** legacyWal 是旧用户数据库固定 WAL sidecar。 */
        val legacyWal = File(databaseDir, "magnet_feature_database.db-wal").apply { writeText("wal") }
        /** unrelatedDatabase 必须在清理后保留。 */
        val unrelatedDatabase = File(databaseDir, "unrelated.db").apply { writeText("keep") }
        /** sourceCacheDir 是旧 Academic Torrents 独占缓存目录。 */
        val sourceCacheDir = File(cacheDir, "magnet_source_cache").apply { mkdirs() }
        /** sourceCacheDatabase 是旧 Academic Torrents Room 缓存。 */
        val sourceCacheDatabase = File(sourceCacheDir, "magnet_source_cache.db").apply { writeText("cache") }
        /** unrelatedCache 即使位于旧目录也不属于精确目标，必须保留并阻止目录删除。 */
        val unrelatedCache = File(sourceCacheDir, "keep.bin").apply { writeText("keep") }
        /** cleaner 只接收两个已解析应用私有根目录。 */
        val cleaner = LegacyMagnetDataCleaner(databaseDir, cacheDir)

        /** snapshot 锁定确认弹窗使用的分类数量与内部目标。 */
        val snapshot = cleaner.detect()
        assertEquals(2, snapshot.databaseFileCount)
        assertEquals(1, snapshot.cacheFileCount)

        /** result 返回精确删除和失败数量，不把路径暴露给 UI。 */
        val result = cleaner.clean(snapshot)
        assertEquals(3, result.deletedFileCount)
        assertEquals(0, result.failedFileCount)
        assertFalse(legacyDatabase.exists())
        assertFalse(legacyWal.exists())
        assertFalse(sourceCacheDatabase.exists())
        assertTrue(unrelatedDatabase.exists())
        assertTrue(unrelatedCache.exists())
        assertTrue(sourceCacheDir.exists())
    }

    /** 伪造 snapshot 中的非白名单目标必须失败且不得删除文件。 */
    @Test
    fun rejectsSnapshotOutsideExactWhitelist() {
        /** databaseDir 模拟应用 databases 目录。 */
        val databaseDir = folder.newFolder("db2")
        /** cacheDir 模拟应用 cache 目录。 */
        val cacheDir = folder.newFolder("cache2")
        /** unrelated 是任何不属于旧实现固定名称的文件。 */
        val unrelated = File(databaseDir, "unrelated.db").apply { writeText("keep") }
        /** cleaner 用于验证 snapshot 防篡改边界。 */
        val cleaner = LegacyMagnetDataCleaner(databaseDir, cacheDir)
        /** forgedSnapshot 模拟调用方把任意路径混入待清理集合。 */
        val forgedSnapshot = LegacyMagnetDataSnapshot(1, 0, setOf(unrelated.absolutePath))

        /** result 必须把伪造目标计为失败而不是执行删除。 */
        val result = cleaner.clean(forgedSnapshot)
        assertEquals(0, result.deletedFileCount)
        assertEquals(1, result.failedFileCount)
        assertTrue(unrelated.exists())
    }
}
