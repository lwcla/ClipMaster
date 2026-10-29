package com.cla.clip.base.general.search

import com.cla.clip.base.general.entity.ClipVisibilityScope
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** 名称查询自动匹配的历史来源可能很多，不能依赖设备的 SQL 变量数量上限。 */
class ClipFieldSearchQueryTest {
    /** 空身份、前缀相似名称、分隔符与中文都必须有无碰撞 UTF-8 编码。 */
    @Test fun sourceEncodingKeepsExactIdentityBoundaries() {
        assertEquals("||61|6162|62|7C|E4B8AD|", encodeSearchSourcePackages(setOf("中", "ab", "", "|", "b", "a")))
    }
    /** 超过常见低上限的合成来源仍应使用固定数量绑定参数。 */
    @Test fun manySourceMatchesDoNotExpandBoundParameterCount() {
        /** 一千二百个名称命中身份，不读取真实安装应用。 */
        val packages = (1..1_200).map { "fixture.$it" }.toSet()
        /** 实际生产构造器输出，保护自动名称匹配路径。 */
        val query = buildClipFieldSearchQuery(setOf(ClipSearchField.SourceName), "fixture", 0, 5_000,
            emptySet(), packages, ClipVisibilityScope.VisibleOnly)
        assertTrue("大量来源匹配必须使用有界绑定参数", query.argCount <= 8)
    }
}
