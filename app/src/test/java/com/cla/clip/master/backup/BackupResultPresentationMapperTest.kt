package com.cla.clip.master.backup

import com.cla.clip.base.general.backup.settings.*
import org.junit.Assert.*
import org.junit.Test

/** 两条结果展示规则，不依赖 Compose、默认时区或真实时间。 */
class BackupResultPresentationMapperTest {
    /** 两页读取的展示状态保留本地目录冲突原因，新任务不能掩盖上次失败或影响云端成功。 */
    @Test fun localDirectoryConflictSurvivesPendingRoundPresentation() {
        /** 合成运行态同时包含旧结果和新一轮排队任务。 */
        val runtime = BackupRuntimeSnapshot(
            localResult = BackupTargetResult(100L, true, "local_directory_conflict"),
            webDavResult = BackupTargetResult(200L),
            pendingUpload = BackupPendingUpload("next", "hash", "auto", "queued", 300L, "local", "queued"),
        )
        /** 我的页和备份页都消费此共享映射，不能丢失原因。 */
        val state = BackupResultPresentationMapper.map(BackupSettingsSnapshot(), runtime)
        assertEquals(BackupResultKind.Failed, state.local.kind)
        assertEquals("local_directory_conflict", state.local.reasonCode)
        assertEquals(BackupResultKind.Success, state.webDav.kind)
    }

    /** 备份页另行展示等待包，不用新等待状态覆盖历史结果。 */
    @Test fun pendingDetailsKeepCreationTimeAndActualWaitPolicy() {
        /** 合成等待身份不涉及真实网络或文件路径。 */
        val pending = BackupPendingUpload("new", "hash", "auto", "queued", 300L)
        assertEquals(BackupResultKind.WaitingExecution, BackupResultPresentationMapper.pending(pending).single().kind)
        assertEquals(300L, BackupResultPresentationMapper.pending(pending).single().createdAt)
        assertEquals(BackupResultKind.WaitingExecution, BackupResultPresentationMapper.pending(pending.copy(source = "manual")).single().kind)
        assertEquals(BackupResultKind.Uploading, BackupResultPresentationMapper.pending(pending.copy(phase = "uploading")).single().kind)
        assertTrue(BackupResultPresentationMapper.pending(pending.copy(phase = "failed")).isEmpty())
    }
    /** 历史成功与失败都优先于等待状态，每个目标独立。 */
    @Test fun waitingDoesNotHideHistoricalResults() {
        /** 配置了两类目标，仅自动上传等待 Wi-Fi。 */
        val settings = BackupSettingsSnapshot(localBackupDirUri = "synthetic", webDavEndpoint = "https://example.invalid")
        /** 独立的本地成功和远端失败，与新等待包同时存在。 */
        val runtime = BackupRuntimeSnapshot(localResult = BackupTargetResult(100L), webDavResult = BackupTargetResult(200L, true, "remote_failed"),
            pendingUpload = BackupPendingUpload("new", "hash", "auto", "queued", 300L))
        /** 两条区域的纯展示状态。 */
        val state = BackupResultPresentationMapper.map(settings, runtime)
        assertEquals(BackupResultKind.Success, state.local.kind)
        assertEquals(100L, state.local.completedAt)
        assertEquals(BackupResultKind.Failed, state.webDav.kind)
    }
    /** 私有生成文件不能被显示为本地正式备份，手动上传等待任意网络。 */
    @Test fun privatePackageIsNotLocalSuccessAndManualIgnoresWifi() {
        /** 手动等待包只显示网络等待，历史未备份。 */
        val state = BackupResultPresentationMapper.map(BackupSettingsSnapshot(webDavEndpoint = "https://example.invalid"),
            BackupRuntimeSnapshot(pendingUpload = BackupPendingUpload("new", "hash", "manual", "queued", 10L)))
        assertEquals(BackupResultKind.LocalUnconfigured, state.local.kind)
        assertEquals(BackupResultKind.WaitingExecution, state.webDav.kind)
    }

    /** 本地正在保存时云端可独立上传；未选择目标没有虚假的排队详情。 */
    @Test fun bothTargetsExposeIndependentStages() {
        /** 两目标共享轮次，但各自进度不同。 */
        val pending = BackupPendingUpload("new", "hash", "auto", "uploading", 100L, "local", "publishing")
        assertEquals(listOf(BackupResultKind.SavingLocal, BackupResultKind.Uploading),
            BackupResultPresentationMapper.pending(pending).map { it.kind })
        assertEquals(listOf(BackupResultKind.Generating), BackupResultPresentationMapper.pending(
            pending.copy(phase = "unselected", localPhase = "generating")).map { it.kind })
    }
}
