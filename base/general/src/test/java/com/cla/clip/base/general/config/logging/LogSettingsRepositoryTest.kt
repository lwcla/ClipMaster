package com.cla.clip.base.general.config.logging

import com.cla.clip.base.general.config.datastore.createTestHostSettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 使用隔离设置介质验证默认关闭、提交回流与重新创建仓库，不接触用户文件。 */
class LogSettingsRepositoryTest {
    /** 临时设置只存测试布尔值。 */
    @get:Rule val folder = TemporaryFolder(File("build"))

    /** 开关属于宿主已提交设置，不能由页面内存冒充。 */
    @Test fun defaultOffAndCommittedToggleSurvivesRepositoryRecreation() = runBlocking {
        /** 隔离测试生命周期。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 单份设置介质由两个领域仓库实例共享。 */
            val store = createTestHostSettingsStore(scope, File(folder.root, "logging.preferences_pb"))
            /** 当前写入入口。 */
            val settings = LogSettingsRepository(store)
            assertFalse(settings.enabled.first())
            settings.setEnabled(true)
            assertTrue(LogSettingsRepository(store).enabled.first())
            settings.setEnabled(false)
            assertFalse(settings.enabled.first())
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
}
