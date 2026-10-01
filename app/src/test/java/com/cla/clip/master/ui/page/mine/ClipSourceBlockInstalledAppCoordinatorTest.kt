package com.cla.clip.master.ui.page.mine

import com.cla.clip.master.installedapps.InstalledAppInfo
import com.cla.clip.master.installedapps.InstalledAppLoadResult
import com.cla.clip.master.installedapps.InstalledAppReader
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** 可见生命周期和刷新回归，使用受控读取器，不访问真实安装列表或真实时钟。 */
class ClipSourceBlockInstalledAppCoordinatorTest {
    @Test
    /** 成功后普通或空扫描失败均保留旧列表、范围与查询条件。 */
    fun refreshFailureKeepsLastUsableSnapshotAndSearch() = runBlocking {
        /** 测试 scope 与可切换读取结果，只有真实协调规则参与状态转换。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        /** 可切换合成失败，用于验证失败后的状态与重试。 */
        var fail = false
        try {
            /** 空扫描失败与系统异常同样不能隐式清空历史成功快照。 */
            val scanner = scanner(scope) { if (fail) failed("package_scan_empty") else success("com.example.a") }
            scanner.onVisible()
            scanner.updateSearchQuery("app")
            fail = true
            scanner.refresh()
            assertEquals(listOf("com.example.a"), scanner.apps.value.map { it.packageName })
            assertEquals("app", scanner.state.value.searchQuery)
            assertEquals("package_scan_empty", scanner.state.value.unavailableReasonCode)
            assertEquals(1, scanner.state.value.loadedAppCount)
            assertFalse(scanner.state.value.loading)
        } finally { scope.cancel() }
    }

    @Test
    /** loading 期间重复下拉不重读，已有可用列表不消失。 */
    fun repeatedPullDuringRefreshDoesNotRestartScan() = runBlocking {
        /** 受控第二次读取，测试无 sleep 或真实线程竞态。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        /** 受控完成门闩，用于挂起 IO 而不依赖真实等待时间。 */
        val gate = CompletableDeferred<Unit>()
        /** 会话读取次数，用于检查重复手势与自动加载边界。 */
        var calls = 0
        try {
            /** 首次成功，第二次等待，重复下拉不能触发第三次。 */
            val scanner = scanner(scope) {
                calls++
                if (calls > 1) gate.await()
                success("com.example.a")
            }
            scanner.onVisible()
            scanner.refresh()
            scanner.refresh()
            assertEquals(2, calls)
            assertTrue(scanner.state.value.loading)
            assertEquals(1, scanner.apps.value.size)
            gate.complete(Unit)
            assertFalse(scanner.state.value.loading)
        } finally { scope.cancel() }
    }

    @Test
    /** 离开可见态清理 loading，首次未成功任务取消后重新进入仍可读取。 */
    fun hiddenPageCancelsLoadingAndNextEntryRetries() = runBlocking {
        /** 第一次模拟不可即时中断的系统调用，第二次正常返回。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        /** 受控完成门闩，用于挂起 IO 而不依赖真实等待时间。 */
        val gate = CompletableDeferred<Unit>()
        /** 会话读取次数，用于检查重复手势与自动加载边界。 */
        var calls = 0
        try {
            /** 旧系统调用晚返回，必须在 apply 前检查取消。 */
            val scanner = scanner(scope) {
                calls++
                if (calls == 1) withContext(NonCancellable) { gate.await() }
                success(if (calls == 1) "com.example.old" else "com.example.new")
            }
            scanner.onVisible()
            assertTrue(scanner.state.value.loading)
            scanner.onHidden()
            assertFalse(scanner.state.value.loading)
            scanner.onVisible()
            assertEquals(listOf("com.example.new"), scanner.apps.value.map { it.packageName })
            gate.complete(Unit)
            assertEquals(listOf("com.example.new"), scanner.apps.value.map { it.packageName })
            assertEquals(2, calls)
        } finally { gate.complete(Unit); scope.cancel() }
    }

    @Test
    /** 系统开关变化必须替换读取中任务，旧范围结果不得覆盖新范围。 */
    fun systemToggleReplacesOldScanAndDropsLateResult() = runBlocking {
        /** 旧范围不可即时中断，新范围读完后仍不能被它覆盖。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        /** 受控完成门闩，用于挂起 IO 而不依赖真实等待时间。 */
        val gate = CompletableDeferred<Unit>()
        /** 读取器接收的范围序列，用于检查系统开关触发替换。 */
        val ranges = mutableListOf<Boolean>()
        try {
            /** 非系统范围等待，系统范围直接完成，读参数就是边界契约。 */
            val scanner = scanner(scope) { showSystem ->
                ranges.add(showSystem)
                if (!showSystem) withContext(NonCancellable) { gate.await() }
                success(if (showSystem) "com.example.system" else "com.example.old")
            }
            scanner.onVisible()
            scanner.updateShowSystemApps(true)
            assertEquals(listOf(false, true), ranges)
            assertEquals(listOf("com.example.system"), scanner.apps.value.map { it.packageName })
            gate.complete(Unit)
            assertEquals(listOf("com.example.system"), scanner.apps.value.map { it.packageName })
            assertFalse(scanner.state.value.loading)
        } finally { gate.complete(Unit); scope.cancel() }
    }

