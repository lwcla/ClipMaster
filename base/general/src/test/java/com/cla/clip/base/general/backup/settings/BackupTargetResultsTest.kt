package com.cla.clip.base.general.backup.settings

import com.cla.clip.base.general.config.datastore.createTestHostSettingsStore
import androidx.datastore.preferences.core.stringPreferencesKey
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
            assertFalse(repository.finishPending("old", 200L, "remote_failed"))
            assertEquals("new", repository.snapshot().pendingUpload?.artifactId)
            assertEquals(0L, repository.snapshot().webDavResult.completedAt)
            assertTrue(repository.finishPending("new", 300L, "remote_failed"))
            assertEquals("failed", repository.snapshot().pendingUpload?.phase)
            assertTrue(repository.snapshot().webDavResult.failed)
            assertFalse(repository.updatePendingPhase("new", "queued"))
            assertFalse(repository.finishPending("new", 400L))
            assertEquals(300L, repository.snapshot().webDavResult.completedAt)
        } finally { scope.cancel() }
    }
}
