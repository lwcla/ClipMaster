package com.cla.clip.base.general.backup.settings

import com.cla.clip.base.general.config.datastore.createTestHostSettingsStore
import com.cla.clip.base.general.backup.BackupKind
import com.cla.clip.base.general.backup.BackupSource
import com.cla.clip.base.general.backup.BackupSuccessSummary
import com.cla.clip.base.general.backup.BackupSummary
import com.cla.clip.base.general.backup.BackupTaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 自动备份版本测试，使用独立 DataStore 文件验证已落盘的变更不会被旧备份确认吞掉。 */
class BackupRuntimeRepositoryTest {
    /** 每个测试独占的合成配置目录，不读取设备或用户设置。 */
    @get:Rule val folder = TemporaryFolder(File("build"))

    @Test
    /** 成功摘要与已覆盖版本必须在同一持久化事务中出现，后续变化仍保持 dirty。 */
    fun successRecordAcknowledgesOnlyExportedRevision() = runBlocking {
        /** 本测试独占的磁盘 DataStore 生命周期。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 存放合成备份状态的文件。 */
            val file = File(folder.root, "backup-success.preferences_pb")
            /** 被测版本仓库。 */
            val repository = BackupRuntimeRepository(createTestHostSettingsStore(scope, file))
            /** 本次导出最初捕获的业务版本。 */
            val exportedRevision = repository.captureRevision()
            repository.markChanged()
            /** 不包含任何用户内容的成功摘要。 */
            val summary = BackupSuccessSummary(
                createdAt = 1L,
                fileName = "synthetic.zip",
                fileSize = 1L,
                source = BackupSource.LocalAuto,
                backupKind = BackupKind.Auto,
                summary = BackupSummary(),
                localSuccess = true,
                webDavSuccess = false,
            )
            repository.recordSuccess(BackupTaskStatus.Success, summary, acknowledgedRevision = exportedRevision)
            /** 状态已成功，但并发新增的版本仍待下次备份。 */
            val saved = repository.snapshot()
            assertTrue(saved.status == BackupTaskStatus.Success)
            assertTrue(saved.dirty)
            assertTrue(saved.backedUpRevision == exportedRevision)
        } finally {
            scope.cancel()
        }
    }

    @Test
    /** 导出期间新增数据时，确认导出前版本仍须保持 dirty。 */
    fun acknowledgingOldRevisionKeepsNewChangesDirty() = runBlocking {
        /** 本测试 DataStore 作用域，退出时取消，防止跨测试实例冲突。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 只承载本测试版本标记的合成磁盘文件。 */
            val file = File(folder.root, "backup-runtime.preferences_pb")
            /** 真正以磁盘文件存储的宿主设置接口。 */
            val store = createTestHostSettingsStore(scope, file)
            /** 被测试的备份运行状态仓库。 */
            val repository = BackupRuntimeRepository(store)

            assertTrue(repository.snapshot().dirty)
            /** 导出开始时捕获的版本，不包含之后发生的变更。 */
            val exportedRevision = repository.captureRevision()
            repository.markChanged()
            repository.acknowledgeRevision(exportedRevision)
            assertTrue(repository.snapshot().dirty)
        } finally {
            scope.cancel()
        }
    }

    @Test
    /** 只有完整导出的最新版本获确认后，才可以清除待备份标记。 */
    fun acknowledgingCurrentRevisionClearsDirty() = runBlocking {
        /** 本测试 DataStore 作用域。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 与其他测试隔离的合成磁盘文件。 */
            val file = File(folder.root, "current.preferences_pb")
            /** 单例存储接口，生命周期限定在当前测试。 */
            val store = createTestHostSettingsStore(scope, file)
            /** 负责标记与确认版本的仓库。 */
            val repository = BackupRuntimeRepository(store)

            repository.markChanged()
            /** 完成本次导出后允许确认的当前版本。 */
            val exportedRevision = repository.captureRevision()
            repository.acknowledgeRevision(exportedRevision)
            assertFalse(repository.snapshot().dirty)
        } finally {
            scope.cancel()
        }
    }
}
