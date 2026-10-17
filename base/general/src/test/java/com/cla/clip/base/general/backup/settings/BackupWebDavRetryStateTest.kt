package com.cla.clip.base.general.backup.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.cla.clip.base.general.config.datastore.HostSettingsKeys
import com.cla.clip.base.general.config.datastore.HostSettingsStore
import com.cla.clip.base.general.config.datastore.createTestHostSettingsStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 真实 DataStore 事务保护重试上限、最新轮次和历史结果，不连接服务器。 */
class BackupWebDavRetryStateTest {
    /** 合成安装目录，与用户配置完全隔离。 */
    @get:Rule val folder = TemporaryFolder(File("build"))

    /** 两次批准后耗尽，进程内重建及同身份更新不能重置次数或目录许可。 */
    @Test fun networkRetryQueuesTwiceThenExhaustsAcrossRepositoryRecreation() = runBlocking {
        /** 独占持久化生命周期。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 同一介质用于重建仓库验证持久化。 */
            val store = createTestHostSettingsStore(scope, File(folder.root, "retry.preferences_pb"))
            /** 被测事务入口。 */
            val runtime = BackupRuntimeRepository(store)
            runtime.recordTargetResult(BackupResultTarget.WebDav, 10L)
            runtime.recordTargetResult(BackupResultTarget.Local, 20L)
            runtime.replacePending(pending("A"))
            assertTrue(runtime.claimWebDavDirectoryRecoveryIfCurrentRound("A"))
            assertEquals(BackupWebDavRetryDecision.Queued(1), runtime.requestWebDavRetryIfCurrentRound("A"))
            assertEquals("queued", runtime.snapshot().pendingUpload!!.phase)
            runtime.replacePending(pending("A"))
            assertEquals(1, runtime.snapshot().pendingUpload!!.webDavNetworkRetryCount)
            assertEquals(BackupWebDavRetryDecision.Queued(2), BackupRuntimeRepository(store).requestWebDavRetryIfCurrentRound("A"))
            assertEquals(BackupWebDavRetryDecision.Exhausted, runtime.requestWebDavRetryIfCurrentRound("A"))
            assertEquals(10L, runtime.snapshot().webDavResult.completedAt)
            assertEquals(20L, runtime.snapshot().localResult.completedAt)
            assertTrue(runtime.snapshot().pendingUpload!!.webDavDirectoryRecoveryAttempted)
            runtime.replacePending(pending("B").copy(phase = "generating"))
            assertEquals(0, runtime.snapshot().pendingUpload!!.webDavNetworkRetryCount)
            assertEquals(BackupWebDavRetryDecision.Obsolete, runtime.requestWebDavRetryIfCurrentRound("A"))
            assertFalse(runtime.recordTargetResultIfCurrentRound("A", BackupResultTarget.WebDav, 99L, "remote_failed"))
            runtime.clearPending("A")
            assertEquals("B", runtime.snapshot().pendingUpload!!.artifactId)
            runtime.clearPending("B")
            assertFalse(store.snapshot().contains(HostSettingsKeys.PENDING_WEBDAV_NETWORK_RETRY_COUNT))
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    /** 终态、半包、身份错配不能批准，缺键兼容为零，异常计数按上限收敛。 */
    @Test fun retryCannotReviveTerminalOrSupersededRound() = runBlocking {
        /** 独占测试存储生命周期。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 真实介质供固定状态和损坏字段验证。 */
            val store = createTestHostSettingsStore(scope, File(folder.root, "phases.preferences_pb"))
            /** 同一仓库逐轮检查阶段白名单。 */
            val runtime = BackupRuntimeRepository(store)
            // phase 同时作为合成轮次，避免同身份更新绕过终态限制的测试歧义。
            for (phase in listOf("failed", "succeeded", "cancelled", "result_store_failed", "unselected", "generating", "unknown")) {
                runtime.replacePending(pending(phase).copy(phase = phase))
                assertEquals(BackupWebDavRetryDecision.Obsolete, runtime.requestWebDavRetryIfCurrentRound(phase))
            }
            runtime.replacePending(pending("active"))
            store.edit { preferences -> preferences.remove(HostSettingsKeys.PENDING_WEBDAV_NETWORK_RETRY_COUNT) }
            assertEquals(0, runtime.snapshot().pendingUpload!!.webDavNetworkRetryCount)
            store.edit { preferences -> preferences[HostSettingsKeys.PENDING_WEBDAV_NETWORK_RETRY_COUNT] = 99 }
            assertEquals(2, runtime.snapshot().pendingUpload!!.webDavNetworkRetryCount)
            assertEquals(BackupWebDavRetryDecision.Exhausted, runtime.requestWebDavRetryIfCurrentRound("active"))
            store.edit { preferences -> preferences[HostSettingsKeys.PENDING_ARTIFACT_ID] = "other" }
            assertEquals(BackupWebDavRetryDecision.Obsolete, runtime.requestWebDavRetryIfCurrentRound("active"))
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    /** 多个并发申请共享两个额度，不能因读改写竞争无限补传。 */
    @Test fun concurrentRetryClaimsRemainBounded() = runBlocking {
        /** 真实事务序列化并发请求。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 独立安装内状态。 */
            val runtime = BackupRuntimeRepository(createTestHostSettingsStore(scope, File(folder.root, "concurrent.preferences_pb")))
            runtime.replacePending(pending("A"))
            /** 各申请同时发起，额度与 queued 在同一事务提交。 */
            val results = coroutineScope { List(8) { async { runtime.requestWebDavRetryIfCurrentRound("A") } }.awaitAll() }
            assertEquals(2, results.count { it is BackupWebDavRetryDecision.Queued })
            assertEquals(6, results.count { it == BackupWebDavRetryDecision.Exhausted })
            assertEquals(2, runtime.snapshot().pendingUpload!!.webDavNetworkRetryCount)
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    /** 未落盘的等待不能返回成功批准，阻止无次数保护的网络重发。 */
    @Test fun retryStoreFailurePropagates() {
        /** 写入故障假介质，读取为空不影响本测试的事务失败契约。 */
        val store = object : DataStore<Preferences> {
            /** 无敏感字段的测试快照。 */
            override val data: Flow<Preferences> = flowOf(emptyPreferences())
            /** 所有写入明确失败，不能悄悄降级为内存状态。 */
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw IOException("synthetic")
        }
        assertThrows(IOException::class.java) {
            runBlocking { BackupRuntimeRepository(HostSettingsStore(store)).requestWebDavRetryIfCurrentRound("A") }
        }
    }

    /** 最小合成上传轮次，本地阶段用于证明两目标隔离。 */
    private fun pending(id: String) = BackupPendingUpload(id, "hash", "auto", "uploading", 100L, "local", "succeeded")
}
