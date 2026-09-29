package com.cla.clip.base.general.config.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File
import java.io.IOException

/** 宿主设置临时文件测试，保护默认读取、提交持久性和单文件唯一实例约束。 */
class HostSettingsStoreTest {
    /** 每个测试独立的合成文件目录，避免依赖真实用户设置。 */
    @get:Rule val folder = TemporaryFolder(File("build"))

    @Test
    /** 不存在的设置文件应正常产生空快照，具体默认值由领域仓库解释。 */
    fun missingFileReturnsEmptyPreferences() = runBlocking {
        /** 当前测试独占的 DataStore 协程作用域。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 尚不存在的宿主文件。 */
            val file = File(folder.root, "missing.preferences_pb")
            /** 测试中的真实临时文件 DataStore。 */
            val store = createTestHostSettingsStore(scope, file)
            assertEquals(0, store.snapshot().asMap().size)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    /** 提交返回后关闭旧实例，新实例仍必须读取已写入的值。 */
    fun committedValueSurvivesRecreation() = runBlocking {
        /** 两次实例共用的合成文件路径。 */
        val file = File(folder.root, "reopen.preferences_pb")
        /** 第一实例的独立协程作用域。 */
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        /** 测试用任务标记键。 */
        val key = longPreferencesKey("task_id")
        try {
            /** 首个真实文件 DataStore。 */
            val firstStore = createTestHostSettingsStore(firstScope, file)
            firstStore.edit { preferences -> preferences[key] = 42L }
        } finally {
            firstScope.coroutineContext[Job]?.cancelAndJoin()
        }
        /** 第二实例的独立协程作用域，仅在旧实例停止后创建。 */
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 重建后的真实文件 DataStore。 */
            val secondStore = createTestHostSettingsStore(secondScope, file)
            assertEquals(42L, secondStore.snapshot()[key])
        } finally {
            secondScope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    /** 同一文件仍有活跃实例时不能另建实例，避免两个缓存覆盖。 */
    fun simultaneousInstancesForSameFileAreRejected(): Unit = runBlocking {
        /** 两个活跃实例试图共用的合成路径。 */
        val file = File(folder.root, "same.preferences_pb")
        /** 两个实例分别使用的测试作用域。 */
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 已先启动读取的首个 DataStore。 */
            val first = createTestHostSettingsStore(firstScope, file)
            first.data.first()
            /** 违规创建的第二个 DataStore。 */
            val second = createTestHostSettingsStore(secondScope, file)
            assertThrows(IllegalStateException::class.java) { runBlocking { second.snapshot() } }
        } finally {
            firstScope.coroutineContext[Job]?.cancelAndJoin()
            secondScope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    /** 读取介质失败必须向上抛出，不能伪装成新安装空设置。 */
    fun readFailureIsNotConvertedToDefaults() {
        /** 故意在读取和写入时失败的合成介质。 */
        val failingStore = object : DataStore<Preferences> {
            /** 读取故障流用于验证宿主包装层不吞掉 IO 错误。 */
            override val data: Flow<Preferences> = flow { throw IOException("synthetic failure") }

            /** 测试不用写入；若误调用则直接失败。 */
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                throw IOException("synthetic failure")
            }
        }
        /** 仅负责传递真实介质错误的宿主包装层。 */
        val store = HostSettingsStore(failingStore)
        assertThrows(IOException::class.java) { runBlocking { store.snapshot() } }
    }}
