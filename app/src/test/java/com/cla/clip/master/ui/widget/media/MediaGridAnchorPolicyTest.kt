package com.cla.clip.master.ui.widget.media

import com.cla.clip.master.ad.AdListAnchor
import com.cla.clip.master.ad.AdListAnchorPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/** 首行合并布局必须保留全部图片身份，防止首图删除后滚动跳到下一行。 */
class MediaGridAnchorPolicyTest {
    /** 原首图删除，最近幸存图片仍属于当前首行，应映射到首行而非第五张。 */
    @Test fun deletedFirstImageKeepsNextSurvivorInFirstRow() {
        // 原顺序和像素位置来自真实业务项，不包含广告。
        val anchor = AdListAnchor(listOf("a", "b", "c", "d", "e", "f"), 0, 17)
        // 当前首行由 b、c、d、e 构成，f 在下一行。
        val current = listOf("b", "c", "d", "e", "f")
        // 先按全部业务身份解析最近邻，再映射物理 Lazy 行。
        val target = AdListAnchorPolicy.resolve(anchor, current)!!
        // 首行别名映射必须包含仍存活的第二张图片。
        val aliases = MediaGridAnchorPolicy.lazyKeyMap(current)
        assertEquals("b", aliases[current[target.index]])
        assertEquals(0, listOf<Any>("b", "f").indexOf(aliases[current[target.index]]))
        assertEquals(17, target.offset)
    }

    /** 首行逐张增长不会改变现有图片所属的行身份，后续图片保持独立身份。 */
    @Test fun shortFirstRowKeepsStableAliasesAsItGrows() {
        assertEquals(mapOf("a" to "a"), MediaGridAnchorPolicy.lazyKeyMap(listOf("a")))
        assertEquals(mapOf("a" to "a", "b" to "a", "c" to "a", "d" to "a", "e" to "e"),
            MediaGridAnchorPolicy.lazyKeyMap(listOf("a", "b", "c", "d", "e")))
        assertEquals(emptyMap<Any, Any>(), MediaGridAnchorPolicy.lazyKeyMap(emptyList()))
    }
}
