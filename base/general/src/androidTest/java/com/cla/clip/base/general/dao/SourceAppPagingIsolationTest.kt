package com.cla.clip.base.general.dao

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cla.clip.base.general.entity.ClipVisibilityScope
import com.cla.clip.base.general.search.ClipSearchField
import com.cla.clip.base.general.search.buildClipFieldSearchQuery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 在真实 Room 通知上验证来源表隔离，不能用 fake 推断 PagingSource 观察范围。 */
@RunWith(AndroidJUnit4::class)
class SourceAppPagingIsolationTest {
    /** 每次测试独立的内存库，不读取设备真实剪贴数据。 */
    private lateinit var database: AppDatabase

    /** 为分页注册观察前准备合成来源和记录，避免插入通知干扰断言。 */
    @Before
    fun setUp() = runBlocking {
        /** 仪器测试应用 Context，不使用宿主运行数据库。 */
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        database.sourceAppDao().upsert(SourceAppData("fixture.app", "合成来源", null, null, null))
        database.clipDao().upsertClipsForBackup(listOf(ClipData(
            id = 1, content = "keyword", timestamp = 1000, link = "fixture-link",
            sourceAppPackage = "fixture.app", searchText = "keyword",
        )))
        delay(200)
    }

    /** 关闭内存库及通知任务，不保留测试副作用。 */
    @After
    fun tearDown() { database.close() }

    /** 所有共享分页在来源名称、图标、主色变化时都不应失效。 */
    @Test
    fun sourceMetadataDoesNotInvalidateAnyResultPaging() = runBlocking {
        /** 覆盖普通、折叠、回收站、综合搜索及三种新定向路径，空页也必须观察正确的表。 */
        val sources = listOf(
            database.clipDao().loadClipsByFoldState(false),
            database.clipDao().loadClipsByFoldState(true),
            database.clipDao().loadRecycleBinClips(),
            database.clipDao().searchClipsByFilters(null, null, 0, listOf("fixture"), false, false),
            database.clipDao().searchClipsByLike("keyword", null, null, 0, listOf("fixture"), false, false),
            database.clipDao().searchClipsByKeyword("keyword*", "keyword", "keyword", "keyword", null, null, 0, listOf("fixture"), false, false),
        ) + ClipSearchField.entries.map { target ->
            // 来源名命中身份独立传入；SQL 不允许为了名称检索加入来源图标表依赖。
            database.clipDao().searchClipsByFields(buildClipFieldSearchQuery(setOf(target), "keyword", null, null,
                emptySet(), setOf("fixture.app"), ClipVisibilityScope.VisibleOnly))
        }
        sources.forEach { source -> register(source) }
        database.sourceAppDao().upsert(SourceAppData("fixture.app", "更新名称", "fixture-v2.png", 123, "v2"))
        database.invalidationTracker.refreshVersionsAsync()
        delay(500)
        sources.forEach { source -> assertFalse("来源更新不应使结果分页失效", source.invalid) }
    }

    /** 隔离来源不能破坏真实内容和链接预览变化的分页通知。 */
    @Test
    fun contentAndLinkPreviewStillInvalidateResultPaging() = runBlocking {
        /** 已注册观察的普通结果分页。 */
        val source = database.clipDao().loadClipsByFoldState(false)
        register(source)
        /** 确认真实内容通知会到达，避免阴性测试因为根本没有订阅而误通过。 */
        val invalidation = CompletableDeferred<Unit>()
        source.registerInvalidatedCallback { invalidation.complete(Unit) }
        database.clipDao().updatePinStatus(1, 2000)
        withTimeout(5000) { invalidation.await() }
        assertTrue(source.invalid)
        /** 新标题查询也必须收到预览补齐通知，否则首次无标题会永久漏掉命中。 */
        val linkedSource = database.clipDao().searchClipsByFields(buildClipFieldSearchQuery(
            setOf(ClipSearchField.LinkTitle), "标题", null, null, emptySet(), null, ClipVisibilityScope.VisibleOnly,
        ))
        register(linkedSource)
        /** 链接更新的实际失效信号。 */
        val linkInvalidation = CompletableDeferred<Unit>()
        linkedSource.registerInvalidatedCallback { linkInvalidation.complete(Unit) }
        database.linkPreviewDao().upsert(LinkPreviewData("fixture-link", "标题", null, null, null))
        withTimeout(5000) { linkInvalidation.await() }
    }

