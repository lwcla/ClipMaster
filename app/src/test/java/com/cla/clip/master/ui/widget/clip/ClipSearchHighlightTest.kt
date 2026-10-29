package com.cla.clip.master.ui.widget.clip

import com.cla.clip.base.general.search.ClipSearchTarget
import org.junit.Assert.assertEquals
import org.junit.Test

/** 搜索范围应同时约束数据与可见高亮，不能把同词出现在其他字段误标成命中原因。 */
class ClipSearchHighlightTest {
    /** 三种定向模式都只开放对应字段，提取链接只有综合模式可以标记。 */
    @Test fun targetOnlyHighlightsItsOwnField() {
        /** 手工定义业务映射，不复用生产规则生成预期值。 */
        val expected = mapOf(ClipSearchTarget.Content to ClipHighlightField.Content,
            ClipSearchTarget.LinkTitle to ClipHighlightField.Title, ClipSearchTarget.SourceName to ClipHighlightField.SourceName)
        expected.forEach { (target, allowed) ->
            // 对每种字段检查命中与排除，错误放宽会使此断言失败。
            ClipHighlightField.entries.forEach { field ->
                assertEquals(if (field == allowed) "会议" else null, ClipSearchHighlight("会议", target).queryFor(field))
            }
        }
    }

    /** 综合模式保持原有所有可见字段。 */
    @Test fun allFieldsKeepsEveryVisibleMatch() {
        ClipHighlightField.entries.forEach { field ->
            assertEquals("会议", ClipSearchHighlight("会议").queryFor(field))
        }
    }
}
