package com.cla.clip.base.general.backup

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 清理仅作用于后台生成域，不触碰独立系统导出和恢复预览。 */
class BackupTempFileStoreTest {
    /** 测试独占目录，不引用本机授权或真实文件。 */
    @get:Rule val folder = TemporaryFolder(File("build"))

    /** 即使旧包是刚生成的半包也立即删除，其他入口目录必须保留。 */
    @Test fun newRoundCleansOnlyBackgroundExportIntermediates() {
        /** 私有缓存根目录。 */
        val root = folder.newFolder("cache")
        /** 被测目录管理器，可注入合成目录避免 Android Context。 */
        val store = BackupTempFileStore(root)
        /** UUID 轮次及升级前的每日目录均属于后台中间产物。 */
        val round = store.createExportDir("11111111-1111-1111-1111-111111111111")
        val oldDaily = store.createExportDir("daily-synthetic-1")
        /** 系统导出和导入目录有独立所有权，不能被新轮次批量清理。 */
        val manual = store.createExportDir("manual-local-synthetic-1")
        val restore = store.createImportDir("restore-synthetic-1")
        store.cleanupBackgroundExports()
        assertFalse(round.exists())
        assertFalse(oldDaily.exists())
        assertTrue(manual.exists())
        assertTrue(restore.exists())
    }
}
