package com.cla.clip.base.general.search

import com.cla.clip.base.general.entity.ClipVisibilityScope
import org.junit.Assert.*
import org.junit.Test

/** 多选构造器使用真实参数化 SQL；数据库执行行为另外由 Room 用例覆盖。 */
class ClipMultiFieldSearchQueryTest {
    /** 七项默认只能读取明确列，不能偷偷使用综合索引或 FTS。 */
    @Test fun allFieldsUseSevenExplicitBranches() {
        /** 默认完整集合包含七种业务字段。 */
        val query = query(ClipSearchField.entries.toSet(), setOf("fixture.source"))
        assertEquals(7, ClipSearchField.entries.size)
        listOf("c.content", "c.link", "p.title", "p.description", "p.site_name", "c.source_app_package").forEach { column ->
            assertTrue("缺失字段 $column", query.sql.contains("$column LIKE ?"))
        }
        assertFalse(query.sql.contains("search_text"))
        assertFalse(query.sql.contains("MATCH"))
    }

    /** 没有名称命中只排除名称分支，正文条件必须仍然在同一 OR 组内。 */
    @Test fun emptyNameBranchDoesNotEliminateContent() {
        /** 正文和来源名构成并集，名称本轮没有候选。 */
        val sql = query(setOf(ClipSearchField.Content, ClipSearchField.SourceName), emptySet()).sql
        assertTrue(sql.contains("(c.content LIKE ? ESCAPE '\\' OR 0)"))
        assertFalse(sql.contains("AND 0"))
    }

    /** 包名来自剪贴自身，无需标题连接或来源名称解析。 */
    @Test fun packageSearchDoesNotRequireNameOrPreview() {
        /** 只有包名分支，传 null 解析结果必须合法。 */
        val sql = query(setOf(ClipSearchField.SourcePackage)).sql
        assertTrue(sql.contains("c.source_app_package LIKE ?"))
        assertFalse(sql.contains("JOIN"))
    }

    /** 字段全取消是非法调用，数据库必须显式空结果而非默认全选。 */
    @Test fun emptyFieldsFailClosedEvenWithoutKeyword() {
        /** 空词也不能绕过空集合防线。 */
        val query = buildClipFieldSearchQuery(emptySet(), "", null, null, emptySet(), null, ClipVisibilityScope.VisibleOnly)
        assertTrue(query.sql.contains("AND 0"))
    }

    /** 选择点击顺序不应改变实际 SQL 或参数顺序。 */
    @Test fun fieldSetOrderDoesNotChangeQuery() {
        assertEquals(query(linkedSetOf(ClipSearchField.LinkTitle, ClipSearchField.Content)).sql,
            query(linkedSetOf(ClipSearchField.Content, ClipSearchField.LinkTitle)).sql)
    }

    /** 手动来源限制与名称集合都很多时，总绑定数量仍有界。 */
    @Test fun combinedLargeSourceSetsKeepBindingsBounded() {
        /** 合成大量身份，不使用设备应用列表。 */
        val packages = (1..1_200).map { "fixture.$it" }.toSet()
        /** 两层来源限制同时启用，不能各自满足上限却合计超限。 */
        val query = buildClipFieldSearchQuery(ClipSearchField.entries.toSet(), "词", 0, 9_000, packages, packages, ClipVisibilityScope.VisibleOnly)
        assertTrue(query.argCount < 30)
    }

    /** 为普通范围构造非空字面短语查询。 */
    private fun query(
        /** 本次目标集合。 */ fields: Set<ClipSearchField>,
        /** 名称命中身份，其他字段不需要。 */ matches: Set<String>? = null,
    ) = buildClipFieldSearchQuery(fields, "ab cd", null, null, emptySet(), matches, ClipVisibilityScope.VisibleOnly)
}
