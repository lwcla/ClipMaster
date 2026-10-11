package com.cla.clip.master.ui.page.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 保护手动入队只显示确认弹窗，同时保留实际本地导出的进度反馈。 */
class BackupBusyOperationPresentationTest {
    /** 手动 WebDAV 入口只提交后台任务，不能先短暂显示创建进度弹窗。 */
    @Test
    fun manualWebDavSubmissionDoesNotShowModalProgress() {
        assertFalse(BackupBusyOperation.UploadingWebDav.isModalProgress)
    }

    /** 本地导出在当前页面实际写文件，仍需原有模态进度反馈。 */
    @Test
    fun localExportStillShowsModalProgress() {
        assertTrue(BackupBusyOperation.Exporting.isModalProgress)
    }

    /** 其他操作不借本次修复新增模态进度，包括由独立恢复页管理的恢复任务。 */
    @Test
    fun otherPageOperationsDoNotShowModalProgress() {
        // operation 枚举所有不属于导出或手动入队的操作，保护原有非模态范围。
        BackupBusyOperation.entries.filter { operation ->
            operation != BackupBusyOperation.Exporting && operation != BackupBusyOperation.UploadingWebDav
        }.forEach { operation ->
            assertFalse("$operation 不应显示备份页模态进度", operation.isModalProgress)
        }
    }
}
