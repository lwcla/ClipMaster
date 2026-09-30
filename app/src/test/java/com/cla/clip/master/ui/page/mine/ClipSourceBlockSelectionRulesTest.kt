package com.cla.clip.master.ui.page.mine

import com.cla.clip.master.installedapps.InstalledAppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证系统开关、搜索和完整草稿编辑，防止隐藏规则被误删。 */
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
    /** 编辑可见应用后保留未安装或隐藏系统规则，不能用展示列表覆盖完整名单。 */
    fun toggleVisiblePackagesPreservesHiddenRule() {
        /** 初始完整名单，A 隐藏，B 可见且已选。 */
        val originalDraft = setOf("com.example.a", "com.example.b")
        /** 取消可见 B 后仍保存隐藏 A。 */
        val afterRemovingB = toggleBlockedSourcePackage(originalDraft, "com.example.b")
        /** 新增可见 C 后仍保存隐藏 A。 */
        val afterAddingC = toggleBlockedSourcePackage(afterRemovingB, "com.example.c")
        assertEquals(setOf("com.example.a"), afterRemovingB)
        assertEquals(setOf("com.example.a", "com.example.c"), afterAddingC)
        assertEquals(setOf("com.example.a", "com.example.b"), originalDraft)
    }

    @Test
    /** 刷新发现已保存应用后正常显示并保留屏蔽状态，未扫描前无占位行。 */
    fun refreshedInstalledPackageBecomesVisibleAndRemainsSelected() {
        /** 跨安装保留的完整名单。 */
        val draft = setOf("com.example.a")
        /** 安装前扫描为空，保存规则不生成行。 */
        val beforeInstall = buildBlockedSourceAppCandidates(draft, emptyList())
        /** 安装后成功刷新扫描结果，候选恢复屏蔽标记。 */
        val afterInstall = buildBlockedSourceAppCandidates(draft, listOf(InstalledAppInfo("com.example.a", "应用 A", false)))
        /** 最终可见项仍通过完整草稿判断勾选。 */
        val visibleAfterInstall = filterClipSourceBlockCandidates(afterInstall, "", false).single()
        assertTrue(beforeInstall.isEmpty())
        assertTrue(visibleAfterInstall.savedBlocked)
        assertTrue(visibleAfterInstall.packageName in draft)
    }

    @Test
    /** 扫描为空时列表为空，候选构建和展示筛选不得修改完整草稿。 */
    fun emptyInstalledSnapshotKeepsFullDraft() {
        /** 可变完整名单用于检测意外的就地删除。 */
        val draft = mutableSetOf("com.example.a", "com.example.b")
        /** 没有扫描结果时不生成任何候选。 */
        val candidates = buildBlockedSourceAppCandidates(draft, emptyList())
        assertTrue(filterClipSourceBlockCandidates(candidates, "", true).isEmpty())
        assertEquals(setOf("com.example.a", "com.example.b"), draft)
    }

    @Test
    /** 数量上限包含隐藏规则，不能通过只编辑可见项添加第 501 条。 */
    fun toggleRejectsAdditionWhenFullDraftReachesLimit() {
        /** 500 条合成规则，是否可见不影响容量。 */
        val fullDraft = (1..500).map { index -> "com.example.app$index" }.toSet()
        /** 容量已满时新增包名不应写入草稿。 */
        val result = toggleBlockedSourcePackage(fullDraft, "com.example.extra")
        assertEquals(fullDraft, result)
        assertFalse("com.example.extra" in result)
    }

    @Test
    /** 达到上限仍允许取消已有规则，不丢失其他隐藏规则。 */
    fun toggleAllowsRemovalAtLimit() {
        /** 500 条完整规则，移除操作不应被容量检查阻挡。 */
        val fullDraft = (1..500).map { index -> "com.example.app$index" }.toSet()
        /** 取消第一个包名后的完整草稿。 */
        val result = toggleBlockedSourcePackage(fullDraft, "com.example.app1")
        assertEquals(499, result.size)
        assertFalse("com.example.app1" in result)
        assertTrue("com.example.app500" in result)
        assertEquals(500, fullDraft.size)
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
