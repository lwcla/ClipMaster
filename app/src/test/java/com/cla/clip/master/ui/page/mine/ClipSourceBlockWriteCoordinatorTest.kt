package com.cla.clip.master.ui.page.mine

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 保护单项即时持久化、并发点击、失败以及返回等待，全部使用合成包名。 */
class ClipSourceBlockWriteCoordinatorTest {
    @Test
    /** 单项增删必须立即改变完整持久名单，不能重建可见列表而丢失隐藏规则。 */
    fun immediateAddAndRemovePreserveHiddenPackages() = runBlocking {
        /** 独立协程作用域和合成持久名单，Unconfined 避免测试依赖真实线程时序。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        /** 合成的完整持久名单，断言隐藏规则与真实增删结果。 */
        val packages = mutableSetOf("com.example.hidden", "com.example.b")
        try {
            /** 实际提交协调器，仅替换底层存储操作。 */
            val writer = writer(scope, packages)
            assertTrue(writer.setBlocked("com.example.b", false))
            assertEquals(setOf("com.example.hidden"), packages)
            assertTrue(writer.setBlocked("com.example.c", true))
            assertEquals(setOf("com.example.hidden", "com.example.c"), packages)
            assertTrue(writer.state.value.pendingPackages.isEmpty())
        } finally { scope.cancel() }
    }

    @Test
    /** 同一项未提交时不能重复接收，其他包名仍可独立完成。 */
    fun duplicatePendingItemIsRejectedButOtherItemsCanCommit() = runBlocking {
        /** 独立 scope、受控存储等待点和合成结果，避免 sleep。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        /** 受控完成门闩，用于挂起 IO 而不依赖真实等待时间。 */
        val gate = CompletableDeferred<Unit>()
        /** 合成的完整持久名单，断言隐藏规则与真实增删结果。 */
        val packages = mutableSetOf<String>()
        try {
            /** A 的写入被人为挂起，B 可以继续写入。 */
            val writer = ClipSourceBlockWriteCoordinator(
                scope = scope,
                readPackages = { packages.toSet() },
                addPackage = { name ->
                    // name 是目标身份，只有 A 等待；其他写入不受其阻塞。
                    if (name == "com.example.a") gate.await()
                    packages.add(name)
                },
                removePackage = { name -> packages.remove(name) },
            )
            assertTrue(writer.setBlocked("com.example.a", true))
            assertEquals(setOf("com.example.a"), writer.state.value.pendingPackages)
            assertFalse(writer.setBlocked("com.example.a", false))
            assertFalse(writer.setBlocked(" com.example.a ", false))
            assertTrue(writer.setBlocked("com.example.b", true))
            assertEquals(setOf("com.example.b"), packages)
            gate.complete(Unit)
            assertEquals(setOf("com.example.a", "com.example.b"), packages)
            assertTrue(writer.state.value.pendingPackages.isEmpty())
        } finally { scope.cancel() }
    }

    @Test
    /** 已过滤优先仅跟随真实提交结果，拒绝时原分组不移动，成功增删保留同组扫描顺序。 */
    fun committedEditsRegroupFilteredAppsAndFailureKeepsOrder() = runBlocking {
        /** 合成作用域、名单和扫描顺序，测试不依赖真实 PackageManager。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        /** 合成的完整持久名单，断言隐藏规则与真实增删结果。 */
        val packages = mutableSetOf("com.example.b")
        /** 合成扫描顺序，用于检查已提交分组及同组稳定排序。 */
        val apps = listOf("a", "b", "c").map { name ->
            com.cla.clip.master.installedapps.InstalledAppInfo("com.example.$name", name, false)
        }
        try {
            /** 拒绝新增后顺序保持，下一次真实提交才进入已过滤组。 */
            val rejectedWriter = writer(scope, packages, rejectAdds = true)
            rejectedWriter.setBlocked("com.example.c", true)
            assertEquals(listOf("com.example.b", "com.example.a", "com.example.c"), buildBlockedSourceAppCandidates(packages, apps).map { it.packageName })
            /** 正常仓库模拟支持增删，已过滤组沿用 a/b/c 扫描顺序。 */
            val writer = writer(scope, packages)
            writer.setBlocked("com.example.c", true)
            assertEquals(listOf("com.example.b", "com.example.c", "com.example.a"), buildBlockedSourceAppCandidates(packages, apps).map { it.packageName })
            writer.setBlocked("com.example.b", false)
            assertEquals(listOf("com.example.c", "com.example.a", "com.example.b"), buildBlockedSourceAppCandidates(packages, apps).map { it.packageName })
        } finally { scope.cancel() }
    }

    @Test
    /** 事务拒绝新增时必须区分容量限制，且上限不妨碍移除。 */
    fun rejectedAdditionReportsCapacityAndRemovalStillCommits() = runBlocking {
        /** 完整容量由不可见包名同样占用。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        /** 合成的完整持久名单，断言隐藏规则与真实增删结果。 */
        val packages = (1..500).map { index -> "com.example.app$index" }.toMutableSet()
        try {
            /** 仓库在 500 条时拒绝第 501 条，协调器不能把 false 当成功。 */
            val writer = writer(scope, packages, rejectAdds = true)
            writer.setBlocked("com.example.extra", true)
            assertEquals(ClipSourceBlockWriteFailure.Capacity, writer.state.value.lastFailure)
            assertEquals(500, packages.size)
            writer.setBlocked("com.example.app1", false)
            assertEquals(499, packages.size)
            assertNull(writer.state.value.lastFailure)
        } finally { scope.cancel() }
    }

