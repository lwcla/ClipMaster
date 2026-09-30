package com.cla.clip.master.ui.page.mine

import com.cla.clip.master.installedapps.InstalledAppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证最终列表显隐和完整草稿编辑，防止未安装条目回流或隐藏规则被误删。 */
class ClipSourceBlockSelectionRulesTest {

    @Test
    /** 恢复的未安装应用即使已保存、已勾选，也不能进入最终可见列表。 */
    fun filterHidesSavedUninstalledPackage() {
        /** 完整草稿包含未安装 A 和已安装 B，筛选不得修改它。 */
        val draft = setOf("com.example.a", "com.example.b")
        /** 与恢复场景一致的候选，A 缺少安装信息。 */
        val candidates = listOf(
            candidate("com.example.a", installed = false, savedBlocked = true),
            candidate("com.example.b", savedBlocked = true),
        )

        assertEquals(
            listOf("com.example.b"),
            filterClipSourceBlockCandidates(candidates, draft, "", false).map { it.packageName },
        )
        assertEquals(setOf("com.example.a", "com.example.b"), draft)
    }

    @Test
    /** 搜索和系统开关都不能绕过安装条件，即使候选已保存且有历史来源。 */
    fun searchAndSystemToggleCannotRevealUninstalledPackage() {
        /** 已保存的历史来源 A；安装列表没有它。 */
        val missingApp = candidate("com.example.a", installed = false, savedBlocked = true)
            .copy(historySource = true, appName = "历史应用")
        // 分别覆盖无搜索和包名命中，避免某条可见性分支绕过安装门槛。
        listOf("", "com.example.a").forEach { query ->
            // 开关两种状态都必须隐藏 A；完整草稿仍保留它。
            listOf(false, true).forEach { showSystemApps ->
                assertTrue(
                    "未安装应用不得因搜索或系统开关重新显示",
                    filterClipSourceBlockCandidates(
                        listOf(missingApp), setOf("com.example.a"), query, showSystemApps,
                    ).isEmpty(),
                )
            }
        }
    }

    @Test
    /** 手动添加仅改变完整草稿，不能把未安装包名变成占位行。 */
    fun filterHidesManuallySelectedUninstalledPackage() {
        /** 尚未落盘但已加入草稿的未安装包名。 */
        val draft = setOf("com.example.manual")

        assertTrue(
            filterClipSourceBlockCandidates(
                listOf(candidate("com.example.manual", installed = false)), draft, "", false,
            ).isEmpty(),
        )
        assertEquals(setOf("com.example.manual"), draft)
    }

    @Test
    /** 编辑可见 B/C 后仍保留隐藏 A，防止用当前可见勾选结果覆盖完整名单。 */
    fun toggleVisiblePackagesPreservesHiddenRule() {
        /** 初始完整名单，A 在列表中隐藏，B 可见且已选。 */
        val originalDraft = setOf("com.example.a", "com.example.b")
        /** 取消 B 后待保存的完整名单必须仍包含 A。 */
        val afterRemovingB = toggleBlockedSourcePackage(originalDraft, "com.example.b")
        /** 新增可见 C 后，隐藏 A 仍随完整草稿保存。 */
        val afterAddingC = toggleBlockedSourcePackage(afterRemovingB, "com.example.c")

        assertEquals(setOf("com.example.a"), afterRemovingB)
        assertEquals(setOf("com.example.a", "com.example.c"), afterAddingC)
        assertEquals(setOf("com.example.a", "com.example.b"), originalDraft)
    }

    @Test
    /** 安装列表刷新发现 A 后，真实候选构建与最终筛选恢复显示并保留屏蔽状态。 */
    fun refreshedInstalledPackageBecomesVisibleAndRemainsSelected() {
        /** 跨安装保留的同一份完整名单。 */
        val draft = setOf("com.example.a")
        /** 模拟首次读取尚未安装 A。 */
        val beforeInstall = buildBlockedSourceAppCandidates(draft, emptyList(), emptyList())
        /** 模拟手动刷新成功后读取到 A。 */
        val afterInstall = buildBlockedSourceAppCandidates(
            draft,
            listOf(InstalledAppInfo("com.example.a", "应用 A", false)),
            emptyList(),
        )
        /** 安装后最终可见的单个候选，状态仍由完整名单提供。 */
        val visibleAfterInstall = filterClipSourceBlockCandidates(afterInstall, draft, "", false).single()

        assertTrue(filterClipSourceBlockCandidates(beforeInstall, draft, "", false).isEmpty())
        assertEquals("com.example.a", visibleAfterInstall.packageName)
        assertTrue(visibleAfterInstall.savedBlocked)
        assertTrue(visibleAfterInstall.packageName in draft)
    }

