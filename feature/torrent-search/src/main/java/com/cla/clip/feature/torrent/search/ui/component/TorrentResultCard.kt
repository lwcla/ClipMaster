package com.cla.clip.feature.torrent.search.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cla.clip.feature.torrent.search.R
import com.cla.clip.feature.torrent.search.model.contract.Torrent

/** 结果卡稳定展示模型，不携带 URL、magnet 或页面业务对象。 */
internal data class TorrentResultCardState(
    /** 稳定 infoHash，仅 identity，仅供列表 key 与后续事件页面动作关联。 */
    val infoHash: String,
    /** 两行内展示的结果名称。 */
    val name: String,
    /** 单行展示的来源名称。 */
    val providerName: String,
    /** 可选做种数。 */
    val seeders: UInt?,
    /** 可选连接节点数。 */
    val peers: UInt?,
    /** 可选短大小文本。 */
    val size: String?,
)

/** 把领域 Torrent 收窄为不含链接和磁力内容的卡片展示模型。 */
internal fun Torrent.toTorrentResultCardState(): TorrentResultCardState {
    return TorrentResultCardState(
        infoHash = infoHash,
        name = name,
        providerName = providerName,
        seeders = seeders,
        peers = peers,
        size = size,
    )
}

/** 搜索、浏览和本地数据页共用的 Torrent 结果卡，不依赖页面 ViewModel 或导航。 */
@Composable
internal fun TorrentResultCard(
    /** 已收窄且不含外部链接的展示状态。 */
    state: TorrentResultCardState,
    /** 调用方提供的外部布局约束。 */
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = state.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.providerName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                /** unknown 是数值缺失时统一使用的资源化展示值。 */
                val unknown = stringResource(R.string.torrent_search_value_unknown)
                Text(
                    text = stringResource(R.string.torrent_search_result_seeders, state.seeders?.toString() ?: unknown),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.torrent_search_result_peers, state.peers?.toString() ?: unknown),
                    style = MaterialTheme.typography.bodySmall,
                )
                state.size?.let { /** size 是 Provider 提供的短大小展示文本。 */ size ->
                    Text(
                        text = size,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 结果卡 Preview 覆盖长标题、来源和基础统计。 */
@Preview(showBackground = true)
@Composable
private fun TorrentResultCardPreview() {
    MaterialTheme {
        TorrentResultCard(
            state = TorrentResultCardState(
                infoHash = "0123456789012345678901234567890123456789",
                name = "A deliberately long synthetic torrent title for layout verification",
                size = "1.5 GiB",
                seeders = 42u,
                peers = 7u,
                providerName = "Fixture Provider",
            ),
            modifier = Modifier.padding(12.dp),
        )
    }
}
