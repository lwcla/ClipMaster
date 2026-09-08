package com.cla.clip.feature.torrent.search.provider.builtin.common

import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** 为需要逐条补详情的内置来源提供结构化并发与稳定顺序，不持有网络或站点解析职责。 */
internal suspend fun <Draft> loadDetailDraftsInSourceOrder(
    /** 已完成必填字段与详情 URL 校验的列表草稿。 */
    drafts: List<Draft>,
    /** 由调用 Provider 读取并解析单条详情；空值只跳过当前草稿。 */
    loadDetails: suspend (Draft) -> TorrentDetails?,
    /** 由站点结果 parser 把草稿与详情映射为公开结果。 */
    merge: suspend (Draft, TorrentDetails) -> Torrent,
): List<Torrent> = coroutineScope {
    /** deferredResults 按来源列表顺序保存子任务，完成顺序不会改变最终结果顺序。 */
    val deferredResults = drafts.map { draft ->
        async {
            /** details 为当前草稿的完整详情；坏结构按来源契约仅跳当前项。 */
            val details = loadDetails(draft) ?: return@async null
            merge(draft, details)
        }
    }
    deferredResults.awaitAll().filterNotNull()
}
