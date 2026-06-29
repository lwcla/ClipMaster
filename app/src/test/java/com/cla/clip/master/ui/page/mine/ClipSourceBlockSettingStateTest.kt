package com.cla.clip.master.ui.page.mine

import com.cla.clip.base.general.dao.SourceAppData
import com.cla.clip.master.installedapps.InstalledAppInfo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 剪贴来源过滤设置页状态测试，保护候选合并和图标来源模型规则。 */
class ClipSourceBlockSettingStateTest {

    @get:Rule
    /** 测试专用临时目录，用于构造可读或失效的历史图标文件路径。 */
    val temporaryFolder = TemporaryFolder()

    @Test
    /** 当前安装应用存在时，应优先使用主进程直读到的名称和安装图标模型。 */
    fun buildCandidatesPrefersInstalledAppNameAndIconModel() {
        /** 可读历史图标文件；模拟 source_apps 中仍有效的正式来源图标缓存。 */
        val historyIcon = temporaryFolder.newFile("history-icon.png").apply { writeText("history-icon") }
        /** 历史来源缓存；名称和图标都应被当前安装应用信息覆盖。 */
        val historySourceApp = sourceApp(
            packageName = "com.example.app",
            appName = "历史名称",
            iconPath = historyIcon.absolutePath,
            iconHash = "history-hash",
        )
        /** 主进程 PackageManager 当前读到的安装应用；不携带 Drawable 或文件路径。 */
        val installedApp = installedApp(
            packageName = "com.example.app",
            appName = "安装名称",
        )

        /** 合并后的候选列表；单个包名只应出现一次。 */
        val candidates = buildBlockedSourceAppCandidates(
            blockedPackages = setOf("com.example.app"),
            installedApps = listOf(installedApp),
            historySourceApps = listOf(historySourceApp),
        )

        /** 目标候选项；包名是唯一稳定身份。 */
        val candidate = candidates.single()
        assertEquals("安装名称", candidate.appName)
        assertEquals(BlockedSourceAppIcon.Installed(packageName = "com.example.app"), candidate.icon)
        assertTrue(candidate.savedBlocked)
        assertTrue(candidate.historySource)
        assertTrue(candidate.installed)
    }

    @Test
    /** 当前安装应用缺失时，应回退到历史来源可读图标文件模型。 */
    fun buildCandidatesFallsBackToHistoryFileIconWhenNotInstalled() {
        /** 可读历史图标文件；安装列表没有当前包名时用于兜底展示。 */
        val historyIcon = temporaryFolder.newFile("history-icon.png").apply { writeText("history-icon") }
        /** 历史来源缓存；名称和图标都来自 source_apps。 */
        val historySourceApp = sourceApp(
            packageName = "com.example.app",
            appName = "历史名称",
            iconPath = historyIcon.absolutePath,
            iconHash = "history-hash",
        )

        /** 合并后的候选列表；历史来源是唯一可读展示信息。 */
        val candidates = buildBlockedSourceAppCandidates(
            blockedPackages = emptySet(),
            installedApps = emptyList(),
            historySourceApps = listOf(historySourceApp),
        )

        /** 目标候选项；图标模型应指向历史来源文件而不是安装应用图标。 */
        val candidate = candidates.single()
        assertEquals("历史名称", candidate.appName)
        assertEquals(
            BlockedSourceAppIcon.HistoryFile(iconPath = historyIcon.absolutePath, iconHash = "history-hash"),
            candidate.icon,
        )
        assertTrue(candidate.historySource)
    }

    @Test
    /** 历史来源图标文件不可读时，应退化为无图标模型。 */
    fun buildCandidatesDropsUnreadableHistoryIconFile() {
        /** 已不存在的历史图标路径；恢复或清理缓存后可能残留在 source_apps 中。 */
        val missingHistoryIcon = File(temporaryFolder.root, "missing-history-icon.png")
        /** 历史来源缓存；图标路径不可读，只能保留名称和包名。 */
        val historySourceApp = sourceApp(
            packageName = "com.example.app",
            appName = "历史名称",
            iconPath = missingHistoryIcon.absolutePath,
            iconHash = "history-hash",
        )

        /** 合并后的候选列表；坏历史图标应被过滤。 */
        val candidates = buildBlockedSourceAppCandidates(
            blockedPackages = emptySet(),
            installedApps = emptyList(),
            historySourceApps = listOf(historySourceApp),
        )

        /** 目标候选项；图标模型保持无图标，方便 UI 使用通用 App 图标兜底。 */
        val candidate = candidates.single()
        assertEquals("历史名称", candidate.appName)
        assertEquals(BlockedSourceAppIcon.None, candidate.icon)
    }

    @Test
    /** 未安装但已保存的包名仍应展示，且没有图标时保持可移除状态。 */
    fun buildCandidatesKeepsBlockedUnrecognizedPackageWithoutIcon() {
        /** 合并后的候选列表；只有已保存包名，没有历史来源和安装列表。 */
        val candidates = buildBlockedSourceAppCandidates(
            blockedPackages = setOf("com.missing.app"),
            installedApps = emptyList(),
            historySourceApps = emptyList(),
        )

        /** 目标候选项；标题由 UI 回退包名，图标保持为空。 */
        val candidate = candidates.single()
        assertEquals("com.missing.app", candidate.displayName)
        assertEquals(BlockedSourceAppIcon.None, candidate.icon)
        assertTrue(candidate.savedBlocked)
    }

    /** 构造测试用历史来源 App 缓存。 */
    private fun sourceApp(
        packageName: String,
        appName: String,
        iconPath: String?,
        iconHash: String?,
    ): SourceAppData {
        return SourceAppData(
            packageName = packageName,
            appName = appName,
            iconPath = iconPath,
            primaryColor = null,
            iconHash = iconHash,
        )
    }

    /** 构造测试用当前安装应用信息。 */
    private fun installedApp(
        packageName: String,
        appName: String,
        isSystemApp: Boolean = false,
        isLaunchableApp: Boolean = true,
    ): InstalledAppInfo {
        return InstalledAppInfo(
            packageName = packageName,
            appName = appName,
            isSystemApp = isSystemApp,
            isLaunchableApp = isLaunchableApp,
        )
    }
}
