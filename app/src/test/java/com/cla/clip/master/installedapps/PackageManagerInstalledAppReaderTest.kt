package com.cla.clip.master.installedapps

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 主进程安装应用读取测试，保护 QUERY_ALL_PACKAGES 直读规则和失败 reason。 */
class PackageManagerInstalledAppReaderTest {

    @Test
    /** 读取安装应用时应排除当前 App、裁剪名称、空标签回退包名，并按名称和包名稳定排序。 */
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
                    label = "   ",
                    isSystemApp = true,
                ),
                InstalledAppPackageEntry(
                    packageName = "com.example.long",
                    label = "长".repeat(MAX_INSTALLED_APP_NAME_LENGTH + 8),
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
        val result = reader.loadInstalledApps()

        assertTrue(result is InstalledAppLoadResult.Success)
        result as InstalledAppLoadResult.Success
        assertEquals(1234L, result.loadedAtMillis)
        assertEquals(
            listOf("Alpha", "com.example.blank", "长".repeat(MAX_INSTALLED_APP_NAME_LENGTH)),
            result.apps.map { app -> app.appName },
        )
        assertEquals(listOf(false, true, false), result.apps.map { app -> app.isSystemApp })
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
    /** 安装列表为空时应返回空列表 reason，避免 UI 误认为仍在同步旧 Shizuku 列表。 */
    fun loadInstalledAppsMapsEmptyListToPackageScanEmpty() = runBlocking {
        /** 假包读取器；模拟卸载重装或可见性配置异常导致的空结果。 */
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

        /** 读取结果；空列表应转成失败态供 UI 展示手动添加兜底。 */
        val result = reader.loadInstalledApps()

        assertTrue(result is InstalledAppLoadResult.Failed)
        result as InstalledAppLoadResult.Failed
        assertEquals(INSTALLED_APP_REASON_PACKAGE_SCAN_EMPTY, result.reasonCode)
    }

    /** 测试用 PackageManager 适配器，只提供安装条目并可注入扫描异常。 */
    private class FakeInstalledAppPackageReader(
        /** 原始安装应用条目；保持与 PackageManager 返回值接近但不依赖 Android 对象。 */
        private val entries: List<InstalledAppPackageEntry>,
        /** 可选异常；用于覆盖安全异常和普通扫描失败分支。 */
        private val failure: Throwable? = null,
    ) : InstalledAppPackageReader {

        /** 返回合成安装条目；注入异常时模拟系统扫描失败并沿用真实 reader 失败映射。 */
        override fun loadInstalledApplications(): List<InstalledAppPackageEntry> {
            /** 注入异常时直接抛出，模拟 PackageManager 读取失败。 */
            failure?.let { throwable -> throw throwable }
            return entries
        }
    }
}