    @Test
    /** 被替换系统调用迟到抛异常也不能清理新任务 loading 或写入失败提示。 */
    fun replacedScanLateExceptionDoesNotOverwriteNewLoading() = runBlocking {
        /** 独立作用域运行真实协调器，不需要真实系统调用。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        /** 旧任务模拟不可中断平台调用，在取消后才抛出普通 IO 异常。 */
        val oldGate = CompletableDeferred<Unit>()
        /** 新范围也处于等待中，用于断言旧异常不能提前结束它的 loading。 */
        val newGate = CompletableDeferred<Unit>()
        try {
            /** 读取范围区分旧任务和新任务，异常在 NonCancellable 内抛出以保留普通异常类型。 */
            val scanner = scanner(scope) { showSystem ->
                if (!showSystem) withContext(NonCancellable) {
                    oldGate.await()
                    throw IOException("synthetic")
                }
                newGate.await()
                success("com.example.system")
            }
            scanner.onVisible()
            scanner.updateShowSystemApps(true)
            oldGate.complete(Unit)
            assertTrue(scanner.state.value.loading)
            assertNull(scanner.state.value.unavailableReasonCode)
            newGate.complete(Unit)
            assertEquals(listOf("com.example.system"), scanner.apps.value.map { it.packageName })
            assertFalse(scanner.state.value.loading)
        } finally { oldGate.complete(Unit); newGate.complete(Unit); scope.cancel() }
    }

    @Test
    /** 已成功的同范围会话重新可见不自动重扫，手动下拉仍可以读取。 */
    fun successfulSessionSkipsAutomaticLoadButManualRefreshRuns() = runBlocking {
        /** 独立会话读取次数，不依赖摘要时间。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        /** 会话读取次数，用于检查重复手势与自动加载边界。 */
        var calls = 0
        try {
            /** 相同范围应复用成功列表，手动刷新才发起新 IO。 */
            val scanner = scanner(scope) { calls++; success("com.example.a") }
            scanner.onVisible()
            scanner.onHidden()
            scanner.onVisible()
            assertEquals(1, calls)
            scanner.refresh()
            assertEquals(2, calls)
        } finally { scope.cancel() }
    }

    @Test
    /** 系统范围切换中离开前台，回来必须补读目标范围，不能错用旧范围成功标记。 */
    fun cancelledSystemRangeChangeRetriesOnNextVisibleEntry() = runBlocking {
        /** 范围变化的首次 IO 等待，重新进入时可直接完成。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        /** 受控完成门闩，用于挂起 IO 而不依赖真实等待时间。 */
        val gate = CompletableDeferred<Unit>()
        /** 系统范围读取次数，取消后重新进入必须补扫。 */
        var systemReads = 0
        try {
            /** 非系统成功并不代表系统列表已经成功读取。 */
            val scanner = scanner(scope) { showSystem ->
                if (showSystem && ++systemReads == 1) gate.await()
                success(if (showSystem) "com.example.system" else "com.example.a")
            }
            scanner.onVisible()
            scanner.updateShowSystemApps(true)
            scanner.onHidden()
            scanner.onVisible()
            assertEquals(2, systemReads)
            assertEquals(listOf("com.example.system"), scanner.apps.value.map { it.packageName })
        } finally { scope.cancel() }
    }

    /** 真实协调器与单一可替换读取边界，回调参数表达本次系统范围。 */
    private fun scanner(scope: CoroutineScope, load: suspend (Boolean) -> InstalledAppLoadResult) =
        ClipSourceBlockInstalledAppCoordinator(scope, object : InstalledAppReader {
            /** 用受控结果替代 PackageManager IO，其余协调行为都来自生产类。 */
            override suspend fun loadInstalledApps(showSystemApps: Boolean) = load(showSystemApps)
        })

    /** 固定成功 fixture，不读取系统时间或图标，名称保留真实字段结构。 */
    private fun success(packageName: String) = InstalledAppLoadResult.Success(
        listOf(InstalledAppInfo(packageName, "App", false)), elapsedMs = 1L, loadedAtMillis = 1L,
    )

    /** 固定失败 fixture，验证失败分类不改变已有数据。 */
    private fun failed(reason: String) = InstalledAppLoadResult.Failed(reason, elapsedMs = 1L, loadedAtMillis = 1L)
}