    @Test
    /** 已安装但没有可用图标的应用仍可见，不能把默认图标等同于未安装。 */
    fun installedPackageWithoutIconRemainsVisible() {
        /** 本机已安装、图标为空的候选。 */
        val installedApp = candidate("com.example.a")

        assertEquals(
            listOf(installedApp),
            filterClipSourceBlockCandidates(listOf(installedApp), emptySet(), "", false),
        )
    }

    @Test
    /** 已安装系统应用仍由原有开关、搜索和勾选控制，不因新增门槛全部隐藏。 */
    fun installedSystemPackageKeepsExistingVisibilityRules() {
        /** 未选中的系统应用，默认不显示。 */
        val systemApp = candidate("com.example.system", systemApp = true)

        assertTrue(filterClipSourceBlockCandidates(listOf(systemApp), emptySet(), "", false).isEmpty())
        assertEquals(listOf(systemApp), filterClipSourceBlockCandidates(listOf(systemApp), emptySet(), "", true))
        assertEquals(listOf(systemApp), filterClipSourceBlockCandidates(listOf(systemApp), emptySet(), " SYSTEM ", false))
        assertEquals(
            listOf(systemApp),
            filterClipSourceBlockCandidates(listOf(systemApp), setOf("com.example.system"), "", false),
        )
        assertTrue(filterClipSourceBlockCandidates(listOf(systemApp), emptySet(), "unknown", true).isEmpty())
    }

    @Test
    /** 无桌面入口的非系统应用也必须默认可见，防止筛选误依赖 Launcher 查询。 */
    fun installedNonSystemPackageIsVisibleWithoutLauncherOrHistory() {
        /** 没有桌面入口、没有过滤或历史记录的已安装非系统应用。 */
        val installedApp = candidate("com.example.service")
        /** 同包名曾是剪贴来源时，沿用历史来源默认可见规则。 */
        val historyApp = installedApp.copy(historySource = true)

        assertEquals(listOf(installedApp), filterClipSourceBlockCandidates(listOf(installedApp), emptySet(), "", false))
        assertEquals(listOf(installedApp), filterClipSourceBlockCandidates(listOf(installedApp), emptySet(), "service", false))
        assertEquals(listOf(historyApp), filterClipSourceBlockCandidates(listOf(historyApp), emptySet(), "", false))
    }

    @Test
    /** 安装结果为空时列表为空，但不得借筛选过程清除后续确认要保存的规则。 */
    fun emptyInstalledSnapshotKeepsFullDraft() {
        /** 备份恢复的完整规则快照；用可变集合检测意外就地删除。 */
        val draft = mutableSetOf("com.example.a", "com.example.b")
        /** 读取失败或卸载后的内部候选只有保留规则。 */
        val candidates = buildBlockedSourceAppCandidates(draft, emptyList(), emptyList())

        assertTrue(filterClipSourceBlockCandidates(candidates, draft, "", true).isEmpty())
        assertEquals(setOf("com.example.a", "com.example.b"), draft)
    }

    @Test
    /** 数量上限按包含隐藏规则的完整草稿计算，不能再添加第 501 条。 */
    fun toggleRejectsAdditionWhenFullDraftReachesLimit() {
        /** 500 条合成规则，是否可见不影响容量。 */
        val fullDraft = (1..500).map { index -> "com.example.app$index" }.toSet()
        /** 达到上限后的添加结果，应保留原有所有规则。 */
        val result = toggleBlockedSourcePackage(fullDraft, "com.example.extra")

        assertEquals(fullDraft, result)
        assertFalse("com.example.extra" in result)
    }

    @Test
    /** 达到上限仍允许取消已有规则，且不丢失其他隐藏规则。 */
    fun toggleAllowsRemovalAtLimit() {
        /** 500 条完整规则，测试移除不被容量检查提前拦截。 */
        val fullDraft = (1..500).map { index -> "com.example.app$index" }.toSet()
        /** 取消第一个包名后的结果。 */
        val result = toggleBlockedSourcePackage(fullDraft, "com.example.app1")

        assertEquals(499, result.size)
        assertFalse("com.example.app1" in result)
        assertTrue("com.example.app500" in result)
        assertEquals(500, fullDraft.size)
    }

    /** 构造无平台依赖的完整候选；图标默认不可用，安装状态独立表达。 */
    private fun candidate(
        packageName: String,
        installed: Boolean = true,
        savedBlocked: Boolean = false,
        systemApp: Boolean = false,
    ): BlockedSourceAppCandidate = BlockedSourceAppCandidate(
        packageName = packageName,
        appName = null,
        icon = BlockedSourceAppIcon.None,
        installed = installed,
        systemApp = systemApp,
        savedBlocked = savedBlocked,
        historySource = false,
    )
}
