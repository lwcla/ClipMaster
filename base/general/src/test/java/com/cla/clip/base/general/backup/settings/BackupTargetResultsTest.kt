package com.cla.clip.base.general.backup.settings

import com.cla.clip.base.general.config.datastore.createTestHostSettingsStore
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.cla.clip.base.general.backup.*
import com.cla.clip.base.general.config.datastore.HostSettingsKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 分目标结果与待上传身份回归测试；只使用合成配置。 */
class BackupTargetResultsTest {
    /** 一个 taskId 只有一次许可，回到 queued 和重建仓库不重置；新任务才重新获得机会。 */
    @Test fun directoryRecoveryPermitPersistsAcrossRescheduleAndNewRepository() = runBlocking {
        /** 使用合成 DataStore，实际事务参与验证，不模拟标记写入。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 独占安装内测试介质及仓库。 */
            val store = createTestHostSettingsStore(scope, File(folder.root, "recovery.preferences_pb"))
            /** 真实事务仓库，许可不能由测试替身重置。 */
            val repository = BackupRuntimeRepository(store)
            repository.replacePending(BackupPendingUpload("A", "hash", "auto", "uploading", 100L))
            assertFalse(repository.claimWebDavDirectoryRecoveryIfCurrentRound("old"))
            assertTrue(repository.claimWebDavDirectoryRecoveryIfCurrentRound("A"))
            assertTrue(repository.snapshot().pendingUpload!!.webDavDirectoryRecoveryAttempted)
            // 同身份替换描述也不是新任务，不应重置许可。
            repository.replacePending(repository.snapshot().pendingUpload!!.copy(phase = "queued"))
            assertFalse(repository.claimWebDavDirectoryRecoveryIfCurrentRound("A"))
            assertFalse(repository.claimWebDavDirectoryRecoveryIfCurrentRound("A"))
            repository.updateTargetPhase("A", BackupResultTarget.WebDav, "queued")
            assertFalse(BackupRuntimeRepository(store).claimWebDavDirectoryRecoveryIfCurrentRound("A"))
            assertTrue(repository.snapshot().pendingUpload!!.webDavDirectoryRecoveryAttempted)
            assertEquals(0L, repository.snapshot().webDavResult.completedAt)
            repository.replacePending(BackupPendingUpload("B", "hash", "auto", "ready", 200L))
            assertFalse(repository.snapshot().pendingUpload!!.webDavDirectoryRecoveryAttempted)
            assertTrue(repository.claimWebDavDirectoryRecoveryIfCurrentRound("B"))
        } finally { scope.cancel() }
    }

    /** 已结束的任务不能获得目录许可，保护成功结果和系统恢复边界。 */
    @Test fun terminalRoundCannotClaimRecovery() = runBlocking {
        /** 合成持久化生命周期。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 独占被测仓库。 */
            val repository = BackupRuntimeRepository(createTestHostSettingsStore(scope, File(folder.root, "terminal-recovery.preferences_pb")))
            repository.replacePending(BackupPendingUpload("A", "hash", "auto", "ready", 100L))
            repository.recordTargetResultIfCurrentRound("A", BackupResultTarget.WebDav, 200L)
            assertFalse(repository.claimWebDavDirectoryRecoveryIfCurrentRound("A"))
        } finally { scope.cancel() }
    }
    /** 旧 Wi-Fi 键只退役策略，不能抹掉两目标历史结果或已登记的新轮次。 */
    @Test fun retiredWifiPreferenceDoesNotEraseResultsOrRound() = runBlocking {
        /** 合成持久化生命周期。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 不含真实目标或凭据的安装内文件。 */
            val store = createTestHostSettingsStore(scope, File(folder.root, "wifi-migration.preferences_pb"))
            store.edit { preferences ->
                preferences[booleanPreferencesKey("auto_backup_only_wifi")] = true
                preferences[HostSettingsKeys.LOCAL_RESULT_AT] = 100L
                preferences[HostSettingsKeys.CURRENT_BACKUP_ROUND_ID] = "new"
            }
            /** 兼容事务只删除已退役键。 */
            val saved = BackupRuntimeRepository(store).snapshot()
            assertFalse(store.snapshot().contains(booleanPreferencesKey("auto_backup_only_wifi")))
            assertEquals(100L, saved.localResult.completedAt)
            assertEquals("new", saved.currentRoundId)
        } finally { scope.cancel() }
    }

    /** 半包生成阶段不能发布目标，ready 事务只推进所选目标并拒绝旧 ID。 */
    @Test fun completePackageReadinessKeepsUnselectedTargetInactive() = runBlocking {
        /** 独占事务生命周期。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 被测运行态，不需要 Android 或网络。 */
            val repository = BackupRuntimeRepository(createTestHostSettingsStore(scope, File(folder.root, "ready.preferences_pb")))
            repository.replacePending(BackupPendingUpload("new", "", "auto", "unselected", 100L, "local", "generating"))
            repository.markRoundReady("old")
            assertEquals("generating", repository.snapshot().pendingUpload?.localPhase)
            repository.markRoundReady("new")
            assertEquals("ready", repository.snapshot().pendingUpload?.localPhase)
            assertEquals("unselected", repository.snapshot().pendingUpload?.phase)
            assertFalse(repository.recordTargetResultIfCurrentRound("new", BackupResultTarget.WebDav, 200L))
        } finally { scope.cancel() }
    }
    /** A 已开始，B 在 A 完成前替换；A 的成功或失败都不得覆盖两个新结果。 */
    @Test fun lateResultsCannotOverwriteNewRoundAndTargetsFinishIndependently() = runBlocking {
        /** 合成 DataStore 独占生命周期。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 被测事务仓库，不使用真实运行态。 */
            val repository = BackupRuntimeRepository(createTestHostSettingsStore(scope, File(folder.root, "round.preferences_pb")))
            repository.replacePending(BackupPendingUpload("A", "hash", "auto", "ready", 100L, "local", "ready"))
            assertTrue(repository.isCurrentRound("A"))
            repository.replacePending(BackupPendingUpload("B", "hash", "auto", "generating", 200L, "local", "generating"))
            for (target in BackupResultTarget.entries) {
                assertFalse(repository.recordTargetResultIfCurrentRound("A", target, 300L))
                assertFalse(repository.recordTargetResultIfCurrentRound("A", target, 300L, "remote_failed"))
            }
            assertTrue(repository.recordTargetResultIfCurrentRound("B", BackupResultTarget.Local, 400L))
            assertTrue(repository.recordTargetResultIfCurrentRound("B", BackupResultTarget.WebDav, 500L, "remote_failed"))
            assertFalse(repository.recordTargetResultIfCurrentRound("B", BackupResultTarget.Local, 600L, "late_failure"))
            assertEquals(400L, repository.snapshot().localResult.completedAt)
            assertEquals("remote_failed", repository.snapshot().webDavResult.reasonCode)
        } finally { scope.cancel() }
    }
    /** 独占文件目录，避免真实设置和并发 DataStore 冲突。 */
    @get:Rule val folder = TemporaryFolder(File("build"))

    /** 仅迁移明确成功目标，新结果和其他业务设置都不得被旧摘要覆盖。 */
    @Test fun legacySuccessMigratesOnceAndPreservesNewResults() = runBlocking {
        /** 受控存储生命周期。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 独占合成设置，模拟已升级部分结果后再订阅。 */
            val store = createTestHostSettingsStore(scope, File(folder.root, "legacy-success.preferences_pb"))
            store.edit { preferences ->
                preferences[stringPreferencesKey("last_auto_backup_status")] = "success"
                preferences[HostSettingsKeys.LAST_BACKUP_SUCCESS_SUMMARY] = BackupJson.encodeSuccessSummary(
                    BackupSuccessSummary(100L, "synthetic.zip", 1L, BackupSource.WebDavAuto, BackupKind.Auto,
                        BackupSummary(), localSuccess = true, webDavSuccess = true))
                preferences[HostSettingsKeys.AUTO_BACKUP_ENABLED] = true
            }
            /** 更晚的新本地失败必须优先于旧全局成功。 */
            val repository = BackupRuntimeRepository(store)
            repository.recordTargetResult(BackupResultTarget.Local, 200L, "storage_not_writable")
            assertTrue(repository.snapshot().localResult.failed)
            assertEquals(200L, repository.snapshot().localResult.completedAt)
            assertEquals(100L, repository.snapshot().webDavResult.completedAt)
            assertTrue(store.snapshot()[HostSettingsKeys.AUTO_BACKUP_ENABLED] == true)
            assertFalse(store.snapshot().contains(HostSettingsKeys.LAST_BACKUP_SUCCESS_SUMMARY))
        } finally { scope.cancel() }
    }

    /** 旧成功摘要不能覆盖后来的失败，全局旧键在一次兼容事务中释放。 */
    @Test fun failedLegacyStateDoesNotInventLatestSuccess() = runBlocking {
        /** 合成存储的独占生命周期。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 仅本机历史兼容输入，不含真实用户信息。 */
            val store = createTestHostSettingsStore(scope, File(folder.root, "legacy.preferences_pb"))
            store.edit { preferences ->
                preferences[stringPreferencesKey("last_auto_backup_status")] = "failed"
                preferences[HostSettingsKeys.LAST_BACKUP_SUCCESS_SUMMARY] = BackupJson.encodeSuccessSummary(
                    BackupSuccessSummary(100L, "synthetic.zip", 1L, BackupSource.LocalAuto, BackupKind.Auto,
                        BackupSummary(), localSuccess = true, webDavSuccess = false))
            }
            /** 旧失败不能被更早的成功摘要冒充最近结果。 */
            val saved = BackupRuntimeRepository(store).snapshot()
            assertEquals(0L, saved.localResult.completedAt)
            assertFalse(store.snapshot().contains(HostSettingsKeys.LAST_BACKUP_SUCCESS_SUMMARY))
            assertFalse(store.snapshot().contains(stringPreferencesKey("last_auto_backup_status")))
        } finally { scope.cancel() }
    }

    /** 一个目标失败不能覆盖另一目标成功，重新构造仓库仍能读到已提交结果。 */
    @Test fun targetResultsPersistIndependently() = runBlocking {
        /** 本测试的数据层生命周期。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 唯一合成设置文件。 */
            val store = createTestHostSettingsStore(scope, File(folder.root, "results.preferences_pb"))
            /** 被测结果仓库。 */
            val repository = BackupRuntimeRepository(store)
            repository.recordTargetResult(BackupResultTarget.Local, 100L)
            repository.recordTargetResult(BackupResultTarget.WebDav, 200L, "remote_failed")
            /** 模拟页面重建，读取同一持久化快照。 */
            val saved = BackupRuntimeRepository(store).snapshot()
            assertEquals(100L, saved.localResult.completedAt)
            assertFalse(saved.localResult.failed)
            assertTrue(saved.webDavResult.failed)
            assertEquals("remote_failed", saved.webDavResult.reasonCode)
        } finally { scope.cancel() }
    }

    /** 旧任务不能结束新包；真正的失败与终止阶段必须原子保存，防止启动后复活失败包。 */
    @Test fun staleUploadCannotFinishReplacement() = runBlocking {
        /** 独占 DataStore 生命周期。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 被测持久化状态。 */
            val repository = BackupRuntimeRepository(createTestHostSettingsStore(scope, File(folder.root, "pending.preferences_pb")))
            repository.replacePending(BackupPendingUpload("new", "hash", "auto", "ready", 100L))
            assertFalse(repository.recordTargetResultIfCurrentRound("old", BackupResultTarget.WebDav, 200L, "remote_failed"))
            assertEquals("new", repository.snapshot().pendingUpload?.artifactId)
            assertEquals(0L, repository.snapshot().webDavResult.completedAt)
            assertTrue(repository.recordTargetResultIfCurrentRound("new", BackupResultTarget.WebDav, 300L, "remote_failed"))
            assertEquals("failed", repository.snapshot().pendingUpload?.phase)
            assertTrue(repository.snapshot().webDavResult.failed)
            assertFalse(repository.updateTargetPhase("new", BackupResultTarget.WebDav, "queued"))
            assertFalse(repository.recordTargetResultIfCurrentRound("new", BackupResultTarget.WebDav, 400L))
            assertEquals(300L, repository.snapshot().webDavResult.completedAt)
        } finally { scope.cancel() }
    }
}
