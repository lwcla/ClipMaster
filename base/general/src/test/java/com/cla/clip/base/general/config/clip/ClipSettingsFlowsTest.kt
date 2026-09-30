package com.cla.clip.base.general.config.clip

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.cla.clip.base.general.config.ClipItemQuickAction
import com.cla.clip.base.general.config.datastore.HostSettingsKeys
import com.cla.clip.base.general.config.datastore.HostSettingsStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/** 使用可控内存介质验证字段订阅隔离，不依赖磁盘、真实时间或后台线程。 */
class ClipSettingsFlowsTest {
    @Test
    /** 名单变化只通知名单订阅者；同文件其他领域写入不通知任何剪贴字段。 */
    fun fieldChangesNotifyOnlyTheirSubscribers(): Unit = runBlocking {
        /** 手动推进的已提交设置流，每次提交后让当前线程上的收集任务处理完。 */
        val source = FakeClipSettingsDataStore()
        /** 生产仓库负责默认值、规范化和通知去重。 */
        val repository = ClipSettingsRepository(HostSettingsStore(source))
        /** 三组通知历史分别保护快捷动作、名单和保留天数的订阅边界。 */
        val actions = mutableListOf<ClipItemQuickAction>()
        val packages = mutableListOf<Set<String>>()
        val days = mutableListOf<Int>()
        /** 先启动收集并消费初值，避免订阅时机影响断言。 */
        val collectors = listOf(
            launch(start = CoroutineStart.UNDISPATCHED) { repository.quickAction.collect { actions.add(it) } },
            launch(start = CoroutineStart.UNDISPATCHED) { repository.blockedPackages.collect { packages.add(it) } },
            launch(start = CoroutineStart.UNDISPATCHED) { repository.recycleBinRetentionDays.collect { days.add(it) } },
        )
        try {
            repository.addBlockedPackage("com.demo.reader")
            yield()
            assertEquals("名单变化不得再次通知快捷动作", listOf(ClipItemQuickAction.Copy), actions)
            assertEquals("名单变化不得再次通知保留天数", listOf(30), days)
            assertEquals(listOf(emptySet<String>(), setOf("com.demo.reader")), packages)

            repository.setQuickAction(ClipItemQuickAction.Fold)
            yield()
            repository.setRecycleBinRetentionDays(7)
            yield()
            source.updateData { preferences ->
                // 同文件的升级检查时间不属于剪贴设置。
                preferences.toMutablePreferences().apply { this[HostSettingsKeys.APP_UPDATE_LAST_CHECK_AT] = 42L }
            }
            yield()
            assertEquals(listOf(ClipItemQuickAction.Copy, ClipItemQuickAction.Fold), actions)
            assertEquals(listOf(30, 7), days)
            assertEquals(listOf(emptySet<String>(), setOf("com.demo.reader")), packages)
        } finally {
            collectors.forEach { it.cancelAndJoin() }
        }
    }

    @Test
    /** 单字段仍沿用未知动作回退、名单规范化和保留天数裁剪。 */
    fun fieldFlowsNormalizePersistedValues(): Unit = runBlocking {
        /** 包含异常旧值的合成设置，不使用用户数据。 */
        val source = FakeClipSettingsDataStore(preferencesOf(
            HostSettingsKeys.CLIP_ITEM_QUICK_ACTION to "unknown",
            HostSettingsKeys.BLOCKED_CLIP_SOURCE_PACKAGES to setOf(" com.demo.reader ", "bad/name"),
            HostSettingsKeys.RECYCLE_BIN_RETENTION_DAYS to 0,
        ))
        /** 单字段默认值必须与一次性读取契约一致。 */
        val repository = ClipSettingsRepository(HostSettingsStore(source))
        assertEquals(ClipItemQuickAction.Copy, repository.quickAction.first())
        assertEquals(setOf("com.demo.reader"), repository.blockedPackages.first())
        assertEquals(1, repository.recycleBinRetentionDays.first())
    }

    @Test
    /** 每个字段读取失败仍抛出原始 IO 异常，禁止以默认值伪装读取成功。 */
    fun fieldFlowsPropagateReadFailures() {
        /** 故障介质只测试读取，写入误调用也直接失败。 */
        val source = object : DataStore<Preferences> {
            /** 在收集时抛出合成 IO 故障。 */
            override val data: Flow<Preferences> = flow { throw IOException("synthetic failure") }

            /** 本测试不允许写入。 */
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("synthetic failure")
        }
        /** 不吞掉底层故障的生产设置仓库。 */
        val repository = ClipSettingsRepository(HostSettingsStore(source))
        assertThrows(IOException::class.java) { runBlocking { repository.quickAction.first() } }
        assertThrows(IOException::class.java) { runBlocking { repository.blockedPackages.first() } }
        assertThrows(IOException::class.java) { runBlocking { repository.recycleBinRetentionDays.first() } }
    }
}

/** 仅服务本文件的可控介质；同一测试线程串行提交，不模拟磁盘持久性。 */
private class FakeClipSettingsDataStore(
    /** 默认空设置用于验证业务默认值，也允许注入合成异常旧值。 */
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {
    /** 最近已提交版本，作为下一次事务的输入。 */
    private var current = initial
    /** 保留初值，后续 emit 由测试线程确定性推进。 */
    private val updates = MutableSharedFlow<Preferences>(replay = 1).apply { tryEmit(initial) }
    /** 对外只读设置流。 */
    override val data: Flow<Preferences> = updates

    /** 串行应用生产 edit 变换并通知订阅者；异常不提交新版本。 */
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        current = transform(current)
        updates.emit(current)
        return current
    }
}
