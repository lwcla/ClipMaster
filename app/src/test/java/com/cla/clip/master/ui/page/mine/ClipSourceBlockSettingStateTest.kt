package com.cla.clip.master.ui.page.mine

import com.cla.clip.master.installedapps.InstalledAppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 当前扫描候选规则测试，保护候选范围、已保存优先和名称缺失边界。 */
class ClipSourceBlockSettingStateTest {

    @Test
    /** 未扫描到的保存规则不得生成候选，防止设置名单变成占位行。 */
    fun buildCandidatesIncludesOnlyScannedApps() {
        /** 只有 B 出现在当前扫描中，A 仅是保存规则。 */
        val candidates = buildBlockedSourceAppCandidates(
            blockedPackages = setOf("com.example.a"),
            installedApps = listOf(InstalledAppInfo("com.example.b", "应用 B", false)),
        )
        assertEquals(listOf("com.example.b"), candidates.map { it.packageName })
    }

    @Test
    /** 当前扫描名称缺失时显示空名，不使用包名作为主标题。 */
    fun buildCandidatesKeepsMissingNameEmpty() {
        /** 空名候选仍保留包名身份，供副标题、搜索和系统图标读取使用。 */
        val candidate = buildBlockedSourceAppCandidates(
            blockedPackages = emptySet(),
            installedApps = listOf(InstalledAppInfo("com.example.a", "", false)),
        ).single()
        assertEquals("", candidate.appName)
        assertEquals("com.example.a", candidate.packageName)
    }

    @Test
    /** 已屏蔽应用稳定移到前面，各组保持读取器已确定的名称顺序。 */
    fun buildCandidatesPlacesSavedAppsFirstAndKeepsGroupOrder() {
        /** 扫描器已经按名称排序的合成应用，其中 B/D 被屏蔽。 */
        val scannedApps = listOf(
            InstalledAppInfo("com.example.a", "Alpha", false),
            InstalledAppInfo("com.example.b", "Beta", false),
            InstalledAppInfo("com.example.c", "Charlie", false),
            InstalledAppInfo("com.example.d", "Delta", true),
        )
        /** 候选构建只改变屏蔽分组，不对各组再次排序或去掉系统应用。 */
        val candidates = buildBlockedSourceAppCandidates(setOf("com.example.b", "com.example.d"), scannedApps)
        assertEquals(listOf("com.example.b", "com.example.d", "com.example.a", "com.example.c"), candidates.map { it.packageName })
        assertEquals(listOf(true, true, false, false), candidates.map { it.savedBlocked })
        assertTrue(candidates[1].systemApp)
        assertEquals(listOf("Alpha", "Beta", "Charlie", "Delta"), scannedApps.map { it.appName })
    }
}
