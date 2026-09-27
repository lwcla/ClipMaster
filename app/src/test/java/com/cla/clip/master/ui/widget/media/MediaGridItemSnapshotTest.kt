package com.cla.clip.master.ui.widget.media

import androidx.compose.runtime.mutableStateListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 保护 Lazy 延迟消费时的媒体与 key 一致性，回归候选追加触发的下标越界。 */
class MediaGridItemSnapshotTest {
    /** 捕获 14 张后追加第 15 张，旧快照仍能完整消费且新快照包含新增图片。 */
    @Test
    fun appendKeepsCapturedMediaAndKeysTogether() {
        // 使用与实时提取相同的 Compose 状态列表，合成候选不包含真实图片地址。
        val candidates = mutableStateListOf<Int>().also { it.addAll(1..14) }
        // 组合时捕获的条目将在候选更新之后才被 Lazy 消费。
        val captured = snapshotMediaGridItems(candidates) { it }
        candidates.add(15)

        assertEquals(14, captured.size)
        assertEquals((1..14).toList(), captured.map { it.media })
        assertEquals("media:14", captured.last().key)
        // 下一次组合应能看到新增项，而不是把快照永久缓存在列表对象身份上。
        val refreshed = snapshotMediaGridItems(candidates) { it }
        assertEquals(15, refreshed.size)
        assertEquals("media:15", refreshed.last().key)
        assertEquals(15, refreshed.last().media)
    }

    /** 上游清空并替换短首行后，旧快照不能误读新列表或访问已消失的下标。 */
    @Test
    fun clearAndReplaceDoesNotChangeCapturedItems() {
        // 三张图片覆盖旧首行合并路径，旧布局在清空时也可能读到过期下标。
        val candidates = mutableListOf("a", "b", "c")
        // 保留当前帧快照，模拟 Lazy 在上游写入之后执行内容回调。
        val captured = snapshotMediaGridItems(candidates) { it }
        candidates.clear()
        candidates.add("replacement")

        assertEquals(listOf("a", "b", "c"), captured.map { it.media })
        assertEquals(listOf("media:a", "media:b", "media:c"), captured.map { it.key })
        assertEquals("replacement", snapshotMediaGridItems(candidates) { it }.single().media)
    }

    /** 重排和删除首图后，图片身份跟随业务 key，不能沿用下标或首行公共身份。 */
    @Test
    fun reorderKeepsEachSurvivingMediaIdentity() {
        // 前后快照手工设置不同顺序，覆盖首图删除和同行图片重排。
        val before = snapshotMediaGridItems(listOf("a", "b", "c")) { it }
        val after = snapshotMediaGridItems(listOf("c", "b")) { it }

        assertEquals(listOf("media:c", "media:b"), after.map { it.key })
        assertEquals(before[2].key, after[0].key)
        assertEquals(before[1].key, after[1].key)
        assertEquals(listOf("c", "b"), after.map { it.media })
    }

    /** 空集合没有媒体身份，不调用只对真实图片有效的 key 提取器。 */
    @Test
    fun emptyContentDoesNotResolveMediaKeys() {
        // 任何 key 调用都代表凭空构造了媒体条目，应立即让测试失败。
        val captured = snapshotMediaGridItems(emptyList<String>()) { error("空集合不应读取图片 key") }
        assertTrue(captured.isEmpty())
    }
}
