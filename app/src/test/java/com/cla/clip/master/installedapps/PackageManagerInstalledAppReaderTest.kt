package com.cla.clip.master.installedapps

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 主进程安装应用读取测试，保护 QUERY_ALL_PACKAGES 直读规则和失败 reason。 */
class PackageManagerInstalledAppReaderTest {
    @Test
    /** 默认扫描范围只保留非系统应用，避免为隐藏系统应用读取名称和图标。 */
    fun normalizeInstalledAppsExcludesSystemAppsByDefault() {
        /** 合成安装条目；系统应用不应成为默认扫描结果。 */
        val apps = normalizeInstalledAppEntries(
            entries = listOf(
                InstalledAppPackageEntry("com.example.user", "User", false),
                InstalledAppPackageEntry("com.example.system", "System", true),
            ),
            selfPackageName = "com.example.self",
        )
        assertEquals(listOf("com.example.user"), apps.map { it.packageName })
    }

    @Test
    /** 读取安装应用时应排除当前 App、保留完整名称、缺失标签保留空名，并按名称和包名稳定排序。 */
    fun loadInstalledAppsNormalizesAndSortsEntries() = runBlocking {
        /** 假包读取器；只提供安装应用条目，展示不依赖桌面入口信息。 */
        val packageReader = FakeInstalledAppPackageReader(
            entries = listOf(
                InstalledAppPackageEntry(
                    packageName = "com.example.self",
                    label = "Self",
                    isSystemApp = false,
                ),
                InstalledAppPackageEntry(
                    packageName = "com.example.blank",
                    label = null,
                    isSystemApp = true,
                ),
                InstalledAppPackageEntry(
                    packageName = "com.example.long",
                    label = "长".repeat(88),
                    isSystemApp = false,
                ),
                InstalledAppPackageEntry(
                    packageName = "com.example.alpha",
                    label = "Alpha",
                    isSystemApp = false,
                ),
            ),
        )
        /** 被测 reader；使用 Unconfined 调度器让单测同步执行 IO 逻辑。 */
        val reader = PackageManagerInstalledAppReader(
            packageReader = packageReader,
            selfPackageName = "com.example.self",
            clock = { 1234L },
            dispatcher = Dispatchers.Unconfined,
        )

        /** 读取结果；当前 App 不应出现在候选中。 */
        val result = reader.loadInstalledApps(showSystemApps = true)

        assertTrue(result is InstalledAppLoadResult.Success)
        result as InstalledAppLoadResult.Success
        assertEquals(1234L, result.loadedAtMillis)
        assertEquals(
            listOf("", "Alpha", "长".repeat(88)),
            result.apps.map { app -> app.appName },
        )
        assertEquals(listOf(true, false, false), result.apps.map { app -> app.isSystemApp })
        assertEquals(true, packageReader.requestedSystemApps)
    }

    @Test
    /** 名称中的首尾空白和纯空白标签都属于原始内容，读取层不得擅自 trim。 */
    fun normalizeInstalledAppsPreservesLabelWhitespace() {
        /** 合成标签用于保护原样传递契约，不依赖真实系统应用名称。 */
        val apps = normalizeInstalledAppEntries(
            entries = listOf(
                InstalledAppPackageEntry("com.example.padded", "  应用名称  ", false),
                InstalledAppPackageEntry("com.example.whitespace", "   ", false),
            ),
            selfPackageName = "com.example.self",
        )
        assertEquals(
            mapOf("com.example.padded" to "  应用名称  ", "com.example.whitespace" to "   "),
            apps.associate { app -> app.packageName to app.appName },
        )
    }

    @Test
    /** PackageManager 因包可见性或权限抛出 SecurityException 时应返回专用 reasonCode。 */
    fun loadInstalledAppsMapsSecurityExceptionToPackageVisibilityDenied() = runBlocking {
        /** 假包读取器；模拟 Android 包可见性拒绝。 */
        val packageReader = FakeInstalledAppPackageReader(
            entries = emptyList(),
            failure = SecurityException("denied"),
        )
        /** 被测 reader；失败分支同样运行在可控调度器中。 */
        val reader = PackageManagerInstalledAppReader(
            packageReader = packageReader,
            selfPackageName = "com.example.self",
            clock = { 1000L },
            dispatcher = Dispatchers.Unconfined,
        )

        /** 读取结果；需要给 ViewModel 可展示的低敏失败原因。 */
        val result = reader.loadInstalledApps()

        assertTrue(result is InstalledAppLoadResult.Failed)
        result as InstalledAppLoadResult.Failed
        assertEquals(INSTALLED_APP_REASON_PACKAGE_VISIBILITY_DENIED, result.reasonCode)
    }

    @Test
    /** 成功读取后仅有自身被排除，正常空结果应允许页面显示无应用提示。 */
    fun loadInstalledAppsReturnsSuccessWhenOnlySelfRemains() = runBlocking {
        /** 假包读取器返回自身，归一化后为空，不抛系统异常。 */
        val packageReader = FakeInstalledAppPackageReader(
            entries = listOf(
                InstalledAppPackageEntry(
                    packageName = "com.example.self",
                    label = "Self",
                    isSystemApp = false,
                ),
            ),
        )
        /** 被测 reader；当前 App 被排除后应为空。 */
        val reader = PackageManagerInstalledAppReader(
            packageReader = packageReader,
            selfPackageName = "com.example.self",
            clock = { 1000L },
            dispatcher = Dispatchers.Unconfined,
        )

        /** 读取已完成且结果正常为空，不能误报权限或加载失败。 */
        val result = reader.loadInstalledApps()

        assertTrue(result is InstalledAppLoadResult.Success)
        result as InstalledAppLoadResult.Success
        assertTrue(result.apps.isEmpty())
        assertEquals(1000L, result.loadedAtMillis)
        assertEquals(0L, result.elapsedMs)
    }

