package com.cla.clip.base.general.config.privacy

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.cla.clip.base.general.config.datastore.HostSettingsStore
import com.cla.clip.base.general.config.datastore.createTestHostSettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** 合成设置验证首次选择原子提交及设备绑定偏好，不读取真实用户数据。 */
class PrivacySettingsRepositoryTest {
    /** 每个用例使用独立的 Windows 可写临时文件。 */
    @get:Rule val folder = TemporaryFolder(File("build"))

    /** 暂不开启只关闭可选网络，仍完成说明，不应下一次又拦截前台。 */
    @Test fun deferCompletesDisclosureWithoutEnablingNetwork() = withStore { store ->
        /** 被测仓库复用真实 DataStore。 */
        val repository = PrivacySettingsRepository(store)
        repository.initialize(existingInstallation = false)
        assertEquals(PrivacySettingsSnapshot(false, false), repository.snapshot())
        assertTrue(repository.completeFirstLaunch(enablePreview = false))
        assertEquals(PrivacySettingsSnapshot(true, false), PrivacySettingsRepository(store).snapshot())
    }

    /** 首次同意只写一次，重复回调不能覆盖之后的手动关闭。 */
    @Test fun duplicateAcceptanceDoesNotOverwriteLaterPreviewChoice() = withStore { store ->
        /** 本次首次说明入口。 */
        val repository = PrivacySettingsRepository(store)
        repository.initialize(existingInstallation = false)
        assertTrue(repository.completeFirstLaunch(enablePreview = true))
        assertEquals(PrivacySettingsSnapshot(true, true), repository.snapshot())
        repository.setPreviewEnabled(false)
        assertFalse(repository.completeFirstLaunch(enablePreview = true))
        assertEquals(PrivacySettingsSnapshot(true, false), repository.snapshot())
    }

    /** 用户稍后从设置开预览，不改变说明处理状态或触发任何权限。 */
    @Test fun previewCanBeEnabledAfterDeferringAndSurvivesRecreation() = withStore { store ->
        /** 两次仓库对象共享同一已提交文件。 */
        val repository = PrivacySettingsRepository(store)
        repository.initialize(false)
        repository.completeFirstLaunch(false)
        repository.setPreviewEnabled(true)
        assertEquals(PrivacySettingsSnapshot(true, true), PrivacySettingsRepository(store).snapshot())
    }

    /** 历史安装不补弹，尚未选择的新安装升级也不能被误判为已同意。 */
    @Test fun upgradeMigrationOnlyAppliesWhenNoInstallationDecisionExists() = withStore { store ->
        /** 缺键历史安装沿用原来预览行为。 */
        val repository = PrivacySettingsRepository(store)
        repository.initialize(true)
        assertEquals(PrivacySettingsSnapshot(true, true), repository.snapshot())
        repository.setPreviewEnabled(false)
        repository.initialize(true)
        assertFalse(repository.snapshot().previewEnabled)
    }

    /** 未操作就退出的新安装在升级后仍需首次说明。 */
    @Test fun unhandledFreshInstallStaysUnhandledAfterUpgrade() = withStore { store ->
        /** 首次启动时写入 false 用于区分新安装与遗留缺键。 */
        val repository = PrivacySettingsRepository(store)
        repository.initialize(false)
        repository.initialize(true)
        assertEquals(PrivacySettingsSnapshot(false, false), repository.snapshot())
    }

    /** IO 故障不可伪装成同意成功，否则会错误启动联网或权限弹窗。 */
    @Test fun failedStorageNeverReportsSuccessfulChoice() = runBlocking {
        /** 只提供失败介质，不模拟仓库业务行为。 */
        val brokenStore = HostSettingsStore(object : DataStore<Preferences> {
            /** 读失败直接抛出，以验证仓库不吞异常。 */
            override val data: Flow<Preferences> = flow { throw IOException("synthetic") }
            /** 写失败不能提交任何设置。 */
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw IOException("synthetic")
        })
        /** 真实仓库应传播介质故障。 */
        val repository = PrivacySettingsRepository(brokenStore)
        try { repository.completeFirstLaunch(true); fail("写失败不应成功") } catch (_: IOException) { }
        try { repository.snapshot(); fail("读失败不应返回默认状态") } catch (_: IOException) { }
    }

    /** 每个用例独立创建真实介质，结束后等待写入协程清理，避免 Windows 文件锁残留。 */
    private fun withStore(block: suspend (HostSettingsStore) -> Unit) = runBlocking {
        /** 文件 IO 生命周期独立于测试调用协程。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try { block(createTestHostSettingsStore(scope, File(folder.root, "privacy.preferences_pb"))) }
        finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }
}
