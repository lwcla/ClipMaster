package com.cla.clip.master.ui.page.search

import com.cla.clip.base.general.dao.data.SourceAppCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 名称匹配与来源选择取交集，尤其保护无匹配不能被解释成全部来源。 */
class SearchSourceNameMatcherTest {
    /** 同名来源保留各自身份，名称匹配不依赖图标或包名显示。 */
    @Test fun sameNameAppsAllMatchIgnoringCase() {
        assertEquals(setOf("a", "b"), matchSearchSourcePackages(" CHAT ", listOf(
            SourceAppCandidate("a", "Chat"), SourceAppCandidate("b", "Chat Work"),
            SourceAppCandidate("chat.package", "Browser"),
        ), emptySet()))
    }

    /** 用户来源条件和关键词名称条件必须同时满足。 */
    @Test fun selectedSourcesRestrictNameMatches() {
        assertEquals(setOf("b"), matchSearchSourcePackages("会议", listOf(
            SourceAppCandidate("a", "会议"), SourceAppCandidate("b", "会议助手"),
        ), setOf("b")))
    }

    /** 空交集是零条结果，不是取消来源限制。 */
    @Test fun disjointSourcesProduceExplicitEmptyMatches() {
        assertTrue(matchSearchSourcePackages("Chat", listOf(SourceAppCandidate("a", "Chat")), setOf("b")).isEmpty())
    }

    /** “未知”与包名仅是展示兜底，不能参与真实名称查询。 */
    @Test fun missingNamesAndPackageNamesDoNotMatch() {
        assertTrue(matchSearchSourcePackages("unknown", listOf(SourceAppCandidate("unknown", "")), emptySet()).isEmpty())
    }

    /** 原始名称中的符号保持字面量，不解释通配符或正则。 */
    @Test fun specialCharactersAreLiteral() {
        assertEquals(setOf("a"), matchSearchSourcePackages("%_", listOf(
            SourceAppCandidate("a", "工具%_"), SourceAppCandidate("b", "工具AB"),
        ), emptySet()))
    }
}
