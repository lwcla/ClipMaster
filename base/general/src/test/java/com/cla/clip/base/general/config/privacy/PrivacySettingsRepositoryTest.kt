package com.cla.clip.base.general.config.privacy

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.cla.clip.base.general.config.datastore.HostSettingsKeys
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

    /** 系统清除数据不会重置 APK 更新历史，重建仓库后仍必须重新说明且禁止预览。 */
    @Test fun clearedSettingsRequireDisclosureEvenOnUpdatedPackage() = withStore { store ->
        /** 清除前已经完成说明并开启预览的安装。 */
        val repository = PrivacySettingsRepository(store)
        repository.initialize()
        repository.completeFirstLaunch(enablePreview = true)
        // 模拟系统清除应用数据后的空设置，不清理设备上的任何真实数据。
        store.edit { preferences -> preferences.clear() }
        /** 重启后的仓库只读取空设置，APK 更新历史不再作为初始化输入。 */
        val restartedRepository = PrivacySettingsRepository(store)
        restartedRepository.initialize()
        assertEquals(PrivacySettingsSnapshot(false, false), restartedRepository.snapshot())
    }

    /** 安装身份或其他设置的存在不能替代用户处理说明，否则冷启动写入会绕过弹窗。 */
    @Test fun unrelatedSavedSettingsDoNotCompleteDisclosure() = withStore { store ->
        // 合成安装身份只用于证明其他领域已写入，不代表用户完成首次选择。
        store.edit { preferences -> preferences[HostSettingsKeys.INSTALL_ID] = "12345678901234567890" }
        /** 首次字段缺失的真实仓库。 */
        val repository = PrivacySettingsRepository(store)
        repository.initialize()
        assertEquals(PrivacySettingsSnapshot(false, false), repository.snapshot())
    }

    /** 暂不开启只关闭可选网络，仍完成说明，不应下一次又拦截前台。 */
    @Test fun deferCompletesDisclosureWithoutEnablingNetwork() = withStore { store ->
        /** 被测仓库复用真实 DataStore。 */
        val repository = PrivacySettingsRepository(store)
        repository.initialize()
        assertEquals(PrivacySettingsSnapshot(false, false), repository.snapshot())
        assertTrue(repository.completeFirstLaunch(enablePreview = false))
        assertEquals(PrivacySettingsSnapshot(true, false), PrivacySettingsRepository(store).snapshot())
    }

    /** 首次同意只写一次，重复回调不能覆盖之后的手动关闭。 */
    @Test fun duplicateAcceptanceDoesNotOverwriteLaterPreviewChoice() = withStore { store ->
        /** 本次首次说明入口。 */
        val repository = PrivacySettingsRepository(store)
        repository.initialize()
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
        repository.initialize()
        repository.completeFirstLaunch(false)
        repository.setPreviewEnabled(true)
        assertEquals(PrivacySettingsSnapshot(true, true), PrivacySettingsRepository(store).snapshot())
    }

    /** 正常重启或升级保留真实选择，不能重新弹窗或覆盖后来手动关闭的预览。 */
    @Test fun initializationPreservesCompletedDisclosureAndLaterPreviewChoice() = withStore { store ->
        /** 先真实完成一次选择，不以旧安装标识代替用户同意。 */
        val repository = PrivacySettingsRepository(store)
        repository.initialize()
        repository.completeFirstLaunch(enablePreview = true)
        assertEquals(PrivacySettingsSnapshot(true, true), repository.snapshot())
        repository.setPreviewEnabled(false)
        /** 升级或重启后的仓库仍以持久化选择为准。 */
        val restartedRepository = PrivacySettingsRepository(store)
        restartedRepository.initialize()
        assertEquals(PrivacySettingsSnapshot(true, false), restartedRepository.snapshot())
    }

    /** 未操作就退出的新安装在升级后仍需首次说明。 */
    @Test fun unhandledFreshInstallStaysUnhandledAfterUpgrade() = withStore { store ->
        /** 首次启动写入未处理状态，重启或升级都不能自动变成已处理。 */
        val repository = PrivacySettingsRepository(store)
        repository.initialize()
        PrivacySettingsRepository(store).initialize()
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
