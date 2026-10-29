package com.cla.clip.base.general.search

import org.junit.Assert.assertEquals
import org.junit.Test

/** 保护历史和定向搜索的参数绑定语义，用户符号不能变成 SQL 通配符。 */
class SearchLikePatternTest {
    /** 同时覆盖转义符本身与两种 LIKE 通配符。 */
    @Test fun escapesWildcardsAndEscapeCharacter() {
        assertEquals("a\\%\\_\\\\b", escapeSearchLikeLiteral("a%_\\b"))
    }

    /** 中文、引号与空格应原样传给绑定参数，不通过拼接 SQL 处理。 */
    @Test fun keepsOrdinaryTextUnchanged() {
        assertEquals("会议 '记录'", escapeSearchLikeLiteral("会议 '记录'"))
    }
}
