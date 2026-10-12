package com.cla.clip.base.general.config

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.cla.clip.base.general.config.ad.AdSettingsRepository
import com.cla.clip.base.general.config.clip.ClipSettingsRepository
import com.cla.clip.base.general.config.datastore.HostSettingsKeys
import com.cla.clip.base.general.config.datastore.createTestHostSettingsStore
import com.cla.clip.base.general.config.download.VideoDownloadRuntimeStore
import com.cla.clip.base.general.config.installation.InstallationIdentityRepository
import com.cla.clip.base.general.config.update.AppUpdateCheckStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 领域仓库真实临时文件测试，保护默认值、事务与运行状态的失败边界。 */
class HostSettingsRepositoriesTest {
    /** 测试文件不接触真实安装身份与用户设置。 */
    @get:Rule val folder = TemporaryFolder(File("build"))

    /** 合成非空身份，覆盖长度、字符与空白差异；读取不得按生成格式替换或规范化。 */
    private val nonEmptyInstallIds = listOf(
        "1234567890123456789",
        "0123456789012345678x",
        "00000000-0000-0000-0000-000000000001",
        "  legacy-id  ",
        "   ",
    )

    @Test
    /** 新安装默认值应与旧业务规则一致，广告隐私默认为未同意。 */
    fun missingKeysMapToSafeDomainDefaults() = runBlocking {
        /** 独立 DataStore 生命周期。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 测试用真实文件介质。 */
            val store = createTestHostSettingsStore(scope, File(folder.root, "defaults.preferences_pb"))
            assertEquals(ClipItemQuickAction.Copy, ClipSettingsRepository(store).snapshot().quickAction)
            assertEquals(emptySet<String>(), ClipSettingsRepository(store).snapshot().blockedPackages)
            assertEquals(30, ClipSettingsRepository(store).snapshot().recycleBinRetentionDays)
            assertEquals("auto", AdSettingsRepository(store).snapshot().activeSourceId)
            assertTrue(AdSettingsRepository(store).snapshot().adsGlobalEnabled)
            assertEquals("unknown", AdSettingsRepository(store).snapshot().consentState)
            assertEquals("", AdSettingsRepository(store).snapshot().privacyPolicyVersion)
            assertEquals("", InstallationIdentityRepository(store).snapshot().installId)
            assertEquals(-1L, VideoDownloadRuntimeStore(store).snapshot().taskId)
            assertEquals(0L, AppUpdateCheckStore(store).snapshot().lastCheckAt)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    /** 多个并发加入必须基于同一串行事务的最新名单，不得丢失先前写入。 */
    fun concurrentBlockedPackageAddsKeepEveryPackage() = runBlocking {
        /** 独立 DataStore 生命周期。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 测试用真实文件介质。 */
            val store = createTestHostSettingsStore(scope, File(folder.root, "packages.preferences_pb"))
            /** 来源过滤领域入口。 */
            val repository = ClipSettingsRepository(store)
            /** 并发加入的合成包名任务。 */
            val writes = (0..19).map { index -> async(Dispatchers.Default) { repository.addBlockedPackage("com.demo.$index") } }
            assertTrue(writes.awaitAll().all { it })
            assertEquals(20, repository.snapshot().blockedPackages.size)
            assertFalse(repository.addBlockedPackage("com.demo.0"))
            assertTrue(repository.removeBlockedPackage("com.demo.0"))
            assertFalse(repository.removeBlockedPackage("com.demo.0"))
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    /** 单项编辑必须保留其他隐藏规则，仅真实变化提交名单。 */
    fun singlePackageEditsPreserveHiddenRulesAndOnlyRealChangesCommit() = runBlocking {
        /** 独立文件 scope、仓库和初始名单，全部使用合成包名。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 真实 DataStore 事务，覆盖协调器假存储无法证明的 名单原子性。 */
            val store = createTestHostSettingsStore(scope, File(folder.root, "single-packages.preferences_pb"))
            /** 沿用生产仓库增删，不能使用整单替换模拟单项操作。 */
            val repository = ClipSettingsRepository(store)
            repository.replaceBlockedPackages(setOf("com.example.hidden", "com.example.visible"))
            /** 核对两次业务提交保留了候选范围外的已保存规则。 */
            assertTrue(repository.removeBlockedPackage("com.example.visible"))
            assertTrue(repository.addBlockedPackage("com.example.new"))
            assertFalse(repository.addBlockedPackage("com.example.new"))
            assertFalse(repository.removeBlockedPackage("com.example.absent"))
            assertEquals(setOf("com.example.hidden", "com.example.new"), repository.snapshot().blockedPackages)
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    @Test
    /** 完整名单达到容量后拒绝新增且不写入名单，删除规则仍然可提交。 */
    fun fullBlockedListRejectsAdditionWithoutWritingButAllowsRemoval() = runBlocking {
        /** 真实临时文件作用域，容量 fixture 不包含用户数据。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 500 条规则同样包含隐藏项，不能以可见项数量绕过限制。 */
            val store = createTestHostSettingsStore(scope, File(folder.root, "full-packages.preferences_pb"))
            /** 原子仓库是名单状态唯一持久化边界。 */
            val repository = ClipSettingsRepository(store)
            /** 合成满容量名单，隐藏状态不影响规则总量。 */
            val fullPackages = (1..500).map { index -> "com.example.app$index" }.toSet()
            repository.replaceBlockedPackages(fullPackages)
            /** 拒绝新增不改变名单，满容量仍能删除已有规则。 */
            assertFalse(repository.addBlockedPackage("com.example.extra"))
            assertEquals(fullPackages, repository.snapshot().blockedPackages)
            assertTrue(repository.removeBlockedPackage("com.example.app1"))
            assertEquals(499, repository.snapshot().blockedPackages.size)
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    @Test
    /** 未知快捷动作恢复为复制，替换名单时按既有规则去重排序。 */
    fun clipSettingsNormalizePersistedCodesAndPackages() = runBlocking {
        /** 独立 DataStore 生命周期。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 测试用真实文件介质。 */
            val store = createTestHostSettingsStore(scope, File(folder.root, "clip.preferences_pb"))
            /** 剪贴设置仓库。 */
            val repository = ClipSettingsRepository(store)
            assertTrue(repository.replaceBlockedPackages(listOf(" com.demo.B ", "com.demo.a", "com.demo.B", "bad/name")))
            assertEquals(linkedSetOf("com.demo.B", "com.demo.a"), repository.snapshot().blockedPackages)
            repository.setRecycleBinRetentionDays(0)
            assertEquals(1, repository.snapshot().recycleBinRetentionDays)
            repository.setQuickAction(ClipItemQuickAction.Fold)
            assertEquals(ClipItemQuickAction.Fold, repository.snapshot().quickAction)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    /** 并发首次请求必须得到同一个已提交的安装 ID。 */
    fun concurrentInstallIdRequestsReturnOnePersistentValue() = runBlocking {
        /** 独立 DataStore 生命周期。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 测试用真实文件介质。 */
            val store = createTestHostSettingsStore(scope, File(folder.root, "identity.preferences_pb"))
            /** 安装身份领域入口。 */
            val repository = InstallationIdentityRepository(store)
            /** 多个同时请求身份的模拟入口。 */
            val requests = (0..15).map { async(Dispatchers.Default) { repository.getOrCreateInstallId() } }
            /** 所有调用得到的唯一身份值。 */
            val ids = requests.awaitAll().toSet()
            assertEquals(1, ids.size)
            assertTrue(NumericInstallIdGenerator.isValid(ids.single()))
            assertEquals(ids.single(), repository.snapshot().installId)
            repository.setExpectedProcessName(" com.demo:shizuku ")
            assertEquals("com.demo:shizuku", repository.snapshot().expectedProcessName)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    /** 已有非空身份在重复请求后仍原样保留，不因长度或字符变化重新生成。 */
    fun nonEmptyInstallIdsRemainUnchangedAcrossRepeatedRequests() = runBlocking {
        /** 独立 DataStore 生命周期，测试结束后等待文件作用域关闭。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** index 隔离各合成身份的文件；storedId 是应原样保留的非空持久化值。 */
            for ((index, storedId) in nonEmptyInstallIds.withIndex()) {
                /** 每个身份使用独立真实文件，避免前一项影响下一项的生成分支。 */
                val store = createTestHostSettingsStore(scope, File(folder.root, "existing-identity-$index.preferences_pb"))
                /** preferences 是本次事务快照，写入受控身份以模拟已生成的安装状态。 */
                store.edit { preferences -> preferences[HostSettingsKeys.INSTALL_ID] = storedId }
                /** 生产仓库是重复获取身份的唯一入口。 */
                val repository = InstallationIdentityRepository(store)
                assertEquals(storedId, repository.getOrCreateInstallId())
                assertEquals(storedId, repository.getOrCreateInstallId())
                assertEquals(storedId, store.snapshot()[HostSettingsKeys.INSTALL_ID])
            }
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    /** 快照和身份流不得把非空身份过滤为空，也不得裁剪其空白。 */
    fun identitySnapshotsExposeNonEmptyInstallIdsWithoutNormalization() = runBlocking {
        /** 独立 DataStore 生命周期，读取只接触测试临时文件。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** index 隔离读取 fixture；storedId 同时作为快照与流的字面量期望值。 */
            for ((index, storedId) in nonEmptyInstallIds.withIndex()) {
                /** 每个文件保存一个合成非空身份，读取前不调用生成入口。 */
                val store = createTestHostSettingsStore(scope, File(folder.root, "identity-snapshot-$index.preferences_pb"))
                /** preferences 是受控写入快照，读取阶段不得修改此身份。 */
                store.edit { preferences -> preferences[HostSettingsKeys.INSTALL_ID] = storedId }
                /** 两个读取入口复用生产仓库的身份映射。 */
                val repository = InstallationIdentityRepository(store)
                assertEquals(storedId, repository.snapshot().installId)
                assertEquals(storedId, repository.data.first().installId)
                assertEquals(storedId, store.snapshot()[HostSettingsKeys.INSTALL_ID])
            }
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    /** 已保存空字符串仍表示尚未生成，首次获取后必须持久化并持续复用数字身份。 */
    fun emptyStoredInstallIdGeneratesOnePersistentNumericValue() = runBlocking {
        /** 独立 DataStore 生命周期，空值边界不接触真实安装数据。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 真实临时文件明确保存空字符串，区别于缺失键的既有测试。 */
            val store = createTestHostSettingsStore(scope, File(folder.root, "empty-identity.preferences_pb"))
            /** preferences 是本次事务快照，空字符串模拟尚未生成的身份。 */
            store.edit { preferences -> preferences[HostSettingsKeys.INSTALL_ID] = "" }
            /** 安装身份入口负责空值判断、首次生成和原子提交。 */
            val repository = InstallationIdentityRepository(store)
            /** 首次返回的身份必须已持久化，格式期望独立于生成器校验方法。 */
            val installId = repository.getOrCreateInstallId()
            assertTrue(installId.matches(Regex("[0-9]{20}")))
            assertEquals(installId, repository.getOrCreateInstallId())
            assertEquals(installId, repository.snapshot().installId)
            assertEquals(installId, store.snapshot()[HostSettingsKeys.INSTALL_ID])
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    /** 隐私状态和政策版本一起提交，未知状态不得被识别为同意。 */
    fun adConsentNormalizesUnknownStateAndCommitsVersionTogether() = runBlocking {
        /** 独立 DataStore 生命周期。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 测试用真实文件介质。 */
            val store = createTestHostSettingsStore(scope, File(folder.root, "ad.preferences_pb"))
            /** 广告设置领域入口。 */
            val repository = AdSettingsRepository(store)
            repository.setConsent(" GRANTED ", " 2026-09 ")
            assertEquals("granted", repository.snapshot().consentState)
            assertEquals("2026-09", repository.snapshot().privacyPolicyVersion)
            repository.setConsent("unexpected", "next")
            assertEquals("unknown", repository.snapshot().consentState)
            assertEquals("next", repository.snapshot().privacyPolicyVersion)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
}
