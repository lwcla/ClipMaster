package com.cla.clip.base.general.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 剪贴来源 App 过滤纯规则测试，保护保存链路、设置页和备份恢复共用的包名边界。 */
class ClipSourceBlockRulesTest {

    @Test
    /** 规范化只做 trim、去空、去重、排序和非法项过滤，不做大小写归一。 */
    fun normalizePackageSetKeepsExactCaseAndSorts() {
        /** 待规范化的包名集合，包含空白、重复、大小写差异和非法字符。 */
        val packages = listOf(" com.demo.B ", "", "com.demo.a", "com.demo.B", "com.demo/a", "com.demo.b")

        /** 规范化后的包名集合。 */
        val normalized = ClipSourceBlockRules.normalizePackageSet(packages)

        assertEquals(
            linkedSetOf("com.demo.B", "com.demo.a", "com.demo.b"),
            normalized
        )
    }

    @Test
    /** 空来源不命中，完整包名精确命中，大小写不同不会误匹配。 */
    fun isSourceBlockedUsesExactNonBlankPackageName() {
        /** 已保存的过滤名单。 */
        val blockedPackages = linkedSetOf("com.demo.App")

        assertTrue(ClipSourceBlockRules.isSourceBlocked(" com.demo.App ", blockedPackages))
        assertFalse(ClipSourceBlockRules.isSourceBlocked(null, blockedPackages))
        assertFalse(ClipSourceBlockRules.isSourceBlocked("", blockedPackages))
        assertFalse(ClipSourceBlockRules.isSourceBlocked("com.demo.app", blockedPackages))
        assertFalse(ClipSourceBlockRules.isSourceBlocked("com.demo", blockedPackages))
    }

    @Test
    /** 删除手动输入入口仍保留共享包名边界，空白、危险字符与超长来源继续拒绝。 */
    fun normalizeSinglePackageRejectsUnsafeInput() {
        assertEquals(null, ClipSourceBlockRules.normalizeSinglePackage(null))
        assertEquals(null, ClipSourceBlockRules.normalizeSinglePackage(" "))
        assertEquals(null, ClipSourceBlockRules.normalizeSinglePackage("com.example bad"))
        assertEquals(null, ClipSourceBlockRules.normalizeSinglePackage("com.example/bad"))
        assertEquals(null, ClipSourceBlockRules.normalizeSinglePackage("a".repeat(ClipSourceBlockRules.MAX_PACKAGE_NAME_LENGTH + 1)))
    }

    @Test
    /** 合法来源沿用 trim 与精确大小写，不因移除手动校验而改变持久化协议。 */
    fun normalizeSinglePackageKeepsTrimmedExactIdentity() {
        assertEquals("com.vendor.Special_App", ClipSourceBlockRules.normalizeSinglePackage(" com.vendor.Special_App "))
    }

    @Test
    /** 规范化总名单时最多保留协议上限数量。 */
    fun normalizePackageSetLimitsTotalCount() {
        /** 超过上限的包名序列。 */
        val packages = (0..ClipSourceBlockRules.MAX_PACKAGE_COUNT).map { index -> "com.demo.$index" }

        /** 规范化后的包名集合。 */
        val normalized = ClipSourceBlockRules.normalizePackageSet(packages)

        assertEquals(ClipSourceBlockRules.MAX_PACKAGE_COUNT, normalized.size)
    }
}
