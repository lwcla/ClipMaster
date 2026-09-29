package com.cla.clip.master.ui.page.backup

import com.cla.clip.base.general.backup.BackupFeatureRestoreFailure
import com.cla.clip.base.general.backup.BackupRestoreReport
import com.cla.clip.base.general.backup.BackupSettingsRestoreFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 恢复失败阶段判定决定页面是否保留已提交的 Room 报告。 */
class BackupPartialRestoreOutcomeTest {
    /** 功能库失败时仍展示宿主数据库计数，并指明未完成的是功能库阶段。 */
    @Test
    fun featureFailurePreservesHostRoomReport() {
        /** 已提交的宿主数据库统计，不包含未确认的功能库行。 */
        val roomReport = BackupRestoreReport(insertedCount = 2, updatedCount = 1, skippedCount = 0)
        /** 合成可选功能库失败，仅保护阶段契约，不访问真实数据库。 */
        val failure = BackupFeatureRestoreFailure(roomReport, IllegalStateException("synthetic"))
        assertEquals(BackupPartialRestoreOutcome(roomReport, BackupPartialRestoreStage.Feature), failure.toPartialRestoreOutcomeOrNull())
    }

    /** 设置写入失败时显示已完成的 Room 统计，避免误报整个恢复失败。 */
    @Test
    fun settingsFailurePreservesCompletedReport() {
        /** 已完成宿主与功能库阶段后的报告。 */
        val completedReport = BackupRestoreReport(insertedCount = 3, updatedCount = 0, skippedCount = 1)
        /** 合成 DataStore 设置提交异常。 */
        val failure = BackupSettingsRestoreFailure(completedReport, IllegalStateException("synthetic"))
        assertEquals(BackupPartialRestoreOutcome(completedReport, BackupPartialRestoreStage.Settings), failure.toPartialRestoreOutcomeOrNull())
    }

    /** 未提交任何已知阶段的普通异常仍交给原有完整失败页处理。 */
    @Test
    fun unrelatedFailureHasNoPartialOutcome() {
        assertNull(IllegalStateException("synthetic").toPartialRestoreOutcomeOrNull())
    }
}