    /** null 初始值可条件更新；旧任务不得覆盖新版本、名称或复活删除来源。 */
    @Test
    fun conditionalIconCommitPreservesConcurrentDataAndDeletedRows() = runBlocking {
        /** 当前来源 DAO 使用真实 SQLite null 匹配规则。 */
        val dao = database.sourceAppDao()
        assertEquals(1, dao.updateIconIfUnchanged("fixture.app", null, null, "first", "hash", 10))
        assertEquals(0, dao.updateIconIfUnchanged("fixture.app", null, null, "stale", "old", 20))
        assertEquals("first", dao.loadByPackageName("fixture.app")?.iconPath)
        dao.upsert(SourceAppData("fixture.app", "新名称", "first", 10, "hash"))
        assertEquals(1, dao.updateIconIfUnchanged("fixture.app", "first", "hash", "second", "new", 30))
        assertEquals("新名称", dao.loadByPackageName("fixture.app")?.appName)
        database.openHelper.writableDatabase.execSQL("DELETE FROM source_apps WHERE package_name = 'fixture.app'")
        assertEquals(0, dao.updateIconIfUnchanged("fixture.app", "second", "new", "third", "later", 40))
        assertEquals(null, dao.loadByPackageName("fixture.app"))
    }

    /** 来源候选投影相等时过滤表级图标通知，真实名称变化仍然可观察。 */
    @Test
    fun candidateNamesDoNotEmitForIconOnlyWrites() = runBlocking {
        /** 与 Repository 完全相同的候选投影与等值过滤。 */
        val candidates = database.sourceAppDao().observeCandidates().distinctUntilChanged()
        /** 两次有效候选发射应分别是初始值和名称变化。 */
        val emissions = async { candidates.take(2).toList() }
        candidates.first()
        delay(100)
        database.sourceAppDao().updateIconIfUnchanged("fixture.app", null, null, "version", "hash", 10)
        database.invalidationTracker.refreshVersionsAsync()
        delay(300)
        assertFalse("纯图标写入不应产生第二次候选发射", emissions.isCompleted)
        database.sourceAppDao().upsert(SourceAppData("fixture.app", "新名称", "version", 10, "hash"))
        /** 等待真实名称通知，超时代表候选订阅被错误截断。 */
        val values = withTimeout(5000) { emissions.await() }
        assertEquals(listOf("合成来源", "新名称"), values.map { it.single().appName })
    }

    /** Provider 读取旧坏路径后，不能清空并发后台补齐的新版本。 */
    @Test
    fun staleProviderClearMustPreserveNewlyCommittedIcon() = runBlocking {
        /** 同一个真实 DAO 模拟两条图标链路的交错，不使用线程时间猜测。 */
        val dao = database.sourceAppDao()
        /** Provider 预判时看到的旧来源快照。 */
        val stale = dao.loadByPackageName("fixture.app")!!
        assertEquals(1, dao.updateIconIfUnchanged(stale.packageName, stale.iconPath, stale.iconHash, "new", "hash", 42))
        assertEquals(0, dao.clearIconCache(stale.packageName, stale.iconPath, stale.iconHash))
        assertEquals("旧清理不得抹掉刚提交的新图标", "new", dao.loadByPackageName(stale.packageName)?.iconPath)
        assertEquals(1, dao.clearIconCache(stale.packageName, "new", "hash"))
        assertEquals(null, dao.loadByPackageName(stale.packageName)?.iconPath)
    }

    /** 首次 load 使 Room PagingSource 真正注册通知，加载失败不能被当成阴性通过。 */
    private suspend fun register(source: PagingSource<Int, *>) {
        /** 合成首页请求；禁止用未加载的 PagingSource 验证观察行为。 */
        val result = source.load(PagingSource.LoadParams.Refresh(null, 20, false))
        if (result is PagingSource.LoadResult.Error) throw result.throwable
        assertTrue(result is PagingSource.LoadResult.Page)
    }
}