    @Test
    /** 无变化但已经达到目标是幂等成功，不报告保存失败。 */
    fun idempotentTargetDoesNotReportFailure() = runBlocking {
        /** 独立作用域、既有名单和提交后回调计数。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        /** 合成的完整持久名单，断言隐藏规则与真实增删结果。 */
        val packages = mutableSetOf("com.example.a")

        try {
            /** 同目标加入和不存在项移除都返回 false，但持久结果已经正确。 */
            val writer = writer(scope, packages)
            writer.setBlocked("com.example.a", true)
            writer.setBlocked("com.example.absent", false)
            assertNull(writer.state.value.lastFailure)
        } finally { scope.cancel() }
    }

    @Test
    /** 写入失败保留旧名单和分组，结束 busy，允许重试而不是假成功。 */
    fun storageFailureKeepsCommittedStateAndAllowsRetry() = runBlocking {
        /** 可切换的合成 IO 失败与独立作用域。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        /** 合成的完整持久名单，断言隐藏规则与真实增删结果。 */
        val packages = mutableSetOf("com.example.a")
        /** 可切换合成失败，用于验证失败后的状态与重试。 */
        var fail = true
        try {
            /** 取消操作先失败再成功，底层数据作为结果断言。 */
            val writer = ClipSourceBlockWriteCoordinator(
                scope, { packages.toSet() }, { name -> packages.add(name) },
                removePackage = { name ->
                    if (fail) throw IOException("synthetic")
                    packages.remove(name)
                },
            )
            writer.setBlocked("com.example.a", false)
            assertEquals(setOf("com.example.a"), packages)
            assertEquals(ClipSourceBlockWriteFailure.Storage, writer.state.value.lastFailure)
            assertTrue(writer.state.value.pendingPackages.isEmpty())
            fail = false
            writer.setBlocked("com.example.a", false)
            assertTrue(packages.isEmpty())
            assertNull(writer.state.value.lastFailure)
        } finally { scope.cancel() }
    }

    @Test
    /** 已接受的保存未结束时返回必须等待，提交完成后自动给出导航事件。 */
    fun backWaitsForAcceptedWritesThenReturnsWithoutConfirmation() = runBlocking {
        /** 受控写入门闩及作用域，不依赖真实等待时间。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        /** 受控完成门闩，用于挂起 IO 而不依赖真实等待时间。 */
        val gate = CompletableDeferred<Unit>()
        /** 合成的完整持久名单，断言隐藏规则与真实增删结果。 */
        val packages = mutableSetOf<String>()
        try {
            /** 写入完成前没有返回许可，用户返回不会取消事务。 */
            val writer = ClipSourceBlockWriteCoordinator(
                scope, { packages.toSet() },
                addPackage = { name -> gate.await(); packages.add(name) },
                removePackage = { name -> packages.remove(name) },
            )
            writer.setBlocked("com.example.a", true)
            assertFalse(writer.requestBack())
            assertFalse(writer.state.value.navigateBack)
            assertFalse(writer.setBlocked("com.example.b", true))
            gate.complete(Unit)
            assertTrue(writer.state.value.navigateBack)
            assertEquals(setOf("com.example.a"), packages)
        } finally { scope.cancel() }
    }

    @Test
    /** 返回等待期间写入失败应留页，下一次返回无需确认。 */
    fun failedPendingWriteCancelsAutomaticBack() = runBlocking {
        /** 受控失败门闩和独立作用域。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        /** 受控完成门闩，用于挂起 IO 而不依赖真实等待时间。 */
        val gate = CompletableDeferred<Unit>()
        try {
            /** 合成失败在用户请求返回后发生。 */
            val writer = ClipSourceBlockWriteCoordinator(
                scope, { emptySet() },
                addPackage = { gate.await(); throw IOException("synthetic") },
                removePackage = { false },
            )
            writer.setBlocked("com.example.a", true)
            assertFalse(writer.requestBack())
            gate.complete(Unit)
            assertFalse(writer.state.value.navigateBack)
            assertEquals(ClipSourceBlockWriteFailure.Storage, writer.state.value.lastFailure)
            assertTrue(writer.requestBack())
        } finally { scope.cancel() }
    }



    /** 构建使用小型内存存储的真实协调器；失败与提交后回调仅替换边界副作用。 */
    private fun writer(
        scope: CoroutineScope,
        packages: MutableSet<String>,
        rejectAdds: Boolean = false,
    ) = ClipSourceBlockWriteCoordinator(
        scope, { packages.toSet() },
        addPackage = { name -> if (rejectAdds) false else packages.add(name) },
        removePackage = { name -> packages.remove(name) },
    )
}
