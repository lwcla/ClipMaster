package com.cla.clip.master.ui.widget.clip

import androidx.paging.ItemSnapshotList
import com.cla.clip.base.general.entity.ClipShowEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** 共享剪贴分页快照去重规则测试，保护 Paging 切代重排时的重复 key 防御。 */
class ClipPagingSnapshotDeduperTest {

    @Test
    /** 同一快照里出现重复 clip.id 时，应只保留首次出现的真实索引。 */
    fun buildUniqueClipPagingRenderEntriesKeepsFirstOccurrenceOfDuplicateIds() {
        /** 模拟 Paging 当前代际里已经加载完成的剪贴快照。 */
        val snapshot = ItemSnapshotList(
            /* placeholdersBefore = */ 0,
            /* placeholdersAfter = */ 0,
            /* items = */ listOf(
                clip(id = 99L, content = "first-99"),
                clip(id = 42L, content = "unique-42"),
                clip(id = 99L, content = "duplicate-99"),
            ),
        )

        /** 去重后的渲染索引；重复 id 只应留下第一次出现的位置。 */
        val entries = buildUniqueClipPagingRenderEntries(snapshot)

        assertEquals(
            listOf(
                ClipPagingRenderEntry(sourceIndex = 0, clipId = 99L),
                ClipPagingRenderEntry(sourceIndex = 1, clipId = 42L),
            ),
            entries,
        )
    }

    @Test
    /** 当前快照前面存在占位时，去重结果仍要映射回 LazyPagingItems 的真实源索引。 */
    fun buildUniqueClipPagingRenderEntriesAddsPlaceholderOffsetBackToSourceIndex() {
        /** 模拟当前已加载页之前还留有未加载占位的分页快照。 */
        val snapshot = ItemSnapshotList(
            /* placeholdersBefore = */ 5,
            /* placeholdersAfter = */ 2,
            /* items = */ listOf(
                clip(id = 7L, content = "first"),
                clip(id = 8L, content = "second"),
            ),
        )

        /** 去重后的真实源索引应该回加 placeholdersBefore，确保后续 peek 仍能命中正确位置。 */
        val entries = buildUniqueClipPagingRenderEntries(snapshot)

        assertEquals(
            listOf(
                ClipPagingRenderEntry(sourceIndex = 5, clipId = 7L),
                ClipPagingRenderEntry(sourceIndex = 6, clipId = 8L),
            ),
            entries,
        )
    }

    /** 构造最小 UI 剪贴实体，只保留当前去重规则真正依赖的字段。 */
    private fun clip(
        id: Long,
        content: String,
    ): ClipShowEntity {
        return ClipShowEntity(
            id = id,
            content = content,
            timestamp = 0L,
            foldedAt = 0L,
            deletedAt = 0L,
            formattedTime = "just now",
            appName = null,
            appIconPath = null,
            appIconHash = null,
            appColor = null,
            isPinned = false,
            isFolded = false,
            linkImgUrl = null,
            link = null,
            linkTitle = null,
        )
    }
}