    /** 系统读取正常返回零条目，属于成功空列表，不能仅凭数量猜测读取失败。 */
    @Test
    fun loadInstalledAppsReturnsSuccessForEmptyRawList() = runBlocking {
        /** 合成适配器正常返回空，不访问 PackageManager 或真实设备。 */
        val packageReader = FakeInstalledAppPackageReader(emptyList())
        /** 固定时间及调度器只验证空结果契约。 */
        val reader = PackageManagerInstalledAppReader(packageReader, "com.example.self", { 1000L }, Dispatchers.Unconfined)
        /** 读取结果仍需保留成功元数据，便于协调器结束加载并提交零结果快照。 */
        val result = reader.loadInstalledApps()
        assertTrue(result is InstalledAppLoadResult.Success)
        result as InstalledAppLoadResult.Success
        assertTrue(result.apps.isEmpty())
        assertEquals(1000L, result.loadedAtMillis)
        assertEquals(0L, result.elapsedMs)
    }

    /** 默认范围过滤掉所有系统应用时，同样是正常完成的空结果。 */
    @Test
    fun loadInstalledAppsReturnsSuccessWhenSystemFilterRemovesAllEntries() = runBlocking {
        /** 仅提供一个范围外系统应用，归一化防御检查应移除它。 */
        val packageReader = FakeInstalledAppPackageReader(listOf(InstalledAppPackageEntry("com.example.system", "System", true)))
        /** 默认隐藏系统应用，不把用户选择导致的空列表当作系统失败。 */
        val reader = PackageManagerInstalledAppReader(packageReader, "com.example.self", { 1000L }, Dispatchers.Unconfined)
        /** 成功空结果允许范围胶囊继续切换并重新读取。 */
        val result = reader.loadInstalledApps()
        assertTrue(result is InstalledAppLoadResult.Success)
        result as InstalledAppLoadResult.Success
        assertTrue(result.apps.isEmpty())
        assertEquals(false, packageReader.requestedSystemApps)
    }

    /** 普通读取异常仍是失败，允许空结果不能吞掉真正的系统错误。 */
    @Test
    fun loadInstalledAppsMapsOrdinaryExceptionToScanFailure() = runBlocking {
        /** 合成平台异常只含固定短信息，不使用真实路径或系统消息。 */
        val packageReader = FakeInstalledAppPackageReader(emptyList(), IllegalStateException("synthetic"))
        /** 异常分支继续使用可控时间和调度器。 */
        val reader = PackageManagerInstalledAppReader(packageReader, "com.example.self", { 1000L }, Dispatchers.Unconfined)
        /** 失败类别与原因码不能因为正常空列表支持而改变。 */
        val result = reader.loadInstalledApps()
        assertTrue(result is InstalledAppLoadResult.Failed)
        result as InstalledAppLoadResult.Failed
        assertEquals(INSTALLED_APP_REASON_PACKAGE_SCAN_FAILED, result.reasonCode)
    }

    @Test
    /** 系统开关开启才允许系统应用进入扫描结果，自身仍排除。 */
    fun normalizeInstalledAppsIncludesSystemAppsWhenEnabled() {
        /** 完整范围合成条目；自身不应因开关开启而进入结果。 */
        val apps = normalizeInstalledAppEntries(
            entries = listOf(
                InstalledAppPackageEntry("com.example.self", "Self", false),
                InstalledAppPackageEntry("com.example.system", "System", true),
            ),
            selfPackageName = "com.example.self",
            showSystemApps = true,
        )
        assertEquals(listOf("com.example.system"), apps.map { it.packageName })
    }

    @Test
    /** 快速切换范围时取消必须向上传播，不生成扫描失败状态覆盖新任务。 */
    fun loadInstalledAppsPropagatesCancellation() = runBlocking {
        /** 假扫描抛出取消异常，模拟旧读取被新范围替换。 */
        val packageReader = FakeInstalledAppPackageReader(emptyList(), CancellationException("cancelled"))
        /** 被测读取器；取消不得被 Throwable 失败映射吞掉。 */
        val reader = PackageManagerInstalledAppReader(packageReader, "com.example.self", { 1000L }, Dispatchers.Unconfined)
        try {
            reader.loadInstalledApps()
            fail("取消应继续传播，不能返回失败结果")
        } catch (error: CancellationException) {
            assertEquals("cancelled", error.message)
        }
    }

    /** 测试用 PackageManager 适配器，只提供安装条目并可注入扫描异常。 */
    private class FakeInstalledAppPackageReader(
        /** 原始安装应用条目；保持与 PackageManager 返回值接近但不依赖 Android 对象。 */
        private val entries: List<InstalledAppPackageEntry>,
        /** 可选异常；用于覆盖安全异常和普通扫描失败分支。 */
        private val failure: Throwable? = null,
    ) : InstalledAppPackageReader {

        /** 记录实际扫描范围，验证自动入口和显式完整范围能传到系统适配边界。 */
        var requestedSystemApps: Boolean? = null

        /** 返回合成安装条目；注入异常时模拟系统扫描失败并沿用真实 reader 失败映射。 */
        override suspend fun loadInstalledApplications(showSystemApps: Boolean, selfPackageName: String): List<InstalledAppPackageEntry> {
            requestedSystemApps = showSystemApps
            /** 注入异常时直接抛出，模拟 PackageManager 读取失败。 */
            failure?.let { throwable -> throw throwable }
            return entries
        }
    }
}
