package com.cla.clip.master.ui.page.mine

import com.cla.clip.master.installedapps.InstalledAppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证系统开关、搜索和完整已提交名单编辑，防止隐藏规则被误删。 */
class ClipSourceBlockSelectionRulesTest {

    @Test
    /** 已屏蔽或搜索命中的系统应用也受系统开关约束，不能默认放行。 */
    fun systemToggleControlsSavedAndSearchMatchedSystemApps() {
        /** 已屏蔽的系统应用，检测已保存和搜索两个旧例外。 */
        val systemApp = candidate("com.example.system", savedBlocked = true, systemApp = true)
        assertTrue(filterClipSourceBlockCandidates(listOf(systemApp), "", false).isEmpty())
        assertTrue(filterClipSourceBlockCandidates(listOf(systemApp), "system", false).isEmpty())
        assertEquals(listOf(systemApp), filterClipSourceBlockCandidates(listOf(systemApp), " SYSTEM ", true))
        assertTrue(filterClipSourceBlockCandidates(listOf(systemApp), "unknown", true).isEmpty())
    }

    @Test
    /** 未屏蔽的系统应用同样默认隐藏，打开开关后可搜索。 */
    fun systemToggleControlsUnselectedSystemApps() {
        /** 当前扫描的普通系统应用。 */
        val systemApp = candidate("com.example.system", systemApp = true)
        assertTrue(filterClipSourceBlockCandidates(listOf(systemApp), "", false).isEmpty())
        assertEquals(listOf(systemApp), filterClipSourceBlockCandidates(listOf(systemApp), "", true))
    }

    @Test
    /** 无桌面入口或剪贴历史的非系统应用仍默认可见。 */
    fun scannedNonSystemAppIsVisibleByDefault() {
        /** 候选不需要启动入口或历史元数据，只凭扫描的系统标记分类。 */
        val app = candidate("com.example.service")
        assertEquals(listOf(app), filterClipSourceBlockCandidates(listOf(app), "", false))
    }

    @Test
    /** 搜索按当前名称或包名匹配，空名称不妨碍包名命中，且保持候选顺序。 */
    fun searchMatchesCurrentNameOrPackageAndKeepsOrder() {
        /** 已保存优先的候选输入，其中第一项没有名称，第二项有当前扫描名称。 */
        val candidates = listOf(
            candidate("com.example.beta", savedBlocked = true),
            candidate("com.example.alpha", appName = "当前应用"),
        )
        assertEquals(listOf("com.example.beta"), filterClipSourceBlockCandidates(candidates, " BETA ", false).map { it.packageName })
        assertEquals(listOf("com.example.alpha"), filterClipSourceBlockCandidates(candidates, "当前", false).map { it.packageName })
        assertEquals(listOf("com.example.beta", "com.example.alpha"), filterClipSourceBlockCandidates(candidates, "example", false).map { it.packageName })
    }

    @Test
    /** 刷新发现已保存应用后正常显示并保留屏蔽状态，未扫描前无占位行。 */
    fun refreshedInstalledPackageBecomesVisibleAndRemainsSelected() {
        /** 跨安装保留的完整名单。 */
        val committedPackages = setOf("com.example.a")
        /** 安装前扫描为空，保存规则不生成行。 */
        val beforeInstall = buildBlockedSourceAppCandidates(committedPackages, emptyList())
        /** 安装后成功刷新扫描结果，候选恢复屏蔽标记。 */
        val afterInstall = buildBlockedSourceAppCandidates(committedPackages, listOf(InstalledAppInfo("com.example.a", "应用 A", false)))
        /** 最终可见项仍通过完整已提交名单判断勾选。 */
        val visibleAfterInstall = filterClipSourceBlockCandidates(afterInstall, "", false).single()
        assertTrue(beforeInstall.isEmpty())
        assertTrue(visibleAfterInstall.savedBlocked)
        assertTrue(visibleAfterInstall.packageName in committedPackages)
    }

    @Test
    /** 扫描为空时列表为空，候选构建和展示筛选不得修改完整已提交名单。 */
    fun emptyInstalledSnapshotKeepsFullDraft() {
        /** 可变完整名单用于检测意外的就地删除。 */
        val committedPackages = mutableSetOf("com.example.a", "com.example.b")
        /** 没有扫描结果时不生成任何候选。 */
        val candidates = buildBlockedSourceAppCandidates(committedPackages, emptyList())
        assertTrue(filterClipSourceBlockCandidates(candidates, "", true).isEmpty())
        assertEquals(setOf("com.example.a", "com.example.b"), committedPackages)
    }

    /** 构造纯展示候选；只表达当前扫描数据和已保存状态，无历史来源或图标模型。 */
    private fun candidate(
        packageName: String,
        appName: String = "",
        savedBlocked: Boolean = false,
        systemApp: Boolean = false,
    ): BlockedSourceAppCandidate = BlockedSourceAppCandidate(
        packageName = packageName,
        appName = appName,
        systemApp = systemApp,
        savedBlocked = savedBlocked,
    )
}
