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
